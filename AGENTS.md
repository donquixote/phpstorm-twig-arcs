# AGENTS.md: Twig Arcs (PhpStorm plugin)

Context for coding agents continuing work on this project. Read this first, then `README.md`.

Formerly "Twig Block Tree" (briefly "Twig Bowls", rejected as an inside joke). Naming: display name
"Twig Arcs: Matching Tag & Branch Highlighting" (brand + search terms: matching tag, highlight); short name "Twig Arcs"
(settings page, README); plugin id `io.github.donquixote.twigarcs` (never
change it once published); Java package the same; Gradle project / repo `twigarcs` (no dashes, matching the id).

## Purpose

In PhpStorm, Twig templates give no visual cue for which code sits inside a `{% if %}`, `{% for %}`,
`{% block %}` etc. The color scheme options under Editor → Color Scheme → Twig only style tokens, not regions.
PhpStorm's own HTML **tag tree highlighting** (colored gutter edge + tinted tag names for the tags around the caret)
only follows the HTML hierarchy, never the Twig hierarchy. Matching-tag highlighting (`{% if %}` ↔ `{% endif %}`)
also does not exist for Twig (confirmed by the user).

This plugin adds the Twig equivalent:

1. **Curves** (whole file): every Twig block gets dotted curves left of its tags, **one curve per branch**, so
   if/elseif/else looks like an inverted "3". A branch curve is a half circle if it fits between the tag and the
   left limit, otherwise a vertical line at the limit with a quarter circle at each end. Available space = indentation
   + gutter up to its LEFT free painters area (left of the icon column); nested curves stay `NEST_STEP` right of the
   curve of the parent *branch* they are in. Where the two tags of a branch start at different columns, the curve
   starts at the leftmost one and a horizontal connector reaches the other. Curves end `TAG_GAP` before the first
   character of the tag line. Single-line blocks get a small half circle.
   Round dots, gap edge to edge: `DOT` 1.5px + `DOT_GAP` 2.5px; caret's branch `DOT_ACTIVE` 2.5px + `DOT_GAP_ACTIVE` 1.5px.
   A block is active when the caret is anywhere in its range. Colors (user tunes them in the color scheme):
   `DEFAULT` blue = blocks without the caret, `ACTIVE` green = caret's branch, `OPPOSITE` red = excluded branches.
2. **Greyed text**: content of excluded branches of active blocks gets `EXCLUDED_TEXT`
   (falls back to `NOT_USED_ELEMENT_ATTRIBUTES`).
3. **Matching tags**: when the caret is on a Twig tag, the **whole** tags of the same block are highlighted
   (`MATCHED_TAG`, falls back to `MATCHED_BRACE_ATTRIBUTES`).
4. **Backgrounds**: `STATEMENT_BACKGROUND` on every `{% %}` (pale by default, via `colorSchemes/*.xml` for
   Default/Darcula), `PRINT_BACKGROUND` on `{{ }}` and `COMMENT_BACKGROUND` on `{# #}` (none by default).
