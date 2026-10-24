# Audit technique Octo4a → Miracle Klipper

Date de l'audit : 24 octobre 2026  
Branche examinée : `feat/octo4a-to-miracle-klipper`

## Périmètre et sources

- Octo4a : https://github.com/feelfreelinux/octo4a
- Constructeur de bootstrap : https://github.com/feelfreelinux/octo4a-bootstrap-builder
- Bootstrap Debian de Miracle Klipper : https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-
- Application Miracle Klipper : https://github.com/atelierdesmiracles/miracles-klipper-

Cet audit est une revue statique des scripts et sources consultables. Il ne constitue pas un test sur téléphone, carte MCU ou imprimante réelle.

## 1. Fonctionnement Octo4a confirmé par le code

Le constructeur Octo4a prépare une image Alpine avec OctoPrint et ses dépendances installés à l'avance. Il compile PRoot et ses dépendances natives avec Android NDK, ajoute un lanceur qui extrait le rootfs au premier démarrage, puis exécute des commandes dans ce système en liant des répertoires Android tels que `/dev`, `/proc`, `/storage`, `/system` et `/vendor`.

Les extensions Octo4a utilisent un manifeste et des scripts de démarrage/arrêt. Le script d'extension Klipper trouvé dans le dépôt de référence télécharge les sources Klipper, installe quelques dépendances, puis lance `klippy.py` avec un fichier de configuration. C'est un exemple d'intégration, pas une preuve que Klipper est entièrement pris en charge sur toutes les configurations Android.

## 2. État de la branche Miracle Klipper

### Déjà présent dans le code

- Téléchargement d'un rootfs Debian Bookworm ARM64 épinglé à une release et vérifié par SHA-256.
- Extraction avec vérification des chemins et contrôles de liens.
- Conservation de certaines données `printer_data` lors du remplacement du rootfs.
- Runtime PRoot ARM64 téléchargé par CI et vérifié par sommes SHA-256.
- Service Android de premier plan qui lance PRoot.
- Superviseur shell qui lance Nginx, Klipper et Moonraker sans supposer que systemd est PID 1.
- Écran WebView pointant vers `http://127.0.0.1`.
- Éditeurs de `printer.cfg` et `moonraker.conf`, avec sauvegardes.
- Panneau de diagnostics qui lit les fichiers et journaux présents.

### Pas encore prouvé

- Démarrage réel de Nginx et disponibilité HTTP sur appareil.
- Réponse effective de l'API Moonraker.
- Connexion de Klipper à une carte via USB OTG.
- Arrêt propre de tous les processus enfants lorsque le service Android s'arrête.
- Fonctionnement prolongé écran éteint et sous les restrictions d'énergie Android.
- Compatibilité avec plusieurs téléphones, câbles OTG et contrôleurs d'imprimante.
- Validité complète des fichiers de configuration fournis par l'utilisateur.

## 3. Points à corriger ou tester en priorité

### P0 — État réel des services

Le panneau actuel vérifie surtout la présence des fichiers et affiche les journaux. Il ne fait pas encore de contrôle de santé réseau de Nginx/Mainsail ni d'appel à l'API Moonraker. La réussite de la création du processus ne doit jamais être présentée comme la réussite du serveur.

**Action :** ajouter des contrôles de disponibilité HTTP pour Mainsail et Moonraker, puis exposer des états séparés : arrêté, démarrage, prêt, dégradé et échec.

### P0 — Arrêt et nettoyage des processus

Le service Android détruit le processus PRoot principal. Le superviseur possède des PID files et envoie SIGTERM aux PID enregistrés, mais il faut vérifier par test que tous les processus lancés restent dans le même périmètre et s'arrêtent réellement. Les PID files peuvent aussi devenir obsolètes après un arrêt brutal.

**Action :** tester start/stop répétitif, tuer le service, forcer l'arrêt Android et redémarrer le téléphone ; vérifier l'absence de processus résiduels et de PID files périmés.

### P0 — Configuration Moonraker / socket Klipper

