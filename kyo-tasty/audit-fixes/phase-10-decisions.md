# Phase 10 Decisions

## INV-004 / M7: Log unknown TASTy type tags

### Problem
`TypeUnpickler.decodeTag` had two fallback branches for unknown TASTy type tags (one for category-5
length-prefixed nodes, one for category 1-4 nodes) that silently returned an unresolved placeholder
`Type.Named(makeUnresolvedSym("unknown-type-tag-$other", ...))` without emitting any diagnostic.
Unknown tags indicate either file corruption or a newer TASTy format version; silent fallback makes
these invisible to library users.

### Fix applied
Both fallback branches in `decodeTag` now call `Log.live.unsafe.warn(...)` before falling through to
the existing unresolved-sentinel path. The message includes the tag value and the view's current byte
offset.

### Frame propagation (original implementation)

`DecodeCtx` gained a `frame: Frame` field so the log call can use a meaningful frame without needing
`Frame.derive` (blocked within the `kyo` package).

- `readType` (public Kyo entry, has `using Frame`) passes the real user-call-site frame.
- `readTypeForTree` and `readTypeIntoSession` (synchronous decode paths invoked from
  `TreeUnpickler.decodeSync` and `AstUnpickler.runPass1`) had no user-level call-site frame. They
  used `Frame.internal` with a `// Unsafe:` justification comment.
- SHAREDtype fork-ctx inside `decodeTag` inherits `ctx.frame`, so the frame is not lost across
  cache-miss re-decode paths.

### Frame propagation refactor (verify FAIL remediation)

Phase 10 verify identified two violations: `Frame.internal` usage in `readTypeIntoSession` where a
real Frame was reachable, and `Log.live.unsafe.warn` where the safe `Log.warn` API was reachable.

Changes:

1. `readTypeIntoSession` gained `(using Frame)`. The call chain `readPass1 (using Frame) -> runPass1
   (using Frame) -> walkStats (using Frame)` now threads the real call-site Frame all the way down.
   The `Frame.internal` at this path was removed.

2. `readTypeForTree` retains `Frame.internal` with a `// flow-allow:` comment, because it is called
   from `TreeUnpickler.decodeSync`, which is the OnceCell init lambda for `Symbol.body`. The init
   lambda has type `() => Tree` and cannot accept a Frame parameter. This is the one legitimate
   flow-allow site.

3. Both fallback branches in `decodeTag` now use `Log.warn` (safe-tier API) instead of
   `Log.live.unsafe.warn`. `Sync.Unsafe.evalOrThrow` executes the `Unit < Sync` computation
   synchronously within the decode loop. `AllowUnsafe` was already imported in `decodeTag`.

4. Functions added `(using Frame)`: `TypeUnpickler.readTypeIntoSession`, `AstUnpickler.runPass1`,
   `AstUnpickler.walkStats`, `AstUnpickler.decodeOneTypeIfPresent`,
   `AstUnpickler.readDefDefReturnType`, `AstUnpickler.decodeTemplateParents`.

5. Em-dashes at the original comment lines 133 and 199 were removed in the prior pass; none remain.

### Test decisions

Two new tests added to `kyo-tasty/shared/src/test/scala/kyo/TypeUnpicklerTest.scala`:

1. **M7-1** (`"unknown category-5 TASTy type tag fires a warn-level log"`): encodes a minimal
   category-5 node with tag byte 250 and empty payload. Captures `scala.Console.withOut` to intercept
   the `Log.warn` (via ConsoleLogger) `println` output. Asserts the captured string contains
   `"unknown TASTy type tag 250"` and the returned type is `Named("unknown-type-tag-250")`.
   Pins INV-004, M7.

2. **M7-2** (`"known TASTy type tag does not emit a warn-level log"`): encodes `UNITconst` (tag 2,
   category 1). Captures stdout via the same pattern and asserts the output buffer is empty.
   Pins INV-004 negative path.

### Convention sweep

- No em-dashes.
- `AllowUnsafe`: already imported in `decodeTag`; no new embrace sites.
- `Frame.internal`: exactly 1 use remains (in `readTypeForTree`, the OnceCell init lambda path),
  with `// flow-allow:` rationale comment.
- `Log.live.unsafe.warn`: 0 uses remain.
- No `Option`/`Some` in new code (tests use `Maybe`/`Present`/`Absent`).
- No semicolons in chains.
- No `asInstanceOf`.
- No default params on internal APIs.
- No `java.util.concurrent` additions.
- No LLM-tells or em-dashes in comments/docs.

### Cross-platform compile

JVM, JS, and Native all compile cleanly (`kyo-tasty/Test/compile`,
`kyo-tastyJS/Test/fastLinkJS`, `kyo-tastyNative/Test/compile`).

JVM test run: 17 tests, all passed, including the 2 M7 tests.
