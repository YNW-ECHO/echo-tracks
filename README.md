# Echo Tracks — your money memory

Personal Android app (offline-first). Auto-reads M-PESA + bank SMS after
you grant SMS permission. No manual entry. Shows spent + income,
per-day timeline, 1/3/6 months, custom range, all-time.

## Stack chosen: Native Kotlin + Jetpack Compose
Why: you want perfect SMS access + great UI + one personal APK.
No Flutter / Java on this machine, so we scaffold native — you open
this folder in Android Studio on Windows and press Run.

## Project structure
```
echo_tracks/
  app/src/main/java/com/echotracks/app/
    MainActivity.kt          -> entry + permission + nav
    model/EchoTransaction.kt -> unified transaction
    parser/MpesaParser.kt    -> M-PESA + bank regex parser
    rules/CategoryRules.kt   -> smart category + spend-type
    ui/theme/                -> Echo midnight + mint + amber
  sample_sms.txt             -> real-format samples for testing
  parser_test.py             -> proves parser logic today (no phone needed)
```

## The 3 upgrades you approved (built-in from day 1)
1. Smart category layer (Naivas->shopping, Bolt->fare, learn-once)
2. Real Spend vs Self-transfer vs Fees vs Loans separation
3. Top people / tills / categories grouping

## Build order (the right way)
1. Parser -> must hit 95%+ on your real SMS
2. Database (Room) + categorization
3. UI: Home / Echoes / Stats / Sources
4. Permission flow + background rescan
5. Debug APK for sideload

Next: run `python3 parser_test.py` to see parsing work now.
