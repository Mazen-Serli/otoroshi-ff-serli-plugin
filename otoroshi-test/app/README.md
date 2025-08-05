# Dossier App - Otoroshi JAR

Ce dossier contient le fichier JAR d'Otoroshi nécessaire pour exécuter les tests du plugin FF-SERLI.

## Téléchargement automatique

Le fichier `otoroshi.jar` est automatiquement téléchargé par les scripts de build :
- `scripts/build-and-test.bat` (Windows)
- `scripts/build-and-test.sh` (Linux/macOS)

Si le JAR n'est pas présent lors de l'exécution du script, il sera téléchargé automatiquement depuis :
**https://github.com/MAIF/otoroshi/releases/latest**

## Téléchargement manuel

Si le téléchargement automatique échoue, vous pouvez télécharger manuellement :

1. Allez sur : https://github.com/MAIF/otoroshi/releases
2. Téléchargez la dernière version d'`otoroshi.jar`
3. Placez le fichier dans ce dossier (`otoroshi-test/app/`)

## Note

Le fichier `otoroshi.jar` est exclu du contrôle de version Git (voir `.gitignore`) car :
- Il est volumineux (>100MB)
- Il peut être téléchargé automatiquement
- Il change fréquemment avec les nouvelles versions
