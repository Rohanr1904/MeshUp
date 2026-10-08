# Proposed diff: `app/src/main/assets/licenses.json`

> Proposal only; **not applied**. The Chief applies it in a separate PR after review. Evidence for every line is in `docs/legal/THIRD_PARTY_LICENCES.md` (checked 2026-10-08). **Prepared for review by a qualified lawyer; not legal advice.**
> Schema is unchanged (`name`, `license`, `url`, `licenseUrl`, `category`, optional `notice`). Only existing fields are used, so no code change is needed. Do not add owner contact details.

```diff
--- a/app/src/main/assets/licenses.json
+++ b/app/src/main/assets/licenses.json
@@ AndroidX entry
-    {"name": "AndroidX, Jetpack Compose, Navigation, Room, WorkManager, CameraX and Security Crypto", "license": "Apache License 2.0", "url": "https://developer.android.com/jetpack/androidx", "licenseUrl": "https://www.apache.org/licenses/LICENSE-2.0", "category": "Android libraries"},
+    {"name": "AndroidX, Jetpack Compose, Navigation, Room, WorkManager, CameraX and Security Crypto", "license": "Apache License 2.0", "url": "https://developer.android.com/jetpack/androidx", "licenseUrl": "https://www.apache.org/licenses/LICENSE-2.0", "category": "Android libraries", "notice": "Each AndroidX artifact ships its Apache 2.0 text. CameraX camera-core also declares a BSD 3-Clause licence in its metadata."},
@@ Bouncy Castle
-    {"name": "Bouncy Castle Provider (bcprov)", "license": "Bouncy Castle Licence (MIT-style)", "url": "https://www.bouncycastle.org/java.html", "licenseUrl": "https://www.bouncycastle.org/licence.html", "category": "Cryptography"},
+    {"name": "Bouncy Castle Provider (bcprov)", "license": "MIT License (Bouncy Castle Licence)", "url": "https://www.bouncycastle.org/java.html", "licenseUrl": "https://www.bouncycastle.org/licence.html", "category": "Cryptography", "notice": "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)."},
@@ ML Kit / Play services (non-free; wording only)
-    {"name": "Google ML Kit Barcode Scanning", "license": "ML Kit Terms of Service / Google APIs Terms of Service", ...
+    {"name": "Google ML Kit Barcode Scanning (proprietary, not open source)", "license": "ML Kit Terms of Service", "url": "https://developers.google.com/ml-kit", "licenseUrl": "https://developers.google.com/ml-kit/terms", "category": "Camera and QR", "notice": "Closed-source Google component. Not covered by the GPL. ML Kit sends performance and usage metrics to Google. Third-party notices are bundled in the library (third_party_licenses.txt)."},
-    {"name": "Google Play services (base, basement, tasks, location, ML Kit barcode scanning)", "license": "Android Software Development Kit License / Google APIs Terms of Service", ...
+    {"name": "Google Play services (base, basement, tasks, location) and ML Kit image (odml)", "license": "Android Software Development Kit License", "url": "https://developers.google.com/android/guides/overview", "licenseUrl": "https://developer.android.com/studio/terms", "category": "Google services", "notice": "Closed-source Google components. Not covered by the GPL. Play services barcode scanning is under the ML Kit Terms above."},
@@ JSR-305 (FAIL: published licence is Apache 2.0)
-    {"name": "JSR-305 annotations (findbugs jsr305)", "license": "BSD 3-Clause style (as published with the JSR-305 reference implementation)", "url": "https://code.google.com/archive/p/jsr-305/", "licenseUrl": "https://code.google.com/archive/p/jsr-305/", "category": "Annotations and utilities"},
+    {"name": "JSR-305 annotations (findbugs jsr305 3.0.2)", "license": "Apache License 2.0 (as declared in the published Maven metadata)", "url": "https://github.com/amaembo/jsr-305", "licenseUrl": "https://www.apache.org/licenses/LICENSE-2.0", "category": "Annotations and utilities", "notice": "Some upstream sources describe this library as BSD 3-Clause. Pending legal review."},
@@ Nordic BLE
-    {"name": "Nordic Android BLE Library", "license": "BSD 3-Clause License", ...
+    {"name": "Nordic Android BLE Library 2.11.0", "license": "BSD 3-Clause License", "url": "https://github.com/NordicSemiconductor/Android-BLE-Library", "licenseUrl": "https://github.com/NordicSemiconductor/Android-BLE-Library/blob/main/LICENSE", "category": "Bluetooth", "notice": "Copyright (c) 2015, Nordic Semiconductor."},
@@ NanoHTTPD
-    {"name": "NanoHTTPD", "license": "BSD 3-Clause License", ...
+    {"name": "NanoHTTPD 2.3.1", "license": "BSD 3-Clause License", "url": "https://github.com/NanoHttpd/nanohttpd", "licenseUrl": "https://github.com/NanoHttpd/nanohttpd/blob/master/LICENSE.md", "category": "Networking", "notice": "Copyright (c) 2012 - 2016, nanohttpd."},
@@ Noise-Java
-    {"name": "Noise-Java (Southern Storm Software), vendored in this app", "license": "MIT License", ...
+    {"name": "Noise-Java (Southern Storm Software), vendored in this app", "license": "MIT License", "url": "https://github.com/rweather/noise-java", "licenseUrl": "https://opensource.org/license/mit", "category": "Vendored source", "notice": "Copyright (C) 2016 Southern Storm Software, Pty Ltd. Includes a public-domain AES (Rijndael) implementation by Vincent Rijmen, Antoon Bosselaers and Paulo Barreto."},
@@ New Hope (wrong licenseUrl, no author credit)
-    {"name": "New Hope key exchange port (Rhys Weatherley), vendored in this app", "license": "Public domain", "url": "https://github.com/rweather/noise-java", "licenseUrl": "https://github.com/rweather/noise-java", "category": "Vendored source"},
+    {"name": "New Hope key exchange (Java port by Rhys Weatherley), vendored in this app", "license": "Public domain", "url": "https://github.com/newhopecrypto/newhope-usenix", "licenseUrl": "https://github.com/newhopecrypto/newhope-usenix", "category": "Vendored source", "notice": "Based on the public-domain C reference code by Erdem Alkim, Leo Ducas, Thomas Poeppelmann and Peter Schwabe."},
@@ Natural Earth (scope)
-    {"name": "Natural Earth map data (land, borders, places)", "license": "Public domain", ...
+    {"name": "Natural Earth map data (land outlines)", "license": "Public domain", "url": "https://www.naturalearthdata.com", "licenseUrl": "https://www.naturalearthdata.com/about/terms-of-use/", "category": "Fonts and data", "notice": "Made with Natural Earth. Free vector and raster map data at naturalearthdata.com."},
@@ Arti / Rust summary
-    {"name": "Arti (Tor in Rust) and its Rust dependencies, bundled as a native library", "license": "MIT OR Apache License 2.0 (Arti); Rust dependencies are mostly MIT, Apache-2.0 or both", ...
+    {"name": "Arti (Tor in Rust) and its Rust dependencies, bundled as a native library", "license": "MIT OR Apache License 2.0 (Arti 1.9.0)", "url": "https://gitlab.torproject.org/tpo/core/arti", "licenseUrl": "https://gitlab.torproject.org/tpo/core/arti/-/blob/main/LICENSE-MIT", "category": "Tor", "notice": "Built from 509 Rust crates (Cargo.lock in tools/arti-build). Of the 465 third-party crates, all are under MIT, Apache-2.0, BSD, ISC, Zlib, Unicode, CC0, Unlicense or MPL-2.0 terms, often as alternatives. Notable: ring (ISC and Apache-2.0), curve25519-dalek, ed25519-dalek and subtle (BSD 3-Clause), option-ext (MPL-2.0). The full per-crate list is in the source repository (docs/legal/rust-crate-licences.json)."}
```

