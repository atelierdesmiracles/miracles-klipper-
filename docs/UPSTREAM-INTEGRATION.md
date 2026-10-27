# Miracle Klipper — upstream integration and architecture

This document maps the upstream Klipper ecosystem to the Android-hosted Miracle Klipper implementation.

## Upstream projects and responsibilities

- **Klipper** (`Klipper3d/klipper`): printer host process, printer configuration, MCU communication and motion planning. It is installed from an official, full 40-character commit revision declared in `app/app/src/main/assets/klipper-environment.json`.
- **Moonraker** (`Arksine/moonraker`): Python HTTP/WebSocket API for Klipper. It connects to Klipper over the Unix socket in `printer_data/comms/klippy.sock`; the official full commit revision is declared in `moonraker-environment.json`.
- **Mainsail / Fluidd**: the web client for the Moonraker API. Miracle Klipper installs a pinned release archive from the official repository, validates SHA-256, and serves it from the app's managed Nginx process.
- **KlipperScreen**: separate optional touchscreen client. Its runtime must not be treated as a replacement for the web interface.

## Runtime topology on Android

```text
Android app (activity/service lifecycle)
  └── Debian 13 Trixie rootfs (PRoot)
       ├── Klipper Python process
       │    └── printer_data/comms/klippy.sock
       ├── Moonraker Python process
       │    ├── HTTP API: 127.0.0.1:7125
       │    └── Unix connection to Klipper socket
       └── Nginx process: 0.0.0.0:8080
            ├── selected static client (Mainsail or Fluidd)
            └── /websocket and HTTP API reverse proxy to Moonraker
```

The host is an Android phone, not a normal Debian machine. Services must be launched and supervised by the app rather than relying on systemd. PRoot binaries must be compiled for Android/Bionic for each supported ABI; a generic GNU/Linux ELF binary is not an Android executable merely because its architecture matches.

## Configuration and safety requirements

1. **Do not guess printer hardware settings.** Generate an explicit incomplete starter file until a verified printer profile is selected. Klipper startup stays blocked until the configuration has the required `[mcu]` and `[printer]` sections and passes validation. Users must still supply board-specific pins, serial/CAN details, kinematics, limits, heaters and sensors.
2. **Keep the API private by default.** Moonraker binds to `127.0.0.1:7125`; Nginx is the intentional HTTP entry point. Avoid exposing port 7125 directly on the LAN. The Nginx listener should permit loopback and private LAN ranges only, then deny other clients.
3. **Use one shared data layout.** Config, logs, G-code, history database, backups and communication sockets live under the app-owned `shared/printer_data`, mapped into Debian as `/var/lib/miracle/printer_data`. Klipper and Moonraker must agree on the exact socket path.
4. **Pin code and validate downloads.** Klipper and Moonraker use full official Git commit SHAs. Mainsail/Fluidd use release tags and SHA-256 digests. Never silently update to a moving branch during installation.
5. **Supervise each process.** Start dependencies in order; wait for real health checks; keep bounded logs; stop child processes on cancellation; show separate statuses for installed, starting, connected, printer-ready and failed.
6. **No root requirement for ordinary installation.** Android root is optional. USB access remains subject to Android's USB host permissions and device compatibility.
7. **No systemd or desktop assumption.** In PRoot, start Python and Nginx directly. Do not rely on services being enabled, on a full init system, or on access to Linux host devices unavailable through Android.

## Adopted repository pins

The branch's Klipper and Moonraker manifest revisions are pinned to official commits inspected during the upstream review. Mainsail release pin remains independently managed in `klipper-web-interfaces.json`; its archive and checksum must be refreshed together when upgrading.

- Klipper: https://github.com/Klipper3d/klipper
- Moonraker: https://github.com/Arksine/moonraker
- Mainsail: https://github.com/mainsail-crew/mainsail
- Klipper installation guidance: https://www.klipper3d.org/Installation.html
- Moonraker configuration: https://moonraker.readthedocs.io/en/latest/configuration/
- Mainsail features and documentation: https://docs.mainsail.xyz/

## Adaptation backlog

- Resolve and test Android-compatible PRoot bundle delivery for ARM64 and x86_64; verify ELF ABI and interpreter before activation, and replace old GNU/Linux binaries on upgrade.
- Add an explicit service coordinator that starts Moonraker, then Klipper (only after a valid printer configuration), then Nginx; expose health and failure states in the app.
- Validate Moonraker `/server/info`, Klipper readiness, and UI/API WebSocket connectivity separately.
- Add an assisted printer profile/config wizard based on upstream `config/` examples, including a provenance/source label and a warning to verify hardware-specific values.
- Add a safe update screen that previews pinned/current revisions, backs up configuration, updates one component at a time and rolls back on validation failure.
- Test installation, restart, cancellation, recovery from partial installation, USB reconnection, upload/download, print start/pause/resume/cancel, and logs on actual Android hardware.

## Licensing

The projects above are separate upstream works with their own licenses. Keep upstream notices and license files with distributed source/binaries; modifications in Miracle Klipper should remain identifiable and must not imply the upstream projects endorse Miracle Klipper.
