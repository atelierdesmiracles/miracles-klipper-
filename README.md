# Miracle Klipper

Application Android basée sur le projet de référence [Octo4a](https://github.com/feelfreelinux/octo4a), dont l'objectif est d'héberger **Klipper + Moonraker + Mainsail** à partir du bootstrap Debian maintenu par Atelier des Miracles.

> **État actuel : intégration en préparation.** Ce dépôt était initialement minimal. Le plan d'intégration est suivi dans [docs/PLAN_INTEGRATION.md](docs/PLAN_INTEGRATION.md). L'APK n'est pas déclarée fonctionnelle tant que le runtime, la compilation et les essais sur téléphone réel n'ont pas été validés.

## Objectifs

- Nom de produit : **Miracle Klipper**.
- Remplacer l'identité visuelle Octo4a par le logo Miracle Klipper fourni.
- Préserver les composants Android réutilisables : USB OTG/port série, caméra et MJPEG, service de premier plan, réveil/verrouillage d'alimentation, SSH, réseau, journaux et paramètres.
- Supprimer OctoPrint et les fonctionnalités spécifiquement liées à OctoPrint.
- Installer Klipper + Moonraker + Mainsail via le bootstrap Debian.
- Ne pas installer Fluidd.

## Dépôts associés

- Application : https://github.com/atelierdesmiracles/miracles-klipper-
- Référence amont : https://github.com/feelfreelinux/octo4a
- Bootstrap Debian : https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-

## Attention : bootstrap et runtime Android

Le bootstrap Debian génère un rootfs `.tar.xz` pour `amd64`, `arm64` ou `armhf` avec Klipper, Moonraker, Mainsail, Nginx et des services systemd. Ce rootfs n'est ni un APK, ni une image disque amorçable.

Octo4a utilise aujourd'hui un ZIP de bootstrap adapté à Proot, un entrypoint et des chemins spécifiques à OctoPrint. **Changer seulement l'adresse de téléchargement ne suffit pas.** Miracle Klipper doit vérifier l'architecture et l'intégrité de l'artefact, extraire sans risque, adapter le démarrage de services à Android/Proot (systemd ne peut pas être supposé PID 1), puis relier le port série USB à Klipper.

## Fonctions à préserver et adapter

- USB OTG et pilote série pour la carte d'imprimante ;
- caméra du téléphone et serveur MJPEG ;
- service en arrière-plan, notification persistante et gestion d'énergie ;
- SSH et terminal/journaux de diagnostic ;
- affichage de l'adresse IP et accès à l'interface Web ;
- récupération après erreur et préservation de `printer_data`.

Les dépendances OctoPrint — exécution `octoprint serve`, plugins/extensions, `config.yaml` et mises à jour OctoPrint — doivent être retirées ou remplacées par les mécanismes Klipper/Moonraker correspondants.

## Licence et attribution

La référence Octo4a est publiée sous licence AGPL. Toute dérivation doit conserver les notices nécessaires et respecter les obligations applicables. Consulter la licence du dépôt source avant diffusion.
