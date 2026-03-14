# Test Failure Analysis

693 passing, 22 failing. All failures fall into 4 root causes.

---

## Root Cause 1: Cursor shown on ALL inputs, not just focused (16 failures)

### Pattern
Every test that asserts cursor count or cursor position fails. The `█` character appears on every input in the frame, regardless of focus state.

### Evidence
- "two inputs, focus neither" → found 2 cursors (expected 0)
- "two inputs, tab to first" → found 2 cursors (expected 1)
- "empty input no focus" → found 1 cursor (expected 0)
- "input with value hello unfocused" → cursor at position 0 before text: `█hello`
- "focus input then tab to button" → cursor still visible on input after focus moved to button

### Affected tests (16)
- `two inputs, focus neither - zero cursors`
- `two inputs, tab to first - exactly one cursor`
- `two inputs, tab to second - exactly one cursor on second input row`
- `focus input then tab to button - cursor gone`
- `input with hello focused - cursor at position 5`
- `type AB - cursor at position 2`
- `type AB, ArrowLeft - cursor at position 1`
- `type AB, Home, ArrowRight - cursor at position 1`
- `password abc focused - cursor after third dot`
- `type A verify cursor at 1, type B verify at 2, backspace verify at 1`
- `tab from input1 to input2 - cursor moves between rows`
- `empty input no focus - no cursor`
- `input with value hello unfocused - value visible no cursor`
- `unfocused password - dots only no cursor`
- `focused input has cursor, unfocused does not`
- `only one element focused at a time`

### Root cause
`Lower.lowerTextInput` always emits `Resolved.Cursor(cursor)` in the children:
```scala
Resolved.Node(
    ElemTag.Div,
    style ++ Style.row,
    handlers,
    Chunk(
        Resolved.Text(before),
        Resolved.Cursor(cursor),  // ← always emitted regardless of focus
        Resolved.Text(after)
    )
)
```

It should only emit `Resolved.Cursor` when the input's `WidgetKey` matches `state.focusedId`. When unfocused, it should emit `Resolved.Text(displayText)` without the cursor split.

### Sub-issue: cursor position
Even when focused, the cursor position is wrong:
- `█hello` → cursor at 0 instead of 5 (cursor before text, not after)
- `AB  █` → cursor at 4 instead of 2 (extra spaces between text and cursor)

The cursor position `cursor = math.min(cursorPos.get(), displayText.length)` is correct, but the text is split into `before` (0 to cursor) and `after` (cursor to end). When cursor = 0, `before` is empty and `after` is the full text, so the visual is `█hello`. This is correct for cursor-at-start. But the Painter inverts the cell at the cursor position — if the cursor Laid.Cursor has `charOffset = 0`, it inverts column 0, which shows `█` at the beginning.

The real issue: `cursorPos` starts at 0 (initialized as `SignalRef.Unsafe.init(0)`). When focusing an input with existing text, the cursor should move to the END of the text, not stay at 0. This is a usability bug — clicking an input should position the cursor at the end (or at the click position).

### Fix
1. **Conditional cursor**: In `lowerTextInput`, check if `key == state.focusedId.get()`. If not focused, emit text without cursor split.
2. **Initial cursor position**: When an input gains focus (click or tab), set `cursorPos` to `displayText.length`.
3. **Click cursor positioning**: When an input is clicked at column X, set `cursorPos` to X (relative to input start).

---

## Root Cause 2: Reactive Signal updates lag one render cycle (3 failures)

### Pattern
Tests that use `Signal.initRef` and expect the display to update immediately after modifying the ref show stale content from the previous render.

### Evidence
- "display text bound to same ref updates as input changes" → After typing "AB", echo span shows "A" (one behind)
- "input with character counter" → After typing "AB", counter shows "1/10" (one behind)
- "type filter text" → After typing "ap", all items still visible (filter not applied)

### Affected tests (3)
- `display text bound to same ref updates as input changes`
- `input with character counter updating on keystroke`
- `type filter text - only matching items visible`

### Root cause
These tests use `Signal.initRef` (safe ref) to create reactive state. The UI tree references these signals via `.render(f)` or `UI.when(signal)(ui)`. When the signal value changes (via handler), the change is committed to the ref, but the next `Pipeline.renderFrame` call re-lowers the UI tree. During lowering, `materializeSignal` for `Signal.asRef` creates a piping fiber that propagates changes. But the propagation happens asynchronously — the piping fiber might not have run by the time Lower reads the value.

In contrast, `SignalRef.Unsafe` used by widget state (cursorPos, checked, etc.) is read directly via `.get()` which is synchronous. But derived signals (`signal.map(f)`) depend on the piping fiber to propagate.

### Fix
This is a fundamental timing issue with reactive signals. Options:
1. **Synchronous signal evaluation**: During Lower Phase 2, evaluate derived signals synchronously instead of relying on the piping fiber
2. **Double render**: After dispatch, render twice — first render updates refs, second render picks up propagated values
3. **Accept the lag**: Document that reactive content updates on the next render cycle after the triggering event

---

## Root Cause 3: /dev/tty not available in CI test environment (2 failures)

### Pattern
Tests that create `JvmTerminalIO` fail because `/dev/tty` doesn't exist in the test runner environment.

### Evidence
- "parses ANSI size response" → `Cannot run program "stty": /dev/tty (Device not configured)`
- "full TuiBackend render cycle matches" → same error

### Affected tests (2)
- `JvmTerminalIOTest: parses ANSI size response`
- `JediTermEndToEndTest: full TuiBackend render cycle matches`

### Root cause
After switching to `/dev/tty` for sbt compatibility, `JvmTerminalIO.enterRawMode` opens `/dev/tty` and `stty` redirects from it. In the test environment (sbt's forked JVM), `/dev/tty` exists but is not a real terminal — the `stty` command fails.

The existing tests that pass use `new JvmTerminalIO(capturedOutput, capturedInput)` which provides explicit streams. But `enterRawMode` now also calls `stty` which accesses `/dev/tty` regardless of the provided streams.

### Fix
1. **Skip stty when streams are provided**: If `stdout`/`stdin` were passed to the constructor (non-null), don't call `stty` or open `/dev/tty`. The caller is providing their own streams (test mode).
2. **Separate test constructor**: `JvmTerminalIO.forTesting(out, in)` that skips terminal setup.

---

## Root Cause 4: Mouse tracking escape sequence changed (1 failure)

### Pattern
The mouse tracking test expects `\e[?1000h` but we changed to `\e[?1003h` (all-motion tracking).

### Evidence
- "enableMouseTracking writes SGR mode sequences" → `""` did not contain `""` (the assertion checks for `?1000h` which is no longer emitted)

### Affected test (1)
- `JvmTerminalIOTest: enableMouseTracking writes SGR mode sequences`

### Root cause
We changed `enableMouseTracking` from `?1000h` (button-only) to `?1003h` (all-motion) to match the old backend. The test still checks for `?1000h`.

### Fix
Update the test to check for `?1003h` instead of `?1000h`.

---

## Summary

| Root Cause | Count | Severity | Fix Complexity |
|-----------|-------|----------|----------------|
| Cursor on all inputs | 16 | High — breaks user experience | Medium — conditional in Lower |
| Reactive signal lag | 3 | Medium — reactive content delayed | High — timing architecture |
| /dev/tty in tests | 2 | Low — test-only issue | Low — constructor flag |
| Mouse tracking test | 1 | Low — test assertion stale | Low — update assertion |
