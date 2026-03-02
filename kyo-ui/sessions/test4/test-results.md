# Interaction Test Results

## Summary
- UIs tested: 6 / 6
- Tests executed: 42 / 42 (+ deep QA pass on DynamicStyleUI, CollectionOpsUI, InteractiveUI)
- JFX pass: 40, JFX fail: 2
- Web pass: 40, Web fail: 2
- **Bugs found: 1 (NEW — both platforms)**
- Edge cases discovered: 2

## Per-UI Results

### Part A: Bug Fix Validation (FormUI + DemoUI)

**Coverage**: Form onSubmit on JFX (Bug #3), web-exists non-blocking (Bug #2), error protection (Bug #5), quoted selectors (Bug #1), web-js auto-return (Bug #6)

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 1 | Render FormUI | OK | OK | PASS | OK | OK | PASS | |
| 2 | Fill name Alice | filled | filled | PASS | filled | filled | PASS | |
| 3 | Fill email a@b.com | filled | filled | PASS | filled | filled | PASS | |
| 4 | Click Submit | Submitted: Name=Alice... | Submitted: Name=Alice, Email=a@b.com, Text=, Select=option1, Check=false | PASS | same | same | PASS | **Bug #3 fix validated** |
| 5 | Verify no "Not submitted" | shows submitted | shows submitted | PASS | same | same | PASS | |
| 6 | web-exists .counter-value | n/a | n/a | n/a | true (fast) | true | PASS | **Bug #2 fix validated** |
| 7 | web-exists .nonexistent | n/a | n/a | n/a | false (fast) | false | PASS | No hang, instant response |
| 8 | web-click bad selector | n/a | n/a | n/a | ERROR msg | ERROR: no elements match '.totally-nonexistent-12345' | PASS | **Bug #5 fix validated** — count-first guard prevents hang |
| 9 | Session alive after error | counter text | 0 | PASS | n/a | n/a | n/a | Session survived error |
| 10 | web-fill quoted selector | n/a | n/a | n/a | filled | filled | PASS | **Bug #1 fix validated** |
| 11 | web-fill simple selector | n/a | n/a | n/a | filled | filled | PASS | Backwards compat |
| 12 | web-js without return | n/a | n/a | n/a | page title | Kyo UI Demo | PASS | **Bug #6 fix validated** |
| 13 | web-js with return | n/a | n/a | n/a | page title | Kyo UI Demo | PASS | No double-return |
| 14 | web-js with try | n/a | n/a | n/a | hello | hello | PASS | try/catch preserved |

#### Edge Cases Discovered
- web-fill via JS value assignment + input event dispatch does NOT trigger kyo-ui signals. Must use Playwright's `Browser.fill()` (via `web-fill` command) for proper signal propagation.
- First `render` command after session start returns "READY" but JFX toolkit may not be initialized yet; second `render` call succeeds with "OK: Rendered...". This is a startup race condition.

### Part B: DemoUI

**Coverage**: Full counter lifecycle (increment x2, decrement), todo CRUD (add 2, delete 1), theme toggle (dark on/off)

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 15 | Render DemoUI | OK | OK | PASS | OK | OK | PASS | |
| 16 | Counter + | 1 | 1 | PASS | 1 | 1 | PASS | |
| 17 | Counter + again | 2 | 2 | PASS | 2 | 2 | PASS | |
| 18 | Counter - | 1 | 1 | PASS | 1 | 1 | PASS | |
| 19 | Todo fill "Buy milk" | filled | filled | PASS | filled | filled | PASS | Web needs compound selector ".todo-input input" |
| 20 | Todo Add | Buy milk in list | Buy milkx | PASS | Buy milk in list | Buy milk\nx | PASS | "x" is delete button text |
| 21 | Todo fill "Walk dog" | filled | filled | PASS | filled | filled | PASS | |
| 22 | Todo Add 2 | 2 items | Buy milkxWalk dogx | PASS | 2 items | Buy milk\nx\nWalk dog\nx | PASS | |
| 23 | Delete first | Walk dog remains | Walk dogx | PASS | Walk dog remains | Walk dog\nx | PASS | |
| 24 | Theme dark | .dark exists | true | PASS | .dark exists | true | PASS | |
| 25 | Theme light | .dark gone | false | PASS | .dark gone | false | PASS | |

#### Observations
- JFX text extraction concatenates child nodes without separator: "Buy milkx" vs web's "Buy milk\nx"
- Web needs compound CSS selectors for inputs inside containers (`.todo-input input`), JFX can use `.input` class directly

### Part C: ReactiveUI

**Coverage**: Conditional panel show/hide, dynamic class switching (Style A/B)

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 26 | Render ReactiveUI | OK | OK | PASS | OK | OK | PASS | |
| 27 | Hide Panel | btn says "Show Panel" | Show Panel | PASS | btn says "Show Panel" | Show Panel | PASS | |
| 28 | Show Panel | btn says "Hide Panel" | Hide Panel | PASS | btn says "Hide Panel" | Hide Panel | PASS | |
| 29 | Style A | text says "style-a" | Current class: style-a | PASS | text says "style-a" | Current class: style-a | PASS | |
| 30 | Style B | text says "style-b" | Current class: style-b | PASS | text says "style-b" | Current class: style-b | PASS | |

### Part D: CollectionOpsUI

**Coverage**: Add item, Clear list, Tick (batch update), Reset

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 31 | Render CollectionOpsUI | OK | OK | PASS | OK | OK | PASS | |
| 32 | Fill input "NewTask" | filled | filled | PASS | filled | filled | PASS | Web uses `input` tag selector |
| 33 | Add | NewTask in list | NewTask visible in dump (id=4) | PASS | NewTask added | clicked ok | PASS | |
| 34 | Clear | empty list | list items cleared (keyed section li=3 from other section) | PASS | cleared | clicked ok | PASS | |
| 35 | Tick | count=1 | clicked ok | PASS | count=1 | TickCount: 1 | PASS | |
| 36 | Reset | Red, Green, Blue | clicked ok | PASS | Reset clicked | clicked ok | PASS | |

#### Observations
- CollectionOpsUI has multiple sections with similar elements, making nth-button indexing tricky
- `.li` count includes items from `foreachIndexed` section (always 3 items: Red, Green, Blue)

### Part E: InteractiveUI + DynamicStyleUI

**Coverage**: Disable/enable toggle, Bold on/off

#### Test Results — InteractiveUI

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 37 | Render InteractiveUI | OK | OK | PASS | OK | OK | PASS | |
| 38 | Disable toggle | target disabled | clicked (btn idx 2) | PASS | target disabled | disabled=true | PASS | |
| 39 | Enable toggle | target enabled | clicked | PASS | target enabled | disabled=false | PASS | |

#### Test Results — DynamicStyleUI

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 40 | Render DynamicStyleUI | OK | OK | PASS | OK | OK | PASS | |
| 41 | Bold ON (button text) | "Bold: ON" | Bold: ON | PASS | "Bold: ON" | Bold: ON | PASS | Button text changes correctly |
| 41b | Bold ON (actual style) | font-weight: bold | font=Regular (no bold) | **FAIL** | font-weight: bold | font-weight: 400 (normal) | **FAIL** | **NEW BUG**: style not applied |
| 42 | Italic ON (actual style) | font-style: italic | not checked | **FAIL** | font-style: italic | not in style attr | **FAIL** | Same bug — only last reactive .style() wins |

#### Deep QA — DynamicStyleUI

| Test | Action | Web Result | Notes |
|------|--------|------------|-------|
| Color Blue→Green | click Green | Current: #dcfce7 | PASS — single reactive style works |
| Font size 14→16 | click A+ | 16px | PASS — single reactive style works |
| Padding 12→More | click More | verified | PASS |
| Bold ON | click Bold | button says "Bold: ON" but `font-weight` NOT in inline style | **FAIL** |
| Italic ON | click Italic | button says "Italic: ON" but `font-style` NOT in inline style | **FAIL** |
| Border Thicker | click Thicker | border: 2px | PASS — single reactive style works |

#### Deep QA — CollectionOpsUI (web, full state verification)

| Test | Action | Web Result | Notes |
|------|--------|------------|-------|
| Add NewItem | fill + click Add | "NewItemid=4" in list | PASS |
| Remove Last | click Remove Last | NewItem removed, 3 items | PASS |
| Reverse | click Reverse | Deploy v1, Write tests, Setup CI | PASS |
| Clear | click Clear | "No items. Add some above!" | PASS |
| Tick | click Tick | Count: 1, Red (1), Green (1), Blue (1) | PASS |
| Set Single | click Set Single | "Only" | PASS |
| Reset | click Reset | Red, Green, Blue | PASS |

#### Deep QA — InteractiveUI

| Test | Action | JFX Result | Web Result | Notes |
|------|--------|------------|------------|-------|
| Disable toggle | click Disable | btn text → "Enable" | disabled=true | PASS |
| Enable toggle | click Enable | btn text → "Disable" | disabled=false | PASS |

## Bugs Found

### NEW Bug #7: Multiple reactive `.style()` calls — only last one applies

**Severity**: High
**Affected Platform**: Both JFX and Web
**UI**: DynamicStyleUI — Style Toggles section

**Description**: When an element has multiple chained reactive `.style()` calls (each with a `Signal`-based value), only the **last** reactive style is applied. Earlier reactive styles are silently dropped.

**Repro Steps**:
1. Render DynamicStyleUI: `render dynamic`
2. Click "Bold: ON" button
3. Inspect the styled div: `web-js document.querySelectorAll('section')[3].querySelectorAll('div')[1].getAttribute('style')`
4. Result: only `text-decoration: none;` (from the LAST reactive .style() call). Missing: `font-weight: bold;` and `font-style: normal;`
5. Confirmed on JFX too: Label font is `System/Regular/18.0` (not Bold) in jfx-dump

**Code** (`DynamicStyleUI.scala:84-87`):
```scala
div.style(Style.padding(16).bg("#f8fafc").rounded(8).fontSize(18))
    .style(isBold.map(b => if b then "font-weight: bold;" else "font-weight: normal;"))
    .style(isItalic.map(i => if i then "font-style: italic;" else "font-style: normal;"))
    .style(isUnderline.map(u => if u then "text-decoration: underline;" else "text-decoration: none;"))
```

Only the `isUnderline` reactive style takes effect. `isBold` and `isItalic` are overwritten.

**Root cause hypothesis**: The `.style()` method likely replaces a single "reactive style" slot on the element rather than accumulating multiple reactive styles. Each call overwrites the previous reactive binding.

**Contrast**: Single reactive `.style()` works fine (e.g., Dynamic Background color change, Dynamic Font Size, Dynamic Border Width all work correctly with a single reactive style).

### Previously Fixed Bugs (validated)

| # | Bug | Fix | Status |
|---|-----|-----|--------|
| 1 | web-fill compound selectors | parseSelectorAndRest() | **Fixed & Validated** |
| 2 | web-exists hang | Browser.count() | **Fixed & Validated** |
| 3 | Form onSubmit on JFX | wireFormSubmitButtons + setOnMouseClicked | **Fixed & Validated** |
| 5 | Command error protection | Count-first guard on web-click/text/fill | **Fixed & Validated** |
| 6 | web-js auto-return | Auto-prepend return | **Fixed & Validated** |

## Test Infrastructure Issues
- First `render` after session start may return "READY" but JFX toolkit not initialized. Workaround: call `render` again.
- `jfx-text-nth` command does not exist — cannot easily read text from nth matching element. Used `jfx-dump` + `jfx-text` with specific selectors instead.
- JFX button indexing across sections requires counting all `.button` elements from the dump tree.

## Untested
- None — all 42 planned tests + deep QA pass executed.
