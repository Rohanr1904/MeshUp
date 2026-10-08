# NearBird — donation platforms for an India-resident individual (Playbook D)

> Prepared 2026-10-08 by the `legal-compliance` agent. Read-only lookups of the platforms' own documentation; nothing was signed up for, contacted or published. All donation material stays a draft under `docs/legal/` (Decision 017): `proposals/FUNDING.yml.draft`, `proposals/README_support_section.md`.
> Prepared for review by a qualified lawyer; not legal advice.

## Result

GitHub Sponsors is the one platform whose own docs confirm an Indian resident can receive funds as an individual (India is on the supported list; no fees on personal-account sponsorships). Liberapay lists India only as "partly available", through PayPal. Open Collective needs a fiscal host, and the main open-source host (Open Source Collective) requires a repository under an organisation, not a personal account. UPI/Razorpay terms for an individual could not be confirmed from the pages I could read. Every Indian-law question (FCRA, income tax, GST, FEMA-type receipt rules) remains NEEDS LAWYER / CA.

## Platform table

Status values: CONFIRMED (platform's own doc, quoted or closely paraphrased) · UNKNOWN (page did not say).

| Item | GitHub Sponsors | Liberapay | Open Collective | UPI / Razorpay |
|---|---|---|---|---|
| Recipient eligibility, India | CONFIRMED: "Anyone who contributes to an open source project and lives in a supported region is eligible"; recipients "must reside in a supported region". India is on the supported list. 2FA required. [1][2] | CONFIRMED, partly: India is under "Barely supported territories", described as "partly available ... supported by PayPal but not by Stripe". The page does not list the limits for that tier. [5] | An individual can act as a fiscal host, or a collective can join an existing host. Which hosts accept an individual in India: UNKNOWN. [6] | UNKNOWN. The pages read did not state whether an individual or only a registered business may open an account. [8][9] |
| Fees | CONFIRMED: no fees for sponsorships from personal accounts; up to 6% (3% card + 3% GitHub) from organisations. [1] | CONFIRMED: Liberapay takes no cut; processor fees apply (about 3% Stripe, about 5% PayPal over the last year). [4] | Each host sets its own: for example Open Source Collective 10%, Open Collective Europe 6%. [6] | CONFIRMED (Razorpay): 2% platform fee on domestic cards and UPI, plus 18% GST on that fee; no set-up fee. [9] |
| KYC / identity | Stripe Connect application with accurate identity details (name, date of birth); a tax form (W-8BEN for non-US residents); residence and bank-account region must match; GitHub approval. [3][7] | UNKNOWN (page does not mention KYC; the processors, PayPal and Stripe, have their own). [4] | The host handles compliance. Host KYC: UNKNOWN. [6] | UNKNOWN (Razorpay KYC, PAN etc., not stated on the pages read). |
| Payout | Stripe Connect. Under the "recipient service agreement" for countries outside the full-service list. Paid on the 22nd of the month for the accrued balance (dates vary by country; cross-border minimums may apply); first payment about 60 days after the first sponsorship; amounts in USD, possibly converted to local currency. [7] | Immediately to the recipient's PayPal (held until withdrawn); Stripe auto-pays to a bank where Stripe is supported (not for India per [5]). [4][5] | Via the host (PayPal or Wise are listed as payout options in the docs index). Detail: UNKNOWN. [6] | Razorpay settlement times: UNKNOWN. |
| Constraints / notes | Prohibited uses include misrepresenting why you raise funds and "donation for donation" schemes. GitHub may withhold taxes it is required to deduct. Perks are optional. [7] | Limits: minimum about ₹1.10 and maximum about ₹11,000 per week to any one user. [4] | Open Source Collective: licence must be OSI/FSF-approved (GPLv3 qualifies) and the project "must be hosted under an organizational repository, not a personal account". NearBird's repository is under a personal account. [10] | The LEGAL_RESEARCH baseline treats UPI/Razorpay as the simplest route for Indian supporters. Whether Razorpay allows donation collection by an individual is UNKNOWN. |
| Fit for NearBird | Best fit; sits beside the source. Matches `FUNDING.yml.draft`. | Weak (PayPal only). | Weak for now (needs a host and an organisation repo). | Open. Needs a CA view first. |

Sources (all read 2026-10-08):

1. About GitHub Sponsors: https://docs.github.com/en/sponsors/getting-started-with-github-sponsors/about-github-sponsors
2. Same page, supported regions list; India launch: https://github.blog/changelog/2022-05-23-github-sponsors-is-now-available-in-india (cited by `LEGAL_RESEARCH.md`; not re-fetched)
3. Setting up GitHub Sponsors for your personal account: https://docs.github.com/en/sponsors/receiving-sponsorships-through-github-sponsors/setting-up-github-sponsors-for-your-personal-account
4. Liberapay FAQ: https://liberapay.com/about/faq
5. Liberapay supported countries: https://liberapay.com/about/global
6. Open Collective fiscal hosts: https://docs.opencollective.com/help/fiscal-hosts and https://docs.opencollective.com/help/fiscal-hosts/fiscal-hosts.md
7. GitHub Sponsors Additional Terms: https://docs.github.com/en/site-policy/github-terms/github-sponsors-additional-terms
8. Razorpay Payment Links: https://razorpay.com/docs/payments/payment-links/
9. Razorpay pricing: https://razorpay.com/pricing/
10. Open Source Collective acceptance criteria: https://docs.oscollective.org/getting-started/acceptance-criteria

Gaps I could not close from primary pages: the full GitHub supported-regions list (the summary I read says India is included); Razorpay account eligibility and KYC for individuals; Open Collective payout details for an individual in India; Open Source Collective's current fee. The owner or CA should check these directly before choosing a platform.

## Draft copy rules (Decision 017, playbook D)

- Voluntary, no perks, no promises, no logos, no sponsor tiers. Thank-you list of names only, with consent.
- No live links until the owner confirms the CA/lawyer review. `FUNDING.yml` must not be placed at `.github/FUNDING.yml` until then: GitHub reads that file from the default branch, so merging it would show a Sponsor button.
- GitHub's terms say perks are optional and sponsors may be offered subscriptions "in exchange for goods, services" that the developer chooses [7]. NearBird chooses none.

## Indian-law questions (all NEEDS LAWYER / CA; restated from LEGAL_RESEARCH §2 and §5)

1. **FCRA.** Does an individual developer receiving foreign donations (GitHub Sponsors pays in USD via Stripe from sponsors worldwide) fall under the general permission for individuals, or under the "definite cultural, economic, educational, religious or social programme" rule that needs registration or prior permission? Source: MHA FCRA FAQ (https://www.mha.gov.in/sites/default/files/2022-07/ForeigD-ForeigD-FCRA_FAQs_1.pdf), Q.11 (individuals generally permitted), Q.5 (definite programme), Q.6 (prohibited categories), Q.2 (payment for goods or services is not foreign contribution). The text above is the baseline's reading; I did not re-fetch the PDF in this run. NEEDS LAWYER.
2. **Income tax.** Are the receipts gifts (Income-tax Act 2025 s.92(2)(m), formerly s.56(2)(x), the ₹50,000 non-relative threshold per year; secondary source in `LEGAL_RESEARCH.md`) or business/professional income because they are tied to software work? What must be declared and recorded? NEEDS CA.
3. **GST.** Donations with no quid pro quo are, per Circular 116/35/2019, not a supply (https://www.gstcouncil.gov.in/sites/default/files/2024-06/circular-cgst-116.pdf; the circular's examples are charities). Is GST registration needed for an individual with no perks? Would a names-only thank-you list change that? Would any logo or "sponsored by" placement create a taxable supply (secondary reading in the baseline)? NEEDS CA.
4. **Foreign-exchange receipt rules.** How is an inward remittance from a US-based payment processor to an individual's Indian bank account classified and documented (purpose code, bank requirements)? I have not researched this; I raise it only because the first three answers depend on it. NEEDS CA.
5. **Platform tax forms.** GitHub requires a W-8BEN for non-US residents [3]. Who completes it, and what is the effect of the India-US treaty? NEEDS CA.
6. **Open Collective and Razorpay.** If the owner prefers either: does the platform accept an individual recipient in India, and what are the tax consequences? NEEDS CA.

Prepared for review by a qualified lawyer; not legal advice.
