# WP2 App Installer

An Android app that uploads `.wp2app` packages onto the CMF Watch Pro 2 from the phone, without
breaking the Nothing X pairing. Pair the watch once in Nothing X; this app attaches as a second
GATT client to the already bonded device, exactly like the Home Assistant companion.

## How the upload works

1. Connect with `connectGatt` to the existing bond — no scan, no new pairing, no Paired popup.
2. Read channel key K over the shell service (`AT GETSECRET`) and subscribe to the command
   notifications, so every frame on the wire is encrypted with K instead of the stock bind session.
3. Speak the same install channel the PC `wp2apps install` uses:

   | opcode | request | reply |
   |---|---|---|
   | `0x90A0` INFO | slot | status + 60-byte header (empty slot: status only) |
   | `0x90A1` BEGIN | slot, size, CRC32 | status |
   | `0x90A2` DATA | offset + up to 128 bytes | status + bytes-written-so-far |
   | `0x90A3` END | one byte | status (commits the file and runs the header/CRC checks) |

   Every chunk is acknowledged before the next one is sent; after END the app reads the slot
   back and verifies the installed name. Slot choice mirrors the PC default: a **loader package
   (`loader.wp2app`) always goes to slot L (0xFF)**; an app upgrades the same package name in
   place, otherwise the first free slot of 16 (or pick Slot 0-15 yourself).

The stub accepts these commands under K — its receive path tries the K key for every request and
answers the same way, so the stock bind handshake the PC tool does is never needed here.

## Safety

- **Apps and the App Loader.** Only `.wp2app` files are accepted — raw firmware/OTA images are
  still refused. The header kind decides the destination: apps never land in slot L, the loader
  always does, and the watch re-checks both rules at BEGIN.
- The stub ABI is read before uploading, so a package newer than the watch's App Loader fails
  early with a plain message instead of at commit time.
- A slot that holds the app currently open on the watch answers Busy; the message tells you to
  open something else first.
- K lives in memory only, is zeroed when the link closes, and nothing about it is logged.
- If the HA companion is also connected, both sides filter replies by opcode; if an upload ever
  stalls, close the companion and retry.

## Requirements

- CMF Watch Pro 2 with the FMC patched firmware: the stub with channel key K (2026-11-13 or
  newer; stub 8+ works). Stock firmware has no channel and will not answer. The App Loader can
  be installed or updated with this app over the same link — no separate pairing step.
- The watch paired once in Nothing X, awake while installing.
- Android 8+ with Bluetooth LE and the Bluetooth permission (requested on first launch).

## Build

JDK 17+ and the Android SDK (platform 35), then from this folder:

```bash
gradlew.bat testDebugUnitTest assembleDebug
```

Grab the APK from the [Releases page](https://github.com/krtkygpta/wp2app-installer/releases/latest),
or build it yourself and tap `app/build/outputs/apk/debug/app-debug.apk` in the file manager
(no adb needed).

## Usage

1. Choose the watch (the list puts watches first; the choice is remembered).
2. Choose a `.wp2app` file — an app or the App Loader (`loader.wp2app`); the app shows its
   name, version and size, or rejects a corrupt file.
3. Keep **Auto** for the slot unless you want a specific one (the slot picker applies to apps;
   the loader always installs to slot L), then **Install to watch**.
4. Watch the upload percentage; the final line confirms the slot the watch reports back.

## Where the protocol comes from

Ported from the PC tooling in this workspace: `.sdk/wp2-apps/src/wp2apps/install.py` (requests,
slot rules, chunking, CRC) and `.sdk/wp2-apps/fw/wp2-stub/src/ble.rs` (the watch side). The GATT
and crypto layer is the same pattern as `projects/custom-apps/ha-remote/companion/android/`,
rewritten here under `dev.cmf.wp2installer`.

## License

MIT, like the rest of this workspace.
