# Import des sources Android

Le script `scripts/import-octo4a.sh` prépare l'import du projet Android amont dans le dépôt Miracle Klipper.

## Utilisation

À exécuter depuis la racine d'un clone local de Miracle Klipper :

```bash
chmod +x scripts/import-octo4a.sh
./scripts/import-octo4a.sh
```

Pour choisir une référence amont précise :

```bash
UPSTREAM_REF=<commit-ou-tag> ./scripts/import-octo4a.sh
```

Le script refuse d'écraser un dossier `app/` existant. Il copie le projet Gradle, conserve la licence et le README amont, et enregistre le commit importé.

## Limites et sécurité

- Le script nécessite Git et un accès réseau à GitHub.
- Il ne supprime ni ne remplace les fonctions OctoPrint.
- Il ne modifie pas encore les noms de packages, les ressources ou les icônes.
- Après l'import, examiner les fichiers de configuration et les dépendances avant toute compilation.
- Ne jamais inclure de clés de signature, secrets ou fichiers locaux.
- L'import doit être revu avant publication, en conservant les obligations de licence AGPL applicables.
