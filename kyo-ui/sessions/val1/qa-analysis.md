# QA Analysis

## Interactive UIs (ranked by interaction surface)

### 1. CollectionOpsUI — Highest interaction surface
**User journeys**: Add items via input+button, remove last, reverse order, clear all, tick counter, set single/reset.
**Happy paths**: Add item -> appears in list; Remove last -> last disappears; Reverse -> order flips; Clear -> empty state shows.
**Edge cases**: Add with empty input (should no-op), clear then add (recovery from empty), rapid ticks, single item then reset.
**JFX vs Web divergence risk**: HIGH — foreach/foreachKeyed diffing, empty state conditional rendering, dynamic list updates.

### 2. ReactiveUI — High interaction surface
**User journeys**: Toggle conditional panel (show/hide), toggle visibility, switch dynamic class (A/B/C), add items to foreach list, toggle view mode (list/grid).
**Happy paths**: Hide Panel -> panel disappears; Show Panel -> panel reappears; Add item -> tag appears; Switch view -> layout changes.
**Edge cases**: Add empty item (should no-op), toggle panel rapidly, switch dynamic class multiple times.
**JFX vs Web divergence risk**: HIGH — UI.when conditional rendering, Signal[UI] view mode swap, foreach with dynamic items.

### 3. DemoUI — High interaction surface
**User journeys**: Counter increment/decrement, todo add/delete, theme toggle.
**Happy paths**: Click + -> count goes up; Add todo -> appears in list; Click x -> todo removed; Toggle theme -> dark mode.
**Edge cases**: Decrement below 0 (negative counts), add empty todo (should no-op), delete from middle of list, toggle theme twice (restore).
**JFX vs Web divergence risk**: MEDIUM — counter signal updates, foreachIndexed with delete, clsWhen for dark mode.

### 4. NestedReactiveUI — High interaction surface
**User journeys**: Toggle outer/inner panels (nested when), increment global counter, select items in keyed list, switch list/tags view, toggle filter.
**Happy paths**: Hide outer -> both panels gone; Show outer, hide inner -> only inner gone; Select item -> "(selected)" appears; Filter -> only "A" items shown.
**Edge cases**: Hide outer then toggle inner (inner state preserved?), select then filter (selection persists?), toggle nested mode while items displayed.
**JFX vs Web divergence risk**: HIGH — nested UI.when, foreachKeyed with selection state, Signal[UI] nested in collection.

### 5. FormUI — Medium-high interaction surface
**User journeys**: Fill form fields -> submit -> see values; toggle disabled controls.
**Happy paths**: Fill name/email/textarea, select option, check checkbox, submit -> "Submitted: Name=..." displayed.
**Edge cases**: Submit with empty fields, change select option then submit, toggle disabled then try to interact with disabled elements.
**JFX vs Web divergence risk**: HIGH — form.onSubmit is complex on JFX, select/checkbox bindings, disabled state propagation.

### 6. DynamicStyleUI — Medium interaction surface
**User journeys**: Change background color, adjust font size, adjust padding, toggle bold/italic/underline, adjust border width.
**Happy paths**: Click "Green" -> box turns green; Click "A+" -> text gets bigger; Toggle "Bold: OFF" -> text becomes bold.
**Edge cases**: Min/max bounds (font size 8/48, padding 0/48, border 0/10), toggle all style toggles on then off.
**JFX vs Web divergence risk**: MEDIUM — dynamic style strings applied via Signal[String], JFX CSS vs web CSS interpretation.

### 7. InteractiveUI — Medium interaction surface
**User journeys**: Toggle disabled state, keyboard event detection, focus/blur tracking.
**Happy paths**: Click Disable -> target button+input disabled; Type in keyboard input -> key displayed; Focus input -> "Focused".
**Edge cases**: Click disabled button (should no-op), toggle disable twice (re-enable).
**JFX vs Web divergence risk**: MEDIUM — disabled state, keyboard events, focus/blur events may behave differently.

### 8. KeyboardNavUI — Low-medium (keyboard-dependent)
**User journeys**: Type keys to see keyDown/keyUp events, modifier combos, key log, focus management.
**Edge cases**: The test harness can't easily simulate keyboard events — may need to use jfx-fill/web-fill and verify side effects.
**JFX vs Web divergence risk**: HIGH for keyboard events specifically, but hard to test via the harness. Focus styling testable.

## Non-interactive UIs (skip for interaction testing)
- DashboardUI (static)
- AnimatedDashboardUI (auto-animated, no user interaction)
- AutoTransitionUI (auto-animated, no user interaction)
- LayoutUI, TypographyUI, SemanticElementsUI, MultiPseudoStateUI, TransformsUI, ColorSystemUI, SizingUnitsUI, TableAdvancedUI (all static)

## Features most likely to diverge between backends
1. **form.onSubmit** — JFX has no native form submission concept
2. **select/option** — JFX ChoiceBox vs HTML select
3. **checkbox** — JFX CheckBox vs HTML input[type=checkbox]
4. **UI.when conditional rendering** — signal-driven DOM insertion/removal
5. **foreach/foreachKeyed diffing** — list updates, key reconciliation
6. **Signal[UI] view swaps** — replacing entire subtrees
7. **disabled state** — signal-driven disabled propagation
8. **Dynamic style strings** — raw CSS string interpretation on JFX vs web
9. **clsWhen (dark mode)** — dynamic class toggling

## Testing priority order
1. DemoUI (core: counter, todo, theme)
2. FormUI (form submission, disabled controls)
3. ReactiveUI (conditionals, foreach, view mode)
4. NestedReactiveUI (nested conditionals, selection, filter)
5. CollectionOpsUI (add/remove/reorder, empty state)
6. DynamicStyleUI (dynamic style application)
7. InteractiveUI (disabled state, focus/blur)
8. KeyboardNavUI (limited testability via harness)
