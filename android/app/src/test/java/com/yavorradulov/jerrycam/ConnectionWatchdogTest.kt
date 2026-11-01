package com.yavorradulov.jerrycam

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionWatchdogTest {
    private val healthy = mutableListOf<Boolean>()
    private var reconnects = 0

    @Test
    fun answeredHeartbeatsReportHealthyAndNeverReconnect() = runTest {
        val job = launch {
            watchConnection(INTERVAL, TIMEOUT, beat = { delay(100) }, reconnect = { reconnects++ }, onHealthy = { healthy += it })
        }
        advanceTimeBy(INTERVAL * 3 + 1_000)
        job.cancel()
        assertEquals(listOf(true, true, true, true), healthy)
        assertEquals(0, reconnects)
    }

    @Test
    fun aHeartbeatThatHangsForcesAReconnectAfterTheTimeout() = runTest {
        val job = launch {
            watchConnection(INTERVAL, TIMEOUT, beat = { awaitCancellation() }, reconnect = { reconnects++ }, onHealthy = { healthy += it })
        }
        advanceTimeBy(TIMEOUT - 1)
        runCurrent()
        assertEquals(0, reconnects)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, reconnects)
        assertEquals(listOf(false), healthy)
        job.cancel()
    }

    @Test
    fun healthyAgainOnceHeartbeatsAreAnswered() = runTest {
        var hang = true
        val job = launch {
            watchConnection(
                INTERVAL, TIMEOUT,
                beat = { if (hang) awaitCancellation() },
                reconnect = { reconnects++; hang = false },
                onHealthy = { healthy += it },
            )
        }
        advanceTimeBy(TIMEOUT + INTERVAL + 1)
        runCurrent()
        assertEquals(listOf(false, true), healthy)
        assertEquals(1, reconnects)
        job.cancel()
    }

    private companion object {
        const val INTERVAL = 60_000L
        const val TIMEOUT = 20_000L
    }
}
