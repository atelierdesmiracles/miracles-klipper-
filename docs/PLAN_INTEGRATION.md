# Plan d'intégration — Miracle Klipper

## Phase 1 — Base et identité
- [x] Créer une branche dédiée `feat/octo4a-to-miracle-klipper`.
- [ ] Importer les sources de référence Octo4a en préservant les notices AGPL.
- [ ] Remplacer nom, identité visuelle, icône adaptative, textes et documentation par Miracle Klipper.
- [ ] Définir un identifiant Android distinct pour éviter d'écraser Octo4a.

## Phase 2 — Retrait d'OctoPrint
- [ ] Retirer le téléchargement de versions OctoPrint et les commandes/API propres à OctoPrint.
- [ ] Remplacer le gestionnaire de serveur par un gestionnaire Klipper/Moonraker.
- [ ] Préserver séparément USB OTG, caméra/MJPEG, SSH, journaux, service de premier plan, wakelock et détection réseau.
- [ ] Vérifier les références Kotlin/XML restantes.

## Phase 3 — Bootstrap Debian
- [ ] Publier un artefact versionné avec architecture et SHA-256.
- [ ] Vérifier checksum, architecture et espace disque côté application.
- [ ] Extraire sans traversée de chemin dans un répertoire temporaire, puis activer atomiquement.
- [ ] Adapter le démarrage des services à Android/Proot : ne pas supposer que systemd fonctionne comme PID 1.
- [ ] Mapper la connexion USB OTG au port série configuré dans printer.cfg.
- [ ] Exposer Mainsail sur le réseau local et garder Moonraker derrière un proxy, sans l'ouvrir directement à Internet.

## Phase 4 — Vérifications
- [ ] Tests de syntaxe, compilation Gradle debug/release.
- [ ] Tests sur téléphone réel : installation, OTG, caméra, SSH, veille, redémarrage et récupération après erreur.
- [ ] Tests des architectures supportées, rootées/non rootées, perte réseau et retrait USB.
- [ ] Vérifier qu'aucun écran ou chemin d'exécution OctoPrint ne subsiste.

## Phase 5 — Livraison
- [ ] Workflow GitHub Actions pour compiler APK et publier les artefacts.
- [ ] Documentation de compatibilité et de récupération.
- [ ] Release uniquement après tests réels.

## Blocage technique à traiter
Le bootstrap produit un rootfs Debian `.tar.xz` avec Klipper, Moonraker, Mainsail et des services systemd. Octo4a attend un ZIP de bootstrap Proot avec un entrypoint et une disposition spécifique à OctoPrint. Changer uniquement l'URL de téléchargement ne marcherait pas : la couche runtime et les services doivent être adaptés et testés.
