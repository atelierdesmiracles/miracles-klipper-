# Phase 3 — Identité Miracle Klipper

**État :** identité appliquée et APK compilé; installation, affichage et démarrage doivent encore être vérifiés sur un téléphone Android.

## Changements

- Nom Android et titre du projet : `Miracle Klipper`.
- Icône de lancement et illustration d’accueil remplacées par un symbole original d’imprimante 3D, en ressources adaptatives et en PNG pour les anciennes versions Android.
- Nom du projet Gradle : `Miracle Klipper`. Informations de version : `0.1.0-alpha.1`, code de version `2001001`.
- Les liens du panneau « À propos » dirigent vers le dépôt Miracle Klipper; les liens de la communauté et du don indiquent clairement qu’ils concernent Octo4a en amont.
- Le texte d’accueil précise que cette version conserve encore le serveur OctoPrint existant. Elle ne présente pas le support Klipper comme déjà disponible.
- Le README décrit désormais la filiation avec Octo4a. Le fichier de licence d’origine est conservé.

## Identifiant Android

L’identifiant installé reste `com.octo4a` pour permettre une mise à jour de l’application existante et conserver ses données privées. Il est aussi utilisé par le code JNI, les noms natifs et les chemins internes de l’environnement Linux. Le remplacer à ce stade créerait une application distincte et casserait des chemins natifs. La migration vers un nouvel identifiant devra traiter ces dépendances et les données existantes avant publication; aucun identifiant cible permanent n’est fixé dans cette phase.

Les références restantes à Octo4a dans le code désignent le paquet technique, le bootstrap ou la filiation et les crédits du projet d’origine. Les références à OctoPrint désignent le serveur réellement fourni par cette version.

## Vérifications

- `testDebugUnitTest` réussi : 3 tests, aucun échec.
- `assembleDebug` réussi pour les quatre architectures configurées. La signature de l’APK Debug est valide.
- Les métadonnées de l’APK confirment le libellé `Miracle Klipper`, le paquet conservé `com.octo4a` et la version `0.1.0-alpha.1` (`2001001`).
- Installation, affichage de l’icône et cycle démarrage/arrêt à vérifier sur le téléphone. Ces contrôles nécessitent un appareil Android connecté.
