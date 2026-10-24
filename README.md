# Miracle Klipper

Application Android basée sur le projet de référence [Octo4a](https://github.com/feelfreelinux/octo4a), dont l'objectif est d'héberger **Klipper + Moonraker + Mainsail** à partir du bootstrap Debian maintenu par Atelier des Miracles.

> **État actuel : compilation debug validée par GitHub Actions.** Les sources Android sont importées et l’APK debug est disponible comme artefact temporaire de CI. Cela valide la compilation, pas encore le fonctionnement Klipper/Moonraker sur téléphone réel. L’application reste en cours de conversion depuis Octo4a.

## Bootstrap Debian publié

La release [Miracle Klipper v0.1.0](https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/tag/v0.1.0) est disponible.

| Paramètre | Valeur |
|---|---|
| Version | `v0.1.0` |
| Architecture publiée | ARM64 (`arm64-v8a`) |
| Système | Debian 12 Bookworm |
| Archive | [Rootfs ARM64 (.tar.xz)](https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/download/v0.1.0/miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz) |
| Taille | 237 450 344 octets |\n| SHA-256 | `86d001f4c6979364473616ce82fb2ae0865848b02bfb585ee3f4c5272bb67cc9` |
| Fichier SHA-256 | [Télécharger la somme](https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/download/v0.1.0/miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz.sha256) |

Cette archive contient un rootfs Debian avec Klipper, Moonraker, Mainsail et Nginx. Ce n'est ni une image disque amorçable, ni un APK. La v0.1.0 ne fournit pas actuellement d'archive armhf ou amd64.

## Objectifs Android

- Nom de produit : **Miracle Klipper**.
- Remplacer l'identité Octo4a par le logo Miracle Klipper fourni.
- Préserver les composants Android réutilisables : USB OTG/port série, caméra et MJPEG, service de premier plan, wakelock, SSH, réseau, journaux et paramètres.
- Supprimer OctoPrint et les fonctionnalités spécifiquement liées à OctoPrint.
- Installer Klipper + Moonraker + Mainsail via le rootfs Debian publié.
- **Ne pas installer Fluidd.**

## Runtime Android PRoot

Le workflow Android télécharge les cinq composants ARM64 de PRoot Runtime v1.2.8 depuis la release épinglée, vérifie chacun par SHA-256, puis les embarque dans l'APK. Le service `MiracleKlipperService` lance le superviseur dans le rootfs Debian et conserve les journaux dans `files/miracle-klipper/logs/proot-runtime.log`. Cette intégration doit encore être validée sur un appareil réel.

Compatibilité actuelle visée : Android 8.0+ (API 26), ARM64 uniquement.

## Attention : rootfs et runtime Android

L'archive Debian seule ne suffit pas : le runtime PRoot doit être empaqueté avec l'application et les services doivent être lancés sans supposer que systemd est PID 1.

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

## Compilation actuelle

Une compilation debug antérieure a réussi avant l’intégration du runtime PRoot. La compilation de la version avec PRoot est en cours de correction ; consulter [les exécutions GitHub Actions](https://github.com/atelierdesmiracles/miracles-klipper-/actions) pour le statut actuel.

La compilation de release reste à vérifier séparément ; aucune release publique APK n’est annoncée à ce stade.

## Workflow d'import et compilation

Le workflow `Import Octo4a Android sources` est un import explicite qui doit être lancé depuis GitHub Actions lorsque le fichier de workflow est présent sur la branche par défaut. Il récupère le projet Gradle complet et conserve les notices amont. Le workflow `Miracle Klipper Android Build` compile ensuite un APK debug et publie le fichier en artefact si le projet Android est présent.

Guide : [docs/IMPORT_ANDROID_VIA_GITHUB.md](docs/IMPORT_ANDROID_VIA_GITHUB.md).

## Licence et attribution

La référence Octo4a est publiée sous licence AGPL. Toute dérivation doit conserver les notices nécessaires et respecter les obligations applicables. Consulter la licence source avant redistribution.
