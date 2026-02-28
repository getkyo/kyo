# Gap Report

## Summary

19 UIs compared. 40 total gaps tracked. 36 fixed, 1 pending, 3 platform differences.

- **Fixed**: 36 gaps
- **Pending**: 1 gap (list markers)
- **Platform differences**: 3 (not bugs)

## All Gaps

| # | UI | Section | Dimension | Severity | Classification | Status |
|---|-----|---------|-----------|----------|----------------|--------|
| 1 | demo | Form Example | Layout — vertical stack | High | Layout semantic gap | **Fixed** |
| 2 | demo | Footer | Text alignment — centered | Medium | Style bug | **Fixed** |
| 3 | interactive | Keyboard Events | Sizing — full-width input | Medium | Layout semantic gap | **Fixed** |
| 4 | interactive | Focus & Blur | Sizing — full-width input | Medium | Layout semantic gap | **Fixed** |
| 5 | interactive | Disabled State | Content — button text visible | High | Style bug | **Fixed** |
| 6 | interactive | Cursor Variants | Borders — no spurious border | Low | Style bug | **Fixed** |
| 7 | form | Form with Submit | Layout — vertical stack | High | Layout semantic gap | **Fixed** |
| 8 | form | Form with Submit | Interactive — proper CheckBox | High | Style bug | **Fixed** |
| 9 | form | Form with Submit | Sizing — full-width Submit | Medium | Layout semantic gap | **Fixed** |
| 10 | form | Disabled Controls | Layout — vertical stack | High | Layout semantic gap | **Fixed** |
| 11 | form | Disabled Controls | Content — button text visible | High | Style bug | **Fixed** |
| 12 | typography | Font Styles | Typography — italic works | Medium | Style bug | **Fixed** |
| 13 | typography | Text Decoration | Typography — underline works | Medium | Style bug | **Fixed** |
| 14 | typography | Text Decoration | Typography — strikethrough works | Medium | Style bug | **Fixed** |
| 15 | typography | Text Transform | Typography — transforms work | Medium | Missing mapping | **Fixed** |
| 16 | typography | Spacing | Typography — line height | Medium | Missing mapping | **Fixed** |
| 17 | typography | Spacing | Typography — letter spacing | Medium | Missing mapping | **Fixed** |
| 18 | typography | Text Overflow | Typography — wrapText works | Medium | Style bug | **Fixed** |
| 19 | typography | Text Alignment | Text alignment — works | Medium | Style bug | **Fixed** |
| 20 | layout | Explicit Column | Text alignment — centered | Medium | Style bug | **Fixed** |
| 21 | layout | Justify Content | Layout — all modes work | High | Missing mapping | **Fixed** |
| 22 | layout | Align Items | Layout — alignment works | High | Style bug | **Fixed** |
| 23 | layout | Overflow | Layout — clips correctly | High | Style bug | **Fixed** |
| 24 | reactive | Multiple sections | Content — button text visible | High | Style bug | **Fixed** |
| 25 | reactive | Simple foreach | Layout — horizontal row | Medium | Layout semantic gap | **Fixed** |
| 26 | DashboardUI | Project Status | Sizing — pct width on progress bars | High | Style bug | **Fixed** |
| 27 | DashboardUI | Explicit Widths | Sizing — px width constraining | High | Style bug | **Fixed** |
| 28 | SizingUnitsUI | Width in Pixels | Sizing — px width constraining | High | Style bug | **Fixed** |
| 29 | SizingUnitsUI | Width in Percent | Sizing — pct width constraining | High | Style bug | **Fixed** |
| 30 | SizingUnitsUI | Margin Auto Centering | Sizing — depends on width fix | High | Style bug | **Fixed** |
| 31 | SemanticElementsUI | Code Block | Colors — text visible on dark bg | High | Style bug | **Fixed** |
| 32 | SemanticElementsUI | Lists | Typography — list markers | Medium | Missing mapping | **Pending** |
| 33 | MultiPseudoStateUI | Border Sides | Borders — sides combine | Medium | Style bug | **Fixed** |
| 34 | TableAdvancedUI | Styled Table | Layout — cell text visible | High | Style bug | **Fixed** |
| 35 | TableAdvancedUI | Colspan & Rowspan | Layout — proper merged cells | High | Missing mapping | **Fixed** |
| 36 | DynamicStyleUI | Dynamic Background | Colors — Signal bg renders | High | Style bug | **Fixed** |
| 37 | TypographyUI | Spacing | Layout — lineHeight wraps | Medium | Style bug | **Fixed** |
| 38 | TableAdvancedUI | Styled Table | Layout — rows render horizontally (web) | High | Style bug (web CSS) | **Fixed** |
| 39 | TableAdvancedUI | Dynamic Table | Layout — rows render horizontally (web) | High | Style bug (web CSS) | **Fixed** |
| 40 | SemanticElementsUI | Image Element | Content — missing in JavaFX | Medium | Screenshot truncation | **Fixed** |

