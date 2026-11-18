package com.redder.local

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.redder.playback.GenerationGate
import kotlinx.coroutines.delay

/**
 * Stops generating ahead while the phone is too hot (ADR-009), and writes each chunk's speed and the phone's
 * temperature to the log. Queued audio keeps playing; if it runs out, playback waits for the phone to cool.
 *
 * @param status The thermal status, in `PowerManager.THERMAL_STATUS_*` numbers.
 * @param batteryTenths The battery's temperature in tenths of a degree, or null when unknown.
 */
class ThermalGate(
    private val status: () -> Int,
    private val batteryTenths: () -> Int? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = { message -> runCatching { Log.i(TAG, message) } },
) : GenerationGate {
    @Volatile
    private var turnStartedAt = 0L

    override suspend fun awaitClear() {
        if (status() >= HOLD_AT) {
            log("HOLD thermal=${status()} battery_tenths=${batteryTenths()}")
            while (status() >= HOLD_AT) pauseMs(CHECK_EVERY_MS)
            log("RESUME thermal=${status()} battery_tenths=${batteryTenths()}")
        }
        turnStartedAt = clock()
    }

    override fun onSynthesized(
        index: Int,
        audioMs: Long,
    ) {
        val spentMs = clock() - turnStartedAt
        val rtf = if (audioMs > 0L) spentMs.toFloat() / audioMs else 0f
        log(
            "CHUNK index=$index audio_ms=$audioMs spent_ms=$spentMs rtf=${"%.3f".format(java.util.Locale.ROOT, rtf)} " +
                "thermal=${status()} battery_tenths=${batteryTenths()}",
        )
    }

    companion object {
        private const val TAG = "ThreadReaderHeat"

        /** `PowerManager.THERMAL_STATUS_SEVERE`, a constant that exists only from API 29. */
        const val HOLD_AT: Int = 3
        internal const val CHECK_EVERY_MS: Long = 15_000L

        fun forContext(context: Context): ThermalGate {
            val app = context.applicationContext
            val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return ThermalGate(
                status = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) power.currentThermalStatus else 0
                },
                batteryTenths = {
                    // A sticky broadcast read with no receiver registered, so nothing stays listening.
                    app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                        ?.takeIf { it != Int.MIN_VALUE }
                },
            )
        }
    }
}
