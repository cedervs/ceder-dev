# World Discovery — Architecture

> Document de référence vivant. Décrit la structure technique retenue et les raisons des choix effectués. À maintenir à jour à chaque décision d'architecture.

## 1. Principes directeurs

1. **Offline-first pour le Normal** : le mode Normal fonctionne intégralement sans réseau. Le backend n'est jamais sur le chemin critique de l'usage normal.
2. **Serveur autorité finale pour le Certified** : les événements/décisions de validation côté serveur constituent l'unique source d'autorité du Certified. Toute cellule, tout score, toute statistique ou tout classement Certified est une **projection dérivée et reconstructible** de ce journal — jamais une deuxième source d'autorité. Le téléphone n'est jamais habilité à déclarer lui-même une découverte officiellement Certified.
3. **Normal et Certified sont deux ensembles de données strictement séparés**, physiquement (tables/modules distincts), jamais promus automatiquement l'un vers l'autre.
4. **Évolutif par construction** : le MVP est une fondation, pas un prototype jetable. Les fonctionnalités V2/V3 (Certified complet, classements, social, multi-plateforme) doivent pouvoir s'ajouter sans reconstruire l'application.
5. **Rien n'est irréversible côté score** : tout score (Certified, futurs classements) est **dérivé/recalculable** à partir d'un journal d'événements, jamais un compteur muté directement.
6. **Monotonie de la découverte** : l'historique indiquant qu'une zone a été découverte est monotone — une découverte historique ne disparaît jamais silencieusement. Cette propriété simplifie la synchronisation multi-appareils (union d'ensembles plutôt que résolution de conflits). Elle ne signifie en revanche pas que la **contribution** de cette découverte à un pourcentage affiché est figée : ce pourcentage peut évoluer explicitement si une nouvelle version du référentiel géographique, du référentiel d'éligibilité ou des règles d'agrégation modifie le calcul. Tout recalcul de ce type doit être explicite et versionné, et ne doit jamais détruire l'historique brut de la découverte dérivée conservée.
7. **Minimisation des données** : pas de conservation indéfinie de GPS brut ; conservation de données dérivées minimales, versionnées.
8. **Versionnement multi-axes** : moteur (`engine_version`), règles d'agrégation de précision, référentiel d'éligibilité — chacun versionné indépendamment, aucun ne doit rendre l'historique utilisateur incompréhensible ou destructible.
9. **Présentation séparée du domaine** : langue, thème, unités n'affectent jamais la découverte, les cellules H3, les scores ou le Certified.
10. **Remplaçabilité des fournisseurs externes** : fournisseur cartographique, et plus généralement toute dépendance externe forte, doit rester isolée derrière une interface interne.
11. **Extensibilité vers une future couche communautaire** : une éventuelle couche de discussions géolocalisées (concept produit futur/non-MVP, voir [discovery-engine.md](discovery-engine.md) §18) devra pouvoir s'ajouter sans reconstruire l'application. Elle devra rester architecturalement indépendante du moteur de découverte, du calcul de pourcentage et du score Certified — elle ne doit jamais influencer ces calculs — et ne devra jamais exposer la position actuelle ou précise d'un utilisateur du seul fait de sa participation à une discussion. Ce principe ne préjuge d'aucun module, table ou endpoint concret : il garantit seulement que l'architecture actuelle n'empêche pas cet ajout futur.

## 2. Stack technique retenue

**Android** : Kotlin, Jetpack Compose, Room/SQLite (stockage local), H3 (grille géospatiale), architecture modulaire.

**Backend** : Python, FastAPI, PostgreSQL, PostGIS, API versionnée (`/v1/...`).

**Cartographie** : solution vectorielle/illustrée remplaçable (ex. MapLibre envisagé) ; la logique métier ne doit jamais être enfermée dans un fournisseur cartographique particulier.

**CI/CD** : GitHub Actions. Code source : GitHub (dépôt accessible, pas de générateur no-code fermé).

Le moteur de découverte doit rester autant que possible indépendant de l'UI et des API Android, testable isolément — un futur passage partiel à Kotlin Multiplatform (partage avec un client iOS) reste une option ouverte grâce à cette isolation, sans être une décision prise maintenant.

## 3. Organisation du repository

```
/app        Application Android
/docs       Cahier des charges, architecture, décisions, moteur de découverte
/backend    API et logique serveur
/tests      Tests d'intégration et scénarios
/.github    CI/CD et automatisation
README.md   Installation, développement et règles du projet
CLAUDE.md   Règles permanentes pour le développement assisté par IA
```

## 4. Architecture Android (modules envisagés)

- `:app` — shell applicatif, injection de dépendances, navigation, thème (clair/sombre/système).
- `:core-database` — Room, entités, DAOs, migrations explicites versionnées dès le premier schéma.
- `:core-location` — permissions, capture GPS en arrière-plan (service/WorkManager), échantillonnage adaptatif.
- `:core-discovery-engine` — logique pure Kotlin (sans dépendance Android), testable isolément : classification déplacement/présence, conversion H3, agrégation multi-niveaux de précision. Voir [discovery-engine.md](discovery-engine.md).
- `:core-auth` — interface `AuthProvider` ; implémentations Google et e-mail (OTP) ; emplacement réservé, non implémenté, pour Apple.
- `:core-sync` — pattern outbox local, worker de synchronisation en arrière-plan, restauration complète lors d'une première connexion sur un nouvel appareil.
- `:core-network` — client API, DTOs, endpoints versionnés.
- `:feature-map`, `:feature-journey`, `:feature-progress`, `:feature-profile` — UI Compose.
- `:feature-certified` — module séparé, purement consommateur d'un statut confirmé par le serveur ; ne peut jamais marquer localement une cellule comme Certified.

Convention transverse : aucun texte utilisateur codé en dur dans un composable (voir §9).

## 5. Architecture backend (domaines)

- `auth` — vérification des tokens Google, émission de session/JWT propre à l'application quel que soit le fournisseur d'identité.
- `users` — compte, statut, export des données, suppression de compte.
- `discoveries` — synchronisation des cellules Normal (upsert idempotent).
- `certified` — seul écrivain autorisé du statut `COUNTED` ; machine à états de validation (voir [certified-mode.md](certified-mode.md)).
- `sync` — push/pull par curseur par appareil, restauration complète pour un nouvel appareil.
- `profile` — pseudo, avatar, pays représenté, statut public/privé.
- `eligibility` / `geo` — référentiel pays, correspondance H3 → pays, référentiel d'éligibilité (voir §8).

## 6. Note sur la nature des schémas présentés

> Les structures nommées dans ce document — notamment `discovery_cells`, `cell_eligibility`, `eligibility_signals`, `auth_identities`, `recovery_contacts`, `user_country_certified_stats`, `certified_events`, et toute autre table ou vue décrite ci-dessous — sont des **modèles conceptuels/proposés**, destinés à exprimer les invariants d'architecture avant la conception effective des migrations. Leurs noms, colonnes et clés exactes pourront évoluer si les tests ou des contraintes techniques le justifient. **Les invariants métier qu'elles documentent doivent en revanche être préservés** quelle que soit la forme physique finale (séparation Normal/Certified, unicité par triplet, autorité serveur pour Certified, versionnement, etc.). Aucun de ces schémas ne doit être traité comme un contrat physique irréversible avant la première migration réelle.

## 7. Modèle de comptes, authentification, récupération et profil

### Séparation en quatre surfaces distinctes

1. **`users`** — identité interne immuable, données de compte (statut, date de création). Jamais exposé publiquement tel quel.
2. **`auth_identities`** *(user_id, provider, provider_subject_id, email, verified_at)* — une ligne par méthode de connexion liée (Google, e-mail OTP, futur Apple). Un même `user_id` peut lier plusieurs méthodes ; aucune méthode supplémentaire ne crée un nouveau compte.
3. **`recovery_contacts`** *(user_id, type, value, verified_at)* — table séparée et **facultative** pour des moyens de récupération ajoutés ultérieurement (téléphone vérifié, e-mail secondaire), non utilisables comme méthode de connexion directe par défaut, non requis à l'inscription.
4. **`profile`** *(user_id, pseudo, avatar_ref, pays_represente, is_public)* — surface **publique**, strictement séparée des trois précédentes.

### Sessions et appareils

`devices` / sessions (refresh tokens révocables par appareil) : permet de lister et révoquer l'accès d'un appareil précis. Nécessaire pour une future interface « gérer mes appareils » et pour les procédures de récupération/changement d'e-mail.

### Règle de cloisonnement critique

Aucun flux de récupération de compte ne doit interroger ni accepter en entrée une information publique (pseudo, pays représenté, avatar). La surface de récupération (`auth_identities`, `recovery_contacts`) est architecturalement isolée de la surface publique (`profile`) : aucune jointure ni logique ne doit permettre de remonter de l'une à l'autre dans le sens « info publique → contrôle du compte ».

### Authentification e-mail

Direction retenue : **OTP par e-mail** (code à usage unique), pas de mot de passe World Discovery classique — voir comparaison et justification dans la synthèse de décisions en fin de document (§13).

### Restauration après changement d'appareil

Réutilise le mécanisme de synchronisation (pull par curseur) en mode « pull complet » à la première connexion sur un nouvel appareil : la base Room locale est repeuplée depuis le backend, puis le fonctionnement offline-first reprend normalement. Les données jamais synchronisées avant la perte d'un appareil ne sont pas récupérables — conséquence assumée du modèle offline-first.

## 8. Modèle de découverte, précision et éligibilité

### Représentation canonique unique

World Discovery conserve une **représentation canonique unique**, suffisamment fine pour permettre de dériver Easy, Standard et Precision sans maintenir trois historiques indépendants — **c'est la seule chose actée à ce stade**. Easy, Standard et Precision sont des **agrégations dérivées** de cette même vérité (fonction d'agrégation propre à chaque niveau, pas nécessairement une relation parent/enfant H3 stricte pour Precision, qui admet une tolérance spatiale).

