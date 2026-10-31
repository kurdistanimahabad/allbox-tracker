package com.example.allboxtracker

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var historyContainer: LinearLayout
    private lateinit var statusTextView: TextView
    private lateinit var alarmsListLayout: LinearLayout
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        // درخواست مجوز نوتیفیکیشن در اندروید ۱۳ به بالا
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(8, 12, 8, 12)
        }

        val tab1HistoryView = createHistoryTab()
        val tab2ScheduleView = createScheduleTab()
        val tab3LoginView = createLoginTab()

        val contentFrame = FrameLayout(this).apply {
            addView(tab1HistoryView)
            addView(tab2ScheduleView)
            addView(tab3LoginView)
        }

        fun switchTab(index: Int) {
            tab1HistoryView.visibility = if (index == 0) View.VISIBLE else View.GONE
            tab2ScheduleView.visibility = if (index == 1) View.VISIBLE else View.GONE
            tab3LoginView.visibility = if (index == 2) View.VISIBLE else View.GONE
            if (index == 0) refreshHistoryUI()
        }

        val btnTab1 = createTabButton("📊 تغییرات و بروزرسانی") { switchTab(0) }
        val btnTab2 = createTabButton("⏰ تنظیم ساعت‌ها") { switchTab(1) }
        val btnTab3 = createTabButton("🔑 ورود به سایت") { switchTab(2) }

        tabBar.addView(btnTab1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(btnTab2, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(btnTab3, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        mainLayout.addView(tabBar)
        mainLayout.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(mainLayout)
        switchTab(0)
    }

    private fun createHistoryTab(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val btnManualCheck = Button(this).apply {
            text = "🔄 بررسی و بروزرسانی دستی همین الان"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                isEnabled = false
                text = "⏳ در حال دریافت و مقایسه کالاها..."
                lifecycleScope.launch {
                    val (_, message) = DiscountEngine.checkPricesNow(this@MainActivity, isManual = true)
                    isEnabled = true
                    text = "🔄 بررسی و بروزرسانی دستی همین الان"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    refreshHistoryUI()
                }
            }
        }

        val topInfoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        statusTextView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }

        val btnClear = Button(this).apply {
            text = "🗑️ پاک کردن لیست"
            textSize = 12f
            setOnClickListener {
                DiscountEngine.clearHistory(this@MainActivity)
                refreshHistoryUI()
            }
        }

        topInfoRow.addView(statusTextView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topInfoRow.addView(btnClear)

        val scrollView = ScrollView(this)
        historyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(historyContainer)

        layout.addView(btnManualCheck)
        layout.addView(topInfoRow)
        layout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        return layout
    }

    private fun refreshHistoryUI() {
        val prefs = getSharedPreferences("AllBoxPrefs", MODE_PRIVATE)
        val lastCheck = prefs.getString("LAST_CHECK_TIME", "هنوز بررسی نشده")
        statusTextView.text = "آخرین بروزرسانی: $lastCheck"

        historyContainer.removeAllViews()
        val historyArray = DiscountEngine.getHistoryList(this)
        val numFormat = NumberFormat.getNumberInstance(Locale.US)

        if (historyArray.length() == 0) {
            val emptyView = TextView(this).apply {
                text = "هنوز هیچ تغییر قیمت یا محصول جدیدی ثبت نشده است.\n\n۱. ابتدا از تب «ورود به سایت» وارد حساب آل‌باکس شوید.\n۲. سپس دکمه آبی «بررسی و بروزرسانی دستی» را بزنید تا لیست اولیه کالاها ذخیره شود."
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(32, 100, 32, 32)
            }
            historyContainer.addView(emptyView)
            return
        }

        for (i in 0 until historyArray.length()) {
            val item = historyArray.getJSONObject(i)
            val recordType = item.optString("type", "CHANGE")
            val isBetter = item.optBoolean("isBetter", true)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(if (recordType == "NEW_PRODUCT") Color.parseColor("#EFF6FF") else Color.WHITE)
                setPadding(24, 20, 24, 20)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 16) }
                layoutParams = params
            }

            val titleView = TextView(this).apply {
                text = item.optString("name")
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.BLACK)
            }

            val detailView = TextView(this).apply {
                val oldD = item.optInt("oldDiscount")
                val newD = item.optInt("newDiscount")
                val oldP = numFormat.format(item.optLong("oldPrice"))
                val newP = numFormat.format(item.optLong("newPrice"))

                if (recordType == "NEW_PRODUCT") {
                    text = "🆕 محصول جدید!  |  تخفیف: $newD%  |  قیمت خرید: $newP ریال"
                    setTextColor(Color.parseColor("#1D4ED8"))
                } else {
                    val arrow = if (isBetter) "🟢 افزایش تخفیف:" else "🔴 تغییر تخفیف:"
                    text = "$arrow از $oldD% به $newD%\nقیمت خرید: از $oldP به $newP ریال"
                    setTextColor(if (isBetter) Color.parseColor("#15803D") else Color.parseColor("#B91C1C"))
                }
                textSize = 14f
                setPadding(0, 8, 0, 8)
            }

            val timeView = TextView(this).apply {
                text = "🕒 زمان ثبت: ${item.optString("time")}"
                textSize = 12f
                setTextColor(Color.GRAY)
            }

            card.addView(titleView)
            card.addView(detailView)
            card.addView(timeView)
            historyContainer.addView(card)
        }
    }

    private fun createScheduleTab(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val prefs = getSharedPreferences("AllBoxPrefs", MODE_PRIVATE)

        val switchPeriodic = Switch(this).apply {
            text = "بررسی خودکار هر ۳۰ دقیقه یک‌بار"
            textSize = 16f
            isChecked = prefs.getBoolean("AUTO_30_MIN", true)
            setPadding(0, 16, 0, 32)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("AUTO_30_MIN", isChecked).apply()
                if (isChecked) {
                    val work = PeriodicWorkRequestBuilder<DiscountWorker>(30, TimeUnit.MINUTES).build()
                    WorkManager.getInstance(this@MainActivity).enqueueUniquePeriodicWork(
                        "AllBoxDiscountTracker", ExistingPeriodicWorkPolicy.UPDATE, work
                    )
                    Toast.makeText(context, "بررسی خودکار ۳۰ دقیقه‌ای فعال شد", Toast.LENGTH_SHORT).show()
                } else {
                    WorkManager.getInstance(this@MainActivity).cancelUniqueWork("AllBoxDiscountTracker")
                    Toast.makeText(context, "بررسی ۳۰ دقیقه‌ای متوقف شد", Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (prefs.getBoolean("AUTO_30_MIN", true)) {
            val work = PeriodicWorkRequestBuilder<DiscountWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "AllBoxDiscountTracker", ExistingPeriodicWorkPolicy.KEEP, work
            )
        }

        val btnAddTime = Button(this).apply {
            text = "➕ افزودن ساعت مشخص برای بررسی روزانه"
            setBackgroundColor(Color.parseColor("#0D9488"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, hour, minute ->
                    val timeStr = String.format(Locale.US, "%02d:%02d", hour, minute)
                    val savedTimes = prefs.getStringSet("CUSTOM_TIMES", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    savedTimes.add(timeStr)
                    prefs.edit().putStringSet("CUSTOM_TIMES", savedTimes).apply()

                    AlarmHelper.scheduleDailyAlarm(this@MainActivity, hour, minute)
                    refreshAlarmsListUI()
                    Toast.makeText(context, "ساعت $timeStr اضافه شد", Toast.LENGTH_SHORT).show()
                }, 12, 0, true).show()
            }
        }

        val titleAlarms = TextView(this).apply {
            text = "ساعت‌های تنظیم‌شده برای بررسی روزانه:"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 32, 0, 16)
        }

        alarmsListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        refreshAlarmsListUI()

        layout.addView(switchPeriodic)
        layout.addView(btnAddTime)
        layout.addView(titleAlarms)
        layout.addView(alarmsListLayout)

        return layout
    }

    private fun refreshAlarmsListUI() {
        alarmsListLayout.removeAllViews()
        val prefs = getSharedPreferences("AllBoxPrefs", MODE_PRIVATE)
        val savedTimes = prefs.getStringSet("CUSTOM_TIMES", emptySet())?.sorted() ?: emptyList()

        if (savedTimes.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "هنوز ساعت مشخصی اضافه نکرده‌اید."
                setTextColor(Color.GRAY)
            }
            alarmsListLayout.addView(emptyTv)
            return
        }

        for (timeStr in savedTimes) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(24, 16, 24, 16)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                layoutParams = params
            }

            val tv = TextView(this).apply {
                text = "⏰ هر روز ساعت $timeStr"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
            }

            val btnDelete = Button(this).apply {
                text = "❌ حذف"
                setOnClickListener {
                    val parts = timeStr.split(":")
                    AlarmHelper.cancelDailyAlarm(this@MainActivity, parts[0].toInt(), parts[1].toInt())
                    val updated = prefs.getStringSet("CUSTOM_TIMES", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    updated.remove(timeStr)
                    prefs.edit().putStringSet("CUSTOM_TIMES", updated).apply()
                    refreshAlarmsListUI()
                }
            }

            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btnDelete)
            alarmsListLayout.addView(row)
        }
    }

    private fun createLoginTab(): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val hintTv = TextView(this).apply {
            text = "یک بار در سایت زیر وارد حساب خود شوید؛ برنامه به صورت خودکار کوکی لاگین را ذخیره می‌کند."
            setBackgroundColor(Color.parseColor("#FEF3C7"))
            setTextColor(Color.parseColor("#92400E"))
            setPadding(16, 16, 16, 16)
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                }
            }
            loadUrl("https://allbox.surenamarkets.com")
        }

        layout.addView(hintTv)
        layout.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        return layout
    }

    private fun createTabButton(title: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setOnClickListener { onClick() }
        }
    }
}
