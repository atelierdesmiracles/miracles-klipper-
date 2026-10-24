# Intégration du bootstrap Debian officiel — Miracle Klipper

La source runtime de Miracle Klipper est le projet [boostrap-debian-monnraker-et-klipper-](https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-), release `v0.1.0`.

## Artefact verrouillé

- Version : `v0.1.0`
- Fichier : `miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz`
- URL : https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/download/v0.1.0/miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz
- SHA-256 déclaré par la Release GitHub : `86d001f4c6979364473616ce82fb2ae0865848b02bfb585ee3f4c5272bb67cc9`
- Taille publiée : 237 450 344 octets
- Somme publiée : [miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz.sha256](https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/download/v0.1.0/miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz.sha256)

Les métadonnées de la Release GitHub sont la source de la version et du checksum. L'application doit comparer le SHA-256 calculé sur le fichier téléchargé à cette valeur avant d'extraire l'archive.

## Contenu du rootfs

Le bootstrap construit Debian 12 Bookworm ARM64, les environnements Python et sources Klipper/Moonraker, Mainsail dans `/var/www/mainsail`, Nginx, les configurations initiales et des unités systemd. Le rootfs est une archive de système de fichiers, pas un APK ni une image disque amorçable.

## Intégration Android nécessaire

Le rootfs prévoit systemd comme gestionnaire de services. Android/PRoot ne garantit pas systemd comme PID 1 : l'application ne doit donc pas supposer que `systemctl start` fonctionne. Il faut exécuter Klipper, Moonraker et le serveur Web par un superviseur adapté, en réutilisant les chemins installés du bootstrap :

- Klipper : `/opt/klipper/klippy/klippy.py`
- Environnement Klipper : `/opt/klipper-venv/bin/python`
- Moonraker : `/opt/moonraker/moonraker/moonraker.py`
- Environnement Moonraker : `/opt/moonraker-venv/bin/python`
- Configuration : `/home/klipper/printer_data/config/`
- Journaux : `/home/klipper/printer_data/logs/`
- Fichiers G-code : `/home/klipper/printer_data/gcodes/`
- Socket Klipper : `/home/klipper/printer_data/comms/klippy.sock`
- Mainsail : `/var/www/mainsail`
- Nginx : port 80 dans le rootfs, à vérifier pour l'exécution dans PRoot

## Règles d'installation

1. Télécharger la version épinglée, sans utiliser `latest`.
2. Vérifier la taille, le checksum et l'espace disponible.
3. Vérifier les chemins de l'archive contre les traversées (`..`, chemins absolus) avant extraction.
4. Extraire sous le stockage privé de l'application dans un dossier temporaire.
5. Ne pas exécuter les scripts d'installation Debian à nouveau sur Android : les paquets et composants sont déjà fournis par le rootfs.
6. Copier/rendre persistants les fichiers de configuration séparément des mises à jour du rootfs.
7. Configurer le proxy Mainsail/Moonraker pour un usage local. Ne jamais exposer une API Moonraker non protégée à Internet.
8. Ne déclarer l'environnement « prêt » qu'après vérification des processus et des endpoints HTTP.

## État

- [x] Référence de release et checksum documentés.
- [x] Chemins du bootstrap inspectés et documentés.
- [ ] Téléchargement, vérification et extraction sécurisés intégrés dans l'application Android.
- [ ] Superviseur adapté au vrai layout du rootfs.
- [ ] Intégration Mainsail/Moonraker dans WebView et tests réseau.
- [ ] Validation USB OTG / série avec imprimante réelle.
