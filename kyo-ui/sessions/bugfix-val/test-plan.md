# Interaction Test Plan — bugfix-val

Focus: Verify fixes for 6 bugs found in previous session, plus basic coverage of all 7 UIs.

## SignalCombinatorUI (`signals`) — Bugs 1, 6

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 1 | First name input | `.first-name` | fill "John" | `.full-name` contains "John" | `.full-name` contains "John" |
| 2 | Last name input | `.last-name` | fill "Doe" | `.full-name` contains "John Doe" | `.full-name` contains "John Doe" |
| 3 | Qty + button | `.qty-plus` | click | `.qty-value` = "2" | `.qty-value` = "2" |
| 4 | Price +5 | `.price-plus` | click | `.total` contains "$30" | `.total` contains "$30" |
| 5 | Filter tags initial | `.filter-results` | check | contains "Alpha" | contains "Alpha" |
| 6 | Rapid btn | `.rapid-btn` | click | `.rapid-result` contains "5" | `.rapid-result` contains "5" |

## RapidMutationUI (`rapid`) — Bug 3

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 7 | Initial items | `.item-list` | check | "A", "B", "C" | "A", "B", "C" |
| 8 | Add btn | `.add-btn` | click | `.item-count` contains "4" | `.item-count` contains "4" |
| 9 | Remove First | `.remove-first-btn` | click | count updates | count updates |
| 10 | Clear All | `.clear-btn` | click | `.item-count` = "0" | `.item-count` = "0" |
| 11 | Burst Add 5 | `.burst-btn` | click | `.item-count` = "5" | `.item-count` = "5" |
| 12 | Toggle visibility | `.toggle-btn` | click | list hidden | list hidden |
| 13 | Toggle back | `.toggle-btn` | click | list visible | list visible |

## DeepNestingUI (`deepnest`) — Bug 2

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 14 | Initial categories | `.mode-display` | check | Fruits + Vegetables | Fruits + Vegetables |
| 15 | Switch to Grid | `.mode-btn` | click | btn = "Switch to List" | btn = "Switch to List" |
| 16 | Add Fruit | `.add-fruit-btn` | click | "Mango" appears (NO CRASH) | "Mango" appears |
| 17 | Add Veg | `.add-veg-btn` | click | "Spinach" appears (NO CRASH) | "Spinach" appears |
| 18 | Increment Counter | `.inc-btn` | click | "(clicks: 1)" | "(clicks: 1)" |
| 19 | Hide Details | `.inner-toggle-btn` | click | details hidden | details hidden |
| 20 | Toggle cycle | `.cycle-btn` | click | status = "Hidden" | status = "Hidden" |
| 21 | Toggle cycle back | `.cycle-btn` | click | status = "Visible" | status = "Visible" |

## SignalSwapUI (`swap`) — Bug 2

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 22 | Initial Tasks view | `.swap-content` | check | 3 task items | 3 task items |
| 23 | Switch to Notes | `.view-notes-btn` | click | "Notes" + 2 items | "Notes" + 2 items |
| 24 | Switch to Tags | `.view-tags-btn` | click | "Tags" + 4 items | "Tags" + 4 items |
| 25 | Back to Tasks | `.view-tasks-btn` | click | 3 items preserved | 3 items preserved |
| 26 | Add item | `.new-item-input` fill "Test" + `.add-item-btn` click | "Test" in list (NO CRASH) | "Test" in list |
| 27 | Rapid cycle | `.rapid-swap-btn` | click | swap count increases | swap count increases |

## GenericAttrUI (`attrs`) — Bug 4

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 28 | Cycle attr | `.cycle-attr-btn` | click | `.attr-display` = "data-second" | `.attr-display` = "data-second" |
| 29 | .on('click') | `.on-click-btn` | click | `.event-log` contains "click" | `.event-log` contains "click" |
| 30 | Clear Log | `.clear-log-btn` | click | `.event-log` empty | `.event-log` empty |

## FormResetUI (`formreset`) — Bug 5

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 31 | Fill name | `.name-input` | fill "Alice" | `.form1-preview` contains "Alice" | `.form1-preview` contains "Alice" |
| 32 | Fill email | `.email-input` | fill "a@b.com" | `.form1-preview` contains "a@b.com" | `.form1-preview` contains "a@b.com" |
| 33 | Submit | `.submit1-btn` | click | `.submissions-list` contains "Alice" | `.submissions-list` contains "Alice" |
| 34 | Clear All | `.clear1-btn` | click | `.form1-preview` shows "(empty)" | `.form1-preview` shows "(empty)" |
| 35 | Fill username | `.username-input` | fill "bob" | `.settings-preview` contains "bob" | `.settings-preview` contains "bob" |
| 36 | Save Settings | `.save-settings-btn` | click | history updated | history updated |
| 37 | Reset Defaults | `.reset-settings-btn` | click | username "(empty)" | username "(empty)" |

## ReactiveHrefUI (`rechref`)

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 38 | GitHub btn | `.href-github-btn` | click | link text updates | link text updates |
| 39 | Add Item | `.frag-add-btn` | click | count increases | count increases |
| 40 | Remove Last | `.frag-remove-btn` | click | count decreases | count decreases |
