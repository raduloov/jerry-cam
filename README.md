# Jerry Cam

A live dog camera made from a spare Android phone. Open a web page on your phone, wherever you are, to see and hear
your dog and talk back. Built for a dog named Jerry.

- Live video and room sound, plus **hold to talk** through the camera phone's speaker. Room sound mutes while you
  talk, like a walkie-talkie.
- A flashlight toggle, the camera phone's battery level, and a sound on/off button.
- The camera and microphone are on **only while someone is watching**. Nothing is recorded.
- Only the Google accounts you list can watch.
- Runs on Firebase's free Spark plan, no card needed. Video goes straight between the two devices and never passes
  through Firebase.

## How it works

```
  Android phone at home                            Your phone, anywhere
 ┌──────────────────────┐                        ┌──────────────────────┐
 │  Jerry Cam app       │ ◄── WebRTC: video, ──► │  web page            │
 │  (Kotlin)            │     sound, talk-back   │  (Firebase Hosting)  │
 └──────────┬───────────┘                        └───────────┬──────────┘
            │                                                │
            └──────► Firebase: Google sign-in and ◄──────────┘
                     Realtime Database (connection setup)
```

- **Camera phone** (`android/`): a native Kotlin app. A foreground service stays signed in and watches the database.
  It turns the camera on only when a viewer starts a session.
- **Web page** (`web/public/`): plain ES modules with no build step, served by Firebase Hosting. You sign in with
  Google, then it makes a WebRTC call to the camera phone.
- **Firebase**: Google sign-in, Hosting, and a Realtime Database the two sides use to swap the WebRTC offer,
  answer and network candidates. Only the accounts in `ALLOWED_EMAILS` can read or write it.
- **Network**: a direct peer-to-peer connection, found with Cloudflare's free STUN. An optional Cloudflare TURN relay
  covers networks where a direct connection fails.

Closing the page, switching apps or locking the phone ends the session, and the camera turns off within seconds.
Only one viewer can watch at a time. If you open the page on another device, that device takes over.

## What you need

