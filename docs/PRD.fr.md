# PRD — Loupe

**Visionneuse de logs structurés pour macOS.**

> Doc de travail en français. Le repo public (README, docs, profils) sera en anglais — traduction au M4.
> Repo personnel, licence **MIT**, public dès le départ.
>
> **État : M0, M1 et M2 terminés** — voir [`m0-perf-spike.md`](m0-perf-spike.md),
> [`m1-core.md`](m1-core.md) et [`m2-ui.md`](m2-ui.md). Prochain jalon : M3 (produit).

---

## 1. Contexte

Le débogage des logs de l'app Withings HealthMate se fait aujourd'hui :

- **sur le téléphone**, via `LogViewerActivity` (`HealthMate/src/main/kotlin/com/withings/wiscale2/logs/ui/LogViewerActivity.kt`) — écran 6", filtres par catégorie + grep + date, mais on lit un fichier journalier entier dans une `List<String>` et on `filter { contains }` dessus : ça tient sur un log de dev, pas sur une session longue ;
- **sur le poste**, à coups de `grep` / Sublime sur le fichier exporté — on perd toute la structure que le logger a pourtant écrite (niveau, catégorie, tag, timestamp).

Le format produit est **déjà structuré** : `FileLogger.LineFormat.render` écrit `timestamp [L] [Category]? [tag] -> message`. Cette structure est jetée à la lecture. Tout l'objet du produit est de la récupérer et d'en faire des **facettes** : cocher `Sync` + `level ≥ W` + une fenêtre de 10 minutes, au lieu d'enchaîner trois `grep | grep | grep`.

Le besoin immédiat est Withings. Mais rien dans ce problème n'est spécifique à Withings : **n'importe quelle app écrit un log ligne-par-ligne avec un timestamp, un niveau et un ou deux tags.** D'où l'ambition d'en faire un produit générique open source, piloté par des **profils de format déclaratifs**.

**Résultat attendu** : ouvrir un dossier de logs, voir en 5 secondes la forme du fichier (histogramme des niveaux, top catégories, densité temporelle), et converger sur les ~20 lignes qui expliquent le bug.

---

## 2. Utilisateurs et cas d'usage

| # | Utilisateur | Situation | Ce qu'il fait aujourd'hui |
|---|---|---|---|
| U1 | Dev Android Withings | Un user rapporte « la synchro ne remonte pas mes pas ». Il a le bundle de logs. | `grep -i sync` puis scroll à l'aveugle |
| U2 | Dev Android Withings | Il reproduit en local, pull le fichier du jour, veut voir *seulement* `Wpp` + `BleNetwork` autour du moment de l'appairage | Écran in-app, sur le téléphone |
| U3 | Dev backend / QA | Reçoit un extrait Discourse, veut juste isoler les erreurs | Ctrl+F dans le navigateur |
| U4 | **Dev quelconque (GitHub)** | Un log applicatif maison, format perso | `less` + regex, ou lnav s'il connaît |

**Scénario nominal (U1)** — le fil rouge du produit :

1. Glisser le dossier `logs/` (7 fichiers journaliers) sur l'app.
2. Le profil `withings` est auto-détecté ; 482 391 entrées indexées en 2 s ; la barre de densité montre un pic à 14:32.
3. Sidebar : `Level` → cocher `W` + `E` (1 204 + 120). Reste 1 324 entrées.
4. `Category` → `Sync` (901). La barre de requête affiche `level>=W cat:Sync`.
5. Brosser le pic de 14:32 sur la timeline → `since:14:30 until:14:35`. Reste 38 entrées.
6. Cliquer une entrée : panneau détail avec le message complet, la stack trace dépliée, le fichier source, ± 20 lignes de contexte *non filtré*.
7. Copier la sélection filtrée, la coller dans le ticket.

---

## 3. Différenciation

L'existant, honnêtement :

