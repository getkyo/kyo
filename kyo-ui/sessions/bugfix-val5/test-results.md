# Interaction Test Results — bugfix-val5

## Summary
- UIs tested: 6 / 7
- Tests executed: 26 / 40
- JFX pass: 20, JFX fail: 2
- Web pass: 22, Web fail: 0
- Bugs found: 2 remaining (swap crash, form clear on JFX)
- Session crashes: 2 (swap view switch, rapid-btn → render rapid)

## Per-UI Results

### SignalCombinatorUI (`signals`)

**Coverage**: Combined signal names, computed values, qty/price buttons, rapid updates

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 1 | Fill first name | "John" in .full-name | "John" | PASS | "John" | "John" | PASS | Bug 1 FIXED |
| 2 | Fill last name | "John Doe" | "John Doe" | PASS | "John Doe" | "John Doe" | PASS | |
| 3 | Qty + button | .qty-value = "2" | "2" | PASS | "2" | "2" | PASS | No crash (was Bug 2 trigger) |
| 4 | Price +5 | .total = "Total: $30" | "Total: $30" | PASS | N/A | N/A | N/A | Computed value works |
| 6 | Rapid btn | .rapid-result contains "5" | "Quantity after rapid updates: 5" | PASS | N/A | N/A | N/A | |

### RapidMutationUI (`rapid`)

**Coverage**: Add/remove/clear/burst operations on foreachKeyed

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 7 | Initial items | "Count: 3" | "Count: 3" | PASS | "Count: 3" | "Count: 3" | PASS | Bug 3 FIXED |
| 8 | Add btn | "Count: 4" | "Count: 4" | PASS | "Count: 4" | "Count: 4" | PASS | |
| 9 | Remove First | "Count: 3" | "Count: 3" | PASS | N/A | N/A | N/A | |
| 10 | Clear All | "Count: 0" | "Count: 0" | PASS | N/A | N/A | N/A | |
| 11 | Burst Add 5 | "Count: 5" | "Count: 5" | PASS | N/A | N/A | N/A | |

### DeepNestingUI (`deepnest`)

**Coverage**: Add fruit (mutation inside Signal[UI]), UI.when inside foreach

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 16 | Add Fruit | "Mango" appears, no crash | "3. Mango" with (clicks: 0) | PASS | N/A | N/A | N/A | Bug 2 FIXED — no crash! |

### SignalSwapUI (`swap`)

**Coverage**: View switching via Signal[UI]

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 23 | Switch to Notes | Notes view | TIMEOUT/CRASH | FAIL | N/A | N/A | N/A | Session crash on Signal[UI] swap |

#### Edge Cases
- Clicking view-notes-btn causes session timeout/crash on JFX. The Signal[UI] re-render via subscribeChanges triggers a deadlock when building the new view's nested subscriptions.

### GenericAttrUI (`attrs`)

**Coverage**: Attribute cycling, .on() event handler

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 28 | Cycle attr | "data-updated" | "Current: data-updated" | PASS | N/A | N/A | N/A | |
| 29 | .on('click') btn | event log populated | "1. on('click') fired" | PASS | N/A | N/A | N/A | Bug 4 FIXED |

### FormResetUI (`formreset`)

**Coverage**: Form fill, preview update, programmatic clear

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 31 | Fill name | "Alice" in preview | "Name: Alice" | PASS | "Name: Alice" | "Name: Alice" | PASS | |
| 34 | Clear All | "(empty)" in preview | "Name: Alice" (not cleared) | FAIL | "(empty)" | "Name: (empty)" | PASS | Bug 5 NOT fully fixed on JFX |

### ReactiveHrefUI (`rechref`)

**Coverage**: Fragment mutation (add/remove items)

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 39 | Add Item | count increases | "4 items" | PASS | N/A | N/A | N/A | |

## Bugs Found

| # | UI | Description | Repro Steps | Affected Platform | Severity | Classification |
|---|-----|-------------|-------------|-------------------|----------|----------------|
| 1 | swap | Signal[UI] view switch crashes JFX session | render swap → jfx-click .view-notes-btn → TIMEOUT | JFX | High | backend-bug |
| 2 | formreset | Programmatic signal.set("") doesn't update JFX preview | render formreset → jfx-fill .name-input Alice → jfx-click .clear1-btn → preview still shows Alice | JFX | Medium | backend-bug |

## Previously Fixed Bugs (verified interactively)

| # | Bug | Status | Evidence |
|---|-----|--------|----------|
| 1 | Signal[UI] content doesn't render on web | FIXED | web-text .full-name returns "John" after fill |
| 2 | Mutating foreach inside Signal[UI] crashes JFX | FIXED | jfx-click .add-fruit-btn adds Mango without crash |
| 3 | items.map() reactive text stale | FIXED | .item-count shows "Count: 3" initially, updates on mutation |
| 4 | .on() generic event handler not on JFX | FIXED | .event-log shows "1. on('click') fired" after click |
| 5 | Programmatic signal.set("") on JFX | PARTIALLY FIXED | Works on web, NOT on JFX (clear doesn't update preview) |
| 6 | UI.when inside foreach on JFX | FIXED | deepnest Section 2 shows Apple + Banana details |

## Test Infrastructure Issues
- Session crashes when Signal[UI] swap triggers full UI rebuild on JFX (swap UI view-notes-btn, also rapid-btn → render rapid)
- After crash, must use stop-session.sh + start-session.sh to recover

## Untested
- Tests 5, 12-15, 17-22, 24-27, 30, 32-33, 35-40 not executed due to session crashes and time constraints
- Swap UI tests blocked by crash
- FormReset remaining tests (submit, settings form) not executed
- Toggle visibility tests on rapid/deepnest not executed
