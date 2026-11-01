package com.yavorradulov.jerrycam

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {
    private val servers = listOf(IceServerSpec(listOf("stun:example:3478")))
    private val c1 = Candidate("cand-1", "0", 0)
    private val c2 = Candidate("cand-2", "0", 0)

    private class FakeCall(private val failOnStart: Boolean = false) : Call {
        var iceServers: List<IceServerSpec>? = null
        lateinit var events: CallEvents
        val log = mutableListOf<String>()
        var closed = false

        override fun start(iceServers: List<IceServerSpec>, events: CallEvents) {
            if (failOnStart) error("camera unavailable")
            this.iceServers = iceServers
            this.events = events
        }
        override fun applyAnswer(sdp: String) { log += "answer:$sdp" }
        override fun addRemoteCandidate(c: Candidate) { log += "cand:${c.candidate}" }
        override fun setTorch(on: Boolean) { log += "torch:$on" }
        override fun close() { closed = true }
    }

    private class FakeSignaling : CameraSignaling {
        val offers = mutableListOf<Triple<String, String, List<IceServerSpec>>>()
        val candidates = mutableListOf<Pair<String, Candidate>>()
        val ended = mutableListOf<String>()
        override fun publishOffer(sessionId: String, sdp: String, iceServers: List<IceServerSpec>) {
            offers += Triple(sessionId, sdp, iceServers)
        }
        override fun publishCandidate(sessionId: String, c: Candidate) { candidates += sessionId to c }
        override fun endSession(sessionId: String) { ended += sessionId }
    }

    private val signaling = FakeSignaling()
    private val calls = mutableListOf<FakeCall>()
    private val liveChanges = mutableListOf<Boolean>()

    private fun TestScope.coordinator(fetch: suspend () -> List<IceServerSpec> = { servers }) =
        SessionCoordinator(backgroundScope, signaling, fetch, { FakeCall().also { calls += it } }, { liveChanges += it })

    private fun snap(
        id: String = "s1",
        live: Boolean = true,
        answer: String? = null,
        candidates: Map<String, Candidate> = emptyMap(),
        torch: Boolean = false,
        hasOffer: Boolean = false,
    ) = SessionSnapshot(id, live, answer, candidates, torch, hasOffer)

    @Test
    fun liveSessionStartsCallWithFetchedServersAndPublishesOffer() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        assertEquals(1, calls.size)
        assertEquals(servers, calls[0].iceServers)
        calls[0].events.onOffer("OFFER")
        calls[0].events.onLocalCandidate(c1)
        runCurrent()
        assertEquals(listOf(Triple("s1", "OFFER", servers)), signaling.offers)
        assertEquals(listOf("s1" to c1), signaling.candidates)
    }

    @Test
    fun sessionWithoutViewerLiveDoesNotStartCall() = runTest {
        coordinator().onSession(snap(live = false))
        runCurrent()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun viewerLiveFalseClosesCallAndEndsSession() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        coordinator.onSession(snap(live = false))
        runCurrent()
        assertTrue(calls[0].closed)
        assertEquals(listOf("s1"), signaling.ended)
    }

    @Test
    fun answerThenCandidatesAppliedOnceInOrder() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        val full = snap(answer = "A", candidates = linkedMapOf("k1" to c1, "k2" to c2))
        coordinator.onSession(full)
        coordinator.onSession(full)
        runCurrent()
        assertEquals(listOf("answer:A", "cand:cand-1", "cand:cand-2"), calls[0].log)
    }

    @Test
    fun noCandidatesAppliedBeforeAnswer() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        coordinator.onSession(snap(candidates = mapOf("k1" to c1)))
        runCurrent()
        assertTrue(calls[0].log.isEmpty())
        coordinator.onSession(snap(answer = "A", candidates = mapOf("k1" to c1)))
        runCurrent()
        assertEquals(listOf("answer:A", "cand:cand-1"), calls[0].log)
    }

    @Test
    fun answerArrivingWhileIceServersAreFetchedIsAppliedOnceCallStarts() = runTest {
        val gate = CompletableDeferred<List<IceServerSpec>>()
        val coordinator = coordinator { gate.await() }
        coordinator.onSession(snap())
        runCurrent()
        coordinator.onSession(snap(answer = "A"))
        gate.complete(servers)
        runCurrent()
        assertEquals(listOf("answer:A"), calls[0].log)
    }

    @Test
    fun sessionRemovedClosesCall() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        coordinator.onSession(null)
        runCurrent()
        assertTrue(calls[0].closed)
        assertTrue(signaling.ended.isEmpty())
    }

    @Test
    fun newSessionIdClosesOldCallAndStartsNew() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap("s1"))
        runCurrent()
        coordinator.onSession(snap("s2"))
        runCurrent()
        assertEquals(2, calls.size)
        assertTrue(calls[0].closed)
        assertFalse(calls[1].closed)
        assertTrue(signaling.ended.isEmpty())
    }

    @Test
    fun eventsFromAReplacedCallAreIgnored() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap("s1"))
        runCurrent()
        coordinator.onSession(snap("s2"))
        runCurrent()
        calls[0].events.onPeerState(PeerState.FAILED)
        calls[0].events.onOffer("STALE")
        runCurrent()
        assertFalse(calls[1].closed)
        assertTrue(signaling.ended.isEmpty())
        assertTrue(signaling.offers.isEmpty())
    }

    @Test
    fun peerFailedClosesCallAndEndsSession() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        calls[0].events.onPeerState(PeerState.FAILED)
        runCurrent()
        assertTrue(calls[0].closed)
        assertEquals(listOf("s1"), signaling.ended)
    }

    @Test
    fun disconnectedFor5sEndsSession() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        calls[0].events.onPeerState(PeerState.CONNECTED)
        calls[0].events.onPeerState(PeerState.DISCONNECTED)
        runCurrent()
        advanceTimeBy(SessionCoordinator.DISCONNECT_GRACE_MS - 1)
        runCurrent()
        assertFalse(calls[0].closed)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(calls[0].closed)
        assertEquals(listOf("s1"), signaling.ended)
        assertEquals(5_000L, SessionCoordinator.DISCONNECT_GRACE_MS)
    }

    @Test
    fun reconnectWithin5sKeepsSession() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        calls[0].events.onPeerState(PeerState.CONNECTED)
        calls[0].events.onPeerState(PeerState.DISCONNECTED)
        runCurrent()
        advanceTimeBy(3_000)
        calls[0].events.onPeerState(PeerState.CONNECTED)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(calls[0].closed)
        assertTrue(signaling.ended.isEmpty())
    }

    @Test
    fun notConnectedWithin30sEndsSession() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        advanceTimeBy(SessionCoordinator.CONNECT_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(calls[0].closed)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(calls[0].closed)
        assertEquals(listOf("s1"), signaling.ended)
        assertEquals(30_000L, SessionCoordinator.CONNECT_TIMEOUT_MS)
    }

    @Test
    fun sessionRemovedWhileFetchingIceServersNeverStartsCall() = runTest {
        val gate = CompletableDeferred<List<IceServerSpec>>()
        val coordinator = coordinator { gate.await() }
        coordinator.onSession(snap())
        runCurrent()
        coordinator.onSession(null)
        gate.complete(servers)
        runCurrent()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun liveChangedTrueOnConnectedFalseOnClose() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        calls[0].events.onPeerState(PeerState.CONNECTED)
        runCurrent()
        assertEquals(listOf(true), liveChanges)
        coordinator.onSession(null)
        runCurrent()
        assertEquals(listOf(true, false), liveChanges)
    }

    @Test
    fun shutdownClosesActiveCallAndEndsSession() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        coordinator.shutdown()
        runCurrent()
        assertTrue(calls[0].closed)
        assertEquals(listOf("s1"), signaling.ended)
    }

    @Test
    fun torchFollowsTheViewerAndIsAppliedOncePerChange() = runTest {
        val coordinator = coordinator()
        coordinator.onSession(snap())
        runCurrent()
        coordinator.onSession(snap(torch = true))
        coordinator.onSession(snap(torch = true))
        coordinator.onSession(snap(torch = false))
        runCurrent()
        assertEquals(listOf("torch:true", "torch:false"), calls[0].log)
    }

    @Test
    fun torchRequestedBeforeTheCallStartsIsApplied() = runTest {
        val gate = CompletableDeferred<List<IceServerSpec>>()
        val coordinator = coordinator { gate.await() }
        coordinator.onSession(snap())
        runCurrent()
        coordinator.onSession(snap(torch = true))
        gate.complete(servers)
        runCurrent()
        assertEquals(listOf("torch:true"), calls[0].log)
    }

    @Test
    fun newSessionThatAlreadyHasAnOfferOrAnswerIsEndedWithoutTurningOnTheCamera() = runTest {
        // Left over from before a service restart: the viewer can't answer a second offer.
        coordinator().onSession(snap("s1", hasOffer = true))
        runCurrent()
        coordinator().onSession(snap("s2", answer = "OLD"))
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(listOf("s1", "s2"), signaling.ended)
    }

    @Test
    fun callThatFailsToStartIsClosedAndTheSessionEnded() = runTest {
        val coordinator = SessionCoordinator(
            backgroundScope, signaling, { servers }, { FakeCall(failOnStart = true).also { calls += it } }, { liveChanges += it },
        )
        coordinator.onSession(snap())
        runCurrent()
        assertTrue(calls[0].closed)
        assertEquals(listOf("s1"), signaling.ended)
    }
}
