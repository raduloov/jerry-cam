// Screens, sign-in and live-view wiring for the iPhone page.
import { db, onUser, redirectResult, signIn, signOut } from './firebase.js';
import { ViewerSession, viewerName } from './signaling.js';
import { createTalkController } from './talk.js';
import { showStream } from './player.js';
import { loadSoundOn, saveSoundOn } from './sound.js';
import { batteryLabel, cameraOnline, connectionKind } from './stats.js';

const STATS_INTERVAL_MS = 5_000;
const PRESENCE_RECHECK_MS = 15_000; // notices a camera phone that has gone quiet, even when nothing in the database changes
const END_TEXT = {
  'camera-ended': 'Stream ended.',
  'connection-failed': 'Connection lost.',
  'no-camera': "Jerry Cam phone didn't answer.",
  'replaced': 'Opened somewhere else.',
  'closed-by-viewer': '',
};
const TALK_TEXT = { idle: 'Hold to talk', requesting: 'Hold to talk', talking: 'Talking…', blocked: 'Microphone blocked' };

const $ = (id) => document.getElementById(id);
const video = $('video');
const message = $('message');
const primary = $('primary');
const secondary = $('secondary');
const live = $('live');
const pill = $('pill');
const talkButton = $('talk');
const flashButton = $('flash');
const soundButton = $('sound');
const batteryBadge = $('battery');
const storage = (() => { try { return window.localStorage; } catch { return null; } })();

const state = {
  ready: false,        // auth state known
  user: null,
  denied: false,       // signed in with an account the database rules reject
  camera: null,        // the camera node: online, seenAt, battery, hasFlash
  serverOffset: 0,     // server clock minus local clock, for comparing with camera.seenAt
  torch: false,
  soundOn: loadSoundOn(storage), // the owner's choice, kept across visits
  phase: 'idle',       // idle | starting | connecting | live | ended
  endReason: null,
  needsTap: false,     // Safari refused to start sound without another tap
  error: '',
};
let session = null;
let talk = null;
let talking = false;
let statsTimer = null;
let unsubscribeCamera = null;

function setButton(button, label, onClick) {
  button.hidden = !label;
  button.textContent = label ?? '';
  button.onclick = onClick ?? null;
}

function render() {
  const watching = ['starting', 'connecting', 'live'].includes(state.phase);
  document.body.classList.toggle('watching', watching);
  document.body.classList.toggle('live', state.phase === 'live');
  live.hidden = state.phase !== 'live';
  const soundPlaying = state.soundOn && !state.needsTap;
  soundButton.textContent = soundPlaying ? '🔊' : '🔇';
  soundButton.setAttribute('aria-pressed', String(soundPlaying));
  soundButton.classList.toggle('blocked', state.needsTap);
  const online = cameraOnline(state.camera, Date.now() + state.serverOffset);
  flashButton.hidden = !state.camera?.hasFlash;
  flashButton.setAttribute('aria-pressed', String(state.torch));
  const battery = state.user && !state.denied && online ? batteryLabel(state.camera?.battery) : null;
  batteryBadge.hidden = !battery;
  batteryBadge.textContent = battery ? `🔋 ${battery.text}` : '';
  batteryBadge.classList.toggle('low', Boolean(battery?.low));

  let text = state.error;
  let action = [null, null];
  let quiet = [null, null];
  if (!state.ready) {
    text ||= '';
  } else if (!state.user) {
    action = ['Sign in with Google', () => signIn().catch(showError)];
  } else if (state.denied) {
    text = "This Google account isn't allowed.";
    quiet = ['Sign out', () => signOut()];
  } else if (watching) {
    text = 'Connecting…';
  } else if (!online) {
    text = 'Jerry Cam phone is offline.';
  } else if (state.phase === 'ended') {
    text = END_TEXT[state.endReason] ?? '';
    action = ['Watch again', watch];
  } else {
    action = ['Watch Jerry', watch];
  }
  message.textContent = text;
  setButton(primary, ...action);
  setButton(secondary, ...quiet);
}

