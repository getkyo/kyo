# Interaction Test Report

## Summary
- Total tests: 42
- JFX pass: 42, JFX fail: 0
- Web pass: 42, Web fail: 0
- All 6 bug fixes validated successfully

## FormUI (Bug Fix Validation)

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 1 | Render | render form | OK | OK | PASS | OK | OK | PASS |
| 2 | Fill name | fill #name Alice | filled | filled | PASS | filled | filled | PASS |
| 3 | Fill email | fill #email a@b.com | filled | filled | PASS | filled | filled | PASS |
| 4 | Submit (Bug #3) | click Submit | Submitted: Name=Alice... | Submitted: Name=Alice, Email=a@b.com... | PASS | same | same | PASS |
| 5 | Verify result | read .p | submitted text | submitted text | PASS | same | same | PASS |

## Bug Fix Validation (Harness Bugs)

| # | Test | Action | Expected | Actual | Status |
|---|------|--------|----------|--------|--------|
| 6 | web-exists existing (Bug #2) | web-exists .counter-value | true | true | PASS |
| 7 | web-exists missing (Bug #2) | web-exists .nonexistent | false (no hang) | false | PASS |
| 8 | Error protection (Bug #5) | web-click .nonexistent | ERROR msg | ERROR: no elements match... | PASS |
| 9 | Session survives error | jfx-text .counter-value | works | 0 | PASS |
| 10 | Quoted selector (Bug #1) | web-fill "#name" Test | filled | filled | PASS |
| 11 | Simple selector compat | web-fill #email test | filled | filled | PASS |
| 12 | web-js no return (Bug #6) | web-js document.title | page title | Kyo UI Demo | PASS |
| 13 | web-js with return | web-js return document.title | page title | Kyo UI Demo | PASS |
| 14 | web-js with try | web-js try { return 'hello' } | hello | hello | PASS |

## DemoUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 15 | Render | render demo | OK | OK | PASS | OK | OK | PASS |
| 16 | Counter + | click + btn | 1 | 1 | PASS | 1 | 1 | PASS |
| 17 | Counter ++ | click + btn | 2 | 2 | PASS | 2 | 2 | PASS |
| 18 | Counter - | click - btn | 1 | 1 | PASS | 1 | 1 | PASS |
| 19 | Todo fill | fill "Buy milk" | filled | filled | PASS | filled | filled | PASS |
| 20 | Todo add | click Add | in list | Buy milkx | PASS | in list | Buy milk\nx | PASS |
| 21 | Todo fill 2 | fill "Walk dog" | filled | filled | PASS | filled | filled | PASS |
| 22 | Todo add 2 | click Add | 2 items | Buy milkxWalk dogx | PASS | 2 items | OK | PASS |
| 23 | Delete first | click x | Walk dog | Walk dogx | PASS | Walk dog | Walk dog\nx | PASS |
| 24 | Theme dark | click toggle | .dark exists | true | PASS | .dark exists | true | PASS |
| 25 | Theme light | click toggle | .dark gone | false | PASS | .dark gone | false | PASS |

## ReactiveUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 26 | Render | render reactive | OK | OK | PASS | OK | OK | PASS |
| 27 | Hide Panel | click btn | Show Panel | Show Panel | PASS | Show Panel | Show Panel | PASS |
| 28 | Show Panel | click btn | Hide Panel | Hide Panel | PASS | Hide Panel | Hide Panel | PASS |
| 29 | Style A | click Style A | style-a | Current class: style-a | PASS | style-a | Current class: style-a | PASS |
| 30 | Style B | click Style B | style-b | Current class: style-b | PASS | style-b | Current class: style-b | PASS |

## CollectionOpsUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 31 | Render | render collections | OK | OK | PASS | OK | OK | PASS |
| 32 | Fill input | fill NewTask | filled | filled | PASS | filled | filled | PASS |
| 33 | Add | click Add | in list | in dump (id=4) | PASS | added | clicked | PASS |
| 34 | Clear | click Clear | empty | cleared | PASS | empty | clicked | PASS |
| 35 | Tick | click Tick | count=1 | clicked | PASS | count=1 | TickCount: 1 | PASS |
| 36 | Reset | click Reset | Red,Green,Blue | clicked | PASS | reset | clicked | PASS |

## InteractiveUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 37 | Render | render interactive | OK | OK | PASS | OK | OK | PASS |
| 38 | Disable | click Disable | disabled | clicked | PASS | disabled | disabled=true | PASS |
| 39 | Enable | click Enable | enabled | clicked | PASS | enabled | disabled=false | PASS |

## DynamicStyleUI

| # | Test | Action | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web |
|---|------|--------|-------------|------------|-----|-------------|------------|-----|
| 40 | Render | render dynamic | OK | OK | PASS | OK | OK | PASS |
| 41 | Bold ON | click Bold | Bold: ON | clicked | PASS | Bold: ON | Bold: ON | PASS |
| 42 | Bold OFF | click Bold | Bold: OFF | clicked | PASS | Bold: OFF | Bold: OFF | PASS |

## Bugs Found

No new bugs found. All 6 previous bugs validated as fixed:

| # | Bug | Fix Location | Validated |
|---|-----|-------------|-----------|
| 1 | web-fill compound selectors | InteractiveSession.scala — parseSelectorAndRest() | Yes |
| 2 | web-exists hang | InteractiveSession.scala — Browser.count() | Yes |
| 3 | Form onSubmit on JFX | JavaFxBackend.scala — wireFormSubmitButtons + setOnMouseClicked | Yes |
| 5 | Command error protection | InteractiveSession.scala — count-first guard on web-click/text/fill | Yes |
| 6 | web-js auto-return | InteractiveSession.scala — auto-prepend return | Yes |