| Outil | Ce qu'il fait bien | Ce qui manque |
|---|---|---|
| `grep` / `rg` | Rapide, universel | Aucune structure, aucun contexte temporel |
| **lnav** | Le plus proche : détection de format, SQL sur les champs | TUI, définitions de format en JSON à écrire à la main, courbe d'entrée raide |
| klogg / glogg | Grep GUI très rapide sur gros fichiers | Ne comprend rien à la structure — pas de niveau, pas de facette |
| Console.app | Natif | Logs système uniquement |
| Logcat (Android Studio) | Facettes niveau/tag | Uniquement logcat, uniquement un device branché, pas un fichier |
| Datadog / Grafana Loki | Facettes + timeline (le bon modèle !) | Serveur, ingestion, coût — absurde pour un fichier de 40 Mo sur un bureau |

**Le trou** : personne ne fait *« l'expérience Datadog, en local, sur un fichier, sans serveur »*. C'est la promesse.

Trois choses qu'on fera et que les autres ne font pas :

1. **Le scan produit un profil du fichier, pas juste un parse.** Avant de lire une ligne, on voit la distribution des niveaux, les catégories triées par volume, la densité temporelle. On sait où regarder.
2. **La timeline est un filtre**, brossable — pas une colonne.
3. **Les profils de format sont des fichiers versionnables.** Une équipe commite `withings.logprofile.toml` à côté de son code ; tout le monde a le même viewer. C'est le vecteur d'adoption : un profil contribué = un utilisateur acquis.

---

## 4. Le format Withings (source de vérité pour le profil livré)

Relevé dans `util/utilslegacy/src/main/java/com/withings/util/log/FileLogger.kt` :

```
<yyyy-MM-dd HH:mm:ss.SSS> [<L>] [<Category>]? [<tag>] -> <message>
```

Points durs, chacun devient une exigence produit :

| Fait | Conséquence |
|---|---|
| Le groupe `[Category]` est **absent** sur les overloads dépréciés et sur `report`/`reportOrCrash` | Le profil doit gérer un **groupe de capture optionnel** correctement, sans décaler le tag |
| `report` → `[E] [ERROR] -> …`, `reportOrCrash` → `[E] [CRASH] -> …` : `ERROR`/`CRASH` occupent le slot **tag** | Pas de niveau `CRASH` : c'est un `[E]` avec un pseudo-tag. Le profil peut le promouvoir en facette dédiée |
| Message multi-ligne et stack traces : chaque `\n` est remplacé par `\n` + **23 espaces** (= `timestamp.length`) | **Une entrée ≠ une ligne.** Le filtrage porte sur l'entrée ; une entrée qui matche s'affiche en entier |
| `-> ` peut réapparaître dans le message (`steps: 100 -> 250`) | Ancrer sur le **premier** `-> `. Le regex ci-dessous le garantit (`[^\]]*` ne peut pas franchir un `]`) |
| En release, le tag est le FQCN obfusqué R8 (`ou1`) | La facette `tag` explose en cardinalité → UI facette = top-N trié par volume + champ de recherche, jamais une liste plate |
| Fichiers nommés `2026-06-02`, **sans extension**, dans `cacheDir/logs/`, rétention 7 jours, aucune compression | Ouvrir un **dossier**, pas un fichier. Ne jamais filtrer sur l'extension. Le nom du fichier est une facette (`file:`) |
| L'export concatène les jours avec `=== <nom> ===` entre eux | Ces séparateurs sont des **marqueurs de section**, pas des entrées — à reconnaître et à transformer en facette source |
| Ligne `--- older lines dropped: only the last <N> KiB were scanned ---` | Marqueur `notice` : affiché, jamais silencieusement compté comme une entrée |
| 22 `LogCategory` (`AggregateComputation`, `Sync`, `Wpp`, `BleNetwork`, …), 5 niveaux `V D I W E` (ordre = sévérité) | Livrés en dur dans le profil : ordonne les facettes et rend `level>=W` possible |

Le profil complet est en **annexe A** — il est directement utilisable et sert de spec d'exemple.

---

## 5. Décisions actées