**La structure exacte de cette représentation canonique, sa résolution H3 précise et les règles d'agrégation exactes restent ouvertes** et doivent être étudiées/testées dans [discovery-engine.md](discovery-engine.md) avant d'être figées. Il n'est **pas** décidé qu'elle corresponde nécessairement à « la résolution H3 la plus fine supportée » — H3 reste la technologie géospatiale validée, mais aucune résolution n'est choisie à ce stade.

Une fois cette représentation figée, aucune donnée supplémentaire ne sera nécessaire pour supporter un changement de niveau après plusieurs années d'usage : ce sera un changement de vue de lecture, pas une nouvelle capture.

### `discovery_cells` (modèle conceptuel)

`(user_id, h3_index, mode)` avec `mode ∈ {normal, certified}`, **clé unique sur ce triplet** — invariant à préserver quelle que soit la structure physique finale. Une cellule peut exister indépendamment dans les deux modes. Chaque enregistrement porte un `engine_version`.

Pour `mode = certified`, cette table (ou son équivalent final) est une **projection dérivée et reconstructible** de la seule autorité réelle : le journal des événements de validation côté serveur (`certified_events` ou modèle équivalent retenu — voir [certified-mode.md](certified-mode.md)). Elle ne constitue jamais une deuxième source d'autorité indépendante, et le client ne peut jamais y écrire directement ni déclarer lui-même une cellule officiellement Certified.

