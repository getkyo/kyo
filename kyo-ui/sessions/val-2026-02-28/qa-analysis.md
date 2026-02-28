# QA Analysis

## UIs Ranked by Interaction Surface

1. **DemoUI** — Counter (inc/dec), todo list (add/delete), theme toggle, form (static). Highest interaction density.
2. **CollectionOpsUI** — Add/Remove/Reverse/Clear, Tick counter, Set Single/Reset. Many mutation patterns.
3. **DynamicStyleUI** — 5 color buttons, A-/A+, Less/More, Bold/Italic/Underline toggles, Thinner/Thicker. Many controls.
4. **NestedReactiveUI** — Nested conditionals (outer/inner), counter, selection, view mode toggle, filter toggle.
5. **ReactiveUI** — Conditional show/hide, dynamic class, foreach add, view mode toggle.
6. **FormUI** — Full form: text, email, textarea, select, checkbox, submit + disable toggle.
7. **KeyboardNavUI** — Key events (down/up/modifiers), event log + clear, focus management.
8. **InteractiveUI** — Hover/active pseudo states, keyboard input, focus/blur, disable toggle.
9. **TableAdvancedUI** — Dynamic table add/remove rows.
10. **MultiPseudoStateUI** — Hover/active/focus pseudo states only (no signal mutation).

## UIs with No Interaction (Static/Animated)

- LayoutUI, TypographyUI, SemanticElementsUI, TransformsUI, ColorSystemUI, DashboardUI, SizingUnitsUI
- AutoTransitionUI, AnimatedDashboardUI (fiber-driven, no user interaction)

## Features Most Likely to Diverge Between Backends

1. **Form elements** — select, checkbox, textarea sizing, disabled state styling
2. **Dynamic CSS via .style(signal)** — inline CSS string injection may differ in JFX
3. **Pseudo states** — hover/active/focus may not be testable programmatically in JFX
4. **Table layout** — colspan/rowspan, striped rows, percentage widths
5. **Text overflow/ellipsis** — different text rendering engines
6. **Scroll overflow** — JFX ScrollPane vs CSS overflow:scroll
7. **Transforms** — translate positioning differences
8. **Shadow rendering** — JFX DropShadow vs CSS box-shadow
9. **Opacity** — compositing differences
10. **Keyboard events** — modifier key names, event propagation

## Thorough Test Journeys

### DemoUI
Full journey: increment counter 3x → decrement 1x → verify count=2 → add 3 todos → delete middle one → verify 2 remain → toggle dark mode → verify background changes → toggle back

### FormUI
Fill all fields → submit → verify aggregated output → toggle disable → verify all controls disabled → toggle back → verify re-enabled

### CollectionOpsUI
Add 3 items → reverse → verify order flipped → remove last → verify count → clear → verify empty state shown → add 1 → verify empty state hidden

### DynamicStyleUI
Cycle through all 5 colors → increase font 3x → toggle bold on/off → increase border → verify each change reflected in preview

### NestedReactiveUI
Show outer → show inner → hide inner → verify inner gone, outer still visible → hide outer → verify both gone → show outer → verify inner still hidden