function showError(err) {
  console.error('[JerryCam]', err);
  state.error = err?.message ?? String(err);
  render();
}

function applyRoomSound() {
  video.muted = talking || !state.soundOn || state.needsTap;
}

function watch() {
  state.phase = 'starting';
  state.endReason = null;
  state.needsTap = false;
  // Start playback inside the tap so Safari allows sound once the stream arrives.
  applyRoomSound();
  video.play().catch(() => {});
  state.torch = false;
  state.error = '';
  render();

  session = new ViewerSession({
    db,
    id: crypto.randomUUID(),
    createPeerConnection: (iceServers) => new RTCPeerConnection({ iceServers }),
    onState: onSessionState,
    viewer: viewerName(state.user),
    onTrack: (stream) => showStream(video, stream, {
      onSoundBlocked: () => { state.needsTap = true; applyRoomSound(); video.play().catch(() => {}); render(); },
    }),
  });
  talk = createTalkController({
    requestMic: async () =>
      (await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } }))
        .getAudioTracks()[0],
    attachMic: (track) => session.getAudioSender().replaceTrack(track),
    setRoomMuted: (muted) => { talking = muted; applyRoomSound(); },
    onState: (s) => {
      talkButton.textContent = TALK_TEXT[s];
      talkButton.className = s;
    },
  });
  talkButton.textContent = TALK_TEXT.idle;
  talkButton.className = 'idle';
  session.start().catch((err) => { showError(err); session?.end('connection-failed'); });
}

function toggleSound() {
  const on = !(state.soundOn && !state.needsTap);
  state.soundOn = on;
  state.needsTap = false;
  saveSoundOn(storage, on);
  applyRoomSound();
  if (on) video.play().catch(() => {}); // inside the tap, so Safari allows sound
  render();
}

function onSessionState(phase, reason) {
  state.phase = phase;
  if (phase === 'live') {
    updatePill();
    statsTimer ??= setInterval(updatePill, STATS_INTERVAL_MS);
  }
  if (phase === 'ended') {
    state.endReason = reason;
    state.torch = false;
    clearInterval(statsTimer);
    statsTimer = null;
    talk?.dispose();
    talk = null;
    talking = false;
    session = null;
    video.srcObject = null;
  }
  render();
}

async function updatePill() {
  const kind = session ? connectionKind(await session.getStats()) : 'unknown';
  pill.textContent = kind === 'unknown' ? 'Live' : `Live · ${kind}`;
}

function stopWatching() {
  session?.end();
}

talkButton.addEventListener('pointerdown', (e) => {
  e.preventDefault();
  talkButton.setPointerCapture?.(e.pointerId);
  talk?.press();
});
for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) {
  talkButton.addEventListener(type, () => talk?.release());
}
talkButton.addEventListener('contextmenu', (e) => e.preventDefault());
soundButton.addEventListener('click', toggleSound);
flashButton.addEventListener('click', () => {
  state.torch = !state.torch;
  session?.setTorch(state.torch).catch(showError);
  render();
});
document.addEventListener('visibilitychange', () => { if (document.hidden) stopWatching(); });
window.addEventListener('pagehide', stopWatching);

redirectResult.catch(showError);
db.onValue('.info/serverTimeOffset', (offset) => { state.serverOffset = offset ?? 0; render(); });
setInterval(render, PRESENCE_RECHECK_MS);
onUser((user) => {
  state.ready = true;
  state.user = user;
  state.denied = false;
  unsubscribeCamera?.();
  unsubscribeCamera = null;
  if (user) {
    unsubscribeCamera = db.onValue(
      'camera',
      (camera) => {
        state.camera = camera;
        state.denied = false;
        render();
      },
      () => { state.denied = true; render(); },
    );
  }
  render();
});
render();
