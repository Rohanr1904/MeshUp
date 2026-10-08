# NearBird — One-page brief for legal / tax review (India)

> For an Indian IP/technology lawyer and a Chartered Accountant. Prepared 2026-10-08 from verified repository facts and read-only lookups. Detail and sources: `LEGAL_RESEARCH.md`, `THIRD_PARTY_LICENCES.md`, `GPL_RELEASE_CHECKLIST.md`, `PRIVACY_POLICY_REVIEW.md`, `DONATIONS.md`, `proposals/`.

**NearBird** is a free Android messenger that works offline over a Bluetooth mesh (optional, off-by-default Internet features use Tor and public Nostr relays). It is a **GPLv3 fork of "bitchat for Android"** (permissionlesstech, forked 2026-10-07), by an **individual in India**, distributed via GitHub Releases and a GitHub Pages site (no Play Store). **No servers.** Funding: voluntary donations only (GitHub Sponsors planned), no paid features or perks; **no donation link is live**.

## Verified facts
| Topic | Fact |
|---|---|
| Licence compatibility | Release classpath checked: **194 JVM artifacts + 465 Rust crates**. No GPL-incompatible or unknown licences found. |
| Mixed licences | **ring** (Apache-2.0 AND ISC); **option-ext** (MPL-2.0, file-level); **JSR-305** (POM says Apache-2.0, repository says BSD-3, project once said LGPL) |
| Proprietary components | **Google ML Kit barcode scanning** and **Play Services (base, location)** ship in the APK. Both are proprietary and block F-Droid. ML Kit sends usage/performance metrics to Google; the privacy policy discloses this. |
| Public-domain code | NewHope port, RijndaelAES and SQLite are marked public domain, with no fallback licence |
| GPLv3 mechanics | `LICENSE.md` and the GPLv3 text are in the app (licences screen). Source is tagged on GitHub. Releases are reproducible (byte-identical double builds). The release signing key is held privately. Users can build and sideload their own APK. |
| Personal data | Stored only on the device, encrypted (Android Keystore); excluded from cloud backup. Internet is **off by default**. The developer receives no user data. |
| Trademark search (2026-10-08, read-only) | **USPTO:** no results for "nearbird", "near bird", "nearbyrd" (control query worked). **WIPO Global Brand DB:** no exact NEARBIRD mark; nothing in classes 9/38/42. **"NearBirds"** (EU 018348780, classes 25/28): low risk. **"GEARBIRD"** (India 5948768, class 9): possible visual similarity; goods description not yet checked. **IP India ESEARCH: not done** (portal returned 503 and needs an OTP login; owner action). |

## Questions (each answerable in a paragraph)
1. **GPLv3.** (a) Does a root `NOTICE` file (upstream, fork date, "modified by NearBird") plus public git history satisfy **§5(a)**? Who should be named as copyright holder for NearBird's changes? (b) Are GitHub tag archives a sufficient **§6(d)** "designated place", or should each release attach its own attested source archive (draft in `proposals/release-source-tarball.md`)? Is a pinned upstream fetch enough for the prebuilt Arti (Tor) native library? (c) Since users can build and sideload their own APK, we think **§6 "Installation Information"** does not require publishing the signing key. Please confirm.
2. **FCRA.** May an individual receiving foreign donations (GitHub Sponsors, paid in USD) accept them under the general individual permission, or is this a "definite programme" needing registration or prior permission?
3. **Income tax, GST, foreign receipts.** Are donations taxed as gifts (Income-tax Act 2025 s.92(2)(m)) or as business/professional income, and what records are needed? Is **GST registration** needed with no perks (only a names-only thank-you list), and what would make a supply taxable? Briefly: FEMA classification of the inward remittance and the W-8BEN treaty position for the Stripe payout.
4. **DPDP Act 2023 / Rules 2025.** Is the developer a **Data Fiduciary** at all, given no servers and data staying on device (apart from ML Kit metrics and user-chosen Nostr relays)? Is an **18+** minimum age in the policy appropriate and sufficient? Please confirm the in-force dates (our reading: consent-manager provisions 13 Nov 2026, substantive obligations 13 May 2027).
5. **Proprietary and third-party components.** May the **ML Kit / Play Services** binaries be distributed with GPLv3 code (system-library exception or otherwise), and must their `third_party_licenses` contents be shown in-app? Related notices: which **JSR-305** notice governs; is linking to the MIT/BSD/Apache texts enough or must local copies be embedded (we propose one local copy of each text in app assets, see `LICENSES_SCREEN_DIFF.md`); are the **public-domain** dedications (NewHope, Rijndael) effective in India without a fallback licence?
6. **Trademark.** Is `proposals/TRADEMARKS.md` (GPLv3 §7(e): forks must rename) appropriate, and does it conflict with GPLv3 §7? Should **"NearBird"** be filed as an Indian trademark (classes 9, 38, 42), and before or after launch? Please assess **GEARBIRD 5948768** (class 9) and the outstanding IP India search.

## What we would like back
A short written opinion on 1-6, any changes to the drafts in `docs/legal/proposals/`, and a **fixed-fee quote**; tell us if 2-3 are better handled by a CA. **Suggested routes:** **SFLC.in** (public address mail@sflc.in; an Indian free-software legal society, ask whether they take an individual developer's questions) for 1 and 5; an IP lawyer for 6; a practising **Chartered Accountant found via ICAI CA Connect** (ask for FCRA and foreign-inward-remittance experience) for 2-3. These are options, not endorsements; no one has been contacted.

Contact: **[CONTACT EMAIL]**

---
Prepared for review by a qualified lawyer; not legal advice.
