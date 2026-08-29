# World Discovery — Moteur de découverte

> Document de référence vivant. **Une hypothèse ne doit jamais être transformée en règle définitive simplement pour pouvoir avancer.** Toute implémentation définitive d'une valeur marquée **[Hypothèse à tester]** ou **[OUVERT]** doit d'abord faire l'objet d'une mise à jour de ce document, avec justification et résultats de tests, puis d'une validation explicite avant d'être codée en dur.
>
> Chaque principe ci-dessous porte une étiquette de statut :
> - **[Validé]** — décision produit actée, à respecter telle quelle par toute implémentation.
> - **[Hypothèse à tester]** — comportement attendu, mais dont les paramètres exacts doivent être vérifiés par des scénarios de test reproductibles avant d'être figés.
> - **[OUVERT — à calibrer]** — aucune valeur numérique, formule, résolution ou seuil n'est choisie. Ne pas deviner, ne pas approximer « pour avancer ». Une implémentation temporaire/expérimentale reste possible (voir CLAUDE.md règle 5) mais doit être clairement isolée et jamais présentée comme définitive.
>
> Ce document remplace l'ancienne organisation en catégories A/B/C séparées par une organisation thématique — le contenu et la discipline de séparation sont préservés, seule la présentation change pour rester lisible à mesure que le nombre de règles augmente.

## Pipeline conceptuel

```
Observations/échantillons de localisation
  → analyse du déplacement (classification sol / transport rapide / aérien — voir §1)
  → présence
  → cellules géographiques H3 (représentation canonique — structure et résolution exactes ouvertes, voir §8)
  → découverte (Normal et/ou Certified)
  → agrégation multi-niveaux de précision (Easy / Standard / Precision — voir §8)
  → agrégation géographique (zone → région → pays → monde)
  → pondération / pourcentage (par niveau de précision, sur territoire éligible — voir §9 et §10)
```

H3 reste invisible pour l'utilisateur à chaque étape. Le pipeline ne suppose jamais qu'un signal reçu est automatiquement authentique — cette distinction est particulièrement importante pour Certified, où seule une validation côté serveur fait foi (voir [certified-mode.md](certified-mode.md)).

**[Validé]** La vitesse/altitude est un signal de **classification du déplacement** à l'étape « analyse du déplacement » — jamais un multiplicateur de valeur de découverte appliqué à l'étape « pourcentage ». Voir §1.

---

## 0. Philosophie fondamentale

**[Validé]** World Discovery mesure la **découverte géographique**, pas la qualité ni la profondeur de l'expérience touristique. La question posée est : *« L'utilisateur a-t-il physiquement traversé/exploré cette zone géographique ? »*, pas *« Qu'y a-t-il fait ? »* ni *« Combien de temps y a-t-il passé à visiter ? »*.

**[Validé]** Marche, course, vélo, moto, voiture, camping-car, bus et train sont tous des moyens valides de découvrir un territoire. La découverte n'est **jamais pénalisée** simplement parce que le déplacement était plus rapide ou motorisé.

> Exemple : rouler en voiture le long des Champs-Élysées découvre le corridor géographique parcouru, exactement comme le ferait la marche. Marcher expose l'utilisateur à davantage de rues adjacentes du seul fait de l'itinéraire réellement emprunté à pied — mais le corridor parcouru en voiture n'est **pas** intrinsèquement dévalué parce qu'il a été parcouru en voiture.

**[Validé]** La vitesse reste utilisable pour :
- la classification du mouvement (immobile / marche / vélo / véhicule / train / bateau / avion) ;
- le filtrage de la qualité GPS ;
- l'optimisation batterie ;
- la détection de vols aériens (voir §2) ;
- la détection d'anomalies / déplacements physiquement impossibles ;
- le futur anti-triche Certified.

**[Validé]** La vitesse seule ne détermine **jamais** la valeur de découverte d'une zone traversée.

**[Validé]** Le moteur ne dépend **pas uniquement du réseau routier** : une découverte à pied en forêt, montagne, plage, sentier ou tout autre territoire accessible doit être reconnue.

