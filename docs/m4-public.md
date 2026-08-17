# M4 — La publication

Trois profils supplémentaires, l'intégration continue, et les identifiants de publication.
**121 tests.**

---

## Les profils, et ce qu'ils ont cassé

Chacun a été choisi pour exercer un chemin que le profil Withings ne touche pas. C'était le but
annoncé au M1 — « le repli du compilateur de timestamp n'a qu'un test aujourd'hui » — et il a
effectivement trouvé deux bugs.

| Profil | Priorité | Ce qu'il exerce |
|---|---:|---|
| `withings-healthmate` | 50 | Groupe optionnel, continuations indentées, tags R8 |
| `android-logcat` | 40 | **Horodatage sans année**, sept niveaux, aucune continuation |
| `syslog-rfc3164` | 30 | **Mois nommé, jour cadré à l'espace, aucun niveau du tout** — le seul à prendre le repli `DateTimeFormatter` |
| `generic-timestamped` | 0 | **Millisecondes optionnelles**, niveau optionnel, attrape-tout |

### Bug 1 — l'année inventée n'existait pas

`MM-dd HH:mm:ss.SSS` (logcat) et `MMM d HH:mm:ss` (syslog) ne portent pas d'année. Le chemin rapide
l'exigeait, et le repli `DateTimeFormatter` aurait de toute façon échoué : on ne construit pas un
`LocalDateTime` sans date complète.

L'année est désormais **assumée** — l'année courante par défaut, ce que fait tout autre visualiseur
de logcat et la seule supposition disponible — avec `assume_year` dans le profil pour une capture
archivée. Et surtout, **le chargeur le dit** : `assumesYear` remonte un avertissement, parce qu'un
outil qui invente une date sans le signaler ment.

Côté repli, `parseDefaulting(ChronoField.YEAR, …)` fournit ce que le motif ne porte pas.

### Bug 2 — un groupe plus court que sa mise en page lisait n'importe quoi

`generic-timestamped` a des millisecondes optionnelles : `(?:\.\d{3})?`. Le groupe capturé fait donc
19 ou 23 caractères. Le lecteur rapide lit à des offsets fixes — il allait donc chercher les
millisecondes à l'offset 20 d'un groupe qui s'arrête à 19, et **appelait nombre ce qui suivait dans
la ligne**.

Un `slot.offset + slot.width > available` suffit à corriger : une queue absente laisse son champ à
zéro au lieu de produire une valeur inventée. C'est exactement le genre de bug qui ne plante pas et
donne des horodatages faux.

### Ce qui n'est pas livré, et pourquoi

**`json-lines`.** Un profil JSON piloté par regex ne marcherait que pour un ordre de clés donné, et
se tromperait silencieusement sur tous les autres — sans parler des objets imbriqués et des
guillemets échappés. Un JSON correct demande un extracteur de champs, pas une expression régulière.
Livrer un profil qui ment sur trois formats pour en lire un est pire que ne rien livrer : c'est
reporté avec sa raison.

### L'attrape-tout ne vole pas la détection

`generic-timestamped` reconnaît volontiers une ligne Withings ou logcat. La détection trie par
score puis départage par **priorité**, donc un format qui décrit vraiment le fichier gagne toujours,
et l'attrape-tout ne prend la main que si personne d'autre ne comprend rien. Son `min_match` est
aussi plus strict (0,90) : un fallback qui reconnaît quatre lignes sur cinq d'un format qu'il ne
comprend pas est pire que l'aveu d'ignorance.

Deux tests pinnent la propriété dans les deux sens.

---

## `⌘F` / `⌘L`

Le dernier raccourci du M3. Posé sur la racine de la fenêtre et non sur la liste, parce que
« chercher » doit marcher d'où qu'on soit — et strictement limité à ces deux touches, pour que tout
le reste atteigne la liste.

---

## Intégration continue

`.github/workflows/build.yml`, sur Linux : rien ici n'ouvre de fenêtre. L'état est testé sans
Compose et les parseurs sont du Kotlin ordinaire ; la seule chose qui réclame vraiment un Mac est
`packageDmg`, qui est une étape de release et pas de push.

Le job **échoue sur un avertissement du compilateur**. Une dépréciation est une petite tâche
maintenant et une migration plus tard ; autant qu'elle se voie tout de suite plutôt que de
s'accumuler derrière un mur de bruit.

---

## Identifiants

`group = "io.github.eborchani"`, bundle id `io.github.eborchani.loupe`. Une coordonnée
`io.github.` ne demande rien d'autre que le compte GitHub, là où `dev.loupe` revendiquerait un
domaine. Les paquets Kotlin restent `dev.loupe.*` — ce sont des noms, pas des revendications — et
c'est une ligne à changer si `loupe.dev` est un jour acquis.

---

## Les profils tiers étaient promis, pas branchés

`ProfileRegistry.fromDirectory` existait depuis le M1 et **personne ne l'appelait**. Pire, le
message d'erreur quand aucun profil ne reconnaît un fichier disait déjà « Add one to
`~/.loupe/profiles/` and reopen » — il promettait une fonctionnalité inexistante.

C'est branché : `~/.loupe/profiles/*.logprofile.toml`, relu **à chaque ouverture** pour qu'écrire un
profil ne demande pas de redémarrage — c'est tout le déroulé quand on en écrit un pour un format que
personne n'a décrit.

