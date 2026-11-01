package com.yavorradulov.jerrycam

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Proves the database connection is alive: [beat] writes a heartbeat and returns once the server answers.
 * A connection can die without either side noticing for many minutes, so a beat that hangs past
 * [timeoutMs] forces a [reconnect]. Runs until cancelled.
 */
suspend fun watchConnection(
    intervalMs: Long,
    timeoutMs: Long,
    beat: suspend () -> Unit,
    reconnect: () -> Unit,
    onHealthy: (Boolean) -> Unit,
) {
    while (true) {
        val answered = withTimeoutOrNull(timeoutMs) { beat() } != null
        onHealthy(answered)
        if (!answered) reconnect()
        delay(intervalMs)
    }
}