- A spare Android phone on Android 10 or newer. It stays plugged in and on Wi-Fi.
- A phone or computer to watch from. The page is made for iPhone Safari and works in other modern browsers.
- A Google account for a free Firebase project.
- To build it you need:
  - [Node.js](https://nodejs.org) 22 or newer.
  - [Android Studio](https://developer.android.com/studio), or the Android SDK with `adb`.
  - JDK 17 or newer for the Android build, and JDK 21 for the Firebase emulator in the rules tests. The JDK bundled
    with Android Studio (currently 21) works for both.

## Setup

Replace `<project-id>`, `<web-app-id>`, `<android-app-id>` and `<sha1>` with your own values.

### 1. Clone and install

```sh
git clone https://github.com/raduloov/jerry-cam.git
cd jerry-cam
npm install
npx firebase login
```

### 2. Create a Firebase project

```sh
npx firebase projects:create <project-id> --display-name "Jerry Cam"
npx firebase use --add        # pick the project, alias "default"; this writes .firebaserc
```

Then in the [Firebase console](https://console.firebase.google.com):

1. Under **Authentication → Sign-in method**, enable **Google**.
2. Under **Realtime Database**, click **Create database**. Any region works; start in locked mode.

Do both before step 5. The Android config file only includes the sign-in client and the database address once these
exist.

### 3. Configure the web page

```sh
npx firebase apps:create web "Jerry Cam viewer"
npx firebase apps:sdkconfig web <web-app-id>
cp web/public/js/firebase-config.example.js web/public/js/firebase-config.js
```

Fill `firebase-config.js` with the printed values, but set `authDomain` to `<project-id>.web.app` instead of
`firebaseapp.com`. Google sign-in only works reliably in iPhone Safari and from the Home Screen when it runs on the
page's own domain.

That change needs one redirect address in the
[Google Cloud console](https://console.cloud.google.com/apis/credentials) for the project:

1. Open **Credentials → Web client (auto created by Google Service)**.
2. Under **Authorized redirect URIs**, add `https://<project-id>.web.app/__/auth/handler`.

### 4. Choose who can watch, and deploy

List the allowed Google accounts in `.env.local` at the repo root. The camera phone signs in with one of them too.

```sh
ALLOWED_EMAILS=you@gmail.com,partner@gmail.com
```

```sh
npm run deploy     # builds database.rules.json from the template, then deploys the page and the rules
```

The page is now live at `https://<project-id>.web.app`. To change who's allowed, edit `ALLOWED_EMAILS` and run
`npm run deploy` again.

### 5. Register the Android app

Create a signing key. Keep it safe: updates only install over the old app when they're signed with the same key.

```sh
keytool -genkeypair -v -keystore android/jerrycam-release.jks -alias jerrycam -keyalg RSA -keysize 2048 -validity 10000
```

Create `android/keystore.properties` with your passwords:

```properties
storeFile=../jerrycam-release.jks
storePassword=…
keyAlias=jerrycam
keyPassword=…
```

Google sign-in on Android checks the app's signing fingerprint, so register the app with it, then download the
config:

```sh
keytool -list -v -keystore android/jerrycam-release.jks -alias jerrycam | grep SHA1
npx firebase apps:create android "Jerry Cam camera" --package-name com.yavorradulov.jerrycam
npx firebase apps:android:sha:create <android-app-id> <sha1>
npx firebase apps:sdkconfig android <android-app-id> > android/app/google-services.json
```

Check that `google-services.json` has an `oauth_client` entry with `"client_type": 3`. If it's missing, Google
sign-in wasn't enabled yet; enable it and download the file again. To use your own package name, change
`applicationId` in `android/app/build.gradle.kts` and register that name instead.

### 6. Build and install

Opening `android/` in Android Studio creates `android/local.properties` with the SDK path. You can also write it
yourself as `sdk.dir=/path/to/Android/sdk`.

```sh
cd android
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

You can also copy the APK to the phone and open it there, after allowing installs from unknown apps.

### 7. Set up the camera phone

1. Open Jerry Cam and sign in with one of the allowed Google accounts.
2. Anything missing shows as an orange banner at the bottom: camera, microphone, notifications, or battery
   restriction. Tap the banner to fix it.
3. Optionally, turn on **Restart after reboot** in the ⋯ menu.
   - It needs the "display over other apps" permission, because Android only lets an app restart a camera service
     after a reboot that way.
   - Without it, the phone shows a notification after a reboot, and someone has to tap it.
4. Leave the phone plugged in and on Wi-Fi, with the volume up, pointed at your dog's spot.

The screen shows a green paw and **Ready**. While someone watches, it turns red and shows **Live** with that person's
first name.

### 8. Watch

Open `https://<project-id>.web.app`, sign in, and tap **Watch Jerry**. On an iPhone, tap Share → **Add to Home
Screen** to open it like an app.

If an allowed account sees "Access blocked" when signing in, open
[Google Auth Platform → Audience](https://console.cloud.google.com/auth/audience). Either publish the app, or add the
account as a test user.

## Optional: a relay for strict networks

Some mobile and office networks block direct connections. The label on the live video shows `Live · direct` or
`Live · relay`. If the page gets stuck on "Connecting…" on some networks, add a
[Cloudflare Realtime TURN](https://developers.cloudflare.com/realtime/turn/) key to `android/local.properties`. The
first 1,000 GB a month are free, though Cloudflare may ask for a card.

```properties
turn.keyId=…
turn.apiToken=…
```

Then rebuild and reinstall the APK.
- For each session, the camera phone fetches short-lived relay credentials and passes them to the viewer through the
  database. The API token never reaches the web page.
- The token is built into the APK, so don't share an APK built with it.

## Privacy and security

- **No recording.** Video and sound go directly between the two devices, or through the TURN relay, which doesn't
  store them.
- **The database holds very little**: whether the camera phone is online, its battery level, and the current
  session's connection setup, including the viewer's first name. The session is cleared when it ends.
- **The rules allow only your accounts.** The database rules deny everything except `/camera` and `/session`. Those
  are open only to signed-in accounts in `ALLOWED_EMAILS` with a verified email. `tests/rules/` checks this against the
  Firebase emulator.
- **Nothing at home accepts incoming connections.** Both devices connect out.
- **The web config isn't a secret.** The values in `firebase-config.js` are public by design; the rules are what
  protect the camera.
- **Your own setup stays out of git.** These files are gitignored:
  - `.firebaserc`, `.env.local` and `database.rules.json`
  - `web/public/js/firebase-config.js`
  - `android/app/google-services.json`, `android/local.properties`, `android/keystore.properties` and
    `android/*.jks`

## Good to know

- Some phone makers' battery savers stop background apps. Allow Jerry Cam to run unrestricted when the banner asks.
  If it still gets stopped, check the phone's own battery settings.
- The camera phone checks in every minute, and reconnects by itself if the database stops answering. The page shows it
  as offline after 2.5 minutes without a check-in.
- The app is installed directly from the APK; it isn't on the Play Store.
- To rename it for your own dog, change "Jerry" in `web/public/index.html`, `web/public/js/main.js`,
  `web/public/manifest.webmanifest` and `android/app/src/main/res/values/strings.xml`.

## Development

```sh
npm run test:web                                  # web page logic
npm run test:rules                                # database rules, on the Firebase emulator (JDK 21)
cd android && ./gradlew :app:testDebugUnitTest    # camera phone logic
```

On macOS, you can run the rules tests with Android Studio's JDK:
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" npm run test:rules`.

| Path | What |
| --- | --- |
| `android/` | the Kotlin camera app |
| `web/public/` | the web page; no build step |
| `web/test/` | web page tests (`node --test`) |
| `database.rules.template.json` | database rules; `scripts/render-rules.mjs` fills in `ALLOWED_EMAILS` |
| `tests/rules/` | database rules tests |
