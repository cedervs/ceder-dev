# Étude technique — Moteurs de map matching pour la reconstruction de trajectoire (Phase 2A)

**Statut : ÉTUDE TECHNIQUE — aucune intégration, aucun wiring, aucune décision finale.**

**Round de correction (revue Codex indépendante) : cette version corrige neuf défauts identifiés
dans la version précédente** — une erreur d'unité (km/m) sur un résultat Valhalla, deux affirmations
non prouvées ("outlier rejeté" côté OSRM, classement OSRM>Valhalla), un biais méthodologique (route
OSRM utilisée comme "vérité terrain"), une équivalence de configuration jamais établie entre les
deux spikes, une reproductibilité insuffisante, une formulation ambiguë sur l'état du tampon Phase 1,
et une métrique H3 combinée à corriger en métriques séparées. Chaque correction est signalée
explicitement ci-dessous plutôt que silencieusement réécrite.

Ce document compare Valhalla/Meili, OSRM Match et GraphHopper Map Matching pour la future
reconstruction de trajectoire de World Discovery, sur la base de la documentation officielle, et de
deux spikes isolés réellement exécutés et **ré-audités index par index** dans ce round (OSRM et
Valhalla, via Docker, contre un tracé synthétique dérivé d'une vraie route et calibré sur nos
propres mesures physiques de cadence). Rien dans ce document n'active de reconstruction, ne câble
le buffer, ne modifie le corridor, ne produit de cellule `RECONSTRUCTED`, ni ne change la cadence
GPS actuelle.

Ce document complète, sans le remplacer, `docs/ai-context/LOCATION_TRACKING.md`'s "Trajectory
reconstruction / map matching" section (Phase 1 foundations).

---

## 1. Besoin World Discovery

World Discovery doit reconstruire, quand c'est crédible, le trajet réellement parcouru entre des
observations GPS espacées — pas relier deux points par une ligne droite, pas utiliser
`gridPathCells` H3 comme substitut de trajectoire réelle, pas calculer un plus court chemin naïf
paire par paire. Le moteur doit raisonner sur toute la **séquence** d'observations (A→B→C→D→E),
pas sur des segments isolés — c'est exactement ce que l'approche HMM/Viterbi des trois candidats
étudiés fait par construction (voir §4-6).

Contrainte produit forte : pour un déplacement routier ordinaire, `NO_RECONSTRUCTION` ne doit pas
devenir le comportement normal. Le moteur doit également couvrir marche/rando/sentiers/vélo, sans
jamais forcer artificiellement l'utilisateur sur une route distante quand aucun réseau crédible
n'existe près de lui.

## 2. Exigences produit (rappel, non renégocié ici)

- Aucune reconstruction ne doit être un faux positif : marquer comme "découvert" un territoire
  jamais parcouru est le risque le plus grave.
- H3 reste la représentation canonique **finale**, jamais l'espace de travail du matcher
  (`GPS → matcher → trajectoire → confiance → H3`, jamais `GPS → H3 → choix du trajet`).
- Le corridor bleu actuel (`DiscoveredRoute.kt`) n'est pas touché par cette étude ni par une future
  intégration immédiate (voir §16).
- Contrat `TrajectoryReconstructor` (Phase 1, `core-discovery-engine`) : voir §15 pour l'analyse de
  compatibilité corrigée.

## 3. Architecture actuelle (rappel factuel, code-vérifié)

- `core-discovery-engine.trajectory` : modèles purs (`TrajectoryObservation`, `ObservationWindow`,
  `TrajectoryReconstructor`, `TrajectoryReconstructionResult`, `ReconstructionConfidence` à 13
  composantes, `ObservationCadenceRecommendation`), zéro dépendance H3/Android/moteur.
  `NoOpTrajectoryReconstructor` est la seule implémentation existante — retourne toujours
  `NoReconstruction`.
- `core-database` : `TrajectoryBufferDatabase` (base Room séparée), claim/lease atomique, rétention
  injectable — **c'est une fondation inactive, pas un tampon en fonctionnement.** `buildBufferedObservationRecord`
  n'est appelé nulle part dans le code réel ; aucune observation n'y transite aujourd'hui. La
  découverte OBSERVED offline actuelle (tracking existant, `discovered_cells`) est totalement
  indépendante de ce futur tampon de trajectoire brute — elle ne l'utilise pas et n'en dépend pas.
  **(Correction §11 de la demande — toute formulation antérieure suggérant "les observations sont
  déjà bufferisées" était imprécise ; voir §8.C ci-dessous pour l'endroit exact où c'était le cas.)**
- `discovered_cells`/`WorldDiscoveryDatabase` : totalement séparés, schéma inchangé.
- Cadence GPS actuelle : foreground ~6-7s mesuré, background ~27-30s mesuré (voir §12) —
  inchangée, non touchée par cette étude.
- Aucun moteur de map matching n'est choisi ni intégré à ce stade.

## 4. Valhalla / Meili

**Algorithme.** Meili implémente un HMM (Hidden Markov Model) selon Newson & Krumm (2009) : chaque
observation GPS devient une colonne de candidats (segments routiers proches), et l'algorithme
cherche la séquence de candidats la plus probable. Une probabilité d'émission (gaussienne, centrée
sur la distance mesure↔candidat) et une probabilité de transition (empirique, comparant distance
réseau et distance à vol d'oiseau entre deux mesures consécutives) sont combinées ; Meili les
transforme en coûts pour réutiliser Dijkstra plutôt qu'implémenter Viterbi séparément — un détail
d'implémentation qui ne change rien au résultat conceptuel (source : documentation Valhalla, voir
§Sources).

**Correction d'unité (Codex, point bloquant) :** la version précédente de cette étude affirmait que
`distance_from_trace_point` valait **5.689 km**. C'est faux. La documentation officielle de l'API
`trace_attributes` (voir §Sources) précise explicitement : *"distance_from_trace_point: The
distance in meters from the trace point to the matched point."* — **la valeur est en mètres**, donc
`5.689192` ≈ **5.7 mètres**, pas 5.7 kilomètres. Le champ `units` du niveau supérieur de la réponse
(`"kilometers"` dans notre requête) ne s'applique qu'aux longueurs d'itinéraire/legs, jamais à
`distance_from_trace_point`, qui est toujours en mètres quel que soit `units`. Toute conclusion de
la version précédente reposant sur "5.7 km" (notamment "force-matché loin", "snap absurde") est
retirée et remplacée par l'audit exact ci-dessous.

**Audit exact du point injecté (Codex, re-exécuté §17) :** avec l'unité corrigée, 5.7m est une
distance *faible*, pas un signal d'échec. Cela ne veut PAS dire que Valhalla a bien fonctionné : cela
signifie que le point matché est très proche de la coordonnée **bruitée envoyée en entrée**, ce qui
est cohérent avec deux hypothèses différentes : (a) Valhalla a ramené le point vers la route prévue
et il se trouve que cette route repasse à 5.7m de la position bruitée par coïncidence, ou (b)
Valhalla a matché sur un **autre segment routier proche mais différent** de celui réellement
emprunté. **Ces deux hypothèses ont été départagées par l'audit index par index (§17.3, ré-exécuté
avec des attributs supplémentaires `matched.distance_along_edge`/`begin_route_discontinuity`/
`end_route_discontinuity`) : c'est l'hypothèse (b) qui est confirmée.** Le point injecté (index 21)
a été matché sur `edge_index=144`, un segment **sans nom** (`edges[144].names` vide), alors que les
points voisins (index 20 et 22, tous deux non perturbés) sont matchés sur `edge_index=143` et `148`
respectivement, tous deux nommés **"A1/Peel Road"**. La coordonnée matchée pour l'index 21
(54.198498, -4.61339) est quasiment identique à la coordonnée bruitée d'entrée (54.198500,
-4.613303) — **pas** à la position réelle non-bruitée (54.197422, -4.613303, à ~120m). C'est donc
un **faux positif plausible sur une route/segment voisin** (un petit segment non nommé, probablement
une allée/liaison locale près de Peel Road), **prouvé par la comparaison edge_index + géométrie
matchée**, pas simplement inféré depuis une distance. Aucun signal de discontinuité
(`begin_route_discontinuity`/`end_route_discontinuity`) ne s'est déclenché sur ce point — le
mécanisme de continuité de route de Valhalla n'a **pas** signalé ce problème.

