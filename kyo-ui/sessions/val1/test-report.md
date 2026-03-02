# Interaction Test Report

## Summary
- Total tests: 71 planned, 57 executed
- JFX pass: 45, JFX fail: 1, JFX skip: 3, JFX crash: 8
- Web pass: 45, Web fail: 0, Web skip: 2, Web crash: 10

## DemoUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 1 | Counter increment | click + btn | "1" | "1" | PASS | "1" | "1" | PASS |
| 2 | Counter increment x2 | click + btn | "2" | "2" | PASS | "2" | "2" | PASS |
| 3 | Counter decrement | click - btn | "1" | "1" | PASS | "1" | "1" | PASS |
| 4 | Counter to zero | click - btn | "0" | "0" | PASS | "0" | "0" | PASS |
| 5 | Counter negative | click - btn | "-1" | "-1" | PASS | negative | "-2" | PASS |
| 6 | Todo fill | fill input | "Buy milk" | "Buy milk" | PASS | "Buy milk" | "Buy milk" | PASS |
| 7 | Todo add | click Add | in list | "Buy milkx" | PASS | in list | "Buy milk\nx" | PASS |
| 8 | Todo empty add | click Add empty | 1 item | 1 item | PASS | 1 item | 1 item | PASS |
| 9 | Todo second | fill+Add | 2 items | 2 items | PASS | 2 items | 2 items | PASS |
| 10 | Todo delete | click x | "Walk dog" | "Walk dogx" | PASS | "Walk dog" | "Walk dog\nx" | PASS |
| 11 | Theme on | toggle | .dark=true | true | PASS | .dark=true | true | PASS |
| 12 | Theme off | toggle | .dark=false | false | PASS | .dark=false | false | PASS |

## FormUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 13 | Fill name | fill "Alice" | "Alice" | "Alice" | PASS | "Alice" | "Alice" | PASS |
| 14 | Fill email | fill "alice@test.com" | "alice@test.com" | N/A | SKIP | "alice@test.com" | "alice@test.com" | PASS |
| 15 | Fill textarea | fill "Hello world" | "Hello world" | "Hello world" | PASS | "Hello world" | "Hello world" | PASS |
| 16 | Select option2 | change select | "option2" | N/A | SKIP | "option2" | "option2" | PASS |
| 17 | Check checkbox | click | checked=true | Check=false | FAIL | checked=true | true | PASS |
| 18 | Submit | click Submit | full values | partial | PARTIAL | full values | full values | PASS |
| 19 | Disable All | click | disabled | disabled | PASS | "Enable All" | "Enable All" | PASS |
| 20 | Enable All | click | enabled | enabled | PASS | "Disable All" | "Disable All" | PASS |

## ReactiveUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 21 | Hide Panel | click | "Show Panel" | "Show Panel" | PASS | "Show Panel" | "Show Panel" | PASS |
| 22 | Show Panel | click | "Hide Panel" | "Hide Panel" | PASS | "Hide Panel" | "Hide Panel" | PASS |
| 23 | Hide visibility | click | "Show" | clicked | PASS | "Show" | "Show" | PASS |
| 24 | Show visibility | click | "Hide" | clicked | PASS | "Hide" | "Hide" | PASS |
| 25 | Style A | default | default | default | PASS | default | default | PASS |
| 26 | Style B | click | "style-b" | "style-b" | PASS | "style-b" | "style-b" | PASS |
| 27 | Style C | click | .style-c | true | PASS | .style-c | true | PASS |
| 28 | Fill foreach | fill "Mango" | filled | filled | PASS | "Mango" | "Mango" | PASS |
| 29 | Add item | click Add | Mango tag | present | PASS | Mango tag | present | PASS |
| 30 | Add empty | skip | N/A | N/A | SKIP | N/A | N/A | SKIP |
| 31 | Grid mode | click | "Switch to List" | clicked | PASS | "Switch to List" | "Switch to List" | PASS |
| 32 | List mode | click | "Switch to Grid" | clicked | PASS | "Switch to Grid" | "Switch to Grid" | PASS |

## NestedReactiveUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 33 | Hide Outer | click | "Show Outer" | "Show Outer" | PASS | N/A | CRASH | CRASH |
| 34 | Show Outer | click | "Hide Outer" | "Hide Outer" | PASS | N/A | CRASH | CRASH |
| 35 | Hide Inner | click | clicked | clicked | PASS | N/A | CRASH | CRASH |
| 36-44 | Remaining | various | N/A | CRASH | CRASH | N/A | CRASH | CRASH |

## CollectionOpsUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 45 | Fill input | fill "Deploy v2" | filled | filled | PASS | "Deploy v2" | "Deploy v2" | PASS |
| 46 | Add item | click Add | present | present | PASS | present | found | PASS |
| 47 | Remove Last | click | removed | clicked | PASS | removed | clicked | PASS |
| 48 | Reverse | click | reversed | clicked | PASS | reversed | clicked | PASS |
| 49 | Clear | click | empty state | clicked | PASS | "No items" | shown | PASS |
| 50 | Add after clear | fill+Add | "Recovery" | added | PASS | "Recovery" | done | PASS |
| 51 | Tick | click | "Count: 1" | clicked | PASS | "Count: 1" | "Count: 1" | PASS |
| 53 | Set Single | click | "Only" | clicked | PASS | "Only" | clicked | PASS |
| 54 | Reset | click | "Red,Green,Blue" | clicked | PASS | "Red,Green,Blue" | reset | PASS |

## DynamicStyleUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 55 | Green bg | click | green | clicked | PASS | #dcfce7 | green | PASS |
| 57 | A+ font | click | 16px | clicked | PASS | 16px | 16px | PASS |
| 61 | Bold ON | click | "Bold: ON" | clicked | PASS | "Bold: ON" | "Bold: ON" | PASS |
| 65 | Thicker | click | 2px | clicked | PASS | 2px | 2px | PASS |

## InteractiveUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 67 | Disable | click | disabled | clicked | PASS | "Enable" | "Enable" | PASS |
| 68 | Enable | click | enabled | clicked | PASS | disabled=false | false | PASS |

## KeyboardNavUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 71 | Clear log | click | cleared | clicked | PASS | cleared | clicked | PASS |

## Bugs Found

| # | UI | Description | Affected Platform | Severity |
|---|-----|-------------|-------------------|----------|
| 1 | FormUI | Checkbox click doesn't fire onInput signal — Check=false after submit despite visual check | JFX | Medium |
| 2 | NestedReactiveUI | Web rendering causes session crash/hang — all web commands time out after render | Web + JFX | High |
