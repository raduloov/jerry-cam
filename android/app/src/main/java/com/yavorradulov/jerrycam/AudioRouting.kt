package com.yavorradulov.jerrycam

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Plays the owner's voice loudly through the speaker while a call is live, and puts everything back after. */
class AudioRouting(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var savedMode: Int? = null
    private var savedVolume = 0

    fun enter() {
        if (savedMode != null) return
        savedMode = audio.mode
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            audio.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let(audio::setCommunicationDevice)
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
        }
        audio.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL, audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0,
        )
    }

    fun restore() {
        val mode = savedMode ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0)
        audio.mode = mode
        savedMode = null
    }
}
