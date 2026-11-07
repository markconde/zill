# Zill for Android

**Android client for Sill for Mac: use your Mac from your phone or foldable.**

Zill is an Android client for [Sill for Mac](https://getsill.app). It needs Sill for Mac running on
your Mac: Zill connects to it and shows a window from your Mac, or its whole desktop, on your Android
device.

Use the window with touch, the on-screen trackpad or a keyboard. These Sill features work in Zill:
Menus, Send Audio, Share Clipboard and Aa (text size).

On your home network, Zill finds your Mac by itself. Away from home, it connects through Remote
Access: turn Remote Access on in Sill for Mac, then add the Mac in Zill with its Remote Access
address. Your device has to be able to reach your Mac's network.

Requirements: Android 10 or later, and Sill for Mac 0.6.0 or later.

Tested on the Samsung Galaxy Z Fold8. Support for more Android devices is planned.

Not yet supported: connecting over a USB cable, Direct Wireless Connection, and scanning the pairing
QR code. To pair, type the code Sill for Mac shows, or paste its pairing link.

Zill uses the same protocol as Sill for iPhone and iPad (`Sources/StreamProtocol` in the
[Sill repository](https://github.com/Saffsanity/sill)).

This app is written with the help of Claude (Anthropic). Commits written with Claude carry a
Co-Authored-By: Claude line.

This project is an unofficial client and is not affiliated with or endorsed by the Sill project.

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

## Not yet

The portrait trackpad, QR scanning
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

  In the app, choose Add a Mac, enter `10.0.2.2:17455` and the code `4829 1355 7208`. The
  stand-in logs every message it receives to `build/standin.log`.

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

## Settings on this device

These settings are in Zill's Settings panel, under This device. They apply to this Android device
only and are never sent to the Mac.

| Setting | Default | What it does |
| --- | --- | --- |
| Share Clipboard | On | Text you copy on your Mac is ready to paste on this device, and Paste in the bar sends this device's text to the Mac. Off, nothing you copy goes between the two, and Zill doesn't read this device's clipboard. |
| Ask time servers for the delay | Off | The delay in the status line ("38 ms delay") compares this device's clock with the Mac's. Off, Zill uses Android's own network time (Android 13 and later) and contacts nothing but your Mac. On older Android the delay shows as unknown. On, Zill also asks time.google.com, time.cloudflare.com or pool.ntp.org for a more exact delay. |

## License

Zill is licensed under the Apache License 2.0 ([LICENSE](LICENSE)). Parts of it are derived from
[Sill](https://github.com/Saffsanity/sill), which is also licensed under the Apache License 2.0.
