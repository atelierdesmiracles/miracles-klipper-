# Cahier des charges consolidé — Miracle Klipper

Ce document rassemble les demandes exprimées pour la transformation d'Octo4a en Miracle Klipper. Une case cochée signifie que le travail est intégré au dépôt, pas nécessairement validé sur un téléphone réel.

## 1. Identité et expérience Android
- [x] Nom visible « Miracle Klipper ».
- [x] Compilation APK debug sur GitHub Actions.
- [x] Premier diagnostic au lancement indiquant si des indices d'accès root sont détectés, sans imposer le root.
- [x] Première traduction française des principaux textes d'accueil et de statut.
- [ ] Identifiant d'application distinct de `com.octo4a`, après vérification des implications de migration.
- [ ] Refonte complète de l'interface, navigation, icônes, couleurs et états d'erreur.
- [ ] Supprimer toutes les références et tous les parcours OctoPrint/Octo4a devenus obsolètes.
- [ ] Contrôler l'accessibilité, les tailles d'écran et les orientations.

## 2. Environnement Debian
- [x] Épingler le rootfs Debian Bookworm ARM64 `v0.1.0` du dépôt bootstrap officiel.
- [x] Intégrer le téléchargement depuis une URL versionnée et le contrôle SHA-256 avant extraction.
- [x] Contrôler la taille du téléchargement, les chemins TAR et le contenu attendu du rootfs.
- [x] Extraire dans un dossier temporaire puis activer après validation des fichiers Klipper, Moonraker et Mainsail.
- [x] Préserver `printer.cfg` et `moonraker.conf` lors du remplacement d'un rootfs existant.
- [x] Documenter les chemins exacts du bootstrap et les limites de systemd dans PRoot.
- [ ] Tester l'extracteur sur l'archive publiée : notamment liens symboliques Debian, droits Unix, taille décompressée et espace disque.
- [ ] Finaliser reprise sur erreur, journalisation, estimation de l'espace avant installation et migration des configurations.

## 3. Services d'impression
- [ ] Lancer Klipper, Moonraker et Nginx/Mainsail depuis un superviseur adapté à Android.
- [ ] Gérer arrêt, redémarrage, arrêt propre et processus orphelins.
- [ ] Attendre les contrôles de santé plutôt que considérer un processus lancé comme opérationnel.
- [ ] Persister `printer.cfg`, `moonraker.conf`, les G-code et les journaux.
- [ ] Détecter les erreurs de configuration et fournir une procédure de diagnostic.
- [ ] Tester les ressources CPU, mémoire, stockage, chauffe et consommation en veille.

## 4. USB OTG et imprimante
- [ ] Conserver la prise en charge USB/serial existante lorsqu'elle est réutilisable.
- [ ] Demander l'autorisation USB Android et détecter branchement/débranchement.
- [ ] Laisser sélectionner le périphérique et le pilote série.
- [ ] Connecter le port série à Klipper avec les mécanismes Android/PRoot réellement compatibles.
- [ ] Traiter les pertes de connexion et permettre la reconnexion.
- [ ] Valider sur plusieurs appareils Android, câbles OTG et cartes de contrôle d'imprimante.

## 5. Mainsail et réseau
- [ ] Fournir Mainsail intégré et ouvrable depuis l'application.
- [ ] Afficher l'URL locale et un bouton d'ouverture dans le navigateur/WebView.
- [ ] Garder Moonraker lié à localhost derrière le proxy lorsque cela est possible.
- [ ] Restreindre les origines CORS et les clients de confiance au réseau nécessaire.
- [ ] Ne jamais exposer l'API non protégée directement sur Internet.
- [ ] Tester Wi-Fi, point d'accès, changement d'adresse IP et perte réseau.

## 6. Résilience, sécurité et livraison
- [ ] État des services en direct, journaux consultables et messages d'erreur utiles.
- [ ] Gestion du service de premier plan, wakelock et restrictions d'économie d'énergie.
- [ ] Vérifier les permissions Android modernes et limiter les permissions non nécessaires.
- [ ] Corriger le workflow de release après analyse du motif exact d'échec.
- [x] Workflow de build debug validé.
- [ ] Build release validé, signé de façon reproductible et accompagné d'une empreinte SHA-256.
- [ ] Publier une release GitHub uniquement quand l'artefact a été vérifié.
- [ ] Tests sur téléphone réel : root/non-root, USB, démarrage, veille, redémarrage, stockage plein et récupération après erreur.
- [ ] Documentation utilisateur, compatibilité, installation, désinstallation et sauvegarde.

## État vérifié
- Le workflow Android debug a compilé avec succès.
- Le diagnostic root ajouté utilise des indices locaux (balises de build et chemins connus). Ce contrôle est indicatif : il ne prouve pas à lui seul qu'un appareil est rooté ou non.
- L'application conserve encore des composants et parcours hérités d'Octo4a/OctoPrint. La compilation ne prouve donc pas encore l'exécution de Klipper/Moonraker sur un téléphone.
- Les fonctions marquées non cochées restent à développer ou à tester. Aucune compatibilité matérielle ne doit être annoncée avant un test réel.
