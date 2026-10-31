package com.example.allboxtracker

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var historyContainer: LinearLayout
    private lateinit var statusTextView: TextView
    private lateinit var alarmsListLayout: LinearLayout
    private lateinit var intervalInfoTextView: TextView
    private lateinit var webView: WebView

    // اجزای تب «همه محصولات»
    private lateinit var categorySpinner: Spinner
    private lateinit var sortSpinner: Spinner
    private lateinit var searchEditText: EditText
    private lateinit var productsCountTv: TextView
    private lateinit var productsListView: ListView
    private var allCachedProducts: List<JSONObject> = emptyList()
    private var displayedProducts: List<JSONObject> = emptyList()
    private lateinit var productsAdapter: BaseAdapter

    private val tabButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        // نوار ۴ تب بالای برنامه
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(6, 10, 6, 10)
        }

        val tab1HistoryView = createHistoryTab()
        val tab2AllProductsView = createAllProductsTab()
        val tab3ScheduleView = createScheduleTab()
        val tab4LoginView = createLoginTab()

        val contentFrame = FrameLayout(this).apply {
            addView(tab1HistoryView)
            addView(tab2AllProductsView)
            addView(tab3ScheduleView)
            addView(tab4LoginView)
        }

        fun switchTab(index: Int) {
            tab1HistoryView.visibility = if (index == 0) View.VISIBLE else View.GONE
            tab2AllProductsView.visibility = if (index == 1) View.VISIBLE else View.GONE
            tab3ScheduleView.visibility = if (index == 2) View.VISIBLE else View.GONE
            tab4LoginView.visibility = if (index == 3) View.VISIBLE else View.GONE

            tabButtons.forEachIndexed { i, btn ->
                btn.setBackgroundColor(if (i == index) Color.parseColor("#2563EB") else Color.parseColor("#334155"))
            }

            if (index == 0) refreshHistoryUI()
            if (index == 1) loadAndFilterAllProducts()
        }

        val titles = listOf("📊 تغییرات", "📦 همه محصولات", "⏰ زمان‌بندی", "🔑 ورود")
        titles.forEachIndexed { idx, title ->
            val btn = createTabButton(title) { switchTab(idx) }
            tabButtons.add(btn)
            tabBar.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            })
        }

        mainLayout.addView(tabBar)
        mainLayout.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(mainLayout)
        switchTab(0)
    }

    // ==================== تب ۱: لیست تغییرات (افزایش، کاهش، کالای جدید) ====================
    private fun createHistoryTab(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        val btnManualCheck = Button(this).apply {
            text = "🔄 بررسی و بروزرسانی دستی تمام محصولات"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                isEnabled = false
                text = "⏳ شروع دریافت تمام دسته‌بندی‌ها..."
                lifecycleScope.launch {
                    val (_, message) = DiscountEngine.checkPricesNow(
                        this@MainActivity,
                        isManual = true,
                        onProgress = { progressMsg ->
                            runOnUiThread { text = progressMsg }
                        }
                    )
                    isEnabled = true
                    text = "🔄 بررسی و بروزرسانی دستی تمام محصولات"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    refreshHistoryUI()
                }
            }
        }

        val topInfoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 14, 0, 14)
        }

        statusTextView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }

        val btnClear = Button(this).apply {
            text = "🗑️ پاک کردن تاریخچه"
            textSize = 11f
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
                text = "هنوز هیچ تغییر قیمت (افزایش یا کاهش تخفیف) یا کالای جدیدی ثبت نشده است.\n\nبرای مشاهده کل کالاهای فعلی سایت، به تب «📦 همه محصولات» بروید."
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(32, 80, 32, 32)
            }
            historyContainer.addView(emptyView)
            return
        }

        for (i in 0 until historyArray.length()) {
            val item = historyArray.getJSONObject(i)
            val recordType = item.optString("type", "CHANGE")
            val isBetter = item.optBoolean("isBetter", true)

            val bgColor = when (recordType) {
                "NEW_PRODUCT" -> "#EFF6FF"   // آبی روشن برای کالای جدید
                "DISCOUNT_DOWN" -> "#FEF2F2" // قرمز ملایم برای کاهش تخفیف
                else -> "#F0FDF4"            // سبز ملایم برای افزایش تخفیف
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor(bgColor))
                setPadding(24, 18, 24, 18)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 14) }
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

                when (recordType) {
                    "NEW_PRODUCT" -> {
                        text = "🆕 محصول جدید!  |  تخفیف: $newD%  |  قیمت خرید: $newP ریال"
                        setTextColor(Color.parseColor("#1D4ED8"))
                    }
                    "DISCOUNT_DOWN" -> {
                        text = "🔴 کاهش تخفیف: از $oldD% به $newD%\nقیمت خرید: از $oldP به $newP ریال"
                        setTextColor(Color.parseColor("#B91C1C"))
                    }
                    else -> {
                        val label = if (isBetter) "🟢 افزایش تخفیف:" else "🔴 کاهش تخفیف:"
                        text = "$label از $oldD% به $newD%\nقیمت خرید: از $oldP به $newP ریال"
                        setTextColor(if (isBetter) Color.parseColor("#15803D") else Color.parseColor("#B91C1C"))
                    }
                }
                textSize = 13.5f
                setPadding(0, 8, 0, 8)
            }

            val timeView = TextView(this).apply {
                text = "🕒 زمان ثبت: ${item.optString("time")}"
                textSize = 11.5f
                setTextColor(Color.DKGRAY)
            }

            card.addView(titleView)
            card.addView(detailView)
            card.addView(timeView)
            historyContainer.addView(card)
        }
    }

    // ==================== تب ۲: لیست تمام محصولات سایت بر اساس دسته‌بندی ====================
    private fun createAllProductsTab(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // کادر جستجوی نام محصول
        searchEditText = EditText(this).apply {
            hint = "🔍 جستجوی نام کالا یا برند..."
            setSingleLine(true)
            setBackgroundColor(Color.WHITE)
            setPadding(20, 16, 20, 16)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applyProductFilters()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        // ردیف انتخاب دسته‌بندی و مرتب‌سازی
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
        }

        categorySpinner = Spinner(this).apply {
            setBackgroundColor(Color.WHITE)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    applyProductFilters()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        sortSpinner = Spinner(this).apply {
            setBackgroundColor(Color.WHITE)
            val sortOptions = listOf(
                "🔥 بیشترین درصد تخفیف",
                "📉 کمترین درصد تخفیف",
                "💰 ارزان‌ترین قیمت",
                "💎 گران‌ترین قیمت",
                "📂 بر اساس دسته‌بندی"
            )
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, sortOptions)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    applyProductFilters()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        filterRow.addView(categorySpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
            setMargins(0, 0, 8, 0)
        })
        filterRow.addView(sortSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))

        productsCountTv = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
            setPadding(4, 4, 4, 10)
        }

        val numFormat = NumberFormat.getNumberInstance(Locale.US)

        productsAdapter = object : BaseAdapter() {
            override fun getCount(): Int = displayedProducts.size
            override fun getItem(position: Int): JSONObject = displayedProducts[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val card = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                    setPadding(24, 18, 24, 18)
                }
                card.removeAllViews()

                val item = getItem(position)
                val rawName = item.optString("rawName", item.optString("name"))
                val catName = item.optString("category", "سایر")
                val brand = item.optString("brand", "")
                val discount = item.optInt("discount", 0)
                val price = numFormat.format(item.optLong("price", 0L))
                val retail = numFormat.format(item.optLong("retailPrice", 0L))
                val stock = item.optInt("stock", 0)

                val topBadgeTv = TextView(this@MainActivity).apply {
                    val brandPart = if (brand.isNotEmpty()) " | برند: $brand" else ""
                    text = "📂 دسته: $catName$brandPart  |  موجودی: $stock"
                    textSize = 12f
                    setTextColor(Color.parseColor("#475569"))
                }

                val nameTv = TextView(this@MainActivity).apply {
                    text = rawName
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.BLACK)
                    setPadding(0, 6, 0, 6)
                }

                val priceTv = TextView(this@MainActivity).apply {
                    text = "🏷️ تخفیف: $discount%   |   خرید: $price ریال\nقیمت مصرف‌کننده: $retail ریال"
                    textSize = 13.5f
                    setTextColor(if (discount >= 10) Color.parseColor("#15803D") else Color.parseColor("#1D4ED8"))
                }

                card.addView(topBadgeTv)
                card.addView(nameTv)
                card.addView(priceTv)
                return card
            }
        }

        productsListView = ListView(this).apply {
            dividerHeight = 12
            adapter = productsAdapter
        }

        layout.addView(searchEditText)
        layout.addView(filterRow)
        layout.addView(productsCountTv)
        layout.addView(productsListView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        return layout
    }

    private fun loadAndFilterAllProducts() {
        allCachedProducts = DiscountEngine.getAllProductsList(this)

        // استخراج لیست دسته‌بندی‌ها به همراه تعداد کالاهای هر دسته
        val categoryCounts = mutableMapOf<String, Int>()
        for (prod in allCachedProducts) {
            val cat = prod.optString("category", "سایر")
            categoryCounts[cat] = (categoryCounts[cat] ?: 0) + 1
        }

        val currentSelection = categorySpinner.selectedItem as? String
        val spinnerItems = mutableListOf("همه دسته‌بندی‌ها (${allCachedProducts.size})")
        categoryCounts.entries.sortedByDescending { it.value }.forEach { (cat, count) ->
            spinnerItems.add("$cat ($count)")
        }

        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spinnerItems)
        if (currentSelection != null) {
            val prefix = currentSelection.substringBefore(" (")
            val matchIndex = spinnerItems.indexOfFirst { it.startsWith("$prefix (") }
            if (matchIndex >= 0) categorySpinner.setSelection(matchIndex)
        }

        applyProductFilters()
    }

    private fun applyProductFilters() {
        val selectedCatItem = (categorySpinner.selectedItem as? String) ?: "همه دسته‌بندی‌ها"
        val selectedCat = if (selectedCatItem.startsWith("همه دسته‌بندی‌ها")) null else selectedCatItem.substringBefore(" (")
        val query = searchEditText.text.toString().trim()
        val sortIndex = sortSpinner.selectedItemPosition

        var filtered = allCachedProducts.filter { item ->
            val matchCat = selectedCat == null || item.optString("category", "سایر") == selectedCat
            val matchSearch = query.isEmpty() ||
                item.optString("name", "").contains(query, ignoreCase = true) ||
                item.optString("brand", "").contains(query, ignoreCase = true)
            matchCat && matchSearch
        }

        filtered = when (sortIndex) {
            0 -> filtered.sortedByDescending { it.optInt("discount", 0) }
            1 -> filtered.sortedBy { it.optInt("discount", 0) }
            2 -> filtered.sortedBy { it.optLong("price", 0L) }
            3 -> filtered.sortedByDescending { it.optLong("price", 0L) }
            else -> filtered.sortedWith(compareBy({ it.optString("category", "") }, { it.optString("rawName", "") }))
        }

        displayedProducts = filtered
        productsCountTv.text = "نمایش ${displayedProducts.size} محصول (از مجموع ${allCachedProducts.size} کالای ذخیره‌شده)"
        productsAdapter.notifyDataSetChanged()
    }

    // ==================== تب ۳: زمان‌بندی (تغییر فاصله زمانی خودکار + ویرایش ساعت‌های روزانه) ====================
    private fun createScheduleTab(): View {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val prefs = getSharedPreferences("AllBoxPrefs", MODE_PRIVATE)

        // بخش اول: بررسی دوره‌ای خودکار با قابلیت تغییر دقیقه
        val periodicCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(24, 20, 24, 20)
        }

        val isAutoEnabled = prefs.getBoolean("AUTO_CHECK_ENABLED", true)
        val savedInterval = prefs.getInt("AUTO_INTERVAL_MINUTES", 30)

        intervalInfoTextView = TextView(this).apply {
            text = "⏱️ فاصله زمانی بررسی خودکار فعلی: هر $savedInterval دقیقه یک‌بار"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
            setPadding(0, 8, 0, 16)
        }

        val switchPeriodic = Switch(this).apply {
            text = "فعال بودن بررسی خودکار دوره‌ای"
            textSize = 15f
            isChecked = isAutoEnabled
            setPadding(0, 0, 0, 12)
            setOnCheckedChangeListener { _, isChecked ->
                val currentMin = prefs.getInt("AUTO_INTERVAL_MINUTES", 30)
                prefs.edit().putBoolean("AUTO_CHECK_ENABLED", isChecked).apply()
                AlarmHelper.updatePeriodicSchedule(this@MainActivity, isChecked, currentMin)
                Toast.makeText(
                    context,
                    if (isChecked) "بررسی خودکار هر $currentMin دقیقه فعال شد" else "بررسی خودکار متوقف شد",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // دکمه تغییر دستی فاصله زمانی بررسی خودکار
        val btnChangeInterval = Button(this).apply {
            text = "✏️ تغییر فاصله زمانی بررسی خودکار (دقیقه)"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                showChangeIntervalDialog()
            }
        }

        // راه‌اندازی اولیه تایمر خودکار
        AlarmHelper.updatePeriodicSchedule(this, isAutoEnabled, savedInterval)

        periodicCard.addView(switchPeriodic)
        periodicCard.addView(intervalInfoTextView)
        periodicCard.addView(btnChangeInterval)

        // بخش دوم: ساعت‌های مشخص روزانه با قابلیت ویرایش و حذف
        val btnAddTime = Button(this).apply {
            text = "➕ افزودن ساعت جدید برای بررسی روزانه"
            setBackgroundColor(Color.parseColor("#0D9488"))
            setTextColor(Color.WHITE)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 32, 0, 16) }
            layoutParams = params

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
            text = "ساعت‌های مشخص روزانه (برای تغییر روی ✏️ ویرایش بزنید):"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 16, 0, 12)
        }

        alarmsListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        refreshAlarmsListUI()

        layout.addView(periodicCard)
        layout.addView(btnAddTime)
        layout.addView(titleAlarms)
        layout.addView(alarmsListLayout)
        scroll.addView(layout)

        return scroll
    }

    private fun showChangeIntervalDialog() {
        val prefs = getSharedPreferences("AllBoxPrefs", MODE_PRIVATE)
        val currentMin = prefs.getInt("AUTO_INTERVAL_MINUTES", 30)

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentMin.toString())
            hint = "مثلاً 15 یا 30 یا 60"
            gravity = Gravity.CENTER
        }

        AlertDialog.Builder(this)
            .setTitle("تنظیم فاصله بررسی خودکار")
            .setMessage("هر چند دقیقه یک‌بار لیست تخفیف‌ها به صورت خودکار بررسی شود؟ (مثلاً 15، 30، 45 یا 60 دقیقه):")
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                val entered = input.text.toString().toIntOrNull() ?: 30
                val validMinutes = maxOf(5, entered)
                prefs.edit().putInt("AUTO_INTERVAL_MINUTES", validMinutes).apply()
                intervalInfoTextView.text = "⏱️ فاصله زمانی بررسی خودکار فعلی: هر $validMinutes دقیقه یک‌بار"

                val enabled = prefs.getBoolean("AUTO_CHECK_ENABLED", true)
                AlarmHelper.updatePeriodicSchedule(this, enabled, validMinutes)
                Toast.makeText(this, "بررسی خودکار روی هر $validMinutes دقیقه تنظیم شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("انصراف", null)
            .show()
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
                setPadding(20, 14, 20, 14)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                layoutParams = params
            }

            val tv = TextView(this).apply {
                text = "⏰ هر روز ساعت $timeStr"
                textSize = 15.5f
                setTypeface(null, Typeface.BOLD)
            }

            // دکمه ویرایش ساعت موجود
            val btnEdit = Button(this).apply {
                text = "✏️ ویرایش"
                textSize = 12f
                setOnClickListener {
                    val parts = timeStr.split(":")
                    val oldH = parts[0].toInt()
                    val oldM = parts[1].toInt()

                    TimePickerDialog(this@MainActivity, { _, newHour, newMinute ->
                        // لغو ساعت قبلی
                        AlarmHelper.cancelDailyAlarm(this@MainActivity, oldH, oldM)
                        val updated = prefs.getStringSet("CUSTOM_TIMES", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        updated.remove(timeStr)

                        // ثبت ساعت جدید
                        val newTimeStr = String.format(Locale.US, "%02d:%02d", newHour, newMinute)
                        updated.add(newTimeStr)
                        prefs.edit().putStringSet("CUSTOM_TIMES", updated).apply()

                        AlarmHelper.scheduleDailyAlarm(this@MainActivity, newHour, newMinute)
                        refreshAlarmsListUI()
                        Toast.makeText(context, "ساعت $timeStr به $newTimeStr تغییر یافت", Toast.LENGTH_SHORT).show()
                    }, oldH, oldM, true).show()
                }
            }

            // دکمه حذف ساعت
            val btnDelete = Button(this).apply {
                text = "❌ حذف"
                textSize = 12f
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
            row.addView(btnEdit)
            row.addView(btnDelete)
            alarmsListLayout.addView(row)
        }
    }

    // ==================== تب ۴: ورود به حساب کاربری ====================
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
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setPadding(4, 12, 4, 12)
            setOnClickListener { onClick() }
        }
    }
}
