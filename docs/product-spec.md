# World Discovery — Spécification produit

> Document de référence vivant. Toute nouvelle décision produit doit être répercutée ici avant d'être implémentée.

## 1. Vision

World Discovery n'est pas un GPS classique ni un tracker de fitness. Le principe central est de construire progressivement une **carte personnelle du monde réellement découvert** par l'utilisateur au cours de sa vie, à partir de ses déplacements normaux.

La question à laquelle répond le produit n'est pas « Où suis-je ? » mais :

> **« Qu'est-ce que j'ai découvert ? »**

Principe UX directeur :

> **« Simple à comprendre. Profonde à explorer. »**

Boucle d'engagement visée : Voyager → découvrir → débloquer → regarder sa carte → avoir envie de découvrir davantage.

## 2. Expérience utilisateur

- Carte **illustrée**, jamais satellite. Interface simple, légèrement ludique, 3–4 couleurs principales.
- Mode clair, mode sombre, et option « suivre le système ».
- Écran principal dominé par la carte, avec le **pourcentage mondial découvert visible immédiatement**.
- Navigation géographique progressive : **Monde → Continent → Pays**, puis une hiérarchie qui **dépend du pays** en dessous (ex. France : Région → Département → Commune → Zone ; USA : État → Comté → Ville → Zone — détails dans [discovery-engine.md](discovery-engine.md) §19). Plus on zoome, plus le détail apparaît ; aucun écran ne doit être surchargé.
- Option future « Détails de voyage » / « Noms de lieux » (désactivée par défaut) : une fois activée, affiche davantage de noms de villes/villages/régions/lieux autour de l'historique de voyage de l'utilisateur, avec une densité de libellés adaptative au zoom pour éviter l'encombrement à l'échelle mondiale. Purement visuel — n'affecte jamais H3, le pourcentage d'exploration, le XP ni le Certified.
- Code couleur indicatif : gris = non découvert ; orange = visité ; orange plus intense = exploré ; lignes = trajets ; points = lieux.
- Les trajets affichés sont **simplifiés visuellement**, jamais rendus comme une trace GPS brute illisible.
- 4 sections principales maximum : **Map, Journey, Progress, Profile**.
- Retour de voyage : écran « New Discoveries » avec animation montrant les nouvelles zones débloquées.
- Clic sur un pays → zoom animé + pourcentage du pays. Clic sur une région → zoom + zones découvertes + lieux associés. Clic sur un lieu → informations, date, preuve/confiance, souvenirs éventuels.
- Fil de navigation discret pour remonter Monde / Pays / Région / Lieu.

## 3. Modèle de découverte (vue produit)

- La grille interne utilise **H3**, entièrement invisible pour l'utilisateur.
- Le système distingue conceptuellement **traversée**, **explorée** et **visitée** (nuances exactes à spécifier dans [discovery-engine.md](discovery-engine.md)).
- Une cellule ne peut être comptée qu'une seule fois **au sein d'un même ensemble** (Normal ou Certified — voir §4).
- Un vol long-courrier ne doit pas débloquer les pays et océans survolés : le moteur distingue présence au sol et transport rapide/aérien, et privilégie la preuve de présence dans une zone plutôt que le tracé exact.
- La découverte n'est **pas limitée au réseau routier** : marcher hors route, explorer une plage, une forêt, une montagne ou tout autre territoire accessible doit pouvoir produire une découverte.
- **100 % d'un territoire ne signifie pas « avoir posé le pied sur chaque mètre carré physique »**. Certaines zones sont privées, militaires, interdites, dangereuses ou non destinées au public : elles ne doivent jamais être nécessaires pour atteindre 100 %, et l'application ne doit jamais encourager à y pénétrer. Un score de 100 % signifie « 100 % du territoire **éligible** selon la version du référentiel utilisée » (détails techniques dans [architecture.md](architecture.md) et [discovery-engine.md](discovery-engine.md)).
- World Discovery **n'est jamais une autorisation d'accès** à un lieu : l'utilisateur reste seul responsable du respect des lois, propriétés privées, fermetures et signalisation locales.

### Niveaux de précision

Trois niveaux conceptuels minimum, **Standard** étant le niveau par défaut :

