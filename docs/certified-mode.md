# World Discovery — Mode Certified

> Document de référence vivant. Le mode Certified n'est **pas** implémenté dans le premier MVP (voir [roadmap.md](roadmap.md)) ; ce document fixe les principes que l'implémentation future devra respecter dès sa première version.

## 1. Principe fondamental

Le mode Certified est un ensemble de données **strictement séparé** du mode Normal (voir [architecture.md](architecture.md) §8). Une cellule Certified doit provenir soit du tracking en direct, soit d'une récupération historique appuyée sur des preuves suffisamment solides (voir §9) — dans les deux cas, elle doit passer par une validation côté serveur : **le téléphone est considéré comme une source de données non fiable, le serveur est l'autorité finale.** Le client ne peut jamais décider seul qu'une cellule est Certified, et une cellule Normal ne devient jamais automatiquement Certified. Toute donnée, classification ou estimation produite côté client — y compris les résultats du moteur de découverte local — susceptible d'influencer une décision Certified doit être considérée comme **non fiable tant qu'elle n'a pas été validée côté serveur**.

L'acquisition de **nouvelles** découvertes Certified commence uniquement lorsque l'utilisateur active le mode Certified. Les découvertes antérieures effectuées en Normal ne sont **jamais** converties rétroactivement en Certified. Les découvertes Certified déjà `COUNTED` restent acquises lors d'une désactivation/réactivation ultérieure (voir §4). Le score Certified est toujours dérivé des données validées côté serveur et n'est **jamais** un compteur mutable (voir §3).

## 2. Machine à états

Logique conceptuelle des événements de validation :

```
DETECTED → PENDING → VALIDATED → COUNTED
```

avec une branche d'échec :

```
DETECTED / PENDING → SUSPICIOUS → REJECTED
```

- **DETECTED** : le client soumet une découverte candidate au serveur.
- **PENDING** : en attente d'évaluation serveur.
- **VALIDATED** : les signaux disponibles sont jugés cohérents.
- **COUNTED** : la cellule est officiellement acquise en Certified, reflétée dans `discovery_cells(mode=certified)`.
- **SUSPICIOUS** : anomalie détectée, nécessite investigation ou preuve supplémentaire.
- **REJECTED** : découverte invalidée, jamais comptée.

Le détail exact des transitions, délais et critères de passage d'un état à l'autre reste à spécifier avant implémentation — ce document fixe le principe de la machine à états, pas ses paramètres.

## 3. Score recalculable, jamais un compteur irréversible

Les événements de validation côté serveur (`certified_events`, ou le modèle équivalent finalement retenu — voir la note sur les schémas conceptuels dans [architecture.md](architecture.md) §6) constituent **l'unique source d'autorité** du Certified. Toute cellule, tout score, toute statistique ou tout classement Certified est une **projection dérivée et reconstructible** de ce journal — jamais une source d'autorité indépendante, et jamais modifiable directement.

Le score Certified (mondial, par pays, ou toute future statistique dérivée) est **toujours calculé à partir de ce journal**, jamais stocké comme un compteur incrémenté directement (`score += x` est explicitement proscrit). Cette contrainte permet :

- de corriger une décision de validation a posteriori sans corrompre un état ;
- de faire évoluer les règles d'anomalie/anti-triche sans devoir « réparer » un compteur ;
- de fournir la base des futurs classements publics (§6) sans duplication de logique.

## 4. Persistance à la désactivation/réactivation

Une cellule Certified déjà `COUNTED` **reste acquise** si l'utilisateur désactive puis réactive le mode Certified. La désactivation arrête uniquement l'acquisition de **nouvelles** découvertes Certified pendant cette période — aucune suppression, aucune remise à zéro.

## 5. Signaux d'intégrité (liste ouverte, non exhaustive)

Le mode Certified pourra exploiter progressivement, sans que cette liste soit figée ni complète dès la première version :

- cohérence temporelle des événements ;
- cohérence de vitesse et d'altitude ;
- cohérence de trajectoire ;
- détection de replay d'événements ;
- détection de déplacement physiquement impossible ;
- détection de localisation simulée, lorsque techniquement détectable ;
- signaux d'intégrité applicative/appareil (ex. Play Integrity côté Android, à étudier) ;
- autres signaux futurs, ajoutés sans nécessiter de refonte grâce à la séparation du module Certified.

