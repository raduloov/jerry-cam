package com.yavorradulov.jerrycam

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenTest {
    private val allSet = Setup(signedIn = true, cameraAndMic = true, notifications = true, batteryUnrestricted = true, enabled = true)
    private val ready = ServiceStatus(running = true, online = true)

    @Test
    fun notSignedInWelcomes() {
        assertEquals(HomeScreen(Mood.WELCOME, null), homeScreen(allSet.copy(signedIn = false), ServiceStatus()))
    }

    @Test
    fun wrongAccountCantStart() {
        assertEquals(HomeScreen(Mood.PROBLEM, Fix.SWITCH_ACCOUNT), homeScreen(allSet, ready.copy(wrongAccount = true)))
    }

    @Test
    fun missingCameraOrMicCantStart() {
        assertEquals(HomeScreen(Mood.PROBLEM, Fix.CAMERA_AND_MIC), homeScreen(allSet.copy(cameraAndMic = false), ServiceStatus()))
    }

    @Test
    fun turnedOffIsStopped() {
        assertEquals(HomeScreen(Mood.STOPPED, null), homeScreen(allSet.copy(enabled = false), ServiceStatus()))
    }

    @Test
    fun startingWhileTheServiceComesUp() {
        assertEquals(HomeScreen(Mood.STARTING, null), homeScreen(allSet, ServiceStatus()))
    }

    @Test
    fun offlineWithoutADatabaseConnection() {
        assertEquals(HomeScreen(Mood.OFFLINE, null), homeScreen(allSet, ready.copy(online = false)))
    }

    @Test
    fun readyAndLive() {
        assertEquals(HomeScreen(Mood.READY, null), homeScreen(allSet, ready))
        assertEquals(HomeScreen(Mood.LIVE, null), homeScreen(allSet, ready.copy(live = true, viewer = "Ana")))
    }

    @Test
    fun liveEvenIfTheDatabaseDropsMidStream() {
        // Video flows phone to phone; the database is only needed to set the call up.
        assertEquals(HomeScreen(Mood.LIVE, null), homeScreen(allSet, ready.copy(live = true, online = false)))
    }

    @Test
    fun fixesThatDontStopTheCameraShowAlongsideTheStatus() {
        assertEquals(HomeScreen(Mood.READY, Fix.NOTIFICATIONS), homeScreen(allSet.copy(notifications = false), ready))
        assertEquals(HomeScreen(Mood.READY, Fix.BATTERY), homeScreen(allSet.copy(batteryUnrestricted = false), ready))
        assertEquals(
            HomeScreen(Mood.READY, Fix.NOTIFICATIONS),
            homeScreen(allSet.copy(notifications = false, batteryUnrestricted = false), ready),
        )
    }
}
