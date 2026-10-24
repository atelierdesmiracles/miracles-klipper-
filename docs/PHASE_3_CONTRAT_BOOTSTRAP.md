# Phase 3 — Contrat d'intégration du bootstrap Debian v0.1.0

## Release publiée

La première release est publiée :
- Version : `v0.1.0`
- Release : https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/tag/v0.1.0
- Artefact : `miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz`
- SHA-256 : `0fe5ec04fca882c8531a60169b795e6b0477229af77d6d75aedcff177e2ff56a`
- Somme publiée : https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/download/v0.1.0/miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz.sha256
- Archive : https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/download/v0.1.0/miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz

La release contient actuellement uniquement une archive ARM64 (Debian Bookworm rootfs). Ne pas proposer armhf/amd64 à partir de cette version.

## Composants et limites connues

Le rootfs fournit Klipper, Moonraker, Mainsail et Nginx. Mainsail est publié sur HTTP/80. Moonraker est configuré par défaut sur `127.0.0.1:7125`. Le paquet est un rootfs, **pas** une image disque amorçable ou un APK Android.

Le rootfs utilise des services systemd, tandis que l'application Android devra choisir et tester une stratégie de supervision adaptée à son environnement (par exemple un init systemd réellement disponible ou un lancement supervisé de services compatibles). Ne pas supposer que systemd peut devenir PID 1 dans Android/PRoot. La connexion série Klipper devra être reliée au périphérique USB OTG sélectionné dans l'application.

## Contrat client recommandé

1. Utiliser exclusivement les URL HTTPS versionnées ci-dessus pour `v0.1.0`.
2. Choisir l'artefact seulement pour l'ABI Android `arm64-v8a` et refuser les autres ABI tant qu'aucun artefact correspondant n'est publié.
3. Télécharger dans un fichier temporaire, afficher la progression et annuler proprement en cas de perte réseau.
4. Vérifier le SHA-256 attendu avant extraction.
5. Inspecter les entrées tar avant écriture ; rejeter chemins absolus, segments `..` et liens sortant du répertoire cible. Extraire dans un espace privé Linux avec propriétaire/droits compatibles.
6. Ne jamais écraser les données utilisateur `printer_data` lors d'une mise à jour. Conserver une version précédente, rendre la bascule transactionnelle, et prévoir une restauration en cas d'échec.
7. Ne pas afficher une installation réussie avant d'avoir vérifié la présence de Klipper, Moonraker et Mainsail et leur état réel.
8. N'exposer ni Moonraker ni des API d'administration sans authentification et contrôle d'accès adaptés.

## État

Ce document fixe les métadonnées de la release, mais ne signifie pas que l'application Android sait déjà installer ou exécuter ce rootfs. Le code Android doit être importé, puis son moteur d'installation OctoPrint retiré et remplacé. La compatibilité runtime et USB doit être testée sur un appareil physique avant une release APK.
