# PromptFont

Bundled at `android/app/src/main/res/font/promptfont.ttf` and used for the controller glyphs in the
mapping editor.

> PromptFont by Shinmera (Yukari Hafner), available at https://shinmera.com/promptfont

Licensed under the SIL Open Font License 1.1 — see [LICENSE.txt](LICENSE.txt). The attribution notice
above is a condition of use and belongs anywhere the app credits its dependencies. Trademarks depicted
by the glyphs remain their owners'.

## Why a font rather than drawings or an icon library

There is no maintained Android or Compose library that provides controller button art. The realistic
options were an icon font, an image pack, or drawing the shapes by hand. A font wins on the thing that
matters here: the glyphs are the real button shapes, they scale with the text size, and one `Text`
call renders any of them.

## Finding a glyph

The codepoints are ordinary BMP characters that the font remaps, not private-use ones, so a glyph
looks like an arrow in any other font. The names come from the release's `glyphs.json`, filtered by
its `xbox` and `analog` tags; `PromptFont.kt` is where they are spelled out for this app.
