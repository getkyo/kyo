# Visual Test Rewrite & Expansion Plan

## Current State

- **1104 tests, 0 failures** across 12 Visual test files + existing tests
- **Zero `contains`/`indexOf` violations** — all Visual tests use full frame assertions
- **Known bugs**: popup bottom border clipped when parent has constrained height, signal settlement stale for multiple derived signals

## Remaining Work: New Tests Needed

The existing tests cover basic rendering and simple interactions. The following scenarios are MISSING and need new tests. These match real-world usage patterns from the TuiDemo.

### VisualSelectTest — Dropdown Expansion (P0, demo is broken)

The dropdown is the most broken widget. Need comprehensive coverage of the popup rendering, overlay behavior, and interaction lifecycle. All tests must assert full frames.

**New tests to add:**

1. **Select inside a form with label above** — the demo pattern:
   ```
   Label:
   ┌──────────┐
   │ Dev... ▼ │
   └──────────┘
   ```
   Click → popup below with bordered options. Verify full frame at each step.

2. **Select with 3 options expanded — full frame** — verify every cell including popup border

3. **Select expanded then ArrowDown — frame shows highlight moved** — what does the frame look like during keyboard navigation? If highlight is visual (bold/inverse), the frame should reflect it.

4. **Select expanded, ArrowDown, Enter — full lifecycle** — collapsed before, expanded with options, collapsed after with new value. Assert frame at every stage.

5. **Select inside column layout with sibling below — popup overlays** — the known bug. Popup's bottom border should render. Currently fails because `layoutPopups` uses parent's constrained `available.h`.

6. **Select inside column layout with sibling above AND below** — header, select, footer. Popup overlays footer but header stays.

7. **Select inside row layout beside another widget** — collapsed select beside a label in a row.

8. **Select with long option text in bounded container** — option text truncated within popup border.

9. **Two selects on the same page** — only one expanded at a time? Or both can be?

10. **Select near bottom of viewport** — popup might overflow viewport. What happens?

11. **Disabled select — click has no effect, frame unchanged**

12. **Select with SignalRef binding — select changes ref value**

### VisualFocusTest — Missing Frame Assertions (P1)

38 of 41 tests only check `hasCursor`/`focusedKey` without verifying the actual rendered output. Every focus test should ALSO assert the full frame.

**Tests to add frame assertions to (examples):**

1. **Tab to input — show full frame with cursor position**
2. **Tab from input to button — show frame changed (cursor gone)**
3. **Click on checkbox — show frame with toggled checkbox**
4. **Tab through 3 inputs — show frame at each step**

### VisualFormTest — Demo Form Pattern (P1)

No test shows a complete form layout matching the demo:

1. **Demo-style form: label + input + label + input + button** — full frame
2. **Type in first input, tab, type in second, submit** — frame at each step
3. **Submit clears fields — frame shows empty inputs with placeholders**

### VisualTextInputTest — Input Inside Form (P2)

1. **Input with label above in form** — the demo pattern
2. **Input with explicit width in row layout** — the `width(40.px)` demo pattern
3. **Multiple inputs in a column with labels** — the demo's left panel

### VisualButtonTest — Complete Button Lifecycle (P2)

1. **Button in Default theme — exact border with padding** — `┌────┐ / │ OK │ / └────┘`
2. **Tab to button then tab away — frame unchanged except focus indicator**

### VisualReactiveTest — Multiple Derived Signals (P2)

1. **Two ref.render bindings on same ref** — both should update. Currently "Len" lags (known signal settlement bug).
2. **Input + reactive table** — the demo's submissions table pattern

## Implementation Order

1. Fix the 4 remaining `contains`/`indexOf` violations ✅ DONE
2. Add VisualSelectTest dropdown expansion tests (12 new tests)
3. Add VisualFocusTest frame assertions to existing tests
4. Add VisualFormTest demo form pattern tests
5. Add remaining widget-specific tests

## Known Bugs to Reproduce in Tests

| Bug | Test File | Status |
|-----|-----------|--------|
| Popup bottom border clipped | VisualSelectTest | Need to add test |
| Signal settlement stale (2 derived) | VisualReactiveTest | Exists, currently commented? |
| Popup overlay doesn't cover sibling | VisualSelectTest | Need to add test |
| Popup `available.h` from parent | VisualSelectTest | Root cause identified |
