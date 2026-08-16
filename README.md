# Spend Analyzer — private build

Personal on-device expense analyzer for KSA banks and wallets (SAB, Barq, Mobily Pay, ENBD; more later).
All data stays on the phone. Database encrypted with SQLCipher; key held in the Android hardware Keystore.

## How to get the APK (no developer tools needed)

1. Create a free account at github.com.
2. Create a new **private** repository (name it anything, e.g. `spend-analyzer`).
3. Upload this whole folder's contents to the repository
   (on the repo page: *Add file → Upload files* → drag everything → *Commit*).
4. GitHub will start building automatically. Open the **Actions** tab, wait ~5 minutes
   for the green check.
5. Click the finished run → download **SpendAnalyzer-APK** at the bottom.
6. Unzip it, copy `app-debug.apk` to your phone, tap to install
   (allow "install from unknown sources" when asked).

Every future update: replace the changed files the same way — a fresh APK builds itself.

## Build stages
- **Stage 1 (this)** — encrypted database foundation, 15 tables, KSA category tree,
  your accounts and starter merchant dictionary seeded. The app opens and shows a status screen.
- Stage 2 — statement & SMS parsers (SAB, Barq, Mobily Pay, ENBD) + paste-in ingestion.
- Stage 3 — matching engine (dedup, transfer linking, refunds/cashback pairing).
- Stage 4 — the real UI: dashboard, ledger, swipe review (per the approved mockup).
- Stage 5 — notification listener + statement import flow + backup.

_Maintained via direct git push since 1.0.0-rc8 — no more manual uploads._
