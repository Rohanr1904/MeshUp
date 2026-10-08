# NearBird — Brief for legal / tax review (India)

> For an Indian IP/technology lawyer (or SFLC.in) and a Chartered Accountant. Prepared 2026-10-08 from verified repository facts.
> **Prepared for review by a qualified lawyer; not legal advice.**
> Detail and sources: `LEGAL_RESEARCH.md`, `THIRD_PARTY_LICENCES.md`, `GPL_RELEASE_CHECKLIST.md`, `PRIVACY_POLICY_REVIEW.md`, `DONATIONS.md` (same folder).

## The project in five lines
- **NearBird** is a free Android messenger that works **offline** over a Bluetooth mesh. Optional, off-by-default Internet features use Tor and public Nostr relays.
- It is a **fork of "bitchat for Android"** (permissionlesstech, **GPLv3**), forked on 2026-10-07. NearBird is distributed under **GPLv3** with full source on GitHub.
- The developer is an **individual resident in India**.
- **Distribution:** GitHub Releases plus a GitHub Pages site; no Play Store.
- **No servers.** Messages and keys stay on users' phones.
- **Funding:** voluntary donations only (GitHub Sponsors planned); no paid features or perks. **No donation link is live yet.**

## Verified facts relevant to your review
| Topic | Fact |
|---|---|
| Licence compatibility | Release classpath checked: **194 JVM artifacts + 465 Rust crates**. No GPL-incompatible or unknown licences found. |
| Mixed licences | **ring** (Apache-2.0 AND ISC); **option-ext** (MPL-2.0, file-level); **JSR-305** (POM says Apache-2.0, repository says BSD-3, project once said LGPL) |
| Proprietary components | **Google ML Kit barcode scanning** and **Play Services (base, location)** are shipped in the APK. Both are proprietary, and they block F-Droid. ML Kit sends usage/performance metrics to Google; the privacy policy discloses this. |
| Public-domain code | NewHope port, RijndaelAES and SQLite are marked public domain, with no fallback licence |
| GPLv3 mechanics | `LICENSE.md` and the full GPLv3 text are in the app (licences screen). Source is tagged on GitHub. Releases are reproducible (byte-identical double builds). The release signing key is held privately. Users can build and sideload their own APK. |
| Personal data | Stored only on the device, encrypted (Android Keystore); excluded from cloud backup. Internet is **off by default**. The developer receives no user data. |

## Questions (each answerable in a paragraph)
**GPLv3 and licensing**
1. Does a root `NOTICE` file (upstream, fork date, "modified by NearBird") plus public git history satisfy **§5(a)**? Who should be named as copyright holder for NearBird's changes?
2. Are GitHub tag archives a sufficient **§6(d)** "designated place", or should each release attach its own attested source archive? Is a pinned upstream fetch enough for the prebuilt Arti (Tor) native library?
3. Since users can build and sideload their own APK, does **§6 "Installation Information"** require publishing the signing key? We think not.
4. May the **proprietary ML Kit / Play Services** binaries be distributed with GPLv3 code (system-library exception or otherwise)? Must their `third_party_licenses` contents be shown in-app?
5. Is linking to the MIT/BSD/Apache texts enough for binary distribution, or must local copies be embedded? Which JSR-305 notice governs? Are the **public-domain** dedications (NewHope, Rijndael) effective in India without a fallback licence?

**Name**

6. Is a `TRADEMARKS.md` (GPL §7(e): forks must rename) appropriate? Should **"NearBird"** be filed as an Indian trademark (classes 9, 38, 42), and before or after launch?

**Privacy (DPDP Act 2023 / Rules 2025)**

7. Is the developer a **Data Fiduciary** at all, given no servers and data stays on device, apart from ML Kit metrics and user-chosen Nostr relays? Is an **18+** minimum age in the policy appropriate and sufficient? What is the exact in-force date of the substantive obligations?

**Donations: for a CA / lawyer**

8. **FCRA:** may an individual accept foreign donations (GitHub Sponsors) for an open-source app under the general individual permission, or is it a "definite programme" needing registration/prior permission?
9. **Income tax:** are donations taxed as gifts (Income-tax Act 2025 s.92(2)(m)) or as business/professional income? What records should be kept?
10. **GST:** is registration needed when there are no perks and only a names-only thank-you list? What would make it a taxable supply?
11. **FEMA / foreign remittance** rules and the W-8BEN treaty position for USD payouts via Stripe (GitHub Sponsors).

## What we would like back
A short written opinion on 1–11, any changes to the drafts in `docs/legal/proposals/`, and a fee quote. Contact: **[NearBird contact email: dedicated mailbox, to be created]**.

---
Prepared for review by a qualified lawyer; not legal advice.
