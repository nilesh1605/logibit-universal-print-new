# Logibit Universal Print

An Android Print Service project with a GitHub Actions pipeline. You do **not** need Android Studio to build it: GitHub Actions builds the APK in the cloud and lets you download it from the workflow run.

> **Compatibility is not literally universal.** No single Android app can print to every printer: manufacturers use incompatible command languages, firmware, USB interfaces and authentication methods. This repository uses standard protocols where possible and is structured to add vendor/model drivers. A printer that needs a proprietary driver is not supported until that driver is implemented and tested.

## Current implementation status

| Connection / language | Current status | Notes |
|---|---|---|
| USB / OTG | Partial, extensible | USB jobs are dispatched through `UsbPrinterDriverRegistry`. The generic PCL 5 driver sends rasterized PDF only when the IEEE-1284 ID advertises PCL 5. Manufacturer/model drivers can be registered with higher priority. |
| Samsung SPL2/QPDL (ML/SCX/CLX mono lasers, e.g. ML-1670; rebranded Xerox Phaser 6100) | Implemented, **untested on hardware** | Built from the publicly documented SPL2 wire format (not from any vendor or GPL driver source). Matches Samsung's USB vendor id and sends literal/uncompressed bands — correct per spec, just less compact than a real compressor. Treat the first print as a test. |
| Wi-Fi / LAN | Partial | Discovers `_ipp._tcp` services and submits the original PDF using IPP 2.0. The printer must accept `application/pdf` over plain IPP/HTTP. |
| Bluetooth | Not implemented | Needs transport support plus the printer's actual Bluetooth profile/language. |
| Raw TCP 9100 / LPR | Not implemented | Separate transport and protocol implementation required. |
| PCL XL/PCL6, PostScript, Canon proprietary, Epson ESC/P, other GDI/host-based languages | Not implemented | Require compatible model-specific drivers (same pattern as the SPL2 driver) or a print server/vendor driver. |
| IPPS / TLS and authenticated IPP | Not implemented | Current IPP client is plain HTTP and does not implement printer authentication. |

"Universal" realistically means covering the handful of printer-language families one at a time (PCL 5 and SPL2/QPDL so far), not literally every device ever made — a host-based printer with no documented protocol at all cannot be driven without reverse-engineering it first, the way SPL2 was reverse-engineered by the SpliX project.

The USB driver system intentionally refuses to send data when no registered driver recognizes the device or when the selected driver cannot confirm its printer language. It does not guess a printer language or send PDF bytes directly to an arbitrary USB printer. This is safer than silently sending data the printer cannot interpret.

## Build without Android Studio

1. Create a GitHub account if you do not already have one.
2. Create a new repository named `logibit-universal-print` on GitHub.
3. Upload the contents of this folder to the repository (not the enclosing folder itself), or use GitHub's web upload interface.
4. Open the repository's **Actions** tab. The `Android APK` workflow starts on push; you can also select **Run workflow**.
5. When the run finishes successfully, open the run and download the artifact named `logibit-universal-print-debug-apk`.
6. Extract the artifact and install `app-debug.apk` on your Android phone. Android may ask you to allow installation from the browser/file manager.
7. Enable **Logibit Universal Print** under Android **Settings → Connected devices / Printing** (the exact menu varies by phone).

No Android Studio or local Android SDK is required. GitHub Actions performs the build.

## Create a versioned release APK

After uploading the project and confirming the workflow builds, create and push a tag such as `v0.1.0` from GitHub's Releases page or Git interface. The `Publish APK release` workflow builds an unsigned release APK and attaches it to the GitHub Release. For public distribution, configure signing secrets and sign the APK before publishing; never commit a keystore or passwords to the repository.

## USB / OTG setup

1. Use an Android device with USB host/OTG support.
2. Connect the printer with a data-capable OTG adapter/cable. Some printers need their own power supply.
3. Unlock the phone and approve Android's USB access prompt.
4. Choose **Logibit Universal Print** in Android's print dialog.
5. If the printer is a Samsung device, the SPL2/QPDL driver is tried automatically. For any other printer, if it does not advertise PCL 5, the current driver reports that the printer language is unsupported instead of sending incompatible bytes.

## Development roadmap

- Add a real driver registry keyed by USB VID/PID and IEEE-1284 `MFG`, `MDL`, `CMD` fields.
- Add vendor-specific backends only with protocol documentation or validated hardware tests.
- Add IPP capability negotiation (`Get-Printer-Attributes`) before submitting a job; current network code uses a minimal PDF Print-Job request.
- Add IPPS/TLS, authentication, and robust printer status handling.
- Add Bluetooth and raw TCP/LPR transports with explicit protocol detection.
- Test each model/firmware/connection combination on physical hardware before marking it supported.

## Contributing a printer driver

USB drivers implement `UsbPrinterDriver` and register with `UsbPrinterDriverRegistry`. Give exact model drivers a higher `priority` than generic language drivers. A driver should document supported vendor/model IDs, connection transport, accepted document format or printer language, supported media/resolutions/color modes, and physical test results. Do not claim compatibility based only on a device being discoverable.

## Project details

- Application ID: `com.logibit.universalprint`
- Minimum Android: API 26 (Android 8.0)
- Compile / target SDK: 35
- Kotlin + Android PrintService API
- Build: GitHub Actions (`.github/workflows/`)

## Security note

The current IPP implementation uses unencrypted HTTP for printer connections on the local network. Use only on trusted networks. Before production distribution, implement IPPS/TLS and printer authentication, configure APK signing, and perform device/security testing.
