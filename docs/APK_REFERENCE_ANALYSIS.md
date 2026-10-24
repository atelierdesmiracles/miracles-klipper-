# Analyse de référence — APK Miracle Klipper

## Objet

Cette note décrit les fonctionnalités repérées dans l'APK de référence fournie pour guider la migration de l'application Android. La présence d'une classe ou d'une ressource dans un APK ne prouve pas, à elle seule, que la fonction marche sur un appareil réel.

## Architecture repérée dans l'APK

### Parcours et écrans
- Accueil et démarrage de l'installation.
- Écran de progression de l'installation.
- Configuration de Klipper.
- Interface Web.
- Terminal Debian.
- Écrans Serveur, Extensions, Paramètres et À propos.
- Dialogue de terminal et aperçu caméra.

### Composants applicatifs repérés
- Gestionnaire d'environnement Linux.
- Gestionnaires Klipper, Moonraker, KlipperScreen et interface Web.
- Services de supervision distincts pour Klipper, Moonraker, KlipperScreen et les interfaces Web.
- Validateurs pour `printer.cfg` et `moonraker.conf`.
- Journalisation et diagnostic de l'état des processus.
- Gestion de la caméra/MJPEG et de la communication série USB.
- Terminal Debian avec intégration de KIAUH.

### Ressources et versions trouvées
Les manifestes de l'APK déclarent Debian 13 Trixie ARM64, un rootfs nommé `miracle-debian-bootstrap-24`, des révisions précises de Klipper, Moonraker et KlipperScreen, ainsi que Mainsail v2.19.0 et Fluidd v1.37.7 avec leurs tailles et sommes SHA-256. Les valeurs exactes restent dans les ressources de l'APK de référence ; elles ne doivent pas être copiées dans l'application actuelle sans vérifier que les archives correspondantes existent et ont été publiées.

## Écart avec la branche Android actuelle

La branche `feat/octo4a-to-miracle-klipper` dispose déjà d'un installateur de rootfs Debian Bookworm v0.1.0, du runtime PRoot, d'un superviseur pour Nginx/Klipper/Moonraker, d'un écran Web et d'un panneau de diagnostic. La compilation debug de cette branche est passée sur GitHub Actions.

L'APK de référence contient toutefois une architecture plus large, avec gestionnaires de dépôt, services et écrans spécialisés qui ne sont pas encore intégrés de façon équivalente dans la branche. Les éléments repérés comme spécifiques à OctoPrint/Octo4a doivent être évalués séparément ; le transport série USB et la caméra peuvent être réutilisables, mais les fonctions liées à OctoPrint ne doivent pas être réintroduites par défaut.

## Plan de migration sécurisé

1. **Geler le socle fonctionnel actuel** : conserver le rootfs Bookworm publié, son SHA-256 et les contrôles d'extraction ; ne pas changer de distribution dans la même modification que les écrans.
2. **Reconstituer le parcours UI** : accueil, état d'installation, progression, configuration imprimante, interface Web, terminal, serveur, extensions, paramètres et À propos.
3. **Créer une couche de modèles et de dépôts** pour l'état des services, les journaux, les fichiers de configuration et les étapes d'installation, sans dupliquer la logique dans les activités.
4. **Ajouter les services de supervision séparément** : Klipper, Moonraker, Nginx/interface Web, puis KlipperScreen si sa configuration et ses dépendances sont qualifiées.
5. **Ajouter les validateurs de configuration** avec tests sur fichiers valides, incomplets, malformés et absents.
6. **Porter les fonctions annexes** (USB série, caméra/MJPEG, KIAUH et extensions) seulement après tests ciblés.
7. **Valider chaque incrément** par compilation CI, tests automatisés disponibles et essai sur appareil ARM64 réel avant de déclarer la fonction opérationnelle.

## Risques à résoudre avant d'étendre l'installation

- **Distribution différente** : l'APK annonce Trixie alors que le dépôt publie actuellement Bookworm. Un changement de rootfs exige une release vérifiée, des sommes officielles, une compatibilité de dépendances et un plan de migration des données.
- **Configuration imprimante** : un rootfs générique ne peut pas garantir une configuration matérielle correcte pour toutes les imprimantes. L'application doit permettre de créer/valider `printer.cfg` plutôt que déclarer l'installation terminée simplement parce que les fichiers existent.
- **État des services** : l'existence d'un processus ne suffit pas. Il faut vérifier les ports/API Moonraker, l'état de Klipper et les journaux avant d'afficher un statut « prêt ».
- **Sécurité d'extraction** : garder les vérifications SHA-256, les protections contre les chemins absolus et `..`, et les contrôles de liens symboliques.
- **Validation terrain** : la réussite de Gradle valide la compilation de l'APK, pas le démarrage fiable des services sur un téléphone ni la connexion USB à une imprimante.

## État vérifié au 24 octobre 2026

- Branche de travail : `feat/octo4a-to-miracle-klipper`.
- Commit observé : `62456f50500788693ce97e72065f148b1d34604f`.
- Workflow Android CI : succès.
- Workflow Android Build : succès, artefact debug généré.
- Tests matériels sur téléphone et imprimante : non confirmés par ces résultats CI.