| Niveau | Rôle |
|---|---|
| **Easy** | Agrégation large, expérience plus accessible. |
| **Standard** | Niveau principal de l'expérience World Discovery. |
| **Precision / Hard** | Granularité fine : rend visibles les petites portions jamais réellement parcourues (petites routes, chemins, quartiers, secteurs ruraux, plages, portions de forêt…). |

- Il n'existe **pas trois historiques de tracking indépendants** : les trois niveaux dérivent d'une seule représentation canonique suffisamment fine (détails dans [architecture.md](architecture.md)).
- Changer de niveau après plusieurs années d'usage ne fait **jamais perdre l'historique** ni repartir à zéro.
- Une zone découverte en Easy n'implique pas que ses sous-zones soient découvertes en Standard ou Precision.
- Le pourcentage est calculable **séparément pour chaque niveau**.
- En Precision/Hard, une tolérance spatiale/règle de couverture est admise (pas d'exigence de passage exact au centre d'une cellule) — valeurs non figées, à déterminer par tests.

## 4. Normal et Certified

Deux ensembles **strictement distincts**. Une même cellule géographique peut être découverte en Normal, validée en Certified, présente dans les deux, ou dans un seul. **Une cellule Normal ne devient jamais automatiquement Certified.**

### Mode Normal
- Tracking automatique en arrière-plan, **entièrement offline-first**.
- Ajout manuel possible uniquement avec preuve (photo géolocalisée, historique de localisation, itinéraire/GPX, justificatif de voyage ou équivalent) — *incrément post-MVP*.
- Import de voyages antérieurs quand des données exploitables existent — *incrément post-MVP*.
- Alimente la carte personnelle / Lifetime Discovery ; ne modifie jamais le score Certified.
- Un indicateur de confiance, de provenance ou de qualité de preuve pourra éventuellement être affiché lorsque pertinent ; sa forme exacte, son mode de calcul et la décision même de l'afficher restent à définir avant implémentation.

### Mode Certified
- L'acquisition de **nouvelles** découvertes Certified commence uniquement lorsque l'utilisateur active le mode Certified. Les découvertes antérieures effectuées en Normal ne sont **jamais** converties rétroactivement en Certified.
- Une découverte Certified doit provenir du tracking en direct **ou** d'une récupération manuelle/historique appuyée sur des preuves suffisamment solides (voir [certified-mode.md](certified-mode.md) §9), et dans tous les cas passer par une **validation côté serveur** : le téléphone est une source non fiable, le serveur est l'autorité finale.
- Récupération manuelle/historique (*incrément post-MVP*) : deux résultats de confiance visibles — **Certified** (preuves validées côté serveur) ou **Non-certifié** (souvenir personnel sans preuve suffisante, affiché dans un style visuel distinct, jamais compté dans les classements officiels). Détails complets dans [certified-mode.md](certified-mode.md) §9.
- Bascule simple sur la carte (« Afficher les découvertes non certifiées ») : OFF = Certified uniquement ; ON = Certified + Normal/non-certifié, ce dernier dans un style visuel clairement distinct. Voir [certified-mode.md](certified-mode.md) §11.
- Détails complets de la machine à états et des signaux d'intégrité dans [certified-mode.md](certified-mode.md).
- Une cellule Certified déjà validée **reste acquise** si l'utilisateur désactive puis réactive le mode ; la désactivation arrête uniquement l'acquisition de nouvelles découvertes pendant cette période.
- Le score Certified n'est jamais achetable.

## 5. Comptes, profil et confidentialité (vue produit)

