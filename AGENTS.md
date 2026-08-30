# AGENTS.md — World Discovery

World Discovery est un projet existant, fonctionnel par parties. Ne jamais repartir de zéro ni déduire son état du seul nom des modules.

## Sources de vérité

Pour déterminer ce qui est réellement implémenté, utiliser dans cet ordre :

1. code, tests et historique Git ;
2. `PROJECT_STATUS.md`.

Pour les décisions produit, intentions, règles futures et éléments non implémentés :

1. lire `docs/ai-context/README.md` ;
2. consulter le document spécialisé pertinent dans `docs/ai-context/` ;
3. suivre ses références vers les spécifications historiques/normatives suivies par Git.

Ne jamais supposer qu'une fonctionnalité `PLANNED` ou `DECIDED / NOT IMPLEMENTED` existe déjà. Utiliser `PROJECT_STATUS.md` et `docs/ai-context/ROADMAP.md` pour connaître l'état et la prochaine phase.

## Avant de poser une question à l'utilisateur

Rechercher d'abord la réponse dans :

- `PROJECT_STATUS.md` ;
- `docs/ai-context/` ;
- les documents normatifs référencés ;
- le code, les tests et Git lorsque pertinent.

Ne pas demander de redéfinir une décision déjà documentée. Interroger l'utilisateur seulement si une décision est réellement classée `NEEDS USER CONFIRMATION` ou si une ambiguïté subsiste après consultation des sources.

`ENGINEERING DESIGN REQUIRED` signifie qu'une conception technique est nécessaire, pas automatiquement une décision utilisateur. `CALIBRATION REQUIRED` signifie qu'une valeur doit être mesurée/testée, pas automatiquement décidée par l'utilisateur.

## Modification du projet

- Inspecter toute implémentation existante avant de la modifier ou la remplacer.
- Préserver les fonctionnalités existantes sauf demande explicite.
- Faire des changements petits, ciblés, réversibles et limités aux fichiers concernés.
- Ne pas modifier de fichiers sans rapport avec la tâche ; préserver les changements utilisateur présents.
- Examiner `git diff` avant de considérer une tâche terminée.
- Ne jamais introduire de secret, token, mot de passe, clé privée ou credential sensible dans le dépôt, la documentation, les logs ou le code généré.

## Validation

Après une modification significative :

- exécuter les tests pertinents ;
- exécuter le build pertinent lorsque nécessaire ;
- indiquer clairement ce qui a été testé et ce qui ne l'a pas été ;
- ne jamais présenter une validation sur appareil physique comme effectuée si elle ne l'a pas réellement été.

Pour les comportements Android qui exigent un téléphone réel, demander une validation utilisateur uniquement lorsqu'elle est réellement nécessaire.

## Approches rejetées

Avant toute modification importante d'architecture ou de règle métier, lire `docs/ai-context/REJECTED_APPROACHES.md`.

Ne pas réintroduire une approche abandonnée sans justification explicite, analyse de ses conséquences et décision appropriée.

## Git

- Baseline précédente : `7a906a9fadb2f41129e3b5d326c634d11337ebfb`.
- Commit de la mémoire persistante validée : `1e22ed4ab17918fe1d14739fc792d198d5e1c350`.
- Ne jamais commit ou push automatiquement, sauf demande explicite de l'utilisateur.
- Vérifier l'état Git et le périmètre exact avant toute opération d'indexation ou de commit.

## Continuité de la mémoire

Lorsqu'une décision produit importante est prise ou qu'une règle existante change, identifier le document spécialisé approprié dans `docs/ai-context/` et proposer sa mise à jour afin de préserver la décision pour les futures sessions et agents.

Lorsque l'état réel du projet évolue significativement, proposer également la mise à jour de `PROJECT_STATUS.md` pour qu'il reste cohérent avec le repository.
