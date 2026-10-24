# Analyse de compatibilité — Bootstrap Debian v0.1.0

Date de vérification : 24 octobre 2026

## Livrable constaté

La release v0.1.0 publie une seule archive :
- `miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz`
- SHA-256 annoncé par GitHub Releases : `86d001f4c6979364473616ce82fb2ae0865848b02bfb585ee3f4c5272bb67cc9`

La release v0.1.0 publiée expose le fichier rootfs de 237 450 344 octets avec le digest SHA-256 `86d001f4c6979364473616ce82fb2ae0865848b02bfb585ee3f4c5272bb67cc9`. La release n'inclut pas encore un artefact armhf ou amd64. L'intégration Android initiale doit donc refuser les appareils qui n'exposent pas l'ABI `arm64-v8a`, sans tenter une conversion implicite.

## Blocage de compatibilité à résoudre avant de démarrer les services

L'inspection de `scripts/setup-rootfs.sh` à la version publiée montre que le rootfs :
- installe `systemd` et `systemd-sysv` ;
- crée et active `klipper.service`, `moonraker.service` et `nginx.service` avec `systemctl enable` ;
- configure Moonraker sur `127.0.0.1:7125` ;
- prépare Mainsail sur Nginx port 80 ;
- laisse le MCU et les broches de l'imprimante à configurer dans `printer.cfg`.

Le rootfs est donc une base Linux avec services systemd, pas un runtime Android directement exécutable. L'import Android doit permettre de choisir/implémenter une supervision compatible avec l'environnement Android/proot. Avant tout lancement réel, il faut prouver comment ces services démarrent sans systemd PID 1 (ou utiliser un environnement où systemd est effectivement supporté).

## Risques à traiter dans le moteur d'installation

1. Vérification SHA-256 sur le fichier téléchargé, avec la valeur attendue intégrée à la définition de version.
2. Téléchargement reprenable/cancellable si possible, espace disque suffisant et messages d'erreur utiles.
3. Extraction sécurisée dans un espace privé compatible POSIX, en préservant les propriétaires/droits nécessaires mais en refusant chemins absolus, segments `..` et liens qui sortent du dossier d'extraction.
4. Échange atomique du rootfs uniquement après extraction et contrôles réussis.
5. Protection persistante de `/home/klipper/printer_data` (configuration, journaux, fichiers G-code), migration explicite et restauration.
6. Gestion du périphérique série sélectionné par USB OTG, sans remplacer son identifiant réel par un chemin supposé.
7. Pas d'accès Moonraker ouvert vers Internet : conserver une politique réseau sûre et contrôler les interfaces d'écoute.
8. Validation d'état des trois composants via journaux et contrôles de disponibilité. Ne jamais assimiler l'extraction de l'archive au succès du serveur.

## Conclusion

La release fournit le rootfs versionné attendu pour démarrer l'intégration. Elle ne résout pas à elle seule l'environnement d'exécution Android, le démarrage des services ou la connexion USB. Ces éléments doivent être implémentés et testés avant une publication APK.