**[Validé]** Le système privilégie la **preuve de présence** dans une zone plutôt que le tracé exact emprunté.

---

## 1. Transport et vitesse

Voir §0 pour le principe général. Ce qui reste ouvert concerne uniquement les **paramètres** de classification, pas le principe lui-même.

**[Hypothèse à tester]** Seuils de vitesse/altitude/durée permettant de distinguer immobile / marche / vélo / voiture / train / bateau / avion.

**[OUVERT — à calibrer]** Comportement précis attendu pour voiture, train, bateau au-delà du principe général (sol = éligible à la découverte du corridor réellement parcouru, sans survaloriser ni pénaliser selon le mode de transport).

---

## 2. Vol aérien

**[Validé]** Voler ne découvre **pas** le sol survolé. Un vol Paris → Bangkok ne doit pas découvrir les pays traversés par l'avion. La découverte reprend uniquement à partir d'une présence réelle au sol, après atterrissage.

**[Validé]** Les zones aéroportuaires légalement accessibles au voyageur peuvent compter normalement selon les règles générales de découverte et d'éligibilité (§10) — un aéroport n'est pas exclu par principe, seul le survol l'est.

**[Hypothèse à tester]** Seuils exacts (vitesse, altitude, durée) déclenchant la classification « vol » plutôt que « sol/véhicule rapide ». Voir §1.

---

## 3. Passage, corridors et trajets

**[Validé]** Passer par un lieu compte comme y avoir été physiquement présent. Un road trip à travers un village/une ville/un pays peut donc :
- marquer cette entité géographique comme visitée/présente (voir §4) ;
- découvrir le corridor effectivement parcouru ;
- contribuer à son pourcentage d'exploration.

**[Validé]** Un simple passage n'implique **jamais** une complétion à 100 %. La contribution dépend de la géographie réellement explorée et de la densité d'exploration environnante attendue — jamais uniquement du mode de transport.

> Exemple : traverser la rue principale d'un petit village peut représenter une part significative de ce village. Traverser quelques rues de Paris ne représente qu'une infime part de Paris. C'est un effet de la densité/diversité géographique attendue (§5), pas une règle spécifique au « passage ».

**[Validé]** Le train suit le même principe : le corridor sol réellement parcouru peut compter, mais ne découvre jamais automatiquement de vastes zones environnantes.

**[OUVERT — à calibrer]** Traitement fin des corridors ferroviaires si une distinction supplémentaire s'avère nécessaire au-delà du principe général ci-dessus (ex. tunnels, viaducs, gares traversées sans arrêt).

---

## 4. Visité vs Exploré

**[Validé]** Ces deux notions restent **séparées** et ne doivent jamais être confondues dans l'affichage ou le calcul :
- **Visité** : présence physique authentifiée dans l'entité géographique (pays/région/ville…), même minime.
- **Exploré** : pourcentage réel du territoire éligible couvert par la découverte.

> Exemple : l'utilisateur visite Barcelone → l'Espagne peut apparaître comme pays visité sur la carte mondiale. Ouvrir l'Espagne peut révéler qu'un pourcentage réduit du pays a réellement été exploré.

**[Validé]** La navigation reste hiérarchique : Monde → Continent → Pays → Région → Zone → Lieu. La carte au niveau mondial doit donner une vision satisfaisante des pays où l'utilisateur est allé ; les niveaux plus profonds révèlent le pourcentage réel d'exploration. Un pays « visité » **ne doit jamais visuellement laisser croire** qu'il est exploré à 100 %.

**[OUVERT — à calibrer]** Critère exact déclenchant le statut « visité » pour une entité géographique (durée minimale de présence, nombre de cellules canoniques distinctes touchées, seuil de confiance...).

---

## 5. Densité d'exploration adaptative

**[Validé]** Le mode Standard n'utilise **pas** une exigence de couverture géographique uniforme partout. Deux surfaces physiques égales peuvent nécessiter des quantités d'exploration très différentes.

