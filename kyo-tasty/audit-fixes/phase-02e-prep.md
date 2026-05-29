# Phase 02e Prep: Privatize Symbol.TastyOrigin.addrMap

## File

`kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`

## Current addrMap signature (line 859)

```scala
// line 858 (blank)
            def addrMap(using AllowUnsafe): IntMap[Tasty.Symbol] =
                if _addrMap.isSet then _addrMap.get()
                else IntMap.empty
```

## Caller check

Total `.addrMap` references in `kyo-tasty/`: 47 matches across 8 files.

**Production callers (all in `kyo.internal.*` -- permitted by `private[kyo]`):**

- `kyo/internal/tasty/reader/TreeUnpickler.scala` (lines 45, 47, 62, 192, 299, 346, 353, 364, 388, 397, 472, 625, 662) -- reads `origin.addrMap` and `ctx.addrMap`
- `kyo/internal/tasty/reader/TypeUnpickler.scala` (lines 142, 158, 288, 309, 315, 400, 413, 448, 646, 689) -- reads `session.addrMap` and `ctx.addrMap`
- `kyo/internal/tasty/query/ClasspathOrchestrator.scala` (lines 555, 561) -- calls `pass1Result.addrMap`
- `kyo/internal/tasty/reader/AstUnpickler.scala` (lines 240, 275, 292, 358, 402, 421, 439) -- these are `addrMap(nodeAddr) = sym` writes into a local `addrMap` variable (the mutable map inside AstUnpickler, NOT `TastyOrigin.addrMap`)
- `kyo/Tasty.scala:552` -- comment only ("AllowUnsafe is needed for TastyOrigin.addrMap SingleAssign read")

**Test callers (package `kyo`, permitted by `private[kyo]`):**

- `kyo/AstUnpicklerTest.scala` (lines 338, 343, 348, 578, 611, 612, 616) -- calls `r.addrMap` and `o.addrMap` on `TastyOrigin` instances

No external module (kyo-ts or otherwise) calls `TastyOrigin.addrMap`. All callers are in `kyo.*` or `kyo.internal.*`.

## Concerns

**Test file:** `AstUnpicklerTest.scala` already tests `addrMap` (lines 597-616, test "T-P4-3"). The plan calls for a new negative test asserting the member is inaccessible from outside `kyo`. The existing test file is in package `kyo`, so `private[kyo]` will still allow its current assertions. The new negative test needs a separate file or compilation unit in a different package -- likely added to an existing test file that is already in a non-`kyo` package, or a new `CompileErrorTest` file. Check whether any existing test file uses a package outside `kyo` before creating a new file.

**AllowUnsafe parameter removal:** The plan says the accessor loses `(using AllowUnsafe)` and instead uses `import AllowUnsafe.embrace.danger` internally (per 05-plan.md line 544). All internal callers currently pass an `AllowUnsafe` implicitly; once the parameter is removed the callers no longer need to supply it. Verify that `TreeUnpickler.scala:62` (`val addrMap = origin.addrMap`) and similar sites compile cleanly after the parameter drop -- they are inside `given AllowUnsafe` scope already due to surrounding `import AllowUnsafe.embrace.danger`.

**No cascade visibility changes needed:** All production callers are already in `private[kyo]`-accessible scope. No other method needs a visibility adjustment as a consequence of this change.

## Self-check verdict

PASS. The change is safe and self-contained: one signature line modified in `Tasty.scala`, existing internal callers remain valid under `private[kyo]`, existing tests in package `kyo` remain valid, and only the new negative-compilation test needs placement attention (recommend adding to an existing file that is already outside the `kyo` package, or a new `addrMapVisibilityTest` file at `src/test/scala/external/AddrMapVisibilityTest.scala` with `package external`).
