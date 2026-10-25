# Phase 4 — Environnement Linux reproductible

**État :** installateur Debian intégré dans l’application; validation sur le téléphone requise avant de considérer l’environnement prêt pour Klipper.

## Décision

- Le bootstrap Octo4a existant reste réservé au serveur historique. Il est téléchargé depuis les dernières versions amont choisies dans l’application; il ne constitue donc pas la base reproductible des futurs services Klipper.
- Le constructeur amont documente un rootfs Alpine, tandis que `bootstrap-builder/build_bootstrap.sh` dans ce dépôt construit un arbre Termux `apt/dpkg` et les scripts historiques appellent `apk`. Cette divergence est consignée; le nouvel environnement Klipper reste isolé sous Debian.
- Le nouvel environnement cible Debian 13 « trixie », ARM64 (`arm64` dans Debian, ABI `arm64-v8a` côté Android), dans un rootfs séparé et exécuté sous PRoot. Il ne requiert pas le root Android. Le root simulé dans PRoot ne donne pas les privilèges du noyau Android.
- Le rootfs candidat est l’archive Debian ARM64 épinglée du projet Termux PRoot-Distro, avec son SHA-256 ([métadonnées du plugin Debian](https://raw.githubusercontent.com/termux/proot-distro/v4.29.0/distro-plugins/debian.sh)). Le manifeste [`linux-environment.json`](../app/app/src/main/assets/linux-environment.json) fixe l’URL et l’empreinte; aucune archive volumineuse n’est intégrée au dépôt.
- Les index de paquets sont destinés à être lus depuis un instantané Debian daté afin de reproduire les mêmes versions. La date de l’instantané doit être mise à jour intentionnellement, pas remplacée par `stable`, `latest` ou une branche Git au moment de l’installation.

Debian 13 est la version stable actuelle au 21 octobre 2026 et prend en charge ARM64. [Debian Releases](https://www.debian.org/releases/). L’instantané Debian devra être configuré avec `trixie` et `trixie-security` et `check-valid-until=no`, comme le prévoit le service de snapshots Debian. [Debian Snapshot](https://snapshot.debian.org/)

## Comparaison des environnements

| Environnement | Gestionnaire | Usage prévu | Décision |
| --- | --- | --- | --- |
| Bootstrap Octo4a amont (Alpine) | `apk` | Serveur OctoPrint historique et compatibilité de l’application d’origine. | Le conserver tel quel pendant la transition; les versions proposées par l’application sont encore choisies dynamiquement. |
| Arbre du constructeur local | Termux `apt/dpkg` | Compilation du bootstrap local. | Ne pas l’utiliser comme preuve du système présent dans les archives amont; son modèle diffère des scripts `apk`. |
| Rootfs Debian ARM64 | `apt` | Futurs composants Klipper/Moonraker. | Rootfs séparé, archive épinglée par SHA-256 et dépôts datés. |

Le constructeur amont décrit le bootstrap Alpine dans son [README](https://github.com/feelfreelinux/octo4a-bootstrap-builder); le manifeste local confirme sa propre archive Debian issue du [plugin PRoot-Distro](https://raw.githubusercontent.com/termux/proot-distro/v4.29.0/distro-plugins/debian.sh). Les commandes `apk` et `apt` ne seront jamais mélangées entre ces deux rootfs.

## Rapport des dépendances

| Groupe | Paquets Debian prévus | Rôle |
| --- | --- | --- |
| Base | `ca-certificates`, `curl`, `git` | TLS, téléchargement et récupération de sources épinglées. |
| Python et compilation | `python3`, `python3-venv`, `python3-dev`, `python3-pip`, `build-essential`, `libffi-dev`, `libncurses-dev`, `pkg-config` | Environnements Python isolés, extensions natives et compilation. |
| Firmware MCU Klipper | `avrdude`, `gcc-avr`, `binutils-avr`, `avr-libc`, `stm32flash`, `gcc-arm-none-eabi`, `binutils-arm-none-eabi`, `libnewlib-arm-none-eabi` | Compilation et transfert du firmware des cartes prises en charge. |
| Interface graphique | Aucun | KlipperScreen reste différé; le premier écran utilisera le WebView Android. |

L’application propose maintenant dans les réglages l’installation facultative du rootfs et de ces paquets. Elle vérifie l’ABI ARM64, l’espace libre, l’empreinte SHA-256 de l’archive, puis extrait le système dans une zone temporaire et installe les paquets par PRoot sans privilèges root Android. Les sources apt utilisent l’instantané daté du manifeste. Les sorties sont conservées dans le journal privé de l’application. Klipper et Moonraker ne sont pas présents dans cette phase. Moonraker documente Debian comme environnement principal et exige au minimum Python 3.10 pour son paquet actuel; cette exigence sera contrôlée à l’exécution avant l’installation de la phase 5. [Installation Moonraker](https://moonraker.readthedocs.io/en/latest/installation/)

Les fichiers de remplacement des liens matériels simulés par PRoot (`link2symlink`) sont stockés dans `.l2s` à l’intérieur du rootfs Debian. Cette disposition suit PRoot-Distro et évite que `dpkg` perde ses fichiers temporaires `.dpkg-new` pendant l’application des métadonnées sous Android.

Les commandes exécutées dans PRoot fixent `TMPDIR`, `TMP` et `TEMP` à `/tmp` du rootfs. Android transmet sinon son propre chemin de cache, qui n’existe pas dans Debian et faisait échouer `mktemp` pendant la configuration de `ca-certificates`.

L’installation devra rester distincte des commandes Alpine/`apk` du bootstrap historique. Aucun `systemd` ni `sudo` ne sera nécessaire dans PRoot : Android gardera la supervision des processus, qui seront lancés au premier plan. La communication USB conservera le modèle Android sans root déjà présent (autorisation USB utilisateur et PTY partagé).

## Fiabilité du bootstrap Octo4a existant

- Les téléchargements exigent HTTPS, ont des délais d’attente et vérifient une réponse HTTP réussie.
- Les entrées ZIP sont confinées au dossier de préparation; les chemins qui en sortent sont refusés.
- L’espace libre est contrôlé avant l’extraction, par entrée connue et pendant les grosses écritures, avec une réserve de 64 Mio.
- L’extraction incomplète est supprimée à l’échec, la connexion réseau est fermée et une reprise repart d’un dossier propre.
- L’archive doit contenir un `entrypoint.sh` et le répertoire `bootstrap` avant d’être activée; la progression ne dépasse pas 99 % avant la validation.

Ces garde-fous concernent le bootstrap Octo4a existant. L’installateur Debian a son propre téléchargement contrôlé par SHA-256, une extraction temporaire, une vérification des outils de base et une activation du rootfs après installation réussie.

L’entrée « Prepare Debian 13 environment » dans les réglages lance cette préparation et affiche son état. Un espace libre d’au moins 1,5 Gio est requis. Le bootstrap Octo4a doit être initialisé au préalable, car l’application réutilise ses binaires `minitar` et `proot`.

## Vérifications et points ouverts

- Tests unitaires présents pour les protections du bootstrap et la validation du manifeste Debian.
- La compilation et les tests Android sur PC valident le code, pas l’exécution de PRoot sur le téléphone.
- L’architecture du téléphone n’a pas été fournie et aucun appareil n’est visible par ADB. Le manifeste ARM64 est donc une cible pilote, pas une compatibilité confirmée.
- À faire sur téléphone : installation du rootfs et de ses paquets, versions détectées, reprise après téléchargement interrompu, nettoyage d’une installation échouée, espace insuffisant, puis persistance après redémarrage.

**Porte de phase :** aucun service applicatif ne doit être installé tant que le rootfs Debian, ses dépendances et la persistance ne sont pas validés sur l’appareil cible.

