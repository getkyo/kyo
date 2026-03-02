# Interaction Test Plan

## DemoUI

| # | Element | Selector (JFX / Web) | Action | Expected Result |
|---|---------|---------------------|--------|-----------------|
| 1 | Counter + button | `.counter-btn` idx 1 / `.counter-btn:nth-child(3)` | click | `.counter-value` text = "1" |
| 2 | Counter + button | same | click again | `.counter-value` text = "2" |
| 3 | Counter - button | `.counter-btn` idx 0 / `.counter-btn:nth-child(1)` | click | `.counter-value` text = "1" |
| 4 | Counter - button | same | click | `.counter-value` text = "0" |
| 5 | Counter - button | same | click (go negative) | `.counter-value` text = "-1" |
| 6 | Todo input | `.input` (in .todo-input) / `.todo-input input` | fill "Buy milk" | input contains "Buy milk" |
| 7 | Todo Add button | `.submit` / `.submit` | click | `ul` contains "Buy milk", input cleared |
| 8 | Todo add empty | `.submit` / `.submit` | click with empty input | no new item added |
| 9 | Todo add second | fill "Walk dog" + click Add | | `ul` contains "Buy milk" and "Walk dog" |
| 10 | Todo delete | `.delete-btn` idx 0 / web-js querySelectorAll('.delete-btn')[0].click() | click | "Buy milk" removed, "Walk dog" remains |
| 11 | Theme toggle | `.theme-toggle` / `.theme-toggle` | click | dark mode activates (class "dark" added) |
| 12 | Theme toggle back | `.theme-toggle` / `.theme-toggle` | click again | dark mode deactivates |

## FormUI

| # | Element | Selector (JFX / Web) | Action | Expected Result |
|---|---------|---------------------|--------|-----------------|
| 13 | Name input | `[id=name]` / `[id=name]` | fill "Alice" | value = "Alice" |
| 14 | Email input | `[id=email]` / `[id=email]` | fill "alice@test.com" | value = "alice@test.com" |
| 15 | Textarea | `.textarea` / `textarea` | fill "Hello world" | value = "Hello world" |
| 16 | Select dropdown | `.choice-box` / `select` | change to "option2" | select value = "option2" |
| 17 | Checkbox | `.check-box` / `input[type=checkbox]` | click/check | checked = true |
| 18 | Submit button | `.button` (last in form) / `form button` | click | text shows "Submitted: Name=Alice, Email=alice@test.com, Text=Hello world, Select=option2, Check=true" |
| 19 | Disable All button | (in disabled controls section) | click | inputs below become disabled |
| 20 | Enable All button | same button (now says "Enable All") | click | inputs re-enabled |

## ReactiveUI

| # | Element | Selector (JFX / Web) | Action | Expected Result |
|---|---------|---------------------|--------|-----------------|
| 21 | Hide Panel button | `.button` idx 0 / section:nth-child(1) button | click | conditional panel disappears, button says "Show Panel" |
| 22 | Show Panel button | same | click | panel reappears, button says "Hide Panel" |
| 23 | Hide visibility button | `.button` idx 1 / section:nth-child(2) button | click | div becomes hidden, button says "Show" |
| 24 | Show visibility button | same | click | div visible again, button says "Hide" |
| 25 | Style A button | (dynamic class section) | click | text shows "Current class: style-a" |
| 26 | Style B button | | click | text shows "Current class: style-b" |
| 27 | Style C button | | click | text shows "Current class: style-c" |
| 28 | Add item input | `.input` (foreach section) / input in foreach section | fill "Mango" | input has "Mango" |
| 29 | Add item button | (submit btn in foreach section) | click | "Mango" tag appears, input cleared |
| 30 | Add empty item | click Add with empty input | | no new tag added |
| 31 | View mode toggle | (view mode section button) | click | switches from list to grid view |
| 32 | View mode toggle back | same | click | switches back to list view |

## NestedReactiveUI

