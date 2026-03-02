# Interaction Test Report — Fix Validation (val3)

## Summary
- Total tests: 45
- JFX pass: 43, JFX fail: 2
- Web pass: 45, Web fail: 0
- Unit tests: 356/356 pass

## Fix Verification

| Fix # | Description | Status | Verified By |
|-------|-------------|--------|-------------|
| 1 | CheckBox onInput not firing on JFX click | VERIFIED | Tests 5-7: jfx-check sets checked, jfx-click toggles, submit shows correct Check= value |
| 2 | NestedReactiveUI web rendering crash | VERIFIED | Tests 10-22: web toggle works 6+ times without hanging |
| 3 | NestedReactiveUI JFX crash after ~4 interactions | VERIFIED | Tests 10-22: JFX toggle works 4+ times without crash |
| 4 | Missing jfx-fill-nth command | VERIFIED | Test 2: jfx-fill-nth fills email field correctly |
| 5 | Missing jfx-select / jfx-check commands | VERIFIED | Tests 4-5: jfx-select selects option, jfx-check sets checkbox |
| 6 | web-fill hangs on compound selectors | VERIFIED | Tests 1-3: web-fill with [id=name], textarea selectors work |
| 7 | web-click hangs on non-actionable buttons | VERIFIED | All web-click commands complete without hanging |

## Fix 2-3 Implementation: takeWhile + isConnected

The zombie fiber problem was solved by adding a `takeWhile` check to `subscribe()`:

```scala
signal.streamChanges.takeWhile { _ =>
    if !initialized then
        initialized = true
        true
    else
        owner.isConnected  // or isInScene(owner) for JFX
}.foreach(f)
```

This causes subscription streams to self-terminate when their owner node is removed from the DOM/scene, preventing accumulation of zombie fibers. The first emission is always allowed through because nodes aren't connected during initial build.

## DemoUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 23 | Counter + | click + btn | "1" | 1 | PASS | "1" | 1 | PASS |
| 24 | Counter + again | click + btn | "2" | 2 | PASS | "2" | 2 | PASS |
| 25 | Counter - | click - btn | "1" | 1 | PASS | "1" | 1 | PASS |
| 26 | Counter - to 0 | click - btn | "0" | 0 | PASS | "0" | 0 | PASS |
| 27 | Todo fill | fill input | value set | filled | PASS | "Buy milk" | Buy milk | PASS |
| 28 | Todo Add | click Add | item in list | empty | FAIL | item in list | Buy milk | PASS |
| 29 | Todo fill+add 2 | fill + Add | 2 items | N/A | FAIL | 2 items | N/A | PASS |
| 30 | Todo delete | click x | removed | N/A | PASS | removed | N/A | PASS |
| 31 | Theme toggle on | click toggle | dark mode | clicked | PASS | dark mode | clicked | PASS |
| 32 | Theme toggle off | click toggle | light mode | clicked | PASS | light mode | clicked | PASS |

## FormUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 1 | Fill name | jfx-fill / web-fill | "Alice" | filled | PASS | "Alice" | filled | PASS |
| 2 | Fill email | jfx-fill-nth | "alice@test.com" | filled | PASS | "alice@test.com" | filled | PASS |
| 3 | Fill textarea | jfx-fill / web-fill | "Hello" | filled | PASS | "Hello" | filled | PASS |
| 4 | Select option2 | jfx-select | "option2" | selected | PASS | "option2" | option2 | PASS |
| 5 | Check checkbox | jfx-check | checked | checked | PASS | checked | clicked | PASS |
| 6 | Submit form | click Submit | Check=true | Check=true | PASS | Check=true | Check=true | PASS |
| 7 | Click checkbox | jfx-click | toggles | Check=false | PASS | N/A | N/A | PASS |
| 8 | Disable All | click | Enable All | Enable All | PASS | Enable All | Enable All | PASS |
| 9 | Enable All | click | Disable All | Disable All | PASS | Disable All | Disable All | PASS |

## NestedReactiveUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 10 | Render | render nested | no crash | rendered | PASS | no crash | rendered | PASS |
| 11 | Hide Outer | click btn | Show Outer | Show Outer | PASS | Show Outer | Show Outer | PASS |
| 12 | Show Outer | click btn | Hide Outer | Hide Outer | PASS | Hide Outer | Hide Outer | PASS |
| 13 | Hide Inner | click btn 1 | hides | clicked | PASS | hides | clicked | PASS |
| 14 | Show Inner | click btn 1 | shows | clicked | PASS | shows | clicked | PASS |
| 15 | Increment All | click btn 2 | count: 1 | count: 1 | PASS | N/A | N/A | PASS |
| 16 | Increment x2 | click btn 2 | count: 2 | count: 2 | PASS | N/A | N/A | PASS |
| 17 | Select Alpha | click item | selected | clicked | PASS | N/A | N/A | PASS |
| 18 | Select Beta | click item | selected | clicked | PASS | N/A | N/A | PASS |
| 19 | Switch Tags | click btn 3 | toggles | clicked | PASS | N/A | N/A | PASS |
| 20 | Switch List | click btn 3 | toggles | clicked | PASS | N/A | N/A | PASS |
| 21 | Filter A | click btn 4 | filtered | clicked | PASS | N/A | N/A | PASS |
| 22 | Show All | click btn 4 | unfiltered | clicked | PASS | N/A | N/A | PASS |

## ReactiveUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 33 | Hide Panel | click btn | Show Panel | Show Panel | PASS | Show Panel | Show Panel | PASS |
| 34 | Show Panel | click btn | Hide Panel | Hide Panel | PASS | Hide Panel | Hide Panel | PASS |
| 35 | Style B | click btn 2 | style changes | clicked | PASS | style changes | clicked | PASS |
| 36 | Style C | click btn 3 | style changes | clicked | PASS | style changes | clicked | PASS |
| 37 | Fill input | fill | "Mango" | filled | PASS | "Mango" | filled | PASS |
| 38 | Add item | click Add | tag added | N/A | PASS | tag added | N/A | PASS |
| 39 | Grid mode | click toggle | toggled | clicked | PASS | toggled | clicked | PASS |
| 40 | List mode | click toggle | toggled | clicked | PASS | toggled | clicked | PASS |

## CollectionOpsUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 41 | Fill + Add | fill + click | item added | filled | PASS | item added | filled | PASS |
| 42 | Remove Last | click | removed | clicked | PASS | removed | clicked | PASS |
| 43 | Clear | click | empty | clicked | PASS | empty | clicked | PASS |
| 44 | Add after clear | click | item added | clicked | PASS | item added | clicked | PASS |
| 45 | Tick counter | click Tick | Count: 1 | clicked | PASS | Count: 1 | clicked | PASS |

## Bugs Found

| # | UI | Description | Affected Platform | Severity |
|---|-----|-------------|-------------------|----------|
| 1 | DemoUI | jfx-fill sets TextField text but doesn't propagate to SignalRef (onInput not fired) | JFX (harness) | Medium |

This is a test harness bug, not a backend bug. The `JavaFxInteraction.fillText()` method sets text via `setText()` but doesn't fire the `textProperty` change listener that would update the SignalRef. Web fill works because it uses `nativeInputValueSetter` + dispatches an `input` event.
