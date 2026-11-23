# AGENTS.md: Twig Block Tree (PhpStorm plugin)

Context for coding agents continuing work on this project. Read this first, then `README.md`.

## Purpose

In PhpStorm, Twig templates give no visual cue for which code sits inside a `{% if %}`, `{% for %}`,
`{% block %}` etc. The color scheme options under Editor → Color Scheme → Twig only style tokens, not regions.
PhpStorm's own HTML **tag tree highlighting** (colored gutter edge + tinted tag names for the tags around the caret)
only follows the HTML hierarchy, never the Twig hierarchy. Matching-tag highlighting (`{% if %}` ↔ `{% endif %}`)
also does not exist for Twig (confirmed by the user).

This plugin adds the Twig equivalent:

1. **Gutter bars**: for each Twig block enclosing the caret, a thin colored bar on the left editor edge,
   one color per nesting level. Each line shows the color of the innermost block containing it.
   Colors are keyed by absolute depth (outermost = level 1), so a block keeps its color while the caret moves inside it.
   The bar is drawn next to (not on top of) the HTML tag tree bar, so both remain visible.
2. **Matching tags**: when the caret is on a Twig tag, all tags of the same block are highlighted
   (`{% if` / `{% elseif` / `{% else` / `{% endif`). Only the `{% tagname` part is highlighted, not long conditions.
3. A **color settings page** (Editor → Color Scheme → Twig Block Tree) for level colors and the matched-tag style.

## Design decisions (and rejected alternatives)

- **Not background highlighting** of block contents. The user preferred the gutter edge, like HTML's tag tree.
- **No folding-based approach.** Twig tags already fold, but adding/relying on fold regions was rejected:
  HTML and Twig fold regions share one folding model that requires proper nesting, and templates often interleave them
  (e.g. `{% if %}<div class="a">{% else %}<div class="b">{% endif %}`), so regions would get dropped.
  The plugin must not touch folding.
- **Caret-driven, own listeners** (not a daemon highlighting pass): an `EditorFactoryListener` attaches caret +
  document listeners to Twig editors; work is done with `ReadAction.nonBlocking(...).withDocumentsCommitted(...)
  .coalesceBy(...).finishOnUiThread(...)`. Highlighters live in the editor's own `MarkupModel` and are tracked in editor user data.
- **Generic block detection**, no list of tag types: a *tag* is a composite PSI element whose first child token is
  `TwigTokenTypes.STATEMENT_BLOCK_START` (`{%`). A *block* is a non-tag element whose first child is a tag and that has
  ≥ 2 tag children. Single-tag statements (`{% set x = 1 %}`) are ignored. This relies on Twig's PSI shape:
  `IF_STATEMENT` → children `IF_TAG`, content, `ELSE_TAG`, …, end tag (verified via usages in the open-source Symfony plugin).
- The Twig PSI tree is obtained with `file.getViewProvider().getPsi(TwigLanguage.INSTANCE)`. For caret offsets inside
  HTML content, `findElementAt` returns an outer-language leaf whose parents are the enclosing Twig statements.
- Gutter bars: `RangeHighlighter` with `HighlighterTargetArea.LINES_IN_RANGE` + a `LineMarkerRenderer`
  (default position = RIGHT free-painters area, ≥ 5px wide). HTML tag tree draws at `r.x-1 .. r.x+1`;
  we draw at `r.x + BAR_X_OFFSET` (2px) with width `BAR_WIDTH` (2px). Segments are computed per line so outer and inner
  blocks never overlap (no reliance on paint order).
- Colors: `ColorKey`s with `JBColor` light/dark defaults, deliberately different from the HTML tag tree palette.
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

- **Compiles** (`./gradlew buildPlugin` OK, 2026-11-23; output `build/distributions/twig-block-tree-0.1.0.zip`).
  The only fix needed was the `intellijPlatform` extension import in `settings.gradle.kts`. **Not yet run in an IDE.**
- Next steps:
  1. ~~`./gradlew buildPlugin` and fix compile errors.~~ Done.
  2. `./gradlew runIde` (or install the zip via Settings → Plugins → ⚙ → Install Plugin from Disk), open a Twig/Drupal template, verify: bars appear for nested if/for/block; matched tags highlight;
     no exceptions in idea.log; no overlap with HTML tag tree bar or VCS markers.
  3. Check block types: `if/elseif/else`, `for/else`, `block`, `macro`, `embed`, `apply`, `set ... endset`,
     `with`, `autoescape`, `verbatim`, and Drupal's `trans`. If one is missed, inspect its PSI (Tools → View PSI Structure,
     needs internal mode `-Didea.is.internal=true`) and adjust `toBlock()` / `isTag()`.
  4. Check custom Twig syntax delimiters (Settings → PHP → Twig) still work (token-type based, should be fine).

## Possible future features (discussed, not implemented)

- Option to always show bars for the whole file (not just around the caret).
- Faint tint of the tags of all enclosing levels (like the HTML tag tree does).
- Settings toggle / max levels / opacity, like the HTML tag tree options.
- Keyboard action to jump between matching tags.

## Files

- `src/main/java/twigblocktree/TwigBlockTreeEditorListener.java`: attaches listeners to Twig editors.
- `src/main/java/twigblocktree/TwigBlockTreeHighlighter.java`: PSI analysis + painting (main logic).
- `src/main/java/twigblocktree/TwigBlockTreeColors.java`: color keys.
- `src/main/java/twigblocktree/TwigBlockTreeColorSettingsPage.java`: color settings page.
- `src/main/resources/META-INF/plugin.xml`: registration; depends on `com.jetbrains.twig`.
