# Intégration Alpine Linux + QEMU + Docker Engine pour Miracle Klipper

**Statut :** proposition d'architecture et socle d'intégration, pas encore un moteur VM opérationnel.
**Branche :** `feat/alpine-qemu-docker-runtime`
**Cible :** Android ARM64 sans root ; aucune modification de `main`.

## 1. Décision

La cible retenue est une VM Alpine Linux ARM64 démarrée par QEMU TCG (émulation logicielle), avec Docker Engine exécuté à l'intérieur de la VM. Android reste l'hôte non privilégié. Le daemon Docker et les conteneurs disposent des privilèges root *dans le système invité*, pas sur Android.

Le premier backend doit être QEMU TCG. Le backend AVF ne doit pas être requis pour la version distribuée : les API AVF documentées par AOSP sont des API système et demandent `MANAGE_VIRTUAL_MACHINE`, permission non disponible normalement aux applications tierces préinstallées ou signée système. Un accès accordé via ADB est un mécanisme de développement, pas une garantie de fonctionnement pour l'APK public.

## 2. Pourquoi une VM et non Docker sur Android

Le noyau Android hôte n'expose pas nécessairement les namespaces, cgroups, overlayfs et règles réseau nécessaires au moteur Docker. Une VM fournit son propre noyau Linux et ses propres primitives. Docker fonctionne alors dans Alpine, sur le noyau invité.

Le projet Podroid est une référence technique publique pertinente : il annonce Alpine Linux dans une VM QEMU, un noyau personnalisé, Docker/Podman, stockage persistant, transfert de ports et USB passthrough sur le backend QEMU. Il indique également que QEMU TCG est plus lent que l'exécution native (sa documentation annonce 2–5× plus lent sur des charges simples). Ces performances doivent être mesurées sur nos appareils, pas extrapolées.

## 3. Architecture logique

```text
Android (application Miracle Klipper, sans root)
  ├─ UI / configuration / diagnostics
  ├─ Foreground service : durée de vie de la VM
  ├─ USB Host API : permissions et handles USB
  ├─ QEMU ARM64 (TCG) + canal de contrôle QMP
  └─ Port forwarding / bridge local
        |
        v
Alpine Linux ARM64 VM (noyau invité dédié)
  ├─ init + supervision OpenRC
  ├─ Docker Engine (dockerd + containerd + runc)
  ├─ réseau bridge / NAT dans la VM
  ├─ volumes persistants
  └─ conteneurs applicatifs
       ├─ Klipper
       ├─ Moonraker
       ├─ Mainsail (service web statique)
       └─ services optionnels (caméra, outils)
        |
        └─ USB série transmis à la VM -> /dev/ttyUSB* ou /dev/ttyACM*
```

## 4. Périmètre du premier prototype

### Inclus
- Détection ABI ARM64 et espace de stockage disponible.
- Modèle de configuration versionné pour la VM.
- Contrat Kotlin indépendant du backend hyperviseur.
- QEMU TCG comme backend de référence.
- Disque virtuel persistant et image de base vérifiée par SHA-256.
- Console série/virtio-console et QMP pour supervision.
- Ports Moonraker/Mainsail exposés uniquement sur loopback par défaut.
- Service Android au premier plan avec notification persistante et arrêt propre.
- USB passthrough QEMU avec permission explicite Android.
- Journaux exportables et diagnostics.

### Exclu du premier prototype
- AVF obligatoire ou permission système non disponible au public.
- Mode daemon Android invisible en arrière-plan.
- Docker exposé directement au LAN sans authentification.
- KlipperScreen avant validation de la solution graphique.
- Migration/destruction automatique des données Debian existantes.

## 5. Artefacts nécessaires

L'APK ne doit pas télécharger une image arbitraire « latest ». Chaque composant sera versionné et vérifié :
1. binaire QEMU Android ARM64 et ses licences/NOTICE ;
2. noyau Linux invité ARM64 avec options requises : cgroups v2, namespaces, overlayfs, veth/bridge, netfilter, virtio-blk, virtio-net, USB host ;
3. initramfs de démarrage ;
4. rootfs Alpine ARM64 immuable ou versionné ;
5. disque de données ext4 persistant ;
6. bundle Docker Engine / containerd / runc pour Alpine ARM64, versionnés ;
7. manifest signé ou contrôlé par SHA-256, avec tailles et versions.

