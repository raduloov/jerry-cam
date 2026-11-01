package com.yavorradulov.jerrycam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test
    fun fromMapNullOrMissingIdIsNull() {
        assertNull(SessionSnapshot.fromMap(null))
        assertNull(SessionSnapshot.fromMap(mapOf("live" to mapOf("s1" to true))))
    }

    @Test
    fun fromMapReadsWhoIsWatching() {
        assertEquals("Ana", SessionSnapshot.fromMap(mapOf("id" to "s1", "viewer" to "Ana"))!!.viewer)
        assertNull(SessionSnapshot.fromMap(mapOf("id" to "s1"))!!.viewer)
    }

    @Test
    fun fromMapReadsLiveFlagForOwnIdOnly() {
        assertFalse(SessionSnapshot.fromMap(mapOf("id" to "s1", "live" to mapOf("other" to true)))!!.viewerLive)
        assertFalse(SessionSnapshot.fromMap(mapOf("id" to "s1"))!!.viewerLive)
        assertTrue(SessionSnapshot.fromMap(mapOf("id" to "s1", "live" to mapOf("s1" to true)))!!.viewerLive)
    }

    @Test
    fun fromMapKeepsCandidateOrderAndAnswer() {
        // RTDB hands back a HashMap; push ids sort chronologically.
        val candidates = HashMap<String, Any?>().apply {
            put("-Nb", mapOf("candidate" to "cand-b", "sdpMid" to "0", "sdpMLineIndex" to 0L))
            put("-Na", mapOf("candidate" to "cand-a", "sdpMid" to null, "sdpMLineIndex" to 1L))
        }
        val snapshot = SessionSnapshot.fromMap(
            mapOf(
                "id" to "s1",
                "answer" to mapOf("type" to "answer", "sdp" to "ANSWER"),
                "viewerCandidates" to candidates,
            ),
        )!!
        assertEquals("ANSWER", snapshot.answerSdp)
        assertEquals(listOf("-Na", "-Nb"), snapshot.viewerCandidates.keys.toList())
        assertEquals(Candidate("cand-a", null, 1), snapshot.viewerCandidates["-Na"])
        assertEquals(Candidate("cand-b", "0", 0), snapshot.viewerCandidates["-Nb"])
    }

    @Test
    fun fromMapReadsTorch() {
        assertFalse(SessionSnapshot.fromMap(mapOf("id" to "s1"))!!.torch)
        assertTrue(SessionSnapshot.fromMap(mapOf("id" to "s1", "torch" to true))!!.torch)
    }

    @Test
    fun fromMapReadsWhetherAnOfferExists() {
        assertFalse(SessionSnapshot.fromMap(mapOf("id" to "s1"))!!.hasOffer)
        assertTrue(SessionSnapshot.fromMap(mapOf("id" to "s1", "offer" to mapOf("type" to "offer", "sdp" to "X")))!!.hasOffer)
    }
}
