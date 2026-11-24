# Model-specific driver engine plan

This document records upstream engine matches and the integration boundary. A catalogue match is not a claim that the Android app can print yet.

## Confirmed upstream model matches

| Printer | Native language family | Candidate engine | Upstream model evidence | License |
|---|---|---|---|---|
| Samsung ML-1670 | SPL2 / SPL-C | OpenPrinting SpliX 2.0.2 | ML-1670 is explicitly listed as supported | GPL-2.0 |
| HP LaserJet M1005 MFP | HP XQX | OpenPrinting foo2xqx (foo2zjs project) | foo2xqx README explicitly lists HP LaserJet M1005 MFP | GPL-2.0-or-later in COPYING; inspect individual files for additional notices |
| Brother HL-L2320D | Brother raster / HBP family | brlaser | brlaser explicitly lists HL-L2320D series | GPL-2.0 |

Sources:
- SpliX supported models and release notes: https://github.com/OpenPrinting/splix and https://github.com/OpenPrinting/splix/releases
- foo2xqx supported models and license: https://github.com/OpenPrinting/foo2zjs
- brlaser supported models and license: https://github.com/pdewacht/brlaser

## Important implementation boundary

These upstream projects are Linux printer filters, not Android-ready libraries. They commonly expect CUPS/Ghostscript or a PBM/raster input pipeline. They cannot safely be copied into the APK as-is and called a working driver.

The Android integration still needs:
1. A defined raster contract from Android PDF rendering to each engine (page dimensions, resolution, bit depth, stride, orientation and margins).
2. NDK builds for supported Android ABIs, with all dependencies and notices included.
3. A narrow engine interface that accepts raster pages and emits native printer bytes to a file/stream.
4. A separately versioned driver package with SHA-256 verification, signed release metadata, atomic installation and rollback.
5. GPL source/offers and corresponding notices for every distributed GPL engine. Keep the main app and engine package distribution separate pending legal review; package separation alone is not a legal conclusion.
6. Physical USB tests on each exact model before changing its catalogue availability to BUILT_IN.

## Current state

- The app catalogue recognizes Samsung ML-1670, HP LaserJet M1005 MFP and Brother HL-L2320D.
- The app correctly refuses to send generic PCL to these known host-based devices.
- None of the three Android engine packages is integrated or ready for installation yet.
- Do not reactivate the old handwritten Samsung SPL encoder; it previously produced a printer firmware error and has not been validated.

## Additional HP model identified from hardware photo

**HP LaserJet Tank MFP 1005 / 1005w** is a separate model from the older HP LaserJet M1005 MFP. HP's product specifications list PCLmS, URF and PWG as its print languages. It must not be routed to foo2xqx (the older M1005 XQX engine). The Android catalogue now has a separate `hp.laserjet-tank-mfp-1005` profile using a dedicated PCLmS/URF/PWG engine requirement. The USB product ID is not yet confirmed; matching currently requires the HP VID plus the model name. No native engine is implemented yet.

HP references:
- https://support.hp.com/in-en/product/product-specs/hp-laserjet-tank-mfp-1005-printer-series/model/2100846455
- https://media.flixcar.com/webp/synd-asset/hp-100648929-c07986320.pdf

## HP LaserJet Tank MFP 1005: supported upstream path

HP's product specification confirms the model's print languages are PCLmS, URF and PWG. The HPLIP supported-device list separately lists **HP LaserJet Tank MFP 1005** and 1005w as supported by HPLIP 3.22.2, with USB connectivity and full print support. OpenPrinting describes HPLIP's `hpcups` backend as a CUPS Raster driver. Therefore the appropriate USB engine candidate is an Android/NDK port of the model-specific HPLIP `hpcups` path (including the required raster pipeline and USB backend), not `foo2xqx`, not PCL 5, and not simply dumping a PWG file to a USB bulk endpoint.

References:
- HP model specifications: https://support.hp.com/in-en/product/product-specs/hp-laserjet-tank-mfp-1005-printer-series/model/2100846455
- HPLIP supported-device list (Tank MFP 1005 / 1005w, HPLIP 3.22.2, USB): https://test.hpopenprint.com/hp-linux-imaging-and-printing/supported_devices/index
- OpenPrinting HPLIP driver architecture and mixed MIT/BSD/GPL licensing: https://openprinting.github.io/foomatic/driver/hplip

This port is not implemented yet. The next technical work is to isolate the minimal hpcups raster/filter and USB-backend dependencies, cross-compile them for Android ABIs, and verify the output on the physical Tank MFP 1005. Do not mark the profile BUILT_IN before that hardware test.
