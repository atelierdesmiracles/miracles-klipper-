# Phase 2 — Plan de remplacement du moteur OctoPrint

Ce document décrit la migration à effectuer à partir des sources Octo4a. Il ne signifie pas que le code applicatif a déjà été importé.

## Fichiers amont examinés

- `OctoPrintHandlerRepository.kt` : cycle de vie du serveur, USB, caméra, SSH, wakelock et extensions. Cette classe mélange les fonctions génériques et OctoPrint ; il faut la découper plutôt que la supprimer en bloc.
- `BootstrapRepository.kt` : téléchargement d'un ZIP, extraction avec `ZipInputStream`, entrypoint `entrypoint.sh`, bind-mounts et chemins `/home/octoprint`. Ce contrat n'est pas compatible avec l'archive rootfs Debian `.tar.xz`.
- `InstallationViewModel.kt` : sélectionne les releases de `feelfreelinux/octo4a-bootstrap-builder` et affiche « OctoPrint ». Ce flux doit pointer vers les releases du dépôt bootstrap Debian et reconnaître les manifestes d'artefact.
- Ressources Android : chaînes, icônes, manifeste et identifiant de package doivent être renommés dans le cadre de l'import.

## Découpage recommandé

### 1. Conserver les fonctions transversales
- gestion USB OTG et autorisations Android ;
- lien série virtuel `/dev/ttyOcto4a` ou nouveau nom neutre `/dev/ttyMiracleKlipper` ;
- caméra/MJPEG ;
- wakelock et service de premier plan ;
- SSH, journaux, réseau et terminal ;
- préférences et sauvegarde des données.

### 2. Remplacer l'API du serveur
Créer un contrat indépendant du serveur, par exemple `PrinterServerRepository`, avec :
- `serverState`, `installProgress`, `installErrorDescription` ;
- `beginInstallation()`, `startServices()`, `stopServices()` ;
- `moonrakerUrl`, `mainsailUrl`, `usbDeviceStatus` ;
- statut de Klipper, Moonraker et Mainsail séparé, car le démarrage de l'un ne prouve pas que les autres sont prêts.

Ne pas garder les méthodes `startOctoPrint()`, `stopOctoPrint()`, `octoPrintVersion`, `getConfigValue()` ni `registeredExtensions` dans l'interface finale.

### 3. Contrat d'artefact bootstrap
Le workflow du bootstrap doit publier un manifeste JSON versionné qui donne pour chaque ABI :
- nom de l'artefact `.tar.xz` ;
- URL de téléchargement de release ;
- SHA-256 ;
- version du bootstrap et architecture cible ;
- liste des composants attendus.

L'application doit rejeter un manifeste inconnu, une architecture non prise en charge ou un checksum invalide. Ne pas utiliser une URL « latest » non vérifiée sans manifeste.

### 4. Extraction sûre
Ne pas réutiliser directement le code ZIP actuel. L'extraction tar.xz doit :
- vérifier les chemins canonisés et refuser `..`, chemins absolus et écritures hors du répertoire de staging ;
- traiter les liens symboliques/durs de façon sûre ;
- contrôler l'espace libre et l'archive avant activation ;
- extraire dans un répertoire temporaire et ne remplacer l'installation active qu'après succès ;
- préserver les données utilisateur `printer_data` lors des mises à jour.

### 5. Démarrage des services
Le rootfs Debian utilise systemd. Android/Proot ne doit pas être supposé fournir systemd en PID 1. Il faut choisir explicitement une stratégie testée :
- services adaptés au démarrage direct et supervisés par l'application, ou
- environnement Linux isolé qui prend réellement en charge systemd.

Ne pas afficher « serveur démarré » avant des contrôles de disponibilité distincts : processus Klipper, API Moonraker et page Mainsail. Toute erreur doit être visible dans les journaux.

### 6. Réseau et sécurité
- Exposer Mainsail sur le réseau local avec un avertissement clair.
- Ne jamais ouvrir Moonraker directement à Internet sans authentification et filtrage.
- Garder les identifiants SSH et secrets hors du dépôt.
- Ne pas exécuter de commandes de téléchargement non vérifiées avec `curl | bash`.

## Critères d'acceptation de la phase 2

- Aucun appel à l'API OctoPrint ni commande `octoprint serve` dans le chemin d'exécution.
- L'installation ne télécharge plus `feelfreelinux/octo4a-bootstrap-builder`.
- Les fonctions caméra, USB OTG, SSH et gestion d'énergie ont des tests de non-régression.
- L'application signale correctement les états « installation », « démarrage », « partiellement prêt », « prêt », « arrêt » et « erreur ».
- Une compilation Gradle complète et un essai sur appareil sont requis avant de considérer la phase terminée.
