// Hold-to-talk: the mic is requested on the first press and reused after that.
// While talking, room sound is muted so the owner doesn't hear their own echo.

export function createTalkController({ requestMic, attachMic, setRoomMuted, onState = () => {} }) {
  let track = null;
  let pending = null;
  let pressed = false;
  let blocked = false;
  let disposed = false;

  const setState = (state) => onState(state);

  async function acquire() {
    pending ??= requestMic().then(async (t) => {
      t.enabled = false;
      await attachMic(t);
      return t;
    });
    return pending;
  }

  async function press() {
    if (blocked || disposed) return;
    pressed = true;
    if (!track) {
      setState('requesting');
      try {
        track = await acquire();
      } catch {
        blocked = true;
        setRoomMuted(false);
        setState('blocked');
        return;
      }
      if (disposed) return void track.stop();
    }
    if (!pressed) return void setState('idle');
    track.enabled = true;
    setRoomMuted(true);
    setState('talking');
  }

  function release() {
    pressed = false;
    if (track) track.enabled = false;
    setRoomMuted(false);
    if (track && !blocked) setState('idle');
  }

  function dispose() {
    disposed = true;
    track?.stop();
  }

  return { press, release, dispose };
}
