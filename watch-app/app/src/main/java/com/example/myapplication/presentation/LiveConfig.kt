package com.example.myapplication.presentation

object LiveConfig {
    const val ENABLED = true

    // Your EMQX deployment address. "ssl://" = encrypted, 8883 = MQTT over TLS.
    const val BROKER_URL = "ssl://YOUR-BROKER-ADDRESS:8883"
    const val USERNAME = "YOUR-USERNAME"
    const val PASSWORD = "YOUR-PASSWORD"

    const val DELETE_AFTER_CONFIRMED = true      // delete on watch only after the laptop verified the checksum
    const val POLL_MS = 500L                     // check for new data twice per second
    const val RETRY_MS = 5_000L                  // wait 5 s between reconnect attempts
    const val MAX_BYTES_PER_MESSAGE = 64 * 1024  // up to 64 KB per message when catching up
}