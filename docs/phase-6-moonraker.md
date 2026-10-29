# Phase 6 — API Moonraker

## Révision et installation

Moonraker est récupéré depuis le dépôt officiel `Arksine/moonraker` à la révision complète `9e676eba6b02661a4dfa3ec6e7ac3f3504498e6d`. Le programme compare le commit extrait au SHA demandé avant de valider l’installation. Ses dépendances Python sont installées dans un virtualenv séparé, puis vérifiées avec `pip check`, une compilation des modules Python et `moonraker.py --help`.

La procédure suit le découpage de [KIAUH](https://github.com/dw-0/kiauh) entre source, environnement Python et `printer_data` (configuration, journaux, gcodes et sockets), en utilisant les dépendances système déclarées par [Moonraker](https://github.com/Arksine/moonraker/blob/9e676eba6b02661a4dfa3ec6e7ac3f3504498e6d/scripts/system-dependencies.json). Le rootfs Debian 13 fournit déjà Python, Git, les outils de compilation et `python3-venv`; l’installation ajoute les bibliothèques d’image et de chiffrement nécessaires. Elle bloque les scripts de démarrage de paquets pendant `apt`, car Debian tourne dans PRoot et ne doit pas lancer de services système.

KIAUH installe habituellement Moonraker comme service `systemd`. Android non rooté dans PRoot n’offre pas ce gestionnaire. L’application lance donc Moonraker depuis un service Android de premier plan et surveille le processus comme Klipper.

## API, sécurité et état

- Le fichier initial `printer_data/config/moonraker.conf` est créé sans écraser une configuration existante.
- Moonraker écoute uniquement sur `127.0.0.1:7125`. Nginx sert l’interface sur `127.0.0.1:8080` et refuse tout accès distant. Les clients de confiance Moonraker et les origines CORS sont limités à la boucle locale. L’accès depuis un autre appareil du réseau n’est pas activé par défaut.
- `klippy_uds_address` vise `/var/lib/miracle/printer_data/comms/klippy.sock`, partagé avec Klipper.
- `[machine] provider: none` évite les opérations système que KIAUH confie normalement à `systemd`.
- Le démarrage attend une réponse réelle de `GET /server/info` et lit le résultat enveloppé par Moonraker dans `result`. L’application affiche séparément API active avec Klipper prêt, API active avec Klipper déconnecté, API active avec un autre état Klipper, ou API indisponible. Un processus vivant seul n’est pas considéré comme prêt.
- Les sorties Moonraker sont conservées dans `printer_data/logs/moonraker-process.log`; le journal Moonraker reste dans `printer_data/logs/moonraker.log`.

L’installation et le démarrage restent disponibles dans Réglages sous « Moonraker API ». Le tableau de bord principal démarre Klipper, Moonraker puis Nginx dans cet ordre, et ouvre l’interface sélectionnée. L’API directe et l’interface restent sur la boucle locale, comme décrit en phase 7.

## Vérifications

- Tests unitaires : SHA épinglé, réponse `/server/info` enveloppée ou directe, erreur API, configuration locale acceptée, écoute publique, client de confiance supplémentaire, section dupliquée et socket incorrecte refusés.
- Build Android : `:app:assembleDebug`.
- Sur téléphone : installer Moonraker après Klipper, vérifier `/server/info` lorsqu’il est actif, tester Klipper arrêté puis démarré, vérifier l’arrêt et la consultation des journaux. Aucun appareil Android n’est connecté à ce poste; ces essais matériels restent à faire avant de qualifier le couple Klipper–Moonraker de prêt.
