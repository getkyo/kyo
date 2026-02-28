# Interaction Test Plan

## DemoUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 1 | Counter + button | `.counter-btn` idx 1 | click | `.counter-value` text = "1" | `.counter-value` text = "1" |
| 2 | Counter + button | `.counter-btn` idx 1 | click again | `.counter-value` text = "2" | `.counter-value` text = "2" |
| 3 | Counter - button | `.counter-btn` idx 0 | click | `.counter-value` text = "1" | `.counter-value` text = "1" |
| 4 | Todo input | `.input` idx 0 | fill "Buy milk" | input contains "Buy milk" | input contains "Buy milk" |
| 5 | Todo Add button | `.submit` | click | todo list contains "Buy milk" | todo list contains "Buy milk" |
| 6 | Todo input | `.input` idx 0 | fill "Walk dog" | input contains "Walk dog" | input contains "Walk dog" |
| 7 | Todo Add button | `.submit` | click | todo list has 2 items | todo list has 2 items |
| 8 | Delete first todo | `.delete-btn` idx 0 | click | first todo is "Walk dog" | first todo is "Walk dog" |
| 9 | Theme toggle | `.theme-toggle` | click | root gets `.dark` class | root gets `.dark` class |
| 10 | Theme toggle | `.theme-toggle` | click again | `.dark` class removed | `.dark` class removed |

## ReactiveUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 11 | Hide Panel button | `.button` idx 0 | click | conditional panel hidden | conditional panel hidden |
| 12 | Show Panel button | `.button` idx 0 | click again | conditional panel visible | conditional panel visible |
| 13 | Hide visibility | `.button` idx 1 | click | element hidden | element hidden |
| 14 | Show visibility | `.button` idx 1 | click again | element visible | element visible |
| 15 | Style A button | `.button` idx 2 | click | "Current class: style-a" | "Current class: style-a" |
| 16 | Style B button | `.button` idx 3 | click | "Current class: style-b" | "Current class: style-b" |
| 17 | Add item input | `.input` | fill "Mango" | input has "Mango" | input has "Mango" |
| 18 | Add button | `.button` (after input) | click | "Mango" tag appears | "Mango" tag appears |
| 19 | View mode toggle | `.button` (last) | click | switches to grid view | switches to grid view |

## FormUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 20 | Name input | `#name` | fill "Alice" | value = "Alice" | value = "Alice" |
| 21 | Email input | `#email` | fill "a@b.com" | value = "a@b.com" | value = "a@b.com" |
| 22 | Submit button | `.submit` | click | submitted text shows values | submitted text shows values |
| 23 | Disable toggle | `.button` idx 0 (Disabled Controls) | click | controls become disabled | controls become disabled |
| 24 | Enable toggle | same button | click again | controls become enabled | controls become enabled |

## CollectionOpsUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 25 | Task input | `.input` | fill "New task" | input has "New task" | input has "New task" |
| 26 | Add button | `.submit` / Add btn | click | "New task" appears in list | "New task" appears in list |
| 27 | Remove Last | Remove Last btn | click | last item removed | last item removed |
| 28 | Reverse | Reverse btn | click | list order reversed | list order reversed |
| 29 | Clear | Clear btn | click | list empty, empty state shown | list empty, empty state shown |
| 30 | Tick button | `.button` (Tick) | click | counter = 1, tags show (1) | counter = 1, tags show (1) |
| 31 | Set Single | Set Single btn | click | only "Only" tag shown | only "Only" tag shown |
| 32 | Reset | Reset btn | click | Red, Green, Blue tags shown | Red, Green, Blue tags shown |

## DynamicStyleUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 33 | Green color btn | color button idx 1 | click | preview bg changes to green | preview bg changes to green |
| 34 | A+ button | A+ btn | click | font size increases to 16px | font size increases to 16px |
| 35 | A- button | A- btn | click | font size decreases to 14px | font size decreases to 14px |
| 36 | Bold toggle | Bold btn | click | text "Bold: ON", text becomes bold | text "Bold: ON", text becomes bold |
| 37 | Bold toggle off | Bold btn | click again | text "Bold: OFF", text normal | text "Bold: OFF", text normal |
| 38 | Italic toggle | Italic btn | click | text "Italic: ON" | text "Italic: ON" |
| 39 | Underline toggle | Underline btn | click | text "Underline: ON" | text "Underline: ON" |
| 40 | Thicker border | Thicker btn | click | border width increases to 2px | border width increases to 2px |

## NestedReactiveUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 41 | Hide Outer | Hide Outer btn | click | outer panel hidden | outer panel hidden |
| 42 | Show Outer | Show Outer btn | click again | outer + inner visible | outer + inner visible |
| 43 | Hide Inner | Hide Inner btn | click | inner panel hidden, outer visible | inner panel hidden, outer visible |
| 44 | Show Inner | Show Inner btn | click again | inner visible again | inner visible again |
| 45 | Increment All | Increment All btn | click | global count = 1, rows show 1 | global count = 1, rows show 1 |
| 46 | Click Alpha item | Alpha row | click | "Selected: Alpha" shown | "Selected: Alpha" shown |
| 47 | Switch to Tags | Switch btn | click | items render as tags | items render as tags |
| 48 | Filter (A only) | Filter btn | click | only Alpha shown | only Alpha shown |

## TableAdvancedUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 49 | Name input | `.input` | fill "Eve" | input has "Eve" | input has "Eve" |
| 50 | Add Row | Add Row btn | click | new row with "Eve" in table | new row with "Eve" in table |
| 51 | Remove Last | Remove Last btn | click | last row removed | last row removed |

## InteractiveUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 52 | Disable toggle | Disable btn | click | Target Button and Input disabled | Target Button and Input disabled |
| 53 | Enable toggle | same btn | click again | Target Button and Input enabled | Target Button and Input enabled |
