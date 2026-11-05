package com.example.myapplication.presentation

import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

class ChunkWriter(
    dir: File,
    deviceId: String,
    seq: Long,
    bootCount: Int,
    samplingPeriodUs: Int,
) {
    private val baseName = String.format(Locale.US, "chunk_%s_%08d", deviceId, seq)
    private val partFile = File(dir, "$baseName.part")
    private val fos = FileOutputStream(partFile)
    private val out = DataOutputStream(BufferedOutputStream(fos, 16 * 1024))

    val openedAtElapsedMs: Long = SystemClock.elapsedRealtime()
    var recordCount = 0L
        private set

    init {
        out.write(MAGIC)
        out.writeInt(VERSION)
        out.writeLong(seq)
        out.writeInt(bootCount)
        out.writeLong(SystemClock.elapsedRealtimeNanos())
        out.writeLong(System.currentTimeMillis())
        out.writeInt(samplingPeriodUs)
        out.writeUTF(deviceId)
        out.flush()
    }

    fun write(sensorType: Int, timestampNs: Long, x: Float, y: Float, z: Float) {
        out.writeByte(sensorType)
        out.writeLong(timestampNs)
        out.writeFloat(x)
        out.writeFloat(y)
        out.writeFloat(z)
        recordCount++
    }

    fun flush() = out.flush()

    fun close() {
        out.flush()
        fos.fd.sync()
        out.close()
        finalizeChunk(partFile)
    }

    companion object {
        val MAGIC = "HARC".toByteArray()
        const val VERSION = 1

        fun finalizeChunk(part: File): File {
            val bin = File(part.parentFile, part.name.removeSuffix(".part") + ".bin")
            writeSha(part, File(bin.path + ".sha256"), bin.name)
            if (!part.renameTo(bin)) throw IOException("rename failed: $part")
            return bin
        }

        fun recoverLeftovers(dir: File): Int {
            var count = 0
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".part")) {
                    if (f.length() == 0L) f.delete() else { finalizeChunk(f); count++ }
                } else if (f.name.endsWith(".bin") && !File(f.path + ".sha256").exists()) {
                    writeSha(f, File(f.path + ".sha256"), f.name)
                    count++
                }
            }
            return count
        }

        private fun writeSha(data: File, sidecar: File, nameInSidecar: String) {
            val md = MessageDigest.getInstance("SHA-256")
            data.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            val tmp = File(sidecar.path + ".tmp")
            FileOutputStream(tmp).use { s ->
                s.write("$hex  $nameInSidecar\n".toByteArray())
                s.fd.sync()
            }
            tmp.renameTo(sidecar)
        }
    }
}
