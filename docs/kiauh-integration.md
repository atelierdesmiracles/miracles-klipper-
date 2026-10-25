# KIAUH integration

The app bundles the KIAUH source archive supplied for this project and exposes it from the app bar as **KIAUH (essai)**. Confirming the compatibility notice opens KIAUH in the in-app Debian terminal. Its source and GPL-3.0 license are included in `app/app/src/main/assets/kiauh/kiauh-master.zip` and are extracted to the app's private Debian shared directory on first launch.

This is an experimental launcher, not a port of KIAUH's service manager. Miracle Klipper runs Debian under PRoot without systemd and supervises its own Klipper, Moonraker, and web-interface processes. KIAUH actions that create or control systemd services may fail, and components installed by KIAUH are not registered with Miracle Klipper. Use the app's Settings actions to install and operate the supported printer stack.

The app provides a scoped `sudo` compatibility wrapper only in the KIAUH terminal session. It executes commands as PRoot's simulated Debian root; it does not grant Android root access.

Upstream project: <https://github.com/dw-0/kiauh>
