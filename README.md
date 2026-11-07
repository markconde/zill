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

## Getting started

### Install

1. Install Sill for Mac from [getsill.app](https://getsill.app) and open it. It asks for Screen
   Recording and Accessibility, and on macOS 15 or later for Local Network.
2. On your Android device, download the latest `zill-<version>.apk` from
   [Releases](https://github.com/markconde/zill/releases) and open it. The first time, Android asks
   you to allow installing apps from your browser or file manager.

### Pair and connect

1. Open Zill on the same network as your Mac. Your Mac shows up in the list.
2. Tap your Mac. Sill for Mac shows a 12-digit code. Type it in Zill.
3. Zill shows your Mac. You pair once: next time, tap your Mac and it connects.

Away from home, turn on Remote Access in Sill for Mac, then tap Add a Mac in Zill and type the
Mac's Remote Access address. Zill uses Sill's Remote Access port, 7455, unless you add another. For
example:

- **Over WireGuard into your home network**: the Mac's address at home, such as `192.168.1.20`.
- **With a port forwarded on your router**: your home's public address or name with that port, such
  as `home.example.com:7455`.

A Mac you already paired connects without a code.

### Using Zill

- **The bar** at the top: Disconnect, Mute, the on-screen trackpad, Windows and apps, Paste,
  Keyboard and, with the screen upright, Split layout. Hide controls makes room for the picture.
- **The key row** under the bar: Menus for the app you use, then esc, tab, the arrows and ⌘ ⌥ ⌃ ⇧.
- **Windows and apps** opens a sheet with three tabs: Windows to pick a window or the Desktop, Apps
  to open any app on the Mac, and Settings for Sill for Mac's settings and this device's.

## Features

- **Finding your Mac**: Macs running Sill for Mac on your network show up in Zill by themselves.
- **Pairing**: tap a Mac and type the 12-digit code Sill for Mac shows, or paste its pairing link.
  You pair once. After that, Zill connects without a code.
- **Security**: the connection is encrypted, and Zill checks that it is talking to the Mac you
  paired with before it sends anything.
- **Away from home**: add a Mac with its Remote Access address.
- **The picture**: a window from your Mac, or the whole desktop. Pinch to zoom in up to 5×.
- **Touch**: tap to click, two-finger tap to right-click, drag to scroll, and touch and hold, then
  move, to drag. Three-finger swipes open Mission Control, App Exposé and Spaces.
- **The on-screen trackpad**: the trackpad button in the bar makes the picture work like a laptop
  trackpad, with a pointer you move.
- **Split layout**: on a Fold held upright, the Mac on the top half and the controls on the bottom
  half.
- **Keyboard**: the Android keyboard types on the Mac in any language, with emoji and dictation. A
  row of keys adds esc, tab, the arrows and ⌘ ⌥ ⌃ ⇧ for shortcuts. A connected keyboard, mouse or
  trackpad works too.
- **Menus**: open the menu bar of the app you use on the Mac and choose any item.
- **Windows and apps**: switch between the Mac's windows or the Desktop, close, minimize or
  full-screen a window, and open any app on the Mac.
- **Send Audio**: hear your Mac's sound with the picture. Mute it in the bar. The volume keys set its
  volume.
- **Share Clipboard**: text copied on the Mac is ready to paste on Android, and Paste sends
  Android's text to the Mac.
- **Aa (text size)**: the Mac resizes the window you stream, so its text is larger or smaller here.
- **Sill for Mac's settings**: change Quality, Resolution, Frame rate limit and the Mac's other
  streaming settings from Zill.
- **Delay**: the bar shows how far behind the Mac the picture is.

## Settings on this device

These settings are in Zill's Settings panel, under This device. They apply to this Android device
only and are never sent to the Mac.

| Setting | Default | What it does |
| --- | --- | --- |
| Share Clipboard | On | Text you copy on your Mac is ready to paste on this device, and Paste in the bar sends this device's text to the Mac. Off, nothing you copy goes between the two, and Zill doesn't read this device's clipboard. |
| Ask time servers for the delay | Off | The delay in the status line ("38 ms delay") compares this device's clock with the Mac's. Off, Zill uses Android's own network time (Android 13 and later) and contacts nothing but your Mac. On older Android the delay shows as unknown. On, Zill also asks time.google.com, time.cloudflare.com or pool.ntp.org for a more exact delay. |

## Build

Needs JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleDebug
```

How each part works, the terms it uses, the tests, the stand-in Mac and publishing are in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## License

Zill is licensed under the Apache License 2.0 ([LICENSE](LICENSE)). Parts of it are derived from
[Sill](https://github.com/Saffsanity/sill), which is also licensed under the Apache License 2.0.
