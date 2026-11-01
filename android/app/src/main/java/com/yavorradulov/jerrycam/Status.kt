package com.yavorradulov.jerrycam

/** What the service knows, shared with the screen. */
data class ServiceStatus(
    val running: Boolean = false,
    /** Connected to the database and its heartbeats are answered. */
    val online: Boolean = false,
    val live: Boolean = false,
    val viewer: String? = null,
    /** Signed in with an account the database rules reject. */
    val wrongAccount: Boolean = false,
    val battery: BatteryState? = null,
)

/** One-time setup the screen checks for itself. */
data class Setup(
    val signedIn: Boolean,
    val cameraAndMic: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
    /** The owner hasn't pressed Stop. */
    val enabled: Boolean,
)

enum class Mood { WELCOME, PROBLEM, STOPPED, STARTING, OFFLINE, READY, LIVE }

/** What the banner at the bottom asks for, most important first. */
enum class Fix { SWITCH_ACCOUNT, CAMERA_AND_MIC, NOTIFICATIONS, BATTERY }

data class HomeScreen(val mood: Mood, val fix: Fix?)

fun homeScreen(setup: Setup, status: ServiceStatus): HomeScreen {
    if (!setup.signedIn) return HomeScreen(Mood.WELCOME, null)
    if (status.wrongAccount) return HomeScreen(Mood.PROBLEM, Fix.SWITCH_ACCOUNT)
    if (!setup.cameraAndMic) return HomeScreen(Mood.PROBLEM, Fix.CAMERA_AND_MIC)
    // These don't stop the camera, so they show alongside the normal status.
    val fix = when {
        !setup.notifications -> Fix.NOTIFICATIONS
        !setup.batteryUnrestricted -> Fix.BATTERY
        else -> null
    }
    val mood = when {
        !setup.enabled -> Mood.STOPPED
        !status.running -> Mood.STARTING
        status.live -> Mood.LIVE
        !status.online -> Mood.OFFLINE
        else -> Mood.READY
    }
    return HomeScreen(mood, fix)
}
