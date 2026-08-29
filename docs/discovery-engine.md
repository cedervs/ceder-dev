# World Discovery — Moteur de découverte

> Document de référence vivant, à séparer explicitement en trois catégories. **Une hypothèse ne doit jamais être transformée en règle définitive simplement pour pouvoir avancer.** Toute implémentation définitive d'une valeur listée en catégorie B ou C doit d'abord faire l'objet d'une mise à jour de ce document, avec justification et résultats de tests.

## Pipeline conceptuel

```
Observations/échantillons de localisation
  → analyse du déplacement (sol vs transport rapide/aérien)
  → présence
  → cellules géographiques H3 (représentation canonique — structure et résolution exactes ouvertes, voir catégorie C)
  → découverte (Normal et/ou Certified)
  → agrégation multi-niveaux de précision (Easy / Standard / Precision)
  → agrégation géographique (zone → région → pays → monde)
  → pourcentage (par niveau de précision, sur territoire éligible)
```

H3 reste invisible pour l'utilisateur à chaque étape. Le pipeline ne suppose jamais qu'un signal reçu est automatiquement authentique — cette distinction est particulièrement importante pour Certified, où seule une validation côté serveur fait foi (voir [certified-mode.md](certified-mode.md)).

---

## A. Décisions validées

- La grille géospatiale interne est **H3**.
- Une cellule ne peut être comptée qu'une seule fois **au sein d'un même ensemble** (`mode` : normal ou certified) ; les deux ensembles sont indépendants.
- Le moteur doit distinguer **présence au sol** et **transport rapide/aérien** : un vol long-courrier ne doit pas débloquer les pays et océans survolés.
- Le système privilégie la **preuve de présence** dans une zone plutôt que le tracé exact.
- Le moteur ne dépend **pas uniquement du réseau routier** : une découverte à pied en forêt, montagne, plage, sentier ou tout autre territoire accessible doit être reconnue.
- Trois niveaux de précision minimum — Easy, Standard, Precision/Hard — dérivés d'**une seule représentation canonique**, suffisamment fine pour tous les produire, sans capture indépendante par niveau (structure et résolution H3 exactes non fixées — voir catégorie C). Standard est le niveau par défaut.
- Une zone découverte en Easy n'implique pas la découverte de ses sous-zones en Standard/Precision.
- Le pourcentage est calculable séparément par niveau de précision.
- Un score de 100 % signifie « 100 % du territoire éligible selon la version du référentiel utilisée » (voir référentiel d'éligibilité, [architecture.md](architecture.md) §8), pas chaque mètre carré physique. **La composition exacte du dénominateur reste ouverte** — voir catégorie C ci-dessous, notamment pour le traitement de `UNKNOWN`.
- Les cellules `RESTRICTED_EXCLUDED` ne sont jamais nécessaires pour atteindre 100 % et n'entrent jamais dans le dénominateur.
- La présence de Google Street View ou d'une couverture automobile similaire **n'est pas une preuve d'accessibilité légale** ; elle peut au mieux être un signal auxiliaire parmi d'autres, jamais la source de vérité de l'éligibilité.
- Toute donnée dérivée persistée porte un `engine_version` ; les règles d'agrégation de précision et le référentiel d'éligibilité sont versionnés indépendamment.
- Pas de conservation indéfinie de GPS brut : rétention courte, puis conservation de données dérivées minimales uniquement.
- World Discovery n'est jamais présenté comme une autorisation légale d'accès à un lieu.

---

## B. Hypothèses à tester (avant d'être considérées comme définitives)

- Seuils de vitesse/altitude/durée permettant de distinguer immobile / marche / vélo / voiture / train / bateau / avion.
- Durée minimale de présence dans une cellule pour valider une découverte (« couverture minimale d'une cellule »).
- Règle de tolérance spatiale / rayon en mode Precision/Hard, pour éviter deux extrêmes : exiger une précision absurde, ou débloquer des zones voisines jamais réellement visitées.
- Comportement face à un GPS imprécis (bâtiments denses, canyons urbains, intérieur, tunnels) : filtrage, lissage, ou rejet de l'échantillon.
- Comportement au retour dans une zone déjà découverte : confirmation, aucune double comptabilisation, éventuel enrichissement du niveau de confiance.
- Comportement lors d'une perte de signal prolongée : stockage local, reprise, et impact sur la continuité d'un trajet.
- Fonction d'agrégation exacte reliant la représentation canonique à chacun des niveaux Easy/Standard/Precision (relation H3 parent/enfant stricte vs règle de couverture propre à Precision).

Chaque hypothèse doit être validée par des scénarios de test reproductibles avant d'être figée dans le code de production.

---

## C. Décisions encore ouvertes (non tranchées, à ne pas deviner)

- Structure exacte et résolution H3 de la représentation canonique unique, et résolutions dérivées pour chacun des niveaux (Easy, Standard, Precision).
- Valeurs exactes de rayon/tolérance/couverture en Precision/Hard.
- Seuils de présence, vitesses et durées exacts pour chaque mode de transport.
- Comportement précis attendu pour voiture, train, bateau et avion au-delà du principe général (sol vs transport rapide).
- Traitement exact du GPS imprécis (algorithme de filtrage retenu).
- Définition exacte des terres éligibles à la surface mondiale (quelles surfaces comptent : terres émergées uniquement ? eaux territoriales ? cas particuliers).
- Traitement de l'**Antarctique**.
- Traitement des **micro-îles**.
- Traitement des **frontières et territoires contestés** dans le référentiel `countries` / `h3_country_mapping`.
- Source(s) définitive(s) alimentant le référentiel d'éligibilité (données administratives/légales, cartographiques, tags d'accès, réserves, zones militaires connues — nature exacte et gouvernance de mise à jour non arrêtées).
- **Traitement de `UNKNOWN` dans le calcul du dénominateur.** Explicitement non tranché : `UNKNOWN` ne doit être **ni inclus ni exclu arbitrairement** avant décision documentée — décider par défaut son exclusion (ou son inclusion) pourrait rendre artificiellement plus facile, ou plus difficile, de compléter un territoire mal couvert par les données d'accessibilité. La solution retenue devra être choisie en connaissance de cause (inclusion, exclusion, statut intermédiaire, pondération) et documentée ici avant implémentation.
- Procédure de gestion des changements futurs d'éligibilité (comment et quand une cellule change de statut, impact sur les scores déjà atteints).
- Méthode exacte de calcul des pourcentages (agrégation géométrique précise, gestion des arrondis, cas des cellules partiellement couvertes par une frontière).

---

## Scénarios de test indispensables (dérivés du cahier des charges)

- Première présence suffisamment fiable dans une zone (y compris le domicile) → comportement déterminé selon les futures règles de présence validées (voir catégorie B).
- Rester immobile dans une zone déjà découverte → aucune progression artificielle vers les cellules voisines, aucune double comptabilisation.
- Marcher → zones cohérentes.
- Conduire → progression cohérente.
- Prendre l'avion → pas de découverte massive des zones survolées.
- Perte de réseau → stockage local puis synchronisation à la reconnexion.
- Application redémarrée/fermée → reprise conforme aux capacités et permissions du système.
- Retour dans une zone déjà découverte → aucune double comptabilisation.
- Changement de niveau de précision après un usage long → aucune perte d'historique, pas de recommencement à zéro.
- Localisation simulée, téléportation, horloge manipulée, replay d'événements, base locale modifiée → à couvrir spécifiquement avant toute activation d'un classement ou d'une fonctionnalité Certified compétitive (voir [certified-mode.md](certified-mode.md)).

Ces scénarios doivent être formalisés en tests automatisés (unitaires pour la logique d'agrégation, avec fixtures de trajectoires simulées) avant que les seuils de la section B ne soient figés.
