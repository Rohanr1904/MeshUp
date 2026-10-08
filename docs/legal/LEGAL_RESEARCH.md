# NearBird — Legal & Launch Research (baseline)

> Prepared 2026-10-08 by the chief engineer (cloud session) for the owner, Claude Code and the `legal-compliance` agent.
> **Prepared for review by a qualified lawyer; not legal advice.** Labels: CONFIRMED (primary source, cited) · SECONDARY (law-firm/practitioner summary) · NEEDS LAWYER · RECOMMENDED.

## 0. Owner decisions (2026-10-08) — record as Decision 017
- **Open source under GPLv3** (confirms Decision 011): anyone may see, copy and rebuild NearBird. The owner accepts this.
- **Funding: voluntary donations/support**, with no paid features.
- **Domain deferred** until the app is device-tested and working. The interim web presence is the GitHub repository + GitHub Pages.

Proposed Decision 017 text for `docs/product/08_DECISIONS.md`:
> **Decision 017 — Open source + donations; domain later.** NearBird is GPLv3 open source, funded by voluntary donations with no paid features or perks. No domain is bought before device testing passes; until then the privacy policy and download page live on GitHub Pages under the owner's GitHub account. Donation platforms and their tax treatment are reviewed by a CA/lawyer before the donate links go live.

