# Roadmap — Miracle Klipper

This is the working plan for the `miracle-klipper-next` branch. Work is intentionally staged so that the original `main` branch remains untouched.

## Phase 0 — Baseline and safety

- [x] Create a separate development branch.
- [x] Document project scope, current status, and validation requirements.
- [x] Add a CI build path that does not require signing secrets on every push.
- [ ] Record the first clean CI result and its exact failure list if it fails.

## Phase 1 — Build and repository hygiene

- [ ] Run debug and release builds in CI.
- [ ] Run unit tests.
- [ ] Check Gradle wrapper, SDK/NDK setup, and native CMake requirements.
- [ ] Inventory old/deprecated dependencies and repositories.
- [ ] Check for accidentally committed secrets and unsafe release configuration.
- [ ] Keep dependency upgrades separate from functional changes.

**Exit criteria:** repeatable build, unit tests running, no signing secret required for ordinary CI.

## Phase 2 — Installation and Linux environment

- [ ] Validate clean installation on a supported Android device.
- [ ] Verify disk-space checks, download progress, archive integrity, and extraction safety.
- [ ] Verify cancellation, retry, and recovery after interrupted installation.
- [ ] Verify Debian environment persists across app restarts and device reboot.
- [ ] Verify logs expose actionable errors without leaking sensitive values.

**Exit criteria:** installation either succeeds completely or fails with a clear recovery path.

## Phase 3 — Services and API

- [ ] Verify Klipper and Moonraker process lifecycle.
- [ ] Add/verify health checks beyond PID/process-start checks.
- [ ] Validate Moonraker HTTP API and WebSocket reconnect behavior.
- [ ] Verify stop/restart behavior and recovery after process crashes.
- [ ] Make service states explicit in UI: stopped, starting, ready, degraded, failed.

**Exit criteria:** services report their actual health and can recover from common disconnects.

## Phase 4 — USB and printer hardware

- [ ] Validate USB OTG and permission handling.
- [ ] Verify multiple-device enumeration and stable device selection.
- [ ] Handle cable removal/reconnection without app crash.
- [ ] Validate MCU connection and printer status.
- [ ] Validate emergency stop and temperature reporting.
- [ ] Complete a supervised test print on supported hardware.

**Exit criteria:** documented hardware test matrix with device, Android version, MCU, and outcome.

## Phase 5 — UI and product identity

- [ ] Define Miracle Klipper design system and navigation.
- [ ] Improve status dashboard and error explanations.
- [ ] Improve responsive behavior for phones and tablets.
- [ ] Clarify first-run setup and permissions.
- [ ] Decide whether to retain `com.octo4a` or migrate application ID.
- [ ] Review accessibility, translations, and branding.

**Exit criteria:** UI changes preserve working installation and printer workflows.

## Phase 6 — Release

- [ ] Confirm licensing and attribution.
- [ ] Run the complete CI suite and device test matrix.
- [ ] Generate a release APK with a controlled signing key.
- [ ] Verify checksum and release notes.
- [ ] Publish a test release before stable release.
- [ ] Document upgrade and rollback paths.

## Known constraints

The project currently uses an older Android Gradle Plugin, Kotlin, Gradle wrapper, and Android dependency versions. Do not claim compatibility with current Android SDKs until a dedicated upgrade and device tests have passed. A green build is necessary but does not prove printer hardware reliability.
