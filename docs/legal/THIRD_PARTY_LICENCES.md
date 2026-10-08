# NearBird third-party licence verification (Playbook A)

> Checked 2026-10-08 by the `legal-compliance` specialist. Worktree `docs/legal-work`. **Prepared for review by a qualified lawyer; not legal advice.**
> Labels: PASS / FAIL / UNKNOWN / NEEDS LAWYER. Inputs: `app/gradle.lockfile` (`releaseRuntimeClasspath`, 194 coordinates), `app/src/main/assets/licenses.json`, `gradle/libs.versions.toml`, vendored sources, `tools/arti-build/Cargo.lock`, local Gradle cache (`modules-2/files-2.1`).

## 1. Result

- Every JVM artifact on the release runtime classpath (194 of 194) was found in the local Gradle cache, so POMs and archive listings were inspected directly.
- No GPLv3-incompatible licence was found. Two Rust crates offer LGPL only as an *alternative* (section 5), and one MPL-2.0 crate is probably linked (`option-ext`).
- **Corrections needed in `licenses.json`:** JSR-305 (published licence is Apache-2.0, not BSD-3-Clause as the screen says), Bouncy Castle wording (the shipped text is MIT), camera-core's second licence (BSD-3-Clause), copyright holders for Nordic/NanoHTTPD/Noise-Java, missing RijndaelAES public-domain note, Natural Earth scope, and a better Arti/Rust summary. See `LICENSES_SCREEN_DIFF.md`.
- **Non-free:** ML Kit and Play Services (including Location) are proprietary. NEEDS LAWYER; they block F-Droid main-repo inclusion.

## 2. The four uncertain groups

| Component | Version | SPDX | Source of licence (checked 2026-10-08) | NOTICE file? | GPLv3-compatible | Attribution to show | Status |
|---|---|---|---|---|---|---|---|
| Nordic Android BLE Library (`no.nordicsemi.android:ble`) | 2.11.0 | BSD-3-Clause | Cached `ble-2.11.0.pom` `<licenses>`: "The BSD 3-Clause License". Repo LICENSE https://raw.githubusercontent.com/NordicSemiconductor/Android-BLE-Library/main/LICENSE : BSD 3-Clause, "Copyright (c) 2015, Nordic Semiconductor" | No (AAR holds only `aar-metadata.properties`) | Yes | Copyright line and BSD text must be reproduced for binary distribution | PASS (screen correct; add the copyright line) |
| NanoHTTPD (`org.nanohttpd:nanohttpd`) | 2.3.1 | BSD-3-Clause | Parent POM `nanohttpd-project-2.3.1.pom`: "The BSD 3-Clause License". `LICENSE.txt` inside `nanohttpd-2.3.1.jar`: "Copyright (c) 2012 - 2016, nanohttpd". Repo https://raw.githubusercontent.com/NanoHttpd/nanohttpd/master/LICENSE.md (BSD-3-Clause; current text names Hawke, Elonen, Togias) | `LICENSE.txt` in jar root | Yes | Copyright + BSD text | PASS (screen correct; add the copyright line) |
| JSR-305 (`com.google.code.findbugs:jsr305`) | 3.0.2 | Apache-2.0 per published POM; BSD-3-Clause per upstream source-repo POM; LGPL claimed by FindBugs project | Cached `jsr305-3.0.2.pom`: "The Apache Software License, Version 2.0". Sources jar has no licence headers; no LICENSE file in the jar (only `META-INF/maven/...`). Conflict documented at https://github.com/findbugsproject/findbugs/issues/128 and https://github.com/amaembo/jsr-305/blob/master/ri/pom.xml (secondary; from a web-search summary, not read in full). The archived page https://code.google.com/archive/p/jsr-305/ states no licence. | None | Yes under every candidate (Apache-2.0, BSD-3-Clause, LGPL) | Apache-2.0 text if relying on the POM | **FAIL on the screen** (says BSD only); NEEDS LAWYER on which statement governs |
| Natural Earth data (`assets/world_land.geojson`, `world_borders.geojson`, `world_cities.geojson`) | 110m land per `LandData.kt` comment | Public domain (no SPDX id) | https://www.naturalearthdata.com/about/terms-of-use/ : data "in the public domain"; "Crediting the authors is unnecessary" (optional text "Made with Natural Earth.") | No | Yes | None required; optional credit | PASS for land. **UNKNOWN** provenance for borders and cities: fields `r`, `cap`, `mega`, `pop` resemble Natural Earth populated places, but the repo records no source |
| NewHope port (`noise/southernstorm/crypto/NewHope.java`, `NewHopeTor.java`; used by `protocol/NewHopeDHState.java`) | n/a | Public domain (author dedication) | File headers (both): "Based on the public domain C reference code for New Hope. This Java version is also placed into the public domain. Original authors: Erdem Alkim, Leo Ducas, Thomas Poeppelmann, Peter Schwabe. Java port: Rhys Weatherley." `NewHopeTor` is the "torref" variant (constant-time generation of `a`). Upstream C: https://github.com/newhopecrypto/newhope-usenix (README: "All code in this repository is in the public domain"; Keccak, ChaCha, AES from other public-domain sources; no LICENSE file) and https://github.com/newhopecrypto/newhope (README: public domain except NIST files `PQCgenKAT.c`, `rng.c`, `rng.h`). Port host https://github.com/rweather/noise-java (repo licence MIT; README silent on NewHope). | No | Yes | None legally required; keep headers | PASS (headers match primary sources). NEEDS LAWYER only on the general effect of public-domain dedications (question 3) |