## 1. GPLv3 + donations: compatible
- GPLv3 §4: "You may charge any price or no price for each copy that you convey." Donations are fully compatible. (CONFIRMED, https://www.gnu.org/licenses/gpl-3.0.html)
- Obligations that apply to every APK released on GitHub:
  - §6(d): offer the complete corresponding source "from a designated place". A release tag + source tarball on the same GitHub release satisfies this in practice.
  - §5(a): "prominent notices stating that you modified it, and giving a relevant date". RECOMMENDED: a `NOTICE`/`CHANGES` file naming the upstream and the fork date, plus git history. The lawyer should confirm it is sufficient.
  - §5(d): Appropriate Legal Notices in the UI. The licences screen (PR #20) covers this.
  - §6 "Installation Information": INFERRED not to require publishing signing keys, because users can sideload a self-built APK. NEEDS LAWYER confirmation.
- **Name protection:** the GPL grants no trademark rights. §7(e) lets you decline trademark rights for your name/logo. RECOMMENDED: a `TRADEMARKS.md` saying forks must rename. This stops a malicious fork from impersonating "NearBird", which matters for a security app.
- **F-Droid caveat (CONFIRMED in code):** ML Kit (barcode) and Play Services Location are proprietary Google libraries. They block inclusion in F-Droid's main repository, the natural channel for GPL apps. The planned ML Kit replacement (Decision 016) plus a non-GMS location provider would open that channel.

## 2. Donations — platforms and Indian law
| Platform | India status | Notes |
|---|---|---|
| **GitHub Sponsors** | Supported for recipients with an Indian bank account and tax residence (CONFIRMED) | No fees on sponsorships from personal accounts; up to 6% from organisations (CONFIRMED). Best fit: sits next to the source. |
| Liberapay / Open Collective / Ko-fi / Buy Me a Coffee | Varies by payout processor | `legal-compliance` to verify each platform's India payout terms before use. |
| UPI / Razorpay (domestic) | Available | Simplest for Indian supporters; KYC applies. |

**Indian law (NEEDS LAWYER/CA for all of these):**
- **FCRA (foreign donors):** MHA FAQ Q.11: "Individuals in general and a HUF are permitted to accept foreign contribution without permission", unless they fall in a prohibited category (Q.6: e.g. journalists/news publishers, government servants, legislators, political-party members). However, Q.5 says a person with a "definite cultural, economic, educational, religious or social programme" needs registration or prior permission. Whether an open-source project counts as such a programme is the key question for the lawyer. Payments for goods/services in the ordinary course of business are not foreign contribution (Q.2). (CONFIRMED source: MHA FCRA FAQ)
- **Income tax:** money received without consideration from non-relatives above ₹50,000 in a year is taxable, under s.92(2)(m) of the Income-tax Act 2025 (formerly s.56(2)(x) of the 1961 Act) (SECONDARY). Donations linked to your software work may instead be treated as business/professional income. Either way: **declare them and keep records.**
- **GST:** Circular 116/35/2019 treats donations with no quid pro quo as not a supply. Its examples are charities, though. Sponsor perks that advertise a donor's business (logo placement, "sponsored by") can become a taxable service (SECONDARY reading). RECOMMENDED: a plain "thank you" list of names only; no logos or ads until a CA approves.
- **Donation copy:** don't promise features, priority or support in return.

## 3. Privacy policy placeholders — recommendations
| Placeholder | Recommendation |
|---|---|
| Contact email | Create a **dedicated mailbox** for NearBird. Do not publish your personal address (AGENTS.md privacy rule). **Plan (RECOMMENDED, from the 2026-10-08 report, none created yet):** Proton Mail Free now (privacy-aligned, usually no phone number); a custom domain later, via Proton Mail Plus (about US$48/yr) or Zoho Mail Free (US$0). Until the owner confirms the address, it stays a `[CONTACT EMAIL]` placeholder in every doc. Note: commits already pushed to the public repo carry your personal Gmail as author. RECOMMENDED: switch git to your GitHub noreply address for future commits. |
| Policy URL / website | **GitHub Pages: `https://rohanr1904.github.io/nearbird/`** (free, HTTPS, no domain needed). It matches the applicationId `io.github.rohanr1904.nearbird`, which is valid only because you control that GitHub account. Move to a domain later with a redirect. |
| Repository URL | The public GitHub repo (consider renaming MeshUp → NearBird; GitHub keeps redirects). |
| Minimum age | **RECOMMENDED 18+ for v1.** The DPDP Act treats under-18s as children (verifiable parental consent). NearBird lets strangers nearby message you, with no moderation or reporting, so 18+ is the safer choice. Festival, campus and traveller users are mostly adults. 13+ is the common global norm; NEEDS LAWYER if you want under-18 users. |
| Grievance contact | Add a "Grievance / privacy contact" line (the same mailbox). DPDP expects a contact for data-principal queries. |

**DPDP timeline (SECONDARY, AZB & Partners):** Rules notified 13 Nov 2025. Consent-manager provisions apply from 13 Nov 2026. **Substantive obligations (notice, consent, children, rights, penalties) apply from 13 May 2027.** Open question for the lawyer: is the developer a "Data Fiduciary" at all, given that NearBird runs no servers and all personal data stays on users' devices or goes to third-party Nostr relays the user chooses? If not, the policy is a transparency document rather than a compliance obligation.

**ML Kit:** Google's ML Kit terms say input images are processed on-device and not sent to Google, but "the ML Kit APIs also send metrics about the performance and utilization of the APIs in your app to Google". They also say "You are responsible for informing users of your app about Google's processing of ML Kit metrics data." No opt-out is documented. The current policy draft already discloses this (CONFIRMED). Replacing ML Kit removes the issue and helps F-Droid.

## 4. Name and domain (checked 2026-10-08)
Sources: the first-pass RDAP/web checks, then the cowork report `NearBird_cowork_report_2026-10-08.md` (read-only browser lookups on 2026-10-08 = CONFIRMED; nothing bought, filed or signed up for).

| Check | Result |
|---|---|
| Web search "NearBird" | No messenger found; only unrelated bird-watching apps (CONFIRMED by search; not a trademark search). |
| Trademark: USPTO | No results for "nearbird", "near bird", "nearbyrd". A control query ("bluebird") worked, so the empty result is real. (CONFIRMED) |
| Trademark: WIPO Global Brand Database | No exact NEARBIRD mark; nothing in classes 9/38/42. (CONFIRMED) |
| Trademark: "NearBirds", EU 018348780, classes 25/28 | Same-sounding name but unrelated goods (clothing, toys/games). Low risk. (CONFIRMED record; the risk view is NEEDS LAWYER) |
| Trademark: "GEARBIRD", India 5948768, class 9 | Possible (visual) similarity: same class, one letter apart in appearance; sounds different. Goods description not yet checked. NEEDS LAWYER to assess. |
| Trademark: IP India ESEARCH | **OUTSTANDING (owner action).** The new portal (`tmrsearch.ipindia.gov.in/ESEARCH`) returned HTTP 503 and needs an OTP login. WIPO's database includes some Indian records, but that is no substitute. Run NEARBIRD / NEAR BIRD / NEARBYRD, phonetic + "contains", classes 9/38/42. |
| `nearbird.app` | **Available** at Porkbun: about US$8.75 first year, about US$15/yr renewal (Namecheap about 11 / 18). Cloudflare price not checked (needs login). **No purchase before device testing passes (Decision 017).** (CONFIRMED) |
| `nearbird.com` | **Parked and for sale** (Afternic / Porkbun aftermarket about US$2,999). Registered since 2007. **Do not buy.** (CONFIRMED) |
| `nearbird.org` | **Registered since 2007**, no live site. (CONFIRMED) |
| Handles | "nearbird" is **taken** on GitHub, X, Mastodon (mastodon.social) and Instagram. **Use "nearbirdapp"** as the project handle (appears free on all four; an "unavailable" result on X/Instagram can also mean a suspended account, so confirm at sign-up). The repo **stays under the `Rohanr1904` GitHub account** because applicationId `io.github.rohanr1904.nearbird` depends on it. An optional `nearbirdapp` GitHub org plus a transfer is possible later (GitHub redirects old URLs). (RECOMMENDED) |

Old registrations of the .com and .org are not a trademark conflict by themselves. A parked .com can confuse users, so `nearbird.app` (HTTPS-only by design) is the best later option.

Not completed: IP India direct search; EUIPO TMview as a separate query (WIPO data already includes EU records); GEARBIRD goods description; Cloudflare Registrar price; phone-number requirements of mailbox providers.

**Overall (not legal advice):** no direct conflict found in the US or WIPO datasets for classes 9/38/42; one possible class 9 similarity (GEARBIRD) for the lawyer; the Indian register search is still open.

## 5. Questions for the lawyer / CA (short brief)
1. Does an individual developer receiving donations (GitHub Sponsors, incl. foreign donors) for a GPLv3 app fall under FCRA §11 registration ("definite programme"), or under the general individual permission?
2. How should those donations be taxed: as gifts (s.92(2)(m)) or as business income? Is GST registration needed (and at what threshold) if there are no perks?
3. Is the developer a DPDP Data Fiduciary for an app with no servers? Is 18+ appropriate?
4. Is the GPL §5(a)/§6 compliance mechanism (NOTICE file + tagged source on each release; no signing-key disclosure) sufficient?
5. Can the ML Kit / Play Services Location binaries be distributed alongside GPLv3 code (the system-library question)?
6. Trademark: should "NearBird" be filed in India (classes 9/38), and with what timing (before or after the public launch)?
Where to find help: an Indian IP/technology lawyer. SFLC.in (Software Freedom Law Center, India) works on open-source licensing.

## Sources
- Cowork brand and legal checks (2026-10-08): `docs/legal/NearBird_cowork_report_2026-10-08.md` (trademark, domain, handle, mailbox and India legal-help lookups; includes the GitHub Sponsors sign-up steps and a draft enquiry email)
- GPLv3 text: https://www.gnu.org/licenses/gpl-3.0.html
- MHA FCRA FAQ: https://www.mha.gov.in/sites/default/files/2022-07/ForeigD-ForeigD-FCRA_FAQs_1.pdf
- GitHub Sponsors (regions, fees): https://docs.github.com/en/sponsors/getting-started-with-github-sponsors/about-github-sponsors · India launch: https://github.blog/changelog/2022-05-23-github-sponsors-is-now-available-in-india
- Income-tax Act 2025 gifts (secondary): https://www.taxscan.in/income-tax/gift-tax-under-the-new-incometax-act-2025-taxfree-gifts-and-relatives-explained-1447754
- GST Circular 116/35/2019: https://www.gstcouncil.gov.in/sites/default/files/2024-06/circular-cgst-116.pdf
- DPDP Rules 2025 timeline (secondary): https://www.azbpartners.com/bank/update-indias-digital-personal-data-protection-framework-comes-into-effect/
- ML Kit terms: https://developers.google.com/ml-kit/terms
- RDAP: https://rdap.verisign.com/com/v1/domain/nearbird.com · https://rdap.publicinterestregistry.org/rdap/domain/nearbird.org · Google Registry RDAP (nearbird.app: 404)

Prepared for review by a qualified lawyer; not legal advice.
