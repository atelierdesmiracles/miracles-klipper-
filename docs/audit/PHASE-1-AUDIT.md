# Phase 1 — Audit technique des sources Octo4a

Date : 2026-10-24  
Projet cible : **Miracle Klipper**  
Branche de travail : `feat/octo4a-to-miracle-klipper`

## 1. Périmètre audité

Audit statique de l'archive `octo4a-master.zip` fournie dans la conversation. L'archive contient le projet Android Gradle, le code Kotlin/Java, le code natif C/C++, les ressources UI, des scripts d'installation et le générateur de bootstrap.

L'archive indique comme commit upstream : `3e9716a1f63c4c6e11ccbb5802070d9bb859f52a`.

Le contrôle local a recensé 184 fichiers hors dossiers de génération et de cache. Cet audit est une analyse statique : il ne prouve pas encore que l'application compile ni que les services fonctionnent sur un téléphone réel.

## 2. État général

Le projet est une application Android native Kotlin/Java. Il possède déjà des composants réutilisables :
- gestion des périphériques USB et ports série ;
- pont série virtuel et code natif PTY ;
- caméra Android et serveur MJPEG ;
- journalisation, état réseau et wakelock ;
- écran WebView pour l'interface distante ;
- installation et extraction d'un environnement Linux.

Cependant, le flux applicatif reste fortement couplé à OctoPrint. Un simple changement de nom ou de logo ne suffira pas.

## 3. Points bloquants identifiés

### 3.1 Couplage à OctoPrint

Des références à `OctoPrintHandlerRepository`, `OctoPrintService`, `startOctoPrint()`, `stopOctoPrint()` et aux chemins OctoPrint se trouvent dans les ViewModels, le service, le pilote série et la caméra. Le modèle d'état du serveur devra être remplacé par une abstraction adaptée à Klipper/Moonraker, tout en conservant les fonctionnalités USB et caméra utiles.

### 3.2 Identité Android

Le module déclare actuellement `applicationId = "com.octo4a"`, avec un nom de version upstream `2.1.0`. L'identité, les ressources, les écrans et les métadonnées devront être migrés avec prudence. Le changement d'identifiant peut empêcher une mise à jour directe d'une installation Octo4a existante ; c'est attendu pour une application distincte, mais doit être documenté.

### 3.3 Chaîne de compilation ancienne

La configuration utilise notamment Android Gradle Plugin 7.1.3, Kotlin 1.4.30, compileSdk 28, targetSdk 28, Gradle et des dépendances Android anciennes. Cela augmente les risques de compatibilité avec les JDK, SDK Android et règles récentes de Google Play. Ne pas mettre à jour toutes les dépendances en une seule fois : établir d'abord une compilation reproductible, puis moderniser par étapes.

### 3.4 Scripts d'installation hérités et environnements incompatibles

Les scripts existants utilisent `apk add` et des chemins de type Alpine Linux. Ils ne sont pas directement adaptés à Debian Bookworm. Le script `setup-klipper-moonraker.sh` contient aussi des hypothèses sur nginx, des répertoires de configuration et des chemins d'installation qu'il faudra valider dans l'environnement cible.

Le rootfs Debian ARM64 ne doit pas être traité comme un système démarrable autonome. Dans Android, l'exécution devra tenir compte des limites de PRoot/chroot, des permissions, des services sans systemd et des binaires compatibles ARM64.

### 3.5 USB série et Klipper

Le code USB existant est utile, mais il est intégré à l'ancien service OctoPrint et à son protocole de série virtuel. Il faut définir un chemin de communication explicite entre Android, le périphérique série et Klipper, puis tester l'accès USB sur matériel réel. La présence du pilote USB dans le code ne suffit pas à prouver que Klipper pourra ouvrir le périphérique dans l'environnement Debian.

### 3.6 Sécurité réseau

