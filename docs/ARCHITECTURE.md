# Architecture — Miracle Klipper

## Current shape

The existing application is an Android host for a Linux userland and printer services. The current codebase contains Android UI and lifecycle components, a Linux/PRoot environment, installation/bootstrap logic, Klipper and Moonraker repositories, web-interface handling, USB serial integration, and diagnostic logging.

The application ID is still `com.octo4a`; the Gradle project name is already `Miracle Klipper`. Keep the application ID unchanged until upgrade/migration requirements are understood.

## Target boundaries

1. **Android shell** — permissions, foreground/background lifecycle, notifications, settings, and user-facing status.
2. **Environment manager** — Debian/PRoot installation, versioned bootstrap assets, storage checks, and recovery.
3. **Service supervisor** — start/stop/restart and health checks for Klipper, Moonraker, and optional services.
4. **Printer connectivity** — enumerate USB serial devices, handle permissions/disconnects, and report device state.
5. **Moonraker client** — HTTP API, WebSocket connection, reconnect policy, and clear connection states.
6. **Web interface** — open the selected local UI without bypassing network or origin security.
7. **Diagnostics** — structured logs, redaction of secrets, exportable support bundle, and actionable errors.

## Non-negotiable behavior

- Installation and service operations must be idempotent where practical.
- Never report a service as healthy merely because its process was launched; use a health check.
- Treat missing USB permission, disconnected serial devices, stopped services, and unreachable Moonraker as distinct states.
- Do not swallow errors without recording a useful, redacted diagnostic.
- Do not store credentials or API keys in source control.
- Downloads and bootstrap archives must be integrity-checked before extraction or execution.
- Archive extraction must reject path traversal and unexpected destinations.
- A failed upgrade must leave the user with a recoverable state and clear instructions.
- Long-running installation and service work must not block the main UI thread.
- Avoid exposing Moonraker on an untrusted network. Prefer loopback/local network access and document any wider binding.

## Modernization policy

Modernize in small, independently buildable steps. First make the existing build reproducible and add CI. Then improve tests and service-state reporting. Only after a green baseline should Gradle, Android Gradle Plugin, Kotlin, target SDK, and libraries be upgraded. Those upgrades can alter build behavior, permission behavior, and runtime compatibility and should not be mixed into one unreviewable change.

## Verification layers

- **Static/build:** Gradle build, lint where supported, dependency and secret checks.
- **Unit:** manifest parsing, archive safety, service state mapping, retry/backoff logic.
- **Device:** first install, permissions, process lifecycle, storage pressure, reboot/restart.
- **Hardware:** USB serial attach/detach, Moonraker WebSocket, printer status, emergency stop, controlled print.
