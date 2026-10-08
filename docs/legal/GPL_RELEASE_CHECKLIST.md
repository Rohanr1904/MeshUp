# NearBird — GPLv3 release compliance checklist (Playbook B)

> Prepared 2026-10-08 by the `legal-compliance` agent against branch `docs/legal-work` (code at `main` + docs). Evidence paths are relative to the repository root. Status: PASS / FAIL / NEEDS LAWYER / UNKNOWN.
> Prepared for review by a qualified lawyer; not legal advice.

## Result

The licence mechanics are largely in place (GPLv3 text shipped, in-app licences screen, source URL, README attribution, owner-controlled signing, sideloading possible). There is one real in-app gap (no "no warranty" / copyright line, §5(d)), no dated modification notice yet (§5(a)), and a clear leftover-branding problem: the launcher icons, the README, the Wear app name, two hard-coded UI labels, and the public release file names (`BITCHAT_*`) are still upstream "bitchat". Three proposals are in `docs/legal/proposals/`.

## Checklist

| # | Item | Status | Evidence / notes |
|---|---|---|---|
| B1 | §6(d): complete corresponding source offered with every binary release, tag ↔ commit | PASS (practical) · NEEDS LAWYER on sufficiency | `.github/workflows/release.yml` only builds and attests; it does not publish. Publication is manual (`docs/maintainer-release-guide.md`, `gh release create ... --verify-tag`). The guide relies on GitHub's automatic "Source code" archives for the tag and says not to upload a separate archive. `BUILDINFO.json` records `sourceCommit`, and `tools/reproducible-builds/verify-github-release.sh` checks the commit against the release (lines 81-84). The reproducible build is what lets a user tie binary to source. Gap: the automatic archives carry no checksum or attestation and are not controlled by the project. Proposal: `proposals/release-source-tarball.md`. |
| B1a | Source for the bundled native Tor library (Arti, `app/src/main/jniLibs/*/libarti_android.so`) | PASS · NEEDS LAWYER | Build recipe is in the repo: `tools/arti-build/` (Cargo.toml, Cargo.lock, Dockerfile, build-arti.sh, SHA256SUMS). The Arti source is fetched at a pinned tag/commit (`docs/reproducible-builds.md`, "What is pinned"). Lawyer: is a pinned upstream fetch enough as "corresponding source" for the prebuilt `.so` files? |
| B1b | Build scripts, install instructions and keys needed to build | PASS | "Corresponding Source" includes the build scripts: `gradlew`, `tools/reproducible-builds/`, `README.md` "Building". |
| B2 | `LICENSE.md` shipped | PASS | `LICENSE.md` is in the repo root (GPLv3, 29 June 2007). `app/src/main/assets/gpl-3.0.txt` is byte-identical (`cmp` reports no difference), so every APK carries the full text. |
| B3 | §5(d) Appropriate Legal Notices in the UI | FAIL (minor) · NEEDS LAWYER | `app/src/main/java/com/bitchat/android/meshup/shell/LicensesScreen.kt` shows the licence name, the "based on bitchat" line, the source URL, the full GPLv3 text and third-party entries. Missing: (a) a copyright notice line, (b) the statement that there is no warranty, (c) a line saying you may convey it under the GPL. `grep -i warranty` in `app/src/main/res/values/strings.xml` returns nothing. The full GPLv3 text includes §15-16, but the screen itself does not say it. Proposed strings are in the section below. |
| B3a | Licences screen reachable from the UI | PASS | `meshup_settings_licenses` string and `MeshUpApp.kt:105` wire `LicensesScreen`. |
| B3b | `licenses.json` correctness (for example "Public domain" labels) | OUT OF SCOPE | Playbook A (`THIRD_PARTY_LICENCES.md`), written by another agent. |
| B4 | §5(a): prominent notice that the work was modified, with a relevant date | FAIL (no notice yet) · NEEDS LAWYER | There is no NOTICE or CHANGES file. The only statements are README line 30 and the licences screen ("Based on bitchat..."), with no modification date. Git history preserves authorship (first upstream commit 2025-07-08; fork import `8d7de5e7` on 2026-10-07). Proposal: `proposals/NOTICE`, with the fork date. The lawyer should confirm that NOTICE plus git history satisfies §5(a), and whether per-file notices are needed. Note: the 308 Kotlin files carry no copyright headers (`grep -L copyright`), which is the upstream convention. |
| B5 | Upstream attribution retained | PASS (README, licences screen) · FAIL for NOTICE | README lines 30-32; string `meshup_licenses_based_on`. `LICENSE.md` is unchanged. No upstream copyright notices were found removed (upstream files have none). Add the NOTICE. |
| B5a | Licence-text statements | PASS | README (line 30) now says GPLv3 and that the "public domain" statements no longer apply. `PRIVACY_POLICY.md` has no "public domain" claim. `docs/product/09_LICENSE_NOTES.md:39` mentions public domain in an unrelated sense. |
| B6 | §6 Installation Information (User Products) | PASS (inferred) · NEEDS LAWYER | Release APKs are signed by the owner key held locally (`docs/reproducible-builds.md`: "Signing remains local"). Nothing in the manifest blocks sideloading, and a user can build the same source (`README.md` "Building") and install it with `adb install`. Android phones normally let owners install their own builds, so no signing key needs disclosing. Caveat: a self-built APK has a different signature, so it cannot update over the official one. Lawyer: confirm this is "Installation Information" enough. |
| B7 | Trademark / name policy (§7(e)) | FAIL (none yet) | No `TRADEMARKS.md`. Proposal: `proposals/TRADEMARKS.md`. |
| B8 | No upstream "bitchat" branding left in user-facing assets (wire identifiers excluded per Decision 012) | FAIL | See the table below. |