**Le système ne doit jamais être présenté comme impossible à tricher à 100 %.** L'objectif est un système fortement résistant à la fraude, pas une garantie absolue.

## 6. Base des futurs classements publics (non implémentés)

Les classements publics futurs (mondial et par pays) seront basés **exclusivement** sur les données Certified `COUNTED`, jamais sur le mode Normal. Voir [architecture.md](architecture.md) §11 pour le modèle d'agrégation prévu (vues dérivées, dimension `precision_level`, signaux de départage conservés sans règle figée). Rappels produit :

- Classement officiel initial : niveau de précision **Standard** uniquement.
- Un classement Certified Precision est envisageable plus tard, sans jamais mélanger deux niveaux de précision dans un même classement.
- Le classement mondial et le classement par pays sont repoussés jusqu'à ce que le système anti-triche soit jugé suffisamment robuste — ce seuil de maturité est une décision produit à prendre explicitement plus tard, pas un critère technique figé ici.
- Un profil privé n'apparaît pas nécessairement dans les classements publics (paramètre de confidentialité à préciser avant activation).

## 7. Tests avant toute activation compétitive

Avant d'activer un classement ou toute autre fonctionnalité Certified à caractère compétitif, les scénarios de fraude suivants doivent être couverts par des tests explicites : localisation simulée, téléportation, horloge manipulée, replay d'événements, base locale modifiée, et tout autre scénario jugé pertinent au moment de l'implémentation.

## 8. Perte GPS temporaire et candidats reconstruits

**[Validé — 2026-08-29]** Si la localisation est fiable en un point A, disparaît temporairement, puis redevient fiable en un point B, le moteur peut proposer une reconstruction plausible du trajet entre A et B (voir [discovery-engine.md](discovery-engine.md) §24 pour le principe général). Une coupure courte et cohérente ne doit pas interrompre la continuité Certified : le segment reconstruit peut être soumis comme candidat `DETECTED`, au même titre qu'un segment observé directement — il **reste soumis à la même validation serveur complète**, la reconstruction n'est qu'une méthode de génération du candidat, jamais un raccourci de validation.

**[Validé]** La provenance (observation directe vs reconstruction automatique) doit rester **distinguable en interne** sur chaque cellule/segment Certified, pour permettre un recalcul futur si l'algorithme de reconstruction évolue (cohérent avec §3 : rien n'est un compteur irréversible).

**[OUVERT — à calibrer]** Limites exactes de durée de coupure et de degré d'ambiguïté au-delà desquelles le serveur doit refuser ou dégrader la confiance d'un candidat reconstruit.

## 9. Récupération manuelle/historique de découvertes

**[Validé — cadrage futur]** Les utilisateurs doivent pouvoir reconstituer des voyages passés lorsque le tracking automatique n'a pas fonctionné (batterie vide, téléphone oublié, localisation désactivée, échec technique) ou pour un voyage antérieur à l'installation de l'application. Deux résultats de confiance **visibles** pour l'utilisateur : **Certified** ou **Non-certifié** (affichage détaillé dans [product-spec.md](product-spec.md) §4 et §11 ci-dessous).

**[Validé]** Non-certifié : ajout manuel basé sur le souvenir de l'utilisateur, sans preuve suffisante — n'entre **jamais** dans les classements officiels, reste une couche personnelle de [discovery-engine.md](discovery-engine.md) (mode Normal).

**[Validé]** Certified : une récupération historique peut devenir Certified si des preuves suffisamment solides sont fournies et **validées côté serveur**, exactement comme un candidat issu du tracking en direct — aucun raccourci de confiance n'est accordé du seul fait qu'une preuve existe. Preuves potentielles : photos géolocalisées d'origine (EXIF date/heure/position), plusieurs photos cohérentes entre elles, fichiers GPX/historique de localisation, billets de train/bus/avion, réservations d'hôtel, reçus ou autres justificatifs datés de voyage, ou combinaisons de ces éléments.

**[Validé]** La reconnaissance faciale/selfie n'est **jamais** exigée. Aucune reconnaissance biométrique d'identité n'est conçue comme prérequis de validation.

