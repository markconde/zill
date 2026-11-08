# Developing Zill

How Zill works inside, how to test it and how to publish it. What Zill does for the people who use
it is in the [README](../README.md).

## Terms used here

Most of these come from Sill's own source code and developer docs, since Zill speaks Sill's
protocol.

- **Kind**: the number at the start of every message between Sill for Mac and a device, which says
  what the message is. For example, kind 14 is the Mac's cursor shape and kind 32 the clipboard.
  Sill lists them all in
  [`StreamMessage.swift`](https://github.com/Saffsanity/sill/blob/main/Sources/StreamProtocol/StreamMessage.swift).
- **Door**: one way into the Mac: the pairing door for a new device, and the door a paired device
  connects through, at home or by Remote Access.
- **Bonjour, `_sill._tcp`, TXT record**: how a Mac announces itself on the local network. The TXT
  record carries a few small values with the announcement, such as the door type and the
  recognition tag.
- **Recognition tag**: a value in the Mac's announcement that lets a paired device recognize its
  Mac before connecting.
- **Damm digit**: the last of the 12 pairing digits, a check digit that catches a mistyped code.
- **PBKDF2**: the slow, deliberate way the pairing code is turned into a key, so the code can't be
  guessed quickly.
- **Pin, SPKI**: a fingerprint of the Mac's public key (its SPKI), saved when you pair. Every later
  connection must show the same key.
- **TLS 1.3, ALPN, SNI**: the encryption, and two labels a connection sends at the start to say
  which protocol it speaks.
- **HEVC, keyframe, NAL, SPS**: the video format; a keyframe is a whole picture the following
  frames build on; NAL units and the SPS are pieces of an HEVC stream.
- **AAC-ELD**: the low-delay audio format Sill sends.
- **MediaCodec, AudioTrack**: Android's video and audio decoding and playback.
- **Hello, goodbye**: the first and last message of a session.
- **Viewport**: the size of this device's picture area, sent so the Mac can fit the stream to it.
- **dp**: Android's density-independent pixels, what Sill calls points.
- **Stand-in Mac**: a test server in this repo that behaves like Sill for Mac.

## How each part works

- **Finding your Mac**: Bonjour (`_sill._tcp`) on the local network, with the TXT record's door
  type (`p`) and recognition tag (`r`), so a paired Mac is recognized by its tag.
- **Pairing**: tap an unpaired Mac and it shows its code (kind 19 `ask` → `shown`). Type the 12
  digits, which are checked with the Damm digit, or scan the QR code, or paste the `sill://pair`
  link the QR code carries. The code path uses PBKDF2-HMAC-SHA256 with 600,000 rounds; the QR
  path's key is the link's 16-byte secret. Both proofs are checked, and the Mac's key is pinned
  from then on.
- **Scanning**: CameraX frames go to ZXing (`scan/QrDecode.kt`), which reads the brightness plane
  only. No Google Play services. Only a link `PairLink.parse` accepts ends the scan; other QR
  codes show a hint. The camera permission is asked the first time Scan QR Code is tapped, and
  devices without a camera don't show the button.
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
  - Soft keyboard, plus a key row with esc, tab, arrows and latching ⌘ ⌥ ⌃ ⇧ for shortcuts. Any
    language and input method types (accents, Pinyin, kana, Hangul, emoji, dictation), as Sill's
    TextComposition: the keyboard edits a copy of what it typed since the Mac's caret last moved,
    text being composed stays on the device until chosen, and each change goes as backspaces and
    text (TextMirror). A click, a key from the row or a hardware keyboard, or a Return starts it again.
- **The Mac's pointer**: drawn in the Mac's cursor shape (kind 14) while the Mac's own mouse, or
  another device, moved it last (kind 26, stale reports dropped by `seen`), as in Sill's own app.
  This device's touches and keys hide it. A connected mouse shows the Mac's cursor shape as its
  pointer.
- **Sound**: the Mac's audio (kind 43, AAC-ELD 48 kHz stereo) through MediaCodec into a
  low-latency AudioTrack, kept within 150 ms of the picture (later sound is dropped, a stalled
  track restarted). The hello lists `aac-eld`. Mute button in the bar; the volume keys set its
  volume. The Mac's Send Audio setting has to be on.
- **Trackpad mode** (the touch/mouse button in the bar, remembered): the picture works as a laptop
  trackpad, as Sill's portrait pad does. Drag to move the pointer (1.25 pictures for a stroke across
  it), tap to click where it is, two fingers to right-click and scroll there, touch and hold then move
  to drag. It carries on from the Mac's pointer when the Mac moved it last; zoomed, the view follows it.
- **Split layout** (draft; the split button in the bar, shown when the screen is upright, remembered):
  the Mac on the top half, down to the middle where the Fold's hinge is, and the bar, key row and
  controls on the bottom. Two large buttons there: Trackpad slides up a trackpad (with Click, Right
  click and Done; its handle puts it away), Keyboard opens the keyboard over that half, the Mac staying
  in view. Touching the picture itself stays direct. Made by hand rather than by the hinge, which on
  the Fold8 reads only 0, 90 and 180 degrees and springs to flat (see `../sensor-probe`).
- **Pinch to zoom**: 1× to 5× about the fingers, as Sill's StreamZoom. Two fingers on a zoomed
  picture move it; a two-finger drag on the whole picture still scrolls the Mac. Taps and scrolls
  land on what's under the finger at any zoom. Pinch all the way out for the whole picture.
