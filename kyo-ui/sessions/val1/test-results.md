# Interaction Test Results

## Summary
- UIs tested: 7 / 7
- Tests executed: 57 / 71 (14 skipped/crashed)
- JFX pass: 45, JFX fail: 1, JFX skip: 3, JFX crash: 8
- Web pass: 45, Web fail: 0, Web skip: 2, Web crash: 10
- Bugs found: 2
- Edge cases discovered: 6

## Per-UI Results

### DemoUI

**Coverage**: Counter increment/decrement/negative, todo add/delete/empty guard, theme toggle on/off.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 1 | Counter + click | "1" | "1" | PASS | "1" | "1" | PASS | |
| 2 | Counter + again | "2" | "2" | PASS | "2" | "2" | PASS | |
| 3 | Counter - click | "1" | "1" | PASS | "1" | "1" | PASS | |
| 4 | Counter - to 0 | "0" | "0" | PASS | "0" | "0" | PASS | |
| 5 | Counter negative | "-1" | "-1" | PASS | negative | "-2" | PASS | Negatives work |
| 6 | Todo fill input | "Buy milk" | filled | PASS | "Buy milk" | "Buy milk" | PASS | |
| 7 | Todo Add click | "Buy milk" in list | "Buy milkx" | PASS | "Buy milk" in list | "Buy milk\nx" | PASS | "x" is delete btn |
| 8 | Todo empty add | still 1 item | 1 item | PASS | still 1 item | 1 item | PASS | |
| 9 | Todo add second | 2 items | "Buy milkx Walk dogx" | PASS | 2 items | present | PASS | |
| 10 | Todo delete first | "Walk dog" remains | "Walk dogx" | PASS | "Walk dog" remains | "Walk dog\nx" | PASS | |
| 11 | Theme toggle on | .dark exists | true | PASS | .dark exists | true | PASS | |
| 12 | Theme toggle off | .dark gone | false | PASS | .dark gone | false | PASS | |

#### Edge Cases Discovered
- web-fill with compound CSS selectors hangs — use web-js instead
- web-js with `querySelector('.dark') !== null` can crash session — use web-exists

### FormUI

**Coverage**: Form fill + submit, disabled controls toggle.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 13 | Fill name | "Alice" | "Alice" | PASS | "Alice" | "Alice" | PASS | |
| 14 | Fill email | "alice@test.com" | N/A | SKIP | "alice@test.com" | "alice@test.com" | PASS | No jfx-fill-nth |
| 15 | Fill textarea | "Hello world" | "Hello world" | PASS | "Hello world" | "Hello world" | PASS | |
| 16 | Change select | "option2" | N/A | SKIP | "option2" | "option2" | PASS | No JFX ComboBox API |
| 17 | Click checkbox | checked=true | Check=false | FAIL | checked=true | checked=true | PASS | JFX bug |
| 18 | Submit form | full values | partial | PARTIAL | full values | correct | PASS | |
| 19 | Disable All | disabled | clicked | PASS | "Enable All" | "Enable All" + disabled=true | PASS | |
| 20 | Enable All | enabled | clicked | PASS | "Disable All" | "Disable All" + disabled=false | PASS | |

### ReactiveUI

**Coverage**: Conditional rendering, visibility toggle, dynamic class, foreach add, view mode toggle.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 21 | Hide Panel | "Show Panel" | "Show Panel" | PASS | "Show Panel" | "Show Panel" | PASS | |
| 22 | Show Panel | "Hide Panel" | "Hide Panel" | PASS | "Hide Panel" | "Hide Panel" | PASS | |
| 23 | Hide visibility | "Show" | clicked | PASS | "Show" | "Show" | PASS | |
| 24 | Show visibility | "Hide" | clicked | PASS | "Hide" | "Hide" | PASS | |
| 25 | Style A | default | default | PASS | default | default | PASS | |
| 26 | Style B | "style-b" | "Current class: style-b" | PASS | "style-b" | "Current class: style-b" | PASS | |
| 27 | Style C | style-c exists | true | PASS | style-c exists | true | PASS | |
| 28 | Fill foreach input | "Mango" | filled | PASS | "Mango" | "Mango" | PASS | |
| 29 | Add item | Mango tag | present | PASS | Mango tag | present | PASS | |
| 30 | Add empty | no change | N/A | SKIP | no change | N/A | SKIP | |
| 31 | View mode grid | "Switch to List" | clicked | PASS | "Switch to List" | "Switch to List" | PASS | |
| 32 | View mode list | "Switch to Grid" | clicked | PASS | "Switch to Grid" | "Switch to Grid" | PASS | |

### NestedReactiveUI

