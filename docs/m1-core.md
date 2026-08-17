# M1 — Le cœur générique

**Verdict : le moteur est réellement piloté par les profils, et la généricité coûte 6 %.**

Le M0 avait répondu « oui » avec un parseur codé en dur pour le format Withings. Le M1 remplace
ce parseur par un moteur qui ne connaît aucun format : tout vient d'un `*.logprofile.toml`.

---

## Ce qui est livré

| Brique | Fichier | Rôle |
|---|---|---|
| Spec de profil | `profile/LogProfileSpec.kt` | Le TOML tel qu'écrit, désérialisé, rien de compilé |
| Compilation + validation | `profile/CompiledProfile.kt` | Regex compilée, numéros de groupes, échelle de niveaux, prédicats. **Rapporte tous les problèmes d'un coup**, pas le premier |
| Numérotation des groupes | `profile/NamedGroups.kt` | `Matcher.start("nom")` re-résout le nom à chaque appel ; `Pattern.namedGroups()` n'existe qu'en Java 20. On scanne donc la source |
| Timestamps | `profile/TimestampFormat.kt` | Compile un motif à largeur fixe en offsets, avec repli `DateTimeFormatter` |
| Prédicats de ligne | `profile/LinePredicate.kt` | Le pré-filtre du M0, dérivé du profil au lieu d'être écrit à la main |
| Échelle de niveaux | `profile/LevelDecoder.kt` | L'ordre déclaré **est** la sévérité ; c'est ce qui rend `level>=W` exprimable |
| Auto-détection | `profile/ProfileRegistry.kt` | Score par échantillon, jamais un choix silencieux |
| Parseur générique | `parse/ProfileEntryParser.kt` | Remplace la stratégie A du M0 |
| Index générique | `index/LogIndex.kt` | N colonnes de facettes déclarées, plus « catégorie + tag » en dur |
| Langage de requête | `query/QueryLexer.kt`, `query/QueryCompiler.kt` | La grammaire de la barre de requête → `EntryFilter` |

**56 tests**, dont chaque cas du format Withings rejoué contre les deux parseurs.

---

## La distinction qui fait marcher le pré-filtre générique

Le M0 avait établi que rejeter les lignes de continuation avant la regex est le levier principal
(18,6 % des lignes). Restait à le dériver d'un profil plutôt que de l'écrire à la main.

La première tentative a échoué de façon instructive : `entry.opens`
(`^\d{4}-\d{2}-\d{2} …`) ne se réduit pas à un préfixe littéral, donc il retombait sur « exécuter
la regex » — ce qui allouait une `String` par ligne **avant** que le parseur n'en alloue une
seconde. Strictement pire que pas de pré-filtre du tout. Le test de compilation du profil l'a
signalé immédiatement, en refusant un profil qui produisait un avertissement.

Le déblocage tient à une distinction que le code rend maintenant explicite :

- **`entry.continues` est sémantique.** Sa réponse décide si une ligne rejoint l'entrée du dessus
  ou tente d'en ouvrir une. Il doit signifier *exactement* ce que dit sa regex → `compileExact`,
  qui produit un préfixe littéral ou, à défaut, exécute la regex (avec avertissement).
- **`entry.opens` n'est qu'une optimisation.** La vraie regex passe juste après et tranche. Il
  suffit donc que ce soit une **condition nécessaire** : il peut accepter des lignes qui ne
  parseront pas, il ne doit jamais en rejeter une qui aurait parsé → `compileNecessary`, qui
  dérive des contraintes positionnelles (« position 0 est un chiffre, position 4 est un tiret… »)
  et s'arrête au premier motif qu'il ne comprend pas, en gardant ce qu'il a. Un préfixe partiel
  reste une condition nécessaire valide.

C'est exactement le `opensEntry` écrit à la main du M0, dérivé au lieu d'être codé. Un test vérifie
la propriété qui compte : sur un corpus mêlant toutes les formes, aucune ligne acceptée par la
regex complète n'est rejetée par le pré-filtre.

---

## Performance — la généricité ne coûte presque rien

1 GiB, 9 013 588 entrées, Apple M5 Pro, JDK 17, **une JVM par stratégie**.