### Other vendored code and data
| Item | Licence | Evidence | Status |
|---|---|---|---|
| Noise-Java (`noise/southernstorm/**`, 25 headed files) | MIT, "Copyright (C) 2016 Southern Storm Software, Pty Ltd." | File headers; https://github.com/rweather/noise-java (MIT) | PASS. Headers retained. Screen should name the copyright holder. |
| `RijndaelAES.java` | Public domain (Rijmen, Bosselaers, Barreto; `rijndael-alg-fst.c` v3.0) with a disclaimer | File header | PASS. Not on the screen; add. |
| Geist Mono (`res/font/geist_mono_*.ttf`, `res/raw/geist_mono_ofl.txt`) | SIL OFL 1.1, "Copyright 2024 The Geist Project Authors" | `geist_mono_ofl.txt` header | PASS. OFL text is shipped. |
| `assets/gpl-3.0.txt` | GPLv3 text | present | PASS |
| `assets/nostr_relays.csv` (relay hostnames + lat/lon) | UNKNOWN | No provenance in the repo; inherited from upstream | UNKNOWN. Low risk (public hostnames, coordinates); record provenance. |
| `assets/design-icons/` | UNKNOWN | Not inspected (outside the requested scope) | UNKNOWN, follow-up |

## 3. JVM table (release runtime classpath, by Maven group)

Licence text is each artifact's POM `<licenses>` in the Gradle cache. "Carried files" = count of LICENSE/NOTICE-type entries in the cached AAR/JAR, excluding javadoc jars (details in section 4).

