# Phase 2 — QEMU integration boundary

## Implemented in this increment

- A deterministic QEMU command builder for the ARM64 `virt` machine.
- TCG multi-threaded emulation as the baseline; no KVM/host-root assumption.
- VirtIO block and network devices for the guest.
- A private Unix QMP socket path and serial log target.
- Preflight checks for the QEMU executable, kernel, initramfs, disk image, memory, vCPU count, and unsafe socket paths.
- A process controller with start/stop handling and explicit error results.
- JVM tests for missing artifacts, command generation, path validation, and resource bounds.

## Not yet implemented or proven

This code is an integration layer, not a bundled emulator. The repository still needs:
1. A QEMU `qemu-system-aarch64` build for Android Bionic `arm64-v8a`, including its runtime shared libraries and license notices.
2. A pinned Alpine ARM64 kernel built for QEMU `virt`, with PL011 console, initramfs, and VirtIO drivers.
3. A reproducible initramfs/rootfs assembly process and checksums.
4. QMP client and guest readiness protocol.
5. Android foreground service, persistent disk creation, and safe app lifecycle integration.
6. Physical-device boot tests.

The controller must not be connected to the app's Start button until the binary and guest artifacts are bundled or safely installed and their checksums verified.

## Boot command design

The generated command uses:
- `-machine virt`, `-accel tcg,thread=multi`, `-cpu max`;
- ARM64 kernel and initramfs boot;
- `console=ttyAMA0,115200` as the default serial console;
- a qcow2 VirtIO block disk;
- user-mode NAT and VirtIO networking;
- headless operation and a private Unix QMP socket.

The Alpine kernel/initramfs must explicitly support these devices. The generated command alone does not establish that the guest can boot.

## Validation

Run:

```bash
gradle -p v2/android testDebugUnitTest
gradle -p v2/android assembleDebug
```

The phase-2 acceptance gate remains a successful Alpine readiness handshake on a physical ARM64 Android phone, followed by persistence and clean-shutdown tests.
