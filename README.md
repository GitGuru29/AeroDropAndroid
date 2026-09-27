# AeroDrop for Android

Peer-to-peer file transfer between an Android device and a Mac over the local
network. No cloud, no account, no internet connection required — the two devices
find each other over Bonjour and talk directly over TLS 1.3.

This repository is the **Android app only**. The macOS counterpart lives in
[AeroDropMac](https://github.com/GitGuru29/AeroDropMac); this side is useless
without it, and the two are pinned to the same wire format.

> **Read this before testing.** Phone → Mac works. **Mac → phone is currently
> broken** — see [Known bugs](#known-bugs). The bug is server-side and
> Android-specific, so it reproduces on any device.

## Requirements

| | |
|---|---|
| Android | 10 (API 29) or later — `MediaStore.Downloads` requires API 29 |
| Toolchain | JDK 17, Android SDK 35, Gradle 8.14 (wrapper included) |
| Language | Kotlin 2.2.20, Jetpack Compose (Material 3) |
| Network | Both devices on the same LAN, same subnet, same Wi-Fi or Ethernet |
| Peer | The macOS app from AeroDropMac, running |

The app is a single screen plus an always-on receiver. There is nothing to
configure: open it, and it starts advertising immediately.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`assembleRelease` produces an **unsigned** APK — there is no signing config in
the repo, so a release build has to be signed before it will install. Use the
debug build for testing, or add a `signingConfig` reading a keystore that is
deliberately not committed.

The wrapper pins Gradle 8.14 and AGP 8.13.2. There are no third-party
dependencies beyond the AndroidX and Compose BOMs; the transport is plain
`javax.net.ssl` and `java.net`, not a networking library.

## Using it

1. Open AeroDrop on both devices. Android advertises `_aerodrop._tcp` on port
   **7770** as soon as the app starts.
2. Your Mac appears in the **Devices** list and is selected for you.
3. Pick files with the button, drop several at once with **Multiple**, or share
   into AeroDrop from any other app's share sheet.

Transfers are **serialized through a queue** — send twenty files and they go one
at a time, each with its own progress and throughput. Received files land in
`Downloads/AeroDrop/`, and the transfer stays listed until the receiver has
written every declared byte.

The receiver runs as a foreground service, so the Mac can push a file at any
time whether or not a transfer is in progress. Android 14+ requires a typed
foreground service to hold a socket open from the background; the ongoing
notification is the price, and dismissing it stops the listener. (The type it
uses is wrong for the job — see [Known bugs](#known-bugs).)

### Sharing in and out

AeroDrop registers for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`, so any app's
share sheet can hand files to a Mac. Shared **text** is staged into a real file
under the app's cache and sent as `shared-<timestamp>.txt` — an earlier version
ran the text through `Uri.parse`, which produced a scheme-less relative URI that
nothing could open, so a shared note silently failed to transfer.

## How it works

```
Android device                          Mac
     │                                     │
     ├── Bonjour _aerodrop._tcp:7770 ◄────┤   AeroDiscoveryBrowser
     │                                     │
     ├── TLS 1.3 ────────────────────────►─┤   AeroServer
     ├── 64-byte AERO header                │   ├─ magic "AERO", version, file size,
     └── raw payload ─────────────────────►─┤   │  filename (44B), Adler-32
                                           │   └─ 512 KiB chunks
```

**Android advertises and browses in one object.** `AeroDiscovery` owns both the
`NsdManager` registration and the discovery listener. NsdManager allows exactly
one discovery at a time, and the earlier split into a separate advertiser and
discovery service meant the two fought over that slot.

**Resolution is split on API 34.** `NsdServiceInfo.getHostAddresses()` does not
exist below API 34 and `getHost()` changed its return type from `String` to
`InetAddress` *in* API 34. Calling either one unconditionally throws —
`NoSuchMethodError` below 34, `ClassCastException` at it — and because the
original call was wrapped in `runCatching`, discovery silently resolved **zero
peers on every Android 10–13 device** while looking perfectly healthy. The
pre-34 path reads the host reflectively so it is correct either way.

**IPv4 is preferred**, matching the Mac's own resolution order, because Android
binds the listener to the wildcard address and the Mac skips `AF_INET6` results.

### Wire format

A fixed 64-byte header precedes the payload:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `AERO` |
| 4 | 4 | version (`uint32`, currently 1) |
| 8 | 8 | file size (`uint64`) |
| 16 | 44 | filename, UTF-8, null-padded |
| 60 | 4 | Adler-32 of the filename bytes |

The remainder of the connection is the raw file, streamed in 512 KiB chunks with
a progress callback every ~100 ms. Both header fields are **little-endian**.

The filename field holds 43 usable bytes plus the NUL. When a name is too long,
Android truncates on a **UTF-8 character boundary** — the C++ encoder only
checked whether the first dropped byte was a continuation byte, so a 3-byte
character starting at index 43 left a dangling lead byte and the Mac decoded a
mangled name. The Mac itself truncates blindly with `memcpy` and will happily
split a character; the Android receiver tolerates that, and the Adler-32 is
always computed over the **raw** bytes rather than a decode/re-encode round trip,
so a half-character still validates.

### Unknown file sizes

Some content providers report no size. Since the header declares the length
before the payload, an unknown size is **spooled to a private cache file** and
sent from there rather than guessed — a wrong length in the header would
desynchronise the stream and the Mac would wait forever for bytes that were
never coming.

## Security

- TLS 1.3 only. The floor is pinned explicitly with `setEnabledProtocols` rather
  than through the `SSLContext` protocol name, which is not a reliable way to pin
  a version on Android.
- Traffic is encrypted in transit and never leaves the LAN.
- **Certificates are not verified.** A custom `X509TrustManager` accepts any
  chain, on both the sending and receiving side, so the channel is confidential
  but **not authenticated** — another device on the same network could in
  principle man-in-the-middle it. This matches the Mac, which sets
  `SSL_VERIFY_NONE`. See [Pairing](#pairing) for what is and isn't checked.
- The receiver's socket is bound to the wildcard address, so it accepts from any
  peer on the LAN. There is no allow-list.
- Received filenames are **attacker-controlled** — they are whatever bytes a
  sending device put in a 44-byte network field. `AeroFileName` reduces them to
  a single path component, replaces path separators, reserved characters and
  control bytes, strips leading dots, and truncates on a surrogate boundary, so
  a name like `../../../etc/passwd` cannot escape `Downloads/AeroDrop/`.

### Pairing

There is no pairing step. Each device generates its own self-signed certificate
on first launch and the SHA-256 fingerprint is shown in the app footer.

Be aware of what that fingerprint is: it is **this device's own** certificate,
so comparing it against the Mac's shows you that the Mac did not change identity.
It does **not** authenticate the Mac, because the Android client never reads the
remote certificate. Real verification — pinning on first contact, or a PAKE — is
not implemented on either side.

## Known bugs

**Mac → phone fails during the TLS handshake.** The receiver accepts the
connection and then Conscrypt aborts signing:

```
error:04000044:RSA routines:OPENSSL_internal:internal error
javax.net.ssl.SSLHandshakeException: Read error: ... Failure in SSL library
    at AeroReceiverService.handleClient(AeroReceiverService.kt:156)
```

This is Android-specific and reproduces on any device, including the API 36
emulator. The sender reports the whole file as written, because from its side the
transfer *did* complete.

One cause is already fixed: the Keystore RSA key requested `PURPOSE_SIGN` **and**
`PURPOSE_ENCRYPT` together, and an RSA key cannot be authorised for both — the
Keystore accepts the spec, generates the key, and then refuses the signature at
handshake time. The key is now signing-only, with a `canSign()` self-test that
replaces an unusable stored key. That was necessary but not sufficient, so the
remaining suspects are the `KeyManagerFactory` algorithm string used over
`AndroidKeyStore` (Android expects `getDefaultAlgorithm()`, not `"X509"`), and
whether the self-signed chain needs `PURPOSE_VERIFY` to be usable as a server
certificate.

The interop tests do **not** cover this, and cannot: they supply a JVM-generated
PKCS12 certificate to stand in for the Android Keystore, so a Keystore bug is
invisible to them. This is the argument for testing on a real device.

## Tests

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest
```

43 tests, no device or emulator required:

| Suite | What it pins |
|---|---|
| `AeroHeaderTest` | the 64 bytes, against output captured from the real macOS packed C++ struct — truncation, checksum, UTF-8 boundaries, endianness |
| `AeroFileNameTest` | traversal, reserved characters, control bytes, length and surrogate truncation |
| `AeroInteropTest` | both directions over real TLS 1.3, against the actual macOS transport |

`AeroInteropTest` does not mock the peer. `tools/build-mac-peer.sh` compiles
`AeroServer.cpp` and `CertManager.cpp` **unmodified** out of the macOS
repository into a small driver, and the tests then run genuine sessions against
it — sends, overlong names, non-ASCII names, empty files, a corrupted header,
and a 1.2 MB push:

```bash
tools/build-mac-peer.sh /path/to/AeroDropMac   # builds tools/macpeer
./gradlew :app:testDebugUnitTest               # interop tests self-skip if absent
```

`tools/macpeer` is a compiled binary and is gitignored. The suite skips itself
rather than failing when the peer has not been built, so the project still tests
cleanly on a machine without the macOS sources.

This is how the macOS side's `close()` bug was found: `sendFile` closed a socket
whose receive queue still held the peer's TLS 1.3 session tickets, so the kernel
sent RST instead of FIN and the receiver lost the tail of a transfer the sender
had already counted as complete. Fixed on the Mac side in `90062e9`.

## Layout

```
app/src/main/java/com/aerodrop/
  MainActivity.kt             Share-sheet entry, notification permission, Compose host
  AeroViewModel.kt            Transfer state, peer selection, queue
  discovery/
    AeroDiscovery.kt          mDNS advertise + browse, address resolution
    AeroPeer.kt               Peer model
  transfer/
    AeroHeader.kt             The 64-byte wire header
    AeroProtocol.kt           Send/receive loops, shared by app and tests
    AeroCertManager.kt        Keystore key + TLS 1.3 contexts
    AeroReceiverService.kt    Foreground service, TLS listener, inbound
    AeroTransferClient.kt     Outbound, including the unknown-size spool
    AeroInbound.kt            Inbound transfer state
  system/
    AeroFileName.kt           Filename sanitiser (pure, no framework types)
    MediaStoreHelper.kt       IS_PENDING write into Downloads/AeroDrop
  ui/
    AeroTheme.kt              Colours and formatting
    RootScreen.kt             Devices list, queue, status
tools/
  build-mac-peer.sh           Compiles the macOS transport for interop tests
  macpeer.cpp                 Driver for it
```

`AeroProtocol` and `AeroFileName` deliberately carry no Android types. They hold
the wire format and the path-safety rules respectively, and keeping them free of
framework types is what lets plain JVM tests exercise them.

## Notes and limitations

- **The release APK is unsigned.** See [Build](#build).
- **Received files are `Downloads/AeroDrop`**, matching the Mac's
  `~/Downloads/AeroDrop`, and inherit the `Download` collection's visibility
  settings. There is no per-transfer destination choice.
- **Transfers are serialized.** One at a time, both directions. There is no
  parallelism and no resume: a transfer interrupted by a lost connection starts
  over.
- **The receiver is held open with the wrong foreground service type.** It
  declares `dataSync`, which is the *wrong* category for a persistent LAN
  listener: `dataSync` is documented for upload/download, backup/restore and
  fetch/transfer, and on Android 15+ it is capped at **6 hours per rolling
  24 hours** (shared across all of an app's `dataSync` services). The cap
  applies here because `targetSdk` is 35.
  Worse, `AeroReceiverService` does not override `Service.onTimeout(int, int)`,
  so when the cap is reached the system gets no `stopSelf()` and logs
  `Fatal Exception: android.app.RemoteServiceException: "A foreground service of
  type [dataSync] did not stop within its timeout"`. On a phone left listening
  this is a real crash, not a theoretical one.
  The right type is **`connectedDevice`**, which has no time limit and covers
  exactly this case — sustained communication with an external device over the
  network. It needs one qualifying permission; `CHANGE_WIFI_STATE` is the
  cheapest here and pairs with the `CHANGE_WIFI_MULTICAST_STATE` already
  declared. Until that is done, expect the listener to die after six hours and
  the app to crash when it does.
  The service is `START_STICKY`, so a low-memory kill brings the listener back;
  the six-hour cap is not a kill, so sticky does not help with it.
- **Emulator mDNS is unreliable.** Discovery is verified on a real device on the
  same LAN; an emulator will advertise but is not a trustworthy test of finding
  the Mac.
