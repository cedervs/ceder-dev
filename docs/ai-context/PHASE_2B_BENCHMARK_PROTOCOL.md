# Phase 2B — Engine-neutral real-ground-truth benchmark protocol

**Statut : CONCEPTION + OUTILLAGE DE BENCHMARK — aucun moteur sélectionné, aucune intégration
production, aucun câblage du buffer, aucune cadence GPS modifiée, aucune reconstruction activée,
`discovered_cells` inchangée, corridor inchangé.**

**Round 2B-1B (correction) : ce document a été corrigé suite à la revue Codex du round 2B-1
initial, qui a identifié plusieurs défauts invalidant le benchmark avant toute collecte physique**
(modèle de vérité collapsé en une seule trace GPS brute, métrique de recouvrement non pondérée par
la longueur, bug d'antiméridien, sous-échantillonnage H3 par troncature au lieu d'arrondi supérieur,
générateur de dégradation non implémenté malgré une description qui le laissait entendre, modèles
non réellement sérialisables malgré l'affirmation contraire, logger non choisi, stockage privé non
protégé par `.gitignore`). Chaque correction ci-dessous est marquée **[2B-1B]** à l'endroit où elle
s'applique. Aucune collecte physique n'a eu lieu ni en 2B-1 ni en 2B-1B — ce round corrige
uniquement les fondations avant que la première capture physique ne soit autorisée (voir la
conclusion finale du rapport de ce round : `READY FOR LOGGER MINI-VALIDATION` ou
`STILL NOT READY FOR ANY PHYSICAL ACTION`).