**[Validé]** L'IA peut assister le processus : lecture/classification de documents, analyse du contenu photo, identification de lieux probables, vérification de cohérence entre preuves, détection d'incohérences suspectes, aide à la décision de confiance. **La confiance produite par l'IA ne doit jamais être traitée comme une vérité absolue**, ni exposée à l'utilisateur sous forme de probabilités précises dénuées de sens.

**[Validé] Règle importante : une preuve ne certifie que ce qu'elle prouve raisonnablement.** Une photo géolocalisée à Fourvière prouve une présence aux environs de Fourvière — elle ne prouve **pas** que l'utilisateur a exploré tout Lyon.

**[OUVERT — à calibrer]** Barème/critères exacts de confiance (quelles combinaisons de preuves suffisent, à quel niveau de certification), rôle exact et garde-fous précis de l'assistance IA, procédure de contestation en cas de rejet.

## 10. Reconstruction de trajet entre preuves certifiées

**[Validé — cadrage futur]** Si deux points chronologiques A et B sont tous deux fortement certifiés (tracking direct ou récupération historique validée), World Discovery peut reconstruire et certifier le trajet terrestre minimal/le plus plausible entre eux — le raisonnement étant que l'utilisateur a nécessairement voyagé physiquement de A à B, et qu'un transport terrestre normal compte comme découverte (voir [discovery-engine.md](discovery-engine.md) §0).

**[Validé]** Utiliser : horodatages, distance, réseaux de transport disponibles, plausibilité marche/route/rail/transit, et tout autre élément contextuel. En cas de plusieurs possibilités, choisir une reconstruction plausible **conservatrice**, jamais celle qui maximiserait l'exploration.

> Exemples : hôtel → monument dans une même ville : reconstruire le trajet minimal plausible. Paris → Lyon : ne pas colorer aveuglément un itinéraire routier si le train est plausible — le contexte de transport compte. Paris → New York : détecter un vol probable et ne **jamais** colorer le territoire/l'océan survolé.

**[Validé]** Les cellules certifiées reconstruites doivent rester **distinguables en interne** des cellules certifiées observées directement, pour permettre un recalcul futur par de nouveaux algorithmes.

## 11. Affichage Certified / Non-certifié

**[Validé — cadrage futur]** Le concept exposé à l'utilisateur reste volontairement simple : une bascule du type « Afficher les découvertes non certifiées ». **OFF** : seule la découverte Certified est affichée. **ON** : la découverte Certified et le Normal/non-certifié personnel sont tous deux affichés, le non-certifié dans un style visuel clairement différent. Les classements publics officiels restent, dans tous les cas, exclusivement basés sur le Certified (rappel, déjà acté en §6). Ne pas construire une complexité visible inutile (Personnel/Vérifié/Certifié) : la richesse de confiance/provenance décrite en §8–§10 reste **interne**.

## 12. Ce qui n'est pas encore décidé

- Critères exacts de transition entre états de la machine à états.
- Pondération et seuils exacts des signaux d'intégrité.
- Modalités précises d'intégration de Play Integrity (ou équivalent).
- Processus de contestation/appel en cas de rejet erroné.
- Seuil de maturité anti-triche déclenchant l'activation des classements.
- Règle de départage en cas d'égalité dans un classement (voir [architecture.md](architecture.md) §11).
- **Comportement du Certified en l'absence de connexion réseau**, notamment : stockage temporaire éventuel des événements candidats côté client ; validation différée éventuelle après reconnexion ; garanties nécessaires sur l'intégrité/l'horodatage de ces événements ; durée éventuelle pendant laquelle une validation différée resterait acceptable ; situations dans lesquelles une découverte réalisée hors ligne ne pourrait pas être certifiée. Aucune de ces règles n'est décidée à ce stade. Pour rappel, le mode **Normal** reste dans tous les cas intégralement offline-first — cette question ne concerne que le mode Certified.
- Limites exactes de durée de coupure/ambiguïté pour accepter un candidat reconstruit après perte GPS temporaire (§8).
- Barème/critères exacts de confiance pour la récupération manuelle/historique, et garde-fous précis de l'assistance IA (§9).
- Procédure exacte de calcul du trajet « le plus plausible » entre deux preuves certifiées, au-delà du principe de conservatisme déjà acté (§10).
