package os.cemkuck.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import java.util.concurrent.Executors

/** Four-corner keystone editor. SurfaceFlinger reads persist.sys.keystone.* and warps the output. */
class KeystoneActivity : Activity() {
    private val keys = arrayOf("lt", "rt", "rb", "lb")
    private val names = arrayOf("Sol üst", "Sağ üst", "Sağ alt", "Sol alt")
    private val values = Array(4) { intArrayOf(0, 0) }
    private val written = Array(4) { intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE) }
    private var selected = 0
    private var loaded = false

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var guide: GuideView
    private val flush = Runnable { snapshotAndWrite() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guide = GuideView(this)
        setContentView(guide)
        io.execute {
            val read = Array(4) { parse(getProp(PREFIX + keys[it])) }
            main.post {
                if (isDestroyed) return@post
                for (i in 0..3) {
                    values[i] = read[i]
                    written[i] = read[i].copyOf()
                }
                loaded = true
                guide.invalidate()
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(flush)
        snapshotAndWrite()
        io.shutdown()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!loaded) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) selected = (selected + 1) % (keys.size + 1)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-STEP, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(STEP, 0)
            KeyEvent.KEYCODE_DPAD_UP -> if (selected == RESET) reset() else move(0, STEP)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(0, -STEP)
            else -> return super.onKeyDown(keyCode, event)
        }
        guide.invalidate()
        return true
    }

    private fun move(dx: Int, dy: Int) {
        if (selected == RESET) return
        val v = values[selected]
        v[0] = (v[0] + dx).coerceIn(-LIMIT, LIMIT)
        v[1] = (v[1] + dy).coerceIn(-LIMIT, LIMIT)
        schedule()
    }

    private fun reset() {
        values.forEach { it[0] = 0; it[1] = 0 }
        selected = 0
        schedule()
    }

    private fun schedule() {
        main.removeCallbacks(flush)
        main.postDelayed(flush, 60)
    }

    private fun snapshotAndWrite() {
        if (!loaded || io.isShutdown) return
        val snapshot = Array(4) { values[it].copyOf() }
        io.execute { writeChanged(snapshot) }
    }

    private fun writeChanged(snapshot: Array<IntArray>) {
        var changed = false
        for (i in 0..3) {
            if (!snapshot[i].contentEquals(written[i])) {
                setProp(PREFIX + keys[i], "${snapshot[i][0]},${snapshot[i][1]}")
                written[i] = snapshot[i]
                changed = true
            }
        }
        if (changed) {
            setProp(PREFIX + "update", "0")
            setProp(PREFIX + "update", "1")
        }
    }

    private fun parse(s: String): IntArray {
        val p = s.trim().split(",")
        return intArrayOf(p.getOrNull(0)?.toIntOrNull() ?: 0, p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun getProp(key: String): String = runCatching {
        val p = ProcessBuilder("/system/bin/getprop", key).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out
    }.getOrDefault("")

    private fun setProp(key: String, value: String) {
        runCatching { ProcessBuilder("/system/bin/setprop", key, value).start().waitFor() }
    }

    private inner class GuideView(ctx: Context) : View(ctx) {
        private val density = resources.displayMetrics.density
        private val line = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3 * density }
        private val thin = Paint().apply { color = 0xFF1B3A8C.toInt(); strokeWidth = density }
        private val mark = Paint().apply { color = 0xFFF5C542.toInt() }
        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26 * density; textAlign = Paint.Align.CENTER }
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFAFC6F2.toInt(); textSize = 15 * density; textAlign = Paint.Align.CENTER }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawColor(Color.BLACK)
            for (i in 1..5) c.drawLine(w * i / 6, 0f, w * i / 6, h, thin)
            for (i in 1..3) c.drawLine(0f, h * i / 4, w, h * i / 4, thin)
            val inset = line.strokeWidth / 2
            c.drawRect(inset, inset, w - inset, h - inset, line)
            val s = 56 * density
            when (selected) {
                0 -> c.drawRect(0f, 0f, s, s, mark)
                1 -> c.drawRect(w - s, 0f, w, s, mark)
                2 -> c.drawRect(w - s, h - s, w, h, mark)
                3 -> c.drawRect(0f, h - s, s, h, mark)
            }
            val y = h / 2 - 34 * density
            if (selected == RESET) {
                c.drawText("Sıfırlamak için ▲", w / 2, y, title)
            } else {
                c.drawText(names[selected] + "   " + values[selected][0] + ", " + values[selected][1], w / 2, y, title)
            }
            c.drawText("Yön tuşları köşeyi taşır", w / 2, y + 40 * density, body)
            c.drawText("OK: sonraki köşe   Geri: çıkış (ayar kayıtlı kalır)", w / 2, y + 66 * density, body)
        }
    }

    private companion object {
        const val PREFIX = "persist.sys.keystone."
        const val STEP = 2
        const val LIMIT = 400
        const val RESET = 4
    }
}
