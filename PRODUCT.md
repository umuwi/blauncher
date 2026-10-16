# Product

<!-- impeccable:product-schema 1 -->

## Platform

desktop — a Windows app (JavaFX 21, one window, 1280x800 by default, 1000x650 minimum). Styling is a JavaFX CSS template filled from a palette (src/main/resources/css/theme-template.css + model/Palette.java); there is no browser. An Android port exists separately.

## Users

Everyone who plays Minecraft Java — confirmed by the owner as "все подряд": kids and teens on offline-mode servers with friends, players who install mods and modpacks, people running their own small servers. The launcher must be understandable to any of them without reading anything.

## Product Purpose

BLauncher installs and launches any Minecraft Java version, with or without mods, and makes playing together simple: friends by code, rooms over ZeroTier, a local server in a few clicks. It also lets people make things for the game: skins and capes (painter), block/item scenes for command blocks (studio), a BLauncher badge and BL capes visible in-game to other BLauncher players (BL Core mod).

Success: a player gets from opening the launcher to being in the game in one click, and finds every other function without help.

## Positioning

No ads and no junk, yet it does more than the usual launchers (owner's words: «без рекламы и мусора, многофункциональный»). The competing launchers (TLauncher, Legacy) are what users compare against: cluttered with ads and upsells. BLauncher's difference must be visible as calm, clean, fast — with depth available when wanted.

## Constraints

- Russian first, English second; every string lives in i18n/strings_*.properties.
- Three built-in themes plus a player palette editor: every colour must be a palette token, never a literal.
- Must fit and work at 1000x650 and look right at 1280x800.
- Functions, texts and flows stay; the redesign changes the look (confirmed 2026-10-05: «полный редизайн»).
- Assets: BL logo (purple rounded square, "BL"), 16 Minecraft-world background pictures, bundled fonts Fira Sans and JetBrains Mono (replaceable if the redesign needs it).
