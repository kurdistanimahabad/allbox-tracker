package com.example.allboxtracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlin.math.abs

object DiscountEngine {

    private const val BASE_URL = "https://api-allbox.surenamarkets.com/listing/products"
    private const val CATEGORIES_URL_1 = "https://api-allbox.surenamarkets.com/listing/categories"
    private const val CATEGORIES_URL_2 = "https://api-allbox.surenamarkets.com/categories"
    private const val LAT = "36.774354703804"
    private const val LNG = "45.747235942022"

    // جلوگیری از تداخل هم‌زمان در صورتی که ساعت دستی و تایمر دوره‌ای در یک ثانیه اجرا شوند
    private val checkMutex = Mutex()

    fun syncAndGetCookies(context: Context): String {
        val prefs = context.getSharedPreferences("AllBoxPrefs", Context.MODE_PRIVATE)
        var liveCookies = ""
        try {
            val cookieManager = CookieManager.getInstance()
            val apiCookies = cookieManager.getCookie("https://api-allbox.surenamarkets.com") ?: ""
            val mainCookies = cookieManager.getCookie("https://allbox.surenamarkets.com") ?: ""
            liveCookies = listOf(apiCookies, mainCookies).filter { it.isNotEmpty() }.joinToString("; ")
        } catch (_: Exception) {
        }

        if (liveCookies.contains("jptrsessid") || liveCookies.isNotEmpty()) {
            prefs.edit().putString("SAVED_COOKIES", liveCookies).apply()
            return liveCookies
        }
        return prefs.getString("SAVED_COOKIES", "") ?: ""
    }

