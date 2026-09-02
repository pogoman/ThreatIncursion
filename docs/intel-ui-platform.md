# The intel large-description surface: what it can draw

Starsector's modding API gives an intel entry two ways to render: `createSmallDescription`
(the narrow right-hand text column, beside the sector map) and `createLargeDescription`
(`hasLargeDescription()` true, `hasSmallDescription()` false), which hands you a
`CustomPanelAPI` filling the whole right side of the intel screen. Colony crises use the
large one. Everything below concerns the large one.

## Geometry you actually get

- `createLargeDescription(panel, width, height)`: `width` is in **logical** pixels after the
  game's UI scaling. Measured: **1195 px at 3440x1440**, **997 px at 1920x1080** on this
  machine. Design for 1000-1200, never for the physical pixel count.
- Create the main element with `panel.createUIElement(width, height, true)` (scroller on),
  build into it, then `panel.addUIElement(main).inTL(0, 0)`. Vertical overflow scrolls;
  there is **no horizontal scroll**, anything wider is clipped.
- The stock table stretches its columns to fill the element width. Anything drawn on top must
  scale its offsets by `drawnRowWidth / sumOfColumnWidths`.

## Widgets that work (all proven in-game)

| Widget | Notes |
| --- | --- |
| `addPara` with highlights | Prose. Costs ~18 px a line. Keep it for tooltips. |
| `addSectionHeading(text, textColor, bgColor, Alignment.MID, pad)` | Faction-coloured divider. |
| `beginTable2(faction, rowH, border, header, "Name", w, ...)` / `addRow` / `addRowWithGlow` / `addTable("None", -1, pad)` | Text cells only (Alignment, Color, String triples). `makeTableItemsClickable()` + `setIdForAddedRow(id)` route clicks to `IntelInfoPlugin.tableRowClicked(ui, data)` with `data.rowId`. `addTooltipToAddedRow` and `addTableHeaderTooltip(index, String or TooltipCreator)` work. `addRow` returns the row object, which is a `UIComponentAPI` with a live `getPosition()`. |
| `beginGrid` / `addToGrid(x, y, label, value, color)` / `addGrid` | Label/value pairs. Values right-align inside the cell, so give cells room. |
| `beginSubTooltip(w)` ... `endSubTooltip()` then `addCustom(left, pad)` and `addCustomDoNotSetPosition(right).getPosition().rightOfTop(left, pad)` | Two columns side by side. `setHeightSoFar` both to the max so the flow continues below the taller one. |
| `Global.getSettings().createCustom(w, h, plugin)` | A custom panel. The plugin's `render(alphaMult)` may draw with LWJGL `GL11` in screen space: `pos.getX()/getY()` is the bottom-left corner. Draw quads, outlines, lines, triangles. Sprites via `Global.getSettings().getSprite(path)` then `setSize`, `setColor`, `setAlphaMult`, `render(x, y)`; re-enable `GL_TEXTURE_2D` around sprite draws if you disabled it for quads. |
| Text inside a custom panel | `panel.createUIElement(w, 20, false)`, `setTextWidthOverride(w)`, `setParaFont(...)`, `addPara`, then `panel.addUIElement(tm).inTL(x, y)` (top-left, y down). |
| `addImage(s)`, `beginIconGroup`/`addIcons(spec, n, IconRenderMode)`/`addIconGroup(rowH, pad)` | Commodity icons. **`n` draws n stacked icons** (the game's idiom for units) - pass 1 when only the tint matters. Modes: NORMAL, DIM, GREEN, RED, DIM_GREEN, DIM_RED. |
| `addTooltipTo(creator, component, TooltipLocation)` | Hover tooltip on any component, custom panels included. `addSectorMap(w, h, system, pad)` inside a tooltip works. |
| `intel.addGenericButton(tm, width, text, id)` | Standard intel button; press arrives at `buttonPressConfirmed(id, ui)`, with `doesButtonHaveConfirmDialog`/`createConfirmationPrompt` for a confirm step. `setEnabled(false)` + `setShowTooltipWhileInactive(true)` for greyed buttons with an explanatory tooltip. |
| `IntelUIAPI` | `updateUIForItem(this)` redraws after a state change; `showOnMap(entity)` switches to the map; `updateIntelList(false, list)` shows a custom subset of intel items, after which `selectItem(item)` works even for items filtered out of the current tab (plain `selectItem` on a filtered-out item is a no-op). |

## Traps (each cost real time)

1. **"May only anchor on siblings" is fatal.** `getPosition().rightOfTop(x)`, `belowRight(x)`,
   `aboveLeft(x)` etc. accept only a component with the same parent. A table row is a child of
   the table, not of your element: anchor to the **table panel** returned by `beginTable2`
   and offset by `rowIndex * rowHeight` from its bottom (`belowRight(table, -k*ROW_H)`).
2. **The flow continues from the last component you added.** After moving a component
   (a floating button) the next `addPara`/`addCustom` lands beside it. Add all floating,
   repositioned components **last**, and restore `setHeightSoFar` to what it was before them.
3. **Controls inside a `createCustom` panel never report.** Buttons, area checkboxes and their
   `setActionListenerDelegate` inside a nested `createUIElement` toggle visually but call back
   nothing reachable, and the plugin's `buttonPressed` does not fire either. Put interactive
   controls in the intel's own element (stock table rows, `addGenericButton`) and use custom
   panels for drawing only.
4. **Text in a custom-panel element wraps early.** The paragraph width is less than the element
   width. Call `setTextWidthOverride(w)` before `addPara`, and only shorten when
   `computeStringWidth(str) > w - 6`. Do not trust `Alignment.MID` for pixel-exact centring;
   measure with `computeStringWidth` and place a left-aligned element yourself.
5. **`Misc.getJoined(", ", list)` is an "a, b, and c" joiner**: the first argument is the word
   before the last item, so `", "` yields `x, , y`. Write your own join.
6. **`addPara(format, pad, Color, Color, String...)`** is (text colour, highlight colour). The
   `Color[]` variant takes one colour per highlight.
7. **Fonts.** `setParaSmallInsignia()` is not smaller than the default in the intel screen. The
   stock table's cell font is `graphics/fonts/insignia15LTaa.fnt`; use `setParaFont` with that
   path to make custom text match the table. `Fonts` has no small-default constant.
8. **Fog of war.** Anything computed over `ThreatIncData.getAllLiveColonyMarkets()` leaks the
   true size of the hive. Show shares, never counts, or restrict to discovered systems.
9. **Debugging.** Log from the render (`ThreatIncConfig.log`) and read `starsector-core/starsector.log`;
   it is appended across launches, so match on the newest line. Wrap `render` in a try/catch that
   falls back to text - but layout-time crashes (trap 1) happen after your code returns and are
   not catchable.

## Things you cannot do

- Add a top-level intel tab (Intel / Planets / Factions are core code; `CoreUITabId` is fixed).
- Reuse the Planets tab: it's in the obfuscated core jar.
- Put icons or components in stock table cells (text or a `LabelAPI` only).
- Full-screen custom UI is possible only via `InteractionDialogAPI.showCustomDialog(w, h, delegate)`
  opened from an intel button with `ui.showDialog(entity, plugin)`; not used here.
