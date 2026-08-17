# M2 — L'écran

**L'app tourne.** Compose Multiplatform, fenêtre macOS, ouverture d'un dossier, facettes, requête,
timeline brossable, panneau de détail. 94 tests au vert sur les trois modules.

Les trois arbitrages du [brief de conception](https://claude.ai/code/artifact/59cda4e2-7101-44eb-acaf-1fcb5fb3a3ea)
sont tranchés et implémentés :

| Arbitrage | Décision | Où |
|---|---|---|
| Colonnes ou ligne brute | **Les deux**, colonnes par défaut, bascule en bas à droite | `ui/LogList.kt` |
| Détail en bas ou à droite | **En bas** — les lignes de log sont larges | `ui/Panels.kt`, `DetailPane` |
| Dossier fusionné ou onglets | **Flux unique fusionné**, avec facette `file` | `index/IndexMerger.kt` |

---

## Ce qui fait la boucle

**La barre de requête est l'unique source de vérité.** Cocher une facette n'écrit pas dans un
modèle de sélection parallèle : ça édite le texte que l'utilisateur voit. C'est ainsi qu'on apprend
`level>=W` sans lire de grammaire — on coche Warn, puis Error, et les mots apparaissent.

Techniquement, c'est `QueryEdits` (`core/query/`) : une épissure textuelle sur les spans des jetons,
qui laisse intact tout ce qu'elle ne comprend pas. Une phrase, une regex, un `-category:Ui` typé
délibérément — tout ressort à sa place. Un terme négué n'est jamais réécrit ; un nouveau terme est
ajouté à côté, parce qu'inverser silencieusement ce que quelqu'un a tapé serait pire qu'un terme
redondant. Douze tests cadrent cette propriété.

**Les compteurs de facettes sont ceux qu'on obtiendrait en cliquant**, pas ceux de l'écran courant.
Compter sur le résultat courant afficherait toutes les autres catégories à zéro dès qu'on en
choisit une — et il n'y aurait plus moyen de voir vers quoi basculer. Chaque facette est donc
comptée **sa propre contrainte levée** (`index/FacetCounts.kt`), soit une passe par facette, menées
en parallèle hors du thread UI.

**Le résultat porte la requête pour laquelle il a été calculé.** C'est la forme reprise de
`LogViewerViewModel` côté Android, et c'est ce qui rend l'indicateur « en retard » honnête : l'UI
sait que ce qu'elle affiche n'est pas ce qui a été demandé, au lieu de le deviner d'un booléen que
quelqu'un a oublié de remettre à zéro.

---

## Décisions de rendu

- **Les lignes font une ligne de haut, toujours.** Une ligne qui se replie ferait de chaque position
  de scroll une passe de layout, ce qui tue une liste virtualisée. Le texte complet est à un clic
  dans le panneau de détail, et une stack trace se déplie sur place à la demande. La hauteur
  uniforme est la seule raison pour laquelle neuf millions d'entrées peuvent défiler.
- **Le texte est décodé par ligne visible, jamais en masse.** L'index ne stocke que des plages
  d'octets ; seules les quarante lignes à l'écran deviennent des `String`.
- **Seuls W et E sont colorés.** Dans un fichier où sept lignes sur dix sont des `D`, colorer chaque
  niveau revient à ne rien colorer.
- **La facette `file` n'a pas de colonne.** Une colonne de noms de fichiers identiques est de la
  largeur gaspillée ; elle vit dans la barre latérale, où elle sert à filtrer.
- **Les buckets de la timeline couvrent tout le fichier**, même quand la requête l'a réduit : une
  carte qui se remet à l'échelle sous vos pieds n'est pas une carte.
- **Les flèches parcourent le résultat, pas l'index.** `moveSelection(±1)` avance dans
  `results.matches` — avec `category:Sync` actif, descendre saute les entrées que la requête exclut.
  Aux extrémités ça s'arrête plutôt que de boucler : dans une liste de neuf millions, se téléporter
  à l'autre bout n'est jamais ce qu'on voulait.
- **Le défilement suit la sélection, il ne la pilote pas.** Un clic et une flèche défilent
  identiquement, et seulement quand la sélection atteint un bord — avec une ligne de marge, pour que
  la suivante soit déjà à l'écran quand on y arrive.

---

## Architecture

```
desktop/
├─ Main.kt              fenêtre, glisser-déposer, dialogue d'ouverture, chemins en argument
├─ state/LoupeState.kt  StateFlow des entrées → combine + debounce → Results, hors thread UI
├─ theme/LoupeTheme.kt  jetons de couleur (clair/sombre) et de type
└─ ui/
   ├─ LogList.kt        liste virtualisée, colonnes ou ligne brute
   └─ Panels.kt         barre de requête, facettes, timeline, détail, barre d'état
```

Le module ne dépend que de `compose.desktop.currentOs` et de `:core`. Pas de Material : les
composants sont bâtis sur `foundation`, ce qui évite de traîner un thème dont aucun jeton ne
servirait.

---

## Ce qui manque, et c'est assumé

Le M3 a livré la sélection multiple, la copie, le clavier, le contexte non filtré, l'export et le
défilement horizontal — voir [`m3-product.md`](m3-product.md). Restent :

- **`⌘F` / `⌘L`** pour donner le focus à la barre de requête.
- **Un seul profil livré.** `android-logcat`, `json-lines`, `syslog` attendent le M4.

## Essayer

```bash
./gradlew :desktop:run                              # fenêtre vide, glisser-déposer un dossier
./gradlew :desktop:run --args="/chemin/vers/logs"   # ouvre directement
./gradlew test                                      # 94 tests
./gradlew :desktop:packageDmg                       # .dmg (non signé pour l'instant)
```

Un dossier de test à 120 000 entrées sur trois jours se fabrique avec le générateur du spike ;
il exerce la fusion, les continuations et le fichier non reconnu qui doit être ignoré.