| Maven group | Artifacts | Versions | POM licence(s) | Carried files |
|---|---|---|---|---|
| `androidx.activity` | 3 | 1.13.0 | The Apache Software License, Version 2.0 | 3 |
| `androidx.annotation` | 3 | 1.10.0, 1.4.1 | The Apache Software License, Version 2.0 | 1 |
| `androidx.appcompat` | 2 | 1.7.1 | The Apache Software License, Version 2.0 | 0 |
| `androidx.arch.core` | 2 | 2.2.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.autofill` | 1 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.camera.featurecombinationquery` | 1 | 1.6.1 | The Apache Software License, Version 2.0 | 1 |
| `androidx.camera.viewfinder` | 2 | 1.6.1 | The Apache Software License, Version 2.0 | 2 |
| `androidx.camera` | 5 | 1.6.1 | BSD-3-Clause; The Apache Software License, Version 2.0 | 5 |
| `androidx.collection` | 3 | 1.5.0 | The Apache Software License, Version 2.0 | 2 |
| `androidx.compose.animation` | 4 | 1.11.4 | The Apache Software License, Version 2.0 | 2 |
| `androidx.compose.foundation` | 4 | 1.11.4 | The Apache Software License, Version 2.0 | 2 |
| `androidx.compose.material3` | 2 | 1.4.0 | The Apache Software License, Version 2.0 | 1 |
| `androidx.compose.material` | 6 | 1.11.4, 1.7.8 | The Apache Software License, Version 2.0 | 1 |
| `androidx.compose.runtime` | 8 | 1.11.4 | The Apache Software License, Version 2.0 | 4 |
| `androidx.compose.ui` | 14 | 1.11.4 | The Apache Software License, Version 2.0 | 7 |
| `androidx.compose` | 1 | 2026.06.01 | The Apache Software License, Version 2.0 | 0 |
| `androidx.concurrent` | 2 | 1.1.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.core` | 4 | 1.0.0, 1.19.0 | The Apache Software License, Version 2.0 | 4 |
| `androidx.cursoradapter` | 1 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.customview` | 2 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.documentfile` | 1 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.drawerlayout` | 1 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.dynamicanimation` | 1 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.emoji2` | 2 | 1.4.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.exifinterface` | 1 | 1.4.2 | The Apache Software License, Version 2.0 | 1 |
| `androidx.fragment` | 1 | 1.5.4 | The Apache Software License, Version 2.0 | 0 |
| `androidx.graphics` | 1 | 1.0.1 | The Apache Software License, Version 2.0 | 0 |
| `androidx.interpolator` | 1 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.legacy` | 1 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.lifecycle` | 21 | 2.11.0 | The Apache Software License, Version 2.0 | 15 |
| `androidx.loader` | 1 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.localbroadcastmanager` | 1 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.navigation` | 6 | 2.9.8 | The Apache Software License, Version 2.0 | 3 |
| `androidx.navigationevent` | 4 | 1.0.0 | The Apache Software License, Version 2.0 | 2 |
| `androidx.print` | 1 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.profileinstaller` | 1 | 1.4.0 | The Apache Software License, Version 2.0 | 1 |
| `androidx.resourceinspection` | 1 | 1.0.1 | The Apache Software License, Version 2.0 | 0 |
| `androidx.room` | 3 | 2.6.1 | The Apache Software License, Version 2.0 | 0 |
| `androidx.savedstate` | 5 | 1.4.0 | The Apache Software License, Version 2.0 | 3 |
| `androidx.security` | 1 | 1.1.0 | The Apache Software License, Version 2.0 | 1 |
| `androidx.sqlite` | 2 | 2.4.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.startup` | 1 | 1.2.0 | The Apache Software License, Version 2.0 | 1 |
| `androidx.tracing` | 3 | 1.3.0 | The Apache Software License, Version 2.0 | 2 |
| `androidx.transition` | 1 | 1.6.0 | The Apache Software License, Version 2.0 | 1 |
| `androidx.vectordrawable` | 2 | 1.1.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.versionedparcelable` | 1 | 1.1.1 | The Apache Software License, Version 2.0 | 0 |
| `androidx.viewpager` | 1 | 1.0.0 | The Apache Software License, Version 2.0 | 0 |
| `androidx.window` | 3 | 1.5.0 | The Apache Software License, Version 2.0 | 2 |
| `androidx.work` | 2 | 2.10.1 | The Apache Software License, Version 2.0 | 2 |
| `com.google.accompanist` | 1 | 0.37.3 | The Apache Software License, Version 2.0 | 0 |
| `com.google.android.datatransport` | 3 | 2.2.1, 2.2.6, 2.3.3 | The Apache Software License, Version 2.0 | 0 |
| `com.google.android.gms` | 5 | 18.3.1, 18.4.0, 18.9.0, 21.4.0 | Android Software Development Kit License; ML Kit Terms of Service | 0 |
| `com.google.android.odml` | 1 | 1.0.0-beta1 | Android Software Development Kit License | 0 |
| `com.google.auto.value` | 1 | 1.6.3 | (none in POM) | 0 |
| `com.google.code.findbugs` | 1 | 3.0.2 | The Apache Software License, Version 2.0 | 0 |
| `com.google.code.gson` | 1 | 2.14.0 | Apache-2.0 | 0 |
| `com.google.crypto.tink` | 1 | 1.23.0 | Apache License, Version 2.0 | 0 |
| `com.google.dagger` | 1 | 2.59 | Apache 2.0 | 0 |
| `com.google.errorprone` | 1 | 2.48.0 | Apache 2.0 | 0 |
| `com.google.firebase` | 4 | 16.0.0, 16.1.0, 17.1.0 | The Apache Software License, Version 2.0 | 0 |
| `com.google.guava` | 1 | 1.0 | (none in POM) | 0 |
| `com.google.mlkit` | 5 | 16.3.0, 17.0.0, 17.3.0, 18.11.0 | ML Kit Terms of Service | 0 |
| `com.google.zxing` | 1 | 3.5.4 | (none in POM) | 0 |
| `com.squareup.okhttp3` | 2 | 5.4.0 | The Apache Software License, Version 2.0 | 0 |
| `com.squareup.okio` | 2 | 3.17.0 | The Apache Software License, Version 2.0 | 0 |
| `jakarta.inject` | 1 | 2.0.1 | The Apache Software License, Version 2.0 | 2 |
| `javax.inject` | 1 | 1 | The Apache Software License, Version 2.0 | 0 |
| `no.nordicsemi.android` | 1 | 2.11.0 | The BSD 3-Clause License | 0 |
| `org.bouncycastle` | 1 | 1.85 | Bouncy Castle Licence | 1 |
| `org.jetbrains.kotlin` | 5 | 1.8.22, 2.4.10 | Apache-2.0; The Apache License, Version 2.0 | 0 |
| `org.jetbrains.kotlinx` | 9 | 0.28.0, 1.11.0, 1.7.3 | Apache-2.0; The Apache Software License, Version 2.0 | 0 |
| `org.jetbrains` | 2 | 13.0, 23.0.0 | The Apache Software License, Version 2.0 | 0 |
| `org.jspecify` | 1 | 1.0.0 | The Apache License, Version 2.0 | 0 |
| `org.nanohttpd` | 1 | 2.3.1 | (none in POM) | 1 |

Comments:
- **AndroidX** (about 120 artifacts, Apache-2.0): each AAR/JAR carries `META-INF/androidx/<group>/<artifact>/LICENSE.txt` (Apache-2.0 text); none carries a NOTICE. `androidx.camera:camera-core:1.6.1` also declares **BSD-3-Clause** in its POM, but its LICENSE.txt is Apache text only; which code is BSD is UNKNOWN.
- **Tink** `tink-android` 1.23.0, **Gson** 2.14.0, **Dagger** 2.59, **Error Prone annotations** 2.48.0, **JSpecify** 1.0.0: Apache-2.0. Their `legal/LICENSE` files exist only inside javadoc jars (not shipped).
- **Jakarta Inject API** 2.0.1: Apache-2.0; ships `META-INF/LICENSE.txt` and `META-INF/NOTICE.md` (Eclipse Foundation; trademark notice for "Jakarta Dependency Injection").
- **Bouncy Castle** `bcprov-jdk18on` 1.85: POM "Bouncy Castle Licence"; `META-INF/LICENSE.md` is the **MIT** text, "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc."
- **ZXing core** 3.5.4: Apache-2.0 via `zxing-parent-3.5.4.pom`. **auto-value-annotations** 1.6.3: no POM licence, Apache-2.0 per source header. **listenablefuture** 1.0: no POM licence (UNKNOWN from the artifact; the Guava project is Apache-2.0).
- **OkHttp** 5.4.0 / **Okio** 3.17.0: Apache-2.0. The OkHttp AAR carries `assets/PublicSuffixDatabase.list` (Mozilla Public Suffix List, MPL-2.0). No NOTICE file in these versions. The screen already mentions the list.
- **Kotlin, kotlinx, atomicfu, JetBrains annotations 13.0 and 23.0.0, Accompanist Permissions 0.37.3**: Apache-2.0 per POM; no carried files.
- **Firebase / datatransport**: Apache-2.0 (see table). **odml `image`**: Android SDK licence (proprietary, section 6); their AARs carry Google `third_party_licenses.txt/.json`, not META-INF notices.

