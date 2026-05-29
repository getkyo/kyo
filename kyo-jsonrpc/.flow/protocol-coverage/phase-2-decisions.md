# Phase 02 Decisions Log

## DEC-2-01: ProgressEngine.allocateProgressToken — putIfAbsent null check

**Plan says**: `if prior == null then token`

**Problem**: `languageStrictEquality` is enabled in the build; comparing `Channel[...]` with `null` via `==` triggers a compile error. `prior eq null` also fails because `Channel[...]` is not recognized as `AnyRef` in this context.

**Resolution**: Used `Sync.defer(Maybe(progressStreams.putIfAbsent(token, channel))).map { case Absent => token; case Present(_) => loop(...) }`. `Maybe(x)` wraps `null` as `Absent` and non-null as `Present`, which is the idiomatic kyo null-bridging pattern.

## DEC-2-02: ProgressEngine.allocateProgressToken — no .flatten needed

**Plan says**: the inner block uses `.flatten` after `Sync.defer { ... }.map { ... }`.

**Problem**: Kyo's `.map` is a monadic bind that already flattens; `.flatten` is only needed for `A < S < S2` patterns. The `.map` callback returning `Structure.Value < (Sync & Abort[JsonRpcError])` is automatically composed into the outer `Sync`, so the result type is already flat.

**Resolution**: Removed `.flatten`; Kyo's `.map` handles the composition correctly.

## DEC-2-03: JsonRpcMethod.dispatch — return type changed to `Maybe[Structure.Value < (Async & Abort[JsonRpcError])]`

**Plan says**: return type is `Maybe[Structure.Value < (S & Abort[JsonRpcError])]` with `ev.liftContra[...]` to convert.

**Problem**: `handle` is declared to return `Structure.Value < (Async & Abort[JsonRpcError])` (fixed effect row, per STEERING.md variance constraint). The `< [+A, -S]` type is CONTRAVARIANT in S. Given `ev: (Async & Abort[JsonRpcError]) <:< S`, the narrowing direction (from Async & Abort[...] to S) is NOT a valid subtype relationship for the contravariant effect slot. `ev.liftContra` and `ev.liftCo` both failed at compile time for different reasons.

**Resolution**: Declared the return type as `Maybe[Structure.Value < (Async & Abort[JsonRpcError])]` which directly matches what `handle` returns. Callers already have `(Async & Abort[JsonRpcError]) <:< S` in scope so they can use the result in S-contexts via Kyo's automatic effect widening.

## DEC-2-04: callWithProgress token alloc refactor — structure adaptation

**Plan says**: replace `Random.live.unsafe.nextStringAlphanumeric(32).map { raw => ... Sync.defer(discard(progressStreams.put(token, progChan))).andThen { ... }`.

**Problem**: Actual code has `progChan`, `deadlineRef` init AND token gen all inside a single `Sync.Unsafe.defer { ... }` block. The plan's before/after was schematic; the token gen was interleaved with channel/deadline init.

**Resolution**: Split into two steps: (1) `Sync.Unsafe.defer` for `progChan` and `deadlineRef` only; (2) `ProgressEngine.allocateProgressToken(progressStreams, progChan, 32)` called after. The `deadlineRef` registration (after token is known) is done in a separate `Sync.Unsafe.defer { deadlineRef match ... }` to keep side effects properly suspended. Added one extra closing `}` for the new `allocateProgressToken.map { tokenVal => }` lambda level.

## DEC-2-05: callPartialResults token alloc refactor — same adaptation as DEC-2-04

Same pattern as callWithProgress but simpler (no `deadlineRef`). `progChan` and `finalRef` stay in `Sync.Unsafe.defer`; token generation delegated to `allocateProgressToken`. The old `Sync.Unsafe.defer(discard(progressStreams.put(...)))` registration call is removed (registration is now inside the helper). Added one extra closing `}` for the new lambda level.

## DEC-2-06: test-14 assertion fix — `err.message` vs `err.code`

**Plan says**: `assert(err.message == "bad")` for `JsonRpcError.invalidParams("bad")`.

**Problem**: `JsonRpcError.invalidParams(reason)` creates `JsonRpcError(-32602, "Invalid params", Present(Str(reason)))`. The `message` field is always `"Invalid params"`; the custom reason goes in `data`. Asserting `err.message == "bad"` always fails.

**Resolution**: Changed assertion to `assert(err.code == JsonRpcError.InvalidParams.code)` (-32602), which correctly verifies that `invalidParams` error was propagated through dispatch. The key property (abort propagation) is preserved; the plan's specific string comparison was a spec error.
