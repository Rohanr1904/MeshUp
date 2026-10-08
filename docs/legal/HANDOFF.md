# Handoff → Claude Code: legal-compliance agent (2026-10-08)

**New files, untracked, written into the working copy while it was on branch `release/pin-owner-cert`. Do not commit them to that branch.**
- `docs/legal/legal-compliance.agent.md` — new specialist agent definition (Sonnet, high effort, writes only under `docs/legal/`). **Move it to `.claude/agents/legal-compliance.md`.** Remote tools cannot write into `.claude/`.
- `docs/legal/LEGAL_RESEARCH.md` — research baseline: GPLv3 + donations, FCRA/tax/GST, DPDP, ML Kit, name/domain, lawyer questions
- `docs/legal/HANDOFF.md` — this file

## Owner decisions (record as Decision 017; text in LEGAL_RESEARCH §0)
1. GPLv3 open source confirmed; anyone may see, copy and rebuild NearBird.
2. Funding through voluntary donations only; no paid features or perks.
3. No domain until device testing passes. Interim: GitHub Pages `rohanr1904.github.io/nearbird`.

## Tasks, in order
1. Branch `docs/legal-agent` from `main`. Move the agent file into `.claude/agents/`, then commit it with the two docs, plus Decision 017 in `docs/product/08_DECISIONS.md`, and add `legal-compliance` to the specialist lists in `CLAUDE.md` and `docs/product/11_AGENT_ORCHESTRATION.md`. Open a PR through the usual CI flow.
2. Run `legal-compliance` **Playbook A** (licence verification). Cover the four uncertain groups: Nordic BLE / NanoHTTPD / JSR-305; Natural Earth / NewHope "public domain"; the ~500 Arti Rust crates (per-licence counts, flag anything non-permissive); and uninspected AndroidX/Firebase NOTICE files. Fix the licences-screen entries in a separate PR.
3. Run **Playbook B** (GPLv3 release checklist). Proposals: `NOTICE` (upstream + fork date, §5(a)), `TRADEMARKS.md` (§7(e)), and release-workflow source-tarball attachment. Apply them after Chief review.
4. Privacy policy (**Playbook C**): fill these in, using the LEGAL_RESEARCH §3 recommendations:
   - policy URL: GitHub Pages
   - repository URL
   - minimum age: 18+ recommended; the owner may override
   - grievance contact line
   Leave the **contact email as a placeholder until the owner creates a dedicated mailbox**. Never use the owner's personal address.
5. Donations (**Playbook D**): draft the `FUNDING.yml` (GitHub Sponsors) and a short "Support NearBird" README section. **Do not enable or publish donation links until the owner confirms that the CA/lawyer review is done** (questions 1–2 in LEGAL_RESEARCH §5).
6. Produce `docs/legal/LAWYER_BRIEF.md`: one page with verified facts + LEGAL_RESEARCH §5 questions, for SFLC.in or an Indian IP/tech lawyer.
7. Update `docs/AI_STATUS.md` launch gates 3–4 with the results.

## Owner actions (Claude cannot do these)
- Run the IP India ESEARCH trademark search (portal needs an OTP login) for NearBird / Near Bird / Nearbyrd in classes 9, 38, 42. USPTO and WIPO were searched on 2026-10-08 (no identical mark; see `LEGAL_RESEARCH.md` section 4).
- Create a dedicated NearBird contact mailbox.
- Optionally set git to the GitHub noreply email for future commits.
- Book the lawyer/CA review using `docs/legal/LAWYER_BRIEF.md`.
- Later, after device testing passes (Decision 017): buy `nearbird.app` (available on 2026-10-08, about US$8.75 first year, about US$15/yr renewal). Do not buy nearbird.com (parked, for sale at about US$2,999). Use the project handle `nearbirdapp`; keep the repo under github.com/Rohanr1904.

Prepared for review by a qualified lawyer; not legal advice.
