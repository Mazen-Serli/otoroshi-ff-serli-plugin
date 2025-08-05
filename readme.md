# Otoroshi FF-SERLI Plugin

Ce plugin permet d'intégrer les feature flags de FF-SERLI à vos requêtes Otoroshi.

## Fonctionnalités

- Récupération automatique des feature flags depuis l'API FF-SERLI
- Mise en cache des flags pour améliorer les performances
- Ajout des flags aux requêtes sortantes

## Configuration

Le plugin nécessite les paramètres suivants :

- **API Key** : Clé d'API pour l'authentification auprès du service FF-SERLI
- **Project ID** : Identifiant du projet dans le service FF-SERLI
- **TTL** : Durée de validité du cache des flags (en millisecondes)

## Fonctionnement

1. Le plugin récupère les feature flags depuis l'API FF-SERLI
2. Les flags sont mis en cache pour la durée spécifiée dans la configuration
3. Les flags sont ajoutés à la requête sortante sous forme d'en-tête HTTP `X-Feature-Flags`

## Installation

1. Compiler le plugin avec SBT :
   ```
   sbt clean assembly
   ```

2. Ajouter le JAR généré à votre installation Otoroshi
3. Configurer le plugin via l'interface d'administration d'Otoroshi

## Développement

Pour contribuer au développement du plugin :

1. Cloner ce dépôt
2. Modifier le code source
3. Exécuter les tests avec `sbt test`
4. Soumettre une pull request

## Licence

Ce projet est sous licence [LICENSE]
