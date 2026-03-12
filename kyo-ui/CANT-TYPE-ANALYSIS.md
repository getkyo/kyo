# Analysis: "Can't type anything in forms" in TuiShowcase

## Root Cause

`TuiFocus.handleTextInput` (line 930-998 of `TuiFocus.scala`) only processes keyboard input when the input's `value` field is a `SignalRef`:

```scala
private def handleTextInput(ti: UI.TextInput, event: InputEvent, layout: TuiLayout, ...): Unit < Sync =
    ti.value match
        case Present(ref: SignalRef[?]) =>
            // ... typing logic: reads current value, inserts char, writes back
        case _ => ()   // <--- ALL OTHER CASES: SILENTLY IGNORED
```

This means text editing **only works** when `input.value(someSignalRef)` is called with a `SignalRef[String]`. Static strings and absent values produce no response to key events.

## Evidence from the Demo

Every text input in `TuiShowcase` page 1 is missing a `SignalRef` binding:

| Element | `.value(...)` | Result |
|---------|---------------|--------|
| `input.placeholder("Type your name...")` | Absent | No typing |
| `email.placeholder("user@example.com")` | Absent | No typing |
| `password.value("hunter2")` | Static `String` | No typing |
| `input.value("Cannot edit this").readOnly(true)` | Static `String` | No typing (intentional) |
| `textarea.placeholder("...")` | Absent | No typing |
| `number.value("50")` | Static `String` | No typing |

None of these have `SignalRef[String]` values, so `handleTextInput` falls through to `case _ => ()`.

## Same Pattern Applies to Other Input Types

- `handleBooleanToggle` (line 867): requires `checked` to be a `Signal[?]` wrapping a `SignalRef`
- `handlePaste` (line 1050): requires `value` to be `SignalRef`
- `handleSelectOptionClick` → `toggleSelected` (line 799): requires `selected` to be `SignalRef`

The flat pipeline is a **controlled-only** input system — no internal state management.

## Contrast with tui2 Pipeline

The tui2 pipeline (used by TerminalEmulator QA tests) has internal state fallbacks:
- `PickerW` has `ctx.pickerValues` (IdentityHashMap) for selects without SignalRef
- `TextInputW` reads/writes SignalRef but the code path is similar

However, tui2's `TextInputW` also requires SignalRef for editing (checked `ValueResolver.setString` which is a no-op on Absent).

## Fix Options

### Option A: Fix the demo to use SignalRefs (minimal, correct)

Create `SignalRef[String]` for every editable input in the demo and pass them via `.value(ref)`. This aligns with the controlled-component design.

### Option B: Add internal state to flat pipeline (larger change)

Add an `IdentityHashMap[UI.TextInput, String]` to `TuiFocus` (or a companion state object) that stores internal values for inputs without SignalRef. This would mirror how web browsers handle `<input>` without JS bindings. Much larger scope, touches the core pipeline.

## Recommendation

Option A — the pipeline's controlled-component design is intentional and consistent. The demo just needs to follow the API contract. The checkbox in page 2 already does this correctly (`checkVal: SignalRef[Boolean]`).

## Secondary Concern: Reactive Re-rendering

The `page.map[UI] { ... }` in `buildUI` creates new element objects when the page signal changes. `TuiFocus.scan` preserves focus via `prevFocusedElement eq elem.get` (object identity). When switching pages, all objects are new, so focus correctly resets.

Within a page, as long as `page` doesn't change, `Signal.map` should cache the result and return the same objects. The cursor blink timer and other signal changes trigger re-render but don't re-evaluate `page.map[UI]` (only the `page` signal changing would). So this should be fine.

**However**: signals like `log.map(...)` in the status bar, `selVal.map(...)`, `checkVal.map(...)` etc. — these create `Reactive` nodes that DO re-evaluate when their source changes. But they only produce `Text` nodes, not `Element` nodes, so they don't affect input focus.

## Conclusion

The demo's inputs need `SignalRef[String]` values to accept keyboard input. This is the only change required.