| | ns/entrée | Chaud | Extrapolé à 5 M | Budget 5 s |
|---|---:|---:|---:|:---:|
| M0 — parseur regex codé en dur | 386 | 3,48 s | 1,93 s | ✅ |
| **M1 — parseur générique piloté par profil** | **411** | **3,71 s** | **2,06 s** | ✅ |
| Scanner d'octets (référence, non utilisé) | 152 | 1,37 s | 0,76 s | ✅ |

**+6 % pour une généricité complète** : numéros de groupes lus dans des tableaux, décodeur de
niveaux nullable, boucle sur N facettes. Le scanner d'octets est à 152 ns contre 157 au M0 —
inchangé, ce qui confirme qu'aucune régression ne s'est glissée dans le chemin partagé.

### Requêtes, 18 workers

| Requête | Résultats | 1 thread | Parallèle |
|---|---:|---:|---:|
| `level>=W` | 926 146 | 8,6 ms | **1,3 ms** |
| `category:Sync` | 2 105 017 | 30,1 ms | **3,1 ms** |
| `level>=W category:Sync since:-2h` | 5 484 | 9,9 ms | **1,2 ms** |
| `"connected"` | 899 314 | 527,3 ms | **40,7 ms** |
| `level>=W backoff` | 89 655 | 95,7 ms | **9,1 ms** |
| `-category:Ui level:E` | 200 293 | 7,1 ms | **0,8 ms** |
| `tag:~Session` | 479 190 | 20,3 ms | **1,9 ms** |

---

## Le piège de mesure, une deuxième fois

Mesurées **ensemble** dans une seule JVM, les deux stratégies donnaient 333 et 315 ns — le parseur
générique paraissait plus rapide que le codé-en-dur du M0, et le scanner deux fois plus lent
qu'au M0. Les deux chiffres étaient faux : faire transiter deux implémentations par les mêmes sites
d'appel les rend polymorphes et le JIT cesse de spécialiser. C'est le même phénomène qui avait
donné 613 ns à la stratégie B au M0.

Le harnais imprime désormais l'avertissement à chaque exécution multi-stratégies. **La règle : le
run combiné sert à la vérification croisée, jamais aux chiffres.**

---

## Bugs que les tests ont attrapés

- **`MMM` lu comme un mois à trois chiffres.** Le compilateur de timestamp acceptait n'importe
  quelle largeur de motif, donc `dd MMM yyyy` prenait le chemin rapide et lisait « Jul » comme le
  mois 3350. Les largeurs sont maintenant contraintes par lettre (`y` exactement 4, `M d H m s`
  exactement 2, `S` de 1 à 9) et tout le reste bascule sur le repli.
- **Le repli dépendait de la locale de la machine.** `DateTimeFormatter.ofPattern` sans locale
  refusait « Jul » sur une machine en français. Les logs sont écrits par des programmes :
  `Locale.ROOT`.
- **Offsets décalés par les littéraux entre quotes.** `'T'` dans `yyyy-MM-dd'T'HH:mm:ss` occupe
  trois caractères de motif et un de texte ; compter en caractères de motif décalait tous les
  champs suivants de chaque timestamp ISO-8601.
- **Interning d'une valeur non-ASCII.** Le chemin octets comparait des octets à des `char`, donc
  une facette contenant un caractère multi-octets était ré-internée à chaque occurrence — une
  entrée de facette dupliquée, pas un crash.

---

## Dette assumée pour le M2

- `MappedText` plafonne à 2 GiB (un seul mapping) — segmentation à écrire.
- Pas encore de fusion multi-fichiers ; les marqueurs de section sont comptés mais ne deviennent
  pas encore une facette `source`.
- `[fields.x] values` sert à la validation et à l'ordre, mais ne signale pas encore une valeur
  hors-liste à l'utilisateur.
- Un seul profil livré. `android-logcat`, `json-lines`, `syslog` et `generic-timestamped` sont
  attendus au M4 — et chacun exercera le repli du compilateur de timestamp, qui n'a aujourd'hui
  qu'un test.

## Reproduire

```bash
./gradlew :core:test                   # 56 tests
./gradlew :spike:run --args="1g A"     # parseur générique, JVM propre — les chiffres ci-dessus
./gradlew :spike:run --args="1g C"     # scanner de référence
./gradlew :spike:run --args="1g"       # les deux + vérification croisée (pas pour les timings)
```