## B8 detail: leftover upstream "bitchat" branding

| Asset | Status | Evidence |
|---|---|---|
| App name (phone) | PASS | `app_name` = "NearBird" (`app/src/main/res/values/strings.xml:71`). `applicationId` = `io.github.rohanr1904.nearbird` (`app/build.gradle.kts:30`). |
| Launcher icons (`mipmap-*/ic_launcher*.png`, `drawable/ic_launcher_foreground.xml`) | FAIL | `git log 8d7de5e7..HEAD` shows 0 commits touching them, so they are the upstream icon. Decision 016 launch gate 2 lists the icon as required before release. Owner must supply a NearBird icon. |
| Wear OS app name | FAIL | `wear/src/main/res/values/strings.xml:3`: `app_name` = "bitchat". Also bitchat strings in `wear/src/main/res/drawable/ic_launcher_foreground.xml` and `values-v31/styles.xml`. The release scripts require `nearbird-wear*.apk`, so this ships. Out of v1 feature scope (Decision 014) but not out of release scope. |
| Hard-coded UI labels | FAIL | `AndroidManifest.xml:161` (`android:label="Share BitChat"`), `hotspot/HotspotActivity.kt:113` (`"Share BitChat"`), `hotspot/ApkWebServer.kt:119` (`<title>Download BitChat</title>`, shown to people who download the shared APK). |
| Permission rationale texts | FAIL | `onboarding/PermissionManager.kt:278-352` ("discover bitchat users", "Allow bitchat to ..."). The Android system dialogs show the text to the user. |
| Strings with brand in the resource key | PASS | Keys such as `share_bitchat_title` hold "NearBird" text. Only the key names mention bitchat. |
| README | FAIL | `README.md` line 1 (upstream icon image from the upstream user-attachments URL), line 3 ("bitchat for Android"), line 7 and line 9 (`bitchat.free` link), screenshot alt texts "Bitchat" (lines 21, 22), feature list "Cross-Platform ... bitchat". Replace the upstream icon and website link. Keep one factual sentence of attribution. Propose a README rewrite as a separate reviewed change (it is a licence-adjacent file, so needs the Chief's approval). |
| README screenshots (`docs/screenshots/readme-*.png`) | UNKNOWN | Unchanged since upstream. Not opened. They may show the bitchat name or logo in the UI. Owner/Chief should look. |
| Store metadata `fastlane/metadata/android/en-US/` | FAIL (stale), minor | `icon.png` is the upstream icon; `changelog/3.txt` is the upstream 2025 changelog; the descriptions are generic (no brand name). Either replace with NearBird content or remove the folder (not used while there is no F-Droid/Play listing). |
| Public release file names | FAIL | `tools/reproducible-builds/prepare-github-release.sh` renames the manifests to `BITCHAT_BUILDINFO.json`, `BITCHAT_SHA256SUMS`, `BITCHAT_SHA256SUMS.unsigned`, and `release.yml` attests `BITCHAT_*` subjects. `verify-github-release.sh` and `docs/maintainer-release-guide.md` (title "Bitchat Android", asset table still `bitchat-android-*.apk`) use the same names. These names are visible on the Releases page. They are not wire identifiers, so Decision 012 does not cover them. Renaming touches the signed-release tooling, so it is a Chief/android-engineer decision. |
| Wire identifiers (`bitchat://verify` scheme, BLE UUIDs, `com.bitchat.android` package, `bitchat_*.xml` pref files, Keystore alias `bitchat_conversation_storage_v1`) | EXCLUDED | Decision 012 / 016. Not user-facing branding. |
| Theme/class names (`Theme.BitchatAndroid`, `BitChatIcon`) | EXCLUDED | Internal. |

## Proposed in-app notice strings (B3). Not applied

Add to `app/src/main/res/values/strings.xml` and show them in `LicensesScreen.kt` under `meshup_licenses_app_license`:

```xml
<string name="meshup_licenses_copyright">Copyright: NearBird contributors and the contributors to bitchat for Android. See NOTICE in the source repository.</string>
<string name="meshup_licenses_no_warranty">NearBird comes with ABSOLUTELY NO WARRANTY, to the extent permitted by law. It is free software, and you may redistribute it under the terms of the GNU General Public License v3.0.</string>
<string name="meshup_licenses_modified">NearBird is a modified version of bitchat for Android. It was forked on 2026-10-07; changes since then are recorded in the source history.</string>
```

The exact copyright-holder wording is for the lawyer.

## Questions for the lawyer

1. Does a root `NOTICE` plus preserved git history satisfy §5(a) for a fork, or are per-file modification notices required?
2. Are GitHub's automatic source archives plus the tag a sufficient "designated place" under §6(d), or is an explicit project-controlled archive advisable? Is the pinned upstream Arti fetch enough for the prebuilt `libarti_android.so` (B1a)?
3. Is "a user can build and sideload their own APK" enough for §6 Installation Information, so the release keystore need not be published (B6)?
4. Does the licences screen need an explicit copyright line and warranty disclaimer (B3), and who is the copyright holder to name for NearBird's own changes?
5. Is the §7(e) name/logo policy in `proposals/TRADEMARKS.md` consistent with GPLv3 (it restricts only the name and logo)?
6. Do the ML Kit and Play Services Location binaries affect the above? (Playbook A. LEGAL_RESEARCH §5 Q5.)

Prepared for review by a qualified lawyer; not legal advice.
