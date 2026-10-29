# Revue technique de KlipPocket pour Miracle Klipper

Date de revue : 2026-10-29

Références publiques examinées :
- https://github.com/Brozinga/KlipPocket
- `app/build.gradle`
- `app/src/main/java/ru/ytkab0bp/beamklipper/BundleInstaller.kt`
- `app/src/main/java/ru/ytkab0bp/beamklipper/service/BasePythonService.kt`
- `docs/build-app.md`

## Constats issus du dépôt KlipPocket

- L'application est Android/Kotlin et utilise Chaquopy pour embarquer Python 3.10 et ses dépendances.
- Les dépendances Python sont versionnées dans Gradle; plusieurs wheels sont conservées dans le dépôt pour les paquets qui doivent être compilés spécifiquement pour Android.
- KlipPocket embarque les sources Klipper, Moonraker et Kalico et gère leurs processus au moyen de services Android.
- Le dépôt sépare plusieurs profils d'imprimantes et documente la gestion USB, les journaux, la caméra et les interfaces web.
- Le projet est publié sous GPL-3.0. Toute copie ou adaptation de code doit respecter cette licence et les licences des composants embarqués.

## Différences importantes avec Miracle Klipper

Miracle Klipper utilise actuellement un environnement Debian Trixie sous PRoot, puis installe les composants dans cet environnement. Cette architecture est utile pour disposer d'outils Linux et de paquets Debian, mais elle dépend de la capacité du noyau Android à autoriser le fonctionnement de PRoot.

KlipPocket ne constitue donc pas un remplacement direct : son modèle Chaquopy/Python embarqué ne fournit pas automatiquement un environnement Debian, apt/dpkg, les outils système ou une compatibilité équivalente pour les scripts Linux existants.

En particulier, un échec Android du type `ptrace(TRACEME): Operation not permitted` ne se corrige pas en répétant `apt-get`. Il faut soit un noyau/appareil compatible avec PRoot, soit une évolution d'architecture évitant cette dépendance.

## Changement initial appliqué dans Miracle Klipper

Avant cette revue, l'installation Debian pouvait passer directement à l'installation des paquets après l'extraction et la configuration du rootfs. La branche `fix/proot-preflight-klippocket-lessons` ajoute un test d'exécution Python dans le rootfs via PRoot **avant** d'exécuter `apt-get`.

Objectifs :
- détecter tôt les appareils où PRoot ne peut pas exécuter un processus invité;
- préserver un message d'erreur et un journal plus utiles;
- éviter de lancer l'installation des paquets lorsque le runtime de base est déjà bloqué.

Cette vérification améliore le diagnostic et la récupération; elle ne supprime pas la limitation du noyau Android et ne remplace pas un essai sur appareil physique.

## Suite recommandée

1. Faire passer la CI sur la branche et examiner les tests ainsi que la génération des APK.
2. Tester sur au moins un appareil ARM64 sans root : installation du rootfs, test PRoot, installation Klipper/Moonraker, démarrage et connexion USB réelle.
3. Si PRoot reste interdit sur des appareils cibles, établir un prototype séparé d'exécution Python embarquée (approche Chaquopy) pour évaluer la faisabilité de Klipper et Moonraker sans Debian complet.
4. Ne migrer vers ce prototype qu'après comparaison fonctionnelle : gestion des dépendances natives, accès USB série, sockets, processus de fond, caméra, mise à jour et taille de l'APK.

## Limites de cette revue

Aucun code KlipPocket n'a été copié dans Miracle Klipper dans le cadre de cette première adaptation. Les conclusions d'architecture viennent du dépôt public; la compatibilité réelle doit être confirmée par les builds CI et des essais sur téléphone/imprimante.