**[Validé]** La densité d'exploration requise doit augmenter avec la densité et la diversité des éléments géographiques significatifs et légalement accessibles. Signaux potentiels (liste non exhaustive, aucun n'est pondéré ni choisi comme définitif) :
- densité du réseau de routes/rues/chemins ;
- bâti / urbanisation ;
- réseau piéton accessible ;
- points ou lieux d'intérêt ;
- environnements géographiques distincts ;
- diversité d'usage des sols ;
- accessibilité ;
- autres signaux géographiques pertinents à identifier.

**[Validé]** Population ou popularité touristique peuvent être des signaux **auxiliaires**, jamais la règle unique.

**[Validé]** La campagne n'est **jamais** classée globalement comme « faible valeur » ou « vide ». Une zone rurale contenant un village, un château, un lac, un parc, des sentiers, etc. peut exiger significativement plus d'exploration qu'une terre agricole homogène de même taille.

**[OUVERT — à calibrer]** Formule exacte de densité adaptative (quels signaux, quelle pondération relative, quelle fonction de combinaison).

---

## 6. Généralisation en territoire homogène

**[Validé]** Les grands territoires homogènes doivent être généralisés plus agressivement en mode Standard. Exemples : terres agricoles répétitives, grandes forêts homogènes, désert, environnements polaires/glaciaires, autres paysages à très faible diversité.

**[Validé]** L'utilisateur ne doit pas avoir besoin de conduire des routes parallèles à travers des champs identiques, de marcher chaque hectare d'une forêt, ni de créer des motifs de grille artificiels dans un désert pour compléter le mode Standard.

**[Validé]** Si une grande forêt ne compte que quelques chemins publics légitimes, ces chemins accessibles peuvent représenter une part bien plus large de la forêt en mode Standard.

**[Validé]** L'application ne doit **jamais** encourager à quitter les chemins légitimes ni à pénétrer un terrain dangereux/inaccessible pour « remplir » la carte.

**[OUVERT — à calibrer]** Rayon maximal de généralisation. **Aucune valeur n'est approuvée** — des exemples conceptuels évoqués en discussion (ex. un rayon de 30 km dans le Sahara, ou 500 km en zone polaire) sont **purement illustratifs et ne constituent en aucun cas des constantes validées**. Le modèle final devra définir des plafonds raisonnables et calibrés pour empêcher qu'une quantité absurde de territoire soit débloquée depuis un seul point.

---

## 7. Territoire urbain

**[Validé]** Les environnements urbains denses exigent une exploration plus fine que les zones homogènes. Le mode Standard doit néanmoins rester **réalistement complétable** : 100 % Standard dans une ville ne doit **pas** exiger de visiter chaque ruelle, impasse, petite rue latérale ou chaque mètre carré. L'objectif est une exploration géographique représentative et significative de la ville.

**[Validé]** Le mode Hard/Precision peut exiger une couverture nettement plus fine et rendre significatives les rues secondaires, chemins et petits secteurs.

**[OUVERT — à calibrer]** Seuil exact séparant « exploration représentative suffisante » (Standard) de couverture fine exhaustive (Hard/Precision) en tissu urbain.

---

## 8. Niveaux Easy / Standard / Precision et représentation canonique

**[Validé]** Les trois niveaux sont conservés : Easy, Standard, Hard/Precision. **Standard est l'expérience de référence** : une visite/exploration normale mais significative et représentative d'un territoire. Easy est plus permissif mais ne doit pas devenir trivial. Hard/Precision vise une complétion profonde et une exploration beaucoup plus fine.

**[Validé]** Ces trois niveaux ne sont **pas** implémentés comme trois historiques de tracking indépendants. Une seule représentation canonique, suffisamment fine, est conservée ; les niveaux en sont dérivés par agrégation/interprétation.

**[OUVERT — à calibrer]** Résolution H3 exacte de la représentation canonique. Les résolutions H3 10/11/12 ont été discutées ; H3 12 a été envisagée comme candidate possible pour la représentation fine canonique, mais **n'est pas approuvée** comme constante technique finale.

