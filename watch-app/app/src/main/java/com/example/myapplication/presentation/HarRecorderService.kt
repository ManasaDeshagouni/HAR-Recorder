package com.example.myapplication.presentation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HarRecorderService : Service(), SensorEventListener2 {

    companion object {
        const val SAMPLING_PERIOD_US = 20_000          // 50 Hz
        const val MAX_REPORT_LATENCY_US = 1_000_000    // sensor chip may batch up to 1 s
        const val CHUNK_DURATION_MS = 5 * 60 * 1000L   // new file every 5 minutes
        const val TICK_MS = 1_000L                     // flush every 1 second

        // Our sensor codes (stored as the first byte of every reading)
        const val TYPE_ACCEL = 1
        const val TYPE_GYRO = 2
        const val TYPE_MAG = 3
        const val TYPE_BARO = 4
        const val TYPE_OFFBODY = 5
        const val TYPE_GRAVITY = 6
        const val TYPE_LINEAR = 7

        private const val CHANNEL_ID = "har_recording"
        private const val NOTIF_ID = 1

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var startedAtMs = 0L
            private set

        fun start(ctx: Context) {
            Prefs.setShouldRecord(ctx, true)
            ctx.startForegroundService(Intent(ctx, HarRecorderService::class.java))
        }

        fun stop(ctx: Context) {
            Prefs.setShouldRecord(ctx, false)
            ctx.stopService(Intent(ctx, HarRecorderService::class.java))
        }

        fun chunkDir(ctx: Context) = File(ctx.filesDir, "har/chunks")
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var events: EventLog
    private lateinit var deviceId: String
    private var writer: ChunkWriter? = null
    private var registeredSensors = 0
    @Volatile private var flushLatch: CountDownLatch? = null
    private var started = false
    private var bootCount = -1
    private var live: LiveUploader? = null

    // One entry per sensor we record: Android type, our code, name, sampling period
    private data class Spec(val androidType: Int, val code: Int, val name: String, val periodUs: Int)

    private val specs = listOf(
        Spec(Sensor.TYPE_ACCELEROMETER, TYPE_ACCEL, "accelerometer", SAMPLING_PERIOD_US),
        Spec(Sensor.TYPE_GYROSCOPE, TYPE_GYRO, "gyroscope", SAMPLING_PERIOD_US),
        Spec(Sensor.TYPE_MAGNETIC_FIELD, TYPE_MAG, "magnetometer", SAMPLING_PERIOD_US),
        Spec(Sensor.TYPE_PRESSURE, TYPE_BARO, "barometer", 200_000),               // 5 Hz
        Spec(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, TYPE_OFFBODY, "offbody", 200_000),
        Spec(Sensor.TYPE_GRAVITY, TYPE_GRAVITY, "gravity", SAMPLING_PERIOD_US),
        Spec(Sensor.TYPE_LINEAR_ACCELERATION, TYPE_LINEAR, "linear_acceleration", SAMPLING_PERIOD_US),
    )

    // Filled during registration: Android sensor type -> our code
    private val codes = mutableMapOf<Int, Int>()

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) startRecording()
        return START_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun startRecording() {
        started = true
        isRunning = true
        startedAtMs = SystemClock.elapsedRealtime()
        bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        deviceId = Prefs.deviceId(this)

        val dir = chunkDir(this).apply { mkdirs() }
        events = EventLog(File(filesDir, "har/events.log"), bootCount)
        val recovered = try {
            ChunkWriter.recoverLeftovers(dir)
        } catch (e: Exception) {
            events.log("RECOVER_ERROR", e.toString()); 0
        }
        events.log("SERVICE_START", "device=$deviceId recovered_chunks=$recovered battery=${batteryPct()}")
        if (LiveConfig.ENABLED) {
            live = LiveUploader(this, deviceId, events).also { it.start() }
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HarWatch::Recording")
        wakeLock.acquire()

        thread = HandlerThread("har-sensors", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
        handler = Handler(thread.looper)
        handler.post { openNewChunk() }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        registeredSensors = 0
        codes.clear()
        for (spec in specs) {
            val sensor = sensorManager.getDefaultSensor(spec.androidType)
            if (sensor == null) {
                events.log("SENSOR_MISSING", spec.name)
                continue
            }
            val ok = sensorManager.registerListener(
                this, sensor, spec.periodUs, MAX_REPORT_LATENCY_US, handler
            )
            events.log(
                if (ok) "SENSOR_REGISTERED" else "SENSOR_REGISTER_FAILED",
                "${spec.name} fifo=${sensor.fifoMaxEventCount} wakeup=${sensor.isWakeUpSensor}"
            )
            if (ok) {
                codes[spec.androidType] = spec.code
                registeredSensors++
            }
        }
        handler.postDelayed(tick, TICK_MS)
    }

    private fun openNewChunk() {
        try {
            val seq = Prefs.takeNextSeq(this)
            writer = ChunkWriter(chunkDir(this), deviceId, seq, bootCount, SAMPLING_PERIOD_US)
            events.log("CHUNK_OPEN", "seq=$seq battery=${batteryPct()}")
        } catch (e: Exception) {
            writer = null
            events.log("CHUNK_OPEN_ERROR", e.toString())
        }
    }

    private fun closeChunk() {
        val w = writer ?: return
        writer = null
        try {
            w.close()
            events.log("CHUNK_CLOSE", "records=${w.recordCount}")
        } catch (e: Exception) {
            events.log("CHUNK_CLOSE_ERROR", e.toString())
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val w = writer
            if (w == null) {
                openNewChunk()
            } else {
                try { w.flush() } catch (e: Exception) { events.log("FLUSH_ERROR", e.toString()) }
                if (SystemClock.elapsedRealtime() - w.openedAtElapsedMs >= CHUNK_DURATION_MS) {
                    closeChunk()
                    openNewChunk()
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val code = codes[event.sensor.type] ?: return
        val v = event.values

        // Make "on/off wrist" easy to spot in the diary, too
        if (code == TYPE_OFFBODY) {
            events.log("OFFBODY", if (v[0] > 0.5f) "on_wrist" else "off_wrist")
        }

        val w = writer ?: return
        try {
            w.write(
                code,
                event.timestamp,
                v[0],
                if (v.size > 1) v[1] else 0f,
                if (v.size > 2) v[2] else 0f,
            )
        } catch (e: Exception) {
            events.log("WRITE_ERROR", e.toString())
            writer = null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        events.log("ACCURACY", "${sensor?.name} accuracy=$accuracy")
    }

    override fun onFlushCompleted(sensor: Sensor?) {
        flushLatch?.countDown()
    }

    override fun onDestroy() {
        if (started) {
            if (registeredSensors > 0) {
                val latch = CountDownLatch(registeredSensors)
                flushLatch = latch
                sensorManager.flush(this)
                latch.await(3, TimeUnit.SECONDS)
            }
            sensorManager.unregisterListener(this)

            val done = CountDownLatch(1)
            handler.post {
                handler.removeCallbacks(tick)
                closeChunk()
                done.countDown()
            }
            done.await(3, TimeUnit.SECONDS)
            thread.quitSafely()
            live?.stop()      // send the last piece + "chunk complete" (up to 5 s)
            live = null

            events.log("SERVICE_STOP", "user_requested=${!Prefs.shouldRecord(this)} battery=${batteryPct()}")
            if (wakeLock.isHeld) wakeLock.release()
        }
        isRunning = false
        super.onDestroy()
    }

    private fun batteryPct(): Int =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "HAR recording", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording motion")
            .setContentText("Motion sensors, 50 Hz")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}