| Sujet | Décision |
|---|---|
| **Stack** | **Compose Multiplatform (Kotlin/JVM)**, cible macOS. Le langage de l'équipe ; la machine à états de `LogViewerViewModel` se reporte quasi telle quelle. |
| **Sources V1** | **Fichiers sur disque** uniquement (fichier, dossier, glisser-déposer, `.zip`/`.gz`). Live tail / `adb logcat` / presse-papier → v1.1. |
| **Filtrage** | **Facettes + barre de requête**, couplées : tout clic de facette écrit dans la barre. L'utilisateur apprend la syntaxe sans lire de doc. |
| **Reconnaissance du format** | **Profils déclaratifs TOML + auto-détection par score** sur un échantillon. Pas d'auto-inférence magique en V1 ; l'assistant de création de profil est un candidat v1.2. |

---

## 6. Périmètre

### V1 — ce qui livre le besoin réel

- Ouvrir fichier / dossier / archive ; glisser-déposer ; fichiers récents.
- **Multi-fichiers fusionnés en un seul flux ordonné par timestamp**, avec facette `file`.
- Auto-détection de profil parmi les profils livrés (`withings`, `android-logcat`, `json-lines`, `syslog`, `generic-timestamped`) ; sélection manuelle possible ; profils utilisateur dans `~/.loupe/profiles/`.
- Sidebar de facettes : valeurs découvertes au scan + compteurs, triées par volume, recherche dans les valeurs, tout cocher / tout décocher.
- Barre de requête (grammaire §7) avec autocomplétion des champs et des valeurs.
- Recherche plein texte, surlignage des occurrences (portage direct de `String.highlighting()`).
- **Bande de densité temporelle** brossable, colorée par niveau.
- Liste virtualisée, coloration par niveau, tri asc/desc, repli/dépli des stack traces.
- Panneau détail d'une entrée : champs parsés, message complet, **contexte ± N lignes non filtré**, copie.
- Copier la sélection / exporter le filtre courant en `.txt`.
- Indicateur de santé du parse : `4 812 / 4 815 lignes reconnues` — cliquable pour voir les 3 orphelines.
- Thème clair/sombre, police monospace, raccourcis clavier (`⌘F`, `⌘L` focus requête, `j/k`, `⌘↵` détail).

### v1.1

Live tail d'un fichier qui grandit · `stdin` / `adb logcat |` · coller depuis le presse-papier (avec dé-échappement HTML pour les extraits Discourse) · signets et annotations · requêtes sauvegardées · `loupe` CLI (même cœur, sortie texte, utilisable en CI).

### v1.2+

Assistant visuel de création de profil (surlignage live des captures, taux de reconnaissance en direct) · diff de deux logs · builds Linux/Windows · `adb pull` intégré.

### Non-objectifs — explicites

Pas de serveur, pas d'ingestion cloud, pas de compte. Pas de détection d'anomalie par ML. Pas d'édition de log. Pas de support de formats binaires (protobuf, `.logcat` binaire) en V1.

---

## 7. Grammaire de la barre de requête

`ET` implicite entre les termes. Chaque clic de facette écrit ou retire un terme.

```
level>=W                 comparaison (champs à ordre déclaré)
level:E,W                énumération — OU dans le champ
cat:Sync                 égalité, insensible à la casse
cat:Sync,Wpp             OU
-cat:Ui                  négation
tag:~Aggregate           « contient »
file:2026-06-02          facette source
"exact phrase"           plein texte
/timeout|refus/          regex
since:14:30  until:14:35 fenêtre absolue
since:-2h                fenêtre relative — au dernier timestamp du fichier, pas à maintenant
```

> `since:-2h` relatif à la **fin du log** et non à l'heure courante : on lit presque toujours un log post-mortem. C'est le genre de détail qui fait la différence entre un outil qu'on garde et un qu'on désinstalle.

---

## 8. Architecture

```
loupe/
├─ core/        Kotlin/JVM pur. Profils, indexation, requêtes. Zéro Compose. C'est ici que vivent les tests.
├─ profiles/    *.logprofile.toml livrés
├─ desktop/     Compose Multiplatform → .dmg
├─ cli/         (v1.1) même core
└─ docs/
```

### Le point critique : **ne pas porter le pipeline Android**

`LogViewerViewModel` fait `readLogsForDay → List<String>` puis `lines.filter { it.contains(q) }`. À 5 M d'entrées c'est mort — allocations, GC, latence. Le cœur desktop est **colonnaire** :

