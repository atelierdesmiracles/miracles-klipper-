# Miracle Klipper

Miracle Klipper is an Android-hosted Klipper project based on the existing Octo4a codebase. The goal is to run a lightweight Linux environment and the Klipper/Moonraker services on a compatible Android device, then control the printer through a local web interface.

> **Development status:** this branch is a development baseline, not a validated stable release. A successful Android build does not prove that a phone can reliably run a printer or communicate with its MCU. Hardware validation is required before production use.

## Project goals

- Preserve the proven parts of the existing Android and Linux/PRoot implementation.
- Make installation, service status, logs, and diagnostics easier to understand.
- Keep Klipper, Moonraker, serial-device access, and the web UI as clearly separated responsibilities.
- Establish repeatable builds and automated tests before changing core dependencies.
- Modernize the Miracle Klipper visual identity after the underlying workflow is reliable.

## Requirements

- Android device running Android 8.0 (API 26) or later, subject to device compatibility.
- **Root is not required.** The Linux environment runs in the app's private storage using PRoot; Android root/Magisk access is optional. USB OTG and device compatibility still apply.
- USB OTG support and a suitable USB cable/hub for the printer controller.
- A compatible printer MCU and Klipper configuration.
- A computer with Git, JDK 11, Android SDK/NDK, and internet access for development builds.

The Android application ID is `fr.atelierdesmiracles.miracleklipper`. The existing Kotlin source namespace remains `com.octo4a` for now to avoid a risky bulk package migration. This means Miracle Klipper is a separate Android app from Octo4a; it cannot be installed as an in-place update over the original Octo4a package. The Debian/PRoot bootstrap is downloaded from the Miracle Klipper release and verified before extraction; the app must not fall back to the Octo4a bootstrap builder.

## Build

From the repository root:

```bash
cd app
chmod +x gradlew
./gradlew assembleDebug
./gradlew test
```

For a release variant:

```bash
./gradlew assembleRelease
```

The CI workflow publishes an **unsigned test APK** for version tags. It is suitable for installation testing, but it is not a production release: users cannot install it as an update over an APK signed with a different key. A production release must be signed with a controlled project key stored in GitHub Actions secrets; never commit the keystore or passwords.

The build uses an older Android Gradle Plugin/Kotlin/Gradle toolchain. Use the versions defined by the project until a dedicated compatibility upgrade is performed. Do not interpret a successful compile as proof of hardware functionality.

## Validation checklist

- [ ] Debug and release APK builds complete in CI.
- [ ] Unit tests pass.
- [ ] Fresh install and first-run permissions work on a physical device.
- [ ] Debian/PRoot environment installs and survives app restarts.
- [ ] Klipper and Moonraker start, stop, and recover cleanly.
- [ ] Moonraker API and WebSocket connection work locally.
- [ ] USB serial device can be selected and opened.
- [ ] Printer status, temperatures, emergency stop, and basic commands behave correctly.
- [ ] A controlled test print completes without unexpected disconnects or overheating.
- [ ] Network access and diagnostic data handling are reviewed before public release.

## Development documents

- [Architecture and boundaries](docs/ARCHITECTURE.md)
- [Roadmap and acceptance criteria](docs/ROADMAP.md)

## Safety

A printer can move hot components and energize heaters. Keep an accessible physical power cutoff, never leave early test prints unattended, and verify thermal protection and emergency-stop behavior before attempting a print. Do not expose Moonraker directly to the public internet.

## License

See [LICENSE](LICENSE.md) for the project's license and attribution terms.
