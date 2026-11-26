# iconkit: "BL" family icon

Launcher icon shared by the "BL" (borderless) apps: BLBrowser, BLRando...
There is **no shared folder**: every BL project holds its own copy of `iconkit/`.
To start a new BL app, copy this folder into it.

Three layers, as vector drawables in a 108×108 viewport:

| Layer | File in the app | Changes per app? |
|---|---|---|
| 1. Background | `drawable/ic_launcher_bg.xml` + `values/bl_icon_colors.xml` | Only the two gradient colors |
| 2. Arrows | `drawable/bl_icon_arrows.xml` (copy of `iconkit/layers/bl_icon_arrows.xml`) | No, it is the family signature |
| 3. Center | `drawable/bl_icon_center.xml` | Yes, it is the app's subject. Keep it inside (33,33)-(75,75). |

## Install the icon or change the colors

From the project root:

```powershell
.\iconkit\New-BLIcon.ps1 -Dark "#0B3D12" -Light "#43A047"
```

- Writes the background, the arrows, the foreground (arrows + center) and the adaptive icon.
- Creates `bl_icon_center.xml` from a placeholder only if it is missing, and never overwrites it.
- Refuses to run if `bl_icon_dark` / `bl_icon_light` are already defined in another `values` file.
- The manifest must use `android:icon="@mipmap/ic_launcher"`.

## PNG export (Play Store, Galaxy Watch, README)

```powershell
.\iconkit\Export-BLIconPng.ps1 -Size 512 -Shape square   -Out play-512.png
.\iconkit\Export-BLIconPng.ps1 -Size 360 -Shape round    -Out watch.png
.\iconkit\Export-BLIconPng.ps1 -Size 256 -Shape squircle -Out readme.png
```

- Reads the project's three layers, converts them to SVG (also written next to the PNG) and renders them with headless Edge.
- Only the visible part of the adaptive icon is rendered (the central 72 of 108).
- In `round`, the arrows and the center are shrunk to 88% (the background is not), otherwise the corner arrows touch the circle.
- Supported: `<path>` and `<group>` (translate, scale, rotation, pivot), fill and stroke with alpha, colors as `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB` or `@color/...`, gradient angles in multiples of 45°.

## Fidelity check (BLRando, 25/11/2026)

- Android icon before and after moving to the kit: identical, 0 pixels different out of 13,225.
- PNG export compared with the icon shown on the S25: same shapes and the same color proportions. The phone shows everything about 9% darker, because One UI darkens icons in dark mode. After compensating for that, the mean difference is 3.5/255, and the only remaining gaps are anti-aliased edges.

## Colors in use

| App | Dark | Light | Center |
|---|---|---|---|
| BLRando | `#0B3D12` | `#43A047` | mountain, trail, walker arrow |
| BLBrowser | `#02125B` | `#0246D1` | globe (still PNG, not migrated yet) |
