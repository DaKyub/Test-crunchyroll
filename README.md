# CrunchyMAL : compagnon Crunchyroll pour Android TV / NVIDIA Shield

Application Android TV (Kotlin + Compose for TV) qui affiche ton catalogue et ta watchlist Crunchyroll
**avec la note MyAnimeList de chaque série**. La lecture des épisodes est confiée à l'app Crunchyroll officielle.

## Fonctionnalités

- **Note MAL partout** : sur l'accueil, la watchlist, la recherche et la fiche série, avec une note par saison sur la fiche.
- **Watchlist filtrable** :
  - par statut : *Non commencées* (aucun épisode vu), *En cours*, *Terminées / à jour* (tous les épisodes disponibles vus) ;
  - par note MAL minimale (≥ 7, 7.5, 8, 8.5) ;
  - tri par date, par note MAL ou par titre.
- **Progression réelle** : X/Y épisodes vus, calculée depuis l'historique Crunchyroll, toutes versions (VO, VF, VA) confondues.
- **Lecture** : *Reprendre*, *Commencer* ou le clic sur un épisode ouvre l'app Crunchyroll officielle.
  Un appui long sur une carte de l'accueil ou de la watchlist lance directement l'épisode suivant.
- **Correction MAL** : bouton *Corriger MAL* sur la fiche série, ou appui long sur une saison, si la correspondance automatique est fausse.

## Identifiants client Crunchyroll (obligatoire)

L'API exige les identifiants client (en-tête `Basic …`) de l'app Android TV officielle.
Ils ne sont **pas inclus dans le dépôt**. Des valeurs à jour sont publiées par des projets comme
[Centulus/BasicAuthFetch](https://github.com/Centulus/BasicAuthFetch) ou
[smirgol/plugin.video.crunchyroll](https://github.com/smirgol/plugin.video.crunchyroll) (fichier `resources/lib/auth.py`).
Trois façons de les fournir :

- **Secret GitHub (recommandé)** : *Settings → Secrets and variables → Actions → New repository secret*,
  nom `CR_BASIC_AUTH`, valeur `Basic xxxxx=`. Relance ensuite le workflow : l'APK généré les contiendra.
- **Build local** : ajoute `cr.basicAuth=Basic xxxxx=` dans `local.properties`.
- **Dans l'app** : si l'APK a été compilé sans identifiants, l'écran de connexion les demande.
  Ils restent modifiables dans **Paramètres → Avancé**.

## Client ID MyAnimeList (obligatoire pour les notes)

1. Connecte-toi sur <https://myanimelist.net/apiconfig> et clique sur **Create ID**.
2. Remplis le formulaire : *App Type* `other`, nom et description libres,
   *App Redirect URL* `http://localhost`, *Homepage URL* l'adresse de ce dépôt, usage non commercial.
3. Copie le **Client ID** (32 caractères ; le *Client Secret* est inutile).
4. Colle-le dans **Paramètres → MyAnimeList** de l'app, ou ajoute un secret GitHub `MAL_CLIENT_ID` puis relance le build.

## Installation sur le Shield

1. Récupère l'APK dans l'onglet **Actions** du dépôt : dernier build vert, artefact `CrunchyMAL-apk`.
2. Sur le Shield, active les *Sources inconnues* pour l'app qui servira à installer
   (par exemple *Send Files to TV* ou *Downloader*).
3. Installe l'APK. L'app apparaît dans la rangée des applications.
4. Au premier lancement, un code s'affiche : va sur <https://www.crunchyroll.com/activate> depuis ton téléphone et saisis-le.

Pour compiler toi-même : `./gradlew assembleRelease` (il faut le SDK Android, API 35).

## Comment ça marche

| Donnée | Source |
| --- | --- |
| Catalogue, watchlist, historique, playheads | API **non officielle** de Crunchyroll (celle de l'app Android TV) |
| Notes MyAnimeList | API officielle MyAnimeList v2 (Client ID gratuit) |

- **Mises à jour.** L'APK est toujours signé avec la même clé (`app/signing`) : une nouvelle version
  s'installe par-dessus l'ancienne, sans désinstaller ni perdre la connexion.
- **Correspondance avec MAL.** Crunchyroll ne fournit pas d'identifiant MAL. L'app cherche donc par titre
  (titre anglais déduit du slug, puis titre affiché) et garde le candidat le plus proche, à 60 % de similarité minimum.
  Les notes sont mises en cache 7 jours. Tes corrections manuelles sont conservées définitivement.
- **Débit MAL limité.** L'app espace ses requêtes (environ 2 par seconde) : au premier lancement, les notes
  apparaissent progressivement. Ensuite, tout vient du cache.
- **Coût du calcul de progression.** Il faut récupérer saisons, épisodes et playheads de chaque série
  de la watchlist. Le résultat est mis en cache 6 h et recalculé au retour de l'app officielle.

## Limites connues

- L'API Crunchyroll n'est pas publique : elle peut changer sans prévenir, et son usage est contraire aux CGU.
  C'est sans risque pour un usage perso, mais à tes risques.
- Si la connexion échoue (HTTP 401/403), les identifiants client de l'app TV ont probablement changé.
  Mets-les à jour dans **Paramètres → Avancé**, sans recompiler (voir la section sur les identifiants client).
- L'app TV officielle ne documente pas ses liens directs. **Paramètres → Lecture** liste les formats
  qu'elle accepte (✓) et permet d'en choisir un. Si aucun ne fonctionne, Crunchyroll s'ouvre sur son accueil.
- Sur MAL, chaque saison est une fiche distincte. La note « série » correspond à la meilleure correspondance,
  généralement la saison 1. La fiche série affiche la note de chaque saison.
