package com.example.allboxtracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object DiscountEngine {

    private const val BASE_URL = "https://api-allbox.surenamarkets.com/listing/products"
    private const val LAT = "36.774354703804"
    private const val LNG = "45.747235942022"

    suspend fun checkPricesNow(
        context: Context,
        isManual: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val cookieManager = CookieManager.getInstance()
            val apiCookies = cookieManager.getCookie("https://api-allbox.surenamarkets.com") ?: ""
            val mainCookies = cookieManager.getCookie("https://allbox.surenamarkets.com") ?: ""
            val combinedCookies = listOf(apiCookies, mainCookies).filter { it.isNotEmpty() }.joinToString("; ")

            val prefs = context.getSharedPreferences("AllBoxPrefs", Context.MODE_PRIVATE)
            val collectedMap = Collections.synchronizedMap(LinkedHashMap<Int, JSONObject>())
            val discoveredSubCategories = Collections.synchronizedSet(mutableSetOf<Int>())

            fun addProductsFromList(items: JSONArray) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val id = item.optInt("id", -1)
                    if (id != -1 && !collectedMap.containsKey(id)) {
                        collectedMap[id] = item
                        val catObj = item.optJSONObject("category")
                        if (catObj != null) {
                            val subId = catObj.optInt("id", -1)
                            val parentId = catObj.optInt("parentId", -1)
                            if (subId > 0) discoveredSubCategories.add(subId)
                            if (parentId > 0) discoveredSubCategories.add(parentId)
                        }
                    }
                }
            }

            // ۱. دریافت لیست اصلی (که به صورت پیش‌فرض بر اساس محبوب‌ترین‌های سایت چیده شده است)
            onProgress?.invoke("⏳ در حال اتصال به سرور آل‌باکس...")
            val initialItems = fetchAllPagesForQuery("", combinedCookies) {
                onProgress?.invoke("⏳ دریافت لیست عمومی... (${collectedMap.size} کالا)")
            }

            if (initialItems == null) {
                val msg = "نشست کاربری شما منقضی شده است. لطفاً از تب «ورود» دوباره وارد حساب شوید."
                if (!isManual) sendNotification(context, "⚠️ نیاز به ورود مجدد", msg)
                return@withContext Pair(false, msg)
            }
            addProductsFromList(initialItems)

            // ۲. اسکن کامل تمام دسته‌بندی‌های اصلی سایت
            val mainCategoryIds = (1..60).toList()
            val scannedCategories = mutableSetOf<Int>()

            coroutineScope {
                for (chunk in mainCategoryIds.chunked(6)) {
                    val deferredList = chunk.map { catId ->
                        scannedCategories.add(catId)
                        async {
                            fetchAllPagesForQuery("&categories%5B%5D=$catId", combinedCookies, null)
                        }
                    }
                    val results = deferredList.awaitAll()
                    results.forEach { arr ->
                        if (arr != null) addProductsFromList(arr)
                    }
                    onProgress?.invoke("⏳ در حال اسکن دسته‌بندی‌ها... (${collectedMap.size} کالا پیدا شد)")
                }

                // ۳. اسکن زیرمجموعه‌های کشف‌شده
                val remainingSubCats = discoveredSubCategories.filter { it !in scannedCategories }
                for (chunk in remainingSubCats.chunked(6)) {
                    val deferredList = chunk.map { subCatId ->
                        scannedCategories.add(subCatId)
                        async {
                            fetchAllPagesForQuery("&categories%5B%5D=$subCatId", combinedCookies, null)
                        }
                    }
                    val results = deferredList.awaitAll()
                    results.forEach { arr ->
                        if (arr != null) addProductsFromList(arr)
                    }
                    onProgress?.invoke("⏳ تکمیل زیرمجموعه‌ها... (${collectedMap.size} کالا پیدا شد)")
                }
            }

            if (collectedMap.isEmpty()) {
                return@withContext Pair(false, "هیچ محصولی دریافت نشد. ابتدا از تب «ورود» وارد حساب خود شوید.")
            }

            // ۴. مقایسه با اطلاعات قبلی و ثبت تغییرات
            val snapshotFile = File(context.filesDir, "products_snapshot.json")
            val oldSnapshot = if (snapshotFile.exists()) JSONObject(snapshotFile.readText()) else JSONObject()
            val isFirstRun = oldSnapshot.length() == 0
            val newSnapshot = JSONObject()

            val historyFile = File(context.filesDir, "changes_history.json")
            val oldHistoryArray = if (historyFile.exists()) JSONArray(historyFile.readText()) else JSONArray()
            val newlyDetectedRecords = mutableListOf<JSONObject>()

            val currentTime = SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.getDefault()).format(Date())
            val notificationLines = mutableListOf<String>()
            val numFormat = NumberFormat.getNumberInstance(Locale.US)

            var newProductsCount = 0
            var discountUpCount = 0
            var discountDownCount = 0
            var popularityRank = 1

            for ((idInt, item) in collectedMap) {
                val id = idInt.toString()
                val rawName = item.optString("name", "کالای کد $id")
                val categoryName = item.optJSONObject("category")?.optString("name", "سایر") ?: "سایر"
                val brandName = item.optJSONObject("brand")?.optString("name", "") ?: ""
                val fullName = "[$categoryName]$rawName"

                val newDiscount = extractDiscount(item)
                val newPrice = item.optLong("unitDiscountedPrice", item.optLong("boxDiscountedPrice", 0L))
                val retailPrice = item.optLong("unitRetailPrice", item.optLong("boxRetailPrice", 0L))
                val stock = item.optInt("purchasableQuantity", 0)
                val unitInBox = item.optInt("unitInBox", 1)
                val isPromoted = item.optBoolean("isPromoted", false)
                val salesScore = item.optInt("salesCount", item.optInt("orderCount", item.optInt("popularity", 0)))

                val prodObj = JSONObject().apply {
                    put("id", idInt)
                    put("rawName", rawName)
                    put("name", fullName)
                    put("category", categoryName)
                    put("brand", brandName)
                    put("discount", newDiscount)
                    put("price", newPrice)
                    put("retailPrice", retailPrice)
                    put("stock", stock)
                    put("unitInBox", unitInBox)
                    put("isPromoted", isPromoted)
                    put("salesScore", salesScore)
                    put("rank", popularityRank++)
                }
                newSnapshot.put(id, prodObj)

                if (oldSnapshot.has(id)) {
                    val oldProd = oldSnapshot.getJSONObject(id)
                    val oldDiscount = oldProd.optInt("discount", 0)
                    val oldPrice = oldProd.optLong("price", 0L)

                    if (oldDiscount != newDiscount || oldPrice != newPrice) {
                        val isDiscountIncreased = newDiscount > oldDiscount || (newDiscount == oldDiscount && newPrice < oldPrice)

                        val changeType: String
                        val badge: String
                        if (newDiscount > oldDiscount) {
                            discountUpCount++
                            changeType = "DISCOUNT_UP"
                            badge = "🟢 افزایش تخفیف"
                        } else if (newDiscount < oldDiscount) {
                            discountDownCount++
                            changeType = "DISCOUNT_DOWN"
                            badge = "🔴 کاهش تخفیف"
                        } else if (newPrice < oldPrice) {
                            discountUpCount++
                            changeType = "DISCOUNT_UP"
                            badge = "🟢 کاهش قیمت خرید"
                        } else {
                            discountDownCount++
                            changeType = "DISCOUNT_DOWN"
                            badge = "🔴 افزایش قیمت خرید"
                        }

                        val changeRecord = JSONObject().apply {
                            put("type", changeType)
                            put("name", fullName)
                            put("category", categoryName)
                            put("oldDiscount", oldDiscount)
                            put("newDiscount", newDiscount)
                            put("oldPrice", oldPrice)
                            put("newPrice", newPrice)
                            put("time", currentTime)
                            put("isBetter", isDiscountIncreased)
                        }
                        newlyDetectedRecords.add(changeRecord)
                        notificationLines.add("$badge | $fullName: از $oldDiscount% به $newDiscount\% (${numFormat.format(newPrice)} ریال)")
                    }
                } else if (!isFirstRun) {
                    newProductsCount++
                    val newProductRecord = JSONObject().apply {
                        put("type", "NEW_PRODUCT")
                        put("name", fullName)
                        put("category", categoryName)
                        put("oldDiscount", 0)
                        put("newDiscount", newDiscount)
                        put("oldPrice", retailPrice)
                        put("newPrice", newPrice)
                        put("time", currentTime)
                        put("isBetter", true)
                    }
                    newlyDetectedRecords.add(newProductRecord)
                    notificationLines.add("🆕 کالای جدید: $fullName (تخفیف: $newDiscount\% \vert{} خرید: ${numFormat.format(newPrice)} ریال)")
                }
            }

            snapshotFile.writeText(newSnapshot.toString())
            prefs.edit().putString("LAST_CHECK_TIME", "$currentTime (${newSnapshot.length()} کالا)").apply()

            if (isFirstRun) {
                return@withContext Pair(true, "لیست کامل شامل ${newSnapshot.length()} کالا ذخیره شد! اکنون می‌توانید همه کالاها را در تب «همه محصولات» ببینید.")
            }

            if (newlyDetectedRecords.isNotEmpty()) {
                val updatedHistory = JSONArray()
                for (record in newlyDetectedRecords) updatedHistory.put(record)
                for (j in 0 until minOf(oldHistoryArray.length(), 600 - newlyDetectedRecords.size)) {
                    updatedHistory.put(oldHistoryArray.getJSONObject(j))
                }
                historyFile.writeText(updatedHistory.toString())

                val summaryParts = mutableListOf<String>()
                if (discountUpCount > 0) summaryParts.add("$discountUpCount افزایش تخفیف")
                if (discountDownCount > 0) summaryParts.add("$discountDownCount کاهش تخفیف")
                if (newProductsCount > 0) summaryParts.add("$newProductsCount کالای جدید")
                val summaryText = summaryParts.joinToString("، ")

                sendNotification(
                    context,
                    "🔔 تغییرات آل‌باکس: $summaryText",
                    notificationLines.take(12).joinToString("\n")
                )
                return@withContext Pair(true, "بروزرسانی انجام شد (${newSnapshot.length()} کالا): $summaryText ثبت شد.")
            }

            return@withContext Pair(true, "بروزرسانی برای ${newSnapshot.length()} کالا انجام شد. تغییر جدیدی وجود نداشت.")
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Pair(false, "خطا در ارتباط با سرور: ${e.localizedMessage}")
        }
    }

    private fun extractDiscount(item: JSONObject): Int {
        if (item.has("totalDiscount") && !item.isNull("totalDiscount")) {
            return item.optDouble("totalDiscount", 0.0).toInt()
        }
        val steps = item.optJSONArray("stepDiscounts")
        if (steps != null && steps.length() > 0) {
            return steps.getJSONObject(0).optDouble("percentage", 0.0).toInt()
        }
        return 0
    }

    private fun fetchAllPagesForQuery(
        extraQuery: String,
        cookies: String,
        onPageFetched: (() -> Unit)?
    ): JSONArray? {
        val all = JSONArray()
        val seenInQuery = mutableSetOf<Int>()

        val firstUrl100 = "$BASE_URL?limit=100&latitude=$LAT&longitude=$LNG$extraQuery"
        var firstPage = fetchSingleUrl(firstUrl100, cookies)
        var limitUsed = 100

        if (firstPage == null) return null

        if (firstPage.length() == 0) {
            val firstUrl50 = "$BASE_URL?limit=50&latitude=$LAT&longitude=$LNG$extraQuery"
            firstPage = fetchSingleUrl(firstUrl50, cookies) ?: return null
            limitUsed = 50
        }

        for (i in 0 until firstPage.length()) {
            val obj = firstPage.getJSONObject(i)
            val id = obj.optInt("id", -1)
            if (id != -1 && seenInQuery.add(id)) all.put(obj)
        }
        onPageFetched?.invoke()

        if (firstPage.length() < 20) return all

        var page = 2
        var useOffsetMode = false

        while (page <= 25) {
            val offset = (page - 1) * firstPage.length()
            val nextUrl = if (!useOffsetMode) {
                "$BASE_URL?limit=$limitUsed&page=$page&latitude=$LAT&longitude=$LNG$extraQuery"
            } else {
                "$BASE_URL?limit=$limitUsed&offset=$offset&latitude=$LAT&longitude=$LNG$extraQuery"
            }

            val nextItems = fetchSingleUrl(nextUrl, cookies) ?: break
            if (nextItems.length() == 0) break

            var addedNew = 0
            for (i in 0 until nextItems.length()) {
                val obj = nextItems.getJSONObject(i)
                val id = obj.optInt("id", -1)
                if (id != -1 && seenInQuery.add(id)) {
                    all.put(obj)
                    addedNew++
                }
            }

            if (addedNew == 0) {
                if (!useOffsetMode) {
                    useOffsetMode = true
                    continue
                } else {
                    break
                }
            }

            onPageFetched?.invoke()
            if (nextItems.length() < 20) break
            page++
        }

        return all
    }

    private fun fetchSingleUrl(pageUrl: String, cookies: String): JSONArray? {
        val connection = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("accept", "application/json, text/plain, */*")
            setRequestProperty("origin", "https://allbox.surenamarkets.com")
            setRequestProperty("referer", "https://allbox.surenamarkets.com/")
            setRequestProperty("x-auth-scope", "client")
            setRequestProperty("user-agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/155.0.0.0 Mobile Safari/537.36")
            if (cookies.isNotEmpty()) {
                setRequestProperty("Cookie", cookies)
            }
        }
        val code = connection.responseCode
        if (code == 401 || code == 403) return null
        if (code != 200) return JSONArray()

        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        return json.optJSONArray("products") ?: JSONArray()
    }

    fun getAllProductsList(context: Context): List<JSONObject> {
        val snapshotFile = File(context.filesDir, "products_snapshot.json")
        if (!snapshotFile.exists()) return emptyList()
        val json = JSONObject(snapshotFile.readText())
        val list = mutableListOf<JSONObject>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            json.optJSONObject(k)?.let { list.add(it) }
        }
        return list
    }

    fun getHistoryList(context: Context): JSONArray {
        val historyFile = File(context.filesDir, "changes_history.json")
        return if (historyFile.exists()) JSONArray(historyFile.readText()) else JSONArray()
    }

    fun clearHistory(context: Context) {
        val historyFile = File(context.filesDir, "changes_history.json")
        if (historyFile.exists()) historyFile.delete()
    }

    private fun sendNotification(context: Context, title: String, message: String) {
        val channelId = "allbox_discount_channel"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "تغییرات و کالاهای جدید آل‌باکس", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
