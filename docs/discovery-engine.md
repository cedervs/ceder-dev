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
  → cellules géographiques H3 (représentation canonique — résolution 12 validée pour la v1, structure exacte et règles d'agrégation encore ouvertes, voir §8)
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

**[Validé]** La navigation reste hiérarchique (Monde → Continent → Pays, puis une hiérarchie dépendante du pays sous ce niveau — voir §19). La carte au niveau mondial doit donner une vision satisfaisante des pays où l'utilisateur est allé ; les niveaux plus profonds révèlent le pourcentage réel d'exploration. Un pays « visité » **ne doit jamais visuellement laisser croire** qu'il est exploré à 100 %.

**[Validé — 2026-08-29]** Toute présence/passage physique **validé** à l'intérieur d'un territoire suffit pour que ce territoire soit considéré comme découvert/visité et coloré visuellement. **Il n'existe aucun pourcentage minimum requis** avant de dire qu'un pays/une région/une ville a été visité(e). Principe formulé par le produit : *« Présence physique validée = territoire découvert/coloré. Le pourcentage représente la profondeur réelle d'exploration. »* Ceci remplace l'ancienne formulation qui laissait un seuil de pourcentage/couverture ouvert pour le statut « visité » — seul ce seuil de *pourcentage* est tranché ; voir ci-dessous pour ce qui reste ouvert.

**[OUVERT — à calibrer]** Critère exact de présence **validée** déclenchant le statut « visité » (durée minimale de présence, nombre de cellules canoniques distinctes touchées, seuil de confiance...) — la question résolue ci-dessus est « faut-il un pourcentage minimum ? » (non), pas « qu'est-ce qu'une présence suffisamment fiable pour compter ? » (encore ouvert).

---

## 5. Densité d'exploration adaptative

**[Validé]** Le mode Standard n'utilise **pas** une exigence de couverture géographique uniforme partout. Deux surfaces physiques égales peuvent nécessiter des quantités d'exploration très différentes.

**[Validé]** La densité d'exploration requise doit augmenter avec la densité et la diversité des éléments géographiques significatifs et légalement accessibles. Signaux **primaires** identifiés (liste non exhaustive, aucun n'est pondéré ni choisi comme définitif) :
1. densité et longueur du réseau accessible de routes/rues/chemins/sentiers ;
2. densité du bâti / de l'urbanisation / des zones habitées ;
3. diversité géographique et d'usage des sols ;
4. lieux/éléments géographiques distinctifs (parcs, places, littoral, rivières, lacs, secteurs historiques, etc.) ;
5. accessibilité.

**[Validé — 2026-08-29]** Signal **secondaire de calibration** : la fréquentation/popularité agrégée des utilisateurs pourra avoir une **influence mineure**, plus tard. Elle ne doit **jamais** devenir le mécanisme de pondération principal. Éviter toute boucle de rétroaction où les lieux déjà populaires deviennent automatiquement et de façon disproportionnée plus importants que les autres.

**[Validé]** Éviter les règles simplistes du type « une rue = X points ». Utiliser des mesures significatives : longueur/densité de réseau, nombre d'intersections, chemins distincts, diversité environnementale, etc. plutôt qu'un comptage naïf d'éléments.

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

**[Validé — 2026-08-29]** **Résolution H3 12** retenue comme résolution canonique de découverte pour la v1. Cette décision est **provisoire/calibrable** et doit rester **versionnée** (`engine_version`) : elle a été choisie comme point de départ raisonnable, pas comme constante figée à jamais — un changement futur de résolution canonique resterait possible via une nouvelle version, sans détruire l'historique (voir §16). Ceci remplace l'ancien statut « OUVERT » de ce paramètre.

**[Hypothèse à tester — cible produit, pas des coefficients finaux]** Philosophie de précision, formulée comme objectifs produit à calibrer :
- **Standard** reste le mode de référence (effort = 100 % par définition) ;
- **Easy** devrait représenter environ **50 à 60 %** de l'effort d'exploration Standard ;
- **Hard/Precision** devrait représenter environ **200 à 250 %** de l'effort d'exploration Standard.

