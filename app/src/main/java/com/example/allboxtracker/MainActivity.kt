package com.example.allboxtracker

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // اجزای تب ۱: تغییرات
    private lateinit var statusTextView: TextView
    private lateinit var historySearchEditText: EditText
    private lateinit var historyTypeSpinner: Spinner
    private lateinit var historyListView: ListView
    private var allHistoryItems: List<JSONObject> = emptyList()
    private var displayedHistoryItems: List<JSONObject> = emptyList()
    private lateinit var historyAdapter: BaseAdapter

    // اجزای تب ۲: همه محصولات
    private lateinit var mainCategorySpinner: Spinner
    private lateinit var subCategorySpinner: Spinner
    private lateinit var sortSpinner: Spinner
    private lateinit var searchEditText: EditText
    private lateinit var inStockCheckBox: CheckBox
    private lateinit var productsCountTv: TextView
    private lateinit var productsListView: ListView
    private var allCachedProducts: List<JSONObject> = emptyList()
    private var displayedProducts: List<JSONObject> = emptyList()
    private lateinit var productsAdapter: BaseAdapter
    private var isUpdatingSpinners = false

    // اجزای تب ۳، ۴ و ۵
    private lateinit var alarmsListLayout: LinearLayout
    private lateinit var intervalInfoTextView: TextView
    private lateinit var batteryStatusBtn: Button
    private lateinit var settingsContentContainer: LinearLayout
    private lateinit var webView: WebView

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

        // نوار ۵ تب در بالای برنامه
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(4, 8, 4, 8)
        }

        val tab1HistoryView = createHistoryTab()
        val tab2AllProductsView = createAllProductsTab()
        val tab3ScheduleView = createScheduleTab()
        val tab4SettingsView = createSettingsTab()
        val tab5LoginView = createLoginTab()

        val contentFrame = FrameLayout(this).apply {
            addView(tab1HistoryView)
            addView(tab2AllProductsView)
            addView(tab3ScheduleView)
            addView(tab4SettingsView)
            addView(tab5LoginView)
        }

        fun switchTab(index: Int) {
            tab1HistoryView.visibility = if (index == 0) View.VISIBLE else View.GONE
            tab2AllProductsView.visibility = if (index == 1) View.VISIBLE else View.GONE
            tab3ScheduleView.visibility = if (index == 2) View.VISIBLE else View.GONE
            tab4SettingsView.visibility = if (index == 3) View.VISIBLE else View.GONE
            tab5LoginView.visibility = if (index == 4) View.VISIBLE else View.GONE

            tabButtons.forEachIndexed { i, btn ->
                btn.setBackgroundColor(if (i == index) Color.parseColor("#2563EB") else Color.parseColor("#334155"))
            }

            if (index == 0) {
                updateHistoryFilterSpinnerTitle()
                loadAndFilterHistory()
            }
            if (index == 1) loadAndFilterAllProducts()
            if (index == 2) updateBatteryButtonState()
            if (index == 3) rebuildSettingsUI()
        }

        val titles = listOf("📊 تغییرات", "📦 کالاها", "⏰ زمان‌بندی", "⚙️ تنظیمات", "🔑 ورود")
        titles.forEachIndexed { idx, title ->
            val btn = createTabButton(title) { switchTab(idx) }
            tabButtons.add(btn)
            tabBar.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(3, 0, 3, 0)
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

    override fun onResume() {
        super.onResume()
        if (::batteryStatusBtn.isInitialized) {
            updateBatteryButtonState()
        }
    }

    // ==================== تب ۱: لیست تغییرات ====================
    private fun createHistoryTab(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
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
                    loadAndFilterHistory()
                }
            }
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 8)
        }

        historySearchEditText = EditText(this).apply {
            hint = "🔍 جستجوی نام محصول در تغییرات..."
            setSingleLine(true)
            setBackgroundColor(Color.WHITE)
            setPadding(18, 14, 18, 14)
            textSize = 13.5f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applyHistoryFilters()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        historyTypeSpinner = Spinner(this).apply {
            setBackgroundColor(Color.WHITE)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    applyHistoryFilters()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        updateHistoryFilterSpinnerTitle()

        searchRow.addView(historySearchEditText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
            setMargins(0, 0, 8, 0)
        })
        searchRow.addView(historyTypeSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))

        val topInfoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 10)
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
                loadAndFilterHistory()
            }
        }

        topInfoRow.addView(statusTextView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topInfoRow.addView(btnClear)

        historyAdapter = object : BaseAdapter() {
            override fun getCount(): Int = displayedHistoryItems.size
            override fun getItem(position: Int): JSONObject = displayedHistoryItems[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val card = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 18, 24, 18)
                }
                card.removeAllViews()

                val item = getItem(position)
                val recordType = item.optString("type", "CHANGE")
                val isBetter = item.optBoolean("isBetter", true)
                val oldD = item.optInt("oldDiscount", 0)
                val newD = item.optInt("newDiscount", 0)
                val threshold = AppSettings.getMajorDiscountThreshold(this@MainActivity)
                val isMajor = abs(newD - oldD) >= threshold &&
                    recordType != "RETAIL_PRICE_UP" &&
                    recordType != "RETAIL_PRICE_DOWN" &&
                    recordType != "NEW_PRODUCT"

                // انتخاب کلید رنگ بر اساس نوع تغییر (خوانده شده از تنظیمات کاربر)
                val colorKey = when {
                    isMajor -> "MAJOR"
                    recordType == "RETAIL_PRICE_UP" || recordType == "RETAIL_PRICE_DOWN" -> "RETAIL"
                    recordType == "NEW_PRODUCT" -> "NEW"
                    recordType == "DISCOUNT_DOWN" || (!isBetter && recordType == "CHANGE") -> "DOWN"
                    else -> "UP"
                }

                val bgHex = AppSettings.getBgColorHex(this@MainActivity, colorKey)
                val txtHex = AppSettings.getTextColorHex(this@MainActivity, colorKey)
                card.setBackgroundColor(AppSettings.safeParseColor(bgHex, Color.WHITE))

                val mainCat = item.optString("mainCategory", "")
                val titleView = TextView(this@MainActivity).apply {
                    val catPrefix = if (mainCat.isNotEmpty()) "$mainCat > " else ""
                    text = "$catPrefix${item.optString("name")}"
                    textSize = 14.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.BLACK)
                }

                val oldBuy = AppSettings.formatPrice(this@MainActivity, item.optLong("oldPrice", 0L))
                val newBuy = AppSettings.formatPrice(this@MainActivity, item.optLong("newPrice", 0L))

                val newRetailLong = item.optLong("newRetailPrice", item.optLong("oldPrice", 0L))
                val oldRetailLong = item.optLong("oldRetailPrice", newRetailLong)
                val oldRetail = AppSettings.formatPrice(this@MainActivity, oldRetailLong)
                val newRetail = AppSettings.formatPrice(this@MainActivity, newRetailLong)

                val retailLine = if (oldRetailLong > 0 && oldRetailLong != newRetailLong && recordType != "NEW_PRODUCT") {
                    "قیمت مصرف‌کننده: از $oldRetail به $newRetail"
                } else if (newRetailLong > 0) {
                    "قیمت مصرف‌کننده: $newRetail"
                } else {
                    "قیمت مصرف‌کننده: ثبت نشده"
                }

                val detailView = TextView(this@MainActivity).apply {
                    when {
                        recordType == "NEW_PRODUCT" -> {
                            text = "🆕 محصول جدید!  |  تخفیف: $newD%\nقیمت خرید: $newBuy\n$retailLine"
                        }
                        recordType == "RETAIL_PRICE_UP" -> {
                            text = "📈 افزایش قیمت مصرف‌کننده: از $oldRetail به $newRetail\nقیمت خرید: از $oldBuy به $newBuy (تخفیف فعلی: $newD%)"
                        }
                        recordType == "RETAIL_PRICE_DOWN" -> {
                            text = "📉 کاهش قیمت مصرف‌کننده: از $oldRetail به $newRetail\nقیمت خرید: از $oldBuy به $newBuy (تخفیف فعلی: $newD%)"
                        }
                        isMajor -> {
                            val diff = abs(newD - oldD)
                            val majorLabel = if (isBetter) "🔥 افزایش ویژه تخفیف (+$diff%):" else "⚠️ کاهش شدید تخفیف (-$diff%):"
                            text = "$majorLabel از $oldD% به $newD%\nقیمت خرید: از $oldBuy به $newBuy\n$retailLine"
                            setTypeface(null, Typeface.BOLD)
                        }
                        recordType == "DISCOUNT_DOWN" || !isBetter -> {
                            text = "🔴 کاهش درصد تخفیف: از $oldD% به $newD%\nقیمت خرید: از $oldBuy به $newBuy\n$retailLine"
                        }
                        else -> {
                            text = "🟢 افزایش درصد تخفیف: از $oldD% به $newD%\nقیمت خرید: از $oldBuy به $newBuy\n$retailLine"
                        }
                    }
                    setTextColor(AppSettings.safeParseColor(txtHex, Color.DKGRAY))
                    textSize = 13.5f
                    setPadding(0, 8, 0, 8)
                }

                val timeView = TextView(this@MainActivity).apply {
                    text = "🕒 زمان ثبت: ${item.optString("time")}"
                    textSize = 11.5f
                    setTextColor(Color.DKGRAY)
                }

                card.addView(titleView)
                card.addView(detailView)
                card.addView(timeView)
                return card
            }
        }

        historyListView = ListView(this).apply {
            dividerHeight = 12
            adapter = historyAdapter
        }

        layout.addView(btnManualCheck)
        layout.addView(searchRow)
        layout.addView(topInfoRow)
        layout.addView(historyListView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        return layout
    }

    private fun updateHistoryFilterSpinnerTitle() {
        if (!::historyTypeSpinner.isInitialized) return
        val currentPos = historyTypeSpinner.selectedItemPosition.coerceAtLeast(0)
        val threshold = AppSettings.getMajorDiscountThreshold(this)
        val types = listOf(
            "همه تغییرات",
            "🟢 افزایش درصد تخفیف",
            "🔴 کاهش درصد تخفیف",
            "🏷️ تغییر قیمت مصرف‌کننده",
            "🔥 تغییر تخفیف ویژه (${threshold}٪+)",
            "🆕 کالای جدید / شارژ مجدد"
        )
        historyTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        if (currentPos < types.size) historyTypeSpinner.setSelection(currentPos)
    }

    private fun loadAndFilterHistory() {
        allHistoryItems = DiscountEngine.getHistoryList(this)
        applyHistoryFilters()
    }

    private fun applyHistoryFilters() {
        val prefs = getSharedPreferences("AllBoxPrefs", MODE_PRIVATE)
        val lastCheck = prefs.getString("LAST_CHECK_TIME", "هنوز بررسی نشده")
        val query = historySearchEditText.text.toString().trim()
        val typeFilter = historyTypeSpinner.selectedItemPosition
        val threshold = AppSettings.getMajorDiscountThreshold(this)

        displayedHistoryItems = allHistoryItems.filter { item ->
            val matchQuery = query.isEmpty() ||
                item.optString("name", "").contains(query, ignoreCase = true) ||
                item.optString("mainCategory", "").contains(query, ignoreCase = true) ||
                item.optString("category", "").contains(query, ignoreCase = true)

            val recType = item.optString("type", "CHANGE")
            val isBetter = item.optBoolean("isBetter", true)
            val oldD = item.optInt("oldDiscount", 0)
            val newD = item.optInt("newDiscount", 0)
            val isMajor = abs(newD - oldD) >= threshold && recType != "RETAIL_PRICE_UP" && recType != "RETAIL_PRICE_DOWN" && recType != "NEW_PRODUCT"

            val matchType = when (typeFilter) {
                1 -> recType == "DISCOUNT_UP" || (recType == "CHANGE" && isBetter)
                2 -> recType == "DISCOUNT_DOWN" || (recType == "CHANGE" && !isBetter)
                3 -> recType == "RETAIL_PRICE_UP" || recType == "RETAIL_PRICE_DOWN"
                4 -> isMajor
                5 -> recType == "NEW_PRODUCT"
                else -> true
            }
            matchQuery && matchType
        }

        statusTextView.text = "آخرین بروزرسانی: $lastCheck | نمایش ${displayedHistoryItems.size} تغییر"
        historyAdapter.notifyDataSetChanged()
    }

    // ==================== تب ۲: همه محصولات ====================
    private fun createAllProductsTab(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
        }

        val prefs = getSharedPreferences("AllBoxPrefs", MODE_PRIVATE)

        val topSearchSortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
        }

        searchEditText = EditText(this).apply {
            hint = "🔍 جستجوی نام کالا یا برند..."
            setSingleLine(true)
            setBackgroundColor(Color.WHITE)
            setPadding(18, 14, 18, 14)
            textSize = 13.5f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applyProductFilters()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        sortSpinner = Spinner(this).apply {
            setBackgroundColor(Color.WHITE)
            val sortOptions = listOf(
                "⭐ محبوب‌ترین‌ها",
                "🔥 بیشترین تخفیف",
                "📉 کمترین تخفیف",
                "💰 ارزان‌ترین",
                "💎 گران‌ترین",
                "📂 دسته‌بندی"
            )
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, sortOptions)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    applyProductFilters()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        topSearchSortRow.addView(searchEditText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f).apply {
            setMargins(0, 0, 8, 0)
        })
        topSearchSortRow.addView(sortSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f))

        val categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 6)
        }

        mainCategorySpinner = Spinner(this).apply {
            setBackgroundColor(Color.WHITE)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!isUpdatingSpinners) {
                        populateSubCategorySpinner()
                        applyProductFilters()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        subCategorySpinner = Spinner(this).apply {
            setBackgroundColor(Color.WHITE)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!isUpdatingSpinners) {
                        applyProductFilters()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        categoryRow.addView(mainCategorySpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, 8, 0)
        })
        categoryRow.addView(subCategorySpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        inStockCheckBox = CheckBox(this).apply {
            text = "✅ فقط نمایش کالاهای موجود در انبار (حذف ناموجودها)"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F766E"))
            isChecked = prefs.getBoolean("ONLY_IN_STOCK_FILTER", false)
            setPadding(6, 4, 6, 4)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("ONLY_IN_STOCK_FILTER", isChecked).apply()
                loadAndFilterAllProducts()
            }
        }

        productsCountTv = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
            setPadding(4, 2, 4, 8)
        }

        productsAdapter = object : BaseAdapter() {
            override fun getCount(): Int = displayedProducts.size
            override fun getItem(position: Int): JSONObject = displayedProducts[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val card = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 16, 24, 16)
                }
                card.removeAllViews()

                val item = getItem(position)
                val rawName = item.optString("rawName", item.optString("name"))
                val mainCat = item.optString("mainCategory", "")
                val subCat = item.optString("category", "سایر")
                val brand = item.optString("brand", "")
                val discount = item.optInt("discount", 0)
                val price = AppSettings.formatPrice(this@MainActivity, item.optLong("price", 0L))
                val retail = AppSettings.formatPrice(this@MainActivity, item.optLong("retailPrice", 0L))
                val stock = item.optInt("stock", 0)
                val isPromoted = item.optBoolean("isPromoted", false)
                val majorThreshold = AppSettings.getMajorDiscountThreshold(this@MainActivity)

                card.setBackgroundColor(if (stock > 0) Color.WHITE else Color.parseColor("#F1F5F9"))

                val topBadgeTv = TextView(this@MainActivity).apply {
                    val catHierarchy = if (mainCat.isNotEmpty() && mainCat != subCat) "$mainCat > $subCat" else subCat
                    val brandPart = if (brand.isNotEmpty()) " | برند: $brand" else ""
                    val stockPart = if (stock > 0) "موجودی: $stock" else "❌ ناموجود"
                    val promoPart = if (isPromoted) "⭐ ویژه | " else ""
                    text = "${promoPart}📂 $catHierarchy$brandPart  |  $stockPart"
                    textSize = 11.5f
                    setTextColor(if (stock > 0) Color.parseColor("#475569") else Color.parseColor("#B91C1C"))
                }

                val nameTv = TextView(this@MainActivity).apply {
                    text = rawName
                    textSize = 14.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (stock > 0) Color.BLACK else Color.GRAY)
                    setPadding(0, 6, 0, 6)
                }

                val priceTv = TextView(this@MainActivity).apply {
                    text = "🏷️ تخفیف: $discount%   |   خرید: $price\nقیمت مصرف‌کننده: $retail"
                    textSize = 13.5f
                    setTextColor(
                        when {
                            stock <= 0 -> Color.GRAY
                            discount >= majorThreshold -> AppSettings.safeParseColor(AppSettings.getTextColorHex(this@MainActivity, "MAJOR"), Color.parseColor("#6B21A8"))
                            discount >= 10 -> AppSettings.safeParseColor(AppSettings.getTextColorHex(this@MainActivity, "UP"), Color.parseColor("#15803D"))
                            else -> Color.parseColor("#1D4ED8")
                        }
                    )
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

        layout.addView(topSearchSortRow)
        layout.addView(categoryRow)
        layout.addView(inStockCheckBox)
        layout.addView(productsCountTv)
        layout.addView(productsListView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        return layout
    }

    private fun loadAndFilterAllProducts() {
        isUpdatingSpinners = true
        allCachedProducts = DiscountEngine.getAllProductsList(this)

        val onlyInStock = inStockCheckBox.isChecked
        val baseList = if (onlyInStock) {
            allCachedProducts.filter { it.optInt("stock", 0) > 0 }
        } else {
            allCachedProducts
        }

        val mainCatCounts = mutableMapOf<String, Int>()
        for (prod in baseList) {
            val mCat = prod.optString("mainCategory", "").ifEmpty { prod.optString("category", "سایر") }
            mainCatCounts[mCat] = (mainCatCounts[mCat] ?: 0) + 1
        }

        val prevMain = (mainCategorySpinner.selectedItem as? String)?.substringBefore(" (")
        val mainItems = mutableListOf("همه دسته‌های اصلی (${baseList.size})")
        mainCatCounts.entries.sortedByDescending { it.value }.forEach { (mCat, count) ->
            mainItems.add("$mCat ($count)")
        }

        mainCategorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mainItems)
        if (prevMain != null) {
            val idx = mainItems.indexOfFirst { it.startsWith("$prevMain (") }
            if (idx >= 0) mainCategorySpinner.setSelection(idx)
        }

        populateSubCategorySpinner()
        isUpdatingSpinners = false
        applyProductFilters()
    }

    private fun populateSubCategorySpinner() {
        val onlyInStock = inStockCheckBox.isChecked
        val selectedMainRaw = (mainCategorySpinner.selectedItem as? String) ?: "همه دسته‌های اصلی"
        val selectedMain = if (selectedMainRaw.startsWith("همه دسته‌های اصلی")) null else selectedMainRaw.substringBefore(" (")

        val relevantProducts = allCachedProducts.filter { item ->
            val matchStock = !onlyInStock || item.optInt("stock", 0) > 0
            val itemMain = item.optString("mainCategory", "").ifEmpty { item.optString("category", "سایر") }
            val matchMain = selectedMain == null || itemMain == selectedMain
            matchStock && matchMain
        }

        val subCatCounts = mutableMapOf<String, Int>()
        for (prod in relevantProducts) {
            val sCat = prod.optString("category", "سایر")
            subCatCounts[sCat] = (subCatCounts[sCat] ?: 0) + 1
        }

        val prevSub = (subCategorySpinner.selectedItem as? String)?.substringBefore(" (")
        val subItems = mutableListOf("همه زیردسته‌ها (${relevantProducts.size})")
        subCatCounts.entries.sortedByDescending { it.value }.forEach { (sCat, count) ->
            subItems.add("$sCat ($count)")
        }

        subCategorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, subItems)
        if (prevSub != null) {
            val idx = subItems.indexOfFirst { it.startsWith("$prevSub (") }
            if (idx >= 0) subCategorySpinner.setSelection(idx)
        }
    }

    private fun applyProductFilters() {
        val selectedMainRaw = (mainCategorySpinner.selectedItem as? String) ?: "همه دسته‌های اصلی"
        val selectedMain = if (selectedMainRaw.startsWith("همه دسته‌های اصلی")) null else selectedMainRaw.substringBefore(" (")

        val selectedSubRaw = (subCategorySpinner.selectedItem as? String) ?: "همه زیردسته‌ها"
        val selectedSub = if (selectedSubRaw.startsWith("همه زیردسته‌ها")) null else selectedSubRaw.substringBefore(" (")

        val query = searchEditText.text.toString().trim()
        val onlyInStock = inStockCheckBox.isChecked
        val sortIndex = sortSpinner.selectedItemPosition

        var filtered = allCachedProducts.filter { item ->
            val matchStock = !onlyInStock || item.optInt("stock", 0) > 0
            val itemMain = item.optString("mainCategory", "").ifEmpty { item.optString("category", "سایر") }
            val itemSub = item.optString("category", "سایر")

            val matchMain = selectedMain == null || itemMain == selectedMain
            val matchSub = selectedSub == null || itemSub == selectedSub
            val matchSearch = query.isEmpty() ||
                item.optString("name", "").contains(query, ignoreCase = true) ||
                item.optString("brand", "").contains(query, ignoreCase = true)

            matchStock && matchMain && matchSub && matchSearch
        }

        val popularityComparator = Comparator<JSONObject> { a, b ->
            val stockA = if (a.optInt("stock", 0) > 0) 1 else 0
            val stockB = if (b.optInt("stock", 0) > 0) 1 else 0
            if (stockA != stockB) return@Comparator stockB.compareTo(stockA)

            val promoA = if (a.optBoolean("isPromoted", false)) 1 else 0
            val promoB = if (b.optBoolean("isPromoted", false)) 1 else 0
            if (promoA != promoB) return@Comparator promoB.compareTo(promoA)

            val scoreA = a.optInt("salesScore", 0)
            val scoreB = b.optInt("salesScore", 0)
            if (scoreA != scoreB) return@Comparator scoreB.compareTo(scoreA)

            val rankA = a.optInt("rank", Int.MAX_VALUE)
            val rankB = b.optInt("rank", Int.MAX_VALUE)
            rankA.compareTo(rankB)
        }

        val categoryComparator = Comparator<JSONObject> { a, b ->
            val c1 = a.optString("mainCategory", "").compareTo(b.optString("mainCategory", ""))
            if (c1 != 0) return@Comparator c1
            val c2 = a.optString("category", "").compareTo(b.optString("category", ""))
            if (c2 != 0) return@Comparator c2
            a.optString("rawName", "").compareTo(b.optString("rawName", ""))
        }

        filtered = when (sortIndex) {
            0 -> filtered.sortedWith(popularityComparator)
            1 -> filtered.sortedByDescending { it.optInt("discount", 0) }
            2 -> filtered.sortedBy { it.optInt("discount", 0) }
            3 -> filtered.sortedBy { it.optLong("price", 0L) }
            4 -> filtered.sortedByDescending { it.optLong("price", 0L) }
            else -> filtered.sortedWith(categoryComparator)
        }

        displayedProducts = filtered
        productsCountTv.text = "نمایش ${displayedProducts.size} کالا (از مجموع ${allCachedProducts.size} کالای سایت)"
        productsAdapter.notifyDataSetChanged()
    }

    // ==================== تب ۳: زمان‌بندی ====================
    private fun createScheduleTab(): View {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val prefs = getSharedPreferences("AllBoxPrefs", MODE_PRIVATE)

        batteryStatusBtn = Button(this).apply {
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            layoutParams = params
            setOnClickListener {
                requestIgnoreBatteryOptimization()
            }
        }
        updateBatteryButtonState()

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
                AlarmHelper.updatePeriodicSchedule(this@MainActivity, isChecked, currentMin, forceReset = true)
                Toast.makeText(
                    context,
                    if (isChecked) "بررسی خودکار هر $currentMin دقیقه فعال شد" else "بررسی خودکار متوقف شد",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val btnChangeInterval = Button(this).apply {
            text = "✏️ تغییر فاصله زمانی بررسی خودکار (دقیقه)"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                showChangeIntervalDialog()
            }
        }

        AlarmHelper.restoreAllSchedules(this)

        periodicCard.addView(switchPeriodic)
        periodicCard.addView(intervalInfoTextView)
        periodicCard.addView(btnChangeInterval)

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
                    Toast.makeText(context, "ساعت $timeStr اضافه شد (مستقل از تایمر دوره‌ای اجرا می‌شود)", Toast.LENGTH_SHORT).show()
                }, 12, 0, true).show()
            }
        }

        val titleAlarms = TextView(this).apply {
            text = "ساعت‌های مشخص روزانه (مستقل از تایمر دوره‌ای - برای تغییر روی ✏️ ویرایش بزنید):"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 16, 0, 12)
        }

        alarmsListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        refreshAlarmsListUI()

        layout.addView(batteryStatusBtn)
        layout.addView(periodicCard)
        layout.addView(btnAddTime)
        layout.addView(titleAlarms)
        layout.addView(alarmsListLayout)
        scroll.addView(layout)

        return scroll
    }

    // ==================== تب ۴: تنظیمات کامل و شخصی‌سازی ====================
    private fun createSettingsTab(): View {
        val scroll = ScrollView(this)
        settingsContentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 40)
        }
        scroll.addView(settingsContentContainer)
        rebuildSettingsUI()
        return scroll
    }

    private fun rebuildSettingsUI() {
        if (!::settingsContentContainer.isInitialized) return
        settingsContentContainer.removeAllViews()

        // -------- بخش ۱: قوانین و حد نصاب هشدارها --------
        val rulesCard = createSectionCard("🎯 قوانین و حد نصاب هشدارها")

        val currentMajor = AppSettings.getMajorDiscountThreshold(this)
        rulesCard.addView(createNumberSettingRow(
            title = "حد نصاب «تغییر ویژه تخفیف» (نوتیفیکیشن و رنگ مجزا)",
            currentValue = "$currentMajor درصد",
            dialogTitle = "درصد تغییر ویژه تخفیف",
            dialogMsg = "وقتی درصد تخفیف یک کالا حداقل چند درصد تغییر کرد، نوتیفیکیشن ویژه و رنگ متمایز داشته باشد؟ (مثلاً 15 یا 20 یا 30):",
            initialInt = currentMajor
        ) { newVal ->
            AppSettings.setMajorDiscountThreshold(this, newVal)
            rebuildSettingsUI()
        })

        val currentMinDiff = AppSettings.getMinDiscountDiff(this)
        rulesCard.addView(createNumberSettingRow(
            title = "حداقل تغییر درصد تخفیف برای ثبت در برنامه",
            currentValue = "$currentMinDiff درصد",
            dialogTitle = "حداقل درصد تغییر تخفیف",
            dialogMsg = "تغییرات کمتر از این عدد نادیده گرفته می‌شوند (برای ثبت همه تغییرات عدد 1 را وارد کنید):",
            initialInt = currentMinDiff
        ) { newVal ->
            AppSettings.setMinDiscountDiff(this, newVal)
            rebuildSettingsUI()
        })

        rulesCard.addView(createSwitchRow(
            title = "فقط بررسی تغییرات کالاهای موجود در انبار (نادیده گرفتن ناموجودها)",
            checked = AppSettings.getOnlyAvailableProducts(this)
        ) { isChecked ->
            AppSettings.setOnlyAvailableProducts(this, isChecked)
        })

        rulesCard.addView(createSwitchRow(
            title = "اطلاع‌رسانی هنگام شارژ مجدد کالایی که ناموجود بوده است",
            checked = AppSettings.getTrackBackInStock(this)
        ) { isChecked ->
            AppSettings.setTrackBackInStock(this, isChecked)
        })

        settingsContentContainer.addView(rulesCard)

        // -------- بخش ۲: شخصی‌سازی رنگ پیام‌های تغییر --------
        val colorsCard = createSectionCard("🎨 شخصی‌سازی رنگ پیام‌های تغییر")

        val colorItems = listOf(
            Triple("MAJOR", "🔥 تغییر ویژه تخفیف (${currentMajor}٪+)", "نمونه کارت تغییر ویژه تخفیف"),
            Triple("UP", "🟢 افزایش درصد تخفیف", "نمونه کارت افزایش درصد تخفیف"),
            Triple("DOWN", "🔴 کاهش درصد تخفیف", "نمونه کارت کاهش درصد تخفیف"),
            Triple("RETAIL", "🏷️ تغییر قیمت مصرف‌کننده", "نمونه کارت تغییر قیمت مصرف‌کننده"),
            Triple("NEW", "🆕 کالای جدید / شارژ مجدد", "نمونه کارت افزوده شدن کالای جدید")
        )

        for ((key, label, sampleText) in colorItems) {
            val bgHex = AppSettings.getBgColorHex(this, key)
            val txtHex = AppSettings.getTextColorHex(this, key)

            val itemRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(AppSettings.safeParseColor(bgHex, Color.WHITE))
                setPadding(20, 16, 20, 16)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
                layoutParams = params
            }

            val previewTv = TextView(this).apply {
                text = "$label\n(پیش‌نمایش رنگ پس‌زمینه و متن)"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(AppSettings.safeParseColor(txtHex, Color.BLACK))
            }

            val btnPick = Button(this).apply {
                text = "🎨 تغییر رنگ"
                textSize = 11.5f
                setOnClickListener {
                    showColorPickerDialog(key, label, sampleText)
                }
            }

            itemRow.addView(previewTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            itemRow.addView(btnPick)
            colorsCard.addView(itemRow)
        }

        settingsContentContainer.addView(colorsCard)

        // -------- بخش ۳: مدیریت تفکیکی نوتیفیکیشن‌ها --------
        val notifCard = createSectionCard("🔔 مدیریت تفکیکی ارسال نوتیفیکیشن")

        notifCard.addView(createSwitchRow(
            title = "ارسال نوتیفیکیشن مجزا برای تغییرات ویژه تخفیف (${currentMajor}٪+)",
            checked = AppSettings.isNotifMajorEnabled(this)
        ) { AppSettings.setNotifMajorEnabled(this, it) })

        notifCard.addView(createSwitchRow(
            title = "ارسال نوتیفیکیشن برای افزایش درصد تخفیف",
            checked = AppSettings.isNotifDiscountUpEnabled(this)
        ) { AppSettings.setNotifDiscountUpEnabled(this, it) })

        notifCard.addView(createSwitchRow(
            title = "ارسال نوتیفیکیشن برای کاهش درصد تخفیف",
            checked = AppSettings.isNotifDiscountDownEnabled(this)
        ) { AppSettings.setNotifDiscountDownEnabled(this, it) })

        notifCard.addView(createSwitchRow(
            title = "ارسال نوتیفیکیشن برای تغییر قیمت مصرف‌کننده",
            checked = AppSettings.isNotifRetailChangeEnabled(this)
        ) { AppSettings.setNotifRetailChangeEnabled(this, it) })

        notifCard.addView(createSwitchRow(
            title = "ارسال نوتیفیکیشن برای کالاهای جدید / شارژ مجدد",
            checked = AppSettings.isNotifNewProductEnabled(this)
        ) { AppSettings.setNotifNewProductEnabled(this, it) })

        settingsContentContainer.addView(notifCard)

        // -------- بخش ۴: نمایش، واحد پول و حافظه --------
        val displayCard = createSectionCard("💰 واحد پول، نمایش و حافظه")

        displayCard.addView(createSwitchRow(
            title = "نمایش تمام قیمت‌ها به «تومان» به جای «ریال» (حذف یک صفر)",
            checked = AppSettings.isUseToman(this)
        ) { isChecked ->
            AppSettings.setUseToman(this, isChecked)
            Toast.makeText(this, if (isChecked) "واحد پول به تومان تغییر یافت" else "واحد پول به ریال تغییر یافت", Toast.LENGTH_SHORT).show()
        })

        val maxHist = AppSettings.getMaxHistoryItems(this)
        displayCard.addView(createNumberSettingRow(
            title = "حداکثر تعداد نگهداری تاریخچه تغییرات",
            currentValue = "$maxHist مورد",
            dialogTitle = "ظرفیت تاریخچه تغییرات",
            dialogMsg = "حداکثر چند تغییر آخر در حافظه گوشی نگهداری شود؟ (مثلاً 500 یا 1000):",
            initialInt = maxHist
        ) { newVal ->
            AppSettings.setMaxHistoryItems(this, newVal)
            rebuildSettingsUI()
        })

        val maxLines = AppSettings.getMaxNotifLines(this)
        displayCard.addView(createNumberSettingRow(
            title = "حداکثر تعداد کالاهای نمایش داده شده در هر نوتیفیکیشن",
            currentValue = "$maxLines خط",
            dialogTitle = "تعداد خطوط نوتیفیکیشن",
            dialogMsg = "در هر نوتیفیکیشن حداکثر چند کالای تغییر یافته فهرست شود؟ (مثلاً 10 یا 15):",
            initialInt = maxLines
        ) { newVal ->
            AppSettings.setMaxNotifLines(this, newVal)
            rebuildSettingsUI()
        })

        settingsContentContainer.addView(displayCard)

        // -------- بخش ۵: موقعیت مکانی و تنظیمات پیشرفته سرور --------
        val serverCard = createSectionCard("🌍 موقعیت مکانی و تنظیمات پیشرفته")

        val currentLat = AppSettings.getLatitude(this)
        val currentLng = AppSettings.getLongitude(this)
        val locRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }
        val locTv = TextView(this).apply {
            text = "مختصات فروشگاه:\nLat: $currentLat | Lng: $currentLng"
            textSize = 12.5f
            setTextColor(Color.DKGRAY)
        }
        val btnEditLoc = Button(this).apply {
            text = "✏️ ویرایش مختصات"
            textSize = 11.5f
            setOnClickListener { showLocationDialog() }
        }
        locRow.addView(locTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        locRow.addView(btnEditLoc)
        serverCard.addView(locRow)

        val maxCat = AppSettings.getMaxCategoryScan(this)
        serverCard.addView(createNumberSettingRow(
            title = "حداکثر شماره دسته‌بندی برای اسکن کامل سایت",
            currentValue = "$maxCat دسته‌بندی",
            dialogTitle = "تعداد دسته‌بندی‌های اسکن",
            dialogMsg = "پیش‌فرض 60 است. اگر دسته‌بندی‌های جدیدی به سایت اضافه شد می‌توانید آن را افزایش دهید:",
            initialInt = maxCat
        ) { newVal ->
            AppSettings.setMaxCategoryScan(this, newVal)
            rebuildSettingsUI()
        })

        val btnClearSnapshot = Button(this).apply {
            text = "🧹 پاکسازی حافظه پایه کالاها (شروع اسکن از صفر)"
            setBackgroundColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 20, 0, 10) }
            layoutParams = params
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("پاکسازی حافظه پایه کالاها")
                    .setMessage("آیا مطمئن هستید؟ با این کار در بروزرسانی بعدی، لیست کالاها از نو به عنوان پایه ذخیره می‌شود.")
                    .setPositiveButton("بله، پاک شود") { _, _ ->
                        DiscountEngine.clearProductsSnapshot(this@MainActivity)
                        Toast.makeText(this@MainActivity, "حافظه پایه کالاها پاک شد", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("انصراف", null)
                    .show()
            }
        }

        val btnResetDefaults = Button(this).apply {
            text = "♻️ بازگردانی تمام تنظیمات و رنگ‌ها به حالت پیش‌فرض"
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
            layoutParams = params
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("بازگشت به تنظیمات پیش‌فرض")
                    .setMessage("آیا تمام رنگ‌ها و تنظیمات به حالت اولیه برگردند؟ (لاگین و تاریخچه شما پاک نمی‌شود)")
                    .setPositiveButton("بله، بازگردانی شود") { _, _ ->
                        AppSettings.resetAllToDefaults(this@MainActivity)
                        rebuildSettingsUI()
                        Toast.makeText(this@MainActivity, "تنظیمات به حالت پیش‌فرض بازگشت", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("انصراف", null)
                    .show()
            }
        }

        serverCard.addView(btnClearSnapshot)
        serverCard.addView(btnResetDefaults)
        settingsContentContainer.addView(serverCard)
    }

    private fun createSectionCard(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(24, 20, 24, 20)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
            layoutParams = params

            val headerTv = TextView(this@MainActivity).apply {
                text = title
                textSize = 15.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E293B"))
                setPadding(0, 0, 0, 14)
            }
            addView(headerTv)
        }
    }

    private fun createSwitchRow(title: String, checked: Boolean, onChanged: (Boolean) -> Unit): View {
        return Switch(this).apply {
            text = title
            textSize = 13.5f
            isChecked = checked
            setPadding(0, 14, 0, 14)
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
    }

    private fun createNumberSettingRow(
        title: String,
        currentValue: String,
        dialogTitle: String,
        dialogMsg: String,
        initialInt: Int,
        onSaved: (Int) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 10)
        }
        val tv = TextView(this).apply {
            text = "$title:\nفعلی: $currentValue"
            textSize = 13.5f
            setTextColor(Color.parseColor("#334155"))
        }
        val btn = Button(this).apply {
            text = "✏️ تغییر"
            textSize = 12f
            setOnClickListener {
                val input = EditText(this@MainActivity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(initialInt.toString())
                    gravity = Gravity.CENTER
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(dialogTitle)
                    .setMessage(dialogMsg)
                    .setView(input)
                    .setPositiveButton("ذخیره") { _, _ ->
                        val v = input.text.toString().toIntOrNull() ?: initialInt
                        onSaved(v)
                    }
                    .setNegativeButton("انصراف", null)
                    .show()
            }
        }
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn)
        return row
    }

    // پنجره انتخاب رنگ (شامل ۱۰ تم آماده + ورود کد رنگ دلخواه و پیش‌نمایش زنده)
    private fun showColorPickerDialog(key: String, label: String, sampleText: String) {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }

        var selectedBg = AppSettings.getBgColorHex(this, key)
        var selectedTxt = AppSettings.getTextColorHex(this, key)

        val previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppSettings.safeParseColor(selectedBg, Color.WHITE))
            setPadding(24, 20, 24, 20)
        }
        val previewTitle = TextView(this).apply {
            text = sampleText
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }
        val previewDetail = TextView(this).apply {
            text = "$label | تخفیف: از 10% به 35%"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(AppSettings.safeParseColor(selectedTxt, Color.DKGRAY))
            setPadding(0, 8, 0, 0)
        }
        previewBox.addView(previewTitle)
        previewBox.addView(previewDetail)

        val bgInput = EditText(this).apply {
            setText(selectedBg)
            hint = "کد رنگ پس‌زمینه (مثلاً #F3E8FF)"
            setSingleLine(true)
        }
        val txtInput = EditText(this).apply {
            setText(selectedTxt)
            hint = "کد رنگ متن (مثلاً #6B21A8)"
            setSingleLine(true)
        }

        fun updateLivePreview(bg: String, txt: String) {
            previewBox.setBackgroundColor(AppSettings.safeParseColor(bg, Color.WHITE))
            previewDetail.setTextColor(AppSettings.safeParseColor(txt, Color.BLACK))
        }

        val presets = listOf(
            Triple("🟣 بنفش ویژه (پیش‌فرض تخفیف بالا)", "#F3E8FF", "#6B21A8"),
            Triple("🟢 سبز زمردی (پیش‌فرض افزایش تخفیف)", "#F0FDF4", "#15803D"),
            Triple("🔴 قرمز یاقوتی (پیش‌فرض کاهش تخفیف)", "#FEF2F2", "#B91C1C"),
            Triple("🟠 نارنجی کهربایی (پیش‌فرض قیمت مصرف‌کننده)", "#FFF7ED", "#C2410C"),
            Triple("🔵 آبی اقیانوسی (پیش‌فرض کالای جدید)", "#EFF6FF", "#1D4ED8"),
            Triple("🟡 طلایی / زرد روشن", "#FEFCE8", "#A16207"),
            Triple("🌸 صورتی / سرخابی", "#FDF2F8", "#BE185D"),
            Triple("💎 فیروزه‌ای شاد", "#ECFEFF", "#0E7490"),
            Triple("🌑 مشکی / تیره مدرن", "#1E293B", "#38BDF8"),
            Triple("⚪ سفید ساده با متن سرمه‌ای", "#FFFFFF", "#0F172A")
        )

        val presetTitle = TextView(this).apply {
            text = "۱. انتخاب سریع از رنگ‌های آماده:"
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 10)
        }

        container.addView(previewBox)
        container.addView(presetTitle)

        for ((name, pBg, pTxt) in presets) {
            val btn = Button(this).apply {
                text = name
                textSize = 12.5f
                setBackgroundColor(AppSettings.safeParseColor(pBg, Color.WHITE))
                setTextColor(AppSettings.safeParseColor(pTxt, Color.BLACK))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 6, 0, 6) }
                layoutParams = params
                setOnClickListener {
                    selectedBg = pBg
                    selectedTxt = pTxt
                    bgInput.setText(pBg)
                    txtInput.setText(pTxt)
                    updateLivePreview(pBg, pTxt)
                }
            }
            container.addView(btn)
        }

        val manualTitle = TextView(this).apply {
            text = "۲. یا وارد کردن دستی کد رنگ (HEX):"
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
        container.addView(manualTitle)
        container.addView(bgInput)
        container.addView(txtInput)

        scroll.addView(container)

        AlertDialog.Builder(this)
            .setTitle("تنظیم رنگ: $label")
            .setView(scroll)
            .setPositiveButton("ذخیره رنگ") { _, _ ->
                val finalBg = bgInput.text.toString().trim().ifEmpty { selectedBg }
                val finalTxt = txtInput.text.toString().trim().ifEmpty { selectedTxt }
                AppSettings.setCustomColors(this, key, finalBg, finalTxt)
                rebuildSettingsUI()
                Toast.makeText(this, "رنگ جدید ذخیره شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showLocationDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 20, 32, 10)
        }
        val latEdit = EditText(this).apply {
            setText(AppSettings.getLatitude(this@MainActivity))
            hint = "Latitude (عرض جغرافیایی)"
            setSingleLine(true)
        }
        val lngEdit = EditText(this).apply {
            setText(AppSettings.getLongitude(this@MainActivity))
            hint = "Longitude (طول جغرافیایی)"
            setSingleLine(true)
        }
        layout.addView(latEdit)
        layout.addView(lngEdit)

        AlertDialog.Builder(this)
            .setTitle("ویرایش مختصات فروشگاه")
            .setView(layout)
            .setPositiveButton("ذخیره") { _, _ ->
                val newLat = latEdit.text.toString().trim()
                val newLng = lngEdit.text.toString().trim()
                if (newLat.isNotEmpty() && newLng.isNotEmpty()) {
                    AppSettings.setLatitude(this, newLat)
                    AppSettings.setLongitude(this, newLng)
                    rebuildSettingsUI()
                    Toast.makeText(this, "مختصات جدید ذخیره شد", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun updateBatteryButtonState() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        if (isIgnoring) {
            batteryStatusBtn.text = "✅ اجرای بدون محدودیت در پس‌زمینه فعال است"
            batteryStatusBtn.setBackgroundColor(Color.parseColor("#15803D"))
            batteryStatusBtn.setTextColor(Color.WHITE)
        } else {
            batteryStatusBtn.text = "⚠️ ضربه بزنید: فعال‌سازی اجرای دائمی در پس‌زمینه (مهم)"
            batteryStatusBtn.setBackgroundColor(Color.parseColor("#DC2626"))
            batteryStatusBtn.setTextColor(Color.WHITE)
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        }
    }

    private fun showChangeIntervalDialog() {
        val prefs = getSharedPreferences("AllBoxPrefs", MODE_PRIVATE)
        val currentMin = prefs.getInt("AUTO_INTERVAL_MINUTES", 30)

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentMin.toString())
            hint = "مثلاً 10 یا 15 یا 30"
            gravity = Gravity.CENTER
        }

        AlertDialog.Builder(this)
            .setTitle("تنظیم فاصله بررسی خودکار")
            .setMessage("هر چند دقیقه یک‌بار لیست تخفیف‌ها به صورت خودکار بررسی شود؟")
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                val entered = input.text.toString().toIntOrNull() ?: 30
                val validMinutes = maxOf(1, entered)
                prefs.edit().putInt("AUTO_INTERVAL_MINUTES", validMinutes).apply()
                intervalInfoTextView.text = "⏱️ فاصله زمانی بررسی خودکار فعلی: هر $validMinutes دقیقه یک‌بار"

                val enabled = prefs.getBoolean("AUTO_CHECK_ENABLED", true)
                AlarmHelper.updatePeriodicSchedule(this, enabled, validMinutes, forceReset = true)
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

            val btnEdit = Button(this).apply {
                text = "✏️ ویرایش"
                textSize = 12f
                setOnClickListener {
                    val parts = timeStr.split(":")
                    val oldH = parts[0].toInt()
                    val oldM = parts[1].toInt()

                    TimePickerDialog(this@MainActivity, { _, newHour, newMinute ->
                        AlarmHelper.cancelDailyAlarm(this@MainActivity, oldH, oldM)
                        val updated = prefs.getStringSet("CUSTOM_TIMES", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        updated.remove(timeStr)

                        val newTimeStr = String.format(Locale.US, "%02d:%02d", newHour, newMinute)
                        updated.add(newTimeStr)
                        prefs.edit().putStringSet("CUSTOM_TIMES", updated).apply()

                        AlarmHelper.scheduleDailyAlarm(this@MainActivity, newHour, newMinute)
                        refreshAlarmsListUI()
                        Toast.makeText(context, "ساعت $timeStr به $newTimeStr تغییر یافت", Toast.LENGTH_SHORT).show()
                    }, oldH, oldM, true).show()
                }
            }

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

    // ==================== تب ۵: ورود به حساب کاربری ====================
    private fun createLoginTab(): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val hintTv = TextView(this).apply {
            text = "یک بار در سایت زیر وارد حساب خود شوید؛ برنامه به صورت خودکار کوکی لاگین را در حافظه دائم ذخیره می‌کند."
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
                    DiscountEngine.syncAndGetCookies(this@MainActivity)
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
            textSize = 10.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setPadding(2, 10, 2, 10)
            setOnClickListener { onClick() }
        }
    }
}
