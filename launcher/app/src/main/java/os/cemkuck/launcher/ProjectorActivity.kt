package os.cemkuck.launcher

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Projector controls: geometry, install position, colour, sleep timer and startup app. */
class ProjectorActivity : Activity() {
    private class Row(val title: String, val value: () -> String, val action: () -> Unit)

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val refreshers = ArrayList<() -> Unit>()
    private var tilt = ""

    private var installIndex = 0
    private var colorIndex = 0

    private var fanPercent = 100
    private val fanHandler = Handler(Looper.getMainLooper())
    private val applyFan = Runnable {
        val value = fanPercent.toString()
        Sys.bg { Sys.setProp(FAN, value) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(150), dp(22), dp(150), dp(22))
            clipToPadding = false
            clipChildren = false
        }
        list.addView(TextView(this).apply {
            text = "Projektör"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(dp(4), 0, 0, dp(14))
        })
        rows().forEach { list.addView(if (it.title == FAN_TITLE) fanView(it) else rowView(it)) }
        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0B1644.toInt(), 0xFF050A2A.toInt()))
            addView(list)
        })
        list.getChildAt(1).requestFocus()
        loadState()
    }

    override fun onResume() {
        super.onResume()
        Sys.readTilt(this) { degrees ->
            tilt = degrees?.let { String.format("%.1f°", it) } ?: "ölçülemedi"
            refreshAll()
        }
    }

    private fun loadState() = Sys.bg {
        val x = Sys.getProp(MIRROR_X) == "1"
        val y = Sys.getProp(MIRROR_Y) == "1"
        val saturation = Sys.getProp(SATURATION)
        val fan = Sys.getProp(FAN).toIntOrNull() ?: 100
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            fanPercent = fan.coerceIn(FAN_MIN, 100)
            installIndex = when {
                x && y -> 2
                y -> 3
                x -> 1
                else -> 0
            }
            colorIndex = COLOR_VALUES.indexOf(saturation).coerceAtLeast(0)
            refreshAll()
        }
    }

    private fun refreshAll() = refreshers.forEach { it() }

    private fun rows() = listOf(
        Row("Dört köşe ayarı", { "" }) { startActivity(Intent(this, KeystoneActivity::class.java)) },
        Row("Otomatik düzelt", { if (tilt.isEmpty()) "" else "Eğim $tilt" }) { autoCorrect() },
        Row("Açılışta otomatik düzelt", { if (prefs.getBoolean(KEY_AUTO, false)) "Açık" else "Kapalı" }) {
            prefs.edit().putBoolean(KEY_AUTO, !prefs.getBoolean(KEY_AUTO, false)).apply()
        },
        Row("Kurulum", { INSTALL_NAMES[installIndex] }) { cycleInstall() },
        Row("Renk", { COLOR_NAMES[colorIndex] }) { cycleColor() },
        Row(FAN_TITLE, { fanLabel() }) { cycleFanPreset() },
        Row("Uyku zamanlayıcı", { sleepLabel() }) { cycleSleep() },
        Row("Açılışta başlat", { BOOT_NAMES[BOOT_PACKAGES.indexOf(prefs.getString(KEY_BOOT, "")).coerceAtLeast(0)] }) {
            val next = (BOOT_PACKAGES.indexOf(prefs.getString(KEY_BOOT, "")).coerceAtLeast(0) + 1) % BOOT_PACKAGES.size
            prefs.edit().putString(KEY_BOOT, BOOT_PACKAGES[next]).apply()
        },
    )

    private fun autoCorrect() {
        Sys.readTilt(this) { degrees ->
            if (degrees == null) {
                Toast.makeText(this, "Bu konumda eğim ölçülemiyor", Toast.LENGTH_SHORT).show()
            } else {
                Sys.applyTilt(degrees)
                tilt = String.format("%.1f°", degrees)
                refreshAll()
                Toast.makeText(this, "Düzeltildi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cycleInstall() {
        installIndex = (installIndex + 1) % INSTALL_NAMES.size
        val x = installIndex == 1 || installIndex == 2
        val y = installIndex == 2 || installIndex == 3
        Sys.bg {
            Sys.setProp(MIRROR_X, if (x) "1" else "0")
            Sys.setProp(MIRROR_Y, if (y) "1" else "0")
            Sys.refreshKeystone()
        }
    }

    private fun cycleColor() {
        colorIndex = (colorIndex + 1) % COLOR_NAMES.size
        val value = COLOR_VALUES[colorIndex]
        Sys.bg {
            Sys.setProp(SATURATION, value)
            Sys.command("color")
        }
    }

    private fun fanLabel(): String {
        val name = when (fanPercent) {
            100 -> "Tam hız"
            FAN_QUIET -> "Sessiz"
            FAN_QUIETER -> "Daha sessiz"
            else -> "%$fanPercent"
        }
        return if (fanPercent < FAN_TESTED_MIN) "$name · test edilmedi" else name
    }

    private fun cycleFanPreset() = setFan(
        when (fanPercent) {
            100 -> FAN_QUIET
            FAN_QUIET -> FAN_QUIETER
            else -> 100
        },
    )

    private fun setFan(percent: Int) {
        val previous = fanPercent
        val wanted = percent.coerceIn(FAN_MIN, 100)
        // The service cannot cut the line for less than about 0.8 ms, so 95 would behave exactly like 90.
        fanPercent = if (wanted == 95) (if (wanted < previous) 90 else 100) else wanted
        if (previous >= FAN_TESTED_MIN && fanPercent < FAN_TESTED_MIN) {
            Toast.makeText(this, "Bu seviyede ısı testi yapılmadı. Soğutma azalır.", Toast.LENGTH_LONG).show()
        }
        if (previous >= FAN_RISKY && fanPercent < FAN_RISKY) {
            Toast.makeText(this, "Fan bu seviyede durabilir. Uzun süre bırakma, menfezden hava geldiğini kontrol et.", Toast.LENGTH_LONG).show()
        }
        fanHandler.removeCallbacks(applyFan)
        fanHandler.postDelayed(applyFan, 250)
        refreshAll()
    }

    /** A normal row with a level bar: left and right change the level, OK steps through the presets. */
    private fun fanView(row: Row): View {
        val root = rowView(row) as LinearLayout
        val fill = View(this).apply { background = card(0xFFF5C542.toInt()) }
        val rest = View(this)
        val track = LinearLayout(this).apply {
            background = card(0xFF1B3A8C.toInt())
            addView(fill, LinearLayout.LayoutParams(0, -1, 1f))
            addView(rest, LinearLayout.LayoutParams(0, -1, 0f))
        }
        root.addView(track, 1, LinearLayout.LayoutParams(dp(170), dp(6)).apply { marginEnd = dp(16) })
        // A fixed column for the label keeps the bar still while the text changes length.
        (root.getChildAt(2) as TextView).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(dp(185), -2)
        }
        val render = {
            val span = (100 - FAN_MIN).toFloat()
            (fill.layoutParams as LinearLayout.LayoutParams).weight = ((fanPercent - FAN_MIN) / span).coerceAtLeast(0.02f)
            (rest.layoutParams as LinearLayout.LayoutParams).weight = (100 - fanPercent) / span
            track.requestLayout()
        }
        refreshers += render
        render()
        root.setOnKeyListener { _, code, event ->
            val step = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -FAN_STEP
                KeyEvent.KEYCODE_DPAD_RIGHT -> FAN_STEP
                else -> 0
            }
            if (step != 0 && event.action == KeyEvent.ACTION_DOWN) setFan(fanPercent + step)
            step != 0
        }
        return root
    }

    private fun sleepLabel(): String {
        val minutes = prefs.getInt(KEY_SLEEP, 0)
        return if (minutes == 0) "Kapalı" else "$minutes dk"
    }

    private fun cycleSleep() {
        val next = SLEEP_MINUTES[(SLEEP_MINUTES.indexOf(prefs.getInt(KEY_SLEEP, 0)).coerceAtLeast(0) + 1) % SLEEP_MINUTES.size]
        prefs.edit().putInt(KEY_SLEEP, next).apply()
        val alarms = getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(this, 0, Intent(this, SleepReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.cancel(pending)
        if (next > 0) alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + next * 60_000L, pending)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun card(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun rowView(row: Row): View {
        val normal = card(0xFF0F1B52.toInt())
        val active = card(0xFFB3131B.toInt())
        val value = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFFDCE7FF.toInt())
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = normal
            setPadding(dp(20), 0, dp(20), 0)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) }
            addView(TextView(context).apply {
                text = row.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(value)
            val render = { value.text = row.value() }
            refreshers += render
            render()
            setOnClickListener {
                row.action()
                refreshAll()
            }
            setOnFocusChangeListener { v, has ->
                v.background = if (has) active else normal
                val s = if (has) 1.02f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(110).start()
            }
        }
    }

    companion object {
        const val PREFS = "sevos"
        const val KEY_AUTO = "auto_keystone"
        const val KEY_BOOT = "boot_app"
        const val KEY_SLEEP = "sleep_minutes"
        private const val MIRROR_X = "persist.sys.keystone.mirror_x"
        private const val MIRROR_Y = "persist.sys.keystone.mirror_y"
        private const val SATURATION = "persist.sys.sevos.saturation"
        private const val FAN = "sys.sevos.fan"
        private const val FAN_TITLE = "Fan"
        private const val FAN_MIN = 10

        // Below this the fan may stall while the light stays at full power.
        private const val FAN_RISKY = 40
        private const val FAN_STEP = 5
        private const val FAN_QUIET = 80
        private const val FAN_QUIETER = 65

        // The lowest level that has passed a 20 minute heat test.
        private const val FAN_TESTED_MIN = 80
        private val INSTALL_NAMES = arrayOf("Ön", "Arka", "Tavan", "Tavan arka")
        private val COLOR_NAMES = arrayOf("Normal", "Canlı", "Sinema")
        private val COLOR_VALUES = arrayOf("1.00", "1.25", "0.90")
        private val SLEEP_MINUTES = intArrayOf(0, 30, 60, 90, 120).toList()
        private val BOOT_NAMES = arrayOf("Ana ekran", "YouTube", "OwnTV", "HDMI")
        private val BOOT_PACKAGES = listOf("", HomeActivity.YOUTUBE_PKG, HomeActivity.IPTV_PKG, HomeActivity.HDMI_PKG)
    }
}
