# Phase 2 — Préparation de la base Android

Date : 2026-10-24  
Branche unique : `feat/octo4a-to-miracle-klipper`

## Objectif

Préparer une base Android complète à partir des sources Octo4a fournies, en préservant le projet upstream et ses mentions de licence. Aucun renommage fonctionnel massif n'est entrepris avant qu'une compilation de référence soit possible.

## État constaté

- Le ZIP Octo4a fourni a été inspecté localement : commit upstream déclaré `3e9716a1f63c4c6e11ccbb5802070d9bb859f52a`.
- Le dépôt Miracle Klipper possède un workflow d'import `.github/workflows/import-octo4a.yml` qui copie le projet Gradle depuis l'amont, conserve la licence et le README upstream et enregistre le commit source.
- Le workflow d'import n'a pas encore été exécuté. Le répertoire de la branche cible ne contient donc pas encore les fichiers du projet Android (`app/`, `gradlew`, `gradle/`, `settings.gradle`).
- Une compilation Android de référence n'est pas possible tant que ces fichiers ne sont pas importés.

## Procédure d'import prévue

Le workflow `Import Octo4a Android sources` doit être exécuté avec les paramètres suivants :

| Paramètre | Valeur |
|---|---|
| Branche de workflow | `main` (le workflow est déclaré sur cette branche) |
| `upstream_ref` | `master` |
| `target_branch` | `feat/octo4a-to-miracle-klipper` |
| `confirm_import` | `IMPORT` |

Le workflow refuse de remplacer un projet Android déjà présent. Il importe les fichiers du projet sans le dossier `.github` upstream, préserve les informations de licence, enregistre le commit upstream et pousse un commit sur la branche cible existante. Il ne doit pas créer de branche supplémentaire.

Lien direct vers le workflow :  
https://github.com/atelierdesmiracles/miracles-klipper-/actions/workflows/import-octo4a.yml

## Contrôles obligatoires après import

1. Vérifier que `app/`, `gradlew`, `gradle/wrapper/gradle-wrapper.jar` et `settings.gradle` existent.
2. Vérifier `LICENSE-OCTO4A-AGPL-3.0.md`, `README-OCTO4A-UPSTREAM.md` et `OCTO4A_UPSTREAM_COMMIT.txt`.
3. Vérifier que le commit est sur `feat/octo4a-to-miracle-klipper`, sans création de nouvelle branche et sans modification de `main`.
4. Lancer la compilation de référence via GitHub Actions.
5. Corriger les erreurs de build avant de renommer ou modifier les services OctoPrint.

## Pourquoi ne pas importer les fichiers incomplets via des modifications individuelles

Le projet contient des fichiers binaires nécessaires (dont le wrapper Gradle et des ressources Android). Une copie partielle via des fichiers texte créerait une base incomplète et rendrait le diagnostic de compilation trompeur. Le workflow d'import est donc préférable : il récupère l'intégralité des sources upstream directement dans GitHub Actions et vérifie les fichiers Gradle critiques.

## Critères de sortie

- [x] Procédure d'import et contrôles consignés dans le dépôt.
- [x] Aucune branche supplémentaire créée.
- [ ] Sources Android complètes présentes sur la branche cible.
- [ ] Commit d'import vérifié.
- [ ] Compilation Gradle de référence réussie ou erreurs consignées.

**Statut : en attente d'exécution du workflow d'import.** Ce document ne prétend pas que l'import ni la compilation sont terminés.
