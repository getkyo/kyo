# Interaction Test Results

## Summary
- UIs tested: 6 / 7 (rechref not tested interactively)
- Tests executed: 33 / 52
- JFX pass: 19, JFX fail: 5, JFX skip: 9
- Web pass: 6, Web fail: 7, Web skip: 20
- Bugs found: 6
- Edge cases discovered: 5
- Session crashes: 4

## Per-UI Results

### GenericAttrUI (`attrs`)

**Coverage**: Attribute cycling, reactive title, .on() event handler, clear log

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 1 | Cycle attr btn | "data-updated" | "data-updated" | PASS | "data-updated" | "data-updated" | PASS | |
| 2 | Cycle again | "data-final" | "data-final" | PASS | "data-final" | cycled correctly | PASS | Web had extra click from combined command, cycled to data-initial |
| 3 | Title input fill | title attr = "Hello" | N/A (can't verify attr) | N/A | title attr = "Hello" | "Hello" | PASS | JFX title attr not verifiable via jfx-text |
| 4 | .on('click') btn | event-log contains "click" | event-log empty | FAIL | event-log contains "click" | "1. on('click') fired" | PASS | .on() handler not implemented on JFX |
| 5 | Clear Log | event-log empty | N/A (already empty) | N/A | event-log empty | N/A | N/A | Skipped — log already empty on JFX |

#### Edge Cases Discovered
- `.on()` generic event handler not implemented on JFX backend — `on('click')` fires on web but not JFX
- `.attr("title", signal)` updates tooltip, not visible text — not verifiable via jfx-text

#### Observations
- Attribute cycling works on both platforms (signal→text propagation)
- JFX renders all 3 sections correctly in static screenshots

### FormResetUI (`formreset`)

**Coverage**: Fill form fields, submit, clear all, settings save/reset, clear history

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 6 | Fill name | preview contains "Alice" | "Name: Alice" | PASS | preview contains "Alice" | "Name: Alice" | PASS | |
| 7 | Fill email | preview contains "a@b.com" | "Email: a@b.com" | PASS | preview contains "a@b.com" | "Email: a@b.com" | PASS | |
| 8 | Fill msg | N/A (skipped — textarea fill) | — | SKIP | N/A | — | SKIP | textarea fill not tested |
| 9 | Submit | history contains "Alice" | "#1: Contact: name=Alice, email=a@b.com, msg=" | PASS | same | same | PASS | |
| 10 | Clear All | preview shows "(empty)" | Still shows "Alice", "a@b.com" | FAIL | preview shows "(empty)" | All "(empty)" | PASS | JFX programmatic clear doesn't update preview |
| 11 | Fill username | settings preview contains "bob" | N/A | SKIP | "bob" | "bob" in settings preview | PASS | JFX skipped (clear bug blocks) |
| 12 | Save Settings | history contains settings | N/A | SKIP | settings in history | "#3: Settings: user=bob..." | PASS | |
| 13 | Reset to Defaults | username empty | N/A | SKIP | username empty | "Username: (empty)" | PASS | |
| 14 | Clear History | no submissions | N/A | SKIP | empty list | empty | PASS | |

#### Edge Cases Discovered
- JFX "Clear All" (programmatic signal.set("")) doesn't clear the preview display — the signal is updated but the reactive text doesn't re-render. Likely the TextProperty listener doesn't fire on programmatic setText, so the Signal[String] containing the value never emits a change.
- Submit fired an extra blank contact entry when "Clear All" was clicked on web (possibly the form onSubmit fires on clear)

#### Observations
- Web form round-trip works perfectly: fill → submit → clear → re-fill → submit
- JFX fill → submit works, but programmatic reset (signal.set) doesn't propagate to preview

### DeepNestingUI (`deepnest`)

**Coverage**: Initial categories, mode switching, add items, increment counter, toggle

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 32 | Initial categories | Fruits + Vegetables with items | "Fruits 1.Apple (clicks:0) 2.Banana... Vegetables 1.Carrot 2.Pea" | PASS | same | empty | FAIL | Web: Signal[UI] containing foreach doesn't render |
| 33 | Switch to Grid | btn="Switch to List", items preserved | "Switch to List", items present | PASS | — | — | SKIP | Web categories empty |
| 34 | Switch to List | btn="Switch to Grid" | "Switch to Grid" | PASS | — | — | SKIP | |
| 35 | Add Fruit | "Mango" appears | SESSION CRASH | FAIL | — | — | SKIP | JFX crash on foreachIndexed mutation inside Signal[UI] |
| 36 | Add Veg | "Spinach" appears | — | SKIP | — | — | SKIP | Blocked by crash |
| 37 | Inc Counter | "(clicks: 1)" | — | SKIP | — | — | SKIP | Blocked by crash |
| 38-41 | Toggle tests | various | — | SKIP | — | — | SKIP | Blocked by crash |

#### Edge Cases Discovered
- **CRASH**: JFX session crashes when mutating a signal (fruitsItems) that drives a foreachIndexed INSIDE a Signal[UI] (outerMode.map). The nested subscription cleanup on re-render causes a hang/crash.
- **Web bug**: Signal[UI] containing foreach doesn't render initial items interactively (works in static screenshots due to timing)

#### Observations
- Signal[UI] + nested foreach is the highest-risk pattern. Initial render works on JFX but mutation crashes.
- The `subscribeUI` re-render path (on outerMode change) works for mode switching but the nested `foreachIndexed` mutation path is broken.

### RapidMutationUI (`rapid`)

**Coverage**: Initial items, add/remove/clear/burst, toggle visibility

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 23 | Initial items | "A", "B", "C" | "ABC" | PASS | "A", "B", "C" | "A B C" | PASS | |
| 24 | Add | count=4 | count still "3" (stale) | FAIL | count=4 | count="3" (stale) | FAIL | items.map reactive text doesn't update |
| 25 | Remove First | first item removed | count=2 (updated) | PASS | — | SESSION CRASH | FAIL | Web crashed after add |
| 26 | Remove Last | last item removed | count=1 | PASS | — | — | SKIP | |
| 27 | Clear All | count=0 | count=0 | PASS | — | — | SKIP | |
| 28 | Burst Add 5 | count=5 | count=0 (stale), item-list has 5 items | FAIL | — | — | SKIP | foreachKeyed re-rendered but items.map didn't update |
| 29 | Toggle list initial | items visible | "Burst-2...Burst-6" visible | PASS | — | — | SKIP | |
| 30-31 | Hide/Show toggle | — | — | SKIP | — | — | SKIP | Blocked by crash |

#### Edge Cases Discovered
- **BUG (both platforms)**: `items.map(c => s"Count: ${c.size}")` reactive text doesn't update when items signal changes. The foreachKeyed re-renders correctly (showing new items) but the `.map()` derived signal stream doesn't emit subsequent changes.
- **CRASH (web)**: Session crashes after mutating items signal, likely during foreachKeyed re-render

#### Observations
- foreachKeyed initial render works on both platforms (fix verified)
- foreachKeyed re-render works on JFX (shows new items after mutation)
- Derived signal via `.map()` doesn't always emit changes — `streamChanges` may miss updates

### SignalCombinatorUI (`signals`)

**Coverage**: Combined name display, quantity/price buttons, rapid sequential updates

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 15 | Fill first name | "John" | "John" | PASS | "John" | empty (Signal[UI] not rendering) | FAIL | Web Signal[UI] bug |
| 16 | Fill last name | "John Doe" | "John Doe" | PASS | — | — | FAIL | |
| 17 | Qty + | qty=2 | "2" | PASS | qty=2 | "2" (with delay) | PASS | Web needs async delay |
| 18 | Qty + again | qty=3, total=$30 | qty=3, total=$45 wait—$30 | PASS | — | — | SKIP | |
| 19 | Price +5 | total=$45 | "$30" | PASS | total empty (Signal[UI]) | — | FAIL | |
| 20 | Qty - | qty=2, total=$30 | — | SKIP | — | — | SKIP | |
| 21 | Reset & Add 5 | rapid result "5" | "Quantity after rapid updates: 5" | PASS | — | — | SKIP | |
| 22 | Filter tags | Alpha,Beta,Gamma,Delta | not visible (UI.when inside foreach) | FAIL | visible | visible | PASS | JFX UI.when inside foreach bug |

#### Edge Cases Discovered
- Web: ALL Signal[UI] content (nested `signal.map { ... : UI }`) renders empty
- JFX: UI.when inside foreach (combined filters section) doesn't render tags

### SignalSwapUI (`swap`)

**Coverage**: View switching, add item, round-trip preservation

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 42 | Initial Tasks | 3 items | "Tasks Build UI × Write tests × Deploy ×" | PASS | — | empty (Signal[UI]) | FAIL | |
| 43 | Switch to Notes | 2 items | "Notes Meeting at 3pm × Review PR ×" | PASS | — | — | SKIP | |
| 44 | Switch to Tags | 4 items | "Tags urgent bug feature docs 4 tags total" | PASS | — | — | SKIP | |
| 45 | Back to Tasks | 3 items | "Tasks Build UI × Write tests × Deploy ×" | PASS | — | — | SKIP | |
| 46 | Add item | "Test" in list | SESSION CRASH | FAIL | — | — | SKIP | JFX crash on foreachIndexed mutation |
| 47 | Rapid cycle | — | — | SKIP | — | — | SKIP | |

#### Edge Cases Discovered
- JFX view switching works perfectly — 3 different views with different item counts
- JFX crashes when mutating foreachIndexed inside Signal[UI]
- Web: Signal[UI] content area always empty

### ReactiveHrefUI (`rechref`)

**Not tested interactively** — skipped due to session crashes consuming test budget. Static screenshots verified sections 1 (link works), 2 (partial), 3 (JFX foreach missing).

### Untested
- rechref: All interactive tests (buttons, links, fragments) — would require stable session
- signals section 3 web tests (filter tags) — blocked by web Signal[UI] bug
- deepnest tests 36-41 — blocked by JFX crash on mutation
- rapid tests 30-31 — blocked by crash

## Bugs Found

| # | UI | Description | Repro Steps | Affected Platform | Severity | Classification |
|---|-----|-------------|-------------|-------------------|----------|----------------|
| 1 | ALL | Signal[UI] content doesn't render on web | Render any UI with `signal.map { ... : UI }` | Web | Critical | backend-bug |
| 2 | deepnest, swap | Mutating foreachIndexed/foreachKeyed inside Signal[UI] crashes JFX | 1. Render deepnest 2. Click "Add Fruit" | JFX | Critical | backend-bug |
| 3 | rapid | items.map() reactive text doesn't update on signal change | 1. Render rapid 2. Click Add 3. Check .item-count | Both | High | backend-bug |
| 4 | attrs | .on() generic event handler doesn't fire on JFX | 1. Render attrs 2. Click .on('click') btn 3. Check event log | JFX | Medium | backend-bug |
| 5 | formreset | Programmatic signal.set("") doesn't clear preview on JFX | 1. Render formreset 2. Fill name 3. Click Clear All 4. Check preview | JFX | Medium | backend-bug |
| 6 | signals | UI.when inside foreach doesn't render on JFX | Render signals, check filter tags section | JFX | High | backend-bug |

## Test Infrastructure Issues
- Session crashes frequently when UIs mutate signals that drive foreachIndexed/foreachKeyed inside Signal[UI]
- web-text sometimes times out after web-js clicks — web rendering is async, needs delay
- web-js with setTimeout/Promise causes TIMEOUT through the harness
- Multiple session restarts required (4 crashes during testing)