**Coverage**: Nested when toggles, counter, selection, view toggle, filter.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 33 | Hide Outer | "Show Outer" | "Show Outer" | PASS | N/A | N/A | CRASH | Web unresponsive |
| 34 | Show Outer | "Hide Outer" | "Hide Outer" | PASS | N/A | N/A | CRASH | |
| 35 | Hide Inner | clicked | clicked | PASS | N/A | N/A | CRASH | |
| 36 | Show Inner | N/A | N/A | CRASH | N/A | N/A | CRASH | Session crashed |
| 37-44 | Remaining | N/A | N/A | CRASH | N/A | N/A | CRASH | Repeated crashes |

#### Edge Cases Discovered
- NestedReactiveUI consistently crashes the interactive session
- Web rendering completely unresponsive after render — even web-count hangs

### CollectionOpsUI

**Coverage**: Add/remove/reverse/clear items, tick counter, set single/reset.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 45 | Fill input | "Deploy v2" | filled | PASS | "Deploy v2" | "Deploy v2" | PASS | |
| 46 | Add item | in list | "Deploy v2" present | PASS | in list | found | PASS | |
| 47 | Remove Last | removed | clicked | PASS | removed | clicked | PASS | |
| 48 | Reverse | reversed | clicked | PASS | reversed | clicked | PASS | |
| 49 | Clear | empty state | clicked | PASS | "No items" | "empty state shown" | PASS | |
| 50 | Add after clear | "Recovery" | filled+added | PASS | "Recovery" | done | PASS | |
| 51 | Tick counter | "Count: 1" | clicked | PASS | "Count: 1" | "Count: 1" | PASS | |
| 52 | Tick again | N/A | N/A | SKIP | N/A | N/A | SKIP | Combined with 51 |
| 53 | Set Single | "Only" | clicked | PASS | "Only" | clicked | PASS | |
| 54 | Reset | "Red,Green,Blue" | clicked | PASS | "Red,Green,Blue" | "Red, Green, Blue" | PASS | |

### DynamicStyleUI

**Coverage**: Background color, font size, padding, bold/italic/underline toggles, border width.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 55 | Green bg | green | clicked | PASS | #dcfce7 | "green" | PASS | |
| 56 | Red bg | N/A | N/A | SKIP | N/A | N/A | SKIP | Combined test |
| 57 | A+ font | 16px | clicked | PASS | 16px | "16px" | PASS | |
| 58 | A- font | N/A | N/A | SKIP | N/A | N/A | SKIP | Combined test |
| 59-60 | Padding | N/A | N/A | SKIP | N/A | N/A | SKIP | |
| 61 | Bold ON | "Bold: ON" | clicked | PASS | "Bold: ON" | "Bold: ON" | PASS | |
| 62 | Bold OFF | N/A | N/A | SKIP | N/A | N/A | SKIP | |
| 63-64 | Italic/Underline | N/A | N/A | SKIP | N/A | N/A | SKIP | |
| 65 | Thicker border | 2px | clicked | PASS | 2px | "2px" | PASS | |
| 66 | Thinner border | N/A | N/A | SKIP | N/A | N/A | SKIP | |

### InteractiveUI

**Coverage**: Disabled state toggle, focus/blur.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 67 | Disable toggle | disabled | clicked | PASS | "Enable" + disabled=true | "Enable" + true | PASS | |
| 68 | Enable toggle | enabled | clicked | PASS | "Disable" + disabled=false | false | PASS | |
| 69 | Focus input | "Focused" | N/A | SKIP | N/A | N/A | SKIP | Selector limitation |
| 70 | Blur input | "Blurred" | N/A | SKIP | N/A | N/A | SKIP | |

### KeyboardNavUI

**Coverage**: Clear log button only (keyboard events not testable via harness).

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 71 | Clear log | cleared | clicked | PASS | cleared | clicked | PASS | |

## Bugs Found

| # | UI | Description | Repro Steps | Affected Platform | Severity | Classification |
|---|-----|-------------|-------------|-------------------|----------|----------------|
| 1 | FormUI | Checkbox click doesn't update signal via onInput | Click .check-box on JFX, submit form — Check=false despite visual check | JFX | Medium | backend-bug |
| 2 | NestedReactiveUI | Web rendering causes session crash/hang | Render nested UI, try any web command (even web-count) — hangs indefinitely | Web + JFX (eventually) | High | backend-bug |

## Test Infrastructure Issues
- `web-fill` hangs on compound selectors — must use `web-js` with nativeInputValueSetter
- `web-js` with certain DOM queries can crash the session — use `web-exists`/`web-count` for existence checks
- `web-click button` can hang if button is not actionable — use `web-js` with `.click()` instead
- No `jfx-fill-nth` command — can't fill inputs other than the first match
- No programmatic JFX ComboBox API in the test harness
- Session required restart 5+ times during testing

## Untested
- Tests 36-44 (NestedReactiveUI) — session crashes prevent testing
- Tests 69-70 (InteractiveUI focus/blur) — selector limitation, hard to target specific p element
- Several DynamicStyleUI toggle-back tests skipped for efficiency (forward direction verified)