**Gaps/vitesse/heading/outliers.** La documentation officielle consultée ne détaille pas
explicitement le traitement des trous temporels ni du heading dans le modèle de coût — point à
vérifier par benchmark réel. Le paramètre `gps_accuracy`/`accuracy` par point influence directement
la probabilité d'émission (`sigma_z=4.07` par défaut dans notre configuration réelle — voir §17.5).
Le paramètre `breakage_distance` (2000m par défaut dans notre configuration réelle, voir §17.5) est
le plus probable mécanisme de gestion des trous ; notre trou injecté (~1.5-2km) était **proche de ce
seuil**, ce qui peut expliquer qu'aucune coupure n'ait été observée — à vérifier systématiquement
en benchmark, pas une conclusion établie ici.

**Licence.** MIT — permissive, aucune restriction d'usage commercial connue (voir §Sources).

**Mobile/offline.** Conçu explicitement pour un usage embarqué : structure de tuiles hiérarchique à
faible empreinte mémoire, utilisé en auto-infotainment, iOS et Android (C++ + NDK). Candidat
plausible pour un futur usage local sur device — **taille de tuiles réelle non mesurée dans cette
étude** (hors budget), à valider par benchmark.

## 5. OSRM Match

**Algorithme.** HMM également (même lignée Newson-Krumm), avec un paramètre `gaps` (`split` par
défaut documenté) qui découpe la trace en sous-traces (`matchings[]`) sur un grand saut de
timestamp ou une transition jugée improbable ; `tidy` (`false` par défaut, non activé dans notre
requête) permet un nettoyage préalable de traces bruitées (voir §Sources pour la documentation
officielle du service Match).

