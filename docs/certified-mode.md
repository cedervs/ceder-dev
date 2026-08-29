# World Discovery — Mode Certified

> Document de référence vivant. Le mode Certified n'est **pas** implémenté dans le premier MVP (voir [roadmap.md](roadmap.md)) ; ce document fixe les principes que l'implémentation future devra respecter dès sa première version.

## 1. Principe fondamental

Le mode Certified est un ensemble de données **strictement séparé** du mode Normal (voir [architecture.md](architecture.md) §8). Une cellule Certified doit provenir du tracking et passer par une validation côté serveur : **le téléphone est considéré comme une source de données non fiable, le serveur est l'autorité finale.** Le client ne peut jamais décider seul qu'une cellule est Certified, et une cellule Normal ne devient jamais automatiquement Certified. Toute donnée, classification ou estimation produite côté client — y compris les résultats du moteur de découverte local — susceptible d'influencer une décision Certified doit être considérée comme **non fiable tant qu'elle n'a pas été validée côté serveur**.

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

## 8. Ce qui n'est pas encore décidé

- Critères exacts de transition entre états de la machine à états.
- Pondération et seuils exacts des signaux d'intégrité.
- Modalités précises d'intégration de Play Integrity (ou équivalent).
- Processus de contestation/appel en cas de rejet erroné.
- Seuil de maturité anti-triche déclenchant l'activation des classements.
- Règle de départage en cas d'égalité dans un classement (voir [architecture.md](architecture.md) §11).
- **Comportement du Certified en l'absence de connexion réseau**, notamment : stockage temporaire éventuel des événements candidats côté client ; validation différée éventuelle après reconnexion ; garanties nécessaires sur l'intégrité/l'horodatage de ces événements ; durée éventuelle pendant laquelle une validation différée resterait acceptable ; situations dans lesquelles une découverte réalisée hors ligne ne pourrait pas être certifiée. Aucune de ces règles n'est décidée à ce stade. Pour rappel, le mode **Normal** reste dans tous les cas intégralement offline-first — cette question ne concerne que le mode Certified.
