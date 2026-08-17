# M0 — Spike de performance

**Verdict : le design déclaratif tient. Le risque n°1 est levé.**

Une seule question était posée : un moteur générique piloté par des profils regex peut-il indexer
~5 M d'entrées en moins de 5 s sur la JVM, ou faut-il renoncer au déclaratif et compiler des
scanners à la main ? Réponse : la regex passe avec un facteur 2,6 de marge.

---

## Protocole

| | |
|---|---|
| Machine | Apple M5 Pro, 18 cœurs, 48 GiB |
| JVM | OpenJDK 64-Bit Server VM 17.0.19 (Homebrew), `-Xmx4g -XX:+UseG1GC` |
| Fixture | 1024 MiB générés, **9 013 588 entrées**, 11 066 525 lignes |
| | dont 2 052 937 lignes de continuation (**18,6 %**), 22 catégories, 817 tags distincts |
| Mesure | 3 passes par stratégie, **une JVM par stratégie**, on retient la meilleure passe chaude |

La fixture (`spike/src/.../LogFileGenerator.kt`) reproduit le format de
`FileLogger.LineFormat.render` et, surtout, ses cas pénibles : catégories à queue lourde, tags R8
obfusqués, messages contenant leur propre ` -> `, messages commençant par `[`, lignes sans
catégorie, pseudo-tags `ERROR`/`CRASH`, messages multi-lignes et stack traces ré-indentées de 23
espaces, plus 1 % de messages accentués pour exercer l'UTF-8.

**Une JVM par stratégie, et ce n'est pas un détail.** Mesurées ensemble, les trois se polluent : le
site d'appel `charAt` de `Matcher` devient bimorphe (`String` et `CharSequence` maison y transitent
toutes deux) et la stratégie B ressortait à 613 ns/entrée. Isolée, elle en fait 325. Le premier
chiffre était un artefact du harnais, pas une propriété de B.

---

## Indexation

| Stratégie | Chaud | ns/entrée | MiB/s | Extrapolé à 5 M | Budget 5 s |
|---|---:|---:|---:|---:|:---:|
| **A · `String` + `Pattern`** | 3,48 s | 386 | 295 | **1,93 s** | ✅ |
| **B · chars élargis + `Pattern`** | 2,93 s | 325 | 350 | **1,63 s** | ✅ |
| **C · scanner d'octets** | 1,41 s | 157 | 725 | **0,79 s** | ✅ |

Les trois produisent un index **strictement identique** — vérifié entrée par entrée sur les
9 013 588 entrées (timestamp, niveau, catégorie, tag, plage d'octets), plus 31 golden tests sur les
formes limites. Une stratégie rapide et fausse ne vaut rien ; la comparaison tourne dans le harnais.

## Mémoire

| | |
|---|---|
| Delta de tas mesuré | **258 MiB** pour 9,01 M entrées, soit **30,0 octets/entrée** (estimation initiale : 33) |
| Texte | hors tas — le 1 GiB reste dans le fichier, atteint par `(offset, longueur)` |
| Extrapolé à 5 M | ≈ **143 MiB** |
| Cible RSS < 500 Mo | ✅ |

## Filtrage

18 workers sur `Dispatchers.Default`, sur les 9,01 M entrées.

| Requête | Résultats | 1 thread | Parallèle | Cible |
|---|---:|---:|---:|:---:|
| `level>=W` | 926 146 | 7,8 ms | **1,3 ms** | < 100 ms ✅ |
| `category:Sync` | 2 105 017 | 22,9 ms | **6,4 ms** | < 100 ms ✅ |
| `level>=W category:Sync` + fenêtre 1 h | 2 910 | 8,0 ms | **1,0 ms** | < 100 ms ✅ |
| plein texte `"connected"` | 899 314 | 658,7 ms | **116,5 ms** | < 500 ms ✅ |
| `level>=W "backoff"` | 89 655 | 224,7 ms | **18,7 ms** | < 500 ms ✅ |
| Histogramme timeline (2000 buckets × 5 niveaux) | — | 18,0 ms | — | — |

Le plein texte est le seul cas qui **échoue en mono-thread** (658 ms contre 500 visés) et passe
largement une fois réparti. La parallélisation n'est donc pas une optimisation à garder pour plus
tard : c'est une exigence du §8.

---

## Décisions

1. **Le moteur reste déclaratif.** Aucun scanner écrit à la main dans le chemin nominal.
2. **On livre la stratégie A** (`String` + `Pattern`). Les 16 % d'avance de B ne paient pas son prix :
   B élargit les octets en chars, ce qui rend le message mojibake dans le tampon de match et impose
   que tous les champs indexés soient ASCII. A n'a aucune de ces hypothèses.
3. **C reste documentée comme échappatoire.** Facteur 2,2 sur A ; si un profil devient un point
   chaud avéré, il pourra être compilé — pas avant.
4. **Le pré-filtre structurel porte le résultat.** 18,6 % des lignes sont des continuations, rejetées
   en ~6 comparaisons d'octets avant que la regex ne s'exécute. En M1, ce pré-filtre doit être
   **dérivé du profil** (préfixe littéral, caractères à position fixe extraits de `entry.opens`) et
   non codé en dur comme ici.
5. **`mmap` perd sur la passe séquentielle, gagne sur l'accès aléatoire.** Un `get()` par octet sur
   un `MappedByteBuffer` garde son contrôle de borne et ne se vectorise pas ; un scan de `ByteArray`
   si. D'où : lectures par blocs de 8 MiB pour indexer, mapping pour relire et chercher.
6. **Un benchmark = une JVM par stratégie**, sous peine de mesurer le profil de type du JIT.

## Ce que le spike ne couvre pas (dette assumée, à traiter en M1)

- `MappedText` plafonne à 2 GiB (un seul mapping) — segmentation à écrire.
- Le pré-filtre et la regex sont ceux de Withings, codés en dur : pas encore de chargement TOML.
- Le fuseau est fixé à la construction du parser ; il doit venir du profil.
- Pas encore de fusion multi-fichiers ni de marqueurs (`=== … ===`, `--- older lines dropped ---`) :
  ils sont aujourd'hui comptés en « non reconnues », ce qui est le bon comportement par défaut mais
  pas le comportement final.

## Reproduire

```bash
./gradlew :spike:run --args="1g"       # les trois stratégies, avec vérification croisée
./gradlew :spike:run --args="1g A"     # une seule, en JVM propre — les chiffres ci-dessus
./gradlew :core:test                   # 31 golden tests sur les formes limites du format
```

La fixture est générée au premier lancement (~2 s) puis mise en cache dans `spike/fixtures/`.
