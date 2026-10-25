# Phase 5 — Installation et gestion de Klipper

## Révision contrôlée

Klipper est récupéré depuis le dépôt officiel HTTPS, à la révision complète `a08fc79fcfa342e5b95ac02a9a8b271f453523e9` du 20 octobre 2026. L’installateur vérifie le SHA retourné par Git avant d’utiliser le code. Cette révision est inscrite dans `app/src/main/assets/klipper-environment.json`; l’installateur ne suit jamais une branche flottante. Le choix reprend les instructions officielles de Klipper, qui distinguent l’hôte Linux du contrôleur MCU et exigent un `printer.cfg` adapté à l’imprimante. [Instructions d’installation Klipper](https://github.com/Klipper3d/klipper/blob/a08fc79fcfa342e5b95ac02a9a8b271f453523e9/docs/Installation.md)

Les dépendances Python sont installées dans un virtualenv Debian privé à Klipper, à partir de `scripts/klippy-requirements.txt` de cette même révision. `pip check`, la compilation des sources Python et la comparaison de `git rev-parse HEAD` valident l’installation avant que le marqueur de version soit écrit.

## Référence KIAUH

L’installation reprend les étapes Klipper gérées par [KIAUH](https://github.com/dw-0/kiauh): dépôt Klipper, virtualenv privé, dépendances Python, puis configuration des chemins `printer_data` pour `config`, `logs`, `gcodes` et `comms`. Les options de lancement gardent la même séparation entre `printer.cfg`, le journal `klippy.log` et la socket API `klippy.sock`.

KIAUH cible un système Linux de type SBC et son service Klipper standard dépend de `systemd`/`sudo` ([service KIAUH](https://github.com/dw-0/kiauh/blob/master/kiauh/components/klipper/assets/klipper.service), [prérequis KIAUH](https://github.com/dw-0/kiauh/blob/master/README.md)). Ce téléphone lance Debian dans PRoot sans root Android ni `systemd`; l’application garde donc la supervision via son service de premier plan. Après une sortie inattendue de Klipper, elle tente jusqu’à trois redémarrages avec délais de 10, 20 puis 40 secondes et affiche l’échec dans la notification si le processus continue de tomber.

## Données et processus

- Les sources et le virtualenv restent sous `/var/lib/miracle/software`; le répertoire est stocké dans le partage persistant déjà monté par PRoot.
- `printer_data/config`, `printer_data/logs`, `printer_data/gcodes` et `printer_data/comms` sont créés sans remplacer les fichiers existants.
- Si `printer.cfg` n’existe pas, l’installation crée seulement un modèle commenté. Elle ne choisit pas de cinématique, de carte ou de paramètres mécaniques pour l’utilisateur.
- Les réglages Android installent Klipper et proposent son démarrage et son arrêt. Le processus est supervisé par un service Android de premier plan. L’arrêt manuel et l’arrêt de l’application demandent la fermeture de PRoot.
- Le démarrage vérifie la présence des sections `[mcu]` et `[printer]`. La socket Klipper est prévue dans `printer_data/comms/klippy.sock`; les journaux se trouvent dans `printer_data/logs/klippy.log` et `printer_data/logs/klipper-process.log`.

Un processus Klipper vivant ne prouve pas que la carte est connectée ou que l’imprimante est prête. La liaison USB et les paramètres propres à l’imprimante restent à valider lors de la phase 9. Le contrôle de santé Moonraker viendra en phase 6.

## Vérifications

- Tests unitaires : validation du manifeste épinglé et détection des sections de configuration requises.
- Build Android : `:app:assembleDebug`.
- À valider sur téléphone : installation propre de Klipper, révision affichée dans les réglages, démarrage/arrêt avec une configuration réelle, journalisation d’une configuration invalide, puis redémarrage après erreur. Aucun appareil Android n’est attaché à ce poste; la phase reste à valider sur le téléphone avant le déploiement du couple Klipper–Moonraker.
