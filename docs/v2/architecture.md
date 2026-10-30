# Miracle Klipper V2 — architecture

## 1. Scope and constraints
V2 is a native Android ARM64 application that supervises an isolated Alpine Linux ARM64 virtual machine. Docker Engine runs in that guest. Android remains responsible for lifecycle, permissions, user experience, USB discovery, diagnostics, and safe access to local web services.

The Android host is not a Linux server. Docker must not depend on Android exposing host cgroups, namespaces, OverlayFS, or privileged networking to an ordinary application process.

## 2. Logical components
    Android application (arm64-v8a, non-root)
      - UI, onboarding and device capability report
      - VM lifecycle coordinator
      - QEMU process adapter and private QMP client
      - guest readiness and health monitor
      - Android USB Host manager
      - local port-forward manager
      - persistent VM disk and backup/restore
      - embedded Mainsail WebView
                |
                | QEMU TCG (initial baseline)
                v
    Alpine Linux ARM64 guest (custom, pinned kernel)
      - init, networking and persistent data mounts
      - Docker Engine, containerd and runc
      - Klipper container
      - Moonraker container
      - Mainsail static web container/service
                |
                - USB serial device passed through by QEMU

## 3. Android host responsibilities
- Enforce ABI arm64-v8a for the first V2 release.
- Check Android API level, available memory/storage and USB host support before installation.
- Request USB permission only when a device is connected and the user chooses to attach it.
- Keep QMP and management sockets in app-private storage with restrictive permissions.
- Bind forwarded Moonraker and Mainsail services to loopback by default.
- Use a foreground service with a persistent notification while the VM is running.
- Stop safely on request and handle process death and low-storage conditions.
- Never request Android root.

## 4. Guest requirements
The guest kernel must be built and version-pinned for ARM64. Its configuration must be validated and tested. Investigate at minimum:
- namespaces and required namespace types;
- cgroup v1/v2 support and required controllers;
- OverlayFS;
- bridge, veth, netfilter and NAT support;
- virtio block and network drivers;
- USB host controller support for the selected virtual controller;
- serial and console support;
- seccomp support where required by the runtime.

Docker storage driver selection must be based on the actual guest filesystem and kernel test results. Do not declare overlay2 supported solely because the kernel config contains OverlayFS.

## 5. Docker and services
Docker Engine is installed and started inside Alpine. Pin the versions of Docker Engine, containerd, runc, Compose plugin, Alpine release, kernel revision, and downloaded artifacts.

Service containers require explicit image digests, documented persistent volumes, health checks, structured logs, restart policies, least-privilege capabilities and device mappings. Do not expose the Docker socket to Android network interfaces or the WebView.

Klipper receives only the required serial device. Moonraker is the API boundary. Mainsail is the web client and communicates with Moonraker through local forwarding.

## 6. USB design
Android USB Host API remains the permission authority. The V2 USB manager must handle attach, detach, permission denial, device replacement, duplicate VID/PID devices, and reconnect.

Preferred prototype: QEMU USB host passthrough with QMP hot-plug. The design must safely bridge the Android USB handle/file descriptor into QEMU; VID/PID alone is not a unique device identity. If this path proves unreliable, evaluate a dedicated serial bridge as a separately documented fallback.

Acceptance requires physical tests with CDC ACM, CH340, CP210x, and FTDI adapters. USB passthrough is not complete from emulator-only tests.

## 7. Networking and security
- QEMU guest networking starts with user-mode NAT.
- QMP endpoints are private and never TCP-listening on all interfaces.
- Moonraker and Mainsail are loopback-only by default.
- LAN access is explicit, authenticated and reviewed.
- No wildcard CORS or default credentials.
- Validate paths used for disk images, logs, sockets, and extracted assets.
- Verify checksums/signatures for downloaded build artifacts and container images.

## 8. Persistence and recovery
Use an app-private, versioned virtual disk. Define first-boot initialization, atomic configuration updates, clean guest shutdown, backup/restore compatibility, disk-full handling, upgrade and rollback behavior, corruption detection and recovery instructions.

Guest upgrades must not destroy Klipper configuration, macros, logs, or uploaded G-code.

## 9. Performance constraints
QEMU TCG may be substantially slower than native execution. Performance is a release gate. Measure boot time, idle CPU/RAM, Klipper scheduling latency, serial responsiveness, print-time stability, thermals, and battery drain on physical ARM64 phones. A successful boot alone does not prove suitability for printer control.

## 10. Licensing and provenance
Do not copy Podroid source files into Miracle Klipper without a formal compatibility review. Podroid is an architectural reference, not a code dependency. Maintain third-party notices/SBOM for QEMU, Alpine, Linux kernel, Docker components, and bundled libraries. Preserve each component's license and source-offer obligations.
