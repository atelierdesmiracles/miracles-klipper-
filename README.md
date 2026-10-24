# Miracle Klipper

Application Android basée sur le projet de référence [Octo4a](https://github.com/feelfreelinux/octo4a), dont l'objectif est d'héberger **Klipper + Moonraker + Mainsail** à partir du bootstrap Debian maintenu par Atelier des Miracles.

> **État actuel : migration Android à effectuer.** Le dépôt contient le plan, les contrats et les workflows d'import/build. Les sources Android Octo4a ne sont pas encore importées dans cette branche ; aucun APK Miracle Klipper n'a donc été compilé ou validé.

## Bootstrap Debian publié

La release [Miracle Klipper v0.1.0](https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/tag/v0.1.0) est disponible.

| Paramètre | Valeur |
|---|---|
| Version | `v0.1.0` |
| Architecture publiée | ARM64 (`arm64-v8a`) |
| Système | Debian 12 Bookworm |
| Archive | [Rootfs ARM64 (.tar.xz)](https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/download/v0.1.0/miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz) |
| SHA-256 | `0fe5ec04fca882c8531a60169b795e6b0477229af77d6d75aedcff177e2ff56a` |
| Fichier SHA-256 | [Télécharger la somme](https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/download/v0.1.0/miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz.sha256) |

Cette archive contient un rootfs Debian avec Klipper, Moonraker, Mainsail et Nginx. Ce n'est ni une image disque amorçable, ni un APK. La v0.1.0 ne fournit pas actuellement d'archive armhf ou amd64.

## Objectifs Android

- Nom de produit : **Miracle Klipper**.
- Remplacer l'identité Octo4a par le logo Miracle Klipper fourni.
- Préserver les composants Android réutilisables : USB OTG/port série, caméra et MJPEG, service de premier plan, wakelock, SSH, réseau, journaux et paramètres.
- Supprimer OctoPrint et les fonctionnalités spécifiquement liées à OctoPrint.
- Installer Klipper + Moonraker + Mainsail via le rootfs Debian publié.
- **Ne pas installer Fluidd.**

## Attention : rootfs et runtime Android

Changer uniquement l'URL de téléchargement ne suffit pas. Il faut importer et étudier les sources Android Octo4a, supprimer le parcours d'installation OctoPrint et construire un moteur d'installation sécurisé pour le rootfs.

Le client doit :
- accepter uniquement ARM64 sur la v0.1.0 ;
- vérifier le SHA-256 avant extraction ;
- empêcher les chemins d'archive absolus, les segments `..` et les liens sortant du dossier cible ;
- conserver les données `printer_data` et prévoir un retour arrière ;
- adapter le démarrage/supervision des services : systemd ne peut pas être supposé PID 1 dans Android/PRoot ;
- relier le port série USB OTG au service Klipper ;
- vérifier l'état réel des trois composants avant d'annoncer le succès.

## Dépôts associés

- Application : https://github.com/atelierdesmiracles/miracles-klipper-
- Référence amont : https://github.com/feelfreelinux/octo4a
- Bootstrap Debian : https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/

## Workflow d'import et compilation

Le workflow `Import Octo4a Android sources` est un import explicite qui doit être lancé depuis GitHub Actions lorsque le fichier de workflow est présent sur la branche par défaut. Il récupère le projet Gradle complet et conserve les notices amont. Le workflow `Miracle Klipper Android Build` compile ensuite un APK debug et publie le fichier en artefact si le projet Android est présent.

Guide : [docs/IMPORT_ANDROID_VIA_GITHUB.md](docs/IMPORT_ANDROID_VIA_GITHUB.md).

## Licence et attribution

La référence Octo4a est publiée sous licence AGPL. Toute dérivation doit conserver les notices nécessaires et respecter les obligations applicables. Consulter la licence source avant redistribution.
