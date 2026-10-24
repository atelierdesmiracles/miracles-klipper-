# Phase 3 — Contrat de téléchargement du bootstrap

Le fichier `docs/BOOTSTRAP_MANIFEST_EXAMPLE.json` est un exemple de contrat, pas un manifeste de production. Les champs `REPLACE_...` doivent être remplacés par des URL d'assets de release réellement publiés et des sommes SHA-256 calculées lors du build.

## Règles d'implémentation côté Android

1. Récupérer un manifeste publié depuis une URL HTTPS stable et versionnée.
2. Valider `schema_version`, `product`, `bootstrap_version` et la présence des composants requis.
3. Faire correspondre l'ABI Android à une seule architecture prise en charge. Ne jamais choisir l'archive seulement sur le nom du fichier.
4. Télécharger vers un fichier temporaire et vérifier la taille et le SHA-256 avant de l'ouvrir.
5. Ne pas extraire le tar directement avec une bibliothèque qui autorise les chemins absolus ou `..`. Refuser aussi les liens qui sortiraient du répertoire de staging.
6. Extraire dans un dossier temporaire sur un stockage compatible avec les permissions Linux attendues ; ne pas utiliser le stockage partagé Android pour le rootfs.
7. Activer le nouveau rootfs uniquement après vérification complète. Conserver l'ancien environnement et les données utilisateur pour permettre le retour arrière.
8. Journaliser l'erreur réelle et ne pas afficher « installation réussie » avant que les composants soient démarrés et vérifiés.

## Architecture et runtime

La disponibilité de `arm64-v8a` dans Android ne garantit pas à elle seule que le rootfs fonctionnera : il faut vérifier les ABI du runtime, la libc et les dépendances. Le rootfs Debian utilise des services systemd ; l'intégration doit implémenter et tester une stratégie de supervision compatible avec le runtime retenu. Ne pas lancer les scripts de service arbitraires depuis l'archive sans vérification.

## État

Le dépôt bootstrap n'expose actuellement qu'un script de build et de validation du rootfs. Tant qu'une release contenant les artefacts et les checksums n'est pas publiée, les URL du manifeste restent volontairement des placeholders et aucune installation automatique ne doit être activée.
