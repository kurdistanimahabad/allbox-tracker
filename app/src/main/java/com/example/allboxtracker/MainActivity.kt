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
import java.text.NumberFormat
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

    // اجزای تب ۳ و ۴
    private lateinit var alarmsListLayout: LinearLayout
    private lateinit var intervalInfoTextView: TextView
    private lateinit var batteryStatusBtn: Button
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

            if (index == 0) loadAndFilterHistory()
            if (index == 1) loadAndFilterAllProducts()
            if (index == 2) updateBatteryButtonState()
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

    override fun onResume() {
        super.onResume()
        if (::batteryStatusBtn.isInitialized) {
            updateBatteryButtonState()
        }
    }

    // ==================== تب ۱: لیست تغییرات (با فیلترهای جدید و رنگ ویژه برای بالای ۲۰٪) ====================
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

        // ردیف جستجو و فیلتر در تب تغییرات
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
            val types = listOf(
                "همه تغییرات",
                "🟢 افزایش درصد تخفیف",
                "🔴 کاهش درصد تخفیف",
                "🏷️ تغییر قیمت مصرف‌کننده",
                "🔥 تغییر تخفیف بالای ۲۰٪",
                "🆕 کالای جدید"
            )
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, types)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    applyHistoryFilters()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

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

        val numFormat = NumberFormat.getNumberInstance(Locale.US)

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
                val isMajor = item.optBoolean("isMajorChange", abs(newD - oldD) > 20 && recordType != "RETAIL_PRICE_UP" && recordType != "RETAIL_PRICE_DOWN")

                // تعیین رنگ پس‌زمینه متمایز برای هر نوع تغییر (بنفش ویژه برای تغییرات بالای ۲۰ درصد)
                val bgColor = when {
                    isMajor && (recordType == "DISCOUNT_UP" || recordType == "DISCOUNT_DOWN" || recordType == "CHANGE") -> "#F3E8FF"
                    recordType == "RETAIL_PRICE_UP" || recordType == "RETAIL_PRICE_DOWN" -> "#FFF7ED"
                    recordType == "NEW_PRODUCT" -> "#EFF6FF"
                    recordType == "DISCOUNT_DOWN" || (!isBetter && recordType == "CHANGE") -> "#FEF2F2"
                    else -> "#F0FDF4"
                }
                card.setBackgroundColor(Color.parseColor(bgColor))

                val mainCat = item.optString("mainCategory", "")
                val titleView = TextView(this@MainActivity).apply {
                    val catPrefix = if (mainCat.isNotEmpty()) "$mainCat > " else ""
                    text = "$catPrefix${item.optString("name")}"
                    textSize = 14.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.BLACK)
                }

                val oldBuy = numFormat.format(item.optLong("oldPrice", 0L))
                val newBuy = numFormat.format(item.optLong("newPrice", 0L))

                val newRetailLong = item.optLong("newRetailPrice", item.optLong("oldPrice", 0L))
                val oldRetailLong = item.optLong("oldRetailPrice", newRetailLong)
                val oldRetail = numFormat.format(oldRetailLong)
                val newRetail = numFormat.format(newRetailLong)

                val retailLine = if (oldRetailLong > 0 && oldRetailLong != newRetailLong && recordType != "NEW_PRODUCT") {
                    "قیمت مصرف‌کننده: از $oldRetail به $newRetail ریال"
                } else if (newRetailLong > 0) {
                    "قیمت مصرف‌کننده: $newRetail ریال"
                } else {
                    "قیمت مصرف‌کننده: ثبت نشده"
                }

                val detailView = TextView(this@MainActivity).apply {
                    when {
                        recordType == "NEW_PRODUCT" -> {
                            text = "🆕 محصول جدید!  |  تخفیف: $newD%\nقیمت خرید: $newBuy ریال\n$retailLine"
                            setTextColor(Color.parseColor("#1D4ED8"))
                        }
                        recordType == "RETAIL_PRICE_UP" -> {
                            text = "📈 افزایش قیمت مصرف‌کننده: از $oldRetail به $newRetail ریال\nقیمت خرید: از $oldBuy به $newBuy ریال (تخفیف فعلی: $newD%)"
                            setTextColor(Color.parseColor("#C2410C"))
                        }
                        recordType == "RETAIL_PRICE_DOWN" -> {
                            text = "📉 کاهش قیمت مصرف‌کننده: از $oldRetail به $newRetail ریال\nقیمت خرید: از $oldBuy به $newBuy ریال (تخفیف فعلی: $newD%)"
                            setTextColor(Color.parseColor("#B45309"))
                        }
                        isMajor -> {
                            val diff = abs(newD - oldD)
                            val majorLabel = if (isBetter) "🔥 افزایش ویژه تخفیف (+$diff%):" else "⚠️ کاهش شدید تخفیف (-$diff%):"
                            text = "$majorLabel از $oldD% به $newD%\nقیمت خرید: از $oldBuy به $newBuy ریال\n$retailLine"
                            setTextColor(Color.parseColor("#6B21A8"))
                            setTypeface(null, Typeface.BOLD)
                        }
                        recordType == "DISCOUNT_DOWN" || !isBetter -> {
                            text = "🔴 کاهش درصد تخفیف: از $oldD% به $newD%\nقیمت خرید: از $oldBuy به $newBuy ریال\n$retailLine"
                            setTextColor(Color.parseColor("#B91C1C"))
                        }
                        else -> {
                            text = "🟢 افزایش درصد تخفیف: از $oldD% به $newD%\nقیمت خرید: از $oldBuy به $newBuy ریال\n$retailLine"
                            setTextColor(Color.parseColor("#15803D"))
                        }
                    }
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

    private fun loadAndFilterHistory() {
        allHistoryItems = DiscountEngine.getHistoryList(this)
        applyHistoryFilters()
    }

    private fun applyHistoryFilters() {
        val prefs = getSharedPreferences("AllBoxPrefs", MODE_PRIVATE)
        val lastCheck = prefs.getString("LAST_CHECK_TIME", "هنوز بررسی نشده")
        val query = historySearchEditText.text.toString().trim()
        val typeFilter = historyTypeSpinner.selectedItemPosition

        displayedHistoryItems = allHistoryItems.filter { item ->
            val matchQuery = query.isEmpty() ||
                item.optString("name", "").contains(query, ignoreCase = true) ||
                item.optString("mainCategory", "").contains(query, ignoreCase = true) ||
                item.optString("category", "").contains(query, ignoreCase = true)

            val recType = item.optString("type", "CHANGE")
            val isBetter = item.optBoolean("isBetter", true)
            val oldD = item.optInt("oldDiscount", 0)
            val newD = item.optInt("newDiscount", 0)
            val isMajor = item.optBoolean("isMajorChange", abs(newD - oldD) > 20 && recType != "RETAIL_PRICE_UP" && recType != "RETAIL_PRICE_DOWN")

            val matchType = when (typeFilter) {
                // ۱: افزایش درصد تخفیف
                1 -> recType == "DISCOUNT_UP" || (recType == "CHANGE" && isBetter)
                // ۲: کاهش درصد تخفیف
                2 -> recType == "DISCOUNT_DOWN" || (recType == "CHANGE" && !isBetter)
                // ۳: تغییر قیمت مصرف‌کننده
                3 -> recType == "RETAIL_PRICE_UP" || recType == "RETAIL_PRICE_DOWN"
                // ۴: تغییر تخفیف بالای ۲۰٪
                4 -> isMajor
                // ۵: کالای جدید
                5 -> recType == "NEW_PRODUCT"
                else -> true
            }
            matchQuery && matchType
        }

        statusTextView.text = "آخرین بروزرسانی: $lastCheck | نمایش ${displayedHistoryItems.size} تغییر"
        historyAdapter.notifyDataSetChanged()
    }

    // ==================== تب ۲: همه محصولات (با فیلتر دسته‌بندی اصلی و زیردسته) ====================
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

        val numFormat = NumberFormat.getNumberInstance(Locale.US)

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
                val price = numFormat.format(item.optLong("price", 0L))
                val retail = numFormat.format(item.optLong("retailPrice", 0L))
                val stock = item.optInt("stock", 0)
                val isPromoted = item.optBoolean("isPromoted", false)

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
                    text = "🏷️ تخفیف: $discount%   |   خرید: $price ریال\nقیمت مصرف‌کننده: $retail ریال"
                    textSize = 13.5f
                    setTextColor(
                        when {
                            stock <= 0 -> Color.GRAY
                            discount > 20 -> Color.parseColor("#6B21A8")
                            discount >= 10 -> Color.parseColor("#15803D")
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

    // ==================== تب ۳: زمان‌بندی + رفع محدودیت باتری ====================
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

    // ==================== تب ۴: ورود به حساب کاربری ====================
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
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setPadding(4, 12, 4, 12)
            setOnClickListener { onClick() }
        }
    }
}
