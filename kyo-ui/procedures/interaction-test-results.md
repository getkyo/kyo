# Interaction Test Results

## Summary

| Metric | Value |
|--------|-------|
| Total tests | 118 |
| Passing | 94 (80%) |
| Failing | 24 (20%) |
| Test files | 8 |
| Bugs fixed | 4 |
| Bugs remaining | 7 |

## Test Files

| File | Pass | Fail | Total |
|------|------|------|-------|
| InteractionLayoutTest | 24 | 0 | 24 |
| InteractionTextTest | 23 | 6 | 29 |
| InteractionInputTest | 10 | 1 | 11 |
| InteractionCheckboxTest | 8 | 0 | 8 |
| InteractionSelectTest | 7 | 1 | 8 |
| InteractionFocusTest | 3 | 10 | 13 |
| InteractionFormTest | 5 | 3 | 8 |
| InteractionComplexTest | 14 | 3 | 17 |

## Bugs Fixed (Iteration 1-3)

### Fix 1: SignalRef materialization for TextInput value
**Root cause**: `Lower.readStringOrRef` cast `SignalRef` to `SignalRef.Unsafe` directly (`asInstanceOf`), but `SignalRef` (safe) is a different type.
**Fix**:
- Added `materializeMaybeStringSignal` to `materializeElement` — caches the ORIGINAL ref's unsafe version for two-way binding
- Changed `readStringOrRef` and `writeStringRef` to look up the cached `SignalRef.Unsafe` from `WidgetStateCache` by key
- For `SignalRef` values, cache `ref.unsafe` directly (not `asRef` copy) so writes go back to the original ref
**Tests fixed**: 8 input render tests, 2 input interaction tests

### Fix 2: Focusable elements not focusable by default
**Root cause**: `lowerPassthrough` only added elements to `focusableIds` when `tabIndex.exists(_ >= 0)`. Buttons, anchors, and other `Focusable` elements without explicit `tabIndex` were not focusable.
**Fix**: Check `elem match case _: UI.Focusable => true` when `tabIndex` is `Absent` (focusable by default for Focusable elements like Button, Anchor, RangeInput, FileInput). Note: `Interactive` is too broad — ALL elements (Div, P, etc.) extend Interactive. `Focusable` is the correct marker trait.
**Tests fixed**: click-focus tests, no regression in LowerTest

### Fix 3: Checkbox click toggle eager evaluation
**Root cause**: `widgetOnClick` was a `val` whose body (`checkedRef.get()`, `checkedRef.set(newVal)`) executed eagerly at lower time, not at click time.
**Fix**: Wrapped handler body in `Sync.Unsafe.defer { ... }` to defer execution. Also added internal `SignalRef.Unsafe[Boolean]` for checked state persistence across re-renders (plain `Boolean` attrs had no persistent state).
**Tests fixed**: 3 checkbox toggle tests

### Fix 4: Dispatch click focus check
**Root cause**: `Dispatch.dispatch` for click checked `node.handlers.tabIndex.nonEmpty` to decide whether to focus. Buttons without explicit tabIndex were not focusable on click.
**Fix**: Check `isFocusable = node.handlers.widgetKey.exists(k => state.focusableIds.exists(_ == k))` instead.
**Tests fixed**: click-focus on button

## Remaining Bugs (24 failures)

### Bug A: Tab-based focus cycling doesn't fire onFocus/onBlur (10 tests)
**Symptoms**: `cycleFocus` in `Dispatch` correctly sets `focusedId` and returns `onFocus`/`onBlur` computations, but the `Sync.defer` callbacks inside those handlers don't execute.
**Tests**: All InteractionFocusTest tab tests, some FormTest/ComplexTest tab tests
**Root cause hypothesis**: The `Sync.defer` computation returned by `cycleFocus` is chained correctly but may not be evaluated by the kyo runtime in the test context. Click-based focus works (same handler type), suggesting the issue is specific to how `cycleFocus` composes the computation, or in how `state.focusedId.set()` interacts with the computation chain.
**Impact**: High — keyboard navigation is broken

### Bug B: Text truncation and alignment not applied (4 tests)
**Symptoms**:
- `UI.div.style(Style.width(5.px))("abcdefghij")` renders full text without truncation
- `Style.textAlign(center/right)` has no effect — text always starts at position 0
**Root cause**: Painter writes text starting at the left edge of the content area without checking if text exceeds the container width. `textAlign` is stored in `FlatStyle` but never read during painting.
**Files**: `Painter.scala` — `paintText` method

