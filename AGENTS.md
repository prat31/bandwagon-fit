# AGENTS.md

Guidance for AI coding agents and contributors working in this repository.

## Product

A social health app for Android: challenges, team goals and sleep pacts with friends. Planning notes,
if present, live in the git-ignored `plan/` folder.

- **Device-agnostic.** Health data comes from **Health Connect**, so any phone, watch or ring that
  writes to it works. Never name or assume a specific device or brand in code, copy or docs.
- **Android only for now.** iOS (HealthKit) may come later, so keep health reads behind
  `core/health/HealthDataSource` and business rules free of Android imports.
- **Only daily summaries leave the phone.** Raw Health Connect samples never do.

## Colour palette

The app is always dark: an **AMOLED black** canvas with a bright, playful accent set. Green drives
CTAs and active states; orange, yellow, red and blue are the supporting and category colours.

| Role | Colour | Hex |
|---|---|---|
| Primary (CTAs, active states) | Bright Green | `#4ADE80` |
| Secondary (progress, streaks) | Orange | `#FFB020` |
| Accent (social/likes, highlights) | Blue | `#38BDF8` |
| Error / destructive | Red | `#FF5252` |
| Highlight (nutrition, accents) | Yellow | `#FACC15` |
| Success / goal hit | Bright Green | `#4ADE80` (same as primary) |
| Background | AMOLED Black | `#000000` |
| Surface / cards | Near-black | `#161616` |
| Text primary | White | `#FFFFFF` |
| Text secondary | Slate | `#A1A1AA` |

### How it maps to code

- **Compose:** `core/ui/.../Theme.kt` is the single source. Material 3 roles: `primary` = Green,
  `secondary` = Orange, `tertiary` = Blue, `error` = Red, `background` = AMOLED Black,
  card containers (`surfaceContainer*`) = near-black, `onSurface` = White,
  `onSurfaceVariant` = Slate. Success has no Material slot: use
  `BandwagonTheme.extendedColors.success` / `successContainer`.
- **Window and splash:** `app/src/main/res/values{,-night}/colors.xml` and `themes.xml`. The theme
  is dark (black window) regardless of the system setting, so light mode never appears.
- **Web pages** (`web/`): keep in sync if the palette changes (not part of the app theme).

### Rules

- Never hard-code colours in screens; use `MaterialTheme.colorScheme` or `BandwagonTheme.extendedColors`.
- Dynamic (wallpaper) colour and light mode are off on purpose: the app is always AMOLED black so
  the bright accents pop and it looks the same on every device.
- Text on the bright fills (Green, Orange, Yellow, Red, Blue) is **black**, not white: white on
  these colours is below 3:1 contrast.
- Pick the colour by meaning: progress bars and streaks → secondary; reactions and avatars
  → tertiary; goal hit, pact kept, milestones reached → success; neutral chips → `surfaceVariant`.
- The Home step bar is the one gradient: red → yellow (`extendedColors.highlight`) → green over the
  thirds of the goal (`GradientProgressTrack`). Score ratings use the same scale.

## Working in the repo

`make help` lists every task. The common loop:

```bash
make emulator          # start the Android emulator (Health Connect built in)
make functions-serve   # Firebase emulators with a demo project; no Firebase account needed
make run               # build, install and launch the dev app
make seeder-run        # write fake steps, workouts and sleep into Health Connect
make fake-member CODE=ABC234 NAME=Arjun STEPS=6500   # add a second member to a group
make test functions-test functions-group-test   # group tests use their own emulator ports
make screenshot        # real emulator screen (adb screencap can lag)
```

- Dev builds use the local emulators and offer a named **test sign-in** instead of Google.
- Group membership and derived data (standings, member names) are written only by Cloud
  Functions. Clients never add members; see `firestore.rules`.
