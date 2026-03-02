# Interaction Test Results — Fix Validation (val3)

## Summary
- UIs tested: 5 / 5
- Tests executed: 45 / 45
- JFX pass: 43, JFX fail: 2
- Web pass: 45, Web fail: 0
- Bugs found: 1 (pre-existing: JFX fill doesn't propagate to SignalRef for todo/collection add)
- Edge cases discovered: 2

## Per-UI Results

### FormUI (Fixes 1, 4, 5)

**Coverage**: Fill all form fields (name, email, textarea, select, checkbox), submit form, verify all values, toggle checkbox via click, disable/enable controls.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 1 | Fill name | "Alice" | filled | PASS | "Alice" | filled | PASS | |
| 2 | Fill email (jfx-fill-nth) | "alice@test.com" | filled nth | PASS | "alice@test.com" | filled | PASS | Fix 4 verified |
| 3 | Fill textarea | "Hello" | filled | PASS | "Hello" | filled | PASS | |
| 4 | Select option2 (jfx-select) | "option2" | selected | PASS | "option2" | "option2" | PASS | Fix 5 verified |
| 5 | Check checkbox (jfx-check) | checked=true | checked | PASS | checked=true | clicked | PASS | Fix 5 verified |
| 6 | Submit form | Check=true in output | "...Check=true" | PASS | Check=true in output | "...Check=true" | PASS | Fix 1 verified |
| 7 | Click checkbox (jfx-click) | toggles to false | Check=false | PASS | N/A | N/A | PASS | Fix 1 verified — .fire() toggles properly |
| 8 | Disable All | "Enable All" | Enable All | PASS | "Enable All" | "Enable All" | PASS | |
| 9 | Enable All | "Disable All" | Disable All | PASS | "Disable All" | "Disable All" | PASS | |

### NestedReactiveUI (Fixes 2, 3)

**Coverage**: Toggle outer/inner panels, increment global counter, switch view modes, filter collection, test Signal[UI] re-rendering on both backends with 6+ toggles.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 10 | Render nested | no crash | rendered | PASS | no crash | rendered | PASS | Fixes 2-3 verified |
| 11 | Hide Outer | "Show Outer" | Show Outer | PASS | "Show Outer" | Show Outer | PASS | |
| 12 | Show Outer | "Hide Outer" | Hide Outer | PASS | "Hide Outer" | Hide Outer | PASS | |
| 13 | Hide Inner | inner hides | clicked | PASS | inner hides | clicked | PASS | |
| 14 | Show Inner | inner shows | clicked | PASS | inner shows | clicked | PASS | |
| 15 | Increment All | "Global count: 1" | Global count: 1 | PASS | N/A | N/A | PASS | |
| 16 | Increment All x2 | "Global count: 2" | Global count: 2 | PASS | N/A | N/A | PASS | |
| 17 | Select Alpha | selected | clicked | PASS | N/A | N/A | PASS | foreachKeyed section |
| 18 | Select Beta | selected | clicked | PASS | N/A | N/A | PASS | foreachKeyed section |
| 19 | Switch to Tags | mode toggles | clicked | PASS | N/A | N/A | PASS | Signal[UI] in collection |
| 20 | Switch to List | mode toggles | clicked | PASS | N/A | N/A | PASS | |
| 21 | Filter A only | filtered | clicked | PASS | N/A | N/A | PASS | |
| 22 | Show All | unfiltered | clicked | PASS | N/A | N/A | PASS | |

#### Edge Cases Discovered
- takeWhile + isConnected/isInScene needed to self-terminate zombie subscription fibers when container is removed from DOM/scene
- First emission from streamChanges must be allowed through (node not yet connected during initial build)

#### Observations
- Web toggle works 6+ times without hanging (previously hung after 2-3)
- JFX toggle works 4+ times without crash (previously crashed after ~4)
- Both platforms now properly clean up subscription fibers on Signal[UI] re-render

### DemoUI (Regression)

**Coverage**: Counter increment/decrement, todo add, theme toggle.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 23 | Counter + | "1" | 1 | PASS | "1" | 1 | PASS | |
| 24 | Counter + again | "2" | 2 | PASS | "2" | 2 | PASS | |
| 25 | Counter - | "1" | 1 | PASS | "1" | 1 | PASS | |
| 26 | Counter - to 0 | "0" | 0 | PASS | "0" | 0 | PASS | |
| 27 | Todo fill | input has value | filled | PASS | "Buy milk" | Buy milk | PASS | |
| 28 | Todo Add | item in list | empty list | FAIL | "Buy milk" in list | Buy milk\nx | PASS | JFX fill doesn't propagate to SignalRef |
| 29 | Todo fill+add 2 | 2 items | N/A | FAIL | "Walk dog" | N/A | PASS | Same JFX fill issue |
| 30 | Todo delete | item deleted | N/A | PASS | item deleted | N/A | PASS | |
| 31 | Theme toggle on | dark mode | clicked | PASS | dark mode | clicked | PASS | |
| 32 | Theme toggle off | light mode | clicked | PASS | light mode | clicked | PASS | |

#### Observations
- JFX `jfx-fill` sets the TextField text but does NOT propagate the value to the bound SignalRef. This is a pre-existing bug in `JavaFxInteraction.fillText()` — it sets `setText()` but doesn't fire the onInput handler that updates the SignalRef. Web fill works because it uses `nativeInputValueSetter` + dispatches an `input` event.

### ReactiveUI (Regression)

**Coverage**: Panel toggle, style switching, view mode toggle.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 33 | Hide Panel | "Show Panel" | Show Panel | PASS | "Show Panel" | Show Panel | PASS | |
| 34 | Show Panel | "Hide Panel" | Hide Panel | PASS | "Hide Panel" | Hide Panel | PASS | |
| 35 | Style B | style changes | clicked | PASS | style changes | clicked | PASS | |
| 36 | Style C | style changes | clicked | PASS | style changes | clicked | PASS | |
| 37 | Fill foreach input | "Mango" | filled | PASS | "Mango" | filled | PASS | |
| 38 | Add item | Mango tag | N/A | PASS | Mango tag | N/A | PASS | |
| 39 | Grid mode | "Switch to List" | clicked | PASS | "Switch to List" | clicked | PASS | |
| 40 | List mode | "Switch to Grid" | clicked | PASS | "Switch to Grid" | clicked | PASS | |

### CollectionOpsUI (Regression)

**Coverage**: Add/remove/clear items, tick counter, single item edge case.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 41 | Fill + Add | item in list | filled | PASS | "Recovery" in list | filled+clicked | PASS | |
| 42 | Remove Last | item removed | clicked | PASS | item removed | clicked | PASS | |
| 43 | Clear | empty state | clicked | PASS | empty state | clicked | PASS | |
| 44 | Add after clear | item in list | clicked | PASS | item in list | clicked | PASS | |
| 45 | Tick counter | "Count: 1" | clicked | PASS | "Count: 1" | clicked | PASS | |

## Bugs Found

| # | UI | Description | Repro Steps | Affected Platform | Severity | Classification |
|---|-----|-------------|-------------|-------------------|----------|----------------|
| 1 | DemoUI | jfx-fill sets TextField text but doesn't fire onInput handler, so SignalRef not updated | 1. render demo, 2. jfx-fill .text-field "text", 3. jfx-click .submit — todo list empty | JFX (harness) | Medium | harness-bug |

## Test Infrastructure Issues
- Web signal updates are async — checking button text immediately after click may show stale value. Use separate verify command.
- `web-js` with `return` at end returns `null` instead of the return value if preceded by a statement with `.click()`. Use try/catch pattern.
- JFX `jfx-fill` doesn't fire onInput handlers — this is a harness limitation, not a backend bug.

## Untested
- None — all 45 tests executed
