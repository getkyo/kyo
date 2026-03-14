# Pipeline Quality Plan

Goal: all pipeline code is **correct, simple, safe, readable, and modular**.

This plan covers `kyo-ui/shared/src/main/scala/kyo/internal/tui/pipeline/` and establishes standards for all future phases.

---

## Principles

These are non-negotiable for all pipeline code — current and future phases.

### Types over primitives
- **Use `Length` for all dimensional values** — widths, padding, margins, gaps, offsets, radii. Use `Length` (can be `Auto`/`Pct`/`Px`/`Em`) for values that need parent context, `Length.Px` for values that are always resolved pixels. Use `N.px`, `N.pct` extension methods in tests and usage code.
- **Use `RGB` for all colors** — opaque type over `Int`. Use `RGB(r, g, b)` to construct, `.r`/`.g`/`.b` extensions to extract. Use `RGB.Transparent` not `-1`.
- **Use `FlatStyle`** (not `ComputedStyle`) for the resolved style type.
- **Use enums for all categorical values** — `Style.FlexDirection`, `Style.Alignment`, `Style.Justification`, etc. Never store as `Int` ordinals. Match on enum variants, never compare `.ordinal`.
- **Use `Maybe[T]` for absent values** — never sentinel ints like `-1`, `-2`, `Int.MinValue`.

### Logic belongs to its type
- **Methods that operate on a type belong to that type**, not to consumers. If multiple pipeline stages need the same operation on a value, it's a method on the type.
  - `Length.resolve`, `Length.resolveOrAuto`, `Length.toPx` — resolution is about what `Length` means
  - `Rect.intersect` — intersection is geometry, not layout algorithm
  - `RGB.fromStyle`, `.r`/`.g`/`.b` — color operations belong to the color type
- **Prefer instance methods** over companion object functions when the method operates on a single value of the type.
- **Don't create private wrapper aliases** — if a method moves to a type, update all call sites directly. No `private def resolve(...) = Length.resolve(...)`.

### AllowUnsafe discipline — THE cardinal rule

Every method falls into exactly one of three categories. **No exceptions. No hybrids.**

1. **A pure method without any side effects doesn't need `AllowUnsafe` nor to compose computations.** Just data in, data out.
   - Examples: `Styler.style`, `Layout.layout`, `Painter.paint`, `Differ.diff`, `Compositor.composite`

2. **Methods that perform side effects without returning a computation MUST take `AllowUnsafe`.**
   - Examples: `SignalRef.Unsafe.get()`, `SignalRef.Unsafe.set()`, `WidgetStateCache.getOrCreate`
   - Examples: Lower Phase 2 walk methods, `readBooleanOrSignal`, `readStringOrRef`

3. **All methods that return a computation MUST NOT take `AllowUnsafe` and use `Sync.Unsafe.defer` to call APIs that take `AllowUnsafe`.**
   - Examples: `Pipeline.renderFrame`, `Pipeline.dispatchEvent`, `Lower.lower`, `Dispatch.dispatch`
   - `Sync.Unsafe.defer` is ONLY for the boundary — wrapping the initial unsafe reads before entering computation land

### Signal usage in computation code

**Always use the safe `SignalRef` API in computation code.** Call `.safe` on any `SignalRef.Unsafe` to get the safe view. The safe `.get` and `.set` return `< IO` — proper computations that compose naturally.

**Never use `Sync.Unsafe.defer` to suspend unsafe signal operations inside computation code.** If you need to read or write a signal inside `.map`, `.andThen`, or a handler closure, use the safe API.

```scala
// WRONG — Sync.Unsafe.defer inside computation code
val handler: Unit < Async =
    Sync.Unsafe.defer {
        val pos = cursorPos.get()
        cursorPos.set(pos + 1)
    }

// WRONG — bare unsafe .set() inside .andThen
someComputation.andThen(cursorPos.set(pos + 1))

// RIGHT — safe ref, .get and .set return < IO
val cursorRef = cursorPos.safe
cursorRef.get.map { pos =>
    cursorRef.set(pos + 1)
}
```

**Handler closures are pure computation code.** They capture safe refs (obtained via `.safe` at construction time in the Rule 2 lowering method) and use only the safe API:

```scala
// Construction time (Rule 2 method with AllowUnsafe):
val cursorRef   = cursorPos.safe      // safe view captured once
val valueRef    = cachedValueRef.safe
val disabledRef = cachedDisabledRef.safe

// Handler (pure computation — no AllowUnsafe, no Sync.Unsafe.defer):
val widgetOnKeyDown: UI.KeyEvent => Unit < Async = ke =>
    disabledRef.get.map { disabled =>
        if !disabled then
            cursorRef.get.map { pos =>
                valueRef.set(newVal)
                    .andThen(cursorRef.set(pos + 1))
                    .andThen(fireCallback(newVal))
            }
        else ()
    }
```

### Control flow
- **No `while`, `var` for loop control, or `return`** — use `@tailrec def loop`. Local `var` is acceptable only for building up an immutable result in bounded scope.
- **No `asInstanceOf`** — pattern match instead.

