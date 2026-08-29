# CLAUDE.md — Règles permanentes pour World Discovery

Ce fichier s'applique à tout travail de développement effectué sur ce dépôt avec Claude Code. Il complète, sans les remplacer, les documents de référence dans `/docs` :

- [docs/product-spec.md](docs/product-spec.md) — vision produit, UX, périmètre fonctionnel.
- [docs/architecture.md](docs/architecture.md) — structure technique, modèle de données, principes.
- [docs/discovery-engine.md](docs/discovery-engine.md) — règles du moteur de découverte (validées / à tester / ouvertes).
- [docs/certified-mode.md](docs/certified-mode.md) — mode Certified.
- [docs/roadmap.md](docs/roadmap.md) — séquencement MVP/V1/V2/V3 et ordre des incréments.

## Règles

1. **Lire `/docs` avant toute modification importante.** Ne pas réinventer une décision déjà prise ailleurs dans le dépôt.
2. **Respecter les décisions déjà validées** dans `/docs`. Ne pas les contourner silencieusement au prétexte qu'une implémentation serait plus simple autrement.
3. **Préserver l'architecture offline-first** : le mode Normal ne doit jamais devenir dépendant du réseau pour un usage courant. Le backend sert aux comptes, à la sauvegarde, à la synchronisation et à la validation Certified — jamais au chemin critique du Normal.
4. **Ne jamais mélanger Normal et Certified.** Ce sont deux ensembles de données strictement séparés ; une cellule Normal ne devient jamais automatiquement Certified, et le client ne décide jamais seul qu'une cellule est Certified.
5. **Ne jamais choisir silencieusement une valeur pour une décision métier encore ouverte** — par exemple : résolution H3, structure exacte de la représentation canonique, seuils de présence, vitesses, durées, règles de transport, tolérance Precision, traitement de `UNKNOWN`, règles d'éligibilité (voir les catégories B et C de `discovery-engine.md`). Avant toute implémentation qui nécessite réellement de trancher l'une de ces décisions :
   1. signaler explicitement que la décision est encore ouverte ;
   2. proposer une ou plusieurs options avec leurs avantages/inconvénients ;
   3. recommander une option si possible ;
   4. attendre une validation explicite avant de la considérer comme une règle produit.

   Un prototype ou test expérimental peut utiliser une valeur temporaire uniquement si elle est clairement identifiée comme telle, isolée du reste du code, jamais présentée comme une décision produit, et sans migration irréversible qui en dépendrait.
6. **Autorité du Certified** : les événements/décisions de validation côté serveur constituent l'unique autorité du Certified. Les cellules, scores, statistiques et classements Certified sont des projections dérivées et reconstructibles de cette autorité — jamais une source indépendante. Le client ne peut jamais promouvoir lui-même une découverte au statut Certified officiel.
7. **Travailler par petits incréments testables**, dans l'ordre recommandé par `roadmap.md` sauf instruction contraire explicite.
8. **Écrire des tests pour la logique critique**, en particulier le moteur de découverte et tout ce qui touche au calcul de score.
9. **Préserver les données utilisateur lors des migrations** (Room comme PostgreSQL) : migrations additives, jamais destructives, jamais de perte d'historique.
10. **Ne pas introduire inutilement une dépendance forte à un fournisseur externe** (cartographie, auth, infra) sans l'isoler derrière une interface interne remplaçable.
11. **Respecter l'internationalisation dès les premiers écrans** : aucun texte utilisateur codé en dur, système de ressources natif dès le premier composant.
12. **Respecter le privacy-by-default** : aucune donnée sensible (position précise, domicile probable, informations d'authentification/récupération) publique ou exposée sans nécessité explicite documentée.
13. **Mettre à jour `/docs`** dès qu'une décision produit ou d'architecture change en cours de développement — ces documents sont vivants, pas figés à leur création.
14. **Signaler clairement un conflit** entre une demande future et une décision déjà documentée, plutôt que de contourner silencieusement l'architecture. Expliquer le conflit et attendre une décision avant d'agir.