| # | Element | Selector (JFX / Web) | Action | Expected Result |
|---|---------|---------------------|--------|-----------------|
| 33 | Hide Outer button | first button in nested when section | click | outer+inner panels disappear |
| 34 | Show Outer button | same | click | outer panel reappears (inner should also be visible if innerShow=true) |
| 35 | Hide Inner button | second button in nested when section | click | inner panel disappears, outer stays |
| 36 | Show Inner button | same | click | inner panel reappears |
| 37 | Increment All button | foreach section | click | "Global count: 1" displayed, each item shows "global: 1" |
| 38 | Increment All again | same | click | count = 2 |
| 39 | Select item Alpha | click Alpha in foreachKeyed section | click | "Selected: Alpha" + "(selected)" appears next to Alpha |
| 40 | Select item Beta | click Beta | click | "Selected: Beta" + "(selected)" next to Beta, removed from Alpha |
| 41 | Switch to Tags | mode toggle button | click | items show as tags instead of list |
| 42 | Switch to List | same | click | items show as list again |
| 43 | Filter (A only) | filter button | click | only "Alpha" shown in filtered list |
| 44 | Show All | same button | click | all items shown again |

## CollectionOpsUI

| # | Element | Selector (JFX / Web) | Action | Expected Result |
|---|---------|---------------------|--------|-----------------|
| 45 | Add item input | input in add/remove section | fill "Deploy v2" | input has "Deploy v2" |
| 46 | Add button | Add button | click | "Deploy v2" appears in list, input cleared |
| 47 | Remove Last button | Remove Last | click | last item removed |
| 48 | Reverse button | Reverse | click | item order reversed |
| 49 | Clear button | Clear | click | all items gone, empty state shows "No items. Add some above!" |
| 50 | Add after clear | fill "Recovery" + click Add | | "Recovery" appears, empty state hidden |
| 51 | Tick button | batch updates section | click | "Count: 1", tags show "(1)" |
| 52 | Tick again | same | click | "Count: 2", tags show "(2)" |
| 53 | Set Single button | edge case section | click | only "Only" tag shown |
| 54 | Reset button | edge case section | click | "Red", "Green", "Blue" restored |

## DynamicStyleUI

| # | Element | Selector (JFX / Web) | Action | Expected Result |
|---|---------|---------------------|--------|-----------------|
| 55 | Green bg button | dynamic background section | click | box background changes to green (#dcfce7) |
| 56 | Red bg button | | click | box background changes to red (#fee2e2) |
| 57 | A+ font button | font size section | click | text gets larger, display shows "16px" |
| 58 | A- font button | | click | text gets smaller, display shows "14px" |
| 59 | More padding button | padding section | click | padding increases, display shows "16px" |
| 60 | Less padding button | | click | padding decreases, display shows "12px" |
| 61 | Bold toggle | style toggles section | click | text becomes bold, button says "Bold: ON" |
| 62 | Bold toggle off | same | click | text normal weight, button says "Bold: OFF" |
| 63 | Italic toggle | | click | text becomes italic |
| 64 | Underline toggle | | click | text becomes underlined |
| 65 | Thicker border | border section | click | border gets thicker, display shows "2px" |
| 66 | Thinner border | | click | border gets thinner, display shows "1px" |

## InteractiveUI

| # | Element | Selector (JFX / Web) | Action | Expected Result |
|---|---------|---------------------|--------|-----------------|
| 67 | Disable toggle button | disabled state section | click | "Target Button" and "Target Input" become disabled, button text = "Enable" |
| 68 | Enable toggle button | same | click | re-enabled, button text = "Disable" |
| 69 | Focus input | focus/blur section input | click/focus | text shows "Focused" |
| 70 | Blur input | click elsewhere | | text shows "Blurred" |

## KeyboardNavUI

| # | Element | Selector (JFX / Web) | Action | Expected Result |
|---|---------|---------------------|--------|-----------------|
| 71 | Clear log button | key event log section | click | log entries cleared |

**Note**: Most KeyboardNavUI tests require keyboard simulation which is limited in the test harness. We test what we can (clear button, focus styles) and document the rest as untestable via harness.

## Total: 71 tests across 7 interactive UIs
