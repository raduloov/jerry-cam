// Firebase init and the db adapter that ViewerSession expects (see signaling.js).
import { initializeApp } from 'https://www.gstatic.com/firebasejs/13.1.0/firebase-app.js';
import {
  getAuth, GoogleAuthProvider, signInWithRedirect, getRedirectResult, onAuthStateChanged, signOut as authSignOut,
} from 'https://www.gstatic.com/firebasejs/13.1.0/firebase-auth.js';
import {
  getDatabase, ref, set, update, push, runTransaction, onValue, onChildAdded, onDisconnect, serverTimestamp,
} from 'https://www.gstatic.com/firebasejs/13.1.0/firebase-database.js';
import { firebaseConfig } from './firebase-config.js';

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const database = getDatabase(app);
const at = (path) => ref(database, path);

export const redirectResult = getRedirectResult(auth);
export const signIn = () => signInWithRedirect(auth, new GoogleAuthProvider());
export const signOut = () => authSignOut(auth);
export const onUser = (cb) => onAuthStateChanged(auth, cb);

export const db = {
  set: (path, value) => set(at(path), value),
  update: (path, values) => update(at(path), values),
  push: (path, value) => push(at(path), value).then(() => {}),
  // RTDB first runs the handler on the local cache, which may be null before the server value arrives.
  // Returning null there makes the server reject the stale guess and re-run with the real value.
  removeIf: (path, predicate) =>
    runTransaction(at(path), (current) => (current === null || predicate(current) ? null : undefined)).then(() => {}),
  onValue: (path, cb, onError) => onValue(at(path), (snapshot) => cb(snapshot.val()), onError),
  onChildAdded: (path, cb) => onChildAdded(at(path), (snapshot) => cb(snapshot.val())),
  onDisconnectRemove: (path) => onDisconnect(at(path)).remove(),
  cancelOnDisconnect: (path) => onDisconnect(at(path)).cancel(),
  serverTimestamp,
};
