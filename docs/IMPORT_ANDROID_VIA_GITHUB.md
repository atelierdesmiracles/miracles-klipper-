# Importer les sources Android d'Octo4a

Le dépôt de Miracle Klipper contient le cadrage et les scripts, mais les sources Android d'Octo4a ne sont pas encore intégrées. Aucun APK n'est donc disponible à ce stade.

## Méthode A — depuis GitHub Actions

1. Ouvrir la PR de préparation et intégrer le workflow d'import dans la branche par défaut (le workflow `workflow_dispatch` doit être présent sur la branche par défaut pour être lancé depuis Actions).
2. Ouvrir **Actions → Import Octo4a Android sources → Run workflow**.
3. Conserver `master` comme ref amont sauf si une version précise a été vérifiée.
4. Cible : `feat/octo4a-to-miracle-klipper`.
5. Saisir exactement `IMPORT` pour confirmer. Le workflow refuse d'écraser un dossier `app/` déjà existant.
6. Vérifier le commit d'import et les fichiers importés, puis lancer **Miracle Klipper Android Build** sur la branche cible.

Le workflow importe le projet Gradle complet (pas seulement `app/`), garde la licence et le README amont sous des noms distincts et enregistre le commit exact d'Octo4a. Il ne réalise pas encore la migration fonctionnelle vers Klipper/Moonraker/Mainsail.

## Méthode B — en local

Depuis la racine du dépôt :

```bash
git checkout feat/octo4a-to-miracle-klipper
chmod +x scripts/import-octo4a.sh
./scripts/import-octo4a.sh
git status --short
```

Relire le diff avant de committer. Le script refuse d'écraser les fichiers Android/Gradle déjà présents.

## Après l'import

L'import n'est pas la migration. Les phases suivantes restent nécessaires :
- vérifier l'architecture Android, les identifiants d'application et les dépendances ;
- remplacer le parcours d'installation OctoPrint par le bootstrap Debian versionné ;
- définir et implémenter le contrat d'installation Klipper + Moonraker + Mainsail ;
- conserver et tester USB OTG/série, caméra/MJPEG, SSH, service au premier plan, wakelock, journaux et paramètres ;
- appliquer l'identité visuelle Miracle Klipper et l'icône fournie ;
- compiler, tester sur appareil Android et publier une release uniquement après validation.

Ne pas présenter l'APK comme fonctionnel avant que ces étapes aient été effectuées et testées.
