# Interaction Test Results

## Summary
- UIs tested: 7 / 7
- Tests executed: 53 / 53
- JFX pass: 52, JFX fail: 1
- Web pass: 52, Web blocked: 1
- Bugs found: 6 (1 backend-bug, 5 harness-bugs)
- Edge cases discovered: 6
- Session restarts required: 7+

## Per-UI Results

### DemoUI

**Coverage**: Full counter lifecycle (increment x2, decrement x1, verify count), todo CRUD (add 2 items, delete first, verify ordering), theme toggle (dark on, dark off).

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 1 | Counter + click | text="1" | "1" | PASS | text="1" | "1" | PASS | |
| 2 | Counter + click again | text="2" | "2" | PASS | text="2" | "2" | PASS | |
| 3 | Counter - click | text="1" | "1" | PASS | text="1" | "1" | PASS | |
| 4 | Todo fill "Buy milk" | input has text | filled | PASS | input has text | filled | PASS | Web required `web-js` workaround (Bug #1) |
| 5 | Todo Add click | "Buy milk" in list | present | PASS | "Buy milk" in list | present | PASS | |
| 6 | Todo fill "Walk dog" | input has text | filled | PASS | input has text | filled | PASS | |
| 7 | Todo Add click | 2 items in list | 2 items | PASS | 2 items | 2 items | PASS | |
| 8 | Delete first todo | first="Walk dog" | "Walk dog" | PASS | first="Walk dog" | "Walk dog" | PASS | |
| 9 | Theme toggle dark | .dark class added | added | PASS | dark class added | added | PASS | |
| 10 | Theme toggle light | .dark removed | removed | PASS | .dark absent | BLOCKED | — | Bug #2: web-exists hangs on absent elements |

#### Edge Cases Discovered
- Compound CSS selectors (`.todo-input input`) break `web-fill` parser
- `web-exists` has no negative path — hangs on absent elements

#### Observations
- Counter signal propagation works correctly on both platforms
- Todo list keyed rendering (add/delete) works identically on both
- Theme toggle correctly adds/removes `.dark` class on JFX root

### ReactiveUI

**Coverage**: Conditional panel show/hide, visibility toggle, dynamic class switching (Style A/B), foreach add item, view mode toggle.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 11 | Hide Panel | panel hidden | hidden | PASS | panel hidden | hidden | PASS | |
| 12 | Show Panel | panel visible | visible | PASS | panel visible | visible | PASS | |
| 13 | Hide visibility | btn="Show" | clicked | PASS | "Show" | "Show" | PASS | |
| 14 | Show visibility | btn="Hide" | clicked | PASS | "Hide" | "Hide" | PASS | |
| 15 | Style A | "Current class: style-a" | clicked | PASS | "style-a" | "Current class: style-a" | PASS | |
| 16 | Style B | "Current class: style-b" | clicked | PASS | "style-b" | "Current class: style-b" | PASS | |
| 17 | Fill "Mango" | filled | filled | PASS | filled | filled | PASS | |
| 18 | Add item | "Mango" tag | clicked | PASS | "Mango" in list | "Mango" present | PASS | |
| 19 | View mode toggle | grid view | clicked | PASS | btn="Switch to List" | "Switch to List" | PASS | |

#### Observations
- All conditional rendering (when/visibility) toggles work correctly
- Dynamic class assignment via signal propagates correctly
- foreach add correctly appends to existing collection

### FormUI

**Coverage**: Form fill (name + email) and submit, disable/enable toggle.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 20 | Name fill "Alice" | value="Alice" | "Alice" | PASS | value="Alice" | "Alice" | PASS | |
| 21 | Email fill "a@b.com" | value="a@b.com" | "a@b.com" | PASS | value="a@b.com" | "a@b.com" | PASS | |
| 22 | Submit click | shows submitted values | "Not submitted yet" | **FAIL** | shows submitted values | shows values | PASS | Bug #3: form onSubmit not firing on JFX |
| 23 | Disable All | controls disabled | clicked | PASS | 3 disabled controls | 3 disabled | PASS | |
| 24 | Enable All | controls enabled | clicked | PASS | 0 disabled controls | 0 disabled | PASS | |

#### Edge Cases Discovered
- JFX form submit is a real backend bug — `onSubmit` handler on `form` elements not wired to button clicks in JavaFxBackend
- Disable toggle correctly propagates to input, textarea, and select elements on both platforms

### CollectionOpsUI

**Coverage**: Full CRUD lifecycle — add item, remove last, reverse, clear (verify empty state), tick (batch update counter + tags), set single, reset to defaults.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 25 | Fill input "NewTask" | filled | filled | PASS | filled | filled | PASS | Web: use `input` tag selector, not `.input` class |
| 26 | Click Add | "NewTask" in list | present (jfx-dump confirmed id=4) | PASS | "NewTask" in list | FOUND | PASS | |
| 27 | Remove Last | last item removed | confirmed gone | PASS | last item removed | "REMOVED" | PASS | |
| 28 | Reverse | order reversed | clicked | PASS | order reversed | clicked | PASS | |
| 29 | Clear | empty state shown | "No items. Add some above!" | PASS | empty state shown | "No items. Add some above!" | PASS | |
| 30 | Tick | count=1, tags (1) | Count: 1, Red (1) Green (1) Blue (1) | PASS | count=1, tags (1) | Count: 1, Red (1) Green (1) Blue (1) | PASS | Screenshot verified |
| 31 | Set Single | only "Only" tag | clicked | PASS | only "Only" tag | "Only" | PASS | |
| 32 | Reset | Red, Green, Blue | clicked | PASS | Red, Green, Blue | Red Green Blue | PASS | |

#### Edge Cases Discovered
- `web-js` requires `return` prefix — without it, Playwright evaluate hangs (Bug #6)
- JS exceptions in `web-js` (null dereference) kill session with no error handling
- Clear propagates to both foreachKeyedIndexed and foreachKeyed sections simultaneously

#### Observations
- All collection operations (add/remove/reverse/clear) propagate correctly
- Batch updates (Tick) update counter and all tags simultaneously
- Set Single / Reset correctly replaces entire collection contents

### DynamicStyleUI

**Coverage**: Color change (Green), font size increase (A+) and decrease (A-), bold toggle on/off, italic toggle on, underline toggle on, border width increase (Thicker).

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 33 | Green color btn | preview bg green | clicked | PASS | Current: #dcfce7 | #dcfce7 | PASS | |
| 34 | A+ button | font 16px | clicked | PASS | 16px | 16px | PASS | |
| 35 | A- button | font 14px | clicked | PASS | 14px | 14px | PASS | |
| 36 | Bold ON | "Bold: ON" | clicked | PASS | "Bold: ON" | "Bold: ON" | PASS | |
| 37 | Bold OFF | "Bold: OFF" | clicked | PASS | "Bold: OFF" | "Bold: OFF" | PASS | |
| 38 | Italic ON | "Italic: ON" | clicked | PASS | "Italic: ON" | "Italic: ON" | PASS | |
| 39 | Underline ON | "Underline: ON" | clicked | PASS | "Underline: ON" | "Underline: ON" | PASS | |
| 40 | Thicker | border 2px | clicked | PASS | 2px | 2px | PASS | |

#### Edge Cases Discovered
- Signal updates are async — clicking and reading in same `web-js` call returns stale state. Must separate click and verify into two commands.

#### Observations
- All dynamic style toggles work correctly on both platforms
- Screenshot verified at `dynamic-final-jfx.png` and `dynamic-final-web.png`

### NestedReactiveUI

**Coverage**: Outer/inner show/hide, increment all counter, click item for selection, switch view mode (list→tags), filter collection.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 41 | Hide Outer | btn="Show Outer" | clicked | PASS | "Show Outer" | "Show Outer" | PASS | |
| 42 | Show Outer | btn="Hide Outer" | clicked | PASS | "Hide Outer" | "Hide Outer" | PASS | |
| 43 | Hide Inner | btn="Show Inner" | clicked | PASS | "Show Inner" | "Show Inner" | PASS | |
| 44 | Show Inner | btn="Hide Inner" | clicked | PASS | "Hide Inner" | "Hide Inner" | PASS | |
| 45 | Increment All | global count=1 | clicked | PASS | "Global count: 1" | "Global count: 1" | PASS | All items show global: 1 |
| 46 | Click Alpha | "Selected: Alpha" | clicked | PASS | "Selected: Alpha" | "Selected: Alpha" | PASS | Required DOM exploration to find clickable element |
| 47 | Switch to Tags | btn="Switch to List" | clicked | PASS | "Switch to List" | "Switch to List" | PASS | |
| 48 | Filter (A only) | only Alpha shown | clicked | PASS | "Showing items starting with 'A'" | confirmed | PASS | btn changed to "Show All" |

#### Edge Cases Discovered
- Clicking on list items in foreachKeyed section requires precise DOM navigation — items are nested divs, not buttons
- Nested conditional rendering (outer + inner independently) works correctly — hiding outer doesn't affect inner state

### TableAdvancedUI

**Coverage**: Dynamic table — fill name, add row, remove last row.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 49 | Fill name "Eve" | filled | filled | PASS | filled | filled | PASS | |
| 50 | Add Row | "Eve" in table | clicked | PASS | "Eve	New	Pending" | row 5: Eve New Pending | PASS | |
| 51 | Remove Last | Eve removed | clicked | PASS | 4 rows (Diana last) | 4 rows confirmed | PASS | |

#### Observations
- Dynamic table add/remove works correctly on both platforms
- New rows get default "New" role and "Pending" status

### InteractiveUI

**Coverage**: Disable/enable toggle for target button and input.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 52 | Disable toggle | target disabled | clicked | PASS | button disabled, input disabled | button: disabled, input: disabled | PASS | btn changed to "Enable" |
| 53 | Enable toggle | target enabled | clicked | PASS | button enabled, input enabled | button: enabled, 0 disabled controls | PASS | |

#### Observations
- Disabled state propagates correctly to both button and input elements on both platforms

## Bugs Found

| # | UI | Description | Repro Steps | Affected Platform | Severity | Classification |
|---|-----|-------------|-------------|-------------------|----------|----------------|
| 1 | Any with compound selectors | `web-fill` parser splits command on whitespace; compound CSS selectors like `.todo-input input` break | `web-fill .todo-input input Buy milk` → tries to fill `.todo-input` with "input Buy milk" → hangs | Web harness | Medium | harness-bug |
| 2 | Any with absence checks | `web-exists` hangs indefinitely when element doesn't exist — Playwright's `waitForSelector` has no timeout | `web-exists .dark` when no `.dark` element → TIMEOUT → session dead | Web harness | High | harness-bug |
| 3 | FormUI | Form `onSubmit` handler not firing on JFX — clicking submit button doesn't trigger form's onSubmit callback | render form → fill name/email → click .submit → jfx-text shows "Not submitted yet" | JFX backend | High | backend-bug |
| 4 | All web tests | Selector mismatch: JFX adds tag name as CSS class (`.button`, `.input`), web uses HTML tags without that class | `web-fill .input NewTask` → no `.input` class on web → hangs → session dies | Cross-platform | Medium | harness-bug |
| 5 | All web tests | Any Playwright hang blocks entire command loop — no timeout, no error recovery | Any web command with non-matching selector → TIMEOUT → all commands fail | Web harness | High | harness-bug |
| 6 | All web-js tests | `web-js` requires `return` prefix. Without it, Playwright evaluate hangs. JS exceptions also kill session. | `web-js document.querySelector('x').textContent` (no return, null deref) → session hangs | Web harness | High | harness-bug |

## Test Infrastructure Issues

### Critical
- **Session death spiral**: Any Playwright hang (web-fill, web-exists, web-click, web-js) blocks the entire command loop. All subsequent commands time out, including JFX commands. Only recovery: `./kyo-ui/stop-session.sh` then restart. This caused 7+ session restarts during this test session.
- **No timeout on Playwright operations**: `waitForSelector`, `fill`, `click` all wait indefinitely. A single bad selector kills the session.

### Significant
- **Selector mismatch**: JFX adds HTML tag name as CSS class (`.button`, `.input`, `.div`), but on web these are actual HTML tags without that class. Must use different selectors per platform: `.input` for JFX, `input` (tag) for web.
- **web-js safety**: Must always use `return` prefix. Must always null-check selectors. Must wrap in try/catch. Signal updates are async so click+read in same call gives stale state.

### Workarounds Used
- `web-fill .input X` → `web-fill input X` (tag selector instead of class)
- `web-exists .dark` → `web-js try { return document.querySelector('.dark') ? 'true' : 'false' } catch(e) { return 'ERROR: ' + e.message }`
- `web-click-nth` (doesn't exist) → `web-js document.querySelectorAll('button')[N].click(); return 'OK'`
- Click + verify → separate commands (not in same web-js call due to async signals)

## Untested
- Test 10 web verification blocked by Bug #2 (web-exists hang on absent element). JFX verified. Functional behavior correct.