Deux décisions de conception :

- **`core` ne lit jamais le répertoire personnel, l'app oui.** `LogSourceLoader` prend par défaut le
  registre embarqué, et `LoupeState` lui passe `bundledPlusUser()`. Sinon chaque test dépendrait
  silencieusement de ce qui traîne dans `~/.loupe/profiles` sur la machine qui l'exécute.
- **Un profil cassé est signalé, jamais fatal.** Quelqu'un qui en écrit un a une erreur de syntaxe
  une fois sur deux ; refuser d'ouvrir quoi que ce soit casserait exactement la tâche que la
  fonctionnalité sert. Les échecs remontent dans le panneau de diagnostic, à côté des lignes non
  reconnues — c'est le même « pourquoi ça ne marche pas ».

La documentation publique du format est dans [`profiles.md`](profiles.md).

## L'icône

`desktop/icon.svg` (détaillée) et `desktop/icon-small.svg` (16 et 32 px), assemblées en `.icns` par
`tools/render-icon.sh`.

Le concept est la thèse du produit en une image : hors de la loupe, un log est un mur de lignes
indifférenciées ; dedans, les mêmes lignes se résolvent en colonnes, et **exactement deux** portent
une couleur — parce que c'est la règle de l'app, où colorer tous les niveaux revient à n'en colorer
aucun. **Pas de manche** : une loupe d'horloger n'en a pas, et c'est ce qui évite le glyphe de
recherche générique que tout le monde utilise déjà.

Deux dessins, parce que **le détaillé ne survit pas à 16 px** : la page derrière la loupe devient du
bruit, la colonne de métadonnées fusionne avec le message, et l'ambre et le rouge se moyennent en
boue. La variante ne garde que ce qui se lit à cette taille — trois barres, pas de page, et un
cerclage à 8 % de la largeur au lieu de 4 % pour qu'il fasse encore plus d'un pixel.

Rasterisation par Chrome headless faute de rasteriseur SVG installé, puis `sips` pour les
réductions. Deux pièges, tous deux dans le script : Chrome pointé sur un `.svg` à dimensions
intrinsèques **recadre** au lieu de mettre à l'échelle (d'où l'enrobage HTML), et il **plafonne**
une fenêtre sous ~50 px (d'où le rendu unique en 1024 suivi de `sips`).

## La barre de menus

`LoupeMenuBar.kt`. Avec `apple.laf.useScreenMenuBar`, elle atterrit en haut de l'écran, là où un
utilisateur Mac la cherche — et surtout là où une fonction se **découvre** : l'export et l'ajout de
profil existaient déjà, aucun des deux n'était trouvable sans qu'on vous le dise.

| Menu | |
|---|---|
| **File** | Open… ⌘O · Add Files… ⇧⌘O · Export Current Filter… ⌘E · Close Log |
| **View** | Columns ⌘1 · Raw Line ⌘2 · Find ⌘F · Unrecognised Lines… |
| **Profiles** | Reveal Profiles Folder · New from Template ▸ · Reload Profiles and Reopen |

**Il n'y a délibérément pas de menu Edit.** Un raccourci de menu est capté par le menu natif avant
que la fenêtre ne voie la touche : mettre Copy sur ⌘C et Select All sur ⌘A là-haut casserait les
deux à l'intérieur du champ de requête — on sélectionnerait des lignes de log en croyant
sélectionner du texte. Ils restent des gestionnaires au niveau de la fenêtre, portés par la liste
qui les possède.

« New from Template » copie un profil livré plutôt que de créer un fichier vide : ils sont
abondamment commentés, et la façon la plus rapide de décrire un format est d'en éditer un qui
marche. La copie est renommée dans le fichier aussi, sinon l'original et elle répondent au même nom
et la détection a deux candidats indiscernables.

## Un lancement qui mentait

Le bloc `run { workingDir = rootProject.projectDir }` n'a **jamais** été committé : `tasks.named("run")`
échoue à la configuration, parce que le plugin Compose enregistre sa tâche `run` après l'évaluation
de ce fichier. Conséquence : `--args="spike/fixtures/folder"` se résolvait contre `desktop/`, ne
pointait sur rien, et `main` filtrait le chemin inexistant **en silence** — l'app s'ouvrait vide,
ce qui ressemble exactement à un lancement réussi.

Corrigé des deux côtés : `tasks.withType<JavaExec>().configureEach` ne dépend pas de l'ordre
d'enregistrement, et un chemin inexistant est désormais **signalé sur stderr** au lieu d'être
écarté. Une entrée invalide qui ne dit rien coûte plus cher qu'une qui échoue.

## Reste

**La notarisation Apple.** Elle demande un compte Apple Developer, un certificat *Developer ID
Application* et un mot de passe spécifique à l'application ; rien de tout cela ne peut être fait
depuis ici. Sans elle, le `.dmg` s'ouvre après un clic droit → Ouvrir, ou un
`xattr -d com.apple.quarantine`. [Conveyor](https://conveyor.hydraulic.dev) est gratuit pour
l'open source et gère signature, notarisation et mises à jour — c'est le chemin le plus court quand
le moment viendra.

**Pousser sur GitHub.** Le dépôt est prêt, MIT, `main`, aucun remote.
