# World Discovery — Roadmap

> Document de référence vivant. Le découpage en versions sert à développer progressivement — **il ne signifie pas qu'une version antérieure est une architecture jetable.** La fondation (voir [architecture.md](architecture.md)) est conçue pour permettre les évolutions prévues sans reconstruire l'application.

## Périmètre du premier MVP

Le premier MVP reste concentré sur le cœur du produit :

1. Architecture/scaffolding Android propre (modules, thème clair/sombre/système).
2. Internationalisation dès le premier écran.
3. Authentification Google + méthode e-mail retenue (OTP).
4. Permissions de localisation.
5. Capture GPS en arrière-plan.
6. Moteur de découverte Normal, local, offline-first.
7. Conversion vers la représentation canonique H3 (structure et résolution exactes non fixées ici, à valider dans [discovery-engine.md](discovery-engine.md) — voir [architecture.md](architecture.md) §8).
8. Stockage Room/SQLite.
9. Carte affichant les découvertes.
10. Pourcentage mondial v0 — une règle simplifiée peut être utilisée pour cette seule étape, uniquement si elle est explicitement documentée et validée avant implémentation ; elle ne tranche silencieusement ni le traitement de `UNKNOWN`, ni les frontières/territoires contestés, ni aucune autre décision encore ouverte (voir [discovery-engine.md](discovery-engine.md)).
11. Synchronisation backend (comptes, sauvegarde, multi-appareils idempotent).
12. Restauration des données synchronisées sur un nouvel appareil.
13. Tests principaux offline/online, scénarios de déplacement, et tests sur appareil Android physique réel (arrière-plan, batterie) — voir [discovery-engine.md](discovery-engine.md) et le détail dans l'ordre des incréments ci-dessous.

**Explicitement hors du premier MVP** (mais l'architecture ne doit pas empêcher leur ajout) :
Certified complet, leaderboard, fonctions sociales, achievements, système avancé de souvenirs, imports historiques complets avec preuve, avatar complexe, bio/liens externes, classement mondial/pays, Sign in with Apple réellement implémenté sur Android.

## Ordre recommandé des incréments de développement

1. **Scaffolding** : structure des modules, thème clair/sombre/système, navigation à 4 onglets (vides), squelette `/docs` (ce dossier). Pas de CI à ce stade (voir étape 4).
2. **Auth** : interface `AuthProvider`, Google Sign-In (client + vérification serveur), backend minimal (FastAPI, Postgres, table `users`, sessions/JWT), écran login/logout.
3. **Permissions + capture brute** : demande des permissions de localisation premier plan/arrière-plan, service de capture GPS écrivant dans le buffer brut local (vérifié via une vue de debug, pas encore la carte finale).
4. **Moteur v0 (Normal, local uniquement)** : conversion vers la représentation canonique qui sera validée dans [discovery-engine.md](discovery-engine.md) (structure et résolution H3 non fixées ici). Toute heuristique sol/transport touchant une décision métier encore ouverte doit être proposée avec options avant implémentation (voir `CLAUDE.md`) puis clairement marquée comme placeholder isolé — jamais présentée comme règle produit définitive. Écriture dans `discovery_cells(mode=normal)` (modèle conceptuel, voir [architecture.md](architecture.md) §6), purge du buffer brut après traitement, tests unitaires sur scénarios simulés (immobile, marche, voiture, avion). **CI de base** (build + tests automatisés) mise en place à partir de cette étape, une fois le projet Android local fonctionnel — pas en précondition de l'étape 1.
5. **Carte** : rendu illustré des cellules découvertes, pourcentage mondial v0 — règle simplifiée admissible uniquement si explicitement documentée et validée avant implémentation, sans trancher silencieusement `UNKNOWN`, les frontières/territoires contestés ou toute autre décision ouverte (voir [discovery-engine.md](discovery-engine.md)) —, zoom minimal Monde→Pays.
6. **Backend sync** : table `discovery_cells` (PostGIS), endpoints push (upsert idempotent) et pull par curseur, enregistrement des appareils.
7. **Sync client** : pattern outbox local, worker de synchronisation en arrière-plan, test hors-ligne (mode avion) puis reprise, restauration complète sur nouvel appareil.
8. **Validation de bout en bout** : test multi-appareils sur un même compte (aucun double comptage), redémarrage de l'app, ensemble des scénarios de test indispensables ([discovery-engine.md](discovery-engine.md)), et **tests sur appareil Android physique réel** couvrant notamment : tracking avec écran éteint, application en arrière-plan, déplacement réel, périodes prolongées, perte puis reprise du réseau, redémarrage de l'application/appareil lorsque pertinent, restrictions Android d'exécution en arrière-plan, comportement avec les mécanismes d'économie d'énergie, et impact sur la batterie. Objectif : observer et mesurer le comportement réel avant de considérer le tracking de fond comme validé — aucun seuil chiffré définitif de consommation batterie n'est fixé à ce stade.

À l'issue de l'étape 8, le moteur de découverte Normal est fonctionnel et synchronisé — socle sur lequel viennent ensuite les incréments V1/V2/V3 ci-dessous.

## V1 (fast-follow immédiat après le MVP)

- Export des données et suppression de compte (requis avant toute mise en production réelle).
- Navigation géographique complète Monde → Continent → Pays → Région → Zone → Lieu.
- Écran « New Discoveries » avec animation au retour de voyage.
- Ajout manuel en mode Normal avec preuve (photo géolocalisée, GPX, justificatif) ; un indicateur de confiance/provenance/qualité de preuve pourra éventuellement être affiché, sa forme exacte restant à définir (voir [product-spec.md](product-spec.md)).
- Gestion des appareils/sessions connectés (révocation).
- Procédures sécurisées : changement d'e-mail, ajout de moyens de récupération facultatifs (téléphone, e-mail secondaire).
- Politique de confidentialité et analyse des obligations applicables, en vue d'une publication réelle.

## V2

- **Mode Certified complet** : module serveur séparé, validation par événements, signaux d'intégrité (dont Play Integrity), machine à états complète, score recalculable (voir [certified-mode.md](certified-mode.md)).
- Suite de tests anti-triche (localisation simulée, téléportation, horloge manipulée, replay, base locale modifiée) avant toute activation compétitive.
- Partage : carte partageable réseaux sociaux, profil public optionnel (Certified, pays/régions, sans localisation précise).
- Achievements (une fois le moteur stabilisé).
- Import d'historique avancé, souvenirs attachés aux lieux.
- Extension du profil : biographie courte, liens externes optionnels.
- Renforcement progressif du Certified (nouveaux signaux, sans réécrire le produit).

## V3 / vision long terme

- Client iOS (Sign in with Apple activé pour de vrai, backend confirmé comme source de vérité multi-clients).
- Classements publics — mondial et par pays — basés exclusivement sur Certified, niveau Standard initialement, une fois l'anti-triche jugé suffisamment robuste.
- Éventuel classement Certified Precision (jamais mélangé avec Standard dans un même classement).
- Fonctions sociales élargies (comparaison entre amis, etc.), avec blocage/signalement/modération définis avant mise en production si de véritables interactions sociales sont ajoutées.
- Gamification plus riche.
- Éventuelle monétisation simple (le score Certified reste non achetable).
- Synchronisation multi-appareils simultanés affinée.

## Principe à préserver à chaque incrément

Chaque fonctionnalité suit le cycle : issue → branche → modification → tests → revue → merge. Le cahier des charges et les décisions d'architecture restent dans `/docs` afin que toute nouvelle contribution (humaine ou IA) puisse reprendre le contexte sans avoir à le redécouvrir.
