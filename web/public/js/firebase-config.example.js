// Copy to firebase-config.js and fill in the web app config of your Firebase project:
//   npx firebase apps:sdkconfig web <appId>
// Keep authDomain on the Hosting domain so Google sign-in stays same-site in Safari and home-screen mode.
export const firebaseConfig = {
  apiKey: '<apiKey>',
  authDomain: '<projectId>.web.app',
  databaseURL: 'https://<projectId>-default-rtdb.<region>.firebasedatabase.app',
  projectId: '<projectId>',
  storageBucket: '<projectId>.firebasestorage.app',
  messagingSenderId: '<messagingSenderId>',
  appId: '<appId>',
};
