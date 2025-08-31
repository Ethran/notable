package com.ethran.notable.utils

import io.shipbook.shipbooksdk.ShipBook

class Timing(
    private val label: String = "Timing",
    private val showLogs: Boolean = true
) {
    private val log = ShipBook.Companion.getLogger("Timing")
    private val startTime = System.nanoTime()
    private var lastCheckpoint = startTime
    private var stepCount = 0

    fun step(name: String? = null) {
        val now = System.nanoTime()
        val deltaMs = (now - lastCheckpoint) / 1_000_000.0
        val totalMs = (now - startTime) / 1_000_000.0
        stepCount++

        if (showLogs) {
            val stepLabel = name ?: "Step $stepCount"
            log.d(
                "$label - $stepLabel: Δ ${"%.3f".format(deltaMs)} ms | Total ${
                    "%.3f".format(
                        totalMs
                    )
                } ms"
            )
        }

        lastCheckpoint = now
    }

    fun end(finalLabel: String = "End") {
        val now = System.nanoTime()
        val totalMs = (now - startTime) / 1_000_000.0
        if (showLogs) {
            log.d("$label - $finalLabel: Total ${"%.3f".format(totalMs)} ms")
        }
    }

    companion object {
        inline fun <T> measure(
            label: String = "Timing",
            showLogs: Boolean = true,
            block: () -> T
        ): T {
            val log = ShipBook.Companion.getLogger("Timing")
            val start = System.nanoTime()
            val result = block()
            val end = System.nanoTime()
            val durationMs = (end - start) / 1_000_000.0

            if (showLogs) {
                log.d("$label: ${"%.3f".format(durationMs)} ms")
            }

            return result
        }
    }
}