## 4. LICENSE and NOTICE files found in the Gradle cache (exact lockfile versions)

- `androidx.activity:activity-compose:1.13.0`: `activity-compose-1.13.0.aar!META-INF/androidx/activity/activity-compose/LICENSE.txt`
- `androidx.activity:activity-ktx:1.13.0`: `activity-ktx-1.13.0.aar!META-INF/androidx/activity/activity-ktx/LICENSE.txt`
- `androidx.activity:activity:1.13.0`: `activity-1.13.0.aar!META-INF/androidx/activity/activity/LICENSE.txt`
- `androidx.annotation:annotation-jvm:1.10.0`: `annotation-jvm-1.10.0.jar!META-INF/androidx/annotation/annotation/LICENSE.txt`
- `androidx.camera.featurecombinationquery:featurecombinationquery:1.6.1`: `featurecombinationquery-1.6.1.aar!META-INF/androidx/camera/featurecombinationquery/featurecombinationquery/LICENSE.txt`
- `androidx.camera.viewfinder:viewfinder-compose:1.6.1`: `viewfinder-compose-1.6.1.aar!META-INF/androidx/camera/viewfinder/viewfinder-compose/LICENSE.txt`
- `androidx.camera.viewfinder:viewfinder-core:1.6.1`: `viewfinder-core-1.6.1.aar!META-INF/androidx/camera/viewfinder/viewfinder-core/LICENSE.txt`
- `androidx.camera:camera-camera2-pipe:1.6.1`: `camera-camera2-pipe-1.6.1.aar!META-INF/androidx/camera/camera-camera2-pipe/LICENSE.txt`
- `androidx.camera:camera-camera2:1.6.1`: `camera-camera2-1.6.1.aar!META-INF/androidx/camera/camera-camera2/LICENSE.txt`
- `androidx.camera:camera-compose:1.6.1`: `camera-compose-1.6.1.aar!META-INF/androidx/camera/camera-compose/LICENSE.txt`
- `androidx.camera:camera-core:1.6.1`: `camera-core-1.6.1.aar!META-INF/androidx/camera/camera-core/LICENSE.txt`
- `androidx.camera:camera-lifecycle:1.6.1`: `camera-lifecycle-1.6.1.aar!META-INF/androidx/camera/camera-lifecycle/LICENSE.txt`
- `androidx.collection:collection-jvm:1.5.0`: `collection-jvm-1.5.0.jar!META-INF/androidx/collection/collection/LICENSE.txt`
- `androidx.collection:collection-ktx:1.5.0`: `collection-ktx-1.5.0.jar!META-INF/androidx/collection/collection-ktx/LICENSE.txt`
- `androidx.compose.animation:animation-android:1.11.4`: `animation.aar!META-INF/androidx/compose/animation/animation/LICENSE.txt`
- `androidx.compose.animation:animation-core-android:1.11.4`: `animation-core.aar!META-INF/androidx/compose/animation/animation-core/LICENSE.txt`
- `androidx.compose.foundation:foundation-android:1.11.4`: `foundation.aar!META-INF/androidx/compose/foundation/foundation/LICENSE.txt`
- `androidx.compose.foundation:foundation-layout-android:1.11.4`: `foundation-layout.aar!META-INF/androidx/compose/foundation/foundation-layout/LICENSE.txt`
- `androidx.compose.material3:material3-android:1.4.0`: `material3.aar!META-INF/androidx/compose/material3/material3/LICENSE.txt`
- `androidx.compose.material:material-ripple-android:1.11.4`: `material-ripple.aar!META-INF/androidx/compose/material/material-ripple/LICENSE.txt`
- `androidx.compose.runtime:runtime-android:1.11.4`: `runtime.aar!META-INF/androidx/compose/runtime/runtime/LICENSE.txt`
- `androidx.compose.runtime:runtime-annotation-android:1.11.4`: `runtime-annotation.aar!META-INF/androidx/compose/runtime/runtime-annotation/LICENSE.txt`
- `androidx.compose.runtime:runtime-retain-android:1.11.4`: `runtime-retain.aar!META-INF/androidx/compose/runtime/runtime-retain/LICENSE.txt`
- `androidx.compose.runtime:runtime-saveable-android:1.11.4`: `runtime-saveable.aar!META-INF/androidx/compose/runtime/runtime-saveable/LICENSE.txt`
- `androidx.compose.ui:ui-android:1.11.4`: `ui.aar!META-INF/androidx/compose/ui/ui/LICENSE.txt`
- `androidx.compose.ui:ui-geometry-android:1.11.4`: `ui-geometry.aar!META-INF/androidx/compose/ui/ui-geometry/LICENSE.txt`
- `androidx.compose.ui:ui-graphics-android:1.11.4`: `ui-graphics.aar!META-INF/androidx/compose/ui/ui-graphics/LICENSE.txt`
- `androidx.compose.ui:ui-text-android:1.11.4`: `ui-text.aar!META-INF/androidx/compose/ui/ui-text/LICENSE.txt`
- `androidx.compose.ui:ui-tooling-preview-android:1.11.4`: `ui-tooling-preview.aar!META-INF/androidx/compose/ui/ui-tooling-preview/LICENSE.txt`
- `androidx.compose.ui:ui-unit-android:1.11.4`: `ui-unit.aar!META-INF/androidx/compose/ui/ui-unit/LICENSE.txt`
- `androidx.compose.ui:ui-util-android:1.11.4`: `ui-util.aar!META-INF/androidx/compose/ui/ui-util/LICENSE.txt`
- `androidx.core:core-backported-fixes:1.0.0`: `core-backported-fixes-1.0.0.aar!META-INF/androidx/core/core-backported-fixes/LICENSE.txt`
- `androidx.core:core-ktx:1.19.0`: `core-ktx-1.19.0.aar!META-INF/androidx/core/core-ktx/LICENSE.txt`
- `androidx.core:core-viewtree:1.0.0`: `core-viewtree-1.0.0.aar!META-INF/androidx/core/core-viewtree/LICENSE.txt`
- `androidx.core:core:1.19.0`: `core-1.19.0.aar!META-INF/androidx/core/core/LICENSE.txt`
- `androidx.exifinterface:exifinterface:1.4.2`: `exifinterface-1.4.2.aar!META-INF/androidx/exifinterface/exifinterface/LICENSE.txt`
- `androidx.lifecycle:lifecycle-common-java8:2.11.0`: `lifecycle-common-java8-2.11.0.jar!META-INF/androidx/lifecycle/lifecycle-common-java8/LICENSE.txt`
- `androidx.lifecycle:lifecycle-common-jvm:2.11.0`: `lifecycle-common-jvm-2.11.0.jar!META-INF/androidx/lifecycle/lifecycle-common/LICENSE.txt`
- `androidx.lifecycle:lifecycle-livedata-core-ktx:2.11.0`: `lifecycle-livedata-core-ktx-2.11.0.aar!META-INF/androidx/lifecycle/lifecycle-livedata-core-ktx/LICENSE.txt`
- `androidx.lifecycle:lifecycle-livedata-core:2.11.0`: `lifecycle-livedata-core-2.11.0.aar!META-INF/androidx/lifecycle/lifecycle-livedata-core/LICENSE.txt`
- `androidx.lifecycle:lifecycle-livedata:2.11.0`: `lifecycle-livedata-2.11.0.aar!META-INF/androidx/lifecycle/lifecycle-livedata/LICENSE.txt`
- `androidx.lifecycle:lifecycle-process:2.11.0`: `lifecycle-process-2.11.0.aar!META-INF/androidx/lifecycle/lifecycle-process/LICENSE.txt`
- `androidx.lifecycle:lifecycle-runtime-android:2.11.0`: `lifecycle-runtime.aar!META-INF/androidx/lifecycle/lifecycle-runtime/LICENSE.txt`
- `androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0`: `lifecycle-runtime-compose.aar!META-INF/androidx/lifecycle/lifecycle-runtime-compose/LICENSE.txt`
- `androidx.lifecycle:lifecycle-runtime-ktx-android:2.11.0`: `lifecycle-runtime-ktx.aar!META-INF/androidx/lifecycle/lifecycle-runtime-ktx/LICENSE.txt`
- `androidx.lifecycle:lifecycle-service:2.11.0`: `lifecycle-service-2.11.0.aar!META-INF/androidx/lifecycle/lifecycle-service/LICENSE.txt`
- `androidx.lifecycle:lifecycle-viewmodel-android:2.11.0`: `lifecycle-viewmodel.aar!META-INF/androidx/lifecycle/lifecycle-viewmodel/LICENSE.txt`
- `androidx.lifecycle:lifecycle-viewmodel-compose-android:2.11.0`: `lifecycle-viewmodel-compose.aar!META-INF/androidx/lifecycle/lifecycle-viewmodel-compose/LICENSE.txt`
- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0`: `lifecycle-viewmodel-ktx-2.11.0.aar!META-INF/androidx/lifecycle/lifecycle-viewmodel-ktx/LICENSE.txt`
- `androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.11.0`: `lifecycle-viewmodel-savedstate.aar!META-INF/androidx/lifecycle/lifecycle-viewmodel-savedstate/LICENSE.txt`
- `androidx.lifecycle:lifecycle-viewmodel:2.11.0`: `lifecycle-viewmodel-2.11.0.aar!META-INF/androidx/lifecycle/lifecycle-viewmodel/LICENSE.txt`
- `androidx.navigation:navigation-common-android:2.9.8`: `navigation-common-release.aar!META-INF/androidx/navigation/navigation-common/LICENSE.txt`
- `androidx.navigation:navigation-compose-android:2.9.8`: `navigation-compose-release.aar!META-INF/androidx/navigation/navigation-compose/LICENSE.txt`
- `androidx.navigation:navigation-runtime-android:2.9.8`: `navigation-runtime-release.aar!META-INF/androidx/navigation/navigation-runtime/LICENSE.txt`
- `androidx.navigationevent:navigationevent-android:1.0.0`: `navigationevent.aar!META-INF/androidx/navigationevent/navigationevent/LICENSE.txt`
- `androidx.navigationevent:navigationevent-compose-android:1.0.0`: `navigationevent-compose.aar!META-INF/androidx/navigationevent/navigationevent-compose/LICENSE.txt`
- `androidx.profileinstaller:profileinstaller:1.4.0`: `profileinstaller-1.4.0.aar!META-INF/androidx/profileinstaller/profileinstaller/LICENSE.txt`
- `androidx.savedstate:savedstate-android:1.4.0`: `savedstate.aar!META-INF/androidx/savedstate/savedstate/LICENSE.txt`
- `androidx.savedstate:savedstate-compose-android:1.4.0`: `savedstate-compose.aar!META-INF/androidx/savedstate/savedstate-compose/LICENSE.txt`
- `androidx.savedstate:savedstate-ktx:1.4.0`: `savedstate-ktx-1.4.0.aar!META-INF/androidx/savedstate/savedstate-ktx/LICENSE.txt`
- `androidx.security:security-crypto:1.1.0`: `security-crypto-1.1.0.aar!META-INF/androidx/security/security-crypto/LICENSE.txt`
- `androidx.startup:startup-runtime:1.2.0`: `startup-runtime-1.2.0.aar!META-INF/androidx/startup/startup-runtime/LICENSE.txt`
- `androidx.tracing:tracing-android:1.3.0`: `tracing.aar!META-INF/androidx/tracing/tracing/LICENSE.txt`
- `androidx.tracing:tracing-ktx:1.3.0`: `tracing-ktx-1.3.0.aar!META-INF/androidx/tracing/tracing-ktx/LICENSE.txt`
- `androidx.transition:transition:1.6.0`: `transition-1.6.0.aar!META-INF/androidx/transition/transition/LICENSE.txt`
- `androidx.window:window-core-android:1.5.0`: `window-core.aar!META-INF/androidx/window/window-core/LICENSE.txt`
- `androidx.window:window:1.5.0`: `window-1.5.0.aar!META-INF/androidx/window/window/LICENSE.txt`
- `androidx.work:work-runtime-ktx:2.10.1`: `work-runtime-ktx-2.10.1.aar!META-INF/androidx/work/work-runtime-ktx/LICENSE.txt`
- `androidx.work:work-runtime:2.10.1`: `work-runtime-2.10.1.aar!META-INF/androidx/work/work-runtime/LICENSE.txt`
- `jakarta.inject:jakarta.inject-api:2.0.1`: `jakarta.inject-api-2.0.1.jar!META-INF/LICENSE.txt`, `jakarta.inject-api-2.0.1.jar!META-INF/NOTICE.md`
- `org.bouncycastle:bcprov-jdk18on:1.85`: `bcprov-jdk18on-1.85.jar!META-INF/LICENSE.md`
- `org.nanohttpd:nanohttpd:2.3.1`: `nanohttpd-2.3.1.jar!LICENSE.txt`

Other carried files:
- `no.nordicsemi.android:ble:2.11.0`: none. `com.google.code.findbugs:jsr305:3.0.2`: none.
- `org.nanohttpd:nanohttpd:2.3.1`: `nanohttpd-2.3.1.jar!LICENSE.txt`.
- Play services, ML Kit, odml `image`, Firebase components, datatransport AARs: root-level `third_party_licenses.txt` and `third_party_licenses.json` (Google oss-licenses format; `firebase-components` has an empty `{}`). E.g. `play-services-location-21.4.0.aar` lists Animal Sniffer (MIT), Checker Framework, Dagger, Error Prone, Guava, J2ObjC, JSR 305, Protobuf, Kotlin. `barcode-scanning-17.3.0.aar` also lists native components: Abseil, TensorFlow, TensorFlow Lite Support, OpenCV, Eigen 3, FlatBuffers, ICU4C, libjpeg-turbo, libyuv, RE2, XNNPACK, zlib, Zstandard and others. The app has both the bundled `barcode-scanning` and the unbundled `play-services-mlkit-barcode-scanning`. UNKNOWN whether these files are merged into the release APK. NEEDS LAWYER on whether their contents must be shown.

## 5. Arti and Rust crates (`libarti_android.so`)

Source: `tools/arti-build/Cargo.lock`: 509 `[[package]]` entries, 465 from crates.io and 44 path packages. Arti `arti-v1.9.0`, commit `ba421ece1acb47e5c554c2bfa043cc9156451bab` (`TOOLCHAIN.env`); `tor-*` crates are 0.38.0 in the lock. cargo is not installed. Each of the 465 registry crates was queried at `https://crates.io/api/v1/crates/{name}/{version}` (field `version.license`) on 2026-10-08, one request per 1.1 s, User-Agent `NearBird-licence-audit (read-only)`. Cache: `docs/legal/rust-crate-licences.json` (`name@version`; path packages as `path:name@version`). All 465 returned a licence; no errors remain.