## Platform Differences (not bugs)

| UI | Section | Description |
|----|---------|-------------|
| all | Various | Font rendering differences (anti-aliasing, glyph widths) |
| all | Form controls | Native JavaFX widget chrome differs from browser defaults |
| all | Colors | Minor color differences in widget states |
| FormUI | Select | JavaFX shows value string vs display text |
| CollectionOpsUI/NestedReactiveUI | foreach | Items render vertically vs inline (block vs inline default) |

## Fixes Applied This Session

### 1. Width constraining (Gaps #26-30)
**File**: `JavaFxBackend.scala` — Added `Width` prop handling in `applyStyleProps()`. For `Size.Px`, sets both `prefWidth` and `maxWidth` so `applyLayoutDefaults` VBox fillWidth doesn't override. For `Size.Pct`, binds to parent width via listener.

### 2. Code element as container (Gap #31)
**File**: `JavaFxBackend.scala` — Changed `Code` from `JLabel` to `VBox` so it can contain child elements (like `pre`). Text color now propagates correctly via `forEachLabeled`.

### 3. Border side combining (Gap #33)
**File**: `FxCssStyleRenderer.scala` — Collects all `BorderTopProp`/`BorderRightProp`/etc. and emits a single combined `-fx-border-color` and `-fx-border-width` instead of each side overwriting the previous.

### 4. Table layout (Gaps #34-35)
**File**: `JavaFxBackend.scala` — Changed `Th` from `JLabel` to `VBox` for child support. Removed fixed percent column widths (let GridPane auto-size). Added colspan-aware column indexing in `buildTableGrid`.

### 5. Dynamic style CSS conversion (Gap #36)
**File**: `JavaFxBackend.scala` — Added `webCssToFxCss()` that converts web CSS property names to JavaFX equivalents (e.g., `background-color:` → `-fx-background-color:`) when raw CSS strings are used via `.style(signal)`.

### 6. LineHeight fix (Gap #37)
**File**: `JavaFxBackend.scala` — Changed lineHeight handling to compute pixel spacing from multiplier (`fontSize * (multiplier - 1)`), and enables `wrapText(true)` so multi-line text doesn't truncate.

### 7. Web table rows via display:contents (Gaps #38-39)
**File**: `DomBackend.scala` — ForeachIndexed/ForeachKeyed/ReactiveNode containers use `<span>` which breaks table layout when inside `<table>`. Added `style="display:contents"` so the span is transparent to layout and `<tr>` elements participate directly in table flow.

### 8. JavaFX table ForeachIndexed + Hgap (Gaps #38-39)
**File**: `JavaFxBackend.scala` — Removed hardcoded `setHgap(16)` from GridPane (HTML tables have no default gap). Rewrote `buildTableGrid` to handle `ForeachIndexed` children by subscribing to the signal and expanding rows inline, placing cells correctly in the grid.

### 9. JavaFX screenshot full-height capture (Gap #40)
**File**: `JavaFxScreenshot.scala` — macOS caps window height to screen resolution, truncating tall UIs. Fixed by calling `root.resize(width, prefHeight)` + `root.layout()` before `root.snapshot()`, so the node renders at its full preferred height regardless of screen constraints.

## Remaining Work

- **Gap #32**: List markers (ol/ul numbering, bullets, indentation) — not yet implemented in JavaFX backend
