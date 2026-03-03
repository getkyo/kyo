# Interaction Test Plan

## GenericAttrUI (`attrs`)

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 1 | Cycle Attribute btn | `.cycle-btn` | click | `.attr-display` text contains "data-second" | `.attr-display` text contains "data-second" |
| 2 | Cycle again | `.cycle-btn` | click | `.attr-display` text contains "data-third" | `.attr-display` text contains "data-third" |
| 3 | Title input | `.title-input` | fill "Hello" | `.dynamic-title` text contains "Hello" | `.dynamic-title` text contains "Hello" |
| 4 | .on('click') btn | `.on-click-btn` | click | `.event-log` contains "click" | `.event-log` contains "click" |
| 5 | Clear Log | `.clear-log-btn` | click | `.event-log` empty | `.event-log` empty |

## FormResetUI (`formreset`)

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 6 | Name input | `.name-input` | fill "Alice" | `.contact-preview` contains "Alice" | `.contact-preview` contains "Alice" |
| 7 | Email input | `.email-input` | fill "a@b.com" | `.contact-preview` contains "a@b.com" | `.contact-preview` contains "a@b.com" |
| 8 | Message textarea | `.msg-input` | fill "Hi there" | `.contact-preview` contains "Hi there" | `.contact-preview` contains "Hi there" |
| 9 | Submit btn | `.submit-btn` | click | `.history` contains "Alice" | `.history` contains "Alice" |
| 10 | Clear All btn | `.clear-btn` | click | `.contact-preview` contains "(empty)" for all | `.contact-preview` contains "(empty)" for all |
| 11 | Username input | `.username-input` | fill "bob" | `.settings-preview` contains "bob" | `.settings-preview` contains "bob" |
| 12 | Save Settings btn | `.save-btn` | click | `.history` contains "bob" | `.history` contains "bob" |
| 13 | Reset to Defaults | `.reset-btn` | click | `.settings-preview` contains "(empty)" for username | `.settings-preview` contains "(empty)" for username |
| 14 | Clear History | `.clear-history-btn` | click | `.history` contains "No submissions" | `.history` contains "No submissions" |

## SignalCombinatorUI (`signals`)

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 15 | First name input | `.first-name` | fill "John" | `.full-name` contains "John" | `.full-name` contains "John" |
| 16 | Last name input | `.last-name` | fill "Doe" | `.full-name` contains "John Doe" | `.full-name` contains "John Doe" |
| 17 | Qty + button | `.qty-plus` | click | `.qty-value` = "2" | `.qty-value` = "2" |
| 18 | Qty + again | `.qty-plus` | click | `.qty-value` = "3", `.total` contains "$30" | `.qty-value` = "3", `.total` contains "$30" |
| 19 | Price +5 | `.price-plus` | click | `.price-value` = "$15", `.total` contains "$45" | same |
| 20 | Qty - | `.qty-minus` | click | `.qty-value` = "2", `.total` contains "$30" | same |
| 21 | Reset & Add 5 | `.rapid-btn` | click | `.rapid-result` contains "5" | `.rapid-result` contains "5" |
| 22 | Filter tags initial | `.filter-results` | check | contains "Alpha" (or verify initial state) | contains "Alpha", "Beta", "Gamma", "Delta" |

## RapidMutationUI (`rapid`)

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 23 | Initial items | `.item-list` | check | contains "A", "B", "C" | contains "A", "B", "C" |
| 24 | Add btn | `.add-btn` | click | count increases, new item | count increases, new item |
| 25 | Remove First | `.remove-first-btn` | click | first item removed | first item removed |
| 26 | Remove Last | `.remove-last-btn` | click | last item removed | last item removed |
| 27 | Clear All | `.clear-btn` | click | `.item-count` = "0" | `.item-count` = "0" |
| 28 | Burst Add 5 | `.burst-btn` | click | `.item-count` = "5" | `.item-count` = "5" |
| 29 | Toggle visibility initial | `.toggled-list` | check | items A,B,C visible | items A,B,C visible |
| 30 | Hide List btn | `.toggle-btn` | click | `.toggled-list` hidden/empty | `.toggled-list` hidden/empty |
| 31 | Show List btn | `.toggle-btn` | click again | `.toggled-list` items visible | `.toggled-list` items visible |

## DeepNestingUI (`deepnest`)

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 32 | Initial categories | `.mode-display` | check | "Fruits" and "Vegetables" with items | same |
| 33 | Switch to Grid | `.mode-btn` | click | layout changes, btn says "Switch to List" | same |
| 34 | Switch to List | `.mode-btn` | click | layout changes back | same |
| 35 | Add Fruit | `.add-fruit-btn` | click | "Mango" appears under Fruits | same |
| 36 | Add Veg | `.add-veg-btn` | click | "Spinach" appears under Vegetables | same |
| 37 | Increment Counter | `.inc-btn` | click | "(clicks: 1)" on all items | same |
| 38 | Hide Details | `.inner-toggle-btn` | click | detail text hidden in section 2 | same |
| 39 | Show Details | `.inner-toggle-btn` | click | detail text reappears | same |
| 40 | Toggle cycle | `.cycle-btn` | click | status = "Hidden", cycle content hidden | same |
| 41 | Toggle cycle back | `.cycle-btn` | click | status = "Visible", content reappears | same |

## SignalSwapUI (`swap`)

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 42 | Initial Tasks view | `.view-content` | check | "Tasks" heading, 3 items | same |
| 43 | Switch to Notes | `.view-btn` idx 1 | click | "Notes" heading, 2 items | same |
| 44 | Switch to Tags | `.view-btn` idx 2 | click | "Tags" heading, 4 items | same |
| 45 | Switch back to Tasks | `.view-btn` idx 0 | click | "Tasks" heading, 3 items preserved | same |
| 46 | Add item | `.add-btn` after fill `.new-item-input` "Test" | fill + click | "Test" appears in Tasks list | same |
| 47 | Rapid cycle | `.cycle-btn` | click | swap count increases, view may change | same |

## ReactiveHrefUI (`rechref`)

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 48 | GitHub btn | `.href-btn` idx 1 | click | link text contains "GitHub", href display updated | same |
| 49 | Scala btn | `.href-btn` idx 2 | click | link text contains "Scala" | same |
| 50 | Add Item | `.add-item-btn` | click | new tag in list, count increases | same |
| 51 | Remove Last | `.remove-last-btn` | click | last tag removed, count decreases | same |
| 52 | Clear | `.clear-btn` | click | all items cleared, count = "0 items" | same |
