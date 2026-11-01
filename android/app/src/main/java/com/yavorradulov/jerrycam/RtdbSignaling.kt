package com.yavorradulov.jerrycam

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Camera side of the signaling contract over the Realtime Database. */
class RtdbSignaling(private val db: FirebaseDatabase) : CameraSignaling {
    private val camera = db.getReference("camera")
    private val online = camera.child("online")
    private val seenAt = camera.child("seenAt")
    private val battery = camera.child("battery")
    private val session = db.getReference("session")
    private val connectedInfo = db.getReference(".info/connected")

    private val denied = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = denied

    private val isConnected = MutableStateFlow(false)
    /** The client's own view of its connection; it can stay true on a dead connection (see heartbeat). */
    val connected: StateFlow<Boolean> = isConnected

    // Presence: re-arm onDisconnect every time the connection comes back.
    private val connectedListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            isConnected.value = snapshot.getValue(Boolean::class.java) == true
            if (!isConnected.value) return
            online.onDisconnect().removeValue()
            online.setValue(true) { error, _ -> onWriteResult(error) }
        }

        override fun onCancelled(error: DatabaseError) = Unit
    }

    fun goOnline() {
        connectedInfo.addValueEventListener(connectedListener)
    }

    fun goOffline() {
        connectedInfo.removeEventListener(connectedListener)
        online.onDisconnect().cancel()
        online.removeValue()
    }

    /** Writes camera/seenAt and returns once the server answers, whether it accepted the write or not. */
    suspend fun heartbeat() = suspendCancellableCoroutine { cont ->
        seenAt.setValue(ServerValue.TIMESTAMP) { error, _ ->
            onWriteResult(error)
            if (cont.isActive) cont.resume(Unit)
        }
    }

    /** Drops the connection and dials again. Pending writes are kept and sent once it's back. */
    fun reconnect() {
        Log.w(TAG, "database connection went silent, reconnecting")
        db.goOffline()
        db.goOnline()
    }

    fun publishBattery(state: BatteryState) {
        battery.setValue(mapOf("level" to state.level, "charging" to state.charging)) { error, _ -> onWriteResult(error) }
    }

    fun publishHasFlash(hasFlash: Boolean) {
        camera.child("hasFlash").setValue(hasFlash) { error, _ -> onWriteResult(error) }
    }

    fun observeSession(onSnapshot: (SessionSnapshot?) -> Unit): () -> Unit {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                @Suppress("UNCHECKED_CAST")
                onSnapshot(SessionSnapshot.fromMap(snapshot.value as? Map<String, Any?>))
            }

            override fun onCancelled(error: DatabaseError) = onWriteResult(error)
        }
        session.addValueEventListener(listener)
        return { session.removeEventListener(listener) }
    }

    override fun publishOffer(sessionId: String, sdp: String, iceServers: List<IceServerSpec>) {
        val servers = iceServers.map { mapOf("urls" to it.urls, "username" to it.username, "credential" to it.credential) }
        session.updateChildren(mapOf("offer" to mapOf("type" to "offer", "sdp" to sdp), "iceServers" to servers)) { error, _ ->
            onWriteResult(error)
        }
    }

    override fun publishCandidate(sessionId: String, c: Candidate) {
        val value = mapOf("candidate" to c.candidate, "sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex)
        session.child("cameraCandidates").push().setValue(value) { error, _ -> onWriteResult(error) }
    }

    override fun endSession(sessionId: String) {
        session.runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                // A null local cache is a guess: writing null lets the server retry with the real value.
                if (data.value == null || data.child("id").value == sessionId) {
                    data.value = null
                    return Transaction.success(data)
                }
                return Transaction.abort()
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) Log.w(TAG, "ending session failed: ${error.message}")
            }
        })
    }

    private fun onWriteResult(error: DatabaseError?) {
        if (error == null) {
            denied.value = false
        } else {
            Log.w(TAG, "database write failed: ${error.message}")
            if (error.code == DatabaseError.PERMISSION_DENIED) denied.value = true
        }
    }
}

const val TAG = "JerryCam"
