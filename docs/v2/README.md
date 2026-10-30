# Miracle Klipper V2

This directory defines the clean V2 architecture and its implementation gates.

## Product decisions
- Target: Android ARM64 (arm64-v8a) only for the first supported release.
- Android host does not require root.
- Linux guest: Alpine Linux ARM64, booted as a real virtual machine.
- Virtualization baseline: QEMU TCG. Hardware acceleration is optional and capability-detected.
- Container runtime: Docker Engine running inside the Linux guest, never directly on Android.
- Printer stack: Klipper + Moonraker + Mainsail.
- USB: Android USB Host permission and a controlled QEMU USB passthrough bridge.
- Web/API endpoints: loopback-only by default; explicit opt-in required for LAN access.

## Current test build

**V2 Android shell: built successfully by GitHub Actions.**

- Version: 2.0.0-test.1-debug
- Target ABI: arm64-v8a
- Minimum Android: API 26
- CI run: [Build and unit-test result](https://github.com/atelierdesmiracles/miracles-klipper-/actions/runs/41034946049)
- Artifact: `miracle-klipper-v2-arm64-debug` (temporary GitHub Actions artifact, expires 13 November 2026)
- SHA-256 of artifact archive: `a335d01230f9bd9243c3c60d7f4476593c358b9ef70e4693052806701100d7da`

To download: open the CI run above, scroll to **Artifacts**, and download `miracle-klipper-v2-arm64-debug`. Extract the ZIP and install the APK on an ARM64 Android device. This is a debug/test APK, not a production release.

The test app currently displays device ABI, Android API level, physical RAM, app-private free storage, and USB Host capability. It does not start a VM.

## Important runtime status

The Android shell and its unit tests build successfully. QEMU, guest kernel, Alpine disk image, Docker daemon, USB passthrough, Klipper, Moonraker, Mainsail and real printer control are **not yet implemented**. A successful APK build is not evidence that those runtime components work.

## Documents
- [Architecture](architecture.md)
- [Rebuild roadmap and acceptance criteria](roadmap.md)
- [Architecture decisions](decisions.md)

## Development rule
Work on rewrite/miracle-klipper-v2. Do not commit directly to main. Keep the existing application and earlier prototype branches intact until V2 passes its acceptance tests and a migration decision is made.