**[OUVERT — à calibrer]** Règles exactes d'agrégation reliant la représentation canonique à chacun des niveaux Easy/Standard/Precision (relation H3 parent/enfant stricte vs règle de couverture propre à Precision, tolérance spatiale en Precision/Hard).

*(Rappel déjà validé ailleurs, préservé ici : une zone découverte en Easy n'implique pas la découverte de ses sous-zones en Standard/Precision ; changer de niveau après un usage long ne fait jamais perdre l'historique ; le pourcentage est calculable séparément par niveau.)*

---

## 9. Pondération de l'exploration vs surface brute

**[Validé — principe]** Le pourcentage d'exploration n'est **pas** supposé égal à « surface découverte en km² / surface totale en km² » de façon brute et uniforme. Un modèle d'exploration **pondéré** est à l'étude : une zone urbaine dense, diverse et accessible peut porter davantage de poids d'exploration par km² physique qu'une vaste zone homogène ; une zone homogène (forêt/agricole/désert) peut nécessiter une couverture représentative bien moindre par km².

Le pourcentage doit représenter **combien de l'exploration significative, accessible et représentative du territoire a été complétée** — pas une simple proportion de surface brute.

**Comportement souhaité** (sans formule figée) :
- deux territoires de même surface mais de densité/diversité différentes ne doivent pas exiger le même effort d'exploration pour atteindre 100 % Standard ;
- ce poids doit être cohérent avec §5 (densité adaptative) et §6 (généralisation homogène) — ce sont vraisemblablement deux facettes d'un même mécanisme de pondération, pas deux règles indépendantes ;
- la pondération ne doit jamais rendre un territoire homogène *plus* difficile à compléter qu'un territoire dense.

**Entrées/signaux possibles** (aucun choisi, aucune pondération fixée) : les mêmes signaux que §5, plus éventuellement une densité de cellules canoniques distinctes réellement découvertes par rapport à la densité attendue localement.

**Invariants à préserver quel que soit le modèle retenu** :
- le résultat doit rester recalculable/versionné (voir §16), jamais un compteur muté directement ;
- le modèle ne doit jamais rendre 100 % Standard irréaliste à atteindre (voir §13) ;
- le modèle ne doit jamais récompenser la vitesse/le mode de transport en tant que tel (voir §0) ;
- le modèle ne doit jamais dépendre d'un signal d'éligibilité non versionné (voir §10).

**Risques identifiés à garder en tête pour la conception du modèle** :
- sur-complexité rendant le pourcentage imprévisible ou peu explicable à l'utilisateur (contredit le principe « simple à comprendre » du produit) ;
- effets de bord si les signaux de densité (§5) sont eux-mêmes de mauvaise qualité ou absents dans certaines régions du monde ;
- risque d'incitation perverse si le poids favorise trop fortement un type de terrain (ex. pousser à multiplier les zones urbaines pour progresser plus vite, au détriment de l'esprit « découverte »).

**[OUVERT — à calibrer]** Formule/poids exacts. Ce modèle n'est **pas** mathématiquement finalisé ; aucune formule ne doit être implémentée en dur avant validation explicite de ce document avec la formule proposée.

---

## 10. Accessibilité et éligibilité

**[Validé]** Un score de 100 % signifie « 100 % du territoire **éligible** selon la version du référentiel utilisée » — jamais chaque mètre carré physique. C'est cette définition qui rend l'invariant de complétion (§13) compatible avec l'exclusion des zones fermées ci-dessous.

**[Validé]** L'existence géographique d'une zone est séparée de son éligibilité à la découverte. Règle centrale : si le public peut légalement et normalement accéder à une zone, elle est potentiellement éligible à la découverte. Si une zone est fermée au public, elle ne doit **jamais** être requise pour 100 %.

**[Validé]** Exemples de zones généralement à exclure de la complétion requise : zones militaires fermées, installations gouvernementales/de sécurité fermées, zones à accès définitivement restreint, zones opérationnelles aéroportuaires inaccessibles aux passagers/au public, autres zones où l'accès public est légalement interdit. Les portions publiquement accessibles restent, elles, éligibles.

