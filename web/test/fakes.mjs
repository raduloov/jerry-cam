// Test doubles for the db adapter and RTCPeerConnection.

export const flush = async (n = 5) => {
  for (let i = 0; i < n; i++) await new Promise((r) => setImmediate(r));
};

export function createFakeDb() {
  const root = {};
  const valueSubs = new Set();
  const childSubs = new Set();
  const log = [];
  let pushCount = 0;

  const keys = (path) => path.split('/').filter(Boolean);
  const get = (path) => keys(path).reduce((o, k) => (o == null ? undefined : o[k]), root) ?? null;
  const put = (path, value) => {
    const ks = keys(path);
    let o = root;
    for (const k of ks.slice(0, -1)) o = o[k] ??= {};
    if (value == null) delete o[ks.at(-1)];
    else o[ks.at(-1)] = structuredClone(value);
  };
  const notifyChildren = (sub) => {
    for (const [k, v] of Object.entries(get(sub.path) ?? {})) {
      if (!sub.seen.has(k)) { sub.seen.add(k); sub.cb(v); }
    }
  };
  const notify = () => {
    for (const s of [...valueSubs]) s.cb(get(s.path));
    for (const s of [...childSubs]) notifyChildren(s);
  };
  const update = (path, values) => { for (const [k, v] of Object.entries(values)) put(`${path}/${k}`, v); notify(); };

  return {
    log,
    get,
    async set(path, value) { log.push(['set', path, value]); put(path, value); notify(); },
    async update(path, values) { log.push(['update', path, values]); update(path, values); },
    async push(path, value) { log.push(['push', path, value]); put(`${path}/k${pushCount++}`, value); notify(); },
    async removeIf(path, predicate) {
      log.push(['removeIf', path, predicate]);
      if (predicate(get(path))) { put(path, null); notify(); }
    },
    onValue(path, cb) {
      const sub = { path, cb };
      valueSubs.add(sub);
      cb(get(path));
      return () => valueSubs.delete(sub);
    },
    onChildAdded(path, cb) {
      const sub = { path, cb, seen: new Set() };
      childSubs.add(sub);
      notifyChildren(sub);
      return () => childSubs.delete(sub);
    },
    async onDisconnectRemove(path) { log.push(['onDisconnectRemove', path]); },
    async cancelOnDisconnect(path) { log.push(['cancelOnDisconnect', path]); },
    serverTimestamp: () => 'TS',
    // Writes made by the other side (the camera phone).
    external: {
      set(path, value) { put(path, value); notify(); },
      update,
    },
  };
}

export class FakePeer {
  constructor(iceServers) {
    this.iceServers = iceServers;
    this.connectionState = 'new';
    this.remoteDescription = null;
    this.localDescription = null;
    this.closed = false;
    this.addedCandidates = [];
    this.audioDirectionAtAnswer = null;
    this.audioSender = { track: null, async replaceTrack(t) { this.track = t; } };
    this.transceivers = [
      { receiver: { track: { kind: 'video' } }, direction: 'recvonly', sender: {} },
      { receiver: { track: { kind: 'audio' } }, direction: 'recvonly', sender: this.audioSender },
    ];
    this.onicecandidate = null;
    this.ontrack = null;
    this.onconnectionstatechange = null;
  }
  async setRemoteDescription(desc) { this.remoteDescription = desc; }
  getTransceivers() { return this.transceivers; }
  async createAnswer() {
    this.audioDirectionAtAnswer = this.transceivers[1].direction;
    return { type: 'answer', sdp: 'ANSWER' };
  }
  async setLocalDescription(desc) { this.localDescription = desc; }
  async addIceCandidate(c) {
    if (!this.remoteDescription) throw new Error('remote description not set');
    this.addedCandidates.push(c);
  }
  close() { this.closed = true; this.connectionState = 'closed'; }
  // helpers for tests
  emitCandidate(json) { this.onicecandidate?.({ candidate: json ? { toJSON: () => json } : null }); }
  setState(state) { this.connectionState = state; this.onconnectionstatechange?.(); }
}