- Compte utilisateur avec authentification : **Google** et **e-mail** au minimum dès le MVP ; architecture prête pour **Sign in with Apple** (non implémenté en MVP Android).
- Un même compte conserve découvertes, voyages, statistiques et paramètres lors d'un changement d'appareil.
- Un compte peut lier plusieurs méthodes d'authentification (ex. Google + e-mail) pour réduire le risque de perte d'accès.
- Profil utilisateur, minimum : identifiant interne immuable, pseudo public, avatar (illustré/prédéfini au départ), pays représenté (choix déclaratif, **pas** une vérification de nationalité), statut public/privé.
- Extensions futures du profil (non MVP) : courte biographie, liens externes/réseaux sociaux.
- Aucune collecte d'âge exact, sexe ou autre donnée personnelle sans besoin produit clairement défini.
- Les données sensibles (position actuelle, domicile probable, GPS précis, trajets détaillés, informations d'authentification/récupération) **ne sont jamais publiques par défaut**.
- Export des données et suppression de compte : requis avant toute mise en production réelle.
- Conformité aux obligations de confidentialité applicables, notamment pour des utilisateurs européens.
- Décisions différées, à traiter avant les fonctionnalités concernées : règles de changement pseudo/avatar/pays, utilisateurs mineurs, signalement/blocage pour de futures fonctions sociales, unités/formats régionaux, périmètre exact public/privé au-delà du minimum posé ici.

## 6. Internationalisation

- Prévue **dès le premier écran**, pas ajoutée après coup.
- Anglais = langue de référence ; français prévu dès les premières versions.
- Aucun texte utilisateur codé en dur ; système de ressources/localisation natif.
- Choix de langue dans les paramètres, avec option « suivre la langue du système ».
- Noms géographiques localisés quand les données le permettent, avec repli raisonnable.
- La langue n'influence **jamais** la découverte, les cellules H3, les scores ou le Certified — uniquement une couche de présentation.
- Prévu plus tard : localisation des formats de dates, heures, unités, nombres.

## 7. Partage, classements et fonctions sociales (futur)

- Profil public optionnel avec score Certified, pays et régions, **sans exposer la localisation précise** par défaut.
- Carte partageable pour réseaux sociaux.
- Futurs classements publics basés **exclusivement sur Certified** (le Normal n'alimente jamais un classement compétitif) : classement mondial par pourcentage Certified du monde, classements par pays par pourcentage Certified du pays. Détails architecturaux dans [architecture.md](architecture.md).
- Classement Certified officiel initial : niveau **Standard** uniquement. Un futur classement Certified Precision est envisageable, mais deux utilisateurs de niveaux de précision différents ne sont jamais comparés dans un même classement.
- Égalités possibles (plusieurs utilisateurs à 100 % d'un pays) : règle de départage volontairement non tranchée pour l'instant.
- Un profil privé n'apparaît pas nécessairement dans les classements publics — décision de confidentialité à préciser avant activation de cette fonctionnalité.
- Achievements envisageables après stabilisation du moteur — voir aussi le système XP/collections ci-dessous, qui formalise ce concept.
- Le score Certified n'est jamais achetable.
- Si de véritables interactions sociales sont ajoutées plus tard (commentaires, amis, comparaisons…), blocage/signalement/modération devront être définis avant mise en production.

### Exploration % vs XP (futur, non-MVP)

Séparation produit stricte, détail complet dans [discovery-engine.md](discovery-engine.md) §21 :

- **Pourcentage d'exploration** répond à « quelle part du monde ai-je géographiquement exploré ? » ; **XP** (futur) répond à « quelles choses notables ai-je découvertes ? ». Les deux ne sont **jamais** mélangés — visiter un monument n'augmente jamais le pourcentage au-delà de la surface physique réellement parcourue.
- Le XP pourra récompenser des lieux notables sélectionnés strictement (monuments, sites historiques, musées majeurs, merveilles naturelles, points de vue, parcs, plages, châteaux, sommets…), avec une importance pouvant être contextuelle (un site local peut compter pour son village sans rivaliser avec un monument mondialement iconique).
- Aucune valeur de XP n'est validée ; le XP n'est pas une monnaie ; une éventuelle dépensabilité future reste ouverte.
- **Futur / non-MVP**, mais l'architecture doit rester extensible pour l'accueillir sans toucher au calcul du pourcentage ni du score Certified.

### Repères communautaires « immanquables » (futur, non-MVP)

Concept distinct des lieux XP officiels ci-dessus et du calcul de découverte — détail complet dans [discovery-engine.md](discovery-engine.md) §22 :

- Lieux recommandés par les voyageurs (point de vue caché, plage locale, ruelle, crique, petit site historique…) — ne donnent **ni** pourcentage géographique **ni** XP par défaut.
- Chaque utilisateur ne peut recommander/aimer un même lieu qu'une seule fois ; une preuve de proximité physique est préférée avant de permettre une recommandation.
- Démarrage à froid : le premier utilisateur crée/recommande le lieu, qui n'est **pas** immédiatement visible publiquement ; un autre utilisateur physiquement proche peut être informé discrètement qu'un lieu proche a déjà été recommandé, pour aider à confirmer plutôt que dupliquer. Fusion intelligente des propositions proches en utilisant plus que la seule proximité (nom, type, description, autres signaux), car deux lieux réels peuvent n'être séparés que de 20–30 mètres.
- Un seuil d'environ 10 utilisateurs indépendants est une **hypothèse de départ uniquement** pour la confirmation publique — **non tranché**, et ne doit pas devenir le seul critère : modération, anti-spam et vérifications de confiance restent nécessaires.
- Cycle de vie interne envisageable (nommage provisoire) : Nouveau → Confirmé → Immanquable communautaire. Le premier créateur pourra recevoir un crédit « Découvert par @Pseudo » une fois le lieu réellement confirmé/public — aucun crédit s'il est rejeté comme spam ou fusionné dans un lieu déjà existant.
- La popularité communautaire ne doit **jamais** promouvoir automatiquement un lieu vers la base XP officielle. Visibilité sur la carte progressive avec le zoom.
- **Futur / non-MVP.**

### Souvenirs personnels de voyage (futur, non-MVP)

Détail complet dans [discovery-engine.md](discovery-engine.md) §15 :

- Court texte/commentaire, emoji/icône, photo, potentiellement courte vidéo, date — attachés à un lieu visité.
- **Privés par défaut** ; aucun effet sur le pourcentage d'exploration, le XP ou le score Certified.
- Couche/bascule « Souvenirs » optionnelle sur la carte, visibilité progressive avec le zoom.
- Un partage explicite futur de souvenirs sélectionnés pourra exister, mais rien ne devient public automatiquement ; les localisations précises/sensibles restent privées.
- **Futur / non-MVP.**

### Communauté / discussions géolocalisées (futur, non-MVP)

World Discovery pourra un jour inclure une couche communautaire de discussions **centrées sur les lieux** — pas un réseau social générique. Détails complets dans [discovery-engine.md](discovery-engine.md) §18 ; résumé produit ici pour cohérence de la vision :

- Les discussions peuvent être associées à une entité géographique : pays, région, ville, zone ou lieu précis.
- Objectif : permettre aux utilisateurs de poser des questions, partager conseils, expériences, recommandations, avertissements, photos et informations pratiques de voyage sur une destination (ex. « où loger à Bangkok ? », quoi visiter/éviter, conseils de transport…).
- Un utilisateur consultant ou préparant une destination pourra accéder aux discussions liées à cette entité géographique.
- Distinction stricte avec les souvenirs personnels (§2, §8) : les notes/photos/souvenirs **personnels** restent privés à l'historique de voyage de l'utilisateur sauf partage explicite ; les discussions **communautaires** sont intentionnellement publiées pour les autres utilisateurs. Ce sont deux systèmes séparés.
- L'activité communautaire n'influence **jamais** le pourcentage de découverte ni le score Certified.
- La position actuelle ou précise d'un utilisateur n'est **jamais** exposée du seul fait de sa participation à une discussion sur un lieu.
- Avant toute implémentation réelle, doivent être définis : modération, signalement, blocage, prévention du spam/abus, confidentialité, comportement profil public/privé, confidentialité de localisation, règles de contenu, édition/suppression, et considérations mineurs/sécurité.
- **Futur / non-MVP** : concept produit uniquement à ce stade, aucune implémentation prévue avant que les points ci-dessus soient explicitement tranchés.

## 8. Ce qui n'est volontairement pas construit maintenant

Réseau social complet, messagerie, communauté/discussions géolocalisées, repères communautaires immanquables, système XP/collections, classement mondial/pays, Certified complet, achievements, système avancé de souvenirs, import historique complet avec preuve, récupération manuelle/historique de découvertes (Certified/Non-certifié), avatar complexe type Bitmoji, bio/liens externes, Sign in with Apple réellement implémenté, monétisation complexe, globe 3D sophistiqué.

## 9. Principe produit à préserver

> Simple à comprendre. Profonde à explorer.
> L'application ne répond pas à « Où suis-je ? » mais à « Qu'est-ce que j'ai découvert ? ».

Toute nouvelle fonctionnalité doit être évaluée à l'aune de ce principe avant d'être ajoutée.