### Bug C: `br` doesn't create visual line break (1 test)
**Symptoms**: `UI.span("top"), UI.br, UI.span("bot")` renders "topbo" — text concatenated without newline
**Root cause**: `Lower` converts `Br` to `Resolved.Text("\n")` per plan, but the Painter/Layout treats `\n` as a regular character, not a line break signal.
**Files**: `Painter.scala` or `Layout.scala`

### Bug D: `hr` renders incorrect content (1 test)
**Symptoms**: `hr` renders as bordered box eating surrounding content instead of a horizontal line
**Root cause**: Theme style for `hr` is `Style.border(1.px, theme.borderColor).width(100.pct)` which creates a full bordered box. Should be border-bottom only or a simpler horizontal line.
**Files**: `Lower.scala` — `themeStyle` method

### Bug E: Form onSubmit not firing on Enter (3 tests)
**Symptoms**: Enter key in a focused input inside a form doesn't trigger `form.onSubmit`
**Root cause**: Per the design, `onSubmit` should be woven into descendants' `onKeyDown` as an Enter handler during lowering. This weaving may not be happening correctly — `parentOnSubmit` is threaded through `walk` but may not be composed into the input's key handler.
**Files**: `Lower.scala` — `lowerTextInput`, `parentOnSubmit` threading

### Bug F: Select escape handling (1 test)
**Symptoms**: Pressing Escape after opening a select dropdown fires `onChange("banana")` instead of closing without selecting
**Root cause**: The Escape key handler in the select widget may be triggering a selection before closing, or the key routing sends Escape through the wrong handler path.
**Files**: `Lower.scala` — `lowerSelect` Escape handling

### Bug G: Placeholder text not rendered (1 test)
**Symptoms**: Empty input with `placeholder("Enter...")` shows cursor `█` but no placeholder text
**Root cause**: `lowerTextInput` checks the current value and displays it. When empty, it shows just the cursor. Placeholder rendering is not implemented — `ti.placeholder` is never read to produce display text when the value is empty.
**Files**: `Lower.scala` — `lowerTextInput`

### Bug H: Complex form layout truncation (2 tests)
**Symptoms**: Form with row layout of label+input+checkbox shows truncated content
**Root cause**: Layout doesn't properly calculate widths for mixed inline/block elements in a row. The form's children overflow their container.
**Files**: `Layout.scala`

### Bug I: UL list items not rendering correctly (1 test)
**Symptoms**: `UI.ul(UI.li("Item 1"), ...)` — "Item 2" not found in output
**Root cause**: List items rendered in a column layout may be clipped by viewport height, or list marker prepending affects layout.
**Files**: `Lower.scala` — list marker prepending, `Layout.scala`

## Production Code Changes Made

### Lower.scala
1. Added `materializeMaybeStringSignal` method for TextInput/PickerInput value materialization
2. Added `valueEffect` to `materializeElement` for TextInput and PickerInput
3. Changed `readStringOrRef` to take `key` and `state`, look up cached ref instead of casting
4. Changed `writeStringRef` to take `key` and `state`, look up cached ref instead of casting
5. Updated all 6 call sites of readStringOrRef/writeStringRef
6. Added `buildOptionNodes` `key` parameter
7. Made `lowerPassthrough` register `Interactive` elements as focusable by default
8. Added internal `checkedRef` to `lowerBooleanInput` for state persistence
9. Wrapped checkbox `widgetOnClick` body in `Sync.Unsafe.defer`

### Dispatch.scala
1. Changed click focus check from `tabIndex.nonEmpty` to `isFocusable` (checks `focusableIds`)

### UI.scala
1. Changed action callbacks (onClick, onClickSelf, onFocus, onBlur, onSubmit) to by-name (`=> Unit < Async`) with `Sync.defer` wrapping

### Screen.scala (test utility)
1. Changed `assertFrame` return type from `Unit` to `Assertion` using `fail`/`succeed`
2. Changed `renderAndAssert` return type accordingly

## Design Principle Compliance

| Principle | Status |
|-----------|--------|
| No `while`/`var` for control | ✅ All new code uses `@tailrec def loop` or pattern match |
| No `asInstanceOf` | ✅ Removed `asInstanceOf` from readStringOrRef/writeStringRef |
| No `;`-joined statements | ✅ |
| Use `Length` for sizes | ✅ |
| Use `RGB` for colors | ✅ |
| Use enums for categoricals | ✅ |
| Use `Maybe` for absence | ✅ |
| IR types immutable | ✅ No vars added to IR types |
| AllowUnsafe discipline | ✅ `materializeMaybeStringSignal` uses `Sync.Unsafe.defer` |
| Handler closures capture refs | ✅ Checkbox handler uses `Sync.Unsafe.defer` to read ref at dispatch time |