    suspend fun checkPricesNow(
        context: Context,
        isManual: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            try {
                val combinedCookies = syncAndGetCookies(context)
                val prefs = context.getSharedPreferences("AllBoxPrefs", Context.MODE_PRIVATE)

                val collectedMap = Collections.synchronizedMap(LinkedHashMap<Int, JSONObject>())
                val discoveredSubCategories = Collections.synchronizedSet(mutableSetOf<Int>())
                val categoryNamesMap = Collections.synchronizedMap(mutableMapOf<Int, String>())

                fetchServerCategories(combinedCookies, categoryNamesMap)

                fun addProductsFromList(items: JSONArray) {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optInt("id", -1)
                        if (id != -1 && !collectedMap.containsKey(id)) {
                            collectedMap[id] = item
                            val catObj = item.optJSONObject("category")
                            if (catObj != null) {
                                val subId = catObj.optInt("id", -1)
                                val subName = catObj.optString("name", "")
                                if (subId > 0 && subName.isNotEmpty()) {
                                    categoryNamesMap[subId] = subName
                                }
                                val parentObj = catObj.optJSONObject("parent")
                                if (parentObj != null) {
                                    val pId = parentObj.optInt("id", -1)
                                    val pName = parentObj.optString("name", "")
                                    if (pId > 0 && pName.isNotEmpty()) {
                                        categoryNamesMap[pId] = pName
                                    }
                                }
                                val parentId = if (catObj.isNull("parentId")) 0 else catObj.optInt("parentId", 0)
                                if (subId > 0) discoveredSubCategories.add(subId)
                                if (parentId > 0) discoveredSubCategories.add(parentId)
                            }
                        }
                    }
                }

                onProgress?.invoke("⏳ در حال اتصال به سرور آل‌باکس...")
                val initialItems = fetchAllPagesForQuery("", combinedCookies) {
                    onProgress?.invoke("⏳ دریافت لیست عمومی... (${collectedMap.size} کالا)")
                }

                if (initialItems == null) {
                    val msg = "نشست کاربری شما منقضی شده است. لطفاً از تب «ورود» دوباره وارد حساب شوید."
                    if (!isManual) sendNotification(context, "⚠️ نیاز به ورود مجدد", msg, isMajorAlert = false)
                    return@withLock Pair(false, msg)
                }
                addProductsFromList(initialItems)

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
                        onProgress?.invoke("⏳ اسکن دسته‌بندی‌ها... (${collectedMap.size} کالا پیدا شد)")
                    }

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
                    return@withLock Pair(false, "هیچ محصولی دریافت نشد. ابتدا از تب «ورود» وارد حساب خود شوید.")
                }

                val parentToSubNames = mutableMapOf<Int, MutableSet<String>>()
                for ((_, item) in collectedMap) {
                    val catObj = item.optJSONObject("category") ?: continue
                    val subName = catObj.optString("name", "سایر")
                    val parentId = if (catObj.isNull("parentId")) 0 else catObj.optInt("parentId", 0)
                    if (parentId > 0) {
                        parentToSubNames.getOrPut(parentId) { mutableSetOf() }.add(subName)
                    }
                }

                val snapshotFile = File(context.filesDir, "products_snapshot.json")
                val oldSnapshot = if (snapshotFile.exists()) JSONObject(snapshotFile.readText()) else JSONObject()
                val isFirstRun = oldSnapshot.length() == 0
                val newSnapshot = JSONObject()

                val historyFile = File(context.filesDir, "changes_history.json")
                val oldHistoryArray = if (historyFile.exists()) JSONArray(historyFile.readText()) else JSONArray()
                val newlyDetectedRecords = mutableListOf<JSONObject>()

                val currentTime = SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.getDefault()).format(Date())
                val regularNotificationLines = mutableListOf<String>()
                val majorNotificationLines = mutableListOf<String>()
                val numFormat = NumberFormat.getNumberInstance(Locale.US)

                var newProductsCount = 0
                var discountUpCount = 0
                var discountDownCount = 0
                var retailPriceChangeCount = 0
                var majorDiscountCount = 0
                var popularityRank = 1

                for ((idInt, item) in collectedMap) {
                    val id = idInt.toString()
                    val rawName = item.optString("name", "کالای کد $id")
                    val catObj = item.optJSONObject("category")
                    val subCategoryName = catObj?.optString("name", "سایر") ?: "سایر"
                    val parentId = if (catObj == null || catObj.isNull("parentId")) 0 else catObj.optInt("parentId", 0)

                    val mainCategoryName = resolveMainCategoryName(
                        parentId = parentId,
                        subCategoryName = subCategoryName,
                        categoryNamesMap = categoryNamesMap,
                        subNamesInGroup = parentToSubNames[parentId] ?: emptySet()
                    )

                    val brandName = item.optJSONObject("brand")?.optString("name", "") ?: ""
                    val fullName = "[$subCategoryName] $rawName"

                    val newDiscount = extractDiscount(item)
                    val newPrice = item.optLong("unitDiscountedPrice", item.optLong("boxDiscountedPrice", 0L))
                    val newRetailPrice = item.optLong("unitRetailPrice", item.optLong("boxRetailPrice", 0L))
                    val stock = item.optInt("purchasableQuantity", 0)
                    val unitInBox = item.optInt("unitInBox", 1)
                    val isPromoted = item.optBoolean("isPromoted", false)
                    val salesScore = item.optInt("salesCount", item.optInt("orderCount", item.optInt("popularity", 0)))

                    val prodObj = JSONObject().apply {
                        put("id", idInt)
                        put("rawName", rawName)
                        put("name", fullName)
                        put("mainCategory", mainCategoryName)
                        put("category", subCategoryName)
                        put("parentId", parentId)
                        put("brand", brandName)
                        put("discount", newDiscount)
                        put("price", newPrice)
                        put("retailPrice", newRetailPrice)
                        put("stock", stock)
                        put("unitInBox", unitInBox)
                        put("isPromoted", isPromoted)
                        put("salesScore", salesScore)
                        put("rank", popularityRank++)
                    }
                    newSnapshot.put(id, prodObj)

                    // بررسی تغییرات فقط برای کالاهایی که موجودی آن‌ها صفر نباشد (stock > 0)
                    if (oldSnapshot.has(id)) {
                        if (stock > 0) {
                            val oldProd = oldSnapshot.getJSONObject(id)
                            val oldDiscount = oldProd.optInt("discount", 0)
                            val oldPrice = oldProd.optLong("price", 0L)
                            val oldRetailPrice = oldProd.optLong("retailPrice", newRetailPrice)

                            val isRetailChanged = oldRetailPrice > 0 && newRetailPrice > 0 && oldRetailPrice != newRetailPrice
                            val isDiscountOrBuyChanged = oldDiscount != newDiscount || oldPrice != newPrice

                            // ۱. اولویت با تغییر قیمت مصرف‌کننده (بدون ارسال اعلان تغییر درصد تخفیف)
                            if (isRetailChanged) {
                                retailPriceChangeCount++
                                val isRetailIncreased = newRetailPrice > oldRetailPrice
                                val changeType = if (isRetailIncreased) "RETAIL_PRICE_UP" else "RETAIL_PRICE_DOWN"
                                val badge = if (isRetailIncreased) "📈 افزایش قیمت مصرف‌کننده" else "📉 کاهش قیمت مصرف‌کننده"

                                val changeRecord = JSONObject().apply {
                                    put("type", changeType)
                                    put("name", fullName)
                                    put("mainCategory", mainCategoryName)
                                    put("category", subCategoryName)
                                    put("oldDiscount", oldDiscount)
                                    put("newDiscount", newDiscount)
                                    put("oldPrice", oldPrice)
                                    put("newPrice", newPrice)
                                    put("oldRetailPrice", oldRetailPrice)
                                    put("newRetailPrice", newRetailPrice)
                                    put("time", currentTime)
                                    put("isBetter", !isRetailIncreased)
                                    put("isMajorChange", false)
                                    put("discountDiff", 0)
                                }
                                newlyDetectedRecords.add(changeRecord)
                                regularNotificationLines.add(
                                    "$badge | $fullName: از ${numFormat.format(oldRetailPrice)} به ${numFormat.format(newRetailPrice)} ریال (خرید: ${numFormat.format(newPrice)} ریال)"
                                )
                            }
                            // ۲. در صورتی که قیمت مصرف‌کننده ثابت بوده و درصد تخفیف یا قیمت خرید تغییر کرده باشد
                            else if (isDiscountOrBuyChanged) {
                                val discountDiff = abs(newDiscount - oldDiscount)
                                val isMajor = discountDiff > 20
                                val isDiscountIncreased = newDiscount > oldDiscount || (newDiscount == oldDiscount && newPrice < oldPrice)

                                val changeType: String
                                val badge: String
                                if (isDiscountIncreased) {
                                    discountUpCount++
                                    changeType = "DISCOUNT_UP"
                                    badge = if (isMajor) "🔥 افزایش ویژه تخفیف (+$discountDiff%)" else "🟢 افزایش درصد تخفیف"
                                } else {
                                    discountDownCount++
                                    changeType = "DISCOUNT_DOWN"
                                    badge = if (isMajor) "⚠️ کاهش شدید تخفیف (-$discountDiff%)" else "🔴 کاهش درصد تخفیف"
                                }

                                val changeRecord = JSONObject().apply {
                                    put("type", changeType)
                                    put("name", fullName)
                                    put("mainCategory", mainCategoryName)
                                    put("category", subCategoryName)
                                    put("oldDiscount", oldDiscount)
                                    put("newDiscount", newDiscount)
                                    put("oldPrice", oldPrice)
                                    put("newPrice", newPrice)
                                    put("oldRetailPrice", oldRetailPrice)
                                    put("newRetailPrice", newRetailPrice)
                                    put("time", currentTime)
                                    put("isBetter", isDiscountIncreased)
                                    put("isMajorChange", isMajor)
                                    put("discountDiff", discountDiff)
                                }
                                newlyDetectedRecords.add(changeRecord)

                                val lineText = "$badge | $fullName: از $oldDiscount% به $newDiscount% | خرید: ${numFormat.format(newPrice)} | مصرف‌کننده: ${numFormat.format(newRetailPrice)} ریال"
                                if (isMajor) {
                                    majorDiscountCount++
                                    majorNotificationLines.add(lineText)
                                } else {
                                    regularNotificationLines.add(lineText)
                                }
                            }
                        }
                    } else if (!isFirstRun && stock > 0) {
                        newProductsCount++
                        val newProductRecord = JSONObject().apply {
                            put("type", "NEW_PRODUCT")
                            put("name", fullName)
                            put("mainCategory", mainCategoryName)
                            put("category", subCategoryName)
                            put("oldDiscount", 0)
                            put("newDiscount", newDiscount)
                            put("oldPrice", newRetailPrice)
                            put("newPrice", newPrice)
                            put("oldRetailPrice", newRetailPrice)
                            put("newRetailPrice", newRetailPrice)
                            put("time", currentTime)
                            put("isBetter", true)
                            put("isMajorChange", newDiscount > 20)
                            put("discountDiff", newDiscount)
                        }
                        newlyDetectedRecords.add(newProductRecord)
                        regularNotificationLines.add(
                            "🆕 کالای جدید: $fullName (تخفیف: $newDiscount% | خرید: ${numFormat.format(newPrice)} | مصرف‌کننده: ${numFormat.format(newRetailPrice)} ریال)"
                        )
                    }
                }

                snapshotFile.writeText(newSnapshot.toString())
                prefs.edit().putString("LAST_CHECK_TIME", "$currentTime (${newSnapshot.length()} کالا)").apply()

                if (isFirstRun) {
                    return@withLock Pair(true, "لیست کامل شامل ${newSnapshot.length()} کالا ذخیره شد!")
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
                    if (retailPriceChangeCount > 0) summaryParts.add("$retailPriceChangeCount تغییر قیمت مصرف‌کننده")
                    if (newProductsCount > 0) summaryParts.add("$newProductsCount کالای جدید")
                    val summaryText = summaryParts.joinToString("، ")

                    // ۱. ارسال نوتیفیکیشن کاملاً مجزا برای تغییرات تخفیف بیشتر از ۲۰ درصد
                    if (majorNotificationLines.isNotEmpty()) {
                        sendNotification(
                            context,
                            "🚨 تغییر ویژه تخفیف (بیشتر از ۲۰٪) در $majorDiscountCount کالا!",
                            majorNotificationLines.take(10).joinToString("\n"),
                            isMajorAlert = true
                        )
                    }

                    // ۲. ارسال نوتیفیکیشن برای سایر تغییرات (تخفیف‌های عادی، قیمت مصرف‌کننده، کالای جدید)
                    if (regularNotificationLines.isNotEmpty()) {
                        sendNotification(
                            context,
                            "🔔 تغییرات آل‌باکس: $summaryText",
                            regularNotificationLines.take(10).joinToString("\n"),
                            isMajorAlert = false
                        )
                    }

                    return@withLock Pair(true, "بروزرسانی انجام شد (${newSnapshot.length()} کالا): $summaryText ثبت شد.")
                }

                return@withLock Pair(true, "بروزرسانی برای ${newSnapshot.length()} کالا انجام شد. تغییر جدیدی در کالاهای موجود نبود.")
            } catch (e: Exception) {
                e.printStackTrace()
                return@withLock Pair(false, "خطا در ارتباط با سرور: ${e.localizedMessage}")
            }
        }
    }

    private fun resolveMainCategoryName(
        parentId: Int,
        subCategoryName: String,
        categoryNamesMap: Map<Int, String>,
        subNamesInGroup: Set<String>
    ): String {
        if (parentId <= 0) return subCategoryName
        categoryNamesMap[parentId]?.let { if (it.isNotEmpty()) return it }

        val joined = subNamesInGroup.joinToString(" ")
        return when {
            joined.contains("روغن") || joined.contains("برنج") || joined.contains("ماکارونی") || joined.contains("رب") || joined.contains("حبوبات") || joined.contains("قند") || joined.contains("شکر") -> "خواروبار و نان"
            joined.contains("شیر") || joined.contains("پنیر") || joined.contains("ماست") || joined.contains("کره") || joined.contains("خامه") || joined.contains("دوغ") -> "لبنیات و بستنی"
            joined.contains("نوشابه") || joined.contains("آبمیوه") || joined.contains("چای") || joined.contains("قهوه") || joined.contains("آب معدنی") || joined.contains("شربت") -> "نوشیدنی و چای"
            joined.contains("مایع") || joined.contains("پودر") || joined.contains("دستمال") || joined.contains("شامپو") || joined.contains("صابون") || joined.contains("سفید کننده") || joined.contains("شوینده") -> "شوینده و بهداشتی"
            joined.contains("کیک") || joined.contains("بیسکویت") || joined.contains("شکلات") || joined.contains("چیپس") || joined.contains("پفک") || joined.contains("آدامس") || joined.contains("ویفر") -> "تنقلات و شیرینی‌جات"
            joined.contains("سوسیس") || joined.contains("کالباس") || joined.contains("مرغ") || joined.contains("گوشت") || joined.contains("تخم مرغ") || joined.contains("همبرگر") -> "پروتئینی و گوشتی"
            joined.contains("سس") || joined.contains("ترشی") || joined.contains("آبلیمو") || joined.contains("ادویه") || joined.contains("زیتون") -> "چاشنی و افزودنی"
            joined.contains("کنسرو") || joined.contains("تن ماهی") || joined.contains("کمپوت") -> "کنسرو و غذای آماده"
            else -> {
                val sample = subNamesInGroup.take(2).joinToString("، ")
                if (sample.isNotEmpty()) "دسته اصلی $parentId ($sample...)" else "دسته اصلی $parentId"
            }
        }
    }

    private fun fetchServerCategories(cookies: String, outMap: MutableMap<Int, String>) {
        val urls = listOf(
            "$CATEGORIES_URL_1?latitude=$LAT&longitude=$LNG",
            "$CATEGORIES_URL_2?latitude=$LAT&longitude=$LNG"
        )
        for (u in urls) {
            try {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("accept", "application/json, text/plain, */*")
                    setRequestProperty("origin", "https://allbox.surenamarkets.com")
                    setRequestProperty("referer", "https://allbox.surenamarkets.com/")
                    setRequestProperty("x-auth-scope", "client")
                    if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookies)
                }
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (text.startsWith("[")) {
                        parseCategoryTree(JSONArray(text), outMap)
                    } else if (text.startsWith("{")) {
                        val obj = JSONObject(text)
                        val arr = obj.optJSONArray("categories") ?: obj.optJSONArray("data") ?: obj.optJSONArray("items")
                        if (arr != null) parseCategoryTree(arr, outMap)
                    }
                    if (outMap.isNotEmpty()) break
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun parseCategoryTree(arr: JSONArray, outMap: MutableMap<Int, String>) {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            val name = item.optString("name", item.optString("title", ""))
            if (id > 0 && name.isNotEmpty()) {
                outMap[id] = name
            }
            val children = item.optJSONArray("children") ?: item.optJSONArray("subCategories") ?: item.optJSONArray("categories")
            if (children != null) {
                parseCategoryTree(children, outMap)
            }
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

    fun getHistoryList(context: Context): List<JSONObject> {
        val historyFile = File(context.filesDir, "changes_history.json")
        if (!historyFile.exists()) return emptyList()
        val arr = JSONArray(historyFile.readText())
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(it) }
        }
        return list
    }

    fun clearHistory(context: Context) {
        val historyFile = File(context.filesDir, "changes_history.json")
        if (historyFile.exists()) historyFile.delete()
    }

    private fun sendNotification(context: Context, title: String, message: String, isMajorAlert: Boolean) {
        val channelId = if (isMajorAlert) "allbox_major_discount_channel" else "allbox_discount_channel"
        val channelName = if (isMajorAlert) "تغییرات ویژه تخفیف (بیشتر از ۲۰٪)" else "تغییرات و کالاهای جدید آل‌باکس"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            if (isMajorAlert) 1 else 0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(if (isMajorAlert) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notifId = (System.currentTimeMillis() % 1000000).toInt() + (if (isMajorAlert) 500000 else 0)
        manager.notify(notifId, notification)
    }
}
