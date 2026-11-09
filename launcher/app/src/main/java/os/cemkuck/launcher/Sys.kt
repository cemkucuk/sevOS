package os.cemkuck.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Device controls shared by the home screen and the projector panel. */
object Sys {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val KEYSTONE = "persist.sys.keystone."

    // Measured on this unit: the corners the user confirmed on the wall at a 16.5 degree tilt.
    private const val PX_X_PER_DEGREE = 59f / 16.5f
    private const val PX_Y_PER_DEGREE = 66f / 16.5f

    fun bg(block: () -> Unit) = worker.execute(block)

    fun getProp(key: String): String = runCatching {
        val p = ProcessBuilder("/system/bin/getprop", key).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor()
        out
    }.getOrDefault("")

    fun setProp(key: String, value: String) {
        runCatching { ProcessBuilder("/system/bin/setprop", key, value).start().waitFor() }
    }

    /** Runs a command through the root init helper, sevos_ctl.sh. */
    fun command(name: String) = setProp("sys.sevos.cmd", name)

    fun availableMb(): Long? = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.first { it.startsWith("MemAvailable:") }.filter(Char::isDigit).toLong() / 1024
        }
    }.getOrNull()

    fun refreshKeystone() {
        setProp(KEYSTONE + "update", "0")
        setProp(KEYSTONE + "update", "1")
    }

    /** Samples the accelerometer briefly. Returns the tilt in degrees, or null if it cannot be read. */
    fun readTilt(context: Context, done: (Float?) -> Unit) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) return done(null)
        var y = 0f
        var z = 0f
        var count = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                y += event.values[1]
                z += event.values[2]
                count++
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, main)
        main.postDelayed({
            manager.unregisterListener(listener)
            // z <= 0 means the unit is upside down, where the wall model does not apply.
            done(if (count == 0 || z <= 0f) null else Math.toDegrees(atan2(-y.toDouble(), z.toDouble())).toFloat())
        }, 450)
    }

    fun applyTilt(degrees: Float) {
        val tilt = if (abs(degrees) < 0.5f) 0f else degrees
        val x = (abs(tilt) * PX_X_PER_DEGREE).roundToInt()
        val y = (abs(tilt) * PX_Y_PER_DEGREE).roundToInt()
        bg {
            val top = tilt > 0f
            setProp(KEYSTONE + "lt", if (top) "$x,-$y" else "0,0")
            setProp(KEYSTONE + "rt", if (top) "-$x,-$y" else "0,0")
            setProp(KEYSTONE + "lb", if (!top && x > 0) "$x,$y" else "0,0")
            setProp(KEYSTONE + "rb", if (!top && x > 0) "-$x,$y" else "0,0")
            refreshKeystone()
        }
    }
}

/** Fires when the sleep timer ends. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSharedPreferences(ProjectorActivity.PREFS, Context.MODE_PRIVATE).edit().putInt(ProjectorActivity.KEY_SLEEP, 0).apply()
        Sys.bg { Sys.command("sleep") }
    }
}