### Counts, normalised (legacy `/` converted to `OR`, alternatives sorted)
| SPDX expression | Crates |
|---|---|
| `Apache-2.0 OR MIT` | 314 |
| `MIT` | 94 |
| `Apache-2.0` | 8 |
| `Apache-2.0 OR MIT OR Zlib` | 7 |
| `MIT OR Unlicense` | 6 |
| `Apache-2.0 OR Apache-2.0 WITH LLVM-exception OR MIT` | 6 |
| `ISC` | 5 |
| `BSD-3-Clause` | 4 |
| `Unicode-3.0` | 3 |
| `Zlib` | 2 |
| `Apache-2.0 OR BSD-3-Clause OR MIT` | 2 |
| `Apache-2.0 OR LGPL-2.1-or-later OR MIT` | 2 |
| `Apache-2.0 OR BSD-2-Clause OR MIT` | 2 |
| `0BSD OR Apache-2.0 OR MIT` | 1 |
| `Unlicense` | 1 |
| `BSD-2-Clause` | 1 |
| `Apache-2.0 OR BSD-1-Clause OR MIT` | 1 |
| `CC0-1.0` | 1 |
| `MPL-2.0` | 1 |
| `LGPL-3.0-or-later OR MPL-2.0` | 1 |
| `Apache-2.0 AND ISC` | 1 |
| `Apache-2.0 OR ISC OR MIT` | 1 |
| `(MIT OR Apache-2.0) AND Unicode-3.0` | 1 |

