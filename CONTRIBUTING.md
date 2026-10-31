# Contributing

Thanks for helping. OpenOBD stays useful by staying small, so a few ground rules:

- **Keep it minimal.** Five screens, no clutter. Open an issue to discuss a feature before building it.
- **No network access, ever.** No `INTERNET` permission, no analytics, no crash reporting, no ads. CI fails if `INTERNET` appears in the APK.
- **No proprietary dependencies.** Everything must be FLOSS and buildable from source, so the app can be packaged for F-Droid.
- **Pin versions exactly.** Bump a dependency in `gradle/libs.versions.toml` deliberately, one change per PR, and say why.
- **Standard first.** Decoders follow SAE J1979 / J2012. Manufacturer-specific PIDs belong in the Terminal unless there is a clear, documented source.

## Pull requests

1. Run `./gradlew testDebugUnitTest assembleDebug`. CI runs the same on every PR.
2. Protocol changes need a test, ideally built from a real adapter's raw output (copy it from the Terminal with *Show polling traffic* on).
3. Test on a real car if you can. Say the car, model year, adapter, Android version, and what you checked.

By contributing, you agree that your work is licensed under the GPL-3.0.
