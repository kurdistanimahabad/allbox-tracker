package com.example.allboxtracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiscountEngine {

    private const val BASE_URL = "https://api-allbox.surenamarkets.com/listing/products"
    private const val LAT = "36.774354703804"
    private const val LNG = "45.747235942022"

    suspend fun checkPricesNow(context: Context, isManual: Boolean = false): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val cookieManager = CookieManager.getInstance()
            val apiCookies = cookieManager.getCookie("https://api-allbox.surenamarkets.com") ?: ""
            val mainCookies = cookieManager.getCookie("https://allbox.surenamarkets.com") ?: ""
            val combinedCookies = listOf(apiCookies, mainCookies).filter { it.isNotEmpty() }.joinToString("; ")

            val prefs = context.getSharedPreferences("AllBoxPrefs", Context.MODE_PRIVATE)
            val allProducts = JSONArray()
            val seenIds = mutableSetOf<Int>()

            // دریافت تمام صفحات محصولات از سرور آل‌باکس
            var page = 1
            var offset = 0
            var keepFetching = true

            while (keepFetching && page <= 40) {
                val pageUrl = "$BASE_URL?limit=50&page=$page&offset=$offset&latitude=$LAT&longitude=$LNG"
                val pageItems = fetchPageProducts(pageUrl, combinedCookies)

                if (pageItems == null) {
                    val msg = "نشست کاربری شما منقضی شده است. لطفاً از تب «ورود به سایت» دوباره لاگین کنید."
                    if (!isManual) sendNotification(context, "⚠️ نیاز به ورود مجدد", msg)
                    return@withContext Pair(false, msg)
                }

                var newItemsInPage = 0
                for (i in 0 until pageItems.length()) {
                    val item = pageItems.getJSONObject(i)
                    val id = item.optInt("id", -1)
                    if (id != -1 && seenIds.add(id)) {
                        allProducts.put(item)
                        newItemsInPage++
                    }
                }

                if (newItemsInPage == 0 || pageItems.length() < 20) {
                    keepFetching = false
                } else {
                    page++
                    offset += pageItems.length()
                }
            }

            // اگر سرور بدون فیلتر دسته‌بندی لیست را محدود کرده بود، دسته‌بندی‌های اصلی (1 تا 25) را هم پیمایش می‌کند
            if (allProducts.length() < 30) {
                for (catId in 1..25) {
                    val catUrl = "$BASE_URL?limit=100&latitude=$LAT&longitude=$LNG&categories%5B%5D=$catId"
                    val catItems = fetchPageProducts(catUrl, combinedCookies) ?: continue
                    for (i in 0 until catItems.length()) {
                        val item = catItems.getJSONObject(i)
                        val id = item.optInt("id", -1)
                        if (id != -1 && seenIds.add(id)) {
                            allProducts.put(item)
                        }
                    }
                }
            }

            if (allProducts.length() == 0) {
                return@withContext Pair(false, "هیچ محصولی دریافت نشد. ابتدا در تب «ورود به سایت» وارد حساب خود شوید.")
            }

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
            var priceChangesCount = 0

            for (i in 0 until allProducts.length()) {
                val item = allProducts.getJSONObject(i)
                val id = item.optInt("id").toString()
                val name = item.optString("name", "کالای کد $id")
                val categoryName = item.optJSONObject("category")?.optString("name", "") ?: ""
                val fullName = if (categoryName.isNotEmpty()) "[$categoryName] $name" else name

                // استخراج دقیق فیلدهای آل‌باکس طبق Response شما
                val newDiscount = item.optDouble("totalDiscount", 0.0).toInt()
                val newPrice = item.optLong("unitDiscountedPrice", item.optLong("boxDiscountedPrice", 0L))
                val retailPrice = item.optLong("unitRetailPrice", item.optLong("boxRetailPrice", 0L))

                val prodObj = JSONObject().apply {
                    put("name", fullName)
                    put("discount", newDiscount)
                    put("price", newPrice)
                    put("retailPrice", retailPrice)
                }
                newSnapshot.put(id, prodObj)

                if (oldSnapshot.has(id)) {
                    val oldProd = oldSnapshot.getJSONObject(id)
                    val oldDiscount = oldProd.optInt("discount", 0)
                    val oldPrice = oldProd.optLong("price", 0L)

                    if (oldDiscount != newDiscount || oldPrice != newPrice) {
                        priceChangesCount++
                        val isBetter = newDiscount > oldDiscount || (newPrice < oldPrice && oldPrice > 0)
                        val badge = if (isBetter) "🟢 افزایش تخفیف" else "🔴 تغییر تخفیف"

                        val changeRecord = JSONObject().apply {
                            put("type", "CHANGE")
                            put("name", fullName)
                            put("oldDiscount", oldDiscount)
                            put("newDiscount", newDiscount)
                            put("oldPrice", oldPrice)
                            put("newPrice", newPrice)
                            put("time", currentTime)
                            put("isBetter", isBetter)
                        }
                        newlyDetectedRecords.add(changeRecord)
                        notificationLines.add("$badge | $fullName: از $oldDiscount% به $newDiscount% (${numFormat.format(newPrice)} ریال)")
                    }
                } else if (!isFirstRun) {
                    newProductsCount++
                    val newProductRecord = JSONObject().apply {
                        put("type", "NEW_PRODUCT")
                        put("name", fullName)
                        put("oldDiscount", 0)
                        put("newDiscount", newDiscount)
                        put("oldPrice", retailPrice)
                        put("newPrice", newPrice)
                        put("time", currentTime)
                        put("isBetter", true)
                    }
                    newlyDetectedRecords.add(newProductRecord)
                    notificationLines.add("🆕 کالای جدید: $fullName (تخفیف: $newDiscount% | خرید: ${numFormat.format(newPrice)} ریال)")
                }
            }

            snapshotFile.writeText(newSnapshot.toString())
            prefs.edit().putString("LAST_CHECK_TIME", "$currentTime (${newSnapshot.length()} کالا)").apply()

            if (isFirstRun) {
                return@withContext Pair(true, "لیست اولیه شامل ${newSnapshot.length()} کالا با موفقیت ذخیره شد. از این لحظه تغییرات و کالاهای جدید رصد می‌شوند.")
            }

            if (newlyDetectedRecords.isNotEmpty()) {
                val updatedHistory = JSONArray()
                for (record in newlyDetectedRecords) updatedHistory.put(record)
                for (j in 0 until minOf(oldHistoryArray.length(), 500 - newlyDetectedRecords.size)) {
                    updatedHistory.put(oldHistoryArray.getJSONObject(j))
                }
                historyFile.writeText(updatedHistory.toString())

                val notifTitle = when {
                    newProductsCount > 0 && priceChangesCount > 0 -> "🔔 $newProductsCount کالای جدید و $priceChangesCount تغییر تخفیف!"
                    newProductsCount > 0 -> "🆕 $newProductsCount محصول جدید به آل‌باکس اضافه شد!"
                    else -> "🔔 تغییر تخفیف در $priceChangesCount محصول آل‌باکس!"
                }
                sendNotification(context, notifTitle, notificationLines.take(12).joinToString("\n"))
                return@withContext Pair(true, "$newProductsCount کالای جدید و $priceChangesCount تغییر قیمت پیدا و ذخیره شد.")
            }

            return@withContext Pair(true, "بررسی انجام شد (${newSnapshot.length()} کالا). تغییر قیمت یا کالای جدیدی وجود نداشت.")
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Pair(false, "خطا در ارتباط با سرور: ${e.localizedMessage}")
        }
    }

    private fun fetchPageProducts(pageUrl: String, cookies: String): JSONArray? {
        val connection = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
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