### Readability
- **One statement per line** — no semicolons joining statements.
- **No magic numbers** — every non-obvious literal gets a named constant, a typed value, or an inline comment explaining the value.
- **Comments explain "why" (domain constraints), not "what" (restating code).**
- **Protocol/encoding details behind named methods** — no raw byte sequences inline in business logic (see Differ's `enableBold`, `writeFgColor`, etc.).
- **Descriptive intermediate names** — `afterAttrs`, `afterFg`, not `sgr2`, `sgr3`.
- **Reduce parameter explosion** — use nested defs to close over invariant parameters in recursive helpers.

### Layout contract
- **`available` rect is authoritative** — when `arrange` is called, the `available` rect represents the size assigned by the parent (flex, table, or viewport). The child's declared size is only used during intrinsic measurement.
- **`resolveAvailable`** bridges explicit sizes to the available rect — called at the root and for overlays to resolve the node's declared size before passing to `arrange`.
- **Flex measurement resolves percentages** — `measureFlexChildren` resolves percentage sizes against the parent content dimensions, not against `Int.MaxValue`.

---

## Completed improvements

All items below are done and tested (245 tests passing).

### `Length` type (replaces `SizeEnc`)
- Created `kyo-ui/shared/src/main/scala/kyo/Length.scala` — standalone ADT with `Px | Pct | Em | Auto`
- `Length.resolve`, `Length.resolveOrAuto`, `Length.toPx` — resolution logic on the type, not in consumers
- Removed `Style.Size` from `Style.scala` — all references use `Length` directly
- 27 `FlatStyle` fields typed as `Length` or `Length.Px`
- Deleted `SizeEnc` — no more fragile Int encoding for sizes
- Styler assigns `Length` directly, uses `Length.toPx` for `Length.Px` fields
- Layout calls `Length.resolve` / `Length.resolveOrAuto` — no overflow bugs

### `RGB` opaque type (replaces `ColorEnc`)
- Opaque type `RGB = Int` with `CanEqual`
- 11 color fields + `gradientStops` + `Cell.fg/bg` + `ResolvedTheme.fg/bg` typed
- Extension methods `.r`, `.g`, `.b`, `.raw` for extraction
- `RGB.fromStyle` for conversion from `Style.Color`
- Deleted `ColorEnc` entirely

### `FlatStyle` (replaces `ComputedStyle`)
- Renamed type and all field references (`computed` → `style`)
- 12 enum-typed fields (not Int ordinals)
- `gradientDirection: Maybe[Style.GradientDirection]` replaces `Int` with 0=absent encoding
- FontWeight uses explicit match instead of `.ordinal` comparison

### `Rect.intersect` instance method
- Moved from Layout private helper to Rect instance method — geometry belongs to the geometry type

### Layout correctness
- `arrange` uses `available` as authoritative parent-assigned size
- `resolveAvailable` bridges explicit sizes to available rects
- `measureFlexChildren` resolves percentage sizes against parent dimensions
- Auto-width children in cross axis fill parent when intrinsic size is 0
- `categorizeChildren` uses nested `@tailrec def loop` (3 params vs 13)
- `layoutOverlayChildren` uses pattern match instead of `asInstanceOf`

### Differ type safety
- `TermState.fg/bg: Maybe[RGB]` — no sentinel values
- `TermState.cursorPos: Maybe[(Int, Int)]` — no `-1` sentinel
- Named ANSI primitives (`enableBold`, `resetAllAttributes`, `moveCursorTo`)

### Other
- `WidgetKey.child` — instance method, not companion object function
- `ImageData.bytes` — `Span[Byte]` not `IArray[Byte]`
- `Cursor.wait_` → `Cursor.await` (conflicts with `Object.wait`)

---

## Standards for future phases

When implementing Phases 3 (Lower), 6 (Painter), 8 (Dispatch), 9 (Pipeline), 10 (Integration):

### Before writing code
1. **Re-read this entire plan** — every principle is non-negotiable. Don't start coding until you've reviewed them.
2. Ask: does this logic belong to the type or to the consumer?
3. Use `Length` for dimensions, `RGB` for colors, enums for categories, `Maybe` for absence
4. Use `FlatStyle` (not `ComputedStyle`)
5. The IMPLEMENTATION-PLAN.md pseudocode for unimplemented phases uses `while`/`return`/`Int` ordinals — these are **wrong** and must be rewritten using the patterns established in completed phases
6. **IR types are fully immutable** — no `var`, no mutable `Array`, no mutable fields. If you need mutable state during a computation (e.g., painting cells), use a private mutable buffer internally and freeze into an immutable IR type at the boundary. See Painter's `Canvas` pattern.

### While writing code
- All iteration via `@tailrec def loop` — no `while`
- One statement per line — no `;`
- Pattern match, not `asInstanceOf`
- Methods that operate on a type go on the type — `Rect.intersect`, `Length.resolve`, etc.
- Instance methods over companion functions when operating on a single value
- Recursive helpers close over invariants via nested defs
- Name intermediates after what changed — `afterAttrs`, `afterFg`, not `state2`, `state3`
- Comments explain domain constraints ("terminals can't turn off individual attributes"), not what code does ("reset and re-emit")
- Protocol/encoding details behind named methods — never inline raw byte writes or bit manipulation in business logic
- **Apply every established pattern proactively** — don't wait to be told "use enums here too." If a pattern was established (enums over ordinals, `Maybe` over sentinels, `Length` over `Int`, instance methods over companions), apply it to every new piece of code without being asked.

### Before submitting for review
- All tests pass (existing + new)
- No raw `Int` for colors/sizes/enums anywhere in the diff
- No `.ordinal`, sentinel values, `while`, `return`, `asInstanceOf`, `;`-joined statements
- No `var` in IR types — case classes must be fully immutable
- No private wrappers that just delegate to a type method — update all call sites directly
- Audit every private helper: could it be an instance method on its primary argument type?
- Audit every `Int` parameter and field: should it be `Length`, `RGB`, an enum, or `Maybe[T]`?
- Audit every comment: does it explain "why" or just restate the code?
- **Audit for immutability**: does any IR case class have `var`, mutable `Array`, or `ChunkBuilder` as a field? If so, redesign — use a private mutable buffer internally and freeze at the boundary.

## How to handle user feedback

### TODOs in code
- The user adds TODO comments as feedback — these are instructions, not suggestions
- Address **exactly** what the TODO says — don't reinterpret, expand scope, or change things the TODO didn't ask about
- If ambiguous, present options and wait — don't guess
- After addressing a TODO, remove it — don't leave comments about intermediate development state

### When the user says to do something
- Do it. Don't propose alternatives, don't add caveats, don't do half the work and ask if they want the rest
- If the change is mechanical (rename, replace all occurrences), do ALL occurrences including tests, plan files, and documentation — no laziness, no "these are just pseudocode"
- Don't create wrapper aliases for backward compatibility unless explicitly asked
- Don't leave stale references anywhere — grep and fix everything

### When making design decisions
- Present options and wait for the user's choice — don't pick one and implement it
- If the user has established a pattern (e.g., enums over ordinals, instance methods over companions), follow it without being told each time
- If you notice an opportunity to improve quality (like moving logic to its type), raise it and wait for approval before implementing
- Never diverge from the user's design — if they say "use Length", use Length everywhere, not "Length in some places and Int aliases in others"

### When tests fail
- **NEVER change a test to make it pass.** If a test fails, the code is wrong, not the test. Changing test expectations to match buggy behavior is reward hacking.
- The only exception: if the test itself has a genuine bug (wrong assertion logic, wrong setup). Even then, explain why the test is wrong before changing it.
- Fix the root cause in the production code. Trace through the failing case to understand what's actually wrong.

### Quality bar
- The code should get MORE readable and safe with each change, never less
- Every change is an opportunity to raise quality — don't just fix the immediate issue, look for the pattern and apply it consistently
- When in doubt about whether something is good enough, it isn't — do the thorough version
- **Don't default to the easy/fast path.** The correct path is the one that follows every principle in this plan. If that takes more work, do the work.

---

## What stays as plain `Int`

These are justified — they represent actual counts or pixel coordinates, not encoded values:

- `Rect(x, y, w, h)` — resolved pixel coordinates
- `ImageData.width/height` — pixel dimensions
- `CellGrid.width/height` — grid dimensions
- `Handlers.colspan/rowspan` — grid span counts
- `Cursor.charOffset` — character index
- `FlatStyle.lineHeight` — integer multiplier
- `FlatStyle.scrollTop/Left` — pixel offsets
- `Handlers.tabIndex: Maybe[Int]` — focus order

---

## Follow-up items

Things to address in future sessions:

### Code quality
- **Math methods on `Length`** — add `+`, `-`, `*`, `/` where they make sense to avoid `.value.toInt` scattered through Layout and Painter
- **Full code review** — read through all pipeline files end-to-end now that the pipeline is complete, looking for readability improvements, missing scaladocs, and patterns that could be simplified
- **Dispatch and Pipeline tests** — Dispatch and Pipeline have no dedicated test files yet. The integration tests in PipelineTest cover basic wiring but don't test hitTest, findByKey, cycleFocus, forId redirect, etc.

### Backend
- **TuiBackend** — shared code, wire Pipeline with terminal I/O. Runs in `Async & Scope`. Needs raw terminal mode, ANSI output, input event loop
- **TerminalEmulator** — JVM-only headless testing harness. Wraps Pipeline with a simulated terminal for snapshot testing
- **Input parsing** — convert raw terminal input bytes to `InputEvent` (keyboard, mouse, paste)
- **Signal change detection** — detect when SignalRef values change and trigger re-render automatically

### Plan maintenance
- **Update IMPLEMENTATION-PLAN.md** — some detailed algorithm listings are stale (Lower, Dispatch). The pseudocode was updated for quality patterns but may diverge from actual implementation
- **Update PIPELINE-QUALITY-PLAN.md** — add rules about `AllowUnsafe` discipline: unsafe reads to set up computations, no unsafe in composed effects. Add rules about `.andThen` over `.map(_ =>)` and `.unit` over `.map(_ => ())`
