# Design

Visual system of the BLauncher desktop app since the 2026-10 redesign. Base reference: Linear
(VoltAgent/awesome-design-md, `linear.app/DESIGN.md`), chosen by the owner's direction "минимализм,
взять за основу современный дизайн". Product truth lives in PRODUCT.md.

## Principles

- Calm shell: near-black canvas, charcoal panels, 1px hairlines. Depth comes from surface steps, not shadows or glows.
- One accent. The BL purple marks only the primary action (Играть), focus, the selected rail item's icon and switches that are on. Buttons are neutral; hover brightens the hairline, never fills with colour.
- Type does the hierarchy: Inter (Regular / Medium / SemiBold / Bold, bundled, Latin + Cyrillic subset); JetBrains Mono only for codes, addresses and numbers.
- Every colour is a palette token (theme-template.css + palette/presets.json); no literals in components.

## Themes (palette/presets.json)

| Token | Тёмная (`violet`) | Чёрная (`black`) | Светлая (`white`) |
|---|---|---|---|
| bg | #120F1D | #000000 | #E6EBF4 (bluish) |
| surface | #120F1B | #0A0A0A | #FFFFFF |
| surface-2 | #1A1626 | #141414 | #F1F1F3 |
| line | #2A2440 | #1F1F1F | #E4E4E7 |
| text / muted | #F4F1FA / #9A93B0 | #EDEDED / #A1A1A1 | #18181B / #62656B |
| accent | #8D6BFA | #EDEDED (monochrome, Vercel-like) | #6A58EE |
| ok / warn / danger | #4CB782 / #F2994A / #EB5757 | #45C27A / #F5A524 / #F25555 | #1E9E5A / #C26A09 / #D93636 |

Theme keys stay `violet/black/white` so saved configs keep working; labels are Тёмная / Чёрная / Светлая.

## Shape and spacing

- Radius: controls 8, cards 10, panels and dialogs 12, chips 6.
- Screen padding 28 36 32; panel padding 20; rail 100px wide, items 8px radius.
- Type scale (before the global fontScale 0.9): page title 24 SemiBold, panel title 13 SemiBold muted, row label 15 Medium, secondary text 13–14, buttons 14 Medium, Play 17 SemiBold.
- Scrollbars: 8px, thumb `line-strong`, no arrow buttons.

## Components

The "Linear layer" at the end of theme-template.css is the source of truth for the shell: rail, panels,
buttons (`secondary-button`, `server-action`, `share-trigger-btn`, `copy-btn` share one look), inputs,
combos, switch, language segmented control, build/tool cards, theme swatches.

## Motion and background

- ui/Motion (owner asked for slower, clearly visible motion): screens arrive from the rail direction
  (480ms) with their blocks following 70ms apart, lists stagger (55ms apart, capped at 600ms, 420ms each,
  rising and growing from 0.97), buttons sink 5% (back in 320ms), cards lift 3.5%, rail icons grow 18%
  on hover, switches bump once, dialogs scale 0.92->1 in 420ms, toasts slide 30px, the rail marker
  slides (420ms), Play sends a light ring (850ms) and a light band sweeps across it every 6s, theme
  changes cross-fade (650ms), wheel scrolling glides (420ms).
  Easing cubic-bezier(0.16,1,0.3,1). Settings → «Анимации» turns all of it off.
- ui/EndSky: the default background ("end"): violet-black End sky with faint stars and a pink-violet
  flash every 7–15 s (rise 420ms, fade 2.6s), modelled on the End flashes of Minecraft 1.21.9.
  Stops with animations off. The dark theme is purple-black to match it; light themes get a pale blue-lavender sky (EndSky.setLight).