### Référentiel d'éligibilité

- `cell_eligibility` *(h3_index, resolution, status, eligibility_version)* avec `status ∈ {ELIGIBLE, RESTRICTED_EXCLUDED, UNKNOWN}`, défini à la résolution de la représentation canonique puis agrégé vers les niveaux supérieurs par le même mécanisme que l'agrégation des découvertes.
- `eligibility_signals` *(h3_index, signal_type, source, value)* — table de **signaux séparée de la décision finale**. La présence de Google Street View ou d'une couverture automobile similaire **ne constitue jamais une preuve d'accessibilité légale** ; elle peut au mieux devenir un signal auxiliaire parmi d'autres (données administratives/légales, tags d'accès, réserves, zones militaires connues). Seul le `status` résolu dans `cell_eligibility` est consommé par le calcul de pourcentage et l'affichage.
- Versionné **indépendamment** de `engine_version` : l'éligibilité peut changer pour des raisons étrangères au moteur.
- Les cellules `RESTRICTED_EXCLUDED` n'entrent **jamais** ni au numérateur ni au dénominateur : elles ne sont jamais nécessaires pour atteindre 100 %. Un score de 100 % correspond à l'objectif de « territoire éligible selon la version du référentiel applicable », pas à chaque mètre carré physique.
- **La composition exacte du dénominateur reste ouverte**, en particulier le traitement de `UNKNOWN` : il ne doit être **ni inclus ni exclu arbitrairement** avant décision documentée — décider par défaut son exclusion (ou son inclusion) pourrait rendre artificiellement un territoire plus facile, ou plus difficile, à compléter selon la qualité des données d'accessibilité disponibles. Décision à étudier explicitement dans [discovery-engine.md](discovery-engine.md).
- Un changement de version du référentiel n'efface pas l'historique ; une recomputation vers une nouvelle version est un job explicite, jamais automatique/silencieux.

