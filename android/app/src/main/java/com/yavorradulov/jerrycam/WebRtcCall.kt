package com.yavorradulov.jerrycam

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver.RtpTransceiverDirection
import org.webrtc.RtpTransceiver.RtpTransceiverInit
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * One WebRTC session with its own factory, audio module and camera capturer, so that close()
 * provably releases the camera and the microphone.
 */
class WebRtcCall(private val context: Context) : Call {
    private val audioRouting = AudioRouting(context)
    private var eglBase: EglBase? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: org.webrtc.VideoSource? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var peer: PeerConnection? = null
    @Volatile private var torchWanted = false
    @Volatile private var closed = false

    override fun start(iceServers: List<IceServerSpec>, events: CallEvents) {
        initializeOnce(context)
        audioRouting.enter()
        val egl = EglBase.create().also { eglBase = it }
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
            .also { audioModule = it }
        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
            .also { this.factory = it }

        val enumerator = Camera2Enumerator(context)
        val cameraName = enumerator.deviceNames.firstOrNull(enumerator::isBackFacing) ?: enumerator.deviceNames.first()
        val capturer = enumerator.createCapturer(cameraName, cameraEvents).also { this.capturer = it }
        val helper = SurfaceTextureHelper.create("JerryCamCapture", egl.eglBaseContext).also { textureHelper = it }
        val videoSource = factory.createVideoSource(false).also { this.videoSource = it }
        capturer.initialize(helper, context, videoSource.capturerObserver)
        capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        val audioSource = factory.createAudioSource(MediaConstraints()).also { this.audioSource = it }

        val config = PeerConnection.RTCConfiguration(
            iceServers.map { server ->
                PeerConnection.IceServer.builder(server.urls)
                    .setUsername(server.username.orEmpty())
                    .setPassword(server.credential.orEmpty())
                    .createIceServer()
            },
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val peer = factory.createPeerConnection(config, observer(events)) ?: error("createPeerConnection failed")
        this.peer = peer

        val streams = listOf("jerry")
        val video = peer.addTransceiver(
            factory.createVideoTrack("video", videoSource), RtpTransceiverInit(RtpTransceiverDirection.SEND_ONLY, streams),
        )
        peer.addTransceiver(
            factory.createAudioTrack("audio", audioSource), RtpTransceiverInit(RtpTransceiverDirection.SEND_RECV, streams),
        )
        val parameters = video.sender.parameters
        parameters.encodings.forEach { it.maxBitrateBps = MAX_VIDEO_BITRATE_BPS }
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        video.sender.setParameters(parameters)

        peer.createOffer(
            sdpObserver("createOffer") { offer ->
                peer.setLocalDescription(sdpObserver("setLocalDescription") { events.onOffer(offer!!.description) }, offer)
            },
            MediaConstraints(),
        )
    }

    override fun applyAnswer(sdp: String) {
        peer?.setRemoteDescription(sdpObserver("setRemoteDescription") {}, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun addRemoteCandidate(c: Candidate) {
        peer?.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate))
    }

    override fun setTorch(on: Boolean) {
        torchWanted = on
        applyTorch()
    }

    /**
     * libwebrtc owns the camera while streaming and has no flashlight API, so this rebuilds its repeating
     * capture request with the flash mode set. It reads Camera2Session's private fields (webrtc-sdk 150.x).
     */
    private fun applyTorch() {
        val capturer = capturer ?: return
        if (closed) return
        try {
            // Null until the camera has opened; cameraEvents re-applies on the first frame.
            val session = privateField(Class.forName("org.webrtc.CameraCapturer"), capturer, "currentSession") ?: return
            val type = session.javaClass
            val captureSession = privateField(type, session, "captureSession") as? CameraCaptureSession ?: return
            val device = privateField(type, session, "cameraDevice") as CameraDevice
            val surface = privateField(type, session, "surface") as Surface
            val handler = privateField(type, session, "cameraThreadHandler") as Handler
            val format = privateField(type, session, "captureFormat") ?: return
            val framerate = format.javaClass.getField("framerate").get(format)!!
            val unit = privateField(type, session, "fpsUnitFactor") as Int
            val fps = Range(
                framerate.javaClass.getField("min").getInt(framerate) / unit,
                framerate.javaClass.getField("max").getInt(framerate) / unit,
            )
            val on = torchWanted
            handler.post {
                try {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AE_LOCK, false)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.FLASH_MODE, if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                        addTarget(surface)
                    }.build()
                    captureSession.setRepeatingRequest(request, null, handler)
                    Log.i(TAG, "flashlight ${if (on) "on" else "off"}")
                } catch (e: Exception) {
                    Log.w(TAG, "flashlight failed: $e")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "flashlight unavailable: $e")
        }
    }

    private fun privateField(type: Class<*>, target: Any, name: String): Any? =
        type.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onFirstFrameAvailable() {
            if (torchWanted) applyTorch()
        }

        override fun onCameraError(error: String) { Log.w(TAG, "camera error: $error") }
        override fun onCameraDisconnected() = Unit
        override fun onCameraFreezed(error: String) { Log.w(TAG, "camera frozen: $error") }
        override fun onCameraOpening(cameraName: String) = Unit
        override fun onCameraClosed() = Unit
    }

    override fun close() {
        if (closed) return
        closed = true
        // Camera first, so the indicator goes off as early as possible.
        try {
            capturer?.stopCapture()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        capturer?.dispose()
        peer?.dispose()
        audioSource?.dispose()
        videoSource?.dispose()
        textureHelper?.dispose()
        factory?.dispose()
        audioModule?.release()
        eglBase?.release()
        audioRouting.restore()
        Log.i(TAG, "call closed: camera and microphone released")
    }

    private fun observer(events: CallEvents) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) =
            events.onLocalCandidate(Candidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "peer connection: $newState")
            events.onPeerState(PeerState.valueOf(newState.name))
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            val kind = if (" typ relay" in event.local.sdp) "relay" else "direct"
            Log.i(TAG, "connected $kind")
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun sdpObserver(step: String, onSuccess: (SessionDescription?) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onSuccess(description)
        override fun onSetSuccess() = onSuccess(null)
        override fun onCreateFailure(error: String) { Log.e(TAG, "$step failed: $error") }
        override fun onSetFailure(error: String) { Log.e(TAG, "$step failed: $error") }
    }

    private companion object {
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_FPS = 24
        const val MAX_VIDEO_BITRATE_BPS = 1_000_000

        @Volatile private var initialized = false

        fun initializeOnce(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
            )
            initialized = true
        }
    }
}
