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

## Important status
This is a rebuild workspace and architecture contract, not a working VM yet. No QEMU binary, guest kernel, Alpine disk image, Docker daemon, USB passthrough, or end-to-end printer connection is claimed as implemented.

## Documents
- [Architecture](architecture.md)
- [Rebuild roadmap and acceptance criteria](roadmap.md)
- [Architecture decisions](decisions.md)

## Development rule
Work on rewrite/miracle-klipper-v2. Do not commit directly to main. Keep the existing application and earlier prototype branches intact until V2 passes its acceptance tests and a migration decision is made.
