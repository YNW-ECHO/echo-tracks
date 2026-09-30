# Get Echo Tracks on your phone (APK)

You have 2 ways. Easiest first.

## Option A — GitHub download (recommended, no Android Studio)
1. Create a GitHub repo, upload this `echo_tracks` folder contents, push to `main`.
2. Open repo → Actions → "Build Echo Tracks APK" → wait ~5 min → green tick.
3. Download artifact `echo-tracks-apk` → unzip → you get `app-debug.apk`.
4. Send it to your phone (WhatsApp to yourself / USB / Drive).

## Option B — Android Studio on Windows
1. Open Android Studio → Open → `C:\Users\LENOVO\Desktop\echo_tracks`.
2. Let Gradle sync (first time downloads SDK — accept licenses).
3. If it asks for missing `gradlew`, run: `gradle wrapper` once.
4. Connect phone with USB debugging ON → press Run ▶.
5. Or Build → Build App Bundle/APK → Build APK → find it in
   `app\build\outputs\apk\debug\app-debug.apk`, copy to phone.

## Install + make it work well on your phone
1. On phone: Settings → Security → allow "Install unknown apps" for Files/Chrome (this APK is yours, sideload).
2. Tap `app-debug.apk` → Install → Open Echo Tracks.
3. Set a 4+ digit PIN when asked (protects money data).
4. Tap "Allow SMS reading" → Allow → wait "Reading SMS…" (2k–8k messages, ~30s).
5. Home shows: Spent / In / Net + Saved% chip + vs-last-month chip + 14-day trend + where-it-went + tops.
6. Use filters: TODAY / 7D / ALL / 1M / 3M / 6M + M-PESA / BANK tabs + search in Echoes.
7. Keep "Hide fees, loans & reversals" ON for clean totals.
8. Tap Export CSV → find file in Downloads → opens in Excel.
9. Tips: rescan after new M-PESA SMS (↻ Rescan), keep phone storage free, don't clear app data (Room cache lives there), enable fingerprint later in lock settings.

Troubleshooting:
- "No echoes": you denied SMS — Settings → Apps → Echo Tracks → Permissions → SMS → Allow.
- Bank SMS missing: banks send from different names — tell me the sender name and I add it to the parser.
- Install blocked: enable unknown sources for the app you open the APK from.
