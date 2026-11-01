package com.yavorradulov.jerrycam

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface CallEvents {
    fun onOffer(sdp: String)
    fun onLocalCandidate(c: Candidate)
    fun onPeerState(s: PeerState)
}

/** One WebRTC session. Starting it turns the camera and mic on; close() turns them off. */
interface Call {
    fun start(iceServers: List<IceServerSpec>, events: CallEvents)
    fun applyAnswer(sdp: String)
    fun addRemoteCandidate(c: Candidate)
    fun setTorch(on: Boolean)
    fun close()
}

interface CameraSignaling {
    fun publishOffer(sessionId: String, sdp: String, iceServers: List<IceServerSpec>)
    fun publishCandidate(sessionId: String, c: Candidate)

    /** Removes /session only if it still belongs to [sessionId]. */
    fun endSession(sessionId: String)
}

/**
 * Decides when the camera is on. A call runs only while the viewer's session is live, and ends
 * when the viewer leaves, the connection fails or stays down, or never comes up in time.
 * Every method must be called on [scope]'s thread; call events are posted back onto it.
 */
class SessionCoordinator(
    private val scope: CoroutineScope,
    private val signaling: CameraSignaling,
    private val fetchIceServers: suspend () -> List<IceServerSpec>,
    private val newCall: () -> Call,
    private val onLiveChanged: (Boolean) -> Unit,
) {
    private var sessionId: String? = null
    private var latest: SessionSnapshot? = null
    private var call: Call? = null
    private var answerApplied = false
    private var torchOn = false
    private val appliedCandidates = mutableSetOf<String>()
    private var startJob: Job? = null
    private var connectTimeout: Job? = null
    private var disconnectGrace: Job? = null
    private var live = false

    fun onSession(snapshot: SessionSnapshot?) {
        val current = sessionId
        when {
            snapshot?.viewerLive == true && snapshot.id == current -> {
                latest = snapshot
                applyRemote()
            }
            snapshot?.viewerLive == true -> {
                stop()
                // An offer or answer already there means a leftover from before this service (re)started:
                // the viewer won't answer a second offer, so end it rather than run the camera for nothing.
                if (snapshot.hasOffer || snapshot.answerSdp != null) signaling.endSession(snapshot.id) else begin(snapshot)
            }
            current != null -> {
                stop()
                // The viewer's connection dropped but its session node is still there: clear it.
                if (snapshot?.id == current) signaling.endSession(current)
            }
        }
    }

    fun shutdown() {
        sessionId?.let { end(it) }
    }

    private fun begin(snapshot: SessionSnapshot) {
        val id = snapshot.id
        sessionId = id
        latest = snapshot
        startJob = scope.launch {
            val servers = fetchIceServers()
            if (sessionId != id) return@launch
            val call = newCall().also { call = it }
            try {
                call.start(servers, events(id, servers))
            } catch (e: Exception) {
                Log.w(TAG, "call failed to start: $e")
                end(id)
                return@launch
            }
            connectTimeout = scope.launch {
                delay(CONNECT_TIMEOUT_MS)
                end(id)
            }
            applyRemote()
        }
    }

    private fun events(id: String, servers: List<IceServerSpec>) = object : CallEvents {
        override fun onOffer(sdp: String) = post(id) { signaling.publishOffer(id, sdp, servers) }
        override fun onLocalCandidate(c: Candidate) = post(id) { signaling.publishCandidate(id, c) }
        override fun onPeerState(s: PeerState) = post(id) { onPeerState(id, s) }
    }

    private fun post(id: String, block: () -> Unit) {
        scope.launch { if (sessionId == id) block() }
    }

    private fun onPeerState(id: String, state: PeerState) {
        when (state) {
            PeerState.CONNECTED -> {
                connectTimeout?.cancel()
                disconnectGrace?.cancel()
                disconnectGrace = null
                setLive(true)
            }
            PeerState.DISCONNECTED -> if (disconnectGrace == null) {
                disconnectGrace = scope.launch {
                    delay(DISCONNECT_GRACE_MS)
                    end(id)
                }
            }
            PeerState.FAILED -> end(id)
            else -> Unit
        }
    }

    private fun applyRemote() {
        val call = call ?: return
        val snapshot = latest ?: return
        if (snapshot.torch != torchOn) {
            call.setTorch(snapshot.torch)
            torchOn = snapshot.torch
        }
        if (!answerApplied) {
            call.applyAnswer(snapshot.answerSdp ?: return)
            answerApplied = true
        }
        for ((key, candidate) in snapshot.viewerCandidates) {
            if (appliedCandidates.add(key)) call.addRemoteCandidate(candidate)
        }
    }

    private fun end(id: String) {
        if (sessionId != id) return
        stop()
        signaling.endSession(id)
    }

    private fun stop() {
        startJob?.cancel()
        connectTimeout?.cancel()
        disconnectGrace?.cancel()
        startJob = null
        connectTimeout = null
        disconnectGrace = null
        call?.close()
        call = null
        sessionId = null
        latest = null
        answerApplied = false
        torchOn = false
        appliedCandidates.clear()
        setLive(false)
    }

    private fun setLive(value: Boolean) {
        if (live == value) return
        live = value
        onLiveChanged(value)
    }

    companion object {
        const val DISCONNECT_GRACE_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
