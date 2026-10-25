# Phase 2 — Socle Android

**État :** correctifs de socle implémentés; validation sur téléphone requise avant la phase 3.

## État de départ

| Élément | Version ou mécanisme relevé |
| --- | --- |
| Android `minSdk` / `compileSdk` / `targetSdk` | 17 / 28 / 28 |
| Android Gradle Plugin | 7.1.3 |
| Gradle wrapper | 7.2 |
| Kotlin Gradle plugin / standard library | 1.4.30 |
| Cycle de vie du serveur | `LifecycleService`, notification au premier plan, `START_STICKY` |
| Configuration persistante | `MainPreferences` sur `SharedPreferences` privées Android |
| USB série | `UsbManager` + permission utilisateur + pilote série USB + PTY natif |
| Journaux | Logcat et flux mémoire partagé |

Les versions historiques sont conservées pour cette phase afin de limiter le changement de surface. Le plugin Gradle Bugsnag reste déclaré avec `7.+`; il devra être épinglé lors d’une phase dédiée aux dépendances avant une livraison reproductible.

## Changements apportés

- Le service Android commence l’environnement après son passage au premier plan, évite de lancer deux fois l’initialisation, annule son travail asynchrone à l’arrêt et protège chaque étape de nettoyage contre une exception.
- Le contrôleur PTY Kotlin évite les démarrages concurrents. Le PTY natif se ferme par un tube de réveil et `pthread_join`; l’ancien signal `SIGTERM` pouvait interrompre tout le processus Android.
- La fermeture du service USB arrête le lecteur série, ferme le port et la connexion, libère l’exécuteur et arrête le PTY. Les déconnexions d’un autre périphérique ne ferment pas le port série actif.
- La réponse d’autorisation USB est vérifiée : un refus n’entraîne plus une nouvelle demande automatique. Le `PendingIntent` est limité au paquet et immuable lorsque la plateforme le permet, conformément au modèle documenté par Android. [USB Host Android](https://developer.android.com/develop/connectivity/usb/host)
- Les notifications utilisent un canal de faible importance et un `PendingIntent` immuable sur les versions qui le prennent en charge. Le démarrage planifié utilise aussi un `PendingIntent` immuable.
- `LoggerRepository` ne bloque plus l’appelant avec `runBlocking`, conserve les derniers messages en mémoire pour l’écran de diagnostic et distingue les erreurs avec leur pile dans Logcat.
- Un gestionnaire d’erreurs commun rapporte les échecs de démarrage, d’installation et d’arrêt. Un échec d’installation d’extensions optionnelles laisse le statut à `NotInstalled`.

## Vérifications

- Tests unitaires exécutés avec succès : `testDebugUnitTest` (3 tests, aucune erreur).
- APK Debug généré avec succès par `assembleDebug`, avec les bibliothèques natives des quatre ABI configurées. Fichier : `app/app/build/outputs/apk/debug/app-debug.apk`.
- Les essais de démarrage, fermeture/réouverture, redémarrage du service, arrêt des processus, persistance des paramètres et erreur d’installation simulée restent à exécuter sur Android. Aucun téléphone n’était connecté à ADB lors de la dernière vérification.
- La détection de permission USB, la reprise après veille et le PTY doivent être vérifiés avec le téléphone et l’imprimante réels; l’inspection statique et un build PC ne prouvent pas ces comportements matériels.

**Porte de phase :** ne pas déclarer la phase 2 validée et ne pas commencer la phase 3 avant l’exécution des essais Android requis. La configuration existante est persistée localement; elle n’est pas encore migrée vers un format de configuration Miracle Klipper.