**Passe d'indexation** (une lecture séquentielle, hors thread UI, avec progression) :

- Le fichier est **mappé en mémoire** (`MappedByteBuffer`, segmenté — la JVM plafonne à 2 Gio par buffer). **Le texte n'est jamais matérialisé en `String`.**
- Chaque ligne est classée : *ouvre une entrée* / *continuation* / *marqueur* / *non reconnue*.
- Par entrée, on écrit dans des tableaux primitifs parallèles :
  `LongArray` timestamps · `ByteArray` niveaux (ordinal de sévérité) · un `IntArray` par facette (**dictionnaire encodé**) · `LongArray` offset + `IntArray` longueur (couvrant toutes ses lignes) · `IntArray` id de fichier source.
- Compteurs de facettes et histogramme temporel (2 000 buckets × niveau) incrémentés **pendant** la passe — gratuits.

Coût mémoire : ~33 octets/entrée → **5 M d'entrées ≈ 165 Mo**, texte exclu (il reste dans le mapping, à la charge de l'OS).

**Filtrage** : prédicat sur les colonnes → écrit les index matchés dans un `IntArray` réutilisé, zéro allocation par entrée, parallélisé sur `Dispatchers.Default`. Le plein texte se fait en **octets, directement dans le buffer mappé**, sur l'intervalle de l'entrée (repli ASCII pour l'insensibilité à la casse) — jamais via `String.contains`.

**Rendu** : `LazyColumn` sur l'`IntArray` de résultats ; chaque ligne visible décode ses octets à la demande (~40 lignes à l'écran).

### Ce qui se réutilise réellement de `LogViewerActivity`

- La forme de la machine à états : `combine(source, query.debounce, sort, facets)` produisant un `FilterResult` **qui embarque les entrées pour lesquelles il a été calculé** — c'est ce qui permet le `isLoading` honnête (« l'affichage est en retard sur ce que tu as demandé »). Bonne idée, à garder telle quelle.
- Le debounce à 0 ms quand la requête se vide.
- `String.highlighting(query)` → `AnnotatedString` (l.444).
- Le mapping niveau → couleur (l.437) et l'UX de la feuille de facettes.
- L'action « copier N lignes » avec plafond.

### Cibles de performance (critères d'acceptation)

| Métrique | Cible | Mesuré au M0 (9,01 M entrées, 1 GiB, M5 Pro) |
|---|---|---|
| Indexation | < 5 s | **3,48 s** pour 9,01 M → 1,93 s à 5 M ✅ |
| Ré-application d'un filtre par facette | < 100 ms | **1,3 – 6,4 ms** (18 workers) ✅ |
| Recherche plein texte | < 500 ms | **116 ms** parallèle · 659 ms mono-thread ✅ |
| RSS | < 500 Mo | index **258 MiB** à 9,01 M → ~143 MiB à 5 M ✅ |
| Scroll | 60 fps constant | pas encore d'UI — M2 |
| Démarrage à froid → fenêtre | < 1,5 s | pas encore d'UI — M3 |

Chiffres complets, protocole et pièges de mesure : [`m0-perf-spike.md`](m0-perf-spike.md).

---

## 9. Risques

