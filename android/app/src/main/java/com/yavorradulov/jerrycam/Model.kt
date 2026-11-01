package com.yavorradulov.jerrycam

data class IceServerSpec(val urls: List<String>, val username: String? = null, val credential: String? = null)

data class Candidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

enum class PeerState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** The parts of /session the camera reacts to (see the plan's signaling contract). */
data class SessionSnapshot(
    val id: String,
    val viewerLive: Boolean,
    val answerSdp: String?,
    /** Keyed by RTDB push id, in chronological order. */
    val viewerCandidates: Map<String, Candidate>,
    /** The viewer wants the flashlight on. */
    val torch: Boolean = false,
    /** An offer is already there, so this session isn't new to the camera. */
    val hasOffer: Boolean = false,
    /** The viewer's first name, for the camera phone's screen. */
    val viewer: String? = null,
) {
    companion object {
        fun fromMap(value: Map<String, Any?>?): SessionSnapshot? {
            val id = value?.get("id") as? String ?: return null
            val live = (value["live"] as? Map<*, *>)?.get(id) == true
            val answer = (value["answer"] as? Map<*, *>)?.get("sdp") as? String
            val candidates = (value["viewerCandidates"] as? Map<*, *>).orEmpty()
                .mapNotNull { (key, raw) ->
                    val map = raw as? Map<*, *> ?: return@mapNotNull null
                    val candidate = map["candidate"] as? String ?: return@mapNotNull null
                    key.toString() to Candidate(
                        candidate,
                        map["sdpMid"] as? String,
                        (map["sdpMLineIndex"] as? Number)?.toInt() ?: 0,
                    )
                }
                .sortedBy { it.first }
                .toMap(LinkedHashMap())
            return SessionSnapshot(
                id, live, answer, candidates,
                torch = value["torch"] == true,
                hasOffer = value["offer"] != null,
                viewer = value["viewer"] as? String,
            )
        }
    }
}
