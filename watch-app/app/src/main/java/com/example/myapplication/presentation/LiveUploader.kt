package com.example.myapplication.presentation

import android.content.Context
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.Deflater

/**
 * Streams the chunk files to the laptop through the MQTT broker, piece by piece,
 * while they are being written. The files themselves are the outbox: the cursor
 * (file number + byte position) only moves forward after the broker confirms
 * each piece, so nothing is skipped, even across disconnects and restarts.
 * The laptop can ask for missing chunks or missing diary lines to be sent again.
 */
class LiveUploader(
    private val ctx: Context,
    private val deviceId: String,
    private val events: EventLog,
) {
    companion object {
        private const val TYPE_DATA: Byte = 1
        private const val TYPE_COMPLETE: Byte = 2
        private const val TYPE_EVENTS: Byte = 3

        @Volatile
        var connected = false
            private set
    }

    private val dir = HarRecorderService.chunkDir(ctx)
    private val eventsFile = File(ctx.filesDir, "har/events.log")
    private val prefix = "chunk_${deviceId}_"
    private val dataTopic = "har/$deviceId/data"
    private val ackTopic = "har/$deviceId/ack"
    private val resendTopic = "har/$deviceId/resend"
    private val resendEventsTopic = "har/$deviceId/resend_events"

    private var client: MqttClient? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var stopRequested = false
    @Volatile private var resendSeq: Long? = null
    @Volatile private var resendEventsFrom: Long? = null
    private var loggedOffline = false

    fun start() {
        if (running) return
        if (!Prefs.hasLiveCursor(ctx)) {
            // First time: start from the oldest file still on the watch
            val first = existingSeqs().minOrNull() ?: Prefs.peekNextSeq(ctx)
            Prefs.setLiveCursor(ctx, first, 0L)
        }
        running = true
        stopRequested = false
        thread = Thread({ loop() }, "har-live").apply { start() }
    }

    /** Called when recording stops: send what's left (up to drainMs), then disconnect. */
    fun stop(drainMs: Long = 5_000) {
        stopRequested = true
        thread?.join(drainMs)
        running = false
        thread?.interrupt()
        thread?.join(1_000)
        disconnect()
    }

    // ---------- main loop ----------

    private fun loop() {
        while (running) {
            try {
                ensureConnected()
                val busy = sendNextChunkPiece() or sendNewEvents()   // "or" runs both
                if (!busy) {
                    if (stopRequested) break                         // everything sent
                    Thread.sleep(LiveConfig.POLL_MS)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (connected || !loggedOffline) {
                    events.log("LIVE_OFFLINE", e.toString())
                    loggedOffline = true
                }
                disconnect()
                if (stopRequested) break
                try { Thread.sleep(LiveConfig.RETRY_MS) } catch (ie: InterruptedException) { break }
            }
        }
    }

    // ---------- connection ----------

    private fun ensureConnected() {
        if (client?.isConnected == true) return
        disconnect()
        val c = MqttClient(LiveConfig.BROKER_URL, "watch-$deviceId", MemoryPersistence())
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) { connected = false }
            override fun messageArrived(topic: String, message: MqttMessage) { onControl(topic, message) }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val options = MqttConnectOptions().apply {
            userName = LiveConfig.USERNAME
            password = LiveConfig.PASSWORD.toCharArray()
            isCleanSession = false        // broker keeps laptop replies for us while we're offline
            isAutomaticReconnect = false  // our loop handles reconnecting
            connectionTimeout = 20
            keepAliveInterval = 30
        }
        c.timeToWait = 20_000L            // wait at most 20 s for the broker to confirm a piece
        c.connect(options)
        c.subscribe(arrayOf(ackTopic, resendTopic, resendEventsTopic), intArrayOf(1, 1, 1))
        client = c
        connected = true
        loggedOffline = false
        events.log("LIVE_CONNECTED", LiveConfig.BROKER_URL)
    }

    private fun disconnect() {
        val c = client ?: return
        client = null
        connected = false
        try { if (c.isConnected) c.disconnect(1_000) else c.disconnectForcibly(1_000) } catch (e: Exception) { }
        try { c.close() } catch (e: Exception) { }
    }

    // ---------- replies from the laptop (runs on Paho's thread: keep it short) ----------

    private fun onControl(topic: String, message: MqttMessage) {
        val number = String(message.payload).trim().toLongOrNull() ?: return
        when (topic) {
            ackTopic -> if (LiveConfig.DELETE_AFTER_CONFIRMED) {
                val bin = File(dir, chunkName(number) + ".bin")
                if (bin.exists()) {
                    bin.delete()
                    File(bin.path + ".sha256").delete()
                    events.log("LIVE_CONFIRMED", "seq=$number deleted_on_watch")
                }
            }
            resendTopic -> synchronized(this) {
                // Keep only the OLDEST request: going forward from there re-sends
                // every file the laptop hasn't confirmed yet, so nothing is skipped.
                val current = resendSeq
                if (current == null || number < current) resendSeq = number
            }
            resendEventsTopic -> synchronized(this) {
                // Here the number is a byte position in the diary; keep the earliest request
                val current = resendEventsFrom
                if (current == null || number < current) resendEventsFrom = number
            }
        }
    }

    // ---------- sending ----------

    /** Sends the next piece of the current file. Returns true if it did something. */
    private fun sendNextChunkPiece(): Boolean {
        val requested = synchronized(this) { resendSeq.also { resendSeq = null } }
        if (requested != null) {
            // Jump back to the oldest file we still have, at or after the requested one
            val target = existingSeqs().filter { it >= requested }.minOrNull()
            if (target != null && target <= Prefs.liveSeq(ctx)) {
                Prefs.setLiveCursor(ctx, target, 0L)
                events.log("LIVE_RESEND", "from seq=$target")
            }
        }

        val seq = Prefs.liveSeq(ctx)
        val offset = Prefs.liveOffset(ctx)
        val file = fileFor(seq)

        if (file == null) {
            // This number has no file (unused number, or already copied by adb): skip ahead
            val next = existingSeqs().filter { it > seq }.minOrNull() ?: return false
            Prefs.setLiveCursor(ctx, next, 0L)
            return true
        }

        val finished = file.name.endsWith(".bin")
        val length = file.length()

        if (offset < length) {
            val n = minOf(length - offset, LiveConfig.MAX_BYTES_PER_MESSAGE.toLong()).toInt()
            val bytes = ByteArray(n)
            try {
                RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(bytes) }
            } catch (e: FileNotFoundException) {
                return true      // .part was just renamed to .bin; try again right away
            }
            publish(message(TYPE_DATA, seq, offset, compress(bytes)))
            Prefs.setLiveCursor(ctx, seq, offset + n)    // only AFTER the broker confirmed
            return true
        }

        if (finished) {
            val shaFile = File(file.path + ".sha256")
            if (!shaFile.exists()) return false
            val sha = shaFile.readText().trim().substringBefore(' ')
            val body = ByteBuffer.allocate(8 + 64)
                .putLong(length)
                .put(sha.toByteArray(Charsets.US_ASCII))
                .array()
            publish(message(TYPE_COMPLETE, seq, 0L, body))
            Prefs.setLiveCursor(ctx, seq + 1, 0L)
            return true
        }
        return false     // caught up with the file being recorded right now
    }

    /** Sends new lines of the diary (events.log). */
    private fun sendNewEvents(): Boolean {
        val requested = synchronized(this) { resendEventsFrom.also { resendEventsFrom = null } }
        if (requested != null && requested < Prefs.eventsOffset(ctx)) {
            Prefs.setEventsOffset(ctx, requested)        // go back and send the missing lines
            events.log("LIVE_RESEND_EVENTS", "from byte=$requested")
        }

        val length = eventsFile.length()
        var offset = Prefs.eventsOffset(ctx)
        if (offset > length) offset = 0L
        if (offset >= length) return false
        val n = minOf(length - offset, LiveConfig.MAX_BYTES_PER_MESSAGE.toLong()).toInt()
        val bytes = ByteArray(n)
        RandomAccessFile(eventsFile, "r").use { it.seek(offset); it.readFully(bytes) }
        publish(message(TYPE_EVENTS, 0L, offset, compress(bytes)))
        Prefs.setEventsOffset(ctx, offset + n)
        return true
    }

    /** QoS 1 = the broker must confirm. publish() waits for that (or throws). */
    private fun publish(payload: ByteArray) {
        val c = client ?: throw IllegalStateException("not connected")
        c.publish(dataTopic, MqttMessage(payload).apply { qos = 1 })
    }

    // ---------- helpers ----------

    /** Message = [type 1 byte][file number 8][byte offset 8][body] */
    private fun message(type: Byte, seq: Long, offset: Long, body: ByteArray): ByteArray =
        ByteBuffer.allocate(17 + body.size).put(type).putLong(seq).putLong(offset).put(body).array()

    private fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun chunkName(seq: Long) = String.format(Locale.US, "%s%08d", prefix, seq)

    private fun fileFor(seq: Long): File? {
        val part = File(dir, chunkName(seq) + ".part")
        if (part.exists()) return part
        val bin = File(dir, chunkName(seq) + ".bin")
        return if (bin.exists()) bin else null
    }

    private fun existingSeqs(): List<Long> =
        dir.listFiles()?.mapNotNull { f ->
            val n = f.name
            if (n.startsWith(prefix) && (n.endsWith(".bin") || n.endsWith(".part")))
                n.removePrefix(prefix).substringBefore('.').toLongOrNull()
            else null
        } ?: emptyList()
}