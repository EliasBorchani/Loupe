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

## Reste

**La notarisation Apple.** Elle demande un compte Apple Developer, un certificat *Developer ID
Application* et un mot de passe spécifique à l'application ; rien de tout cela ne peut être fait
depuis ici. Sans elle, le `.dmg` s'ouvre après un clic droit → Ouvrir, ou un
`xattr -d com.apple.quarantine`. [Conveyor](https://conveyor.hydraulic.dev) est gratuit pour
l'open source et gère signature, notarisation et mises à jour — c'est le chemin le plus court quand
le moment viendra.

**Pousser sur GitHub.** Le dépôt est prêt, MIT, `main`, aucun remote.
