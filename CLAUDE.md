# CLAUDE.md — Règles permanentes pour World Discovery

## Relation avec AGENTS.md et la mémoire persistante du projet

1. **`AGENTS.md`** (racine du dépôt) contient les règles communes permanentes applicables à tous les agents travaillant sur World Discovery (Claude Code, Codex, et tout autre agent futur).
2. Claude Code doit **toujours lire et respecter `AGENTS.md`**, en plus du présent fichier.
3. **`PROJECT_STATUS.md`** et **`docs/ai-context/`** constituent la mémoire persistante du projet, à consulter selon la hiérarchie de sources définie dans `AGENTS.md`.
4. Ce fichier (`CLAUDE.md`) ne doit contenir que des instructions **spécifiques à Claude Code**, ou des compléments qui ne dupliquent pas `AGENTS.md`.
5. En cas de contradiction :
   - le code, les tests et l'historique Git restent la source de vérité pour ce qui est réellement implémenté ;
   - les documents normatifs et la hiérarchie définie dans `AGENTS.md` s'appliquent ;
   - une règle spécifique de ce fichier ne doit **jamais** contredire `AGENTS.md` ;
   - si une contradiction réelle subsiste malgré cela, ne pas trancher arbitrairement : la signaler avant toute modification.

---

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
5. **Ne jamais inventer silencieusement une véritable décision produit encore ouverte** — mais distinguer la catégorie documentée dans `docs/ai-context/` (voir notamment `OPEN_QUESTIONS.md` et le document spécialisé pertinent) avant d'agir :
   1. **`NEEDS USER CONFIRMATION`** : signaler le point, proposer les options pertinentes si possible, et attendre une validation explicite avant d'en faire une règle produit.
   2. **`ENGINEERING DESIGN REQUIRED`** : ne pas demander automatiquement une décision à l'utilisateur ; concevoir et implémenter une solution technique cohérente avec les invariants produit déjà actés — sauf si plusieurs options impliquent réellement des conséquences produit importantes, auquel cas les signaler avant de choisir.
   3. **`CALIBRATION REQUIRED`** : ne pas demander à l'utilisateur de choisir arbitrairement une valeur ; la déterminer ou la proposer par tests, mesures, données ou expérimentation appropriée, et documenter la méthode et les résultats.
   4. **`DECIDED / NOT IMPLEMENTED`** : traiter comme une décision produit déjà prise ; ne pas la rouvrir sans raison explicite.
   5. **`IMPLEMENTED`** : le code et les tests font foi ; respecter le comportement existant.

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
