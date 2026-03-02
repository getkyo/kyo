# Interaction Test Plan — Fix Validation (val2)

## FormUI (Fixes 1, 4, 5)

| # | Test | Action | Expected Result |
|---|------|--------|-----------------|
| 1 | Fill name | jfx-fill / web-fill `[id=name]` "Alice" | value = "Alice" |
| 2 | Fill email (jfx-fill-nth) | jfx-fill-nth .input 1 "alice@test.com" / web-fill `[id=email]` | value = "alice@test.com" |
| 3 | Fill textarea | jfx-fill .textarea "Hello" / web-fill textarea | value = "Hello" |
| 4 | Select option2 (jfx-select) | jfx-select .choice-box option2 / web-js select.value | value = "option2" |
| 5 | Check checkbox (jfx-check) | jfx-check .check-box true / web-click `input[type=checkbox]` | checked = true |
| 6 | Submit form | jfx-click / web-click Submit button | Shows "Submitted: Name=Alice, Email=alice@test.com, Text=Hello, Select=option2, Check=true" |
| 7 | Click checkbox via click (Fix 1) | jfx-click .check-box | toggles to unchecked (Check=false) |
| 8 | Disable All | click Disable All button | inputs become disabled |
| 9 | Enable All | click Enable All button | inputs re-enabled |

## NestedReactiveUI (Fixes 2-3)

| # | Test | Action | Expected Result |
|---|------|--------|-----------------|
| 10 | Render nested | render nested | "OK: Rendered 'nested'" — no crash |
| 11 | Hide Outer | click Hide Outer button | button text = "Show Outer", panels disappear |
| 12 | Show Outer | click Show Outer button | button text = "Hide Outer", panels reappear |
| 13 | Hide Inner | click Hide Inner button | inner panel hides, outer stays |
| 14 | Show Inner | click Show Inner button | inner panel reappears |
| 15 | Increment All | click Increment All | "Global count: 1", items show "global: 1" |
| 16 | Increment All x2 | click again | "Global count: 2" |
| 17 | Select Alpha | click Alpha in foreachKeyed | "Selected: Alpha" |
| 18 | Select Beta | click Beta | "Selected: Beta", Alpha loses selection |
| 19 | Switch to Tags | click mode toggle | button says "Switch to List", items as tags |
| 20 | Switch to List | click again | button says "Switch to Tags", items as list |
| 21 | Filter A only | click Filter button | only Alpha shown |
| 22 | Show All | click again | all 3 items shown |

## DemoUI (Regression)

| # | Test | Action | Expected Result |
|---|------|--------|-----------------|
| 23 | Counter + | click + button | counter = "1" |
| 24 | Counter + again | click + button | counter = "2" |
| 25 | Counter - | click - button | counter = "1" |
| 26 | Counter - to 0 | click - button | counter = "0" |
| 27 | Todo fill (web-fill compound) | web-fill ".todo-input input" "Buy milk" | input has "Buy milk" |
| 28 | Todo Add | click Add | "Buy milk" in list |
| 29 | Todo fill+add 2 | fill "Walk dog" + click Add | 2 items |
| 30 | Todo delete | click first delete-btn | "Walk dog" remains |
| 31 | Theme toggle on | click theme toggle | dark mode on |
| 32 | Theme toggle off | click theme toggle | dark mode off |

## ReactiveUI (Regression — Signal[UI] with foreach)

| # | Test | Action | Expected Result |
|---|------|--------|-----------------|
| 33 | Hide Panel | click Hide Panel | "Show Panel" text |
| 34 | Show Panel | click Show Panel | "Hide Panel" text |
| 35 | Style B | click Style B button | "Current class: style-b" |
| 36 | Style C | click Style C button | style-c class exists |
| 37 | Fill foreach input | fill "Mango" | input has "Mango" |
| 38 | Add item | click Add | Mango tag present |
| 39 | Grid mode | click view toggle | "Switch to List" |
| 40 | List mode | click again | "Switch to Grid" |

## CollectionOpsUI (Regression)

| # | Test | Action | Expected Result |
|---|------|--------|-----------------|
| 41 | Fill + Add | fill "Deploy v2", click Add | "Deploy v2" in list |
| 42 | Remove Last | click Remove Last | item removed |
| 43 | Clear | click Clear | empty state shown |
| 44 | Add after clear | fill "Recovery" + Add | "Recovery" in list |
| 45 | Tick counter | click Tick | "Count: 1" |

## Total: 45 tests across 5 UIs
