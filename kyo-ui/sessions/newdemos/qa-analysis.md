# QA Analysis — New Demo UIs

## 1. Interaction Surface Ranking (most to least)

1. **FormResetUI** — 3 text inputs, 1 textarea, 2 selects, 1 checkbox, 4 buttons, 2 forms. Full CRUD lifecycle on form data. Reset/clear tests signal→input→signal round-trips.
2. **RapidMutationUI** — 7 buttons (Add, Remove First, Remove Last, Clear All, Burst Add 5, Toggle, Add While Hidden, Clear Log). Tests keyed list mutations + visibility toggle simultaneously.
3. **SignalSwapUI** — 3 view-switch buttons, 1 text input, 1 add button, per-item delete buttons, 1 rapid-cycle button. Tests subscription cleanup across view swaps.
4. **SignalCombinatorUI** — 2 text inputs, 8 buttons (qty ±, price ±5, rapid), 2 checkboxes. Tests nested Signal[UI] wrapping Signal[String].
5. **DeepNestingUI** — 4 buttons (mode switch, add fruit, add veg, increment, inner toggle, cycle toggle). 3-level nesting: Signal[UI] → foreach → foreachIndexed → Signal[String].
6. **ReactiveHrefUI** — 3 link-target buttons, 3 fragment-mode buttons, 3 mutation buttons. Tests reactive href and fragment rendering.
7. **GenericAttrUI** — 1 attribute cycle button, 1 text input, 1 .on('click') button, 1 dblclick element, 1 clear log button. Tests untested .attr()/.on() APIs.

## 2. Features Most Likely to Diverge Between Backends

### High Risk
- **Nested Signal[UI] + Signal[String]** (SignalCombinatorUI): `firstName.map { f => span(lastName.map { l => ... }): UI }` — creates Signal[UI] containing ReactiveText. The outer signal re-renders the span on firstName change, but the inner ReactiveText listens to lastName. On re-render, the old inner subscription must be cleaned up. The `takeWhile`/`isConnected` fix may not cover this case on JFX.
- **foreachKeyed identity** (RapidMutationUI): Using `identity` as the key function means duplicate items would collide. "Burst Add 5" doesn't create duplicates, but "Add Fruit" in DeepNestingUI always appends "Mango" — multiple items with the same key!
- **Signal[UI] swap + foreachIndexed** (SignalSwapUI): When viewMode changes, the old view's foreachIndexed subscriptions must terminate. The `takeWhile`/`isConnected` approach depends on the old container being disconnected before the new one builds.
- **UI.when inside foreach** (DeepNestingUI): Each foreach item has its own UI.when subscription. Toggling showInner affects ALL items simultaneously — N subscriptions fire at once.

### Medium Risk
- **Generic .attr(name, Signal[String])** (GenericAttrUI): Reactive attributes — might not be implemented on JFX at all, or may work differently from the DOM `setAttribute` path.
- **Generic .on(name, handler)** (GenericAttrUI): The `.on("dblclick", ...)` handler — JFX MouseEvent for double-click requires different event handling than single click.
- **Reactive a.href(Signal[String])** (ReactiveHrefUI): JFX Hyperlink doesn't have href — it's `setOnAction`. Reactive href may not update the underlying action.
- **form.onSubmit** (FormResetUI): Known to work differently on JFX vs web.

### Lower Risk
- **UI.fragment** (ReactiveHrefUI): Fragments are well-tested in DOM but JFX backend may handle them differently when used inside Signal[UI] or foreach.
- **Programmatic reset** (FormResetUI): Calling `signal.set("")` should clear bound inputs — verified working after our `runOnFx` fix.

## 3. Thorough Test Journeys

### SignalCombinatorUI
1. Fill first name → verify combined display updates with first name only
2. Fill last name → verify combined display shows "First Last"
3. Clear first name → verify display shows just last name
4. Click qty+ 3x → verify qty shows "4", total shows "$40"
5. Click price+5 2x → verify price shows "$20", total shows "$80"
6. Click qty- to 0 → verify total shows "$0"
7. Uncheck filter-a → verify Alpha/Beta/Gamma disappear, Delta remains
8. Uncheck filter-b → verify Delta disappears, all gone
9. Re-check both → verify all 4 items back
10. Click "Reset & Add 5" → verify quantity shows "5", total recalculates

### RapidMutationUI
1. Click Add 3x → verify 6 items total (A,B,C + 3 new), count shows 6
2. Click Remove First → verify first item gone, count decreases
3. Click Remove Last → verify last item gone
4. Click Clear All → verify empty state appears, count = 0
5. Click Burst Add 5 → verify 5 items appear at once
6. Click Hide List → verify toggled-list disappears
7. Click Add While Hidden → add item while list hidden
8. Click Show List → verify hidden-added items appear
9. Check operation log reflects all operations
10. Click Clear Log → verify log empties

### DeepNestingUI
1. Verify initial render: 2 categories (Fruits, Vegetables) with items
2. Click Switch to Grid → layout changes to horizontal
3. Click Switch to List → layout changes back
4. Click Add Fruit → "Mango" appears under Fruits
5. Click Add Veg → "Spinach" appears under Vegetables
6. Click Increment Counter → "(clicks: 1)" updates on ALL nested items
7. Toggle mode 4+ times → no crash, fiber cleanup works
8. Click Hide Details → detail text disappears from all foreach items
9. Click Show Details → detail text reappears
10. Toggle cycle button 6+ times → verify no degradation, status text toggles correctly

### SignalSwapUI
1. Default view is Tasks with 3 items
2. Click Notes → view swaps to Notes with 2 items
3. Click Tags → view swaps to Tags with 4 items
4. Click Tasks → back to Tasks, items preserved
5. Fill input + Add → item added to current view
6. Switch view → verify new item only in its view
7. Delete item from Tasks view → item removed
8. Click rapid cycle button → ends on Tasks, swap count increments by 4
9. Cycle 5+ times → no crash or fiber accumulation

### GenericAttrUI
1. Verify static data-* attributes rendered (check via web-js getAttribute)
2. Click Cycle Attribute Value → data-state changes on element
3. Fill title input → verify title attribute updates
4. Click .on('click') button → log entry appears
5. Double-click element → log entry for dblclick appears
6. Clear log → log empties

### ReactiveHrefUI
1. Click GitHub → link text and href update
2. Click Scala → link text and href update again
3. Click Example.com → back to original
4. Verify static fragment renders 3 colored spans
5. Verify foreach fragment renders items with hr separators
6. Click Simple/Nested/Reactive fragment modes → content swaps
7. Click Add Item → item appears in tag list
8. Click Remove Last → last item removed
9. Click Clear → all items cleared

### FormResetUI
1. Fill name, email, message → verify preview shows values
2. Click Submit → submission appears in history
3. Click Clear All → all fields empty, preview shows "(empty)"
4. Re-fill fields → verify round-trip works after clear
5. Change theme select → verify preview updates
6. Check notifications checkbox → verify preview shows true
7. Change priority select → verify preview updates
8. Click Save Settings → submission with all settings in history
9. Click Reset to Defaults → username empty, theme=light, notify=false, priority=medium
10. Click Clear History → submission list empties