- **The Mac's menus** (Sill 0.6.0+): "≡ ‹app›" at the start of the key row opens the streamed
  app's menu bar (kinds 24/25/27): menus fetched from the Mac as they open, submenus, shortcuts,
  ✓ marks and disabled items as the Mac shows them. Choosing an item presses it on the Mac, by id
  and title within the menus' version, so a menu that changed meanwhile is refused, not misclicked.
- **The Mac's settings** (kinds 16/17): the Settings tab of the windows sheet shows and changes
  Quality, Resolution, Frame rate limit, Prioritize encoding speed, Virtual Display, Send Audio and
  Direct Wireless (a paired device only), with Sill's own labels, what runs now, and the Mac's
  warnings (a link that can't keep up and what would fit, the software encoder, why no sound).
  A change shows at once; one the Mac doesn't take snaps back with a notice. They apply to every
  device.
- **Windows and apps**: the window list with thumbnails, picking a window or the Desktop, and
  Close, Minimize and Full Screen on long-press. Apps opens any installed app.
- **Clipboard** (Share Clipboard in Settings › This device, on by default): text copied on the Mac
  lands on Android. Paste sends Android's clipboard to the Mac and presses ⌘V. Off, the session
  stops the Mac's clipboard (kind 32 `{"stop":true}`) and never reads Android's.
- **Aa (window size)**: as in Sill's own app. Off, the default, leaves the Mac window alone.
  0.5× to 1.5× has the Mac resize the window it streams to this screen ÷ the scale, so a larger
  scale shows its text larger here. Windows only: Sill never resizes the Desktop.
- **Delay**: how far behind the Mac the picture arrives, in the status line ("45 fps · 38 ms delay")
  and in the stats the Mac logs. The Mac stamps frames with its NTP-kept clock; this device
  corrects its own clock (`ClockSync`), since a phone's clock is often tens of ms off: with
  Android's network time (API 33+) by default, or, with Ask time servers for the delay on in
  Settings › This device (off by default), against a public time server (SNTP). -1 (unknown) until
  measured.
- **Other**: the viewport (size in dp as Sill's points, the Aa scale, refresh rate), pings and stats,
  a locked Mac's shade, and goodbyes with the Mac's message.

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
- `QrDecodeTest`: a pairing link drawn as a level M QR code into a camera-like frame (with row
  padding) reads back, a blank frame reads nothing, and another QR code is not taken as a link.
- `LoopbackMacTest`: a stand-in Mac on loopback, written from the wire rules. It covers a TLS
  1.3 door with client certificates and ALPN, ask → shown → a wrong code refused → the right
  code paired, then a pinned session (hello first, source, viewport, window list, clipboard
  subscription, goodbye), and a wrong pin refused before any byte is sent.
- Away from home, the app dials the Mac's home address on its Remote Access port, as Sill's iPhone app does; typing that address in Add a Mac connects a Mac already paired without a code.
- `StandInServe` (skipped by default) runs that stand-in Mac on port 17455 and streams a looping
  HEVC test clip, a TextEdit-like menu bar, an AAC-ELD tone (`testclip.eld`, made by `tools/eld-clip.swift` with
  AudioToolbox, as Sill's AudioEncoder makes it), an arrow cursor and a pointer that circles the
  picture whenever the device leaves it alone for 2 s. Its key is kept in `build/standin-key.p12`,
  so it stays paired across runs:

  ```bash
  SILL_STANDIN_SECONDS=600 ./gradlew testDebugUnitTest --tests '*StandInServe*' --rerun-tasks
  ```

- `ZoomOnDeviceTest` (on a device or emulator, `./gradlew connectedDebugAndroidTest`, with the
  stand-in paired): real pinches and two-finger drags, with screenshots in the app's files folder.

  In the app, choose Add a Mac, enter `10.0.2.2:17455` and the code `4829 1355 7208`. Or pair by
  the QR path: the stand-in writes its link to `build/standin-link.txt` and its QR code to
  `build/standin-qr.png` (the address is `10.0.2.2`, or `SILL_STANDIN_ADDRESS`). Paste the link,
  or start the emulator with `-camera-back virtualscene -virtualscene-poster wall=<that png>` and
  scan it. The stand-in logs every message it receives to `build/standin.log`.

## Source and releases

Tag each published version (`v0.2.1`, …).

### F-Droid

Release builds are signed with the key in `~/.config/sill-android/` (`keystore.properties` and
`release.jks`, outside the project; `SILL_KEYSTORE_PROPERTIES` points elsewhere). Without it they
fall back to the debug key. Every version must use the same key, or Android refuses the update, so
keep a copy of both files in a password manager.

`./publish-fdroid.sh` publishes the release build to a self-hosted F-Droid repo over SSH. Where it
publishes is configuration, outside the repo: `~/.config/sill-android/fdroid.conf` (or the file
`FDROID_CONFIG` names), which sets `FDROID_HOST`, `FDROID_REPO_DIR`, `FDROID_UPDATE` and,
optionally, `FDROID_URL`; the top of the script says what each is. To publish, bump `versionCode`
(and `versionName`) in `app/build.gradle.kts`, then:

```bash
./publish-fdroid.sh
```