| Risque | Gravité | Réponse |
|---|---|---|
| ~~**`java.util.regex` sur 5 M de lignes**~~ | ~~Élevé~~ → **levé** | **Mesuré au M0 : 386 ns/entrée, soit 1,93 s pour 5 M — 2,6× sous le budget.** Le pré-filtre structurel (18,6 % des lignes rejetées en ~6 comparaisons d'octets) suffit ; ni `CharBuffer` ni scanner compilé ne sont nécessaires. Détail : [`m0-perf-spike.md`](m0-perf-spike.md). Reste à faire en M1 : dériver le pré-filtre du profil au lieu de le coder en dur. |
| `SelectionContainer` sur `LazyColumn` en Compose Desktop est notoirement instable | Moyen | Ne pas s'appuyer dessus : modèle de sélection de lignes maison (clic, `⇧`+clic, `⌘A`) + actions de copie explicites |
| Poids du `.dmg` et démarrage JVM | Moyen | `jpackage` avec runtime `jlink` trimmé (~60–80 Mo), AppCDS pour le démarrage. Assumé : c'est le prix de la stack retenue |
| **Signature + notarisation Apple** pour une distribution publique | Moyen | **Conveyor** (gratuit pour l'open source) gère signature, notarisation et mises à jour — bien plus praticable que du `jpackage` nu. Sinon : cask Homebrew, avec la friction `xattr` |
| L'auto-détection choisit le mauvais profil | Faible | Score = % de lignes reconnues sur un échantillon de 200 lignes ; profil affiché et changeable en un clic ; on ne détecte jamais en silence |
| Fichier modifié pendant la lecture (mmap) | Faible | Détecter le changement de taille/mtime, proposer la ré-indexation (et c'est la fondation du live tail v1.1) |

---

## 10. Jalons

| Jalon | Contenu | Sortie vérifiable |
|---|---|---|
| ~~**M0 — Spike perf**~~ **✅ fait** | Indexation colonnaire + mmap + regex sur un log Withings synthétique de 1 Gio | **Toutes les cibles tenues, sur un corpus 1,8× plus gros que la référence du §8.** Harnais maison plutôt que JMH (une passe de plusieurs secondes est du `SingleShotTime`, où JMH ajoute du cérémonial sans ajouter de signal) → [`m0-perf-spike.md`](m0-perf-spike.md) |
| ~~**M1 — Cœur**~~ **✅ fait** | Chargement de profil TOML, indexation, facettes, parseur de requête | **56 tests. Généricité complète pour +6 % (411 ns/entrée contre 386 au M0).** Le pré-filtre est désormais dérivé du profil → [`m1-core.md`](m1-core.md) |
| ~~**M2 — UI utilisable**~~ **✅ fait** | Fenêtre, ouverture de dossier, liste virtualisée, sidebar de facettes, barre de requête | **Les trois arbitrages tranchés et implémentés ; timeline et panneau de détail livrés en avance sur le M3.** 86 tests → [`m2-ui.md`](m2-ui.md) |
| **M3 — Produit** | Timeline, panneau détail, export, thèmes, raccourcis, santé du parse | `.dmg` installable, utilisé par l'équipe Android |
| **M4 — Public** | README anglais, doc du format de profil, 5 profils livrés, CI, licence, notarisation | Repo GitHub publié |

---

## 11. Nom, licence, distribution

**Loupe** — repo personnel, **MIT**, public dès le départ.

Collisions relevées, toutes dans des domaines étrangers au nôtre, donc assumées :

| Projet | Domaine | Impact |
|---|---|---|
| [GNOME Loupe](https://en.wikipedia.org/wiki/Loupe_(software)) — visionneuse d'images par défaut de GNOME 45+, Rust, `apt install loupe` | Bureau Linux, images | Le seul coût réel : **ne pas viser une formule `loupe` dans homebrew-core**. Cask dédié (`loupe-log`) ou tap perso. Aucun recouvrement sur macOS. |
| [loupe-php/loupe](https://github.com/loupe-php/loupe) — moteur de recherche plein-texte PHP/SQLite | Backend PHP | Bruit SEO marginal |

Les alternatives testées sont pires : **Strata** est déjà le nom du produit de logs de Palo Alto (*même domaine*), **Winnow** est la lib de parsing Rust successeur de nom, **Trawl** a deux projets GitHub actifs, **Prism** est écrasé par Prism.js. Tout mot anglais court est pris ; une collision hors-domaine est le meilleur cas atteignable.

Identifiants à figer au M0 :

- Repo : `github.com/<compte>/loupe`
- Group Gradle / bundle id : `dev.loupe` → à confirmer une fois le domaine vérifié, sinon `io.github.<compte>.loupe`
- Binaire CLI (v1.1) : `loupe`
- Cask : `loupe-log`

**Reste ouvert :** le profil `withings` livré ne contient que des noms de `LogCategory` (`Sync`, `Wpp`, `BleNetwork`, …) et une regex de format — rien de sensible. Une validation informelle côté équipe avant publication du M4 reste prudente.

---

## Annexe A — `profiles/withings.logprofile.toml`

```toml
name        = "withings-healthmate"
description = "Withings HealthMate Android — FileLogger"
priority    = 50

# Un fichier candidat sans extension nommé yyyy-MM-dd renforce le score.
[detect]
filename = '''^\d{4}-\d{2}-\d{2}$'''
sample   = 200          # lignes testées
min_match = 0.80        # taux de reconnaissance minimal pour retenir le profil

[entry]
# Ce qui ouvre une entrée / ce qui la prolonge (23 espaces = timestamp.length).
opens     = '''^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \['''
continues = '''^ {23}'''
strip_continuation_indent = true

[parse]
# Appliqué à la LIGNE D'OUVERTURE seule ; les continuations sont concaténées
# au champ `message` après retrait de l'indentation. Pas de jointure avant match
# → une seule regex par entrée, pas par ligne.
#
# `[^\]]*` ne peut pas franchir un `]`, donc le premier ` -> ` fait foi :
#   « … [ComputeAggregateForDay] -> [a1b2c3d4] steps: 100 -> 250 »  parse correctement.
# Le groupe catégorie optionnel se résout seul par backtracking :
#   « [E] [ERROR] -> … »  →  category = null, tag = ERROR   ✓
regex = '''^(?<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[(?<level>[VDIWE])\] (?:\[(?<category>[^\]]*)\] )?\[(?<tag>[^\]]*)\] -> (?<message>.*)$'''

[fields.ts]
role   = "timestamp"
format = "yyyy-MM-dd HH:mm:ss.SSS"
zone   = "local"        # FileLogger écrit en heure locale de l'appareil

[fields.level]
role   = "level"
order  = ["V", "D", "I", "W", "E"]      # ordre = sévérité croissante → autorise level>=W
labels = { V = "Verbose", D = "Debug", I = "Info", W = "Warn", E = "Error" }
colors = { W = "warning", E = "error" }

[fields.category]
role  = "facet"
label = "Category"
# Contrainte de valeurs : ordonne la facette et signale une ligne malformée.
# Non requise pour lever l'ambiguïté (la regex s'en charge), mais utile en validation.
values = [
  "AggregateComputation", "Analytics", "Billing", "BleNetwork", "BodyBalance",
  "Database", "Datastore", "HealthConnect", "MeasurementPlan", "Measurements",
  "Migration", "Network", "Other", "Push", "SleepTrackComputation", "Sync",
  "Tls", "Ui", "Unknown", "Vo2Max", "Wpp", "Zendesk",
]

[fields.tag]
role  = "facet"
label = "Tag"
# Cardinalité élevée en release (FQCN obfusqué R8) → l'UI passe en top-N + recherche.
facet = "auto"
# Promeut les pseudo-tags de report()/reportOrCrash() en facette lisible.
derive = { field = "kind", map = { ERROR = "Reported", CRASH = "Crash" } }

[fields.message]
role = "message"

# Lignes qui ne sont pas des entrées : conservées, classées, jamais comptées comme du log.
[[markers]]
regex = '''^=== (?<source>.+) ===$'''
role  = "section"       # séparateur de l'export multi-jours → devient la facette `source`

[[markers]]
regex = '''^--- older lines dropped.*---$'''
role  = "notice"
```

---

## Annexe B — Vérification

- **M0 ✅** : `./gradlew :spike:run --args="1g"`. Générateur écrit, fixture mise en cache, résultats dans [`m0-perf-spike.md`](m0-perf-spike.md).
- **Correction du parse ✅** : `./gradlew :core:test` — 31 golden tests, chaque cas rejoué contre les trois stratégies : cas nominal, sans catégorie, `[E] [ERROR]`, `[E] [CRASH]`, message multi-lignes, stack trace, message contenant ` -> `, message ouvrant sur `[`, message vide, ligne `--- older lines dropped ---`, séparateur `=== … ===`, fuseau.
- **Bout en bout** : dérouler le scénario §2 sur un vrai dossier `logs/` récupéré par `adb pull` depuis un device de dev (`InternalSetting.WriteLogsInFile` activé), et sur un `healthmate_logs_export.txt` multi-jours.
- **Non-régression du repo Healthmate** : aucune. Le projet est **externe** — aucun fichier de ce dépôt n'est modifié.
