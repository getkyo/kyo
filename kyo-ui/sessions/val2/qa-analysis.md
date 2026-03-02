# QA Analysis — Fix Validation (val2)

## Purpose
Validate 7 fixes from val1 session and check for regressions.

## Fixes Under Test

### Fix 1: FormUI CheckBox onInput (Issue 1)
- **What changed**: `JavaFxInteraction.click()`/`clickNth()` now uses `ButtonBase.fire()` for CheckBox/Button instead of raw MouseEvent
- **Test**: Click checkbox on JFX, submit form, verify Check=true
- **Regression risk**: All JFX click operations now use `.fire()` for ButtonBase — verify counter buttons, todo delete, theme toggle still work

### Fix 2-3: NestedReactiveUI crash (Issues 2-3)
- **What changed**: `subscribeUI` and `subscribeForeach`/`subscribeList` in both DomBackend and JavaFxBackend now wrap `build()` in `Scope.run` inside `Fiber.initUnscoped`
- **Test**: Render nested UI, interact with all 5 sections on both platforms without crash
- **Regression risk**: All Signal[UI] and foreach subscriptions use the new scoping — test ReactiveUI, CollectionOpsUI, DemoUI which also use these patterns

### Fix 4: jfx-fill-nth command (Issue 4)
- **What changed**: Added `fillTextNth` method and `jfx-fill-nth` command
- **Test**: Fill nth input in FormUI (email field is 2nd input)

### Fix 5: jfx-select and jfx-check commands (Issue 5)
- **What changed**: Exposed existing `selectOption()` and `setChecked()` as commands
- **Test**: Use `jfx-select` on FormUI dropdown, `jfx-check` on FormUI checkbox

### Fix 6: web-fill JS-based (Issue 6)
- **What changed**: web-fill now uses JS nativeInputValueSetter instead of Playwright fill
- **Test**: Use `web-fill` with compound selectors (e.g., `.todo-input input`)

### Fix 7: web-click JS-based (Issue 7)
- **What changed**: web-click now uses JS `.click()` instead of Playwright click
- **Test**: Use `web-click` on various selectors

## Regression Test Coverage

### DemoUI (uses foreach, Signal text, click handlers)
- Counter +/- buttons (tests ButtonBase.fire() regression)
- Todo add/delete (tests foreach subscription lifecycle)
- Theme toggle (tests signal-driven class toggling)

### ReactiveUI (uses UI.when, foreach, Signal[UI])
- Conditional show/hide
- Dynamic class switching
- Foreach add item
- View mode toggle (Signal[UI] with foreach inside — same pattern as NestedReactiveUI)

### CollectionOpsUI (uses foreach, batch updates)
- Add/remove/reverse/clear items
- Tick counter (batch signal updates)

## UIs to Test (ordered by priority)
1. **FormUI** — Fix 1 (checkbox), Fix 4 (fill-nth), Fix 5 (select/check)
2. **NestedReactiveUI** — Fixes 2-3 (crash fix, most critical)
3. **DemoUI** — Regression (click handlers, foreach, web-fill compound selectors)
4. **ReactiveUI** — Regression (Signal[UI] with foreach, same pattern as nested)
5. **CollectionOpsUI** — Regression (foreach lifecycle)