**[Validé]** Le danger seul **ne rend jamais** un territoire inéligible. Ne sont **jamais** automatiquement exclus : quartiers dangereux, favelas, pays à risque sécuritaire, ou toute autre zone légalement accessible simplement parce qu'elle peut être dangereuse — des personnes y vivent, des utilisateurs peuvent légitimement s'y rendre.

**[Validé]** Cependant, World Discovery ne doit **jamais** récompenser ni encourager un comportement dangereux. **Aucun bonus d'exploration** n'est accordé au seul motif qu'une zone est dangereuse. Une éventuelle information de sécurité, si elle est implémentée plus tard, doit rester strictement séparée du calcul de découverte/score.

**[Validé]** Les états d'éligibilité versionnés sont conservés : `ELIGIBLE`, `RESTRICTED_EXCLUDED`, `UNKNOWN`. `UNKNOWN` ne doit **jamais** être silencieusement traité comme inclus ou exclu sans politique de dénominateur explicite (voir liste des questions ouvertes en fin de document). Les cellules `RESTRICTED_EXCLUDED` ne sont jamais nécessaires pour atteindre 100 % et n'entrent jamais dans le dénominateur.

**[Validé]** La présence de Google Street View ou d'une couverture automobile similaire **n'est pas une preuve d'accessibilité légale** ; elle peut au mieux être un signal auxiliaire parmi d'autres, jamais la source de vérité de l'éligibilité.

**[Validé]** World Discovery n'est **jamais présenté comme une autorisation légale d'accès** à un lieu : l'utilisateur reste seul responsable du respect des lois, propriétés privées, fermetures et signalisation locales.

**[OUVERT — à calibrer]** Source(s) de données d'éligibilité, gouvernance de mise à jour, définition exacte des terres éligibles à la surface mondiale, traitement des frontières/territoires contestés, procédure de gestion des changements futurs d'éligibilité — voir la section finale.

---

## 11. Océans, îles et territoires isolés

**[Validé]** L'océan ouvert ne fait pas partie du pourcentage d'exploration terrestre. Un trajet en bateau peut être stocké/affiché comme un voyage, mais traverser l'océan ouvert n'augmente pas la complétion terrestre mondiale.

