# M3 — Le produit

Ce qui manquait pour que l'outil se pilote au lieu de se consulter : sélectionner plusieurs lignes,
les copier, se déplacer au clavier, voir ce qui entourait une ligne, et sortir le résultat.

**103 tests.** Rien de neuf côté performance — tout le M3 est de l'interaction.

---

## La sélection, écrite à la main

`SelectionContainer` sur `LazyColumn` est instable sur Compose Desktop. Le risque était identifié
au PRD avant la première ligne de code, il n'a pas bougé, donc le modèle est fait main.

**C'est une plage, pas un ensemble**, parce que c'est ce que produisent les gestes : clic,
`⇧`+clic, extension à la flèche. Et elle est tenue en **positions dans le résultat, pas en index
d'entrées** — c'est la partie qui compte. « Tout ce qui est entre ces deux-là » veut dire tout ce
qui est entre eux *à l'écran* : avec `category:Sync` actif, les entrées Wpp qui dorment dans
l'intervalle ne sont pas sélectionnées et ne doivent pas être copiées. C'est le test.

L'ancre reste où la sélection a commencé, le focus est l'extrémité qui bouge et la ligne que décrit
le panneau de détail. Cette ligne se lit un ton plus fort que le reste de la plage, pour qu'une
longue sélection dise quand même où on en est.

## Le clavier

Tout passe par le gestionnaire `onPreviewKeyEvent` de la liste, dont le `Box` n'enveloppe pas la
barre de requête — un curseur dans le champ de texte n'est jamais touché.

| | |
|---|---|
| `↑` `↓` `j` `k` | déplacer d'une ligne |
| `⇧` + n'importe lequel | étendre au lieu de déplacer |
| `Page↑` `Page↓` | d'un écran, calculé sur les lignes réellement visibles |
| `Début` `Fin` | aux extrémités du résultat |
| `⌘A` | sélectionner le résultat — pas le fichier |
| `⌘C` | copier |

## La copie est plafonnée, et le dit

À 20 000 entrées. Un `⌘A` sur neuf millions, c'est des gigaoctets, et le presse-papier sert à coller
dans un ticket — le fichier, c'est l'export. **Le plafond est annoncé, jamais appliqué en
silence** : un copier-coller tronqué qui ne dit rien, c'est un rapport de bug qui perd sa cause.

Le plafond est un paramètre par défaut, pour que le test l'exerce à 2 plutôt qu'en générant vingt
mille lignes de fixture.

## Le contexte non filtré

L'étape 6 du scénario nominal du PRD. Ce qui s'est passé autour d'une ligne est en général la raison
pour laquelle elle s'est passée, et le filtre l'a par définition caché. La bascule `context ±20` du
panneau de détail lit **l'index, pas le résultat** : avec `level:E` à l'écran, c'est là que sont les
lignes Debug qui ont mené à l'erreur.

## L'export

Écrit **le résultat courant**, pas la sélection — c'est ce que dit le bouton, et restreindre
davantage est le travail de la barre de requête. Non plafonné, hors thread UI, décodé entrée par
entrée directement dans le writer : le pic mémoire est d'une entrée, pas du résultat.

## Le défilement horizontal, en mode ligne brute seulement

Pas un compromis, une décision. En colonnes, faire défiler sur le côté pousserait les horodatages
hors de l'écran et détruirait l'alignement qui est la raison d'être des colonnes ; là, une ligne est
tronquée et le panneau de détail montre le reste. En ligne brute, où l'on veut le fichier tel quel,
le défilement est exactement ce qu'il faut.

Il est posé sur la `Row` du contenu et non sur l'item, pour que la surbrillance de sélection couvre
toujours la largeur visible pendant que le texte glisse à l'intérieur. Le `weight(1f)` a disparu
avec : dans un défilement horizontal la largeur est non bornée, et un poids a besoin d'une borne.

## L'indicateur de santé répond à sa propre question

`40 168 / 41 087 lignes reconnues` disait qu'il y avait un problème sans dire lequel — ce qui est la
moitié du travail. Le compteur est maintenant **cliquable** et ouvre un panneau qui groupe les
lignes orphelines **par forme**, avec un exemple de chacune et sa position.

Le nombre dit que le profil est imparfait ; la forme dit *quelle partie* l'est, et les quatre
pointent vers des correctifs très différents :

| Forme | Ce que ça veut dire |
|---|---|
| ligne vide | Bénin. Écriture partielle, ou un second producteur sur le même fichier. |
| espaces seulement | `entry.continues` est proche mais pas exact — vérifier la largeur d'indentation réelle. |
| indentée, mais pas une continuation | Ressemble à un message replié ou une frame. `entry.continues` est probablement trop strict : un writer plus ancien avec un horodatage de largeur différente indente d'autant moins. |
| **ressemble à une entrée, mais `parse.regex` la rejette** | **Celle qui vaut le détour.** Le pré-filtre a dit oui et la regex complète a dit non : le format a une forme que le profil ne décrit pas. |
| autre chose | Écrite par un tout autre chemin de code. |

Compté **en entier**, échantillonné **par forme**. En entier parce qu'un ratio approximatif ne vaut
rien ; échantillonné parce qu'un fichier ouvert avec le mauvais profil a *toutes* ses lignes
orphelines, et retenir neuf millions de chaînes pour le dire transformerait un diagnostic en
`OutOfMemoryError`. Le plafond est **par forme et non global** : une forme domine presque toujours,
et un plafond global la laisserait écraser l'unique exemple de celle qui explique le problème.

L'outil sait donc diagnostiquer son propre profil — ce qui compte d'autant plus qu'on va en écrire
quatre autres au M4.

---

## Reste pour le M4

`⌘F` / `⌘L` pour le focus de la barre de requête. Les profils `android-logcat`, `json-lines`,
`syslog`, `generic-timestamped` — chacun exercera le chemin de repli du compilateur de timestamp,
qui n'a qu'un seul test aujourd'hui. Et la publication : group Gradle, notarisation, README.
