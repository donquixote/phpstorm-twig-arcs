# Twig Arcs (PhpStorm plugin)

Shows the Twig block structure around the caret, similar to PhpStorm's HTML tag tree highlighting:

- **Gutter bars:** a thin colored bar on the left edge for every Twig block that encloses the caret
  (`{% if %}`, `{% for %}`, `{% block %}`, `{% macro %}`, `{% embed %}`, `{% apply %}`, ...).
  Each nesting level has its own color. A block keeps its color as you move around inside it.
  The bar sits right next to the HTML tag tree bar, so both stay visible.
- **Matching tags:** when the caret is on a Twig tag, the other tags of the same block are highlighted
  (`{% if` / `{% elseif` / `{% else` / `{% endif`).

Folding is not touched.

## Build

Requirements: Linux/macOS/Windows with internet access. A JDK 21 is downloaded automatically if none is found.

```sh
./gradlew buildPlugin
```

The first run downloads Gradle and PhpStorm 2026.1.4 (~1 GB, needed to compile against), so it takes a while.
The result is `build/distributions/twigarcs-0.1.0.zip`.

To try it in a separate sandbox PhpStorm instead of your real one:

```sh
./gradlew runIde
```

## Install

Settings → Plugins → ⚙ → *Install Plugin from Disk…* → choose the zip → restart.

## Configure colors

Settings → Editor → Color Scheme → **Twig Arcs**:

- *Gutter bar / Level 1–6*: colors per nesting depth (deeper levels cycle).
- *Matching tags of the block under the caret*: defaults to the "matched brace" style.

## Tweaks in the code

In `TwigArcsHighlighter.java`:

- `BAR_X_OFFSET`, `BAR_WIDTH`: where and how wide the gutter bar is drawn.
- `tagHighlightRange()`: which part of a tag is highlighted (currently `{% if`, not the whole tag).

## Updating PhpStorm

The plugin is not tied to an exact PhpStorm version (`untilBuild` is unset). If a future version breaks it,
change `phpstorm("2026.1.4")` in `build.gradle.kts` to the new version and rebuild.
