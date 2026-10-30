# Miracle Klipper V2 — architecture decision records

## ADR-001: ARM64-only first target
**Status:** Accepted

The first V2 target is Android arm64-v8a. Other ABIs are excluded from the initial release. QEMU, guest kernel, Alpine rootfs, native bridge libraries and Docker images must align on one architecture. Multi-ABI support would multiply the initial build and test surface before the core runtime is proven.

## ADR-002: Docker Engine runs inside a Linux guest
**Status:** Accepted

Docker Engine runs inside Alpine Linux ARM64, not directly on Android. Docker requires Linux kernel facilities that an ordinary Android application cannot assume are available on the host. A guest kernel provides a controlled Linux environment. Guest kernel configuration and QEMU integration are critical dependencies.

## ADR-003: Alpine Linux guest
**Status:** Accepted for prototype; release suitability pending

Use a minimal Alpine Linux ARM64 guest for the first V2 prototype. Its small footprint and apk package manager make it suitable for a constrained guest. Package availability, Docker service integration, musl compatibility, kernel modules and storage behavior must be verified against the pinned Alpine release.

## ADR-004: QEMU TCG baseline
**Status:** Accepted for first proof of concept; performance gate open

Start with QEMU TCG. Do not make AVF or KVM a mandatory dependency. Hardware virtualization support and permissions vary across Android devices. TCG provides a baseline without assuming host KVM access. Its performance may be inadequate for reliable printer control; measure before product release. AVF may be evaluated later as an optional backend.

## ADR-005: USB passthrough is a separate milestone
**Status:** Accepted

Do not combine initial VM boot and USB implementation into one milestone. First prove VM boot and Docker independently; then implement USB host passthrough. USB permission lifecycle, QEMU hot-plug, descriptor bridging, device identity and serial driver coverage are independent failure sources. No printer-control claim before physical USB tests.

## ADR-006: no direct code reuse from Podroid without license review
**Status:** Accepted

Treat Podroid as a technical reference only. Do not copy code until a license compatibility review explicitly approves the relevant files and obligations. Maintain provenance and third-party notices for every reused component.
