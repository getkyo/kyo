# Test Plan — Bug Fix Validation + General QA

## Part A: Bug Fix Validation

### BV-1: Form onSubmit on JFX (Bug #3 fix)
| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 1 | Render FormUI | | render form | OK | OK |
| 2 | Name input | jfx: `#name` / web: `#name` | fill "Alice" | value = Alice | value = Alice |
| 3 | Email input | jfx: `#email` / web: `#email` | fill "a@b.com" | value = a@b.com | value = a@b.com |
| 4 | Submit button | jfx: `.button` last / web: button with "Submit" | click | "Submitted: Name=Alice..." | "Submitted: Name=Alice..." |
| 5 | Verify no "Not submitted yet" | jfx-text / web-text | read | should NOT say "Not submitted yet" | should NOT say "Not submitted yet" |

### BV-2: web-exists non-blocking (Bug #2 fix)
| # | Element | Action | Expected |
|---|---------|--------|----------|
| 6 | Existing element | web-exists .counter-value (after rendering demo) | OK: true (fast, no hang) |
| 7 | Non-existing element | web-exists .nonexistent-class-xyz | OK: false (fast, no hang) |

### BV-3: Command error protection (Bug #5 fix)
| # | Element | Action | Expected |
|---|---------|--------|----------|
| 8 | Bad selector | web-click .totally-nonexistent-12345 | ERROR message, session still alive |
| 9 | Verify session alive | jfx-text .counter-value (after bad web cmd) | Should still work |

### BV-4: web-fill quoted selectors (Bug #1 fix)
| # | Element | Action | Expected |
|---|---------|--------|----------|
| 10 | Compound selector | web-fill "#name" TestName (on FormUI) | OK: filled |
| 11 | Simple selector | web-fill #email test@test.com | OK: filled (backwards compat) |

### BV-5: web-js auto-return (Bug #6 fix)
| # | Element | Action | Expected |
|---|---------|--------|----------|
| 12 | Without return | web-js document.title | OK: <page title> (not hang) |
| 13 | With return | web-js return document.title | OK: <page title> (same result) |
| 14 | With try | web-js try { return 'hello' } catch(e) { return e.message } | OK: hello |

## Part B: General QA — DemoUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 15 | Render DemoUI | | render demo | OK | OK |
| 16 | Counter + | jfx: `.counter-btn` idx 1 / web: nth button | click | counter = "1" | counter = "1" |
| 17 | Counter + again | same | click | counter = "2" | counter = "2" |
| 18 | Counter - | jfx: `.counter-btn` idx 0 / web: nth button | click | counter = "1" | counter = "1" |
| 19 | Todo fill | jfx: `.input` / web: input tag | fill "Buy milk" | filled | filled |
| 20 | Todo Add | jfx: `.submit` / web: button | click | "Buy milk" in list | "Buy milk" in list |
| 21 | Todo fill 2 | same | fill "Walk dog" | filled | filled |
| 22 | Todo Add 2 | same | click | 2 items | 2 items |
| 23 | Delete first | jfx: `.button` in todo list / web: delete btn | click | first = "Walk dog" | first = "Walk dog" |
| 24 | Theme dark | jfx: theme toggle / web: theme toggle | click | .dark added | .dark added |
| 25 | Theme light | same | click | .dark removed | .dark removed |

## Part C: General QA — ReactiveUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 26 | Render ReactiveUI | | render reactive | OK | OK |
| 27 | Hide Panel | jfx/web: "Hide Panel" btn | click | panel hidden, btn says "Show Panel" | same |
| 28 | Show Panel | same | click | panel shown, btn says "Hide Panel" | same |
| 29 | Style A | jfx/web: "Style A" btn | click | text says "style-a" | same |
| 30 | Style B | jfx/web: "Style B" btn | click | text says "style-b" | same |

## Part D: General QA — CollectionOpsUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 31 | Render CollectionOpsUI | | render collections | OK | OK |
| 32 | Fill input | jfx: `.input` / web: input | fill "NewTask" | filled | filled |
| 33 | Add | jfx/web: Add btn | click | "NewTask" in list | "NewTask" in list |
| 34 | Clear | jfx/web: Clear btn | click | empty state | empty state |
| 35 | Tick | jfx/web: Tick btn | click | count=1 | count=1 |
| 36 | Reset | jfx/web: Reset btn | click | Red, Green, Blue | Red, Green, Blue |

## Part E: General QA — InteractiveUI + DynamicStyleUI

| # | Element | Selector | Action | Expected (JFX) | Expected (Web) |
|---|---------|----------|--------|-----------------|-----------------|
| 37 | Render InteractiveUI | | render interactive | OK | OK |
| 38 | Disable toggle | jfx/web: Disable btn | click | target disabled | target disabled |
| 39 | Enable toggle | same | click | target enabled | target enabled |
| 40 | Render DynamicStyleUI | | render dynamic | OK | OK |
| 41 | Bold ON | jfx/web: Bold btn | click | "Bold: ON" | "Bold: ON" |
| 42 | Bold OFF | same | click | "Bold: OFF" | "Bold: OFF" |

## Total: 42 tests