Ces ratios sont des **cibles produit**, pas des coefficients mathématiques finaux — ils devront guider (sans la figer a priori) la calibration de la fonction d'agrégation reliant la représentation canonique aux trois niveaux.

**[Hypothèse à tester]** Hypothèses initiales de généralisation en tissu urbain, à valider par tests réels (voir §7 et « Terrains de calibration réels » en fin de document) — **valeurs non universelles**, adaptatives/configurables/versionnées :
- Easy : rayon de généralisation de l'ordre de **80 m** ;
- Standard : de l'ordre de **40 m** ;
- Hard : de l'ordre de **15 à 20 m**.

**[OUVERT — à calibrer]** Règles exactes d'agrégation reliant la représentation canonique à chacun des niveaux Easy/Standard/Precision (relation H3 parent/enfant stricte vs règle de couverture propre à Precision, tolérance spatiale exacte en Precision/Hard au-delà des hypothèses initiales ci-dessus).

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

**[Validé — 2026-08-29]** L'éligibilité appartient à la **zone géographique**, jamais à l'utilisateur individuel. Une base militaire fermée au public reste exclue même si un militaire/employé y est personnellement autorisé — l'éligibilité ne dépend jamais de qui demande l'accès.

**[Validé — 2026-08-29]** Propriété privée : World Discovery n'exige **jamais** d'intrusion. Une maison, un jardin ou un champ ordinaire n'ont pas besoin d'être physiquement pénétrés — les routes/chemins accessibles à proximité, combinés à la généralisation (§6), peuvent représenter le territoire environnant.

**[Validé]** Le danger seul **ne rend jamais** un territoire inéligible. Ne sont **jamais** automatiquement exclus : quartiers dangereux, favelas, pays à risque sécuritaire, ou toute autre zone légalement accessible simplement parce qu'elle peut être dangereuse — des personnes y vivent, des utilisateurs peuvent légitimement s'y rendre. « Difficile » et « interdit » sont deux notions différentes.

**[Validé — 2026-08-29]** Lieux extrêmes : la complétion Standard ne doit **jamais** exiger un exploit professionnel/extrême pour la seule progression du pourcentage. Le sommet de l'Everest n'est pas requis pour atteindre 100 % Standard du Népal ; sommet/camp de base/etc. peuvent en revanche relever d'un futur système de XP/badges/collections (voir §21). Principe similaire pour déserts, grandes forêts, zones polaires, îles isolées, etc.

**[Validé]** Cependant, World Discovery ne doit **jamais** récompenser ni encourager un comportement dangereux. **Aucun bonus d'exploration** n'est accordé au seul motif qu'une zone est dangereuse. Une éventuelle information de sécurité, si elle est implémentée plus tard, doit rester strictement séparée du calcul de découverte/score.

**[Validé]** Les états d'éligibilité versionnés sont conservés : `ELIGIBLE`, `RESTRICTED_EXCLUDED`, `UNKNOWN`. Les cellules `RESTRICTED_EXCLUDED` ne sont jamais nécessaires pour atteindre 100 % et n'entrent jamais dans le dénominateur.

**[Validé — 2026-08-29] Politique du dénominateur pour `UNKNOWN` — décision prise, remplace l'ancien statut « ouvert ».** Les zones `UNKNOWN` restent **hors du dénominateur** tant qu'elles ne sont pas classifiées. `UNKNOWN` contribue **0 % au requis et 0 % en bonus** — l'exploration officielle reste plafonnée à 100 % quoi qu'il arrive. Le produit ne doit **jamais** inciter l'utilisateur à entrer dans une zone `UNKNOWN` pour progresser.