Ce document conçoit (et, pour l'outillage, commence à implémenter) le benchmark neutre exigé après
Phase 2A. Il ne contient **aucun résultat de benchmark réel** — le corpus physique indépendant
n'existe pas encore (voir §C, "ce qui reste à faire par l'utilisateur"). Ce round livre :
architecture du harnais, formats de données, protocole de capture de vérité terrain, règles de
dégradation, politique de normalisation, conception des métriques (géométrie + H3 + acceptation/
rejet), plan d'analyse sparse-27s, plan de preuve pour la cadence adaptative future, **et
l'exécution réelle de GraphHopper** (précédemment non exécuté en Phase 2A).

Complète, sans les remplacer : `docs/ai-context/MAP_MATCHING_ENGINE_STUDY.md` (Phase 2A — Valhalla/
OSRM comparés, GraphHopper non exécuté à l'époque) et
`docs/ai-context/map-matching-spike/` (fixture de reproduction Phase 2A, maintenant étendue avec
`graphhopper/`).

## Légende de classification (appliquée dans tout ce document)

- **DECIDED** : décision produit déjà actée ailleurs, rappelée ici sans être rouverte.
- **ENGINEERING DESIGN** : conception technique proposée dans ce document, pas encore un résultat
  mesuré.
- **CALIBRATION REQUIRED** : valeur numérique dont ce document propose la *méthode* de
  détermination, jamais la valeur elle-même.
- **BENCHMARK RESULT** : issu d'une mesure réelle. **Aucune section de ce document n'est marquée
  BENCHMARK RESULT** — le corpus physique n'existe pas encore.
- **OPEN QUESTION** : nécessite une décision produit future, non tranchée ici.

---

## A. Architecture Phase 2B

### A.1 Principe directeur

```
référence indépendante (haute fréquence, vérité terrain manuelle)
        │
        ├──► variantes dégradées standardisées (7s / 15s / 27s / 45s+ / adversariales)
        │            │
        │            ├──► adaptateur OSRM   ──► sortie brute OSRM   ──► normalisation
        │            ├──► adaptateur Valhalla──► sortie brute Valhalla──► normalisation
        │            └──► adaptateur GraphHopper──► sortie brute GraphHopper──► normalisation
        │                                                  │
        │                                                  ▼
        │                                   BenchmarkResult (représentation neutre)
        │                                                  │
        └──► H3 vérité terrain ◄──────────── comparaison ──┴──► H3 moteur
                    │                            │
                    ▼                            ▼
            métriques géométrie          métriques H3 (TP/FP/FN)
                    │                            │
                    └──────────────┬─────────────┘
                                   ▼
                       tables de résultats par scénario × moteur × cadence
```

Neuf étapes (reprises telles que demandées §13 de la requête) :
1. charger la vérité terrain ;
2. charger le jeu d'observations dégradées ;
3. exécuter l'adaptateur du moteur ;
4. normaliser la sortie vers la représentation neutre (`EngineMatchResult`, §B) ;
5. comparer la géométrie matchée à la vérité terrain (§G) ;
6. dériver la couverture H3 depuis la vérité terrain ET la géométrie matchée, avec la **même**
   politique d'échantillonnage pour les deux (§H) ;
7. calculer les métriques (§G/§H/§I) ;
8. persister la sortie brute du moteur, séparément de la représentation normalisée ;
9. générer les tables de synthèse (§26 de la requête → tables SCÉNARIO × MOTEUR × CADENCE).

### A.2 Emplacement (hors production)

Le harnais vit sous `tools/mapmatch-benchmark/` — un nouveau répertoire, au même niveau que
`tools/geo/` (convention déjà établie dans ce dépôt pour l'outillage hors application). **Ce n'est
pas un module Gradle** : aucune modification de `settings.gradle.kts`/`build.gradle.kts`, aucune
nouvelle dépendance déclarée pour l'app ou le backend. Le code est compilé/exécuté via le même
toolchain manuel kotlinc déjà établi tout au long de cette session (voir §F/§M pour les commandes
exactes), exactement comme les scripts d'audit Phase 2A. `TrajectoryReconstructor`/le contrat de
production ne sont ni modifiés ni instanciés par ce code — le harnais définit ses **propres** types
neutres (§B), volontairement découplés du contrat Phase 1, avec une analyse de correspondance
future documentée séparément (§K et non implémentée).

### A.3 Ce qui a été réellement implémenté ce round (pas seulement conçu)

**[2B-1B] Correction de documentation (§6 de la correction)** : le round 2B-1 affirmait ici que les
modèles et métriques étaient "testés unitairement" — c'était **faux** : aucun fichier de test
n'existait en 2B-1, seul le smoke test (qui n'est pas un test unitaire) avait été exécuté. Ce round
(2B-1B) corrige cela en ajoutant de véritables fichiers de test (voir §L du rapport de correction
pour les preuves d'exécution) — la liste ci-dessous ne décrit désormais que ce qui est réellement
vrai à l'issue de 2B-1B :

- Les modèles de données neutres (§B) : implémentés, compilés, et **réellement `@Serializable`**
  (2B-1B — compilés avec le plugin compilateur `kotlinx-serialization`, ce que 2B-1 n'avait pas
  fait malgré l'annotation ; voir §B).
- Les métriques de géométrie (§G) : implémentées (distance de Hausdorff discrète, distance de
  Fréchet discrète — **recalculée en itératif en 2B-1B après qu'un `StackOverflowError` réel a été
  observé** sur la version récursive de 2B-1 avec des entrées densifiées de taille réaliste, voir
  §G —, déviation along-track, pourcentage de recouvrement **pondéré par la longueur depuis
  2B-1B**) — compilées, **testées unitairement pour la première fois en 2B-1B**
  (`GeometryMetricsTest.kt`, exécuté avec succès, voir §L du rapport de correction).
- La dérivation et comparaison H3 (§H) : implémentée (échantillonnage dense d'une géométrie en
  cellules H3 — **pas d'arrondi supérieur (`ceil`) corrigé en 2B-1B**, voir §H —, comparaison
  d'ensembles TP/FP/FN/précision/rappel) — compilée, **testée unitairement pour la première fois en
  2B-1B** (`H3ComparisonTest.kt`).
- Un adaptateur OSRM et un adaptateur Valhalla (§F) : implémentés contre les réponses brutes
  réellement obtenues en Phase 2A (`docs/ai-context/map-matching-spike/osrm/response.json` et
  `.../valhalla/response.json`) — étendus en 2B-1B pour produire `EngineConfigIdentity` (§B.3bis).
- Un **test de fumée** ("smoke test") de bout en bout, utilisant le tracé auto-référentiel Isle of
  Man de Phase 2A (voir l'avertissement §A.4) — pas un résultat de benchmark. Rejoué en 2B-1B avec
  les métriques corrigées (voir §M pour les nouveaux chiffres).
- **[2B-1B nouveau]** Un générateur de dégradation déterministe réellement implémenté
  (`Degradation.kt`) — 2B-1 décrivait la règle de génération en §D.5 mais **ne l'avait pas codée**
  (correction §6 : "do not claim degradation generator exists if it does not" — 2B-1's phrasing was
  ambiguous enough to read as a claim of existence; corrected here by actually implementing it and
  testing it, `DegradationTest.kt`).
- **[2B-1B nouveau]** Le modèle de vérité en trois couches (§2 de la correction) : `ReferencePoint`
  (évidence brute), `VerifiedRouteGeometry`/`VerifiedSegment` (vérité structurée), remplaçant le
  champ libre `manuallyVerifiedPath: String?` de 2B-1 — voir §B.1.
- **[2B-1B nouveau]** `EngineConfigIdentity` (hash SHA-256 du fixture de requête et de la réponse
  brute, profil/costing, mode DEFAULT/NORMALIZED) — voir §B.3bis.
- Un adaptateur GraphHopper : **non implémenté, ni en 2B-1 ni en 2B-1B** (voir §F pour pourquoi — la
  sortie GPX du CLI ne fournit pas la correspondance par index nécessaire ; la voie REST JSON, plus
  riche, est bloquée par la limitation d'environnement documentée en §F ; la correction §18 demande
  explicitement de ne PAS résoudre ce point ce round).

### A.4 Avertissement répété à dessein

**Le tracé Isle of Man auto-référentiel (dérivé d'une requête `/route` OSRM) reste, comme en Phase
2A, une fixture technique, jamais une vérité terrain.** Le "smoke test" §M l'utilise uniquement
pour prouver que le pipeline logiciel fonctionne mécaniquement (chargement → adaptation →
normalisation → comparaison géométrique → H3 → métriques → persistance → synthèse) — **ses
chiffres ne mesurent la justesse d'aucun moteur** et ne doivent jamais être cités comme un résultat
de benchmark. Voir §C pour la vraie vérité terrain requise avant tout résultat exploitable.

---

## B. Formats de données du benchmark

**[2B-1B] Correction de documentation (§6) :** 2B-1 affirmait ici que les fichiers vivaient sous
`tools/mapmatch-benchmark/src/model/` — **faux**, ils vivent à plat sous `tools/mapmatch-benchmark/
src/*.kt` (`Models.kt`, `GeometryMetrics.kt`, etc.), corrigé ci-dessous. Ces classes sont désormais
**réellement** `@Serializable` (`kotlinx.serialization`) — en 2B-1 l'annotation était présente sur
le papier de ce document mais le code réel n'avait ni l'annotation ni le plugin compilateur
nécessaire pour qu'elle produise un effet ; en 2B-1B le plugin `kotlinx-serialization` est activé à
la compilation (voir `tools/mapmatch-benchmark/README.md`) et la compilation a été revérifiée avec
succès. `IntRange` (utilisé par `EngineMatchResult.splits` en 2B-1) n'est pas nativement
sérialisable ; remplacé par `SplitRange(startIndex, endIndex)` en 2B-1B.

### B.1 Le modèle de vérité en trois couches — **[2B-1B] réécrit intégralement (§2 de la correction)**

2B-1 ne distinguait pas la trace GPS brute de la vérité terrain elle-même — `ReferenceTrace`
portait un champ `manuallyVerifiedPath: String?` en texte libre, rejeté explicitement par la
correction ("Do not use one free-form String as the only truth representation"). Trois concepts
distincts, désormais représentés séparément :

**A. `ReferencePoint`** — observation GPS brute haute fréquence. **De l'évidence, jamais
automatiquement la vérité.**

```
ReferencePoint(lat, lon, timestampEpochMs: Long, accuracyMeters?, bearingDegrees?, speedMps?, providerOrSource?)
```

(`timestampEpochMs` est un `Long` epoch-millisecondes — 2B-1's Kotlin-shaped sketch disait à tort
`Instant` dans ce document ; le code réel a toujours utilisé `Long`, corrigé ici pour correspondre.)

**B. `VerifiedRouteGeometry`** — le trajet réellement emprunté, vérifié indépendamment. **Source de
vérité pour la justesse géométrique et les cellules H3 vérité (§G/§H). NE DOIT JAMAIS être produite
par OSRM/Valhalla/GraphHopper.**

```
VerifiedRouteGeometry(
    scenarioId: String,
    geometry: List<LatLon>,                 // trajet réel tracé manuellement
    segments: List<VerifiedSegment>,
    overallConfidence: TruthConfidence,      // HIGH / MEDIUM / UNUSABLE
    sourceDescription: String,               // ex. "trace manuelle OSM/imagerie + séquence de virages connue" -- jamais "sortie OSRM /match"
)
```

**C. `VerifiedSegment`** — annotation structurée par segment, remplaçant le champ libre de 2B-1 :

```
VerifiedSegment(
    segmentId: String,
    startReferenceIndex: Int, endReferenceIndex: Int,   // indices dans ReferenceTrace.rawObservations
    corridorId: String,                      // identifiant réel de route/chemin emprunté
    verificationMethod: VerificationMethod,  // MAP_INSPECTION / KNOWN_TURN_SEQUENCE / VIDEO_OR_DASHCAM / SECOND_INDEPENDENT_LOGGER / MANUAL_ROUTE_ANNOTATION
    confidence: TruthConfidence,             // HIGH / MEDIUM / UNUSABLE
    ambiguityNote: String?, roadOrPathDescription: String?, verifiedGeometryRef: String?,
)
```

**`TruthConfidence`** (§10 de la correction) : seule la confiance `HIGH` doit être utilisée pour la
métrique de sécurité H3-faux-positifs dans un premier temps ; `MEDIUM` peut être rapportée
séparément ; `UNUSABLE` est exclue de toute métrique de qualité (conservée seulement pour le suivi
de corpus).

**`ReferenceTrace`** (mise à jour) :

```
ReferenceTrace(
    id: String, capturedAtEpochMs: Long, device: String, acquisitionMode: String,
    rawObservations: List<ReferencePoint>,        // renommé depuis `points` -- évidence, pas vérité
    verifiedGeometry: VerifiedRouteGeometry?,      // null tant que CAPTURED mais pas encore VALIDATED
    corpusCategory: CorpusCategory, corpusStatus: CorpusStatus,
)
```

### B.2 `DegradationSpec` + `DegradedObservationSet` — **[2B-1B] `DegradationSpec` est nouveau (§5)**

2B-1 ne référençait aucune spécification structurée — une variante n'était qu'une étiquette de
chaîne (`"27s"`). Un `DegradedObservationSet` doit désormais référencer la spécification exacte qui
l'a produit :

```
DegradationSpec(
    targetCadenceSeconds: Double,
    selectionPolicy: DegradationSelectionPolicy = FIRST_AT_OR_AFTER_TARGET,  // seule règle implémentée ce round
    gapInjectionSeconds: Double?, accuracyPerturbationMeters: Double?, coordinateNoiseMeters: Double?,
    outlierRule: OutlierInjectionRule = NONE,
    randomSeed: Long?,
)
DegradedObservationSet(
    referenceTraceId: String,
    variant: String,                     // "7s" / "15s" / "27s" / "45s" / "adversarial-<type>"
    mode: TransportMode,                 // CAR / WALK / BIKE
    spec: DegradationSpec,               // [2B-1B nouveau] -- traçabilité exacte de la génération
    observations: List<DegradedObservation>,
)
DegradedObservation(
    index: Int, lat: Double, lon: Double, timestampEpochMs: Long,
    accuracyMeters: Float?, bearingDegrees: Float?, speedMps: Float?,
    intentLabel: String,                 // "NORMAL" / "INJECTED_OUTLIER" / "STALE" / etc.
)
```

**Règle d'identité (§10 de la requête, non négociable) :** un seul `DegradedObservationSet` par
(scénario, variante) est produit et **partagé, byte-pour-byte, entre les trois adaptateurs** — un
adaptateur ne fait que transformer sa *forme* pour l'API du moteur (URL OSRM, JSON Valhalla, GPX
GraphHopper), jamais son contenu logique. Quand un moteur ne peut pas recevoir un champ (ex. GPX ne
porte pas `accuracyMeters` — voir Phase 2A/2B §F), cela doit être **enregistré explicitement** dans
le résultat normalisé (`EngineMatchResult.unsupportedInputFields`), jamais silencieusement omis.

**[2B-1B nouveau] Générateur réellement implémenté** (`tools/mapmatch-benchmark/src/Degradation.kt`) :
pour chaque instant cible `t0 + k·cadence`, sélectionne la première observation non encore
sélectionnée à ou après cet instant (règle recommandée par la correction §16, implémentée telle
quelle). Gère par construction : intervalles source irréguliers, arrêts (timestamps quasi
identiques), trous plus grands que la cadence (aucun point n'est synthétisé pour les combler),
absence de doublon (garantie structurellement par un curseur de recherche monotone, pas seulement
vérifiée), et une politique de point final (le dernier point de la trace de référence est toujours
inclus, même s'il ne tombe pas exactement sur une frontière de cadence). Testé dans
`DegradationTest.kt` (voir §K du rapport de correction pour les cas couverts et la preuve
d'exécution). Les perturbations aléatoires (bruit de précision/coordonnées, injection d'aberrants)
restent **non implémentées** ce round, conformément à la correction §16 ("do not implement unless
needed now").

### B.3 `EngineMatchResult` — la représentation neutre normalisée (sortie commune, §14 de la requête)

```
EngineMatchResult(
    configIdentity: EngineConfigIdentity,  // [2B-1B nouveau, remplace engine/engineVersion/graphSource/rawResponsePath séparés -- voir §B.3bis
    scenarioId: String, variant: String, mode: TransportMode,
    matchedGeometry: List<LatLon>,       // la polyligne complète retournée par le moteur
    perObservation: List<ObservationOutcome>?,  // null si le moteur ne fournit pas de correspondance par observation (ex. GraphHopper CLI GPX -- voir §F)
    splits: List<SplitRange>,            // [2B-1B] remplace `List<IntRange>` (non sérialisable) -- sous-traces si le moteur a coupé la trace
    engineNativeConfidence: Map<String, String>,   // signaux bruts tels quels, jamais reformatés en une fausse équivalence (§23 de la requête)
    unsupportedInputFields: List<String>,          // ex. ["accuracyMeters"] pour GraphHopper/GPX
    runtimeMillis: Long,
    errors: List<String>,
)
ObservationOutcome(observationIndex: Int, outcome: MatchOutcome, matchedPoint: LatLon?, distanceToObservationMeters: Double?, edgeOrCandidateId: String?)
enum MatchOutcome { MATCHED, INTERPOLATED, UNMATCHED, UNKNOWN }
```

`perObservation` est **nullable par conception** — GraphHopper (voie CLI GPX) ne le remplit pas
(voir §F), OSRM et Valhalla le remplissent tous les deux (36 entrées, un par observation d'entrée,
confirmé empiriquement en Phase 2A). Aucune tentative de "deviner" une correspondance absente.

### B.3bis `EngineConfigIdentity` — **[2B-1B nouveau] identité de configuration (§4 de la correction)**

Rend un résultat auditable : à partir de ce seul enregistrement, on doit pouvoir identifier
exactement quelle version de moteur, quel profil, quel mode (DEFAULT/NORMALIZED), et quel
fixture d'entrée/sortie ont produit un `EngineMatchResult` donné.

```
EngineConfigIdentity(
    engine: String, engineVersion: String,
    profileOrCosting: String,             // ex. "car" (OSRM), "auto" (Valhalla costing)
    benchmarkMode: BenchmarkMode,         // DEFAULT / NORMALIZED (§E)
    graphSource: String,
    requestFixturePath: String, requestFixtureSha256: String,   // hash SHA-256 du fichier de requête
    rawResponsePath: String, rawResponseSha256: String,         // hash SHA-256 de la réponse brute
)
```

Hashes calculés par `Sha256.kt` (simple `MessageDigest`, pas d'infrastructure de signature
cryptographique — conforme à la correction : "do not require cryptographic infrastructure if simple
SHA-256/path metadata is enough"). Vérifié dans le smoke test réexécuté (§M) — les quatre hashes
imprimés dans `smoke-test-output.log` sont calculés réellement sur les fichiers fixture existants,
pas des valeurs de substitution.

### B.4 `BenchmarkResult` — une ligne de la table finale (§26 de la requête)

```
BenchmarkResult(
    scenarioId, engine, variant, mode,
    geometryMetrics: GeometryMetrics,     // §G
    h3Metrics: H3Metrics,                 // §H
    acceptance: AcceptanceClassification?, // §I
    wrongRoadClassification: WrongRoadClassification?, // [2B-1B nouveau] §I / correction §12
    engineMatchResult: EngineMatchResult, // référence complète, pas dupliquée
)
GeometryMetrics(hausdorffMeters, discreteFrechetMeters, meanAlongTrackDeviationMeters, routeOverlapPercent, densificationSpacingMeters, wrongRoadSegmentPercent?)
H3Metrics(truePositiveCells, falsePositiveCells, falseNegativeCells, precision, recall, resolution, samplingStepMeters)
enum AcceptanceClassification { ACCEPTED_CORRECT, ACCEPTED_WRONG, REJECTED_CORRECT, REJECTED_BAD, AMBIGUOUS_DETECTED, SPLIT, UNMATCHED_CORRECT, UNMATCHED_WRONG }
enum WrongRoadClassification { CORRECT, WRONG_ROAD, AMBIGUOUS, NOT_EVALUABLE }  // [2B-1B nouveau] -- voir §I pour la règle opérationnelle
```

Chaque ligne reste **engine-neutre en structure**, mais `engineMatchResult.engineNativeConfidence`
préserve les signaux natifs (confidence OSRM, distance_from_trace_point Valhalla, etc.) sans les
forcer dans un score commun inventé (§23/§26 de la requête : "ne jamais tout réduire à un score
unique").

---

## C. Protocole de capture de vérité terrain indépendante

**Ce protocole doit être prêt AVANT toute collecte physique par l'utilisateur — c'est l'objectif de
ce round (§32 de la requête). Aucune demande de conduite/marche n'est faite ici.**

### C.1 Application / logger — **[2B-1B] choix concret effectué (§7 de la correction, bloquant)**

2B-1 laissait ce choix ouvert ("options à évaluer par l'utilisateur"), ce que la correction 2B-1B
rejette explicitement comme bloquant pour toute capture physique. Choix retenu, sur la base de la
documentation publique/du code source du projet (pas seulement une page marketing), à valider
empiriquement par le protocole de mini-validation §C.1bis avant toute capture réelle :

**Application choisie : GPSLogger (par mendhak)**

**[2B-1C] Correction de source d'installation (Codex a signalé une source incorrecte en 2B-1B) :**
projet officiel **https://gpslogger.app/** — installer depuis **F-Droid**, ou depuis les sources de
publication officielles documentées par le projet GPSLogger lui-même. **Ne pas installer depuis le
Google Play Store** — toute mention précédente de Google Play dans ce document était une erreur,
corrigée ici.

- Source : open source, projet `gpslogger.app` (dépôt `mendhak/gpslogger`), publié sur F-Droid.
- Formats d'export disponibles : CSV, GPX, KML, GeoJSON (entre autres).
  **[2B-1C] Format canonique pour Phase 2B : le CSV.** Le GPX peut être généré en plus, pour
  convenance/visualisation uniquement — **il ne doit jamais être traité comme la source du
  benchmark pour la précision horizontale Android** (voir §C.1bis.2 pour pourquoi).
- Cadence configurable, y compris un intervalle minimal en secondes réglable manuellement — jamais
  imposé à "1 seconde" en dur comme règle produit générale (cohérent avec §3 de la requête d'origine)
  ; pour la mini-validation stationnaire spécifiquement, le réglage exact est 1 seconde (§C.1bis.1).
- Enregistrement en arrière-plan via un service de premier plan (notification persistante) —
  permet une capture écran éteint ; **non testé lors de cette première mini-validation** (§C.1bis.1
  garde l'écran allumé pour ce premier test).
- Aucune dépendance à un compte ou à un cloud pour l'export local (fichier local exporté par
  l'utilisateur) — cohérent avec l'architecture privacy-by-default du projet.
- C'est un enregistreur brut, pas une app de fitness/tracking social (type Strava/Google Fit) — pas
  de lissage/nettoyage automatique de trajectoire documenté qui dénaturerait les points bruts.
- Permissions attendues : localisation précise, localisation en arrière-plan (pour une capture
  écran éteint future, non requise pour cette première mini-validation), notifications (service de
  premier plan Android récent).

**Honnêteté de ce choix (correction 2B-1B §7 : "Do not rely only on marketing pages")** : ce choix
s'appuie sur la documentation et le code source publiquement disponibles du projet officiel
GPSLogger, **pas** sur une simple page de description dans un store d'applications — mais il n'a
**pas** encore été testé à la main sur le Samsung SM-G998U1 réel de l'utilisateur. C'est précisément
l'objet du protocole de mini-validation ci-dessous (§C.1bis), qui doit être exécuté et réussi
**avant** toute collecte de route réelle. **La précision horizontale par point est obligatoire, pas
optionnelle** (§C.1bis.4) : si la mini-validation révèle que le CSV exporté n'en contient pas
d'utilisable, **la validation du logger ÉCHOUE** et ce choix doit être révisé (§7 : "If no
third-party logger can reliably expose accuracy: consider a tiny dedicated benchmark-only logger
implementation, but do NOT build that unless necessary").

### C.1bis Protocole de mini-validation du logger (statique, avant toute route) — **[2B-1C, réglages et procédure rendus exacts et sans ambiguïté]**

**Obligatoire avant toute collecte physique.** Une validation stationnaire de 1 à 2 minutes,
effectuée par l'utilisateur avec son téléphone immobile en extérieur, avant tout déplacement réel.
**Un seul chemin non ambigu** — pas d'alternative GPX/CSV, pas de "précision si exposée".

#### C.1bis.1 Réglages GPSLogger exacts pour cette mini-validation

| Réglage | Valeur pour cette mini-validation |
|---|---|
| Format | **CSV activé** (obligatoire). GPX optionnel, jamais la preuve canonique du benchmark. |
| Intervalle d'enregistrement (logging interval) | **1 seconde** |
| Filtre de distance (distance filter) | **0 mètre** |
| Filtre de précision (accuracy filter) | **désactivé / 0** — ne pas laisser GPSLogger rejeter des mesures pendant ce diagnostic |
| Durée pour atteindre la précision (duration to match accuracy) | **0** |
| Choisir la meilleure précision sur la durée (choose best accuracy in duration) | **désactivé** |
| Source de localisation | **GPS/GNSS satellite : activé** ; réseau (network) : **désactivé** ; passive : **désactivé** |
| Garder le GPS actif entre les fixes (keep GPS on between fixes) | **activé** pour ce diagnostic court |
| Permission de localisation Android | précise (fine location) autorisée |
| Écran | **allumé pendant toute la durée** de la mini-validation (1-2 min) |
| Arrière-plan | **non testé** dans cette mini-validation — aucune exemption de batterie Samsung requise pour ce premier test |
| Upload/synchronisation | **aucun** requis — pas d'envoi automatique |

#### C.1bis.2 Pourquoi le CSV est le format canonique, jamais le GPX

Le CSV exporté par GPSLogger porte la précision horizontale Android par fix ; le format GPX de
GPSLogger ne porte pas nécessairement ce champ de la même façon exploitable pour ce benchmark (le
même écart déjà documenté pour GraphHopper en §F, où l'export GPX ne porte pas de champ de précision
par point). **Pour toute capture de référence Phase 2B, le CSV est donc la source unique et le GPX
n'est qu'une convenance optionnelle de visualisation, jamais une preuve de précision.**

#### C.1bis.3 World Discovery ne doit pas tourner pendant ce test

**World Discovery doit être fermée pendant toute cette première mini-validation.** Raison : ce test
vise à isoler le format exporté par GPSLogger et sa cadence d'échantillonnage effective, sans qu'un
autre consommateur de localisation sur le même appareil ne vienne perturber l'interprétation des
résultats.

#### C.1bis.4 Procédure exacte

1. Installer **GPSLogger by mendhak** depuis F-Droid ou une publication officielle du projet
   GPSLogger (**pas** Google Play — voir §C.1).
2. Appliquer exactement les réglages du tableau C.1bis.1.
3. S'assurer que **World Discovery est fermée** (§C.1bis.3).
4. Sortir à l'extérieur, sous un ciel raisonnablement dégagé.
5. Garder l'écran du téléphone **allumé**.
6. Attendre que GPSLogger ait obtenu une position GPS.
7. Démarrer l'enregistrement.
8. Poser le téléphone et **ne pas le déplacer** pendant 1 à 2 minutes.
9. Arrêter l'enregistrement.
10. Exporter/conserver le **CSV**.
11. Nommer le fichier approximativement : `logger-stationary-YYYYMMDD-HHMM.csv`.
12. Le placer dans `benchmark-private/logger-validation/` (répertoire déjà protégé par
    `.gitignore`, voir §L.1).
13. **Ne pas** encore effectuer de trajet à pied ou en voiture pour le benchmark — cette
    mini-validation reste strictement stationnaire.

#### C.1bis.5 Critères de validation (appliqués une fois le CSV fourni)

Pour **chaque** observation utilisable, **obligatoire** :
- timestamp ; latitude ; longitude ; **précision horizontale (accuracy)**.

À inspecter également, quand présent : vitesse, cap (bearing), altitude, nombre de satellites,
fournisseur (provider) — ainsi que l'espacement réel entre timestamps, les observations manquantes,
les timestamps répétés, les trous inattendus, et tout signe de rééchantillonnage caché, de filtrage
inattendu, ou de lissage.

**PASS** : le CSV contient les champs requis par observation et démontre une capture de référence
haute fréquence utilisable.

**FAIL** : précision absente/inutilisable, timestamps manquants, coordonnées manquantes, ou
comportement de capture/export incompatible avec le benchmark. **Une précision absente ou
inutilisable fait systématiquement échouer la validation — jamais acceptée silencieusement.**

**Ce round ne demande pas encore à l'utilisateur d'exécuter cette validation dans ce message** — le
protocole est désormais défini de façon exacte et non ambiguë ; son exécution reste la **prochaine
étape**. Voir la conclusion du rapport de ce round pour le statut exact.

### C.2 Métadonnées à capturer, au minimum

- cadence d'échantillonnage réelle obtenue (pas seulement celle demandée à l'app — mesurer les
  écarts réels entre points consécutifs) ;
- trous éventuels (l'app elle-même peut perdre des points) ;
- distribution de la précision (`accuracy`) rapportée par observation — **[2B-1C] obligatoire pour
  toute observation de référence utilisable, pas seulement "si l'app l'expose"** (voir §C.1bis.4/.5
  : une précision absente ou inutilisable fait échouer la validation du logger, elle n'est jamais
  acceptée silencieusement) ;
- modèle de téléphone/appareil et mode d'acquisition (foreground actif, écran allumé, app au
  premier plan) ;
- date, heure, conditions (dégagé / urbain / sous couvert forestier).

### C.3 Vérification manuelle du trajet réel — méthode et niveau de confiance requis

**Obligatoire, indépendamment du GPS lui-même** — le trajet réellement emprunté doit être confirmé
par une méthode qui ne dépend pas uniquement du même flux GPS, et catégorisé via
`VerificationMethod` (§B.1) :
- **Routes simples** : inspection visuelle sur une carte détaillée (`MAP_INSPECTION`) immédiatement
  après la capture pendant que le souvenir est frais, appuyée par une séquence de virages connue
  (`KNOWN_TURN_SEQUENCE`), avec la trace GPS dense comme preuve à l'appui (jamais comme seule
  preuve) — suffisant pour une confiance `TruthConfidence.HIGH` sur un trajet non ambigu.
- **Routes ambiguës** (routes parallèles, ponts, routes superposées, échangeurs, sentiers longeant
  une route) : une méthode plus forte est requise — vidéo/dashcam (`VIDEO_OR_DASHCAM`), second
  logger GPS indépendant (`SECOND_INDEPENDENT_LOGGER`), ou un itinéraire noté manuellement au fur
  et à mesure (`MANUAL_ROUTE_ANNOTATION`, ex. "A1 jusqu'à l'échangeur X, sortie 3, puis B12 vers le
  nord"). **Un second appareil n'est PAS requis pour toutes les routes** (§10 de la correction) —
  seulement pour les segments effectivement ambigus.
- Chaque segment difficile est enregistré comme un `VerifiedSegment` (§B.1) distinct, avec sa
  propre `verificationMethod`, sa propre `confidence`, et une `ambiguityNote` le cas échéant —
  jamais fusionné dans une seule vérification globale pour tout le trajet.
- **Seule une confiance `HIGH`** (trajet entier ou segment) doit être utilisée pour la métrique de
  sécurité H3-faux-positifs dans les premiers résultats exploitables (§B.1) ; `MEDIUM` peut être
  rapportée séparément à titre indicatif ; `UNUSABLE` est exclue de toute métrique de qualité.

### C.3bis Création de la géométrie vérifiée — **[2B-1B nouveau, §11 de la correction]**

Processus manuel de benchmark pour produire `VerifiedRouteGeometry.geometry` — **ne doit jamais
appeler un moteur candidat** :
1. Inspecter une carte/imagerie indépendante (OSM, imagerie satellite, ou équivalent) pour le
   trajet réellement emprunté.
2. Tracer manuellement la route/le chemin réellement suivi comme une polyligne (`LineString`),
   en s'appuyant sur la séquence de virages connue et la trace GPS dense comme guides, jamais comme
   substituts de l'inspection elle-même.
3. Stocker le résultat comme `VerifiedRouteGeometry.geometry`, associé à l'identifiant de scénario.
4. Pour les segments difficiles identifiés en C.3, stocker une géométrie vérifiée spécifique au
   segment via `VerifiedSegment.verifiedGeometryRef`, quand le tracé du segment diverge de la
   géométrie densité d'ensemble.

Aucune intégration d'éditeur graphique dédié n'est requise ce round (correction §11 : "Do not yet
require a fancy editor integration") — un outil SIG existant (ex. un éditeur OSM local, QGIS, ou
équivalent) suffit pour produire le GeoJSON `LineString` attendu par `VerifiedRouteGeometry`.

### C.4 Séparation stricte — **[2B-1B] mise à jour de terminologie**

Les observations GPS brutes haute fréquence (`ReferenceTrace.rawObservations`, §B.1) **ne doivent
jamais** être envoyées telles quelles à un moteur candidat — seules leurs **variantes dégradées**
(§D) le sont. **`ReferenceTrace.verifiedGeometry` (et non la trace GPS brute — correction explicite
du modèle de vérité collapsé de 2B-1, §2) reste le seul juge de la justesse géométrique et H3
(§G/§H)** : la trace GPS brute est une évidence utile pour construire la géométrie vérifiée, jamais
un substitut à celle-ci.

### C.5 Démarrage/arrêt et étiquetage

- Démarrer l'enregistrement de référence **avant** le départ, l'arrêter **après** l'arrivée, avec
  une marge de quelques secondes de chaque côté (facilite le recadrage).
- Étiqueter chaque trace avec : catégorie de corpus (ROAD/WALK/BIKE), sous-scénario (ex.
  "rural-unique", "rond-point"), mode de transport, date, et statut (§D.4).

### C.6 Confidentialité (§4 de la requête)

- **Avant tout ajout au dépôt Git**, effectuer une revue de confidentialité explicite : le trajet
  passe-t-il par un domicile/lieu de travail identifiable ? Si oui, ne pas l'ajouter tel quel — soit
  choisir un trajet "sûr" pour la fixture publique, soit recadrer/masquer les extrémités
  sensibles (ex. commencer/finir l'export GPX quelques centaines de mètres après le vrai
  départ/avant la vraie arrivée).
- Les données de calibration personnelles (toute trace réelle de l'utilisateur) restent **locales
  par défaut** — jamais commitées sans assainissement explicite et une décision consciente de le
  faire, distincte de ce round.
- Les scénarios publics futurs (ex. pour enrichir `docs/ai-context/map-matching-spike/`) devront
  utiliser des trajets choisis spécifiquement pour être sûrs à publier (zone publique, sans domicile
  ni lieu de travail reconnaissable), pas n'importe quelle capture réelle de l'utilisateur.

---

## D. Corpus — scénarios et règles de génération de dégradation

### D.1 Statuts de corpus (suivi, aucune capture réelle effectuée ce round)

`PLANNED` (défini, rien capturé) → `CAPTURED` (trace de référence obtenue) → `VALIDATED` (trajet
réel confirmé manuellement, §C.3) → `BENCHMARKED` (les trois moteurs exécutés dessus, métriques
calculées).

### D.2 Corpus ROAD (minimum, tous `PLANNED` pour l'instant)

| # | Scénario | Statut |
|---|---|---|
| A | route rurale unique | PLANNED |
| B | deux routes parallèles plausibles | PLANNED |
| C | grille urbaine dense | PLANNED |
| D | rond-point | PLANNED |
| E | échangeur autoroutier | PLANNED |
| F | pont | PLANNED |
| G | routes superposées / très rapprochées | PLANNED |
| H | tunnel / vraie perte GPS | PLANNED |
| I | sens unique / restriction de virage (si praticable) | PLANNED |
| J | autoroute à haute vitesse | PLANNED |

Pas besoin de capturer tous ces scénarios dès la première collecte physique (§5 de la requête) —
prioriser B (routes parallèles) et C (grille urbaine dense) en premier, ce sont les cas où la
littérature de map matching et Phase 2A suggèrent le plus grand risque de faux positif.

### D.3 Corpus WALK / BIKE

**WALK** : rues en ville ; parc ; chemin forestier ; sentier balisé ; sentier parallèle à une route ;
courte section non cartographiée si sûre à capturer ; **route rurale, aller-retour, même route**
(voir D.3bis — premier scénario réel, `CAPTURED`/`VALIDATED`).
**BIKE** : route normale ; piste cyclable ; voie cyclable physiquement séparée ; piste proche d'une
route. **Si aucune donnée vélo n'est disponible rapidement : garder PLANNED, ne jamais fabriquer.**

### D.3bis Premier scénario WALK réel — **[Phase 2B-2, BENCHMARK RESULT partiel]**

**Statut : `VALIDATED` ; `corridorBenchmarkStatus = BENCHMARKED` ; `exactGeometryBenchmarkStatus =
NOT_EVALUABLE` ; `h3BenchmarkStatus = NOT_EVALUABLE` (trois axes séparés — [2B-2C])** — identifiant privé `walk-rural-out-and-back-001`,
capturé le 2026-09-07 avec GPSLogger (protocole §C.1). Route rurale, aller-retour sur une seule
route mapée — **aucune coordonnée exacte, aucune capture d'écran, aucune donnée de localisation
identifiante ne figure dans ce document** ; tout ce qui précède reste sous `benchmark-private/`
(ignoré par Git, vérifié).

**[2B-2A] Correction de sémantique de vérité — deux affirmations désormais distinctes** (voir
`Models.kt`'s `VerifiedRouteGeometry`/`TruthGuard.kt`) :
- **Vérité de corridor** (quelle route a été utilisée) : `VALIDATED`, confiance `HIGH` — établie par
  la carte annotée par l'utilisateur (`VerificationMethod.MANUAL_ROUTE_ANNOTATION`), confirmant une
  seule route rurale sans alternative, aller et retour.
- **Vérité de géométrie exacte** (la polyligne elle-même est-elle une vérité au mètre près) :
  `PENDING` — **la trace GPS dense sert de géométrie mais n'est PAS traitée comme une vérité exacte
  automatiquement** du seul fait que le corridor est confirmé. Round 2B-2 avait initialement
  conflaté les deux (`overallConfidence = HIGH` appliqué à la géométrie entière) — corrigé ce round.
- **Cellules H3** : `PROVISIONAL` — échantillonnées depuis la géométrie d'évidence GPS, jamais
  utilisées comme `truthCells` dans un calcul de benchmark tant que la vérité de géométrie exacte
  n'est pas `VALIDATED` (garde-fou codé dans `TruthGuard.kt`/`BenchmarkEvaluation.kt`, testé).

**Conclusions agrégées, non identifiantes** (voir le rapport du round 2B-2/2B-2A pour le détail
complet) :
- 945 observations brutes ingérées sans anomalie de schéma (0 timestamp/coordonnée/précision
  manquant, 0 timestamp dupliqué ou non-monotone) — le harnais d'ingestion CSV GPSLogger
  (`GpsLoggerCsvAdapter.kt`) a validé le fichier réel sans réparation silencieuse.
- Le résumé fourni par l'utilisateur (nombre de points, durée, distance, position/heure du
  demi-tour, distance d'arrivée, plage/médiane de précision, histogramme de cadence, vitesse
  médiane) a été **recalculé indépendamment à partir du fichier brut et concorde sur tous les
  champs** — aucune valeur acceptée sans vérification.
- Jeux dégradés standards (dense/7s/15s/27s/45s) générés avec le générateur déterministe existant
  (`Degradation.kt`, aucune modification), chacun avec un `DegradationSpec` et un hash SHA-256
  enregistrés pour la reproductibilité.
- 56 cellules H3 "d'évidence provisoire" calculées pour information de corpus (résolution 12,
  `H3Comparison.kt` sans modification) — **explicitement pas des cellules de vérité**.
- **[2B-2B] OSRM/Valhalla/GraphHopper exécutés localement** ce round (2B-2A avait documenté le
  blocage Docker ; résolu en 2B-2B, Docker Desktop confirmé fonctionnel). Les trois moteurs ont
  tourné en local (Docker pour OSRM/Valhalla, CLI pour GraphHopper) contre le **même extrait OSM
  exact** (un extrait régional Geofabrik, ~98MB — plus petit que la région fusionnée actuelle,
  hash SHA-256 enregistré en privé), pour les 5 variantes de cadence (dense/7s/15s/27s/45s), en
  mode `DEFAULT` uniquement, sans réglage spécifique à la route.
- **[2B-2C] Correction d'un bug réel dans `ValhallaAdapter.kt`** (signalé par Codex) : `matchedGeometry`
  incluait auparavant toute entrée `matched_points[]` porteuse de coordonnées, **y compris celles de
  type `UNMATCHED`/`UNKNOWN`** — traitant silencieusement un échec de correspondance comme une
  vraie correspondance. Corrigé : `matchedGeometry` ne retient désormais que `MATCHED`/
  `INTERPOLATED` ; les entrées `UNMATCHED`/`UNKNOWN` restent pleinement représentées dans
  `perObservation`, jamais supprimées ni fabriquées comme matchées. Testé (`ValhallaAdapterTest.kt`,
  5 cas). Impact sur les nombres de points matchés : négligeable sur les distances de contrôle
  corridor (écart <0.2m par rapport à avant correction à chaque variante) — **aucune classification
  n'a changé**, reconfirmé indépendamment après correction, jamais supposé inchangé.
- **Sans vérité de géométrie exacte `VALIDATED`, les scores Hausdorff/Fréchet/overlap/H3-TP/FP/FN
  restent `NOT_EVALUABLE`** pour les 15 combinaisons moteur×variante — confirmé par le garde-fou
  (`TruthGuard`/`BenchmarkEvaluation`) sur de vraies sorties moteur (y compris après la correction
  Valhalla), pas seulement sur les tests synthétiques. Statut représenté par trois axes séparés
  (`corridorBenchmarkStatus = BENCHMARKED`, `exactGeometryBenchmarkStatus = NOT_EVALUABLE`,
  `h3BenchmarkStatus = NOT_EVALUABLE`) plutôt qu'un seul indicateur `BENCHMARKED` global.
- **Classification corridor (CORRECT/WRONG_ROAD/AMBIGUOUS), conclusion agrégée non identifiante** :
  pour ce scénario unique, Valhalla est resté sur le corridor connu à **toutes** les cadences
  testées, y compris 27s (reconfirmé après la correction de l'adaptateur, pas supposé). OSRM est
  resté correct à dense/7s/15s/45s mais a montré un écart marqué et une confiance native nulle
  spécifiquement à **27s**, où l'audit indépendant de Codex conclut à **`WRONG_ROAD`** (classification
  canonique, plus d'ambiguïté de formulation) — le résultat le plus pertinent pour la sécurité de ce
  round, cohérent avec la préoccupation produit qui motive ce scénario (cadence d'arrière-plan
  historique ~27s). GraphHopper (sortie CLI, ~6-7 segments seulement, pas une correspondance dense
  par observation — limitation déjà documentée en Phase 2A/2B, adaptateur bien réel depuis 2B-2B)
  est resté `AMBIGUOUS` à toutes les cadences, sans dépendance claire à la cadence. **Un seul
  scénario réel — ne
  pas généraliser** à d'autres véhicules, zones urbaines, sentiers, ou cadences (§12/§13 de la
  requête d'origine).

### D.3ter Deuxième scénario WALK réel — urbain, corridors parallèles — **[Phase 2B-3B, BENCHMARK RESULT]**

**Statut : `VALIDATED` ; `corridorBenchmarkStatus = BENCHMARKED` (les trois moteurs exécutés,
2B-3B) ; `exactGeometryBenchmarkStatus = NOT_EVALUABLE` ;
`h3BenchmarkStatus = NOT_EVALUABLE`** — identifiant privé `walk-urban-parallel-corridors-002`,
capturé le 2026-09-13. Marche urbaine avec plusieurs rues proches, corridors parallèles/quasi-
parallèles, plusieurs intersections, changements de direction — délibérément différent du scénario 1
(route rurale simple aller-retour), pour tester si un correspondeur préserve le corridor réellement
emprunté ou bascule vers une rue voisine non visitée quand les observations deviennent rares.

**Modèle de vérité — segmentation par virage algorithmique, pas out-and-back** : le scénario 1
utilisait une détection de demi-tour (point de distance maximale au départ), inapplicable ici (trajet
à sens unique avec virages réels). Un nouveau module générique (`TurnSegmentation.kt`, testé) détecte
les virages directement depuis les données GPS (changement de cap soutenu entre échantillons
rééchantillonnés à distance minimale, jamais depuis la capture d'écran) — 6 virages détectés, 7
segments. `corridorConfidence = HIGH` (corroboré par une capture d'écran Google Maps à 5 étapes
intermédiaires ajoutées manuellement — une preuve de choix de route arguably plus forte que le simple
aller-retour du scénario 1) ; `exactGeometryTruthStatus = PENDING` (même principe qu'au scénario 1 —
jamais dérivé automatiquement du GPS dense).

**Extrait OSM réutilisé** : le point de capture privé se trouve confortablement à l'intérieur du même
extrait régional que le scénario 1 (par prudence, la commune réelle n'est pas nommée ici — même
convention de confidentialité qu'au §D.3quater ; vérifié par hash avant réutilisation, pas
retéléchargé).

**[2B-3] Docker indisponible ce round-là** — deux tentatives bornées (~90s puis ~150s, ~4 minutes au
total) confirmaient des processus Docker Desktop actifs mais le pipe du moteur backend
(`dockerDesktopLinuxEngine`) n'apparaissait jamais. **[2B-3B] Docker de nouveau joignable** —
vérification bornée unique, succès immédiat. OSRM et Valhalla ont donc pu être exécutés en
réutilisant le graphe OSRM et les tuiles Valhalla déjà construits pour le scénario 1 (même PBF,
même profil `foot`/costing `pedestrian`) — aucune reconstruction nécessaire.

**Trois moteurs exécutés, résultats agrégés non identifiants** :
- **GraphHopper** (CLI, réutilisé du scénario 1) : `CORRECT` à toutes les cadences testées
  (dense/7s/15s/27s/45s) — écart moyen 2.8m, maximum 7.5m sur l'ensemble du trajet, aucun segment
  individuel (parmi les 7 détectés algorithmiquement) ne montre un écart localisé masqué. Nettement
  plus serré que le scénario 1 pour GraphHopper (`AMBIGUOUS`, 7-17m/50-159m) — différence réelle non
  expliquée avec certitude, à ne pas généraliser.
- **OSRM** : `CORRECT` à **toutes** les cadences y compris 45s (écart 2.4-2.8m, maximum constant à
  7.5m) — géométriquement stable même à cadence très éparse. Signal notable cependant : la confiance
  native chute fortement aux extrémités (très faible en dense malgré une géométrie correcte, et
  de nouveau très faible à 45s) — un signal d'ambiguïté réel présent bien avant tout échec
  géométrique effectif, pas une preuve d'échec en soi.
- **Valhalla** : `CORRECT` à dense/7s/15s, puis **`AMBIGUOUS`** à **27s et 45s** — un sous-ensemble
  contigu de segments courts (correspondant à une zone de rues courtes/intersections rapprochées,
  confirmé en privé par les noms d'arête bruts Valhalla) montre un écart nettement élevé
  spécifiquement à cadence éparse, alors que les segments plus longs restent serrés. **Jamais
  classé `WRONG_ROAD`** — la règle du round exige un corridor mappé réellement différent, pas
  seulement une distance latérale, et la vérité de géométrie exacte reste `PENDING`. Contraste net
  avec le scénario 1, où Valhalla était resté `CORRECT` à toutes les cadences sans exception.

**Réponse à la question centrale du scénario 2** : pour ce scénario précis, **aucun des trois
moteurs n'a produit de `WRONG_ROAD` confirmé** — mais Valhalla montre une dégradation localisée
réelle et mesurable à cadence éparse dans une zone de rues courtes, et OSRM montre une chute de
confiance native aux mêmes cadences sans dégradation géométrique correspondante. **Un seul
scénario réel — ne pas généraliser** à d'autres véhicules, zones urbaines, sentiers, ou cadences.

**Preuve de cadence adaptative (analyse, non implémentée)** : la dégradation localisée de Valhalla
coïncide avec des segments dont la durée dense (13-69s) est proche ou inférieure à l'intervalle de
cadence testé (27s/45s) — un système futur connaissant la densité d'intersections/virages d'un
tronçon pourrait en principe détecter ce risque avant de s'engager sur une interprétation éparse
(`POTENTIALLY_PREVENTABLE_BY_DENSER_CADENCE`). La chute de confiance native OSRM aux extrémités est
un signal directement observable par le moteur lui-même, disponible avant tout résultat, également
`POTENTIALLY_PREVENTABLE_BY_DENSER_CADENCE` au sens où un futur système pourrait s'en servir comme
déclencheur — bien qu'ici l'issue géométrique soit restée correcte (un « quasi-échec », pas un échec
réel). **Aucune cadence adaptative implémentée** — analyse uniquement.

### D.3quater Troisième scénario réel — véhicule, mixte rural/urbain, vrai trou d'observation — **[Phase 2B-4, BENCHMARK RESULT]**

**Statut : `VALIDATED` ; `corridorBenchmarkStatus = BENCHMARKED` ; `longGapBenchmarkStatus =
BENCHMARKED` ; `exactGeometryBenchmarkStatus = NOT_EVALUABLE` ; `h3BenchmarkStatus =
NOT_EVALUABLE`** — identifiant privé "scénario 3" (le nom de dossier privé exact contient un nom de
commune réel et n'est donc, par prudence, jamais reproduit littéralement dans ce document suivi —
voir la note de confidentialité en tête de §D.3quater), capturé le 2026-09-16.
Premier scénario **véhicule** (mode CAR, pas marche) — trajet mixte route rurale longue puis réseau
urbain dense, aller simple (départ ≠ arrivée, pas de boucle). 1049 observations, ~24 min, ~13.0km,
précision 3.8-14.3m — recoupement indépendant confirmant les estimations préliminaires sur tous les
champs.

**Généralisation du harnais (2B-4)** : le harnais ne gérait jusqu'ici que le mode WALK (profils
piéton/`foot` codés en dur dans le générateur de requêtes et l'analyseur). Corrigé génériquement
(`EngineProfiles.kt`, nouvelle table de correspondance unique `TransportMode` → profil OSRM/costing
Valhalla/profil GraphHopper), pas seulement pour ce scénario — tout futur scénario CAR ou BIKE en
bénéficie automatiquement. Un module générique de détection de trou temporel
(`TurnSegmentation.detectLargeTimeGapIndices`, testé) force désormais une frontière de segment à
tout écart d'observation réel (>60s), indépendamment de la détection de virage géométrique.

**Investigation du trou de ~389s — résultat contraire à l'hypothèse initiale** : le déplacement réel
à travers ce trou n'est que d'environ 3 mètres, et la vitesse rapportée par l'appareil est nulle des
deux côtés — preuve forte que le véhicule était **à l'arrêt** (stationné) pendant ces ~6,5 minutes
non enregistrées, plutôt qu'un cas de perte de signal GPS en roulant à vitesse élevée comme
l'hypothèse initiale du round le supposait. Classifié `REAL_OBSERVATION_GAP` avec cette cause
probable documentée honnêtement plutôt que forcée pour correspondre à l'hypothèse de départ.

**Test A (sections normales)** — tableau final :

| Cadence | OSRM | Valhalla | GraphHopper |
|---|---|---|---|
| dense | CORRECT | CORRECT | CORRECT |
| 7s | CORRECT | CORRECT | CORRECT |
| 15s | CORRECT | CORRECT | CORRECT |
| 27s | AMBIGUOUS | CORRECT | AMBIGUOUS |
| 45s | AMBIGUOUS | CORRECT | AMBIGUOUS |

Valhalla reste **`CORRECT` à toutes les cadences**, y compris 45s — cohérent avec sa robustesse déjà
observée aux scénarios 1 et 2. OSRM et GraphHopper montrent un écart identique (même valeur exacte)
à 27s/45s, le plus probablement dû au point d'arrivée réel du trajet se trouvant hors du réseau
routier cartographié (allée privée non cartographiée comme voie distincte) — un comportement moteur
attendu en fin de trajet, pas une erreur de route en milieu de parcours. La confiance native OSRM
s'effondre spécifiquement à 45s (0.19, la plus basse du round), un signal d'alerte réel malgré une
géométrie encore correcte.

**Test B (sécurité du grand trou)** : OSRM a **explicitement scindé** la trace exactement au trou
(deux correspondances séparées, confiance élevée des deux côtés) — le comportement le plus sûr
possible, aucune connexion fabriquée. Valhalla a interpolé sur le même segment routier des deux
côtés, distance minime — reflète correctement l'quasi-immobilité réelle. Aucun des deux moteurs n'a
forcé une reconstruction non supportée. **Limite importante à noter** : le déplacement réel étant
minime, ce round n'a pas pu observer le comportement des moteurs face à un vrai trou à grand
déplacement — question qui reste ouverte pour un futur scénario.

**Comparaison inter-scénarios (niveau corridor uniquement)** : Valhalla reste le seul moteur
`CORRECT` sans exception sur les trois scénarios réels à ce jour (marche rurale, marche urbaine,
véhicule mixte). OSRM montre une sensibilité récurrente aux cadences éparses (27s/45s) dans des
contextes différents à chaque fois (mauvaise route au scénario 1, confiance en baisse ici).
GraphHopper reste le moteur le plus dépendant du contexte (`AMBIGUOUS` constant au scénario 1,
`CORRECT` constant au scénario 2, mixte ici) — cohérent avec la sortie CLI structurellement plus
grossière déjà documentée. **Aucun moteur sélectionné — trois scénarios réels ne suffisent pas pour
trancher.**

### D.3quinquies Étude de trou d'observation contrôlé — sécurité / faux-positifs — **[Phase 2B-5, BENCHMARK RESULT]**

**Objet** : déterminer comment OSRM, Valhalla et GraphHopper se comportent quand des observations
GPS disparaissent pendant une fenêtre contrôlée et reproductible, en conservant la trace dense
originale du scénario 3 (véhicule) comme évidence privée de ce qui s'est réellement passé pendant
l'intervalle masqué. **Ni le scénario 3 ni ses conclusions n'ont été modifiés** — cette étude
réutilise uniquement sa trace dense privée, en lecture seule.

**Méthodologie** : 14 fenêtres contrôlées gelées **avant** toute exécution de moteur, choisies
uniquement à partir de signaux réels de la trace dense (vitesse, changement de cap, indices de
virage déjà calculés) — jamais en observant d'abord un échec de moteur. Quatre contextes couverts :
route simple, embranchement/virage unique, réseau urbain dense, transition rural→urbain ; durées
30/60/120/180s selon ce que chaque contexte pouvait raisonnablement supporter (180s délibérément
omis pour les contextes urbains/transition trop courts pour l'accueillir sans les faire se
chevaucher). Un module générique réutilisable (`ControlledGap.kt`, testé — 7 cas couvrant absence
réelle des observations masquées, conservation séparée de la fenêtre de référence, non-mutation de
la source, génération déterministe, rejet des fenêtres stationnaires, rejet des plages invalides)
distingue explicitement ce test de la dégradation de cadence habituelle : une seule fenêtre locale
est masquée, le reste de la trace dense reste intact — pas un rééchantillonnage global.

**Découverte méthodologique clé** : un premier diagnostic de distance brute suggérait qu'OSRM et
Valhalla s'éloignaient fortement (dizaines à centaines de mètres) des points réels masqués, de façon
quasi identique entre les deux moteurs — un signal qui aurait pu être mal interprété comme
`WRONG_ROAD`. Une investigation plus poussée (signaux natifs, jamais de coordonnées) a montré qu'il
ne s'agissait **d'aucun des deux** : OSRM **scinde explicitement** son résultat à chaque trou
contrôlé testé (confirmé structurellement), et l'attribut Valhalla demandé (`matched_points`
seulement) ne fournit tout simplement aucune géométrie intermédiaire entre les deux points limites,
que la reconstruction interne soit correcte ou non — confirmé par la continuité des identifiants
d'arête (`edge_index`) de part et d'autre du trou, toujours proche (1 à 17 arêtes d'écart), cohérente
avec un trajet continu sur des segments voisins plutôt qu'un saut ailleurs sur le réseau. **Aucun des
deux moteurs n'a donc forcé une connexion incorrecte** — la distance brute mesurait en réalité
« aucune reconstruction proposée », pas « mauvaise route choisie ». Rappel direct de la règle du
round : ne jamais dériver `WRONG_ROAD` de la seule distance métrique.

**Résultat central** : sur les 42 exécutions (14 trous × 3 moteurs), **aucun cas `WRONG_ROAD` ni
`FORCED_UNSUPPORTED` confirmé.** OSRM scinde systématiquement (comportement le plus prudent possible,
indépendant de la durée 30-180s et du contexte). Valhalla reste `SUPPORTED_CORRIDOR` sur route
simple et embranchement même à 180s, mais devient `AMBIGUOUS` dans le réseau urbain dense et en
transition à cadence courte (signal réel : type d'observation limite passant de « matched » à
« interpolated » dans ces contextes) — la durée seule n'explique pas la difficulté, le contexte si.
GraphHopper reste proche de la trace réelle partout, mais sa sortie CLI s'est révélée quasiment
insensible à la fenêtre retirée d'un cas à l'autre — limitant sa valeur comme test différencié pour
cette étude spécifique, une limite honnêtement documentée plutôt que dissimulée.

**Preuves pour une future passerelle de sécurité de reconstruction (conception uniquement, non
implémentée)** : le mécanisme de scission d'OSRM est le signal le moins coûteux et le plus fiable
observé ; le typage par observation de Valhalla corrèle avec la difficulté du contexte ; l'ampleur de
l'écart d'`edge_index` est un indicateur de complexité réseau plausible mais non validé contre un
vrai cas erroné (aucun n'est survenu) ; un seuil de durée unique n'est **pas** soutenu par ces
données (le contexte prime sur la durée pour Valhalla, aucun effet de durée pour OSRM/GraphHopper).
**Aucun seuil de production assigné.**

### D.4 Cas négatifs/adversariaux (générés à partir des traces de référence PROPRES, jamais en
modifiant la vérité terrain elle-même)

Point isolé aberrant ; aberrants consécutifs ; observation périmée (stale) ; grand trou ; trou de
type "livraison différée" (simulant un `PendingIntent` retardé) ; précision dégradée ; bearing
manquant ; mauvais mode/profil (ex. soumettre une trace piétonne avec `profile=car`) ; arrêt
prolongé ; dérive GPS ; observations manquantes ; chemin non cartographié. Chaque variante
adversariale est dérivée par un générateur déterministe (même principe que
`docs/ai-context/map-matching-spike/osrm/commands.md`'s downsampling logic en Phase 2A), jamais à
la main, pour rester reproductible.

### D.5 Règles de génération des variantes de cadence standard (§9 de la requête)

**[2B-1B] Correction de documentation (§6) :** 2B-1 affirmait ce générateur "implémenté dans
`tools/mapmatch-benchmark/src/degradation/`" — **ce chemin/répertoire n'a jamais existé et le code
n'était en réalité pas implémenté ce round-là** (seule la règle était décrite en prose). 2B-1B
corrige cela en implémentant réellement le générateur : `tools/mapmatch-benchmark/src/Degradation.kt`
(fichier plat, pas de sous-répertoire `degradation/`), testé dans `DegradationTest.kt` (voir §K du
rapport de correction pour la preuve d'exécution).

1. Prendre la `ReferenceTrace.rawObservations` (haute fréquence).
2. Pour une variante à intervalle cible T (7s/15s/27s/45s), référencée par un `DegradationSpec`
   (§B.2) : parcourir la trace par temps écoulé réel (pas par index), en sélectionnant, pour chaque
   instant `t0 + kT`, la première observation non encore sélectionnée à cet instant ou après
   (`DegradationSelectionPolicy.FIRST_AT_OR_AFTER_TARGET`, seule règle implémentée ce round).
   **Ne jamais** synthétiser un point qui n'existe pas dans la référence — la dégradation
   ne fait que sous-échantillonner une trace réelle, jamais interpoler une fausse observation.
   Le dernier point de la trace de référence est toujours inclus dans la sortie dégradée, même
   s'il ne tombe pas exactement sur une frontière `t0 + kT` (politique de point final, testée).
3. Pour les scénarios routiers, générer des variantes supplémentaires à vitesse représentative
   (~50 km/h, ~90 km/h) **en sous-échantillonnant une vraie trace de référence capturée à cette
   vitesse réelle** — jamais en gardant une géométrie de marche et en changeant seulement les
   timestamps pour simuler une vitesse impossible (§9 de la requête, règle explicite). Ceci reste
   **ENGINEERING DESIGN, non exécuté** — nécessite un vrai corpus routier capturé à ces vitesses.
4. Les variantes adversariales (§D.4) sont des transformations supplémentaires appliquées après
   l'étape 2, sur une copie — jamais sur la `ReferenceTrace` elle-même. **Non implémentées ce
   round** (`DegradationSpec.outlierRule`/`accuracyPerturbationMeters`/`coordinateNoiseMeters`
   existent comme champs de spécification mais aucune logique de génération ne les consomme encore
   — correction §16 : "do not implement unless needed now").

---

## E. Politique de normalisation (équité de configuration)

Phase 2A a établi que les configurations par défaut ne sont **pas** comparables (recherche
`search_radius` OSRM vs. Valhalla, gestion de trou `gaps` vs. `breakage_distance`, etc. — voir
`docs/ai-context/MAP_MATCHING_ENGINE_STUDY.md` §17.5). Ce round ne force **pas** des valeurs
numériques identiques quand la sémantique diffère — il établit une **intention commune** par
paramètre, puis le réglage propre à chaque moteur :

| Intention commune | OSRM | Valhalla | GraphHopper |
|---|---|---|---|
| Erreur GPS déclarée / rayon de recherche par point | `radiuses` (mètres, par point, dans l'URL) | `shape[].accuracy` (mètres, par point, JSON) | **Aucun équivalent par point** — un seul `--gps_accuracy` global (CLI) ; REST JSON non vérifié cet round (voir §F) |
| Gestion de trou temporel/spatial | `gaps=split`\|`ignore` (seuil temporel documenté ~60s, non déclenché à 90s en Phase 2A) | `breakage_distance` (mètres, défaut 2000m observé) | Non déterminé empiriquement ce round — non exposé par la CLI `match --help` |
| Profil véhicule/mode | `profile` du fichier `.lua` (ex. `car.lua`) | `costing` (`auto`/`pedestrian`/`bicycle`) | `--profile` (`car`/`foot`/`bike`, selon `config.yml`) |
| Usage des timestamps | requis, monotones croissants | requis (`shape[].time`) | requis (GPX `<time>`) |
| Usage du bearing | paramètre `bearings` disponible, non utilisé en Phase 2A/2B | non identifié dans la doc consultée | non identifié dans la doc consultée |
| Interpolation | non identifié précisément | `interpolation_distance` (défaut 10m observé) | non déterminé empiriquement ce round |
| Découpage de trace (split) | `matchings[]` (plusieurs sous-traces possibles) | non observé dans nos essais (toujours un seul résultat matché) | non déterminé (sortie CLI GPX ne distingue pas de split explicite) |

**DEUX modes de benchmark seront conservés, comme demandé (§11 de la requête) :**
- **DEFAULT** : chaque moteur avec ses valeurs par défaut telles quelles (déjà fait en Phase 2A
  pour OSRM/Valhalla, et en Phase 2B pour GraphHopper avec `--gps_accuracy` omis).
- **NORMALIZED** : chaque moteur réglé pour exprimer la même *intention* (ex. rayon de recherche
  ≈25m pour un point "normal", un seuil de trou cohérent en distance ET en temps) — **valeurs
  exactes non fixées ici, CALIBRATION REQUIRED** une fois le corpus réel disponible pour valider
  empiriquement que l'intention est bien équivalente et pas juste numériquement pareille.

---

## F. Statut du runner GraphHopper (exécuté ce round)

**Résumé factuel — détail complet dans `docs/ai-context/map-matching-spike/graphhopper/commands.md`.**

- Moteur : GraphHopper 11.0, jar officiel `graphhopper-web-11.0.jar` (Maven Central, épinglé par
  URL exacte), pas une image Docker tierce non officielle.
- Exécuté avec succès via le mode CLI `match` (import + matching, 36/36 points GPS lus,
  deux variantes conservées : `--gps_accuracy` par défaut=40 → 204 points de sortie, et =25 → 205
  points de sortie ; déterministe, revérifié).
- **Limitation d'environnement documentée honnêtement** : le mode serveur web (`server
  config.yml`), seul chemin vers l'endpoint REST `/match?type=json` plus riche, échoue dans cet
  environnement avec exactement la même signature d'erreur `Unable to establish loopback
  connection` que l'échec Gradle déjà documenté tout au long de cette session — un bug JVM/Windows
  connu (sandboxing du process enfant empêchant la création d'un pipe de réveil de sélecteur NIO),
  pas spécifique à GraphHopper, non contournable par un flag JVM (un essai avec
  `-Djdk.nio.channels.spi.SelectorProvider=...` n'a pas aidé). **Un seul essai de contournement a
  été tenté, conformément à la convention déjà établie dans ce projet — pas de boucle.**
- Conséquence : seule la sortie GPX du CLI a pu être inspectée, pas le JSON REST plus détaillé.
  Cette sortie GPX **ne préserve pas une correspondance 1:1 propre avec les 36 observations
  d'entrée** (les timestamps de certains points d'entrée réapparaissent décalés/réassignés le long
  du chemin rééchantillonné) — un adaptateur `perObservation` fiable n'a **pas** pu en être
  construit ce round. `EngineMatchResult.perObservation` reste `null` pour GraphHopper dans le
  harnais actuel — honnêtement absent, pas deviné.
- **Pas de classement impliqué par cette limitation** (§10/§27 de la requête) — c'est une
  contrainte d'environnement d'exécution locale, pas une propriété du moteur lui-même ; un futur
  environnement sans cette restriction (ou un accès réseau à un GraphHopper hébergé ailleurs)
  pourrait obtenir le JSON REST et un adaptateur `perObservation` complet.

---

## G. Métriques de géométrie (implémentées, `tools/mapmatch-benchmark/src/GeometryMetrics.kt`)

**[2B-1B] Correction de chemin (§6)** : le fichier réel est `tools/mapmatch-benchmark/src/
GeometryMetrics.kt` (fichier plat), pas `.../metrics/GeometryMetrics.kt` — corrigé ici.

**[2B-1B] Corrections de fond (§13 de la correction)**, chacune identifiée et corrigée ce round :

- **Clamp du haversine** : `h` (l'argument de `asin(sqrt(h))`) est désormais borné à `[0,1]` avant
  `sqrt`/`asin` — l'arrondi flottant pouvait auparavant le pousser légèrement au-dessus de 1.0 pour
  des points quasi-antipodaux ou quasi-identiques, produisant un `NaN`.
- **Antiméridien** : la projection planaire locale utilisée pour la distance point-segment
  calculait la longitude absolue directement, ce qui produisait une distance d'environ 40 000km
  pour deux points distants de quelques mètres de part et d'autre de l'antiméridien (ex. lon
  +179.9999 vs -179.9999). Corrigé en calculant un delta de longitude "enroulé" par rapport à un
  point de référence du segment (`wrapLonDeltaDegrees`). Une même correction s'applique à
  l'interpolation le long d'un segment (`interpolateLon`), réutilisée par la densification (voir
  ci-dessous) et par l'échantillonnage H3 (§H). Testé explicitement (`GeometryMetricsTest.
  testAntimeridianCrossing`).
- **Recouvrement de route pondéré par la longueur, pas par le nombre de sommets** : la version 2B-1
  comptait directement les sommets d'entrée de la trace de référence — invalide, car elle
  sur/sous-pondérait un trajet selon la densité de vertex de sa polyligne *d'entrée* (ex. la
  géométrie dense OSRM ~700 points vs. les 36 points par observation de Valhalla pour la même route
  réelle), rendant le pourcentage de recouvrement incomparable entre moteurs. Corrigé par
  densification de la référence à un espacement fixe (`densify`, `ceil`-based, jamais `floor`/
  `toInt`) avant de compter la couverture — le nombre de points échantillonnés couverts approxime
  alors la *longueur* couverte, indépendamment de la densité native de la polyligne d'entrée. Testé
  explicitement avec un cas construit pour faire diverger le résultat pondéré-vertex de l'ancien
  code et le résultat pondéré-longueur du nouveau (`testRouteOverlapIsLengthWeightedNotVertexWeighted`).
- **Densification symétrique avant Hausdorff/Fréchet** : les deux géométries comparées sont
  désormais densifiées au **même** espacement avant le calcul (`hausdorffMetersDensified`/
  `discreteFrechetMetersDensified`) — corrige exactement le biais que le smoke test de 2B-1
  documentait déjà lui-même (Valhalla 36 points bruts vs. OSRM ~722 points bruts comparés
  directement). Effet mesuré : la distance de Fréchet de Valhalla dans le smoke test est passée de
  990.5m (biaisée par la densité) à 341.4m (équitable en densité, quasi identique à OSRM) — voir
  §M.
- **Distance de Fréchet recalculée en itératif, pas récursif** : la formulation récursive
  mémoïsée classique provoquait un `StackOverflowError` réel, observé en exécutant ce test de fumée
  après densification (profondeur de récursion `O(n+m)`, plusieurs milliers pour une route
  densifiée à quelques mètres d'espacement). Corrigée en programmation dynamique itérative (deux
  lignes glissantes), sans limite de profondeur liée à la taille d'entrée. **Ce bug n'a été trouvé
  qu'en exécutant réellement le code après densification** — un exemple concret de pourquoi ce
  round exige des preuves d'exécution, pas seulement une conception.

Métriques inchangées dans leur principe (toujours rapportées ensemble, §15 de la requête —
**aucune métrique seule ne suffit**) :
- **Distance de Hausdorff** (discrète) : la pire distance point-à-plus-proche-point dans les deux
  sens — sensible aux excursions ponctuelles et (avant densification) à la densité des entrées.
- **Distance de Fréchet** (discrète) : tient compte de l'ordre le long du trajet, pas seulement de
  la proximité spatiale — `O(nm)`, plus fidèle à "suit-on le même chemin dans le même ordre".
- **Déviation along-track moyenne** : pour chaque point de la géométrie matchée, distance
  perpendiculaire au segment de référence le plus proche, moyennée.
- **Pourcentage de segment sur la mauvaise route** (`wrongRoadSegmentPercent`, optionnel — dépend
  d'une annotation manuelle indiquant quels segments de la vérité terrain ont une route parallèle
  plausible, non disponible avant le corpus réel ; voir aussi la classification structurée
  `WrongRoadClassification` en §I).

## H. Métriques H3 (implémentées, `tools/mapmatch-benchmark/src/H3Comparison.kt`)

**[2B-1B] Correction de chemin (§6)** : fichier réel `tools/mapmatch-benchmark/src/H3Comparison.kt`
(fichier plat), pas `.../metrics/H3Comparison.kt`.

1. Échantillonner densément la **géométrie vérifiée** (`ReferenceTrace.verifiedGeometry.geometry` —
   **jamais la trace GPS brute**, correction §14 explicite reprenant le modèle de vérité de §2) le
   long de la polyligne → convertir chaque point échantillonné en cellule H3 (résolution 12, voir
   §H.1 ci-dessous) → ensemble `truthCells`.
2. Appliquer **la même politique d'échantillonnage** à la géométrie matchée → `matchedCells`.
3. `TP = truthCells ∩ matchedCells` ; `FP = matchedCells − truthCells` ; `FN = truthCells −
   matchedCells`.
4. `precision = |TP| / |matchedCells|` ; `recall = |TP| / |truthCells|`.

**[2B-1B] Correction du défaut de sous-échantillonnage (§14 de la correction)** : le calcul du
nombre de pas d'interpolation par segment utilisait `(longueur / pas).toInt()` — une troncature
(= arrondi **inférieur**), pas un arrondi **supérieur**. Un segment de 29m avec un pas de 15m
produisait ainsi `29/15 = 1.93 -> 1` seul pas, donc un seul point échantillonné en plus du départ
(seulement l'extrémité), dépassant silencieusement l'espacement demandé et risquant de sauter une
cellule H3 réellement traversée. Corrigé avec `ceil(longueur / pas)` — le même segment produit
désormais 2 pas (point médian + extrémité), bornant l'espacement réel à `<= 15m`. Un test de
régression explicite compare l'échantillonnage "extrémités seulement" (pas artificiellement large)
à l'échantillonnage avec le point médian sur le même segment fixe, et vérifie que le second est un
sur-ensemble du premier (`H3ComparisonTest.testCeilNotFloorAvoidsUndersamplingRegression`).

**[2B-1B] Espacement d'échantillonnage par défaut abaissé de 15m à 3m, avec justification** : la
résolution H3 12 a une longueur d'arête moyenne publiée d'environ 9.42m (table officielle H3) — un
pas de 15m restait du même ordre de grandeur qu'une cellule entière, donc pas défendable comme
"suffisamment dense". Un pas de 3m est confortablement en dessous (facteur ~3) de la taille d'une
cellule, ce qui rend peu probable qu'un segment quasi rectiligne traverse une cellule entière sans
jamais y échantillonner un point — **ceci reste un choix d'ingénierie documenté, pas une garantie
topologique formelle** (l'API de traversée de grille H3 native, `gridPathCells`, ne relie que des
centres de cellules et n'est pas définie pour une géométrie continue arbitraire, donc pas
directement applicable ici ; l'interpolation dense a été jugée l'option la plus directement
applicable parmi celles pesées par la correction §14). Testé sur segment droit, diagonal, virage,
proche d'une frontière de cellule, segment long (plusieurs cellules), et traversée d'antiméridien
(`H3ComparisonTest.kt`).

**Métrique de sécurité primaire : `|FP|` (nombre absolu de cellules faux positif)** — jamais
seulement le taux/precision, puisqu'un petit pourcentage de faux positifs sur un long trajet peut
encore représenter un territoire réel et non négligeable marqué à tort comme découvert (§16 de la
requête, §14 de la demande Phase 2A — le critère le plus grave reste inchangé). `precision`/
`recall` sont fournis en complément, jamais en remplacement du compte brut.

### H.1 Résolution H3

**DECIDED pour ce round** (correction §15 : "do not redesign production resolution in this
phase") : résolution 12, identique à `DiscoveryEngineVersion.CANONICAL_H3_RESOLUTION` en
production — pas une nouvelle calibration inventée ici, une réutilisation délibérée pour que les
résultats du benchmark restent directement interprétables comme "territoire réellement conflictuel
côté produit". Les métriques de géométrie (§G) restent, elles, indépendantes de toute résolution.

## I. Métriques d'acceptation/rejet (conçues, énumération implémentée dans `AcceptanceClassification`)

### I.1 Classification "mauvaise route" — **[2B-1B nouveau, §12 de la correction]**

**Règle opérationnelle (DECIDED pour ce round)** : un résultat de moteur est `WRONG_ROAD` quand sa
géométrie matchée suit un corridor/chemin vérifié **différent** de celui réellement emprunté, même
si la distance géographique est faible — route parallèle, chaussée adjacente sémantiquement fausse,
mauvais pont, mauvaise route superposée, mauvaise bretelle d'échangeur, route au lieu d'un sentier
parallèle. Énumération `WrongRoadClassification { CORRECT, WRONG_ROAD, AMBIGUOUS, NOT_EVALUABLE }`
implémentée dans `Models.kt` et référencée par `BenchmarkResult.wrongRoadClassification` (§B.4).

**Jamais dérivée uniquement de la distance de Hausdorff/Fréchet** (correction §12, explicite) —
classer un résultat nécessite de comparer le corridor matché au `VerifiedSegment.corridorId` de la
vérité terrain, ce qui nécessite un vrai corpus vérifié. **Aucun classificateur automatique n'est
implémenté ce round** — seul le type est prêt ; la classification réelle nécessitera soit un
jugement manuel soit une future correspondance corridor-matché ↔ corridor-vérifié une fois le
corpus réel disponible (cohérent avec "ne pas surconcevoir" tant qu'aucune donnée réelle n'existe
pour tester une telle logique).

- **Reconstruction correcte acceptée** — le bon résultat.
- **Reconstruction fausse acceptée** — **le pire résultat possible** (§17 de la requête), doit
  être quasi nul.
- **Reconstruction correcte rejetée** — coût d'opportunité (le moteur aurait pu, mais a refusé) ;
  tolérable, jamais aussi grave que l'inverse.
- **Mauvaise reconstruction rejetée** — le comportement de sécurité souhaité face à l'ambiguïté.
- **Ambiguïté détectée** — quand le moteur expose un signal natif d'ambiguïté (alternatives OSRM,
  etc.) et que la situation était effectivement ambiguë.
- **Split** / **point non matché correct ou incorrect** — la trace a-t-elle été coupée/exclue à bon
  escient ?

Classer chaque résultat dans une seule de ces catégories nécessite la vérité terrain (§C) — non
calculable sur la fixture auto-référentielle Phase 2A/2B, seulement conçu et implémenté comme
énumération/structure ce round.

## J. Plan d'analyse sparse-27s

**ENGINEERING DESIGN, non exécuté** (nécessite le corpus réel). Pour chaque scénario capturé,
comparer les quatre variantes de cadence (7s/15s/27s/45s+) sur : justesse de la route complète,
choix d'une mauvaise alternative, comportement de split, taux de rejet, FP/FN H3, et effet de la
vitesse (§18 de la requête). Priorité explicite au scénario "routes parallèles" (§19) et au scénario
"grille urbaine dense avec plusieurs intersections" (§20), en comparant précisément si 27s devient
ambigu alors que 7s/15s résout correctement.

## K. Plan de preuve pour la cadence adaptative future

**ENGINEERING DESIGN, non exécuté.** Utiliser les courbes de dégradation du plan §J pour répondre :
à quel espacement d'observation l'ambiguïté/les faux positifs augmentent-ils fortement ? Exemple
conceptuel attendu (pas un résultat, une hypothèse à vérifier) : route parallèle urbaine correcte à
7s, majoritairement correcte à 15s, ambiguïté/mauvaise route fréquente à 27s. Cette preuve, une fois
réelle, pourra justifier une future `ObservationCadenceRecommendation.INCREASED`/`HIGH` — **aucune
cadence n'est implémentée ici** (§25 de la requête, explicitement interdit ce round).

## L. Contrôles de confidentialité (mis en œuvre dans le protocole, §C.6 pour le détail)

Revue de confidentialité obligatoire avant tout ajout au dépôt d'une trace réelle ; recadrage/
masquage des extrémités sensibles ; séparation entre données de calibration locales (jamais
commitées par défaut) et fixtures publiques (choisies spécifiquement sûres). Rien de tout cela
n'a été appliqué à une vraie trace ce round, puisqu'aucune vraie trace n'a encore été capturée.

### L.1 Stockage privé des traces brutes — **[2B-1B nouveau, §9 de la correction]**

2B-1 ne protégeait **pas réellement** les artefacts privés existants à la racine du dépôt
(`tracking-calibration.ndjson`, `trip1.txt`, `trip2.txt`, `trip3.txt`, `vehicle1.txt`,
`map-doc-diff.txt`, `review-context.txt`, `review.ps1`) — ils étaient non suivis par Git (jamais
ajoutés), mais **pas non plus ignorés par `.gitignore`**, donc protégés uniquement par la discipline
de ne jamais faire `git add -A`, pas par une garantie structurelle. Corrigé en 2B-1B : `.gitignore`
protège désormais explicitement ces huit fichiers par leur nom exact à la racine, **sans les
déplacer** (conforme à la correction : "do not move existing private artifacts unless necessary"),
et ajoute un répertoire `/benchmark-private/` ignoré comme emplacement recommandé pour toute future
vraie capture de route. Vérifié avec `git check-ignore -v` sur les huit fichiers et sur le nouveau
répertoire — tous confirmés ignorés (voir §Q du rapport de correction).

---

## Correspondance future avec `TrajectoryReconstructor` (analyse, pas une modification)

**ENGINEERING DESIGN, explicitement différée** (§24 de la requête : "seulement après preuve de
benchmark"). Le contrat Phase 1 (`AcceptedTrajectory`/`Ambiguous`/`InsufficientEvidence`/
`NoReconstruction`/`UnsupportedMode`, `ReconstructionConfidence`, `ReconstructionReason`,
`ObservationCadenceRecommendation`) n'est **pas modifié** par ce document. La table de compatibilité
DIRECT/DERIVABLE/MISSING déjà établie en Phase 2A (`MAP_MATCHING_ENGINE_STUDY.md` §15) reste
valide ; ce round n'ajoute qu'une clarification structurelle déjà actée : `EngineMatchResult`
(§B.3) est un type de **benchmark**, délibérément distinct du contrat de production — aucune
fusion tant qu'un vrai résultat de benchmark n'a pas révélé un blocage réel du contrat existant
(aucun blocage identifié à ce stade, cohérent avec la conclusion Codex de Phase 2A : "NO BLOCKING
CONTRACT GAP").

## Règle de sélection de moteur (rappel, non appliquée — aucun résultat encore disponible)

Rappel de la règle déjà actée (§27 de la requête), non réévaluée ici faute de résultats : ne
jamais choisir sur la seule moyenne de succès. Ordre de priorité pour une future recommandation :
(1) minimiser les faux positifs dangereux, (2) reconstruction correcte en usage routier sparse
normal, (3) signaux d'ambiguïté/échec utiles, (4) support marche/vélo, (5) faisabilité
opérationnelle, (6) architecture privacy/offline, (7) performance/coût.

---

## M. Test de fumée exécuté (PAS un résultat de benchmark) + tests unitaires **[2B-1B]**

Pour prouver que le pipeline §A fonctionne réellement (charger → adapter → normaliser → comparer →
H3 → métriques), le harnais a été exécuté contre les fixtures Phase 2A/2B déjà existantes :
`docs/ai-context/map-matching-spike/{osrm,valhalla}/response.json` comme sorties moteur, et
`docs/ai-context/map-matching-spike/shared/synthetic-self-referential-route.json` comme
pseudo-référence — **explicitement labellisé "SMOKE TEST ONLY — NOT A BENCHMARK RESULT"** dans la
sortie du harnais lui-même, pas seulement dans ce document. Voir `tools/mapmatch-benchmark/README.md`
pour les commandes exactes exécutées et `tools/mapmatch-benchmark/smoke-test-output.log` pour la
sortie brute obtenue.

**[2B-1B] Résultat réel de la ré-exécution après les corrections de métriques** (compilation propre
avec le plugin `kotlinx-serialization`, exécution réussie, chiffres réels produits par le code —
toujours PAS un résultat de benchmark) :

```
engine     points       hausdorff(m)     frechet(m)  alongTrack(m)   overlap%    H3-TP    H3-FP    H3-FN
osrm       722                 341.4          341.4            0.8       96.1     1242       22       64
valhalla   36                  341.4          341.4            3.3       65.1      532      668      774
perObservation entries -- osrm: 36, valhalla: 36
osrm null (unmatched) count: 2
valhalla unmatched count: 0
```

**Ces chiffres ne sont PAS comparables point-pour-point à ceux de 2B-1** (recouvrement, Fréchet et
comptes H3 ont tous changé à cause des corrections de métriques elles-mêmes — pondération par
longueur, densification symétrique, pas H3 corrigé) — seul le recoupement d'unmatched-count ci-
dessous reste, comme attendu, identique.

**Recoupement utile (inchangé)** : le harnais retrouve, par un chemin de code entièrement
indépendant, exactement le même constat que l'audit manuel Phase 2A (OSRM 2/36 non matchés,
Valhalla 0/36) — un signe que les adaptateurs analysent correctement les réponses brutes réelles,
pas une coïncidence.

**Effet mesuré de la correction de densification** (§G) : la distance de Fréchet de Valhalla est
passée de **990.5m (2B-1, biaisée par le décalage de densité 36 pts vs. 722 pts) à 341.4m (2B-1B,
équitable en densité)** — désormais quasi identique à celle d'OSRM (341.4m), ce qui est le résultat
attendu pour deux adaptateurs décrivant la même route auto-référentielle réelle. C'est une preuve
concrète, observée en ré-exécutant réellement le harnais, que la correction visait le bon défaut.

**Mise en garde résiduelle, non résolue par cette correction** : la ligne Valhalla n'utilise
toujours que les 36 `matched_points` par observation comme "géométrie matchée native" (la requête
Phase 2A n'avait pas demandé l'attribut `shape`, plus dense, de Valhalla) — la densification
symétrique corrige le biais de *comparaison*, pas le fait que Valhalla pourrait exposer une
géométrie native plus riche si on la lui demandait. Un vrai run de benchmark devrait demander une
densité de géométrie native comparable aux deux moteurs quand c'est possible, en plus de densifier
pour la comparaison.

### M.1 Tests unitaires exécutés — **[2B-1B nouveau]**

Contrairement à 2B-1 (qui affirmait à tort des tests "unitaires" en §A.3 sans qu'aucun fichier de
test n'existe), 2B-1B ajoute et exécute réellement trois suites de tests ciblés, en style
`main()`/`check()` (pas JUnit — voir `tools/mapmatch-benchmark/README.md` pour pourquoi) :

- `GeometryMetricsTest.kt` : ligne identique, ligne parallèle, densités de vertex différentes,
  segment court, traversée d'antiméridien, point unique dégénéré, recouvrement pondéré-longueur vs.
  pondéré-vertex, clamp haversine près de l'antipode. **Résultat exécuté : `ALL PASS`, exit 0.**
- `H3ComparisonTest.kt` : segment droit, segment diagonal, virage (couverture cumulative des deux
  branches), proximité de frontière de cellule, segment long vs. court (croissance du nombre de
  cellules), traversée d'antiméridien, régression directe du bug `ceil` vs. `floor`. **Résultat
  exécuté : `ALL PASS`, exit 0.**
- `DegradationTest.kt` : intervalles source irréguliers, arrêt (timestamps quasi identiques), trou
  plus grand que la cadence (jamais comblé), absence de doublon garantie, politique de point final,
  aucune synthèse de point. **Résultat exécuté : `ALL PASS`, exit 0.**

Un défaut réel (`StackOverflowError` sur la formulation récursive du calcul de Fréchet, déclenché en
exécutant le smoke test avec des géométries densifiées de taille réaliste) a été détecté et corrigé
au cours de cette même session de vérification (voir §G) — la preuve que ces tests ont été
réellement exécutés, pas seulement rédigés.

---

## Questions ouvertes (OPEN QUESTION)

**[2B-1B] Résolues ce round** (retirées de la liste, décidées ou répondues ci-dessus) :
- ~~Quelle résolution H3 utiliser~~ → DECIDED pour ce round : résolution 12 (§H.1).
- ~~Faut-il un second logger indépendant pour toutes les routes~~ → répondu : non, seulement pour
  les segments effectivement ambigus (§C.3).
- ~~Quel logger utiliser~~ → choix concret fait : GPSLogger (mendhak) (§C.1), sous réserve de la
  mini-validation §C.1bis.

**Toujours ouvertes** :
- Quels scénarios routiers prioriser en premier pour la toute première capture physique (§D.2
  suggère B et C, à confirmer avec l'utilisateur).
- Le mode CLI GraphHopper restant le seul chemin d'exécution disponible dans cet environnement,
  faut-il envisager un environnement d'exécution alternatif (ex. une machine non sandboxée) pour
  obtenir la sortie REST JSON plus riche avant le premier vrai benchmark ?
- Les valeurs numériques exactes du mode NORMALIZED (§E) restent `CALIBRATION REQUIRED` — non
  déterminables avant un corpus réel.
- Le choix du logger (GPSLogger/mendhak, §C.1) est un choix raisonné mais non testé à la main sur
  le Samsung SM-G998U1 réel — la mini-validation §C.1bis doit être exécutée par l'utilisateur avant
  de considérer ce choix confirmé.
