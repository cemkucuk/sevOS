package os.cemkuck.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** sevOS home. Framework views only: no bitmaps, no AndroidX, no network permission. */
class HomeActivity : Activity() {
    private class Item(val label: String, val sub: String, val icon: Drawable, val colors: IntArray?, val intent: Intent?, val action: (() -> Unit)? = null, val iconOnly: Boolean = false)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val turkish = Locale("tr")
    private lateinit var heroRow: LinearLayout
    private lateinit var quickRow: LinearLayout
    private lateinit var appsTitle: TextView
    private lateinit var appsGrid: GridLayout
    private lateinit var greeting: TextView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var ram: TextView
    private lateinit var temp: TextView
    private var rendered = ""

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadItems()
    }
    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            main.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        registerReceiver(packageChanges, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onResume() {
        super.onResume()
        loadItems()
        main.post(tick)
        if (!bootHandled) {
            bootHandled = true
            if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) onBoot()
        }
    }

    private fun onBoot() {
        val prefs = getSharedPreferences(ProjectorActivity.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(ProjectorActivity.KEY_AUTO, false)) {
            Sys.readTilt(this) { degrees -> degrees?.let(Sys::applyTilt) }
        }
        val pkg = prefs.getString(ProjectorActivity.KEY_BOOT, "").orEmpty()
        if (pkg.isNotEmpty()) launchIntent(pkg)?.let { runCatching { startActivity(it) } }
    }

    private fun cleanMemory() {
        Toast.makeText(this, "Bellek temizleniyor", Toast.LENGTH_SHORT).show()
        Sys.bg {
            val before = Sys.availableMb() ?: 0
            Sys.command("clean")
            Thread.sleep(CLEAN_WAIT_MS)
            val freed = ((Sys.availableMb() ?: before) - before).coerceAtLeast(0)
            main.post {
                if (isDestroyed) return@post
                Toast.makeText(this, "$freed MB boşaldı", Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageChanges)
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Home stays on top")
    override fun onBackPressed() = Unit

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(sizeSp: Float, color: Int, face: Typeface = Typeface.DEFAULT) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = face
        maxLines = 1
        includeFontPadding = false
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun pill() = text(12f, MUTED).apply {
        background = rounded(PILL, 99)
        setPadding(dp(11), dp(5), dp(11), dp(5))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0B1644.toInt(), 0xFF050A2A.toInt()))
            clipChildren = false
        }
        root.addView(View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(460).toFloat()
                setGradientCenter(0.86f, 0f)
                colors = intArrayOf(0x33F5C542, 0x00F5C542)
            }
        }, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(24), dp(36), dp(14))
            clipChildren = false
            clipToPadding = false
        }
        content.addView(header(), LinearLayout.LayoutParams(-1, -2))

        heroRow = row()
        content.addView(heroRow, LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(20) })
        quickRow = row()
        content.addView(quickRow, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(14) })

        appsTitle = text(13f, MUTED, Typeface.DEFAULT_BOLD).apply {
            this.text = "Uygulamalar"
            visibility = View.GONE
            setPadding(dp(8), dp(16), 0, dp(6))
        }
        content.addView(appsTitle)
        appsGrid = GridLayout(this).apply {
            columnCount = COLUMNS
            useDefaultMargins = false
            clipChildren = false
        }
        content.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            addView(appsGrid, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun row() = LinearLayout(this).apply { clipChildren = false }

    private fun header(): View {
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text(32f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            letterSpacing = 0.01f
            this.text = SpannableString("sevOS").apply {
                setSpan(ForegroundColorSpan(ACCENT_LIGHT), 3, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        })
        greeting = text(13f, MUTED).apply { setPadding(dp(1), dp(4), 0, 0) }
        brand.addView(greeting)
        bar.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))

        ram = pill()
        temp = pill()
        bar.addView(ram)
        bar.addView(temp)

        val time = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(14), 0, 0, 0)
        }
        clock = text(36f, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL))
        date = text(13f, MUTED).apply { setPadding(0, dp(3), dp(1), 0) }
        time.addView(clock)
        time.addView(date)
        bar.addView(time)
        return bar
    }

    private fun launchIntent(pkg: String) = packageManager.getLeanbackLaunchIntentForPackage(pkg)
        ?: packageManager.getLaunchIntentForPackage(pkg)

    private fun loadItems() {
        io.execute {
            val heroes = listOfNotNull(
                hero(YOUTUBE_PKG, "YouTube", "SmartTube", R.drawable.ic_play, 0xFFC8161F.toInt(), 0xFF6F0B13.toInt()),
                hero(IPTV_PKG, "OwnTV", "IPTV", R.drawable.ic_tv, 0xFFB8860B.toInt(), 0xFF6B4A06.toInt()),
                hero(HDMI_PKG, "HDMI", "Dış kaynak", R.drawable.ic_input, 0xFF5F82C8.toInt(), 0xFF1B3A8C.toInt()),
            )
            val quick = listOfNotNull(
                Item("Projektör", "", getDrawable(R.drawable.ic_corners)!!, null, Intent(this, ProjectorActivity::class.java)),
                launchIntent(MIRROR_PKG)?.let { Item("Yansıt", "", getDrawable(R.drawable.ic_cast)!!, null, it) },
                launchIntent(MEDIA_PKG)?.let { Item("Medya", "", getDrawable(R.drawable.ic_folder)!!, null, it) },
                Item("Ayarlar", "", getDrawable(R.drawable.ic_tune)!!, null, Intent().setClassName(SETTINGS_PKG, "$SETTINGS_PKG.MainSettings")),
                Item("Temizle", "", getDrawable(R.drawable.ic_memory)!!, null, null, ::cleanMemory),
                Item("Wi-Fi", "", getDrawable(R.drawable.ic_wifi)!!, null, Intent(Settings.ACTION_WIFI_SETTINGS), iconOnly = true),
                Item("Bluetooth", "", getDrawable(R.drawable.ic_bluetooth)!!, null, Intent().setClassName(SETTINGS_PKG, "$SETTINGS_PKG.accessories.AddAccessoryActivity"), iconOnly = true),
            )
            val query = Intent(Intent.ACTION_MAIN)
            val found = packageManager.queryIntentActivities(Intent(query).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
                packageManager.queryIntentActivities(Intent(query).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val others = found.asSequence()
                .map { it.activityInfo.packageName to it }
                .filter { it.first != packageName && it.first !in HERO_PKGS }
                .distinctBy { it.first }
                .mapNotNull { (pkg, info) ->
                    launchIntent(pkg)?.let { Item(info.loadLabel(packageManager).toString(), "", info.loadIcon(packageManager), null, it) }
                }
                .sortedBy { it.label.lowercase(turkish) }
                .toList()
            main.post { if (!isDestroyed) render(heroes, quick, others) }
        }
    }

    private fun hero(pkg: String, title: String, sub: String, icon: Int, from: Int, to: Int): Item? =
        launchIntent(pkg)?.let { Item(title, sub, getDrawable(icon)!!, intArrayOf(from, to), it) }

    private fun render(heroes: List<Item>, quick: List<Item>, others: List<Item>) {
        val signature = (heroes + quick + others).joinToString("|") { it.label }
        if (signature == rendered) {
            if (currentFocus == null) heroRow.getChildAt(0)?.requestFocus()
            return
        }
        rendered = signature
        val focused = currentFocus?.tag as? String

        heroRow.removeAllViews()
        heroes.forEach { heroRow.addView(heroCard(it), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(7), 0, dp(7), 0) }) }
        quickRow.removeAllViews()
        quick.forEach {
            val params = if (it.iconOnly) LinearLayout.LayoutParams(dp(62), -1) else LinearLayout.LayoutParams(0, -1, 1f)
            quickRow.addView(quickTile(it), params.apply { setMargins(dp(7), 0, dp(7), 0) })
        }
        appsGrid.removeAllViews()
        val width = (resources.displayMetrics.widthPixels - dp(72)) / COLUMNS
        others.forEach { appsGrid.addView(appTile(it, width)) }
        appsTitle.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE

        val all = listOf<ViewGroup>(heroRow, quickRow, appsGrid).flatMap { g -> (0 until g.childCount).map { g.getChildAt(it) } }
        (all.firstOrNull { it.tag == focused } ?: all.firstOrNull())?.requestFocus()
    }

    private fun bind(view: View, item: Item, scale: Float, onFocus: (Boolean) -> Unit) {
        view.tag = item.label
        view.isFocusable = true
        view.isClickable = true
        view.setOnClickListener { open(item) }
        view.setOnFocusChangeListener { v, has ->
            val s = if (has) scale else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(110).start()
            onFocus(has)
        }
    }

    private fun ring(radius: Int) = GradientDrawable().apply {
        setStroke(dp(2), 0xFFF5C542.toInt())
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(radius).toFloat()
    }

    private fun heroCard(item: Item): View = FrameLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, item.colors).apply { cornerRadius = dp(20).toFloat() }
        val outline = ring(20)
        bind(this, item, 1.05f) { has -> foreground = if (has) outline else null }
        addView(ImageView(context).apply { setImageDrawable(item.icon) }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START).apply { setMargins(dp(20), dp(20), 0, 0) })
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(this@HomeActivity.text(24f, Color.WHITE, Typeface.DEFAULT_BOLD).apply { this.text = item.label })
        words.addView(this@HomeActivity.text(13f, 0xCCFFFFFF.toInt()).apply {
            this.text = item.sub
            setPadding(0, dp(4), 0, 0)
        })
        addView(words, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(20), 0, 0, dp(18)) })
    }

    private fun quickTile(item: Item): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        val normal = rounded(TILE, 16)
        val active = rounded(ACCENT, 16)
        background = normal
        bind(this, item, 1.04f) { has -> background = if (has) active else normal }
        addView(ImageView(context).apply { setImageDrawable(item.icon) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        if (item.iconOnly) {
            contentDescription = item.label
        } else {
            addView(this@HomeActivity.text(16f, Color.WHITE).apply {
                this.text = item.label
                setPadding(dp(10), 0, 0, 0)
            })
        }
    }

    private fun appTile(item: Item, width: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val normal = rounded(TILE, 14)
        val active = rounded(ACCENT, 14)
        background = normal
        bind(this, item, 1.06f) { has -> background = if (has) active else normal }
        layoutParams = GridLayout.LayoutParams().apply {
            this.width = width - dp(14)
            height = dp(92)
            setMargins(dp(7), dp(7), dp(7), dp(7))
        }
        addView(ImageView(context).apply { setImageDrawable(item.icon) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(this@HomeActivity.text(12f, Color.WHITE).apply {
            this.text = item.label
            setPadding(dp(8), dp(6), dp(8), 0)
        })
    }

    private fun open(item: Item) {
        item.action?.let { return it() }
        try {
            startActivity(item.intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "${item.label} açılamadı", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "${item.label} açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatus() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.ROOT).format(now)
        date.text = SimpleDateFormat("d MMMM EEEE", turkish).format(now)
        greeting.text = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Günaydın"
            in 12..17 -> "İyi günler"
            in 18..22 -> "İyi akşamlar"
            else -> "İyi geceler"
        }
        io.execute {
            val free = runCatching {
                File("/proc/meminfo").useLines { lines ->
                    lines.first { it.startsWith("MemAvailable:") }.filter(Char::isDigit).toLong() / 1024
                }
            }.getOrNull()
            val heat = runCatching {
                File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toLong() / 1000
            }.getOrNull()
            main.post {
                if (isDestroyed) return@post
                ram.text = free?.let { "$it MB boş" } ?: ""
                temp.text = heat?.let { "$it°" } ?: ""
                ram.visibility = if (free == null) View.GONE else View.VISIBLE
                temp.visibility = if (heat == null) View.GONE else View.VISIBLE
            }
        }
    }

    companion object {
        const val YOUTUBE_PKG = "org.smarttube.stable"
        const val IPTV_PKG = "tv.own.owntv"
        const val HDMI_PKG = "com.android.rockchip.camera2"
        private const val MEDIA_PKG = "com.rockchips.mediacenter"
        private const val SETTINGS_PKG = "com.android.tv.set"
        private const val COLUMNS = 6
        private const val BOOT_WINDOW_MS = 180_000L
        private const val CLEAN_WAIT_MS = 2_500L
        private const val MIRROR_PKG = "io.github.jqssun.airplay"
        private val HERO_PKGS = setOf(YOUTUBE_PKG, IPTV_PKG, HDMI_PKG, MEDIA_PKG, MIRROR_PKG)
        private var bootHandled = false
        private const val MUTED = 0xFFAFC6F2.toInt()
        private const val PILL = 0xFF0F1B52.toInt()
        private const val TILE = 0xFF0F1B52.toInt()
        private const val ACCENT = 0xFFB3131B.toInt()
        private const val ACCENT_LIGHT = 0xFFF5C542.toInt()
    }
}
