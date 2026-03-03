# Interaction Test Report

## Summary
- Total tests: 52 planned, 33 executed
- JFX pass: 19, JFX fail: 5, JFX skip: 9
- Web pass: 6, Web fail: 7, Web skip: 20
- Session crashes: 4

## Key Findings

### Critical: Signal[UI] is broken on Web (DOM Backend)
Every UI that uses `signal.map { ... : UI }` (ReactiveNode/Signal[UI]) renders empty on web during interactive testing. This affects:
- SignalCombinatorUI: full-name display, total display
- SignalSwapUI: view content area
- DeepNestingUI: all sections
Static screenshots showed content because the screenshot timing allowed async fibers to complete.

### Critical: foreachIndexed/foreachKeyed mutation inside Signal[UI] crashes JFX
When a signal driving a foreachIndexed/foreachKeyed is mutated while the foreach is nested inside a Signal[UI], the JFX session crashes. This affects:
- DeepNestingUI: "Add Fruit" crashes
- SignalSwapUI: "Add to Current View" crashes

### High: items.map() reactive text stale after mutation
When `items` signal changes, `items.map(c => s"Count: ${c.size}")` doesn't update even though `items.foreachKeyed` correctly re-renders. The derived signal stream misses emissions.

## Per-UI Results

### GenericAttrUI (`attrs`)

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 1 | Cycle attr | click cycle-attr-btn | "data-updated" | "data-updated" | PASS | "data-updated" | "data-updated" | PASS |
| 2 | Cycle again | click cycle-attr-btn | "data-final" | "data-final" | PASS | "data-final" | "data-final" | PASS |
| 3 | Title input | fill title-input "Hello" | title attr="Hello" | N/A | N/A | title="Hello" | "Hello" | PASS |
| 4 | .on('click') | click on-click-btn | log entry | empty log | FAIL | log entry | "1. on('click') fired" | PASS |
| 5 | Clear Log | click clear-log-btn | log empty | already empty | N/A | log empty | N/A | N/A |

### FormResetUI (`formreset`)

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 6 | Fill name | fill name-input "Alice" | "Name: Alice" | "Name: Alice" | PASS | "Name: Alice" | "Name: Alice" | PASS |
| 7 | Fill email | fill email-input "a@b.com" | "Email: a@b.com" | "Email: a@b.com" | PASS | same | same | PASS |
| 9 | Submit | click submit1-btn | history entry | "#1: Contact..." | PASS | same | same | PASS |
| 10 | Clear All | click clear1-btn | preview "(empty)" | still "Alice" | FAIL | "(empty)" | "(empty)" | PASS |
| 12 | Save Settings | click save-settings-btn | settings in history | N/A | SKIP | settings entry | "#3: Settings..." | PASS |
| 13 | Reset Defaults | click reset-settings-btn | username empty | N/A | SKIP | username "(empty)" | "(empty)" | PASS |
| 14 | Clear History | click clear-history-btn | empty list | N/A | SKIP | empty | empty | PASS |

### SignalCombinatorUI (`signals`)

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 15 | First name | fill "John" | "John" | "John" | PASS | "John" | empty | FAIL |
| 16 | Last name | fill "Doe" | "John Doe" | "John Doe" | PASS | "John Doe" | empty | FAIL |
| 17 | Qty + | click qty-plus | qty=2 | "2" | PASS | qty=2 | "2" | PASS |
| 19 | Price +5 | click price-plus | total=$30 | "$30" | PASS | total | empty | FAIL |
| 21 | Rapid update | click rapid-btn | result=5 | "5" | PASS | — | — | SKIP |

### RapidMutationUI (`rapid`)

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 23 | Initial items | check item-list | "A B C" | "ABC" | PASS | "A B C" | "A B C" | PASS |
| 24 | Add | click add-btn | count=4 | count=3 (stale) | FAIL | count=4 | count=3, CRASH | FAIL |
| 25 | Remove First | click remove-first | count=2 | count=2 | PASS | — | CRASH | SKIP |
| 27 | Clear All | click clear-btn | count=0 | count=0 | PASS | — | — | SKIP |
| 28 | Burst Add 5 | click burst-btn | count=5 | count=0, items=5 | FAIL | — | — | SKIP |

### DeepNestingUI (`deepnest`)

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 32 | Initial categories | check mode-display | 2 categories | all present | PASS | 2 categories | empty | FAIL |
| 33 | Switch to Grid | click mode-btn | layout changes | "Switch to List" | PASS | — | — | SKIP |
| 35 | Add Fruit | click add-fruit-btn | "Mango" | CRASH | FAIL | — | — | SKIP |

### SignalSwapUI (`swap`)

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 42 | Tasks view | check swap-content | 3 items | "Build UI, Write tests, Deploy" | PASS | 3 items | empty | FAIL |
| 43 | Notes view | click view-notes-btn | 2 items | "Meeting at 3pm, Review PR" | PASS | — | — | SKIP |
| 44 | Tags view | click view-tags-btn | 4 items | "urgent, bug, feature, docs" | PASS | — | — | SKIP |
| 45 | Back to Tasks | click view-tasks-btn | preserved | preserved | PASS | — | — | SKIP |
| 46 | Add item | fill + click add | "Test" in list | CRASH | FAIL | — | — | SKIP |

## Bugs Found

| # | UI | Description | Affected Platform | Severity |
|---|-----|-------------|-------------------|----------|
| 1 | ALL | Signal[UI] content doesn't render interactively on web | Web | Critical |
| 2 | deepnest, swap | Mutating foreach inside Signal[UI] crashes JFX | JFX | Critical |
| 3 | rapid | items.map() reactive text stale after mutation | Both | High |
| 4 | attrs | .on() generic event handler not implemented on JFX | JFX | Medium |
| 5 | formreset | Programmatic signal.set("") doesn't update JFX preview | JFX | Medium |
| 6 | signals | UI.when inside foreach doesn't render on JFX | JFX | High |
