# Phase 7 — Interface Mainsail / Fluidd

## Interface principale

Le tableau de bord Miracle Klipper ne lance plus OctoPrint comme écran de contrôle. Son bouton principal démarre les composants dans l’ordre Klipper, Moonraker, puis Nginx, et ouvre Mainsail ou Fluidd selon le choix enregistré. Il signale les prérequis manquants et renvoie aux réglages pour les installer. Le bouton d’arrêt ferme les trois services Klipper dans l’ordre inverse.

Les réglages permettent d’installer les deux interfaces et de choisir celle qui s’affiche. Les archives de versions épinglées sont téléchargées depuis les dépôts officiels, vérifiées par taille et SHA-256, puis servies par Nginx. Les fichiers utilisateur restent dans le partage Debian; l’installation d’une interface ne modifie pas `printer.cfg`.

- Mainsail `v2.19.0`, archive `mainsail.zip`, SHA-256 `c4d9d96f89851c6ae0709c2f20725447f3fe8c57cf193869b0083441bd93378a`.
- Fluidd `v1.37.7`, archive `fluidd.zip`, SHA-256 `e45889071dae4e79f22e38bfb28cad0ce0c89e134c09d174cd447c4c93bd9556`.

## Accès et proxy

Nginx écoute sur le port `8080`, sert le client sélectionné et relaie les routes API et WebSocket à Moonraker sur `127.0.0.1:7125`. Le WebView intégré utilise `http://127.0.0.1:8080/`. Nginx refuse les clients hors boucle locale, réseaux IPv4 privés et lien-local. Moonraker ne se lie pas directement au réseau; les adresses IPv4 privées sont toutefois déclarées de confiance pour permettre au proxy d’acheminer l’API. Ne pas exposer ce port sur un réseau non fiable ni sur Internet.

Le modèle suit les routes et options de proxy documentées par [KIAUH](https://github.com/dw-0/kiauh/blob/master/kiauh/components/webui_client/assets/nginx_cfg), et le mode distant Mainsail configuré par [KIAUH](https://github.com/dw-0/kiauh/blob/master/kiauh/components/webui_client/client_utils.py). Les dossiers statiques, le serveur Nginx et les journaux restent dans l’espace privé de l’application, monté dans Debian PRoot.

## Limites de validation

Cette phase remplace l’interface OctoPrint sur le tableau de bord, mais pas encore toute la couche USB héritée d’Octo4a: celle-ci fournit actuellement le pont série Android nécessaire. Le raccordement de ce pont au MCU Klipper et l’essai avec une imprimante réelle restent à valider en phase 9. Aucun téléphone ni imprimante n’est connecté à ce poste; la disponibilité sur appareil, l’accès depuis un autre appareil du Wi-Fi, les WebSockets et les commandes imprimante ne sont donc pas qualifiés ici.

Avant utilisation, installer Debian, Klipper, Moonraker et l’une des interfaces, puis renseigner un `printer.cfg` correspondant exactement à l’imprimante et à sa carte. Garder le téléphone et l’imprimante sur un réseau privé de confiance.