Le superviseur lance Klipper avec le socket `printer_data/comms/klippy.sock`. Le fichier `moonraker.conf` doit utiliser exactement ce chemin et une configuration d'hôte/ports cohérente avec Nginx. La simple présence de `[server]` ne valide pas le fichier.

**Action :** vérifier le fichier réellement livré dans le rootfs, contrôler la cohérence socket/ports, puis valider avec les journaux et l'API sur le rootfs cible.

### P1 — USB série

Le superviseur lie `/dev` dans PRoot, mais la revue statique ne démontre pas que le périphérique USB série sera accessible et stable sur tous les appareils. Les permissions Android, les noms de périphériques et les autorisations USB doivent être contrôlés.

**Action :** détecter les périphériques série, afficher les permissions et erreurs d'accès, tester débranchement/rebranchement et documenter les modèles validés.

### P1 — Installation et espace disque

L'installateur vérifie la taille libre et le SHA-256, mais l'extraction peut demander davantage d'espace que l'archive téléchargée. L'estimation de l'espace nécessaire doit tenir compte de l'archive, du rootfs extrait, du staging et de l'ancien rootfs conservé pendant la transition.

**Action :** calculer le besoin maximal de stockage avant de commencer et afficher une estimation explicite.

### P1 — Éditeurs de configuration

Les vérifications actuelles sont structurelles. Elles ne garantissent pas la validité des broches, la configuration du MCU ou la syntaxe complète attendue par les versions installées. L'éditeur Moonraker sauvegarde le fichier avant écriture, ce qui est utile, mais le succès doit être suivi d'un redémarrage et d'une lecture des journaux.

**Action :** garder des sauvegardes, ajouter un bouton de restauration et expliquer clairement que la validation n'est pas une validation matérielle.

### P2 — Sécurité et maintenance

- Garder les services non authentifiés limités au loopback tant que l'exposition réseau n'a pas été explicitement conçue.
- Vérifier la configuration Nginx et les règles d'accès Moonraker.
- Éviter les secrets ou mots de passe dans les journaux.
- Conserver les notices et obligations de licence de la base Octo4a/AGPL.
- Épingler les versions et sommes de toutes les dépendances distribuées.

## 4. Différences structurantes avec Octo4a

| Sujet | Octo4a | Miracle Klipper |
|---|---|---|
| Distribution | Alpine Linux | Debian Bookworm ARM64 |
| Application serveur principale | OctoPrint | Klipper + Moonraker |
| Interface web | OctoPrint | Mainsail ; Fluidd à intégrer séparément si retenu |
| Environnement | PRoot et rootfs préconstruit | PRoot et rootfs Debian préconstruit |
| Extensions | Manifeste + scripts | Superviseur dédié à la pile Klipper |
| USB/caméra | Fonctionnalités issues d'Octo4a | À réintégrer et qualifier séparément |

Il ne faut pas copier les scripts Alpine ou les dépendances OctoPrint tels quels dans Debian. Il faut reprendre le modèle de distribution du rootfs, l'initialisation et les contrôles de cycle de vie, puis tester les services propres à Klipper.

## 5. Ordre d'exécution recommandé

1. Valider l'installation et l'extraction du rootfs sur un appareil ARM64.
2. Ajouter et tester les contrôles de santé HTTP Mainsail et API Moonraker.
3. Vérifier start/stop et le nettoyage des processus.
4. Valider la configuration Moonraker et le socket Klipper.
5. Tester USB OTG avec une carte MCU réelle.
6. Tester l'exécution prolongée et la reprise après arrêt Android.
7. Ajouter Fluidd seulement après validation du routage web et de la configuration.
8. Publier une APK de test accompagnée d'une liste explicite des tests réalisés.

## Conclusion

Octo4a fournit un modèle utile pour la préparation d'un rootfs, PRoot, les scripts de lancement et les extensions. Miracle Klipper possède déjà plusieurs briques correspondantes, mais la compilation Android ne valide pas le fonctionnement de la pile sur un téléphone. Le prochain travail doit prioriser les contrôles de santé réels, le cycle de vie des processus et l'USB série plutôt que d'ajouter de nouveaux écrans.
