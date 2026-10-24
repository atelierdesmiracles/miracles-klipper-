# Import amont et migration technique

## Référence

Le dépôt Android de référence est https://github.com/feelfreelinux/octo4a (branche master, licence AGPL-3.0). Le conserver comme amont et intégrer ses fonctions utiles dans ce dépôt, avec attribution et notices de licence. Ne pas modifier l'original.

## Sources à importer

- Wrapper Gradle et scripts de build.
- Module Android complet et ressources associées.
- USB OTG, pilote série virtuel et gestion de la caméra/MJPEG.
- Gestion du service de premier plan, réveil, réseau, SSH, terminal et journaux.
- Tests et fichiers de licence pertinents.

Ne pas recopier les fichiers temporaires de compilation, artefacts générés ou configuration locale. Les fichiers contenant des secrets ou des identifiants de service tiers doivent être examinés et retirés/remplacés.

## Remplacements requis avant un APK utilisable

- Identité de l'application et nom du projet Gradle.
- Nom/description/boutons Android, menus et états du serveur.
- Icônes adaptatives et toutes les densités, ainsi que l'icône Play Store.
- Service Android et repository serveur (aujourd'hui centrés sur OctoPrint).
- InstallationViewModel et écrans de progression pour refléter l'installation Klipper/Moonraker.
- Supprimer les appels GitHub vers les releases OctoPrint/Octo4a bootstrap-builder, commandes `octoprint serve`, plugins/extensions OctoPrint et configuration `config.yaml`.
- Préserver les composants indépendants de la caméra, USB/série, veille, réseau et terminal.

## Contrat entre APK et bootstrap Debian

Le bootstrap de https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper- produit un rootfs Debian compressé en tar.xz. Il ne fournit pas directement un entrypoint Android Proot. L'application devra:
1. résoudre explicitement l'architecture ABI du téléphone ;
2. choisir un artefact de la bonne architecture et vérifier SHA-256 avant extraction ;
3. refuser les chemins absolus, `..`, liens dangereux et fichiers sortant du répertoire de staging ;
4. extraire dans un staging propre et basculer atomiquement après vérification ;
5. démarrer et superviser les services avec le runtime retenu, car systemd ne peut pas être supposé fonctionner comme PID 1 dans Proot ;
6. mapper l'USB OTG vers le port de Klipper et vérifier la persistance des données ;
7. exposer Mainsail à travers un accès réseau local contrôlé et ne pas ouvrir le port Moonraker à Internet ;
8. présenter des erreurs honnêtes et des journaux en cas d'échec.

## CI

Le workflow actuel contrôle seulement la documentation. Une CI Android doit être ajoutée après import des sources, puis faire au minimum `./gradlew assembleDebug` et les tests unitaires. La création d'une APK de release signée nécessite une stratégie de clés appropriée ; aucune clé privée ne doit être ajoutée au dépôt.
