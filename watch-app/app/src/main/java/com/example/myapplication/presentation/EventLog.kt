package com.example.myapplication.presentation

import android.os.SystemClock
import java.io.File
import java.io.FileWriter

class EventLog(private val file: File, private val bootCount: Int) {

    companion object {
        // One lock for the whole app: the recorder service AND the screen (activity labels)
        // both write to the same diary file, so their lines must never get mixed up.
        private val LOCK = Any()
    }

    init {
        file.parentFile?.mkdirs()
    }

    fun log(event: String, detail: String = "") {
        synchronized(LOCK) {
            try {
                FileWriter(file, true).use { writer ->
                    val clean = detail.replace(',', ';').replace('\n', ' ')
                    val wallMs = System.currentTimeMillis()
                    val elapsedNs = SystemClock.elapsedRealtimeNanos()   // same clock as the sensor readings
                    writer.write("$wallMs,$elapsedNs,$bootCount,$event,$clean\n")
                }
            } catch (e: Exception) {
                // Logging must never crash the recorder.
            }
        }
    }
}