### Counts, raw crates.io strings
| Raw string | Crates |
|---|---|
| `MIT OR Apache-2.0` | 236 |
| `MIT` | 94 |
| `Apache-2.0 OR MIT` | 40 |
| `MIT/Apache-2.0` | 34 |
| `Apache-2.0` | 8 |
| `Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT` | 6 |
| `ISC` | 5 |
| `Unlicense OR MIT` | 4 |
| `Zlib OR Apache-2.0 OR MIT` | 4 |
| `BSD-3-Clause` | 4 |
| `Apache-2.0/MIT` | 3 |
| `Unicode-3.0` | 3 |
| `Zlib` | 2 |
| `BSD-3-Clause OR MIT OR Apache-2.0` | 2 |
| `MIT OR Apache-2.0 OR LGPL-2.1-or-later` | 2 |
| `Unlicense/MIT` | 2 |
| `BSD-2-Clause OR Apache-2.0 OR MIT` | 2 |
| `0BSD OR MIT OR Apache-2.0` | 1 |
| `Unlicense` | 1 |
| `BSD-2-Clause` | 1 |
| `MIT OR Apache-2.0 OR BSD-1-Clause` | 1 |
| `Apache-2.0 / MIT` | 1 |
| `MIT OR Zlib OR Apache-2.0` | 1 |
| `CC0-1.0` | 1 |
| `MPL-2.0` | 1 |
| `LGPL-3.0-or-later OR MPL-2.0` | 1 |
| `Apache-2.0 AND ISC` | 1 |
| `Apache-2.0 OR ISC OR MIT` | 1 |
| `MIT OR Apache-2.0 OR Zlib` | 1 |
| `(MIT OR Apache-2.0) AND Unicode-3.0` | 1 |
| `Zlib OR MIT OR Apache-2.0` | 1 |

### Flags
| Crate | Licence | Why flagged | Linked in Android build? | Status |
|---|---|---|---|---|
| `ring` 0.17.14 | `Apache-2.0 AND ISC` | Mixed licence. https://github.com/briansmith/ring/blob/main/LICENSE (checked 2026-10-08): new code ISC; BoringSSL-derived code Apache-2.0 or ISC per file; `once_cell` polyfill Apache/MIT. Older ring versions also carried OpenSSL-style terms; the current LICENSE text read did not mention them, so not confirmed either way. | Yes (the `.so` contains `ring-0.17`; used by `rustls`, `rustls-webpki`) | NEEDS LAWYER. ISC and Apache-2.0 are GPLv3-compatible; carry the texts. |
| `option-ext` 0.2.0 | `MPL-2.0` | File-level copyleft | Probably (via `dirs-sys`); not found as a string in the stripped `.so`, so UNKNOWN | PASS with obligations (below) |
| `priority-queue` 2.7.0 | `LGPL-3.0-or-later OR MPL-2.0` | Offers LGPL; MPL-2.0 option avoids relinking terms | Only via `tor-rtmock` (test mocks); not found in the `.so`. Likely not linked | PASS (elect MPL-2.0 if linked) |
| `r-efi` 5.3.0, 6.0.0 | `MIT OR Apache-2.0 OR LGPL-2.1-or-later` | Offers LGPL as an option | UEFI-only, via `getrandom`; not used on Android | PASS (elect MIT/Apache-2.0) |
| `fiat-crypto` 0.2.9 | `MIT OR Apache-2.0 OR BSD-1-Clause` | BSD-1-Clause not on allow-list; one of three alternatives | Yes (via `curve25519-dalek`) | PASS (elect MIT/Apache-2.0) |
| `adler2` 2.0.1 | `0BSD OR MIT OR Apache-2.0` | 0BSD not on allow-list; permissive | Yes (via `miniz_oxide`) | PASS |
| `openssl` 0.10.81 (`Apache-2.0`), `native-tls` | Apache-2.0 | `tor-rtcompat` lists `native-tls` in the lock graph but `Cargo.toml` selects `rustls`. If the OpenSSL C library were linked, its own licence would apply. The `.so` has no `native_tls` or `openssl-0.10` strings | Probably not (UNKNOWN without `cargo tree`) | UNKNOWN. Ask the Chief to run `cargo tree -i openssl` in the container build. |
| `libsqlite3-sys` 0.35.0, `rusqlite` 0.37.0 | MIT | Bundles SQLite (public domain) via `static-sqlite` | Yes | PASS |
| `curve25519-dalek` 4.1.3, `ed25519-dalek` 2.2.0, `subtle` 2.6.1 | BSD-3-Clause | Reproduce copyright and text in binaries | Yes | PASS (attribution) |

All other registry crates fall inside MIT, Apache-2.0, BSD-2/3-Clause, ISC, Zlib, Unicode-3.0, CC0-1.0, Unlicense or OR/AND combinations of them. None is GPL-only, AGPL, BSL, SSPL, OpenSSL-licence, or missing a licence.

**MPL-2.0 file-level obligations:** MPL-2.0 (https://www.mozilla.org/en-US/MPL/2.0/) requires MPL-covered files to stay under MPL-2.0, their source to be available, and licence notices to be kept. Section 3.3 allows combining with GPLv3 code as a "Secondary License". NearBird does not modify `option-ext`; the obligation is to keep the notice and a pointer to the source (crates.io plus `Cargo.lock`). The OkHttp Public Suffix List (MPL-2.0) is the same kind of case.

### Path packages (no crates.io data in the lock)
44 packages have no `source`: 43 are Arti workspace crates (`arti-client`, `tor-*`, `fs-mistrust`, `caret`, `safelog`, `retry-error`, `slotmap-careful`, `oneshot-fused-workaround`), and the wrapper `arti-android-wrapper` 1.9.0.
- The 43 Arti crates: crates.io reports `MIT OR Apache-2.0` for the same names and versions (cached under `path:` keys). Live check: newest `arti-client` 0.47.0 is `MIT OR Apache-2.0`, repo https://gitlab.torproject.org/tpo/core/arti.git/ . The Arti LICENSE files on GitLab returned HTTP 403, so the primary files were NOT read: UNVERIFIED against the repo's own LICENSE-MIT/LICENSE-APACHE.
- `arti-android-wrapper`: not on crates.io (404); its `tools/arti-build/Cargo.toml` has no `license` field. It is NearBird code (GPLv3). Recommend adding a `license` field matching `LICENSE.md` (a decision for the Chief; not changed here).

## 6. Non-free components

| Component | Version | Licence per POM | Status |
|---|---|---|---|
| ML Kit: `barcode-scanning`, `barcode-scanning-common`, `common`, `vision-common`, `vision-interfaces` | 17.3.0, 17.0.0, 18.11.0, 17.3.0, 16.3.0 | "ML Kit Terms of Service" (https://developers.google.com/ml-kit/terms) | NEEDS LAWYER. Proprietary; blocks F-Droid. |
| Play services: `-base` 18.9.0, `-basement` 18.9.0, `-tasks` 18.4.0, `-location` 21.4.0 | as listed | "Android Software Development Kit License" | NEEDS LAWYER. Proprietary; system-library question; blocks F-Droid. |
| `play-services-mlkit-barcode-scanning` 18.3.1 | | "ML Kit Terms of Service" (one of the two ML-Kit-terms entries in the `com.google.android.gms` row) | NEEDS LAWYER. Proprietary; blocks F-Droid. |
| `com.google.android.odml:image` 1.0.0-beta1 | | "Android Software Development Kit License" | NEEDS LAWYER. Proprietary Google SDK terms; ML Kit dependency. The current screen does not list it. |

Removing ML Kit and the Location dependency (Decision 016 plan) would open F-Droid.

## 7. Questions for the lawyer
1. JSR-305 3.0.2: the Maven POM says Apache-2.0, the upstream source repo POM says BSD-3-Clause, the FindBugs project claimed LGPL. Which notice should the licences screen carry? (All are GPLv3-compatible.)
2. May proprietary ML Kit and Play Services binaries ship with a GPLv3 app (system-library exception or otherwise)? What notice is needed?
3. Is a "public domain" dedication (NewHope, Rijndael, SQLite) sufficient in India and elsewhere, without a fallback licence grant?
4. Must the ML Kit/Play services `third_party_licenses.txt` contents be reproduced in the licences screen?
5. `ring`'s mixed licence: are the ISC and Apache-2.0 texts enough?

Prepared for review by a qualified lawyer; not legal advice.