5. **Condition inference**: the caret's branch in an `if` block gives facts (earlier conditions false, own condition
   true). Other `if` blocks with the same condition are decided branch by branch: known true → `INFERRED` ("taken if
   reached": dots alternate between `INFERRED` color/`DOT_ACTIVE` and `DEFAULT` color/`DOT`), rest and known-false →
   excluded (red, greyed); unknown → stop. Excluded stays valid under an undecided parent; inferred only means "taken
   if reached". It becomes `ACTIVE` (*certainly taken*, drawn like the caret's branch) when every enclosing block up
   to the first one containing the caret is an `if` whose branch around it is certainly taken, and that caret block's
   branch around it is the caret's branch (`certainlyReached()`); undecided ifs and for/block/macro without the caret
   make it uncertain. Only within the same
   `for` loop (`Block.loop`); not across a `{% set var %}` of a variable in the condition. Blocks inside an excluded
   branch are unreachable (all red). Matching condition texts get `MATCHED_CONDITION` (pale yellow by default).
   Conditions: text between tag name and `%}`, whitespace-normalized, outer parentheses removed; `not X` is a
   negation only if X is a single operand (Twig: `not a and b` = `(not a) and b`, kept opaque).
6. A **color settings page** (Editor → Color Scheme → Twig Arcs) for all of the above: clearing an attribute in
   the color scheme turns that effect off.
7. A **settings page** (Settings → Editor → Twig Arcs, `TwigArcsConfigurable` + `TwigArcsSettings`, stored in
   `twigarcs.xml`) with one option: "Widen the gutter to fit nested arcs" (default on). It guards the only internal
   API call; a `LinkageError` from it turns it off for the session (default gutter width).

The look was designed in `prototype/index.html` (see below); the plugin implements one fixed configuration of it.

## Design decisions (and rejected alternatives)

- **Not background highlighting** of block contents. The user preferred the gutter edge, like HTML's tag tree.
- **No folding-based approach.** Twig tags already fold, but adding/relying on fold regions was rejected:
  HTML and Twig fold regions share one folding model that requires proper nesting, and templates often interleave them
  (e.g. `{% if %}<div class="a">{% else %}<div class="b">{% endif %}`), so regions would get dropped.
  The plugin must not touch folding.
- **Caret-driven, own listeners** (not a daemon highlighting pass): an `EditorFactoryListener` attaches caret +
  document listeners to Twig editors; work is done with `ReadAction.nonBlocking(...).withDocumentsCommitted(...)
  .coalesceBy(...).finishOnUiThread(...)`. Highlighters live in the editor's own `MarkupModel` and are tracked in editor user data.
- **Block detection**, from the Twig plugin's parser (`com.jetbrains.twig.parser.TwigBlockStatements` in twig.jar):
  ```
  IF_STATEMENT: IF_TAG, content, ELSEIF_STATEMENT(ELSEIF_TAG, content), ELSE_STATEMENT(ELSE_TAG, content), ENDIF_TAG
  FOR_STATEMENT: FOR_TAG, content, ELSE_STATEMENT(ELSE_TAG, content), ENDFOR_TAG
  other statements: opening tag, content, end tag
  ```
  So else/elseif tags are **not** direct children of the if. Moreover, `ELSEIF_STATEMENT` only ends at the end tag
  (its "ends before" list names statement types, which never match a tag), so in `if/elseif/elseif/else` the
  following elseif/else statements are **nested inside** the first ELSEIF_STATEMENT. `collectBlockTags()` descends
  into branch statements; the matched-tag lookup climbs past all of them. Content statements (e.g. `{% include %}`) can be direct
  children of a block. A *tag* is a composite element whose first token is `TwigTokenTypes.STATEMENT_BLOCK_START`.
  A *block* = element whose first child is a tag (opener) + the first tags of its ELSE/ELSEIF_STATEMENT children +
  its structural end tag (`TwigTag.isStructural() && getTagName().startsWith("end")`); needs ≥ 2 tags.
  Structural tags in twig.jar: if/else/elseif, block, for, filter, autoescape, macro, spaceless, embed, sandbox,
  verbatim, raw, set, with (+ end tags); non-structural: extends, import, include, do, flush. Unknown tags (Drupal
  `trans`, `apply`, ...) get the generic `TwigElementTypes.TAG` type; whether they form blocks is untested.
  Matched-tag highlighting: caret tag's parent (or grandparent for else/elseif) must be a block containing that tag.
- The Twig PSI tree is obtained with `file.getViewProvider().getPsi(TwigLanguage.INSTANCE)`. For caret offsets inside
  HTML content, `findElementAt` returns an outer-language leaf whose parents are the enclosing Twig statements.
- Painting: curves are computed in editor content coordinates (negative x = in the gutter, measured from its right
  edge, which touches the text area's x = 0). Three whole-document greedy highlighters per editor: a
  `CustomHighlighterRenderer` paints the curves in the text area; a `LineMarkerRendererEx` at `Position.CUSTOM` gets the
  full gutter width and paints the same paths translated by the gutter width; an `AreaProbe` at `Position.LEFT` paints
  nothing but makes the left free painters area appear and reports its x (the left limit). The area is widened with
  `reserveLeftFreePaintersAreaWidth` to fit nesting. Each branch (with its connectors) is one `Path2D` stroked with a
  round-dot `BasicStroke` (near-zero dashes + round caps). Curves are recomputed per paint (y depends on folding and
  soft wraps); only connector x positions are cached (`Layout`, per model + space width).
- Layers: range highlighters below `HighlighterLayer.SYNTAX` rank *under* the lexer's syntax attributes
  (`IterationState`), so the tag backgrounds use layer `SYNTAX`; otherwise a background from the Twig/template
  color settings hides them.
- Caching: block model (pre-order list with parent indices, depth, tag lines, plus all statement/print/comment ranges)
  per document modification stamp (background).
  Caret moves only update `State.activeBranches`, the matched-tag / greyed-text highlighters, and repaint.
- Design history (rejected): background tinting; folding regions; gutter bars next to the HTML tag tree bar
  (distracting); gutter columns per nesting depth in the LEFT free painters area with per-level colors,
  checkerboard, halos (too disconnected from the code); a guide line directly below `{%`; straight brackets left of
  the tags; directional halos/chevrons/area fill on connectors (prototype only); dotted/dashed 1px lines (too
  faint); blue/orange/violet (too similar). Current: per-branch dotted curves.
- Colors: `ColorKey`s with `JBColor` light/dark defaults.
  Matched-tag `TextAttributesKey` falls back to `CodeInsightColors.MATCHED_BRACE_ATTRIBUTES`.
- Written in **Java** (not Kotlin) to avoid Kotlin compiler / platform metadata version mismatches.

Reference implementation in the platform that this mirrors:
`intellij-community/xml/impl/src/com/intellij/codeInsight/daemon/impl/tagTreeHighlighting/XmlTagTreeHighlightingPass.java`.

## Target environment

- PhpStorm **2026.1.4** (build PS-261.26222.71), Linux (Fedora 44, KDE, Wayland toolkit), new UI enabled.
- Also installed: Symfony Support plugin, PHP Annotations, Drupal plugins (Drupal Twig templates are a main use case).
- Build: Gradle 9.9.2 wrapper, IntelliJ Platform Gradle Plugin 2.20.1 (settings plugin), Java toolchain 21,
  `phpstorm("2026.1.4")`, `bundledPlugin("com.jetbrains.twig")`, `sinceBuild = 261`, no `untilBuild`.

## Status

- Used daily by the user in PhpStorm 2026.1.4; preparing the first Marketplace release (0.1.0, MIT licence,
  repo github.com/donquixote/twigarcs).
- `./gradlew verifyPlugin` (against PS 2026.1.4): compatible, but fails on one internal + experimental API:
  `EditorGutterComponentEx.reserveLeftFreePaintersAreaWidth` (widens the left gutter area for nested curves).
  It is now behind a setting with a LinkageError fallback; the user is evaluating the difference. Decide before
  publishing: keep it (and lower the verifier failure level) or drop it.
- Untested block types: `macro`, `embed`, `apply`, `set ... endset`, `with`, `autoescape`, `verbatim`, Drupal
  `trans`. If one is missed, inspect its PSI (Tools → View PSI Structure, internal mode `-Didea.is.internal=true`)
  and adjust `toBlock()` / `isTag()`.

## Possible future features (discussed, not implemented)

- Option to always show bars for the whole file (not just around the caret).
- Faint tint of the tags of all enclosing levels (like the HTML tag tree does).
- Settings toggle / max levels / opacity, like the HTML tag tree options.
- Keyboard action to jump between matching tags.

## Files

- `src/main/java/io/github/donquixote/twigarcs/TwigArcsEditorListener.java`: attaches listeners to Twig editors.
- `src/main/java/io/github/donquixote/twigarcs/TwigArcsHighlighter.java`: PSI analysis + painting (main logic).
- `src/main/java/io/github/donquixote/twigarcs/TwigArcsColors.java`: color keys.
- `src/main/java/io/github/donquixote/twigarcs/TwigArcsColorSettingsPage.java`: color settings page.
- `src/main/resources/META-INF/plugin.xml`: registration; depends on `com.jetbrains.twig`.
- `src/main/resources/colorSchemes/TwigArcs{Default,Darcula}.xml`: default {% %} background per scheme.
- `src/main/resources/META-INF/pluginIcon.svg`: Marketplace / plugin list icon (40×40).
- `LICENSE`: MIT.
- `prototype/index.html`: standalone browser mockup (canvas) of the editor + gutter for trying visual designs
  cheaply before porting them to the plugin. Has live settings and exports them as JSON. Its parser mimics the
  plugin's block rules with regexes. Not part of the plugin build.