Le script Moonraker contient des exemples de configuration autorisant l'écoute sur `0.0.0.0`, des domaines CORS génériques et de larges plages de clients de confiance. Ces valeurs ne doivent pas être reprises telles quelles en production. Il faudra définir les règles d'accès local, l'autorisation Moonraker et les accès depuis le réseau local avant d'exposer les ports.

## 4. Composants à préserver

| Composant | Décision provisoire | Motif |
|---|---|---|
| Gestion USB / ports série | Réutiliser et découpler | Nécessaire pour la liaison avec l'imprimante |
| Code natif PTY | Auditer puis conserver si nécessaire | Peut servir au pont série |
| Caméra / MJPEG | Conserver comme fonctionnalité optionnelle | Utile pour surveiller une impression |
| Journaux et état réseau | Conserver et adapter | Diagnostic et supervision |
| Wakelock / service Android | Conserver après vérification | Continuité des tâches, dans les limites Android |
| Installation OctoPrint | Remplacer | Hors de l'architecture cible |
| Gestion d'extensions OctoPrint | Réévaluer | Ne pas transposer sans besoin |
| Scripts Alpine | Remplacer ou réécrire pour Debian | Incompatibles tels quels avec Bookworm |

## 5. Architecture cible recommandée

1. Application Android : assistant d'installation, état des composants, logs, permissions USB et accès à Mainsail.
2. Environnement Debian Bookworm ARM64 : stockage privé de l'application et scripts de lancement compatibles Android.
3. Klipper : lancement avec configuration imprimante et périphérique série accessibles.
4. Moonraker : API locale sécurisée, configurée pour communiquer avec Klipper.
5. Mainsail : interface Web accessible dans la WebView Android et, si activé, depuis le réseau local.
6. Supervision : états indépendants pour l'environnement Linux, Klipper, Moonraker, Mainsail et USB.

Le mécanisme de démarrage ne doit pas dépendre de systemd tant que sa disponibilité réelle n'a pas été prouvée dans l'environnement retenu.

## 6. Plan d'action après l'audit

### Phase 2 — Préparer la base Android
- Importer proprement l'ensemble du projet upstream dans le dépôt cible si ce n'est pas déjà fait.
- Vérifier le wrapper Gradle et les fichiers requis à la compilation.
- Définir le nouvel identifiant d'application et les ressources Miracle Klipper.
- Produire un premier build de référence avant les changements fonctionnels, si l'environnement de compilation le permet.

### Phase 3 — Découpler l'application du serveur OctoPrint
- Introduire une interface de gestion des services plutôt que remplacer aveuglément les noms de classes.
- Définir les états et opérations de Klipper/Moonraker.
- Conserver les modules USB et caméra derrière des interfaces indépendantes.

### Phase 4 — Adapter l'installation Debian
- Vérifier l'URL et le SHA-256 de l'archive rootfs.
- Écrire un installateur idempotent avec vérification de l'espace, extraction sûre et journalisation.
- Remplacer les scripts Alpine par des scripts Debian Bookworm.
- Choisir et tester un superviseur de processus compatible avec l'environnement Android réel.

### Phases suivantes
- Démarrage Klipper/Moonraker, intégration Mainsail, USB série, sécurité, tests sur appareil, génération APK et publication Release.

## 7. Critères de sortie de la phase 1

- [x] Archive Octo4a reçue et inspectée.
- [x] Principaux composants réutilisables identifiés.
- [x] Couplages OctoPrint identifiés.
- [x] Risques Gradle, Debian, USB et réseau consignés.
- [ ] Compilation Android de référence : non testée dans cet audit.
- [ ] Tests sur téléphone et imprimante : non réalisés.

## 8. Conclusion

Le code Octo4a fournit une base réutilisable pour Android, en particulier pour USB série, caméra et supervision. La migration devra surtout remplacer la couche d'orchestration OctoPrint et réécrire l'installation Linux pour Debian Bookworm ARM64. Les scripts existants Klipper/Moonraker constituent des références de départ, mais ne sont pas encore validés pour Android/PRoot.

Aucune fonctionnalité n'est déclarée opérationnelle sur la seule base de cet audit statique.