**[Validé]** Les îles accessibles restent une géographie explorable valide. Une île n'a pas besoin d'être un pays indépendant pour être explorable (exemples cités : Tahiti, La Réunion, la Corse et territoires/îles similaires — restent géographiquement explorables lorsqu'elles sont légitimement accessibles).

**[Validé]** Chaque île n'est pas automatiquement comptée comme un « pays visité » distinct — la classification pays/territoire nécessite un référentiel géographique versionné défini plus tard (voir §16 et architecture.md §8).

**[Validé]** Les rochers/îlots minuscules et inaccessibles, ou les territoires que le public ne peut pas légitimement atteindre, ne doivent pas rendre 100 % impossible — ils relèvent d'un statut d'éligibilité approprié (voir §10), pas d'une exclusion géographique ad hoc.

**[Validé]** Le caractère « inhabité » seul n'est **jamais** un critère d'exclusion : une île inhabitée mais légalement visitable peut rester une exploration significative.

**[OUVERT — à calibrer]** Référentiel géographique versionné pour la classification pays/territoire/île ; méthode exacte de détermination de l'accessibilité légitime d'une île/d'un rocher isolé pour l'éligibilité.

---

## 12. Zones polaires et désertiques

**[Validé]** L'Antarctique et autres environnements extrêmes légitimes ne sont **pas** automatiquement exclus au seul motif qu'ils sont difficiles d'accès. Le mode Standard doit au contraire être capable d'une généralisation représentative très forte dans les vastes environnements homogènes (voir §6).

**[Validé]** Le pôle Nord nécessite un traitement géographique spécifique car il ne s'agit pas de terre émergée au sens de l'Antarctique — la banquise/glace de mer ne doit **pas** être traitée avec légèreté comme une surface terrestre éligible permanente.

**[OUVERT — à calibrer]** Traitement exact, référentiel géographique et plafonds de généralisation pour les zones polaires et désertiques — voir aussi §6 et §10.

---

## 13. Invariant de complétion à 100 %

**[Validé]** 100 % Standard doit être théoriquement et réalistement atteignable **sans** :
- intrusion sur une propriété privée ou zone légalement interdite ;
- marche selon des grilles arbitraires en territoire sauvage homogène ;
- visite de chaque ruelle/rue ;
- prise de risque physique déraisonnable dans le seul but de progresser dans l'application.

**[Validé]** 100 % Hard/Precision peut être considérablement plus exigeant, mais doit toujours respecter l'accessibilité légale.

Cet invariant contraint directement §5, §6, §7 et §9 : aucun modèle de densité/généralisation/pondération ne peut être validé s'il rend 100 % Standard irréaliste au sens ci-dessus.

---

## 14. Points / XP (futur, hors calcul d'exploration)

**[Validé — cadrage futur, non-MVP]** Un système de points/XP futur ne doit **pas** être mélangé au pourcentage d'exploration actuel. Le pourcentage d'exploration représente la découverte géographique ; un système séparé pourra récompenser des jalons : nouveaux pays, nouvelles régions, jalons de complétion, diversité géographique, succès/achievements.

**[Validé]** Aucun point supplémentaire n'est **jamais** accordé pour la seule entrée dans une zone dangereuse (cohérent avec §10).

Ceci relève de l'extensibilité future uniquement — pas de l'implémentation MVP actuelle.

---

## 15. Souvenirs personnels de lieux (futur, non-MVP)

**[Validé — cadrage futur, non-MVP]** Les utilisateurs pourront éventuellement associer des informations de voyage personnelles à des lieux : notes, photos, descriptions personnelles, recommandations, lieux à revisiter, souvenirs de voyage. Objectif : aider l'utilisateur à se souvenir de ses voyages, à en planifier de futurs, et éventuellement faire (re)surface des lieux intéressants qu'il n'aurait pas visités autrement.

**[Validé]** Ceci reste strictement séparé du calcul de découverte central pour l'instant — ni le pourcentage d'exploration, ni le score Certified, ne dépendent de ce contenu.

*Note de cohérence documentaire : ce concept recoupe la mention déjà existante des « souvenirs éventuels » dans [product-spec.md](product-spec.md) §2 et de son exclusion du MVP en §8 — cette section en précise le contenu produit, sans contredire product-spec.md.*

---

## 16. Données, versionnement et recalcul

Principes déjà actés (préservés, rappelés ici avec les précisions issues de ce document) :

**[Validé]** Une cellule ne peut être comptée qu'une seule fois **au sein d'un même ensemble** (`mode` : normal ou certified) ; les deux ensembles sont indépendants.

**[Validé]** L'historique de découverte est monotone (une découverte historique ne disparaît jamais silencieusement).

**[Validé]** Toute donnée dérivée persistée porte un `engine_version` ; le référentiel d'éligibilité et les règles d'agrégation de précision sont versionnés indépendamment.

**[Validé]** Le score/pourcentage de progression est toujours **recalculable** à partir des données de découverte canoniques — jamais un compteur muté directement.

**[Validé]** Un changement futur de règle (densité adaptative §5, généralisation §6, pondération §9, référentiel d'éligibilité §10, référentiel géographique §11) ne doit jamais détruire silencieusement l'historique. Si un futur algorithme change la contribution d'une zone déjà découverte au pourcentage actuel, ce changement doit être **explicite et versionné** — jamais silencieux.

**[Validé]** Pas de conservation indéfinie de GPS brut : rétention courte, puis conservation de données dérivées minimales uniquement.

---

## 17. Compatibilité avec le mode Certified

**[Validé]** Aucun principe Certified existant n'est affaibli par ce document. Rappels (détails complets dans [certified-mode.md](certified-mode.md)) :
- la validation serveur Certified reste l'unique autorité ;
- toute classification produite côté client — y compris par ce moteur de découverte — reste **non fiable** tant qu'elle n'est pas validée côté serveur ;
- la découverte/le score Certified doivent rester recalculables à partir des événements/cellules validés ;
- l'historique Normal ne devient **jamais** automatiquement Certified.

L'algorithme Certified complet (machine à états, signaux d'intégrité, pondération anti-triche) n'est **pas** conçu dans ce document — voir [certified-mode.md](certified-mode.md) pour son périmètre actuel et ses points ouverts.

---

## 18. Communauté / discussions géolocalisées (futur, non-MVP)

**[Validé — cadrage futur, non-MVP]** World Discovery pourra éventuellement inclure une couche communautaire de discussions localisées, dans l'esprit léger d'un réseau social géolocalisé — **pas** un fil social générique. L'objectif est d'aider les utilisateurs à échanger des informations, expériences, questions et recommandations sur des destinations géographiques.

**[Validé]** Les publications/discussions pourraient être associées à des entités géographiques : pays, région, ville, zone, lieu précis.

> Exemples d'usage : « Je pars à Bangkok, dans quel quartier loger ? », recommandations d'hébergement, que visiter, que éviter, informations pratiques locales, conseils de transport, questions sur une destination, expériences personnelles, recommandations, avertissements, partage de photos ou de découvertes utiles.

**[Validé — cadrage futur]** Fonctionnalités futures envisageables : publications texte, photos, questions/réponses, commentaires/réponses, vote utile/like, profils publics, fils par destination, recherche par destination, catégories telles que Question / Conseil / Expérience / Recommandation / Avertissement. En consultant un pays/région/ville/lieu, l'utilisateur pourrait accéder aux discussions liées à cette entité géographique.

**[Validé]** Distinction stricte à conserver : les souvenirs/notes/photos **personnels** (§15) appartiennent à l'historique de voyage privé de l'utilisateur sauf partage explicite ; les discussions **communautaires** sont intentionnellement publiées pour les autres utilisateurs. Ce sont deux systèmes distincts.

**[Validé]** Cette fonctionnalité reste indépendante du pourcentage de découverte géographique et du score Certified.

**[Validé]** Non implémenté dans le MVP.

**[Validé]** Avant toute implémentation sociale/communautaire réelle, le produit doit définir : signalement, blocage, modération, prévention spam/abus, contrôles de confidentialité, comportement profil public/privé, confidentialité de localisation, règles de contenu, comportement de suppression/édition, considérations mineurs/sécurité.

**[Validé]** Ne jamais exposer la position actuelle ou précise d'un utilisateur du seul fait qu'il participe à une discussion à propos d'un lieu.

Pour l'instant, seul le concept produit et l'extensibilité architecturale sont documentés — aucune implémentation du système communautaire/social n'est entreprise à ce stade.

*Note de cohérence documentaire : ce concept est également résumé dans [product-spec.md](product-spec.md) §7 (« Communauté / discussions géolocalisées ») et référencé comme principe d'extensibilité (sans conception concrète) dans [architecture.md](architecture.md) §1, principe 11. Les deux documents renvoient ici pour le détail — voir la liste des points nécessitant validation en fin de document.*

---

## Hypothèses à tester (avant d'être considérées comme définitives)

- Seuils de vitesse/altitude/durée permettant de distinguer immobile / marche / vélo / voiture / train / bateau / avion (§1, §2).
- Durée minimale de présence dans une cellule pour valider une découverte (« couverture minimale d'une cellule »).
- Critère exact déclenchant le statut « visité » pour une entité géographique (§4).
- Règle de tolérance spatiale/rayon en mode Precision/Hard (§8).
- Comportement face à un GPS imprécis (bâtiments denses, canyons urbains, intérieur, tunnels) : filtrage, lissage, ou rejet de l'échantillon.
- Comportement au retour dans une zone déjà découverte : confirmation, aucune double comptabilisation, éventuel enrichissement du niveau de confiance.
- Comportement lors d'une perte de signal prolongée : stockage local, reprise, et impact sur la continuité d'un trajet.
- Fonction d'agrégation exacte reliant la représentation canonique à chacun des niveaux Easy/Standard/Precision (§8).

Chaque hypothèse doit être validée par des scénarios de test reproductibles avant d'être figée dans le code de production.

---

## Scénarios de test indispensables (dérivés du cahier des charges)

- Première présence suffisamment fiable dans une zone (y compris le domicile) → comportement déterminé selon les futures règles de présence validées.
- Rester immobile dans une zone déjà découverte → aucune progression artificielle vers les cellules voisines, aucune double comptabilisation.
- Marcher → zones cohérentes.
- Conduire → progression cohérente, **sans dévaluation** par rapport à la marche pour le même corridor réellement parcouru (§0).
- Prendre l'avion → pas de découverte massive des zones survolées ; reprise de la découverte après atterrissage (§2).
- Traverser un petit village en voiture → contribution significative à ce village, sans compléter automatiquement une grande ville de la même façon (§3, §5).
- Traverser quelques rues d'une grande ville → contribution proportionnellement faible par rapport à la ville entière (§3, §5, §9).
- Emprunter les quelques chemins publics d'une grande forêt homogène → généralisation représentative, sans exiger de quadriller la forêt (§6).
- Compléter une ville en Standard en suivant un parcours représentatif → 100 % atteignable sans visiter chaque ruelle (§7, §13).
- Traverser l'océan en bateau → aucune progression de complétion terrestre pendant la traversée (§11).
- Visiter une île accessible (habitée ou non) → reste explorable normalement (§11).
- Perte de réseau → stockage local puis synchronisation à la reconnexion.
- Application redémarrée/fermée → reprise conforme aux capacités et permissions du système.
- Retour dans une zone déjà découverte → aucune double comptabilisation.
- Changement de niveau de précision après un usage long → aucune perte d'historique, pas de recommencement à zéro.
- Localisation simulée, téléportation, horloge manipulée, replay d'événements, base locale modifiée → à couvrir spécifiquement avant toute activation d'un classement ou d'une fonctionnalité Certified compétitive (voir [certified-mode.md](certified-mode.md)).

Ces scénarios doivent être formalisés en tests automatisés (unitaires pour la logique d'agrégation, avec fixtures de trajectoires simulées) avant que les hypothèses ci-dessus ne soient figées.

---

## Questions ouvertes / nécessitent une validation explicite

Aucun des points suivants ne doit être deviné, approximé « pour avancer », ni codé en dur avant décision explicite documentée ici :

1. Résolution H3 canonique exacte (§8).
2. Formule exacte de densité adaptative (§5).
3. Formule exacte de pondération de l'exploration (§9).
4. Distances/rayons minimum et maximum de généralisation en territoire homogène (§6, §12).
5. Règles exactes d'agrégation Easy/Standard/Hard depuis la représentation canonique (§8).
6. Critère exact de statut « visité » pour une entité géographique (§4).
7. Référentiel/hiérarchie géographique de référence (Monde → Continent → Pays → Région → Zone → Lieu) et sa source de données.
8. Classification pays/territoire (îles, territoires contestés, dépendances — §11).
9. Sources de données d'éligibilité et processus de résolution/mise à jour (§10).
10. Politique du dénominateur pour le statut `UNKNOWN` (§10).
11. Traitement du dénominateur pour l'Antarctique/les zones polaires (§12).
12. Comportement de découverte océanique/côtière, y compris le traitement exact des îles minuscules/rochers (§11).
13. Traitement fin des corridors ferroviaires si une distinction supplémentaire s'avère nécessaire (§3).
14. Construction exacte du dénominateur du pourcentage (gestion des arrondis, cellules partiellement couvertes par une frontière, agrégation géométrique précise).
15. Jeux de données et seuils d'acceptation pour la calibration/le test du modèle avant mise en production.
16. Procédure de gestion des changements futurs d'éligibilité (comment et quand une cellule change de statut, impact sur les scores déjà atteints).

Aucun de ces points n'est tranché dans ce document — ils appellent une décision produit explicite avant toute implémentation.