**Audit exact re-exécuté (Codex, point bloquant — "outlier rejection was not proven") :** l'audit
index par index (§17.2) confirme, avec les index exacts, que **deux** des 36 `tracepoints[]` sont
revenus `null` : **l'index 21** (le point injecté, bruité de ~120m, précision déclarée 10m) — bien
le point attendu — **et l'index 20**, un point tout à fait normal, non perturbé, non adjacent au
trou temporel injecté de manière évidente. **Ce deuxième `null` n'est pas expliqué par cette étude**
— il pourrait résulter d'une interaction entre le point aberrant voisin et la fenêtre de recherche
de candidats d'OSRM autour de l'index 20, ou d'un autre effet non identifié ; ce n'est PAS une
affirmation "l'outlier a été rejeté proprement" telle qu'énoncée dans la version précédente, mais
un résultat plus nuancé : **le point injecté a bien été exclu, mais un point voisin non perturbé
l'a été aussi, sans explication établie.** Un seul `matchings[]` (`confidence:0.878803`) couvre les
34 points restants ; `waypoint_index` saute proprement de 19 à 20 (index d'entrée 22), confirmant
que les deux points nuls sont absents de la trajectoire finale, ni interpolés ni comptés. Le trou
temporel de 90s (entre index 21 et 22) **n'a pas** déclenché de split malgré le défaut documenté
`gaps=split` — à vérifier systématiquement, un seul essai ne prouve rien. Fait notable non
rapporté précédemment : `alternatives_count` vaut 0 pour tous les points sauf le **dernier** (index
35), où il vaut **40** — un signal d'ambiguïté réel qu'une future intégration devrait pouvoir
consommer, découvert seulement lors de cet audit précis.

**Correction méthodologique (Codex, point bloquant — "OSRM ground truth") :** l'itinéraire utilisé
comme base du tracé synthétique (Douglas → Peel) a été obtenu via `/route` **du même moteur OSRM**
qui a ensuite servi au test `/match`. **Ce n'est donc pas une vérité terrain indépendante.** Ce
spike doit être qualifié de **spike de cohérence/récupération synthétique auto-référentielle**
("synthetic self-consistency / recovery spike"), pas de "vérité terrain OSRM". Ce qu'il prouve : le
service fonctionne, une requête `/match` avec des points espacés fonctionne, les sorties sont
inspectables, des perturbations injectées peuvent être étudiées précisément. Ce qu'il **ne prouve
pas** : la justesse du trajet dans le monde réel, la supériorité d'un moteur, la sûreté "mauvaise
route" (puisque la route testée est par construction celle qu'OSRM lui-même aurait choisie), ou une
quelconque justesse H3. Le même biais méthodologique s'applique identiquement au spike Valhalla, qui
a reçu le tracé dérivé de cette même route OSRM — **aucun des deux spikes n'a de vérité terrain
indépendante.**

**Licence.** BSD 2-Clause — permissif (voir §Sources).

**Mobile/offline.** Conçu et documenté comme un service serveur C++ ; empreinte mémoire
significative même par pays. Pas le candidat le plus naturel pour du matching local sur device —
non mesuré précisément dans cette étude.

## 6. GraphHopper Map Matching

**Mise à jour Phase 2B : désormais réellement exécuté.** Ce document (l'étude Phase 2A) reste
inchangé sur ce point précis pour ne pas rouvrir une manche déjà close ; le spike réel, ses
commandes exactes, ses résultats bruts et ses limites d'audit sont dans
`docs/ai-context/map-matching-spike/graphhopper/commands.md` et
`docs/ai-context/PHASE_2B_BENCHMARK_PROTOCOL.md` §F. Résumé factuel : GraphHopper 11.0 (jar officiel
Maven Central `graphhopper-web-11.0.jar`), exécuté avec succès en mode CLI `match` (pas le serveur
web — voir la note d'environnement dans `commands.md` sur la même limitation de loopback JVM que
Gradle) sur le même tracé synthétique de 36 points. Trouvaille notable : la sortie GPX de ce mode
CLI ne préserve pas une correspondance 1:1 propre par index d'observation (contrairement à
`tracepoints[]`/`matched_points[]` d'OSRM/Valhalla), ce qui limite l'auditabilité par point de ce
chemin de sortie précis — documenté honnêtement, pas contourné.

**Algorithme.** HMM/Viterbi, également issu de Newson & Krumm (2009) : génération de candidats dans
un rayon de recherche par point GPS, puis Viterbi pour choisir la séquence la plus probable
(`map-matching/src/main/java/com/graphhopper/matching/MapMatching.java`, voir §Sources).

**Sortie documentée.** Résultat exportable en GPX ou JSON ; la documentation publique consultée
n'énumère pas aussi précisément que Valhalla/OSRM les champs de confiance/candidats par point —
information manquante côté documentation publique consultée, pas nécessairement côté code (à
vérifier par audit de code ou exécution réelle, pas ici).

**Licence.** Apache License 2.0, y compris le module map-matching (fusionné dans le dépôt principal
GraphHopper). Une "GraphHopper Directions API" commerciale existe en parallèle mais n'est pas
requise (voir §Sources).

**Mobile/offline — correction (Codex) :** GraphHopper est historiquement né comme moteur de
navigation Android, et son cœur est écrit en Java/JVM pur (pas de cross-compilation NDK nécessaire
pour compiler le moteur lui-même, contrairement à Valhalla/OSRM en C++). **Cela ne signifie
cependant PAS qu'une intégration Android est nécessairement simple** : la taille du graphe en
mémoire, le comportement du GC sur un gros graphe routier, l'empaquetage d'un runtime JVM/Java
complet dans une app Android (au-delà du sous-ensemble déjà couvert par la Kotlin standard library
et l'ART runtime), et la maintenance d'un import OSM à jour restent des inconnues réelles, non
mesurées ici. "Java/JVM" est un avantage de simplicité de *compilation*, pas une preuve de
faisabilité Android complète.

**Ne pas classer GraphHopper en dessous des deux autres simplement parce qu'il n'a pas encore été
exécuté** (correction explicite Codex) — voir §7 et §15, où GraphHopper apparaît comme "non exécuté"
et non comme "inférieur".

## 7. Tableau comparatif (facteurs observés vs. non exécutés, sans classement)

| Critère | Valhalla/Meili | OSRM Match | GraphHopper Map Matching |
|---|---|---|---|
| Algorithme | HMM (Newson-Krumm) | HMM (Newson-Krumm), avec split de trace | HMM/Viterbi (Newson-Krumm) |
| Licence | MIT | BSD-2-Clause | Apache-2.0 (module inclus) |
| Spike exécuté ce round | Oui, ré-audité (§17.3) | Oui, ré-audité (§17.2) | **Non — statut inchangé, non classé pour autant** |
| Signal de confiance observé | Par point : `distance_from_trace_point` (mètres, confirmé), `edge_index`, `distance_along_edge` ; pas de score global observé | Global par `matchings[]` : `confidence` (0.879 observé) ; par point : `distance`, `alternatives_count` | Non observé (non exécuté) |
| Comportement sur point injecté | Matché sur un edge voisin non nommé, proche de l'entrée bruitée plutôt que de la route prévue — **faux positif plausible prouvé par l'audit** (§4) | Exclu (`null`), avec un deuxième point voisin non perturbé également exclu sans explication établie (§5) | Non observé |
| Sortie géométrie complète | Oui (`shape`) | Oui (`geometry`) | Oui (GPX/JSON, documenté) |
| Mobile/offline natif | Conçu pour (tuiles hiérarchiques, C++/NDK) | Non conçu pour (serveur, RAM lourde documentée) | JVM pur (compilation simple) ; faisabilité Android réelle non mesurée |

**Aucun classement de production n'est établi par ce tableau.** Voir §Statut ci-dessous pour la
formulation exacte remplaçant l'ancien classement "OSRM premier, Valhalla second".

### Statut actuel (remplace le classement retiré)

**CURRENT STATUS : OSRM, Valhalla et GraphHopper restent tous les trois candidats de benchmark.**

- **OSRM** : la meilleure observabilité externe standard constatée à ce jour (confidence globale,
  distance par point, alternatives_count, tous confirmés par audit exact).
- **Valhalla** : sorties par-observation/discontinuité solides (une fois les unités correctement
  interprétées) et intérêt multimodal plus large (costing pedestrian/bicycle documentés, non
  testés ici).
- **GraphHopper** : candidat crédible, pas encore exécuté ; ne pas préjuger de sa position avant
  exécution réelle.

**Aucun classement de production tant qu'un benchmark neutre (§20) n'a pas été mené.**

## 8. Architecture server / local / hybrid

**A. Serveur World Discovery.** Le plus simple à opérer et mettre à jour. Coût : bande passante
(coordonnées brutes envoyées), latence, dépendance réseau, coût serveur.

**B. Local sur Android.** Élimine l'envoi réseau de coordonnées brutes, fonctionne offline. Coût
réel : taille du graphe régional, RAM/CPU au runtime, complexité de mise à jour OSM, complexité
d'intégration native. **Ne jamais supposer qu'embarquer un graphe mondial sur Android est
raisonnable.**

**C. Hybride.** Une future architecture pourrait combiner : observations locales bufferisées (**la
fondation existe en Phase 1 mais est actuellement inactive — rien n'y transite aujourd'hui, voir
§3**), reconstruction différée/serveur selon connectivité, ou reconstruction locale pour les cas
simples et serveur pour les cas ambigus. C'est l'option qui répond le mieux simultanément à privacy
(§9) et offline (§11), au prix d'une complexité d'architecture double (logique de décision "où
matcher" à concevoir plus tard — ENGINEERING DESIGN REQUIRED, pas ici).

**Aucune décision n'est prise ici.**

## 9. Privacy

- **Serveur** : nécessite l'envoi temporaire de coordonnées GPS brutes. Implique rétention serveur,
  logs d'accès, sauvegardes serveur potentiellement concernées, observabilité.
- **Local** : les coordonnées brutes ne quittent jamais l'appareil.
- **Hybride** : le volume transmis peut être réduit (ex. seulement les fenêtres ambiguës), mais
  suppose une classification locale préalable non conçue ici.
- **Aucune décision juridique définitive n'est prise dans ce document.**

## 10. Licences / OSM

**CONFIRMÉ (licences logicielles des moteurs) :**
- Valhalla : MIT. OSRM : BSD-2-Clause. GraphHopper (cœur + module map-matching) : Apache-2.0.
  Aucune des trois n'impose de partage du code de World Discovery (voir §Sources pour les fichiers
  LICENSE exacts).

**CONFIRMÉ (structure ODbL, source officielle OSMF — voir §Sources) :**
- L'ODbL distingue "Produced Work" (ex. une trajectoire calculée) et "Derivative Database" (une
  base de données dérivée redistribuée). Un "Produced Work" n'exige pas d'être partagé sous ODbL ;
  une "Derivative Database" mise à disposition de tiers doit rester disponible sous ODbL (partage à
  l'identique). L'attribution reste requise dans tous les cas.

**REQUIERT UNE REVUE LÉGALE (non tranché ici) :**
- Si World Discovery télécharge et distribue un graphe régional compilé (option B/C, §8) aux
  appareils des utilisateurs, la question de savoir s'il constitue une "Derivative Database" au
  sens ODbL, et les obligations précises qui en découlent (partage/attribution/reconstruction),
  doivent être vérifiées avec un conseil juridique avant toute distribution réelle. Il existe des
  précédents d'applications qui le font (OsmAnd, Organic Maps, Komoot), mais cela ne constitue pas
  un avis juridique pour World Discovery.
- L'attribution exacte requise pour un usage purement serveur (résultat de reconstruction seul,
  jamais le graphe) reste à clarifier avec un conseil juridique.
- Toute utilisation d'un jeu de données tiers supplémentaire (au-delà d'OSM) pour le futur corpus
  de benchmark (§13) devra faire l'objet de la même revue.

**Aucune conclusion juridique n'est formulée dans ce document.**

## 11. Offline

- Les observations OBSERVED doivent continuer d'être enregistrées sans réseau — déjà le cas
  aujourd'hui, indépendamment du futur tampon de trajectoire (§3).
- La reconstruction peut, par nature, être différée.
- Un graphe régional offline (option B/C) a un coût de stockage réel à mesurer précisément par un
  futur benchmark (aucune mesure faite ici).
- **Rien n'est construit ici.**

## 12. Données de calibration réelles (inspectées, non modifiées)

Conformément à l'instruction, `trip1.txt`, `trip2.txt`, `trip3.txt`, `vehicle1.txt` et
`tracking-calibration.ndjson` ont été **inspectés en lecture seule**, jamais modifiés ni commités,
et le sont restés dans ce round de correction (aucune nouvelle inspection nécessaire).

- `tracking-calibration.ndjson` confirme le motif déjà documenté : livraisons `LOCATION_DELIVERED`
  en `BACKGROUND_PENDING_INTENT`, précision observée ~16-42m, cadence cohérente ~27-30s.
- `trip1.txt`/`trip2.txt`/`trip3.txt` confirment la cadence ~30s, avec un cas concret de trou
  (`deltaMillis=11338`, `impliedSpeedMetersPerSecond=258`, rejeté par la logique existante).
- **Aucun de ces artefacts ne contient de coordonnées GPS brutes** — choix de confidentialité déjà
  en place. Conséquence directe : le spike n'a pas pu utiliser nos propres traces réelles ; il a dû
  utiliser un tracé synthétique dérivé d'une vraie route (§5 — avec le biais méthodologique
  maintenant explicitement documenté : la route source vient d'OSRM lui-même, donc ni ce spike ni
  le score de confiance qu'il produit ne constituent une preuve de justesse réelle).

Calibration produit (rappel) : foreground ~6-7s, background ~27-30s mesuré ; à 50 km/h ≈ 375m entre
fixes, à 90 km/h ≈ 675m.

## 13. Corpus de benchmark à concevoir (proposition affinée, rien construit)

**ROAD** : route rurale unique ; deux routes parallèles ; grille urbaine dense ; rond-point ;
échangeur autoroutier ; pont ; routes superposées ; tunnel/perte de signal.

**WALK** : ville ; parc ; forêt ; sentier parallèle à une route.

**BIKE** : route ; piste cyclable ; infrastructure cyclable séparée.

**ADVERSARIAL** (catégorie ajoutée ce round, séparée de ROAD/WALK/BIKE) : point aberrant isolé ;
fixe périmé (stale fix) ; saut (jump) ; précision dégradée ; arrêt prolongé ; trou de
processus/livraison ; mauvais mode (ex. vélo détecté comme voiture) ; chemin non cartographié.

**SPACING** (catégorie ajoutée ce round, à appliquer transversalement aux scénarios ci-dessus, en
dérivant systématiquement d'une trajectoire de référence unique — voir §20) : ~27s marche ;
~27s/50km/h voiture ; ~27s/90km/h voiture ; trajectoire de référence dense conservée séparément
comme vérité terrain.

**Ce corpus n'existe pas encore** — sa constitution est un prérequis du futur benchmark réel, pas
une tâche de cette étude. Voir §20 pour la méthode de conception d'une vérité terrain réellement
indépendante.

## 14. Métriques (séparées, jamais combinées en un score unique)

- **Full-route correctness** : le trajet reconstruit suit-il la route réellement empruntée sur
  toute sa longueur ?
- **Wrong-road rate** : fréquence de sélection d'une route parallèle/adjacente incorrecte.
- **False accepted reconstruction** : fréquence à laquelle le moteur accepte une reconstruction
  qui s'avère fausse — **le critère le plus grave**.
- **Correct ambiguity / NoReconstruction rate** : fréquence à laquelle une situation réellement
  ambiguë est correctement signalée comme telle (ni trop, ni trop peu).
- **Observation→network distance** : distance moyenne observation→point matché.
- **Geometry deviation** : écart géométrique cumulé entre trajectoire reconstruite et vérité
  terrain.
- **Route coverage** : proportion de la trajectoire de référence effectivement couverte par une
  reconstruction (vs. non couverte/`NoReconstruction`).
- **H3 false-positive cells** et **H3 false-negative cells** — **corrigé ce round : ces deux
  métriques restent toujours séparées, jamais combinées en un "% correct" opaque** (Codex, point
  bloquant). Le false-positive (une cellule marquée découverte que l'utilisateur n'a probablement
  jamais traversée) est la métrique de sécurité **primaire**, avant toute autre — c'est la
  traduction directe en H3 du critère "false accepted reconstruction" ci-dessus. **H3 precision**
  (vrais positifs / total des cellules produites) et **H3 recall** (vrais positifs / total des
  cellules réellement traversées) peuvent être dérivées séparément si utile, mais ne remplacent
  pas le suivi explicite des comptes bruts de faux positifs et de faux négatifs.
- **Unmatched/split precision/recall** : justesse de la décision "ce point/segment n'a pas pu être
  matché" par rapport à la vérité terrain.
- **Processing latency, graph build time, graph size, RAM, CPU, throughput, bande passante** :
  mesures d'ingénierie/coût.

Aucun score combiné arbitraire n'est proposé ni recommandé.

## 15. Compatibilité avec le contrat `TrajectoryReconstructor` (Phase 1) — corrigée

**Correction (Codex, point bloquant) :** la version précédente affirmait que
`observedIndices`/`inferredIndices` étaient "DERIVABLE" directement depuis les sorties moteur
(`matched_points[].type`, `tracepoints[]` non-null). **C'est trompeur.** `observedIndices` et
`inferredIndices` sont des ensembles d'index dans **l'espace de la géométrie de sortie**
(`AcceptedTrajectory.geometry`, la polyligne complète reconstruite), **pas** dans l'espace des
observations d'entrée. Les sorties des trois moteurs auditées ici (`tracepoints[]` OSRM,
`matched_points[]` Valhalla) sont, elles, indexées **par observation d'entrée** (confirmé
empiriquement : nos 36 observations produisent exactement 36 `tracepoints`/`matched_points`, en
correspondance un-à-un avec l'entrée — voir §17.2/§17.3), pas par point de géométrie de sortie (qui
en contient typiquement beaucoup plus, puisque la géométrie inclut aussi les points interpolés le
long des routes entre deux observations). **Un futur adaptateur devra donc explicitement construire
la provenance géométrique** (quelle position dans `geometry` correspond à quelle observation
d'entrée, laquelle est interpolée) — ce n'est pas une correspondance directe fournie par les
moteurs. **Conclusion Codex reprise ici : aucun écart bloquant du contrat** — le contrat Phase 1
reste inchangé ; c'est une clarification de conception pour la future couche d'adaptation, pas un
défaut du contrat lui-même.

| Élément du contrat | Valhalla/Meili | OSRM Match | GraphHopper Map Matching |
|---|---|---|---|
| `AcceptedTrajectory.geometry` | DIRECT (`shape`) | DIRECT (`geometry`) | DIRECT (GPX/JSON, documenté) |
| `observedIndices`/`inferredIndices` | À CONSTRUIRE — nécessite un adaptateur qui projette les index d'observation (`matched_points[]`) sur l'espace de la géométrie de sortie ; aucune correspondance directe | À CONSTRUIRE — même remarque pour `tracepoints[]` | À CONSTRUIRE — même remarque, non vérifié empiriquement |
| `strategy` (kind/name) | DIRECT | DIRECT | DIRECT |
| `transportModeHypothesis` | DERIVABLE (le `costing` est un intrant, pas une déduction du moteur) | DERIVABLE (idem, `profile`) | DERIVABLE (idem) |
| `ReconstructionConfidence.overall` | DERIVABLE (à dériver de `distance_from_trace_point` agrégé) | DIRECT (`matchings[].confidence`, observé 0.879) | MISSING (non confirmé, non exécuté) |
| `ReconstructionConfidence.observationToCandidateDistance` | DIRECT (`distance_from_trace_point`, en mètres, observé et vérifié §4) | DIRECT (`tracepoints[].distance`, observé) | MISSING (non exécuté) |
| `ReconstructionConfidence.pathAmbiguity` | MISSING (non exposé dans la doc consultée) | DERIVABLE (`alternatives_count` par point, observé — y compris le cas concret =40 au dernier point, §5) | MISSING (non exécuté) |
| `observationMatches` | DIRECT (`edge_index` par observation, observé) — **index d'observation, pas index de géométrie, voir note ci-dessus** | DERIVABLE (`waypoint_index`/`matchings_index`, observé) — même note | DERIVABLE (probable, non vérifié) |
| `ObservationCadenceRecommendation` | MISSING nativement — à construire depuis `distance_from_trace_point` élevé, absence de discontinuité malgré une distance suspecte, etc. | MISSING nativement — à construire depuis `alternatives_count`>0, `confidence` bas, tracepoints `null` | MISSING nativement, non vérifié |

## 16. Relation avec le corridor bleu (analyse seulement, rien modifié)

Inchangé depuis la version précédente. Le corridor bleu (`DiscoveredRoute.kt`) dérive uniquement de
`DiscoveredCell.firstDiscoveredAt` ; `discovered_cells` ne suffit pas à reconstruire un historique de
trajet ordonné. Rien n'est conçu ou implémenté ici.

## 17. Spike technique — audit exact, configuration, reproductibilité

### 17.1 Contexte d'exécution

Docker, entièrement isolé (au moment de l'exécution, dans le répertoire scratchpad de la session
qui a mené le spike) — aucun fichier du projet World Discovery créé/modifié à l'époque, aucun
conteneur du projet (`ceder-dev-backend`, `postgres`, `mailpit`) touché. Le round de correction
précédent a **ré-exécuté la requête Valhalla** (mêmes tuiles déjà construites, requête enrichie de
trois attributs pour l'audit — voir §17.3) et **réutilisé le fichier de réponse OSRM déjà obtenu**
(aucune ré-exécution nécessaire, toutes les données requises pour l'audit y étaient déjà
présentes).

**Correction de reproductibilité (ce round) : tout ce qui précède a été transféré dans une fixture
durable versionnée avec le dépôt**, `docs/ai-context/map-matching-spike/` — commandes exactes,
requêtes exactes, réponses brutes exactes, script d'audit déterministe (PowerShell, vrai parsing
JSON via `ConvertFrom-Json`, jamais de regex) **vérifié en le ré-exécutant depuis cette fixture
seule**, sans dépendre d'un chemin scratchpad, d'un historique de shell ou de conversation. Voir
`docs/ai-context/map-matching-spike/README.md` pour l'index complet, et les avertissements "NOT
INDEPENDENT GROUND TRUTH" / "NOT NORMALIZED" qui y sont répétés à dessein. Les tableaux §17.2/§17.3
ci-dessous restent la synthèse lisible ; `docs/ai-context/map-matching-spike/osrm/audit-result.md`
et `.../valhalla/audit-result.md` en sont la version reproductible vérifiée.

### 17.2 Audit exact OSRM — table indexée

Entrée (36 points, `docs/ai-context/map-matching-spike/shared/input-trace.csv`) croisée avec
`docs/ai-context/map-matching-spike/osrm/response.json` (`tracepoints[]`) — reproductible via
`docs/ai-context/map-matching-spike/osrm/audit-osrm.ps1` :

| idx entrée | coordonnée entrée | intention | tracepoint | coord. matchée | distance (m) | matchings_index | waypoint_index | alt_count | note |
|---|---|---|---|---|---|---|---|---|---|
| 0 | -4.481803,54.150724 | NORMAL | non-null | -4.482648,54.150695 | 55.28 | 0 | 0 | 0 | premier point, snap de 55m |
| 1–18 | (route normale) | NORMAL | non-null | ≈ identique à l'entrée | 0 | 0 | 1–18 | 0 | — |
| 19 | -4.600364,54.194293 | NORMAL (après le trou de 3 points supprimés) | non-null | identique | 0 | 0 | 19 | 0 | — |
| **20** | -4.605657,54.196767 | **NORMAL, non perturbé** | **NULL** | — | — | — | — | — | **exclu sans explication établie par cette étude** |
| **21** | -4.613303,54.198500 | **INJECTED_NOISY** (clone décalé +120m, `radius=10`) | **NULL** | — | — | — | — | — | **le point injecté, exclu comme prévu** |
| 22 | -4.613303,54.197422 | NORMAL (position vraie, non décalée, d'où 21 a été cloné ; arrive 90s après 21) | non-null | -4.613303,54.197422 | 0 | 0 | 20 | 0 | reprise normale après les deux null |
| 23–34 | (route normale) | NORMAL | non-null | identique | 0 | 0 | 21–32 | 0 | — |
| 35 | -4.690383,54.222327 | NORMAL (dernier point) | non-null | identique | 0 | 0 | 33 | **40** | seul point avec ambiguïté significative détectée |

`code:"Ok"`, un seul `matchings[0].confidence = 0.878803` couvrant les 34 points non-nuls.
**Conclusion corrigée** : le point injecté a bien été exclu (index 21), mais un second point non
perturbé (index 20) l'a été aussi — cause non identifiée par cette étude, à creuser en benchmark
(pourrait révéler une sensibilité du candidat-search autour d'un point aberrant voisin, ou un effet
indépendant). Le trou temporel de 90s (indices 21→22) n'a déclenché aucun split malgré le
comportement par défaut documenté.

### 17.3 Audit exact Valhalla — table indexée

Re-exécuté avec `matched.distance_along_edge`, `matched.begin_route_discontinuity`,
`matched.end_route_discontinuity` ajoutés au filtre d'attributs (absents de la première exécution),
sur les tuiles déjà construites (aucune reconstruction de graphe). Requête exacte préservée dans
`docs/ai-context/map-matching-spike/valhalla/request.json`, réponse brute exacte dans
`.../valhalla/response.json`, reproductible via `.../valhalla/audit-valhalla.ps1` :

| idx entrée | intention | type | edge_index | nom d'edge | dist_from_trace (m) | dist_along_edge | discontinuité | note |
|---|---|---|---|---|---|---|---|---|
| 0–19 | NORMAL | matched | 0…143 (séquentiel) | "A1/Peel Road" etc. | 0–0.035 | variable | aucune | route continue |
| **20** | NORMAL (non perturbé) | matched | 143 | A1/Peel Road | 0 | 0.976 | aucune | dernier point normal avant l'injecté |
| **21** | **INJECTED_NOISY** | matched | **144** | **(sans nom)** | **5.689192 ≈ 5.7m** (corrigé, était affiché à tort en km) | 0.610 | aucune | **matché sur un edge voisin non nommé, proche de la coordonnée bruitée d'entrée, pas de la position réelle à ~120m — faux positif plausible confirmé** |
| 22 | NORMAL (position vraie) | matched | 148 | A1/Peel Road | 0 | 0.206 | aucune | reprise sur la route nommée d'origine |
| 23–35 | NORMAL | matched | 150…216 (séquentiel) | "A1/Peel Road", "A3", "Poortown Road/A20", "Derby Road/A20" etc. | 0 | variable | aucune | — |

**36/36 points revenus `type:"matched"`** (aucun `unmatched`/`interpolated`). **Conclusion corrigée
et prouvée (pas seulement inférée d'une distance)** : le point injecté (index 21) n'a été ramené ni
sur la route réellement empruntée (A1/Peel Road, edges 143/148 immédiatement voisins), ni signalé
par un flag de discontinuité — il a été matché sur un edge différent et sans nom (144), à
proximité immédiate de la coordonnée **bruitée**, pas de la position réelle. C'est un comportement
concrètement différent de celui d'OSRM sur le même point (exclusion silencieuse) : Valhalla a
produit une reconstruction plutôt que de refuser, et cette reconstruction s'est avérée être un
mauvais segment.

### 17.4 Interprétation corrigée des deux spikes

Ni l'un ni l'autre spike ne prouve la supériorité d'un moteur. Ce qu'ils prouvent concrètement,
avec preuve à l'appui :
- Les deux services fonctionnent, acceptent nos points synthétiques espacés à ~500m/27-90s, et
  produisent une sortie inspectable en détail.
- OSRM a exclu le point injecté (et un second, inexpliqué) plutôt que de produire une position
  fausse pour ces deux points.
- Valhalla a produit une position pour le point injecté, mais cette position s'est avérée être un
  edge différent et non pertinent (faux positif localisé), sans qu'aucun signal de discontinuité
  ne le révèle.
- Aucun des deux résultats ne dit quoi que ce soit sur la justesse dans un vrai scénario (voir la
  correction méthodologique §5 : la route de référence vient d'OSRM lui-même).

### 17.5 Équivalence de configuration — **LES PARAMÈTRES N'ONT PAS ENCORE ÉTÉ NORMALISÉS**

**OSRM** :
- Image : `osrm/osrm-backend:latest`, digest `sha256:af5d4a83fb90086a43b1ae2ca22872e6768766ad5fcbb07a29ff90ec644ee409`, version rapportée au démarrage : `v5.26.0`.
- Profil : `car.lua` (profil par défaut fourni par l'image, non modifié).
- Algorithme : MLD (`osrm-partition` + `osrm-customize`, pas CH).
- Commandes exactes (fichier source, arguments complets) :
  `docs/ai-context/map-matching-spike/osrm/commands.md`.
- Requête `/match` exacte (URL complète réellement exécutée, sans placeholder) :
  `docs/ai-context/map-matching-spike/osrm/request.txt` — `geometries=geojson`, `overview=full`,
  `annotations=true`, `radiuses` = 25 pour tous les points sauf 10 pour l'index 21,
  `timestamps` = les 36 epochs de `shared/input-trace.csv`. **Aucun `bearings` fourni**, `gaps` et
  `tidy` **non précisés dans la requête** (donc valeurs par défaut du service, documentées comme
  `gaps=split`, `tidy=false`, mais non vérifiées explicitement dans notre requête).

**Valhalla** :
- Image : `ghcr.io/valhalla/valhalla:latest`, digest `sha256:a7d0d02ed5ce4f2817105b443eb58494a5757fc6b48780a39c9cc62740296432`, version rapportée : `3.8.3-7f372987b`.
- Extrait OSM : identique (`isle-of-man.osm.pbf`, même fichier que celui utilisé pour OSRM).
- Costing : `auto`. `shape_match`: `map_snap`.
- **Paramètres meili réellement actifs (extraits de notre propre `valhalla.json` généré par
  `valhalla_build_config`, non modifiés depuis les valeurs par défaut) :** `gps_accuracy` défaut
  5.0m (mais **notre requête a fourni `accuracy` par point : 25m normal, 10m pour l'index 21** —
  override explicite, pas le défaut) ; `search_radius` défaut 50m (non modifié, `max_search_radius`
  100m) ; `breakage_distance` défaut **2000m** (non modifié — pertinent pour l'absence de split
  observée sur notre trou de ~1.5-2km) ; `interpolation_distance` défaut 10m (non modifié) ;
  `sigma_z` 4.07, `beta` 3 (paramètres HMM, non modifiés) ; costing `auto` : `turn_penalty_factor`
  200, `search_radius` 50 (surcharge du profil, pas de nos points).
- Commandes exactes (fichier source, arguments complets) :
  `docs/ai-context/map-matching-spike/valhalla/commands.md`. Corps JSON exact réellement envoyé :
  `docs/ai-context/map-matching-spike/valhalla/request.json` (voir aussi §17.6).

**Conclusion explicite (Codex, point bloquant) : LES PARAMÈTRES N'ONT PAS ÉTÉ NORMALISÉS ENTRE LES
DEUX MOTEURS.** Notamment : le rayon de recherche par défaut diffère dans son principe même (OSRM
utilise `radiuses` comme écart-type déclaré par point ; Valhalla combine `search_radius` global et
`gps_accuracy` par point différemment) ; la définition et le seuil de gestion des trous ne sont pas
équivalents (`gaps=split` documenté à un seuil temporel pour OSRM vs. `breakage_distance` en mètres
pour Valhalla). **Aucune conclusion de sûreté comparative ne doit être tirée de ces deux
comportements par défaut non harmonisés.**

### 17.6 Reproductibilité

**Corrigé ce round : tout ce qui suit est maintenant préservé dans une fixture durable versionnée
avec le dépôt, `docs/ai-context/map-matching-spike/`, jamais dans un chemin scratchpad éphémère.**
Chaque commande, requête et réponse ci-dessous a été effectivement ré-vérifiée en exécutant les
deux scripts d'audit directement depuis cette fixture (voir `.../osrm/audit-result.md` et
`.../valhalla/audit-result.md` pour la sortie exacte obtenue).

| Élément | OSRM | Valhalla |
|---|---|---|
| Image/tag | `osrm/osrm-backend:latest` | `ghcr.io/valhalla/valhalla:latest` |
| Digest | `sha256:af5d4a83fb90086a43b1ae2ca22872e6768766ad5fcbb07a29ff90ec644ee409` | `sha256:a7d0d02ed5ce4f2817105b443eb58494a5757fc6b48780a39c9cc62740296432` |
| Version rapportée | v5.26.0 | 3.8.3-7f372987b |
| Extrait OSM source | `download.geofabrik.de/europe/isle-of-man-latest.osm.pbf` (~6 Mo) | identique |
| Date d'exécution | 2026-09-05 | 2026-09-05 |
| Commandes exactes | `docs/ai-context/map-matching-spike/osrm/commands.md` | `docs/ai-context/map-matching-spike/valhalla/commands.md` |
| Requête exacte | `.../osrm/request.txt` | `.../valhalla/request.json` |
| Réponse brute exacte | `.../osrm/response.json` | `.../valhalla/response.json` (version enrichie ré-auditée) |
| Configuration effective | (profil `car.lua` par défaut, non modifié) | `.../valhalla/effective-config-meili.json` (section `meili` réelle extraite de la config générée) |
| Trace d'entrée partagée | `docs/ai-context/map-matching-spike/shared/input-trace.csv` (36 lignes, index/coord/timestamp/radius/intention — trace synthétique, aucune donnée personnelle, vérifié par grep avant ajout) — **identique pour les deux moteurs** | idem |
| Route source (self-référentielle) | `docs/ai-context/map-matching-spike/shared/synthetic-self-referential-route.json` | idem (même fichier partagé) |
| Script d'audit déterministe | `.../osrm/audit-osrm.ps1` (JSON parsing réel, vérifié en le ré-exécutant depuis la fixture seule) | `.../valhalla/audit-valhalla.ps1` (idem) |
| Nettoyage | conteneurs `osrm-spike` arrêtés/supprimés après usage | conteneurs `valhalla-spike`/`valhalla-spike-r2` arrêtés/supprimés après usage |

Voir `docs/ai-context/map-matching-spike/README.md` pour l'avertissement complet "SYNTHETIC
SELF-REFERENTIAL REFERENCE — NOT INDEPENDENT GROUND TRUTH" et la confirmation de l'absence de
toute donnée personnelle dans cette fixture.

### 17.7 GraphHopper — statut inchangé

Non exécuté ce round non plus (explicitement non requis par la demande de correction). Procédure
de reproduction inchangée par rapport à la version précédente de cette étude (build Maven du module
`map-matching`, import du même extrait OSM, exécution CLI) — voir l'historique Git de ce fichier
pour le détail complet de cette procédure si nécessaire ; non reproduite intégralement ici pour ne
pas alourdir un document déjà long, le contenu factuel n'ayant pas changé.

## 18. Ce qui N'A PAS été implémenté (confirmation explicite)

Aucun wiring du buffer, aucune cadence GPS adaptative, aucun map matching en production, aucune
cellule H3 `RECONSTRUCTED`, aucun changement du corridor, rien pour Certified, aucun backend
géographique de production, aucun téléchargement de graphe sur Android, aucune UI nouvelle.

## 19. Risques (mis à jour)

- **Faux positifs de reconstruction** : confirmé concrètement possible dans ce round (Valhalla,
  §17.3) — pas seulement théorique.
- **Deux moteurs, deux philosophies de gestion des points aberrants, ni harmonisées ni comparées
  équitablement** (§17.5) — tout classement serait prématuré.
- **Comportement de split/gap non déterministe observé pour OSRM ET pour Valhalla** — les deux
  moteurs ont laissé passer un trou temporel sans le signaler comme tel.
- **Le deuxième `null` OSRM (index 20) reste inexpliqué** — pourrait masquer un comportement plus
  large à comprendre avant tout choix de moteur.
- **Sous-estimer le coût ODbL d'une distribution de graphe régional** — revue légale non faite ici.
- **Sur-indexer sur un seul petit extrait insulaire et un seul scénario routier simple** — voir
  §20 pour la conception d'un vrai corpus.

## 20. Conception d'une vérité terrain indépendante (nouveau, remplace toute notion de "moteur = vérité")

**Principe non négociable : ne jamais utiliser un des moteurs candidats pour générer la vérité
terrain qui sert ensuite à l'évaluer** — c'est exactement le défaut méthodologique corrigé en §5.

**Référence (vérité terrain)** : un enregistrement GPS indépendant à haute fréquence (idéalement
1-5s, bien plus dense que notre cadence produit) + une vérification manuelle du trajet/chemin
réellement emprunté + horodatage précis. Sources possibles à documenter, non mises en œuvre ici :
GPS brut haute fréquence du téléphone en mode dédié, un logger/appareil séparé si utile pour une
référence indépendante du même hardware que la future capture produit, un export GPX croisé avec
une inspection manuelle de carte, une vidéo/dashcam pour les intersections difficiles si
nécessaire.

**Entrée moteur (dégradée)** : des copies dérivées et dégradées de cette même référence — jamais
une trace re-générée par un moteur. Dégradation à plusieurs niveaux, correspondant aux scénarios de
cadence (§13, catégorie SPACING) : ~7s (marche), ~27s (mesuré, standard), ~27s/50km/h, ~27s/90km/h,
avec trous et points aberrants injectés séparément de manière contrôlée et documentée (pas
mélangés silencieusement, contrairement à ce que ce round a dû corriger pour le spike Valhalla/OSRM
existant).

**Principe d'équité** : tous les moteurs testés dans un futur benchmark réel doivent recevoir
**strictement la même entrée dégradée**, générée une seule fois depuis la référence indépendante,
jamais régénérée séparément par moteur.

**Rien de tout cela n'est mis en œuvre ici** — c'est une conception, pas une collecte.

## 21. Questions ouvertes

- Architecture serveur/local/hybride définitive (§8).
- Politique de rétention des coordonnées brutes si l'option serveur/hybride est retenue (§9).
- Comment acquérir concrètement la référence indépendante à haute fréquence décrite en §20 (quel
  matériel, quel protocole de terrain, quel consentement) — non tranché ici.
- Le deuxième `null` OSRM (index 20, §17.2) mérite-t-il une investigation dédiée avant le
  benchmark, ou peut-il être laissé de côté comme bruit d'un seul essai ? Recommandation de cette
  étude : à revérifier au moins une fois avec un point d'index différent avant de le considérer
  comme un bruit isolé sans suite.
- GraphHopper reste candidat sans données empiriques équivalentes — pas d'urgence à trancher avant
  le prochain benchmark neutre (§20).

## Sources

- Valhalla — algorithme Meili (HMM/Viterbi, Newson-Krumm) :
  https://valhalla.github.io/valhalla/contributing/architecture/meili/algorithms/
- Valhalla — API `trace_attributes`, sémantique exacte de `matched_points[].type`, `edge_index`,
  `distance_along_edge`, `distance_from_trace_point` (confirmé : mètres) :
  https://github.com/valhalla/valhalla-docs/blob/master/map-matching/api-reference.md — **note
  d'épinglage** : ce fichier est un miroir/copie de la référence API du service de map matching
  Valhalla dans le dépôt `valhalla-docs` (pas `valhalla/valhalla` lui-même) ; le contenu a été
  directement vérifié contre notre propre réponse réelle (§17.3), qui utilise exactement les mêmes
  noms de champs — cohérence confirmée empiriquement, pas seulement documentaire.
- Valhalla — licence MIT : https://github.com/valhalla/valhalla/blob/master/LICENSE.md
- Valhalla — paramètres `meili` par défaut (`gps_accuracy`, `search_radius`, `breakage_distance`,
  `interpolation_distance`, `sigma_z`, `beta`) : **source primaire = notre propre configuration
  générée par `valhalla_build_config`, préservée intégralement dans
  `docs/ai-context/map-matching-spike/valhalla/effective-config-meili.json`** — pas une
  documentation secondaire, une observation directe du binaire réellement utilisé (version
  3.8.3-7f372987b).
- OSRM — service Match, paramètres (`gaps`, `tidy`, `radiuses`, `timestamps`), structure de réponse
  (`matchings[].confidence`, `tracepoints[]`, `alternatives_count`) :
  https://project-osrm.org/docs/v5.22.0/api/#match-service
- OSRM — licence BSD 2-Clause : https://github.com/Project-OSRM/osrm-backend/blob/master/LICENSE.TXT
- GraphHopper — algorithme et implémentation : dépôt `graphhopper/graphhopper`, module
  `map-matching`, fichier
  https://github.com/graphhopper/graphhopper/blob/master/map-matching/src/main/java/com/graphhopper/matching/MapMatching.java
  — **comportement inféré partiellement du code source public et de la description du dépôt, pas
  d'une documentation d'API formelle équivalente à celle de Valhalla/OSRM ; signalé explicitement
  comme tel. Aucun commit/tag précis n'est épinglé pour ce fichier — GraphHopper n'ayant pas été
  exécuté, cette référence reste une lecture de code, pas une vérification empirique.**
- GraphHopper — licence Apache-2.0 (cœur + module map-matching) :
  https://github.com/graphhopper/graphhopper/blob/master/LICENSE.txt
- OSM/ODbL — structure légale, distinction Produced Work / Derivative Database, attribution :
  https://osmfoundation.org/wiki/Licence/Licence_and_Legal_FAQ ,
  https://osmfoundation.org/wiki/Licence/Attribution_Guidelines , et
  https://wiki.openstreetmap.org/wiki/Open_Data_License/Legal_Structure (pages officielles de
  l'OpenStreetMap Foundation).

**Honnêteté sur le degré d'épinglage** : les licences et références API (Valhalla, OSRM) sont
épinglées à un fichier/une page précise, vérifiée par lecture directe. Le comportement de
GraphHopper reste une lecture de code non exécutée, explicitement signalée comme moins solide que
les deux audits empiriques OSRM/Valhalla. Les numéros de version/digest d'image (§17.6) sont, eux,
directement observés à l'exécution, pas déduits d'une documentation.

## Fixture de reproduction durable (ce round de correction)

**Correction (Codex) : la reproductibilité ne doit plus dépendre d'un chemin scratchpad, de
l'historique du shell, ou de l'historique de conversation.** Tout le nécessaire pour reproduire
les deux spikes audités (OSRM, Valhalla) est maintenant présent dans
`docs/ai-context/map-matching-spike/` :

```
docs/ai-context/map-matching-spike/
├── README.md                              -- avertissement self-référentiel + index
├── shared/
│   ├── input-trace.csv                    -- les 36 points exacts, partagés par les deux moteurs
│   └── synthetic-self-referential-route.json  -- la route OSRM brute dont le tracé est dérivé
├── osrm/
│   ├── commands.md                        -- commandes Docker exactes, sources, notes MSYS
│   ├── request.txt                        -- URL /match exacte réellement exécutée
│   ├── response.json                      -- réponse brute exacte reçue
│   ├── audit-osrm.ps1                     -- script d'audit déterministe (ConvertFrom-Json)
│   └── audit-result.md                    -- sortie précalculée, vérifiée reproductible
└── valhalla/
    ├── commands.md                        -- commandes Docker exactes, sources, notes MSYS
    ├── request.json                       -- corps JSON exact réellement envoyé
    ├── response.json                      -- réponse brute exacte reçue (version enrichie)
    ├── effective-config-meili.json        -- section meili réelle extraite de la config générée
    ├── audit-valhalla.ps1                 -- script d'audit déterministe (ConvertFrom-Json)
    └── audit-result.md                    -- sortie précalculée, vérifiée reproductible
```

**Validation réellement effectuée** : les deux scripts (`audit-osrm.ps1`, `audit-valhalla.ps1`) ont
été exécutés directement depuis cette fixture (`powershell -File audit-*.ps1`, working directory =
le sous-dossier lui-même) et produisent exactement les tableaux reportés en §17.2/§17.3 — pas
seulement une vérification mécanique de cohérence, une **ré-exécution réelle et vérifiée**. Les
commandes Docker elles-mêmes (extraction/partition/customize/build-tiles/service) n'ont pas été
ré-exécutées depuis un répertoire temporaire propre dans ce round (les tuiles construites et les
réponses brutes existaient déjà et n'ont pas changé) ; leur cohérence a été vérifiée mécaniquement
(pas de placeholder, chemins/arguments internes cohérents, URLs sources présentes) plutôt que par
ré-exécution complète du pipeline de build, jugée disproportionnée pour ce round strictement
documentaire.

Aucune donnée de `tracking-calibration.ndjson`/`trip1.txt`/`trip2.txt`/`trip3.txt`/`vehicle1.txt`
n'a été copiée dans cette fixture — vérifié par recherche de ces noms de fichiers et par recherche
de tout chemin/nom d'utilisateur de la machine source, les deux recherches ne retournant aucun
résultat.

---

## Résultat attendu (structure demandée pour ce round de correction — reproductibilité uniquement)

**Ce round ne rouvre aucune interprétation technique déjà acceptée** (unité Valhalla, audits
d'index, classement retiré, métriques H3, vocabulaire buffer — tous inchangés depuis le round
précédent). Il ajoute uniquement la fixture de reproduction durable ci-dessus.

**A. Répertoire de fixture durable créé** : `docs/ai-context/map-matching-spike/` (voir
l'arborescence complète dans "Fixture de reproduction durable" ci-dessus) — documentation
uniquement, aucun code de production.

**B. Entrée synthétique exacte préservée** : `shared/input-trace.csv`, 36 lignes, valeurs réelles
(pas de placeholder) — index, longitude, latitude, epoch, rayon/précision déclarée, étiquette
d'intention exacte (y compris la mécanique précise du point 21/22 : lequel est le clone bruité,
lequel est la position vraie, où se situe exactement le saut temporel).

**C. Commandes/requête/réponse OSRM préservées** : `osrm/commands.md` (image, digest, version,
source PBF avec URL, profil, commandes extract/partition/customize/serveur complètes, notes MSYS
Windows), `osrm/request.txt` (URL `/match` complète réellement exécutée), `osrm/response.json`
(réponse brute exacte).

**D. Audit OSRM reproductible** : `osrm/audit-osrm.ps1` (vrai parsing JSON, `ConvertFrom-Json`,
jamais de regex) **exécuté depuis la fixture seule dans ce round** et vérifié produire exactement
le tableau attendu : index 20 → `null`, index 21 → `null`, index 21 = perturbation délibérée,
index 20 = observation normale, second `null` toujours non expliqué (aucune explication inventée).
Sortie précalculée dans `osrm/audit-result.md`.

**E. Commandes/requête/réponse Valhalla préservées** : `valhalla/commands.md` (image, digest,
version, source OSM, génération de config, build de tuiles, commande de service complète, note sur
l'évolution du filtre d'attributs), `valhalla/request.json` (corps JSON complet réellement envoyé,
y compris les attributs de discontinuité), `valhalla/response.json` (réponse brute exacte).

**F. Configuration effective Valhalla préservée** : `valhalla/effective-config-meili.json` —
sous-ensemble JSON exact extrait de la configuration réellement générée par
`valhalla_build_config` (search_radius=50, gps_accuracy=5.0 par défaut mais surchargé par requête
à 25/10, breakage_distance=2000, interpolation_distance=10, sigma_z=4.07, beta=3, et les surcharges
par profil `auto`/`pedestrian`/`bicycle`/`multimodal`) — **explicitement dit être des valeurs de
config générées, pas des valeurs harmonisées avec OSRM.**

**G. Audit Valhalla reproductible** : `valhalla/audit-valhalla.ps1`, exécuté depuis la fixture seule
dans ce round, vérifié produire exactement le tableau attendu — le point déplacé (~120m de sa
référence), Valhalla l'associant à un autre edge éligible voisin (144, sans nom),
`distance_from_trace_point`≈5.7m référant la coordonnée bruitée d'entrée au point matché (pas la
position de référence), présenté explicitement comme un faux positif plausible **pour cette
fixture synthétique seulement**, sans généralisation. Sortie précalculée dans
`valhalla/audit-result.md`.

**H. Formulation GraphHopper corrigée** : toute référence à "voir l'historique Git de ce fichier"
a été retirée (§17.7) — le document était non suivi par Git avant ce round, une telle référence
était donc impossible à honorer. Remplacée par : statut NON EXÉCUTÉ explicite, références
factuelles actuelles (§Sources), procédure de spike future proposée (§6/§17.7), aucun classement.

**I. Sources/épinglage faisant autorité** : section Sources réécrite avec des URLs complètes pour
Valhalla (algorithme, API, licence), OSRM (API Match, licence), GraphHopper (implémentation,
licence — avec mention explicite que c'est une lecture de code, pas une doc API formelle), et ODbL
(pages officielles OSMF). Une note d'honnêteté précise ce qui est épinglé à un fichier/une version
précise vs. ce qui reste une observation directe (config Valhalla) vs. une lecture de code
(GraphHopper).

**J. Placeholders retirés** : recherche effectuée sur l'ensemble du document et de la fixture pour
`<...>`, `TODO`, "scratchpad" (en tant que référence probante), "see history"/"voir l'historique",
"previous command"/"commande précédente", "same as before"/"identique à avant" utilisés comme
substitut d'une valeur réelle — toutes les occurrences trouvées ont été soit remplacées par la
valeur réelle, soit reformulées pour ne plus se présenter comme une preuve de reproductibilité (les
mentions restantes de "scratchpad" dans le document décrivent un fait historique passé, jamais un
chemin à suivre pour reproduire quoi que ce soit aujourd'hui).

**K. Vérification de confidentialité** : recherche effectuée sur tous les fichiers de la fixture
pour un nom d'utilisateur/chemin machine et pour les noms des artefacts de calibration réels
(`tracking-calibration.ndjson`, `trip1.txt`, `trip2.txt`, `trip3.txt`, `vehicle1.txt`) — aucune
occurrence trouvée dans aucun cas ; ces quatre derniers fichiers n'ont pas été copiés dans la
fixture et restent des artefacts de calibration locaux.

**L. Fichiers ajoutés/modifiés** : voir §Git status ci-dessous pour la liste exacte vérifiée.

**M. Validation effectuée** : les deux scripts d'audit ont été réellement ré-exécutés depuis la
fixture (pas seulement vérifiés mécaniquement) et produisent des sorties identiques à celles
documentées. Le pipeline Docker complet (extraction/partition/customize/build-tiles) n'a pas été
rejoué depuis un répertoire propre dans ce round — jugé disproportionné puisque les tuiles et
réponses brutes existantes n'ont pas changé — mais les fichiers de commandes ont été vérifiés
mécaniquement (aucun placeholder, arguments cohérents, URLs sources présentes).

**N. Limites restantes** : le pipeline Docker de build n'a pas été rejoué de bout en bout depuis un
répertoire vierge dans ce round (seuls les scripts d'audit l'ont été) ; GraphHopper reste non
exécuté ; le deuxième `null` OSRM (index 20) reste inexpliqué ; aucune vérité terrain indépendante
n'existe encore.

**O. Git status** : voir ci-dessous.
