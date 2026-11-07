# Sill Client for Android

An unofficial Android client for [Sill](https://github.com/Saffsanity/sill): it shows a window
from your Mac, or its whole desktop, on an Android phone or tablet, and sends your touches and
keys back. It talks to the Sill for Mac app you already run, speaking Sill's own wire protocol
(`Sources/StreamProtocol` in the Sill repo). Nothing changes on the Mac.

Not affiliated with or endorsed by the Sill project.

## What works

- **Finding your Mac**: Bonjour (`_sill._tcp`) on the local network, with the TXT record's door
  type (`p`) and recognition tag (`r`), so a paired Mac is recognized by its tag.
- **Pairing**: tap an unpaired Mac and it shows its code (kind 19 `ask` → `shown`). Type the 12
  digits, which are checked with the Damm digit, or paste the `sill://pair` link. The code path
  uses PBKDF2-HMAC-SHA256 with 600,000 rounds, and both proofs are checked. The Mac's key is
  pinned from then on.
- **Security**: TLS 1.3 only. This device has a P-256 identity with a self-signed certificate
  built the way Sill's `SelfSignedCertificate` builds it. The key is wrapped by an Android
  Keystore AES key. The pin (SHA-256 of the Mac's SPKI) is checked inside the handshake, so a
  different Mac key fails before any byte is sent. ALPN `sill/1` and `sill-pair/1`, SNI `sill`.
- **Away from home**: Add a Mac with its Remote Access address, or
  connect to a paired Mac that told this device its addresses in its signed kind 18. The
  signature is verified and the key must match the pin.
- **The picture**: HEVC (kinds 0 and 1) through MediaCodec with low-latency mode, letterboxed to
  the Mac's aspect ratio. When the picture is interrupted it asks for a keyframe (kind 44).
- **Input**:
  - Tap to click, two-finger tap to right-click.
  - One- or two-finger drag scrolls, with phases and a coast after a flick.
  - Long-press and drag to click-and-drag.
  - Three-finger swipe for Mission Control, App Exposé and Spaces.
  - A connected mouse, trackpad or hardware keyboard works as itself.
  - Soft keyboard, plus a key row with esc, tab, arrows and latching ⌘ ⌥ ⌃ ⇧ for shortcuts.
- **The Mac's pointer**: drawn in the Mac's cursor shape (kind 14) while the Mac's own mouse, or
  another device, moved it last (kind 26, stale reports dropped by `seen`), as in Sill's own app.
  This device's touches and keys hide it. A connected mouse shows the Mac's cursor shape as its
  pointer.
- **Sound**: the Mac's audio (kind 43, AAC-ELD 48 kHz stereo) through MediaCodec into a
  low-latency AudioTrack, kept within 150 ms of the picture (later sound is dropped, a stalled
  track restarted). The hello lists `aac-eld`. Mute button in the bar; the volume keys set its
  volume. The Mac's Send Audio setting has to be on.
- **Pinch to zoom**: 1× to 5× about the fingers, as Sill's StreamZoom. Two fingers on a zoomed
  picture move it; a two-finger drag on the whole picture still scrolls the Mac. Taps and scrolls
  land on what's under the finger at any zoom. Pinch all the way out for the whole picture.
- **Windows and apps**: the window list with thumbnails, picking a window or the Desktop, and
  Close, Minimize and Full Screen on long-press. Apps opens any installed app.
- **Clipboard**: text copied on the Mac lands on Android. Paste sends Android's clipboard to
  the Mac and presses ⌘V.
- **Aa (window size)**: as in Sill's own app. Off, the default, leaves the Mac window alone.
  0.5× to 1.5× has the Mac resize the window it streams to this screen ÷ the scale, so a larger
  scale shows its text larger here. Windows only: Sill never resizes the Desktop.
- **Other**: the viewport (size in dp as Sill's points, the Aa scale, refresh rate), pings and stats,
  a locked Mac's shade, and goodbyes with the Mac's message.

## Not yet

The Mac's menus (24/25/27), the host settings panel (16/17), the portrait trackpad, QR scanning
(paste the link instead), composed text (accents, non-US layouts), the USB-cable path, and Direct
Wireless.

## Build

Needs JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Android 10 (API 29) or later.

## Tests

```bash
./gradlew testDebugUnitTest
```

- `ProtocolVectorsTest`: the pairing vectors published in Sill's `docs/remote-access-plan.md`
  (Mac ID, code key, both proofs for the code and QR paths, recognition tag), the Damm check
  digit, pairing links, NAL conversion, and the SPS size of a test clip.
- `LoopbackMacTest`: a stand-in Mac on loopback, written from the wire rules. It covers a TLS
  1.3 door with client certificates and ALPN, ask → shown → a wrong code refused → the right
  code paired, then a pinned session (hello first, source, viewport, window list, clipboard
  subscription, goodbye), and a wrong pin refused before any byte is sent.
- `StandInServe` (skipped by default) runs that stand-in Mac on port 17455 and streams a looping
  HEVC test clip, an AAC-ELD tone (`testclip.eld`, made by `tools/eld-clip.swift` with
  AudioToolbox, as Sill's AudioEncoder makes it), an arrow cursor and a pointer that circles the
  picture whenever the device leaves it alone for 2 s. Its key is kept in `build/standin-key.p12`,
  so it stays paired across runs:

  ```bash
  SILL_STANDIN_SECONDS=600 ./gradlew testDebugUnitTest --tests '*StandInServe*' --rerun-tasks
  ```

- `ZoomOnDeviceTest` (on a device or emulator, `./gradlew connectedDebugAndroidTest`, with the
  stand-in paired): real pinches and two-finger drags, with screenshots in the app's files folder.

  In the app, choose Add a Mac, enter `10.0.2.2:17455` and the code `4829 1355 7208`. The
  stand-in logs every message it receives to `build/standin.log`.

## Source and releases

Tag each published version (`v0.2.1`, …).

### F-Droid

Release builds are signed with the key in `~/.config/sill-android/` (`keystore.properties` and
`release.jks`, outside the project; `SILL_KEYSTORE_PROPERTIES` points elsewhere). Without it they
fall back to the debug key. Every version must use the same key, or Android refuses the update, so
keep a copy of both files in a password manager.

Phones install and update the app from the F-Droid repo,
`https://fdroid.example.com/fdroid/repo` (on the server, `/path/to/fdroid`). To publish, bump
`versionCode` (and `versionName`) in `app/build.gradle.kts`, then:

```bash
./publish-fdroid.sh
```
