// Viewer side of the signaling contract (see the plan's "Signaling Contract").
// The db adapter and peer connection are injected so this runs under node --test.

export const OFFER_TIMEOUT_MS = 20_000;

/** The name the camera phone shows while someone watches: the first name, else the email before the @. */
export function viewerName(user) {
  if (!user) return null;
  return user.displayName?.trim().split(/\s+/)[0] || user.email?.split('@')[0] || null;
}

export class ViewerSession {
  #db;
  #createPeerConnection;
  #onState;
  #onTrack;
  #viewer;
  #setTimeout;
  #clearTimeout;
  #pc = null;
  #timer = null;
  #unsubscribers = [];
  #handledOffer = false;
  #ended = false;

  // The default timers are wrapped: calling the browser's own setTimeout via this.#setTimeout throws "Illegal invocation".
  constructor({ db, id, createPeerConnection, onState = () => {}, onTrack = () => {}, viewer = null,
    setTimeout = (fn, ms) => globalThis.setTimeout(fn, ms), clearTimeout = (handle) => globalThis.clearTimeout(handle) }) {
    this.id = id;
    this.#db = db;
    this.#createPeerConnection = createPeerConnection;
    this.#onState = onState;
    this.#onTrack = onTrack;
    this.#viewer = viewer;
    this.#setTimeout = setTimeout;
    this.#clearTimeout = clearTimeout;
  }

  async start() {
    this.#onState('starting');
    const db = this.#db;
    const value = { id: this.id, startedAt: db.serverTimestamp(), live: { [this.id]: true } };
    if (this.#viewer) value.viewer = this.#viewer;
    await db.set('session', value);
    await db.onDisconnectRemove(`session/live/${this.id}`);
    if (this.#ended) return;
    this.#timer = this.#setTimeout(() => this.end('no-camera'), OFFER_TIMEOUT_MS);
    this.#unsubscribers.push(db.onValue('session', (value) => this.#onSession(value)));
  }

  async end(reason = 'closed-by-viewer') {
    if (this.#ended) return;
    this.#ended = true;
    this.#clearTimeout(this.#timer);
    this.#unsubscribers.forEach((unsubscribe) => unsubscribe());
    this.#pc?.close();
    this.#onState('ended', reason);
    await this.#db.removeIf('session', (current) => current?.id === this.id);
    await this.#db.cancelOnDisconnect(`session/live/${this.id}`);
  }

  /** Asks the camera phone to switch its flashlight on or off for this session. */
  async setTorch(on) {
    if (this.#ended) return;
    await this.#db.set('session/torch', on);
  }

  getStats() {
    return this.#pc?.getStats() ?? Promise.resolve(new Map());
  }

  getAudioSender() {
    return this.#pc?.getTransceivers().find((t) => t.receiver.track.kind === 'audio')?.sender ?? null;
  }

  #onSession(value) {
    if (this.#ended) return;
    if (!value) return void this.end('camera-ended');
    if (value.id !== this.id) return void this.end('replaced');
    if (value.offer && !this.#handledOffer) {
      this.#handledOffer = true;
      this.#answer(value.offer, value.iceServers ?? []);
    }
  }

  async #answer(offer, iceServers) {
    this.#clearTimeout(this.#timer);
    this.#onState('connecting');
    const db = this.#db;
    const pc = this.#pc = this.#createPeerConnection(iceServers);
    pc.onicecandidate = ({ candidate }) => {
      if (!candidate || this.#ended) return;
      const { candidate: c, sdpMid, sdpMLineIndex } = candidate.toJSON();
      db.push('session/viewerCandidates', { candidate: c, sdpMid: sdpMid ?? null, sdpMLineIndex: sdpMLineIndex ?? 0 });
    };
    pc.ontrack = (event) => this.#onTrack(event.streams[0]);
    pc.onconnectionstatechange = () => {
      if (this.#ended) return;
      if (pc.connectionState === 'connected') this.#onState('live');
      else if (pc.connectionState === 'failed') this.end('connection-failed');
    };

    await pc.setRemoteDescription(offer);
    const audio = pc.getTransceivers().find((t) => t.receiver.track.kind === 'audio');
    if (audio) audio.direction = 'sendrecv';
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    if (this.#ended) return;
    await db.update('session', { answer: { type: answer.type, sdp: answer.sdp } });
    this.#unsubscribers.push(db.onChildAdded('session/cameraCandidates', (c) => {
      pc.addIceCandidate(c).catch((err) => console.warn('[JerryCam] bad camera candidate', err));
    }));
  }
}