Le prototype doit commencer par des artefacts minimaux de test. Aucun hash fictif ni URL mutable ne doit être ajouté au manifeste.

## 6. USB série : point critique pour Klipper

Le flux attendu :
1. Android détecte le périphérique avec UsbManager.
2. L'utilisateur autorise explicitement l'accès.
3. Miracle Klipper conserve le descripteur tant que le périphérique est attaché.
4. Le backend QEMU transmet le périphérique via son contrôleur USB et le canal QMP.
5. Alpine voit le périphérique série et le service Klipper reçoit un chemin stable.
6. Déconnexion/reconnexion : le superviseur met à jour l'état sans perdre la configuration de l'imprimante.

À vérifier sur les adaptateurs CH340, CP210x et FTDI et sur les cartes USB CDC. Le passthrough USB annoncé par Podroid est documenté pour QEMU, pas pour AVF.

## 7. Réseau et sécurité

- QMP et console restent sur sockets privées, jamais sur une interface réseau.
- Moonraker/Mainsail bindent sur loopback au départ.
- Le port forwarding Android n'ouvre que les ports explicitement choisis.
- Le daemon Docker ne publie jamais son socket TCP sans authentification.
- Le stockage VM est dans le répertoire privé de l'application.
- Téléchargements : HTTPS, SHA-256 vérifié, taille maximale, extraction sûre.
- Aucun shell construit à partir de chaînes utilisateur.
- Les conteneurs démarrent avec capacités minimales quand Klipper le permet.

## 8. Android et limites opérationnelles

- QEMU TCG consomme davantage de CPU et de batterie ; tester température, throttling, mémoire et latence USB.
- Android peut tuer les processus de fond (notamment phantom processes sur Android 12+) ; maintenir le runtime dans un Foreground Service, tester les politiques constructeurs et documenter les réglages batterie.
- Une VM persistante nécessite un disque sparse et une stratégie de sauvegarde/restauration.
- Démarrage à froid, arrêt forcé, manque d'espace, mise à jour et coupure batterie doivent être testés.
- Pas de promesse de fonctionnement universel avant matrice de tests sur appareils réels.

## 9. Licences et réutilisation de Podroid

Le dépôt Miracle Klipper porte une licence AGPL-3.0. Podroid annonce GPL-2.0. Ne pas copier son code source ni intégrer ses bibliothèques sans revue juridique et analyse de compatibilité des licences. Cette branche utilise ses documents publics comme référence d'architecture uniquement. Une implémentation indépendante doit conserver les notices et obligations propres à QEMU, au noyau Linux, Alpine et Docker Engine.

## 10. Phases de validation

| Phase | Critère de sortie |
|---|---|
| A. Contrat et manifests | Tests unitaires Kotlin ; validation des versions/ABI/checksums |
| B. Boot QEMU minimal | Alpine atteint un état READY sur ARM64 sans root |
| C. Persistance | Fichier témoin conservé après arrêt et redémarrage |
| D. Docker Engine | `docker info` fonctionne dans la VM ; conteneur Alpine démarre |
| E. Réseau | Moonraker et Mainsail joignables via loopback/forwarding |
| F. USB série | Klipper ouvre réellement un port série de l'imprimante |
| G. Robustesse | 20 cycles start/stop, reboot Android, manque d'espace, export diagnostics |
| H. Migration | Seulement après réussite : migration contrôlée puis retrait Debian/PRoot |

## 11. Références techniques

- Podroid : https://github.com/ExTV/Podroid
- Podroid architecture : https://extv.github.io/Podroid/guide/how-it-works.html
- Podroid backends : https://extv.github.io/Podroid/guide/backends.html
- Podroid limitations : https://extv.github.io/Podroid/guide/limitations.html
- Android Virtualization Framework API : https://android.googlesource.com/platform/packages/modules/Virtualization/+/HEAD/libs/framework-virtualization/README.md
- Docker rootless prerequisites : https://docs.docker.com/engine/security/rootless/

## Conclusion

Cette branche établit le contrat d'intégration et le plan de validation. Elle ne prétend pas encore embarquer QEMU, un noyau, Alpine ou Docker. Le remplacement complet de Debian/PRoot ne sera autorisé qu'après réussite des tests de VM, Docker et USB sur un appareil Android réel.
