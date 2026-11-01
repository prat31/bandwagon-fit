# Contributing to Bandwagon

Thanks for helping. A few things keep the project consistent:

1. **Read [`AGENTS.md`](AGENTS.md).** It holds the product rules (device-agnostic, only daily
   summaries leave the phone), the colour palette and the everyday commands.
2. **Run it locally with no Firebase account.** `make functions-serve` starts the emulators and
   `make run` installs a dev build that signs in with a test user. See the README.
3. **Keep business rules pure.** Logic goes in `core/model` (Kotlin) or `functions/src/domain`
   (TypeScript) with unit tests, not in screens or Cloud Function handlers. Challenge rules
   change on both sides together, guarded by the golden fixtures in `shared/fixtures`.
4. **Test before you push:** `make test functions-test functions-group-test`, plus
   `make functions-rules-test` if you touch `firestore.rules`.
5. **Never commit secrets.** Firebase configs, keystores, `keystore.properties` and service
   account keys are git-ignored; keep it that way.

Bug reports are most useful with the app version, Android version, what you expected and what
happened. Please report security issues privately to the maintainer rather than in a public issue.