Si un utilisateur traverse naturellement une zone `UNKNOWN` (sans y être incité) : le passage peut être stocké ; l'application peut plus tard demander un retour sur l'accessibilité du lieu. Le signalement d'un **seul** utilisateur ne doit **jamais** reclassifier automatiquement une zone à lui seul (il pourrait bénéficier d'une autorisation personnelle exceptionnelle — cohérent avec « l'éligibilité appartient à la zone, pas à l'utilisateur » ci-dessus). La reclassification peut s'appuyer sur : plusieurs passages/signalements indépendants, données cartographiques/d'accès, sources officielles/publiques lorsqu'elles existent. `UNKNOWN` peut ensuite devenir `ELIGIBLE` ou `RESTRICTED_EXCLUDED`.

Si l'éligibilité change plus tard : la découverte physique historique reste préservée ; le pourcentage courant peut être recalculé sous une nouvelle version du référentiel (voir §16) ; les accomplissements historiques déjà atteints ne doivent **jamais** disparaître silencieusement.

**[Validé]** La présence de Google Street View ou d'une couverture automobile similaire **n'est pas une preuve d'accessibilité légale** ; elle peut au mieux être un signal auxiliaire parmi d'autres, jamais la source de vérité de l'éligibilité.

**[Validé]** World Discovery n'est **jamais présenté comme une autorisation légale d'accès** à un lieu : l'utilisateur reste seul responsable du respect des lois, propriétés privées, fermetures et signalisation locales.

**[OUVERT — à calibrer]** Source(s) de données d'éligibilité, gouvernance de mise à jour, définition exacte des terres éligibles à la surface mondiale, traitement des frontières/territoires contestés, procédure de gestion des changements futurs d'éligibilité — voir la section finale.

---

## 11. Océans, îles et territoires isolés

**[Validé]** L'océan ouvert ne fait pas partie du pourcentage d'exploration terrestre. Un trajet en bateau peut être stocké/affiché comme un voyage, mais traverser l'océan ouvert n'augmente pas la complétion terrestre mondiale.

