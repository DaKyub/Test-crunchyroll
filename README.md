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
| Notes MyAnimeList | [Jikan](https://jikan.moe), API publique de MAL sans clé |

- **Correspondance avec MAL.** Crunchyroll ne fournit pas d'identifiant MAL. L'app cherche donc par titre
  (titre anglais déduit du slug, puis titre affiché) et garde le candidat le plus proche, à 60 % de similarité minimum.
  Les notes sont mises en cache 7 jours. Tes corrections manuelles sont conservées définitivement.
- **Débit Jikan limité.** Jikan accepte environ 1 requête par seconde : au premier lancement, les notes apparaissent
  progressivement. Ensuite, tout vient du cache.
- **Coût du calcul de progression.** Il faut récupérer saisons, épisodes et playheads de chaque série
  de la watchlist. Le résultat est mis en cache 6 h et recalculé au retour de l'app officielle.

## Limites connues

- L'API Crunchyroll n'est pas publique : elle peut changer sans prévenir, et son usage est contraire aux CGU.
  C'est sans risque pour un usage perso, mais à tes risques.
- Si la connexion échoue (HTTP 401/403), les identifiants client de l'app TV ont probablement changé.
  Mets-les à jour dans **Paramètres → Avancé**, sans recompiler (voir la section sur les identifiants client).
- L'ouverture d'un épisode précis passe par le lien `https://www.crunchyroll.com/watch/<id>`.
  Si l'app TV officielle ne gère pas ce lien, CrunchyMAL ouvre Crunchyroll sur son accueil.
- Sur MAL, chaque saison est une fiche distincte. La note « série » correspond à la meilleure correspondance,
  généralement la saison 1. La fiche série affiche la note de chaque saison.