## Notes for the Chief
1. The diff keeps every existing field but fills `notice` on several entries. If the licences screen does not render `notice` for every entry, check `LicensesScreen` (not inspected; only OkHttp has a `notice` today).
2. The "Made with Natural Earth" line is optional courtesy text per https://www.naturalearthdata.com/about/terms-of-use/ ; remove it if unwanted. The `world_borders` and `world_cities` provenance is UNKNOWN; the wording above says "land outlines" because only that is confirmed by the code comment in `LandData.kt`.
3. JSR-305: the Apache-2.0 wording follows the published Maven POM (`jsr305-3.0.2.pom`). A lawyer should confirm (question 1 in `THIRD_PARTY_LICENCES.md`).
4. Optional additions the Chief may want: a `Jakarta Inject` notice line is already covered by the grouped Dagger entry (Apache-2.0); no change.
5. The Rust figures in the Arti notice come from `docs/legal/rust-crate-licences.json`. Re-run the audit when `Cargo.lock` changes.
6. Licence texts that must also ship with the app, and where they are today: GPLv3 (`assets/gpl-3.0.txt`), Geist OFL (`res/raw/geist_mono_ofl.txt`). The BSD-3-Clause, MIT and Apache-2.0 texts are only linked, not embedded; a lawyer should say whether links satisfy "reproduce the notice in binary distribution" (BSD clause 2, MIT notice clause). Safer: embed one local copy of each text in assets and link to it.

Prepared for review by a qualified lawyer; not legal advice.