### Référentiel géographique pour pays et classements

`countries` (référentiel versionné) et `h3_country_mapping` (cellule → pays, calculé une fois, pas à la volée) — nécessaires à la fois pour le pourcentage par pays affiché à l'utilisateur et pour les futurs classements (§11).

## 9. Internationalisation (architecture)

- Aucun texte utilisateur en dur : ressources natives (strings.xml / `stringResource`), convention imposée à toutes les `:feature-*`.
- Paramètre de langue stocké dans les settings utilisateur (donc synchronisé multi-appareils comme le reste des paramètres), avec valeur par défaut « suivre la langue du système ».
- Noms géographiques localisables : table de traduction associée au référentiel `countries` (`country_id, locale, name`), avec repli langue utilisateur → anglais → nom natif.
- Messages backend : codes/clés sémantiques renvoyés par l'API, traduction entièrement côté client. Seuls les contenus réellement émis vers un canal externe (ex. code OTP par e-mail) nécessitent un catalogue de traduction minimal côté backend.
- Séparation stricte : la langue est une couche de présentation uniquement, jamais consommée par `:core-discovery-engine`, `:core-database` ou le backend métier.

## 10. Synchronisation multi-appareils et idempotence

Grâce à la monotonie de la découverte, la déduplication se fait par la contrainte unique `(user_id, h3_index, mode)` côté serveur : un push est un **upsert « insert if not exists »**. Deux appareils du même compte remontant la même cellule ne créent jamais de doublon ni de double comptage.

## 11. Préparation architecturale des futurs classements publics (non implémentés)

Basés **exclusivement** sur les données Certified.

- Agrégats dérivés, jamais un compteur mutable : `user_country_certified_stats` / équivalent mondial (vues matérialisées ou table recalculée périodiquement à partir de `certified_events`), incluant dès sa conception une dimension `precision_level` (même si seul `standard` est peuplé initialement).
- Signaux conservés pour un futur départage en cas d'égalité (ex. plusieurs utilisateurs à 100 % d'un pays) : horodatage de la cellule validée qui complète les 100 %, pourcentage mondial Certified de l'utilisateur — sans qu'aucune règle de tri ne soit câblée dans le stockage.
- Ne considère que des utilisateurs au profil public et des cellules au statut `COUNTED`.
- Surface publique exposée : rang, pseudo, avatar, pays représenté, statistiques Certified appropriées — uniquement des champs de `profile`, jamais de jointure directe vers `users`, `auth_identities` ou des données de localisation.

## 12. Rétention et versionnement

- Pas de conservation indéfinie de GPS brut : rétention courte des échantillons, dans une table locale dédiée (`raw_location_buffer`), purgée après traitement par le moteur.
- Données dérivées minimales conservées : cellules H3, timestamps/métadonnées nécessaires, `engine_version`, informations de validation.
- Les migrations de base de données (Room comme PostgreSQL) ne doivent jamais détruire l'historique utilisateur — migrations additives par défaut, jamais destructives.
- Conséquence assumée : le recalcul d'une découverte avec de nouvelles règles n'est possible que dans la fenêtre de rétention brute ; au-delà, une découverte ancienne reste figée sous sa version d'origine.

## 13. Décisions transverses déjà actées

- **Authentification e-mail : OTP recommandé** plutôt que mot de passe classique ou magic link. Un mot de passe impose stockage sécurisé, flux de réinitialisation, et expose au bourrage d'identifiants — surface de responsabilité inutile ici. Entre OTP et magic link, l'OTP (code à 6 chiffres) évite les problèmes de deep-linking entre client mail et application, se généralise sans friction à un futur client iOS, et reste cohérent avec l'absence totale de mot de passe World Discovery à mémoriser.
- Le mode Normal ne doit jamais nécessiter une connexion réseau pour fonctionner au quotidien ; le backend sert aux comptes, à la sauvegarde, à la synchronisation, au multi-appareils, et plus tard à la validation Certified.

## 14. Renvois

- Règles précises du moteur de découverte, hypothèses à tester et décisions ouvertes : [discovery-engine.md](discovery-engine.md).
- Détails du mode Certified : [certified-mode.md](certified-mode.md).
- Séquencement de développement : [roadmap.md](roadmap.md).
- Vision produit et UX : [product-spec.md](product-spec.md).
- Concept futur de communauté/discussions géolocalisées (non-MVP) : [discovery-engine.md](discovery-engine.md) §18 et [product-spec.md](product-spec.md) §7.