**[Validé]** Les îles accessibles restent une géographie explorable valide. Une île n'a pas besoin d'être un pays indépendant pour être explorable (exemples cités : Tahiti, La Réunion, la Corse et territoires/îles similaires — restent géographiquement explorables lorsqu'elles sont légitimement accessibles).

**[Validé]** Chaque île n'est pas automatiquement comptée comme un « pays visité » distinct — la classification pays/territoire nécessite un référentiel géographique versionné défini plus tard (voir §16 et architecture.md §8).

**[Validé — 2026-08-29]** Les morceaux géographiques physiquement séparés sont découverts **séparément**. Visiter la France métropolitaine ne colore **pas** automatiquement la Corse, La Réunion, la Guadeloupe, la Martinique, etc. Ces territoires peuvent rester rattachés à la France pour l'agrégation politique/statistique, mais la découverte géographique visuelle exige une présence réelle dans chaque morceau distinct. Des morceaux liés au même pays parent peuvent optionnellement être mis en évidence ensemble dans l'UI lors d'une sélection/survol/tap, sans que cela n'affecte le calcul.

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

**[Validé — 2026-08-29, précisions]** Contenu envisagé : court texte/commentaire, emoji/icône, photo, potentiellement courte vidéo, date. **Privé par défaut**. Couche/bascule « Souvenirs » optionnelle sur la carte, visibilité progressive avec le zoom (cohérent avec le principe de détails de carte progressifs — voir [product-spec.md](product-spec.md) §2). Un partage explicite futur de souvenirs sélectionnés pourra exister, mais rien ne devient public automatiquement. Les localisations précises/sensibles restent privées.

**[Validé]** Ceci reste strictement séparé du calcul de découverte central pour l'instant — ni le pourcentage d'exploration, ni le XP (§21), ni le score Certified, ne dépendent de ce contenu.

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

## 19. Hiérarchie géographique flexible

**[Validé — 2026-08-29]** Le sommet de la hiérarchie est fixe : **Monde → Continent → Pays**. **Sous le niveau pays, la hiérarchie dépend du pays** — aucun nombre de niveaux n'est imposé uniformément.

> Exemples : France = Pays → Région → Département → Commune/Ville → Zone. USA = Pays → État → Comté → Ville → Zone.

**[Validé]** Types génériques internes envisagés : `COUNTRY`, `ADMIN_1`, `ADMIN_2`, `LOCALITY`, `ZONE`. L'UI affiche le nom localement approprié (Région, Département, État, Province, Comté, etc.) plutôt que ces types génériques internes.

**[Validé]** Les grands territoires peuvent avoir davantage de niveaux ; les très petits territoires/îles peuvent en avoir moins. Si les subdivisions administratives officielles ne suffisent pas à une progression utile, World Discovery peut créer des « **zones World Discovery** » non officielles, clairement identifiées comme telles (jamais confondues avec une subdivision administrative réelle).

**[Validé]** H3 reste la représentation géographique sous-jacente à tous les niveaux ; cette hiérarchie est une couche de lecture/agrégation, pas une capture indépendante.

**[OUVERT — à calibrer]** Référentiel/source de données exact alimentant cette hiérarchie par pays — voir architecture.md §8 (« Sources de données géographiques ») et la liste des questions ouvertes.

---

## 20. Frontières contestées

**[Validé — 2026-08-29]** L'exploration s'attache d'abord à la **zone géographique physique**, pas à la revendication politique.

**[Validé]** Frontières stables : affichage normal. Frontières disputées/incertaines : représentation visuelle **neutre** (dégradé, hachures, bande d'incertitude large, ou équivalent selon le cas) ; l'UI peut indiquer « frontière contestée ». L'utilisateur n'est **jamais** forcé de choisir un camp/revendicateur.

**[Validé]** Le rattachement administratif peut évoluer dans le temps, mais le passage physique historique doit **toujours** rester préservé (cohérent avec la monotonie de l'historique, §16).

**[OUVERT — à calibrer]** Classification exacte territoire par territoire (quelles frontières sont considérées disputées, référentiel source), traitement visuel précis par cas.

---

## 21. Exploration % vs XP

**[Validé — 2026-08-29]** Séparation produit stricte, ne jamais mélanger :
- **Pourcentage d'exploration** : *« Quelle part du monde/du territoire ai-je géographiquement exploré ? »*
- **XP** (futur, non-MVP) : *« Quelles choses notables ai-je découvertes dans le monde ? »*

**[Validé]** Visiter la Tour Eiffel n'augmente **jamais** artificiellement le pourcentage d'exploration géographique au-delà de la surface physique réellement parcourue. Un lieu notable peut octroyer du **XP séparément**.

**[Validé — cadrage futur]** Exemples de lieux notables pouvant octroyer du XP à l'avenir : monuments, sites historiques majeurs, grands musées, merveilles naturelles, points de vue, parcs importants, plages, parcs d'attractions, châteaux, lieux patrimoniaux, sommets notables. La sélection doit être **stricte** — ne jamais transformer chaque point d'intérêt/commerce/bâtiment en source de XP. L'importance peut être contextuelle : un château local peut être important pour son village tout en valant moins de XP qu'un monument mondialement iconique.

**[Validé]** Les valeurs de XP exactes ne sont **pas** validées ; aucun chiffre d'exemple antérieur ne doit être traité comme définitif.

**[Validé — cadrage futur]** Futures collections envisageables : merveilles du monde, merveilles naturelles, patrimoine UNESCO, châteaux, parcs nationaux, monuments iconiques, sommets majeurs, etc. Le XP n'est **pas** une monnaie à ce stade ; une éventuelle dépensabilité future reste **ouverte**.

**[Validé]** L'ensemble de ce système XP est **futur/non-MVP**, mais l'architecture doit rester extensible pour l'accueillir (voir architecture.md §1, principe 12) sans mélanger son calcul à celui du pourcentage d'exploration ni du score Certified.

---

## 22. Repères communautaires « immanquables » (futur, non-MVP)

**[Validé — cadrage futur, non-MVP]** Concept distinct à la fois du calcul de découverte et du XP officiel — détail produit complet dans [product-spec.md](product-spec.md) §7. Invariant à préserver ici : les repères communautaires **ne donnent jamais** de pourcentage géographique et **ne donnent pas de XP par défaut** ; la popularité communautaire ne doit **jamais** promouvoir automatiquement un lieu vers la base XP officielle (§21) — toute éventuelle promotion resterait un acte éditorial explicite, jamais automatique.

---

## 23. Fusion de signaux de localisation

**[Validé — 2026-08-29]** World Discovery ne doit **jamais** traiter une seule coordonnée GPS brute comme une vérité absolue. La localisation automatique doit exploiter **tous les signaux pertinents disponibles** via l'appareil/la plateforme, selon disponibilité : GNSS/GPS, localisation fusionnée/réseau d'Android, Wi-Fi à proximité, réseau mobile/cellulaire, accéléromètre, gyroscope, magnétomètre, baromètre si utile, continuité temporelle et de mouvement.

**[Validé]** L'**adresse IP** ne doit **jamais** servir de preuve de découverte géographique — VPN/proxys et l'imprécision de la géolocalisation IP la rendent inadaptée.

**[Validé]** Principe de traitement attendu du moteur :
1. rassembler les signaux de localisation/mouvement disponibles ;
2. évaluer leur cohérence mutuelle ;
3. rejeter les valeurs aberrantes/le bruit évident ;
4. estimer la position/trajectoire la plus plausible ;
5. valider la découverte géographique seulement après ce filtrage.

**[Validé]** Ne **jamais** utiliser une simple moyenne arithmétique de coordonnées.

> Exemple : si un relevé GPS saute soudainement de plusieurs centaines de mètres, alors que les relevés environnants et les capteurs de mouvement restent cohérents avec le tracé d'origine, ce point doit être rejeté comme bruit GPS.

**[Validé]** Maintenir un concept interne de **confiance** pour les positions/événements (sans qu'aucune valeur/format ne soit choisi ici).

**[OUVERT — à calibrer]** Algorithme de filtrage exact et seuils associés — restent ouverts jusqu'à implémentation/tests réels (voir « Terrains de calibration réels » en fin de document).

---

## 24. Perte GPS temporaire et reconstruction de trajet

**[Validé — 2026-08-29]** Si la localisation est fiable au point A, disparaît temporairement, puis redevient fiable au point B : le moteur peut reconstruire le trajet le plus logique/plausible entre A et B, en utilisant le temps, la distance, les réseaux de transport disponibles et le contexte de mouvement. Une coupure courte et cohérente ne doit **pas** casser la continuité de découverte ; un trajet reconstruit peut compter comme une découverte **Normal**, et — sous réserve de validation serveur — potentiellement Certified (voir [certified-mode.md](certified-mode.md) §8, nouveau).

**[Validé]** Provenance à préserver en interne pour chaque portion de trajet : observation directe / reconstruction automatique / preuve importée / ajout manuel non certifié (voir §25). L'UI n'a pas nécessairement besoin d'exposer tous ces états techniques.

**[Validé]** Pour les coupures longues (plusieurs heures) ou un mouvement fortement ambigu, le moteur doit rester **conservateur** plutôt que de reconstruire agressivement.

**[OUVERT — à calibrer]** Limites exactes (durée de coupure acceptable, degré d'ambiguïté tolérable) — à calibrer par des tests réels.

---

## 25. Récupération manuelle/historique de découvertes

**[Validé — cadrage futur]** Les utilisateurs doivent pouvoir reconstituer des voyages passés (batterie vide, téléphone oublié, localisation désactivée, échec de tracking, voyage antérieur à l'installation de l'app). Deux résultats de confiance **visibles** : **Certified** ou **Non-certifié**. Détail complet des types de preuve, des garde-fous IA et de la règle « une preuve ne certifie que ce qu'elle prouve raisonnablement » dans [certified-mode.md](certified-mode.md) §9 (nouveau).

**[Validé]** Non-certifié : ajout manuel basé sur le souvenir de l'utilisateur, couleur/couche de carte clairement différente, activable/désactivable, préserve l'historique personnel, **n'entre jamais** dans les classements officiels. Certified : le tracking automatique produit normalement du Certified ; une récupération historique **peut aussi** devenir Certified si les preuves sont suffisamment solides et validées côté serveur — ceci étend, sans le contredire, le principe §1 de certified-mode.md (« doit provenir du tracking » s'entend désormais comme « du tracking en direct **ou** de preuves suffisamment fiables, toujours validées côté serveur »).

**[Validé]** La reconnaissance faciale/selfie n'est **jamais** exigée ; aucune reconnaissance biométrique d'identité n'est conçue comme prérequis.

---

## 26. Reconstruction de trajet entre preuves certifiées

**[Validé — cadrage futur]** Si deux points chronologiques A et B sont tous deux fortement certifiés, World Discovery peut reconstruire et certifier le trajet terrestre minimal/le plus plausible entre eux (l'utilisateur a nécessairement voyagé physiquement de A à B, et un transport terrestre normal compte comme découverte — voir §0). Détail complet, exemples et invariant de conservatisme dans [certified-mode.md](certified-mode.md) §10 (nouveau). Les cellules certifiées reconstruites doivent rester **distinguables en interne** des cellules certifiées directement observées.

---

## 27. Affichage Certified / Non-certifié

**[Validé — cadrage futur]** Le concept visible reste simple : un bascule (« Afficher les découvertes non certifiées ») — OFF affiche uniquement le Certified ; ON ajoute le Normal/non-certifié personnel dans un style visuel clairement différent. Les classements publics officiels restent **exclusivement** basés sur le Certified (rappel, déjà validé). Ne pas créer de complexité visible inutile (Personnel/Vérifié/Certifié) : la richesse de confiance/provenance reste interne. Détail dans [product-spec.md](product-spec.md) §4 et [certified-mode.md](certified-mode.md) §11.

---

## Hypothèses à tester (avant d'être considérées comme définitives)

- Seuils de vitesse/altitude/durée permettant de distinguer immobile / marche / vélo / voiture / train / bateau / avion (§1, §2).
- Durée minimale de présence dans une cellule pour valider une découverte (« couverture minimale d'une cellule »), et plus généralement critère exact de présence *validée* déclenchant le statut « visité » (§4 — le seuil de *pourcentage*, lui, est tranché : aucun).
- Philosophie de précision Easy ≈ 50–60 % et Hard ≈ 200–250 % de l'effort Standard — cibles produit à traduire en fonction d'agrégation réelle (§8).
- Hypothèses initiales de rayon de généralisation urbain (Easy ≈ 80 m, Standard ≈ 40 m, Hard ≈ 15–20 m) — non universelles (§8).
- Règle de tolérance spatiale/rayon en mode Precision/Hard au-delà des hypothèses initiales ci-dessus (§8).
- Algorithme exact de fusion/filtrage multi-signaux (GNSS, réseau, capteurs) et seuils de rejet du bruit (§23).
- Limites exactes de reconstruction lors d'une perte GPS temporaire (durée de coupure acceptable, degré d'ambiguïté tolérable) (§24).
- Comportement au retour dans une zone déjà découverte : confirmation, aucune double comptabilisation, éventuel enrichissement du niveau de confiance.
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
- Visiter une île d'un territoire déjà découvert par ailleurs (ex. Corse après la métropole) → ne doit pas être automatiquement colorée (§11).
- Entrer puis sortir d'une zone `UNKNOWN` sans y être incité → passage stocké, aucun bonus, aucune pénalité, dénominateur inchangé (§10).
- Perte GPS temporaire en cours de trajet, avec reprise cohérente → reconstruction plausible entre les deux points connus (§24).
- Perte de réseau → stockage local puis synchronisation à la reconnexion.
- Application redémarrée/fermée → reprise conforme aux capacités et permissions du système.
- Retour dans une zone déjà découverte → aucune double comptabilisation.
- Changement de niveau de précision après un usage long → aucune perte d'historique, pas de recommencement à zéro.
- Localisation simulée, téléportation, horloge manipulée, replay d'événements, base locale modifiée → à couvrir spécifiquement avant toute activation d'un classement ou d'une fonctionnalité Certified compétitive (voir [certified-mode.md](certified-mode.md)).

Ces scénarios doivent être formalisés en tests automatisés (unitaires pour la logique d'agrégation, avec fixtures de trajectoires simulées) avant que les hypothèses ci-dessus ne soient figées.

### Terrains de calibration réels

**[Validé — 2026-08-29]** La calibration ne doit pas se limiter à des scénarios synthétiques. Prévoir des tests de terrain sur **appareil Android physique réel** couvrant des lieux réels diversifiés : centre-ville dense, petite ville, village, campagne agricole, forêt à réseau de sentiers riche, forêt à réseau de sentiers pauvre, montagne, littoral, île, désert, trajet en train, trajet en voiture, marche, métro/tunnel, GPS urbain de mauvaise qualité, perte de signal temporaire, vol en avion. Objectif : **valider un comportement réel**, pas obtenir une formule mathématiquement élégante sur le papier. Aucun coefficient/seuil GPS final ne doit être choisi avant ces tests.

---

## Questions ouvertes / nécessitent une validation explicite

Aucun des points suivants ne doit être deviné, approximé « pour avancer », ni codé en dur avant décision explicite documentée ici. *(Résolus depuis la dernière révision : résolution H3 canonique → §8 ; politique du dénominateur `UNKNOWN` → §10 ; principe général de traitement des frontières contestées → §20 ; principe général de fusion de signaux de localisation → §23.)*

1. Formule exacte de densité adaptative (§5) et de pondération de l'exploration (§9) — signaux identifiés, pondération non choisie.
2. Coefficients finaux de la philosophie de précision (Easy/Hard vs Standard) et distances de généralisation urbaine calibrées (§8).
3. Distances/rayons minimum et maximum de généralisation en territoire homogène (§6, §12).
4. Règles exactes d'agrégation Easy/Standard/Hard depuis la représentation canonique (§8).
5. Critère exact de présence *validée* déclenchant le statut « visité » (§4).
6. Référentiel/hiérarchie géographique de référence par pays (§19) et sa source de données (voir architecture.md §8, « Sources de données géographiques »).
7. Classification pays/territoire (îles, territoires contestés, dépendances — §11, §20) ; classification exacte territoire par territoire des frontières disputées (§20).
8. Sources de données d'éligibilité et processus de résolution/mise à jour (§10) ; seuils exacts de reclassification `UNKNOWN` (nombre de signalements indépendants requis, pondération des sources).
9. Traitement du dénominateur pour l'Antarctique/les zones polaires (§12).
10. Comportement de découverte océanique/côtière, y compris le traitement exact des îles minuscules/rochers (§11).
11. Traitement fin des corridors ferroviaires si une distinction supplémentaire s'avère nécessaire (§3).
12. Construction exacte du dénominateur du pourcentage (gestion des arrondis, cellules partiellement couvertes par une frontière, agrégation géométrique précise).
13. Jeux de données et seuils d'acceptation pour la calibration/le test du modèle avant mise en production (voir « Terrains de calibration réels »).
14. Procédure de gestion des changements futurs d'éligibilité — cadre général tranché (§10), délais/gouvernance exacts restant ouverts.
15. Algorithme exact et seuils de fusion/rejet de signaux de localisation (§23) ; limites exactes de reconstruction lors d'une perte GPS temporaire (§24).
16. Valeurs de XP exactes, sélection précise des lieux notables, et éventuelle dépensabilité du XP (§21).
17. Seuil exact de confirmation publique d'un repère communautaire (l'hypothèse ~10 utilisateurs indépendants n'est qu'une hypothèse de départ), règles de fusion anti-doublon précises, et modalités de modération/anti-spam (§22, voir aussi product-spec.md §7).
18. Barème/critères exacts de confiance pour la récupération historique (quelles combinaisons de preuves suffisent à quel niveau de certification) (§25, voir certified-mode.md §9).

Aucun de ces points n'est tranché dans ce document — ils appellent une décision produit explicite avant toute implémentation.
