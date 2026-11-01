// Remembers whether the owner wants room sound, so a muted peek stays muted next time.
// Storage can be missing or throw (private mode), which falls back to sound on.

const KEY = 'jerrycam.sound';

export function loadSoundOn(storage) {
  try {
    return storage.getItem(KEY) !== 'off';
  } catch {
    return true;
  }
}

export function saveSoundOn(storage, on) {
  try {
    storage.setItem(KEY, on ? 'on' : 'off');
  } catch {
    // Not remembered; the choice still applies to this visit.
  }
}
