# Gap Report

## Summary
Total gaps found: 4 (2 fixable, 2 platform/timing differences)
Fixed: 2
Platform difference: 2

## Gaps

| # | UI | Section | Dimension | Web (reference) | JavaFX (actual) | Severity | Classification | Status |
|---|-----|---------|-----------|-----------------|-----------------|----------|----------------|--------|
| 1 | DemoUI | Data Table | Layout | Columns distribute evenly across card width | Columns cramped to left, minimal width | Medium | Layout semantic gap | Fixed |
| 2 | SemanticElementsUI | Ordered/Nested Lists | Content | ol shows 1. 2. 3., ul shows bullets, nested lists indented | Plain text lines, no markers, no indentation | Medium | Missing mapping | Fixed |
| 3 | AutoTransitionUI | All sections | Content | Snapshot captures early state | Snapshot captures later state (more items populated) | Low | Platform difference | Platform difference |
| 4 | AnimatedDashboardUI | View Toggle | Content | Card view (not yet auto-switched) | Table view (auto-switch already occurred) | Low | Platform difference | Platform difference |

## Fix Details

### Gap 1: Table column distribution
**Classification**: Layout semantic gap — HTML tables distribute columns to fill available width by default. JFX GridPane does not.
**Fix**: Always call `applyTableColumnGrow` for GridPane tables (not just when explicit width is set), and set `maxWidth(Double.MaxValue)` + `HBox.setHgrow(grid, Priority.ALWAYS)` so the grid fills its parent.
**Generic test**: Any UI with a `table` element inside a card/container would benefit — e.g., a settings page with key-value rows, a user directory table.
**Verified**: DemoUI table now matches web. TableAdvancedUI spot-checked — no regression.

### Gap 2: List markers and indentation
**Classification**: Missing mapping — HTML `ol` renders with numeric markers, `ul` with bullet markers, and `li` items are indented. JFX VBox/HBox have no equivalent.
**Fix**: In `addChildren`, detect `Ol`/`Ul` elements and: (a) add 20px left padding (matching HTML default), (b) prepend marker labels ("1.", "2." for ol; "•" for ul) to each `Li` child HBox.
**Generic test**: Any UI using `ol`/`ul`/`li` elements would benefit — e.g., a FAQ page with numbered steps, a feature list with bullets.
**Verified**: SemanticElementsUI now shows numbered ordered lists, bulleted unordered lists, and properly indented nested lists.
