# Phase 10 Audit — M7 unknown TASTy tag logging

HEAD: 7cd758d64
Verdict: PASS — ready for Phase 11.

## 1. Sync.Unsafe.evalOrThrow bridge — PASS

`decodeTag` is a deeply recursive synchronous decoder (TypeUnpickler.scala:275). `readTypeNode` (l. 263), every internal recursion (l. 380, 386, 390, 400, 411, 471, 591, 600, 735…), `readTypeIntoSession`, and `readTypeForTree` all call `decodeTag` synchronously and return `Tasty.Type`, not `Type < Sync`. Widening `decodeTag` to `Type < Sync` would force ~30 recursive call sites into effect-row composition and turn pass-1 hot path AST walking into a deferred monad chain. The unknown-tag fallback is a sentinel arm reached only on TASTy major-version drift. The `Sync.Unsafe.evalOrThrow(Log.warn(...))` bridge at TypeUnpickler.scala:606,615 (with `given Frame = ctx.frame` and the existing `import AllowUnsafe.embrace.danger` at l. 277) is the correct localization.

## 2. Frame propagation through 5 AstUnpickler helpers — PASS

Five `(using Frame)` additions verified:
- `readPass1` (l. 90, entry; was already `(using Frame)` via call from ClasspathOrchestrator.scala:530's `(using Frame)` enclosing scope — confirmed at l. 549)
- `runPass1` (l. 112)
- `walkStats` (l. 203; recursive self-calls at l. 250, 319, 394 reuse same `using` scope)
- `decodeOneTypeIfPresent` (l. 517)
- `readDefDefReturnType` (l. 545)
- `decodeTemplateParents` (l. 587)

All callers were already inside a `(using Frame)` scope: `readPass1` from `ClasspathOrchestrator.parseFile`; the four internal helpers from `walkStats`/`runPass1` body. No external caller broken.

`TypeUnpickler.readType` and `readTypeIntoSession` both gained `(using frame: Frame)` and thread `frame` into the new `DecodeCtx.frame` field (l. 244, 247). All `new DecodeCtx(...)` sites updated (5 sites: l. 88, 156, 207, 297-309, 540-557 — counted via diff).

## 3. Single Frame.internal at readTypeForTree — PARTIAL

One Frame.internal site, rationale present at TypeUnpickler.scala:131-134. Verified `readTypeForTree` is the ONLY caller of `readTypeNode` via the TreeTypeSession path.

NOTE for Phase 11: `readTypeForTree` is reachable from TWO TreeUnpickler entry points, not one:
- `TreeUnpickler.decodeSync` (l. 60) — the OnceCell init lambda for `Symbol.body`; signature `() => Tree`, cannot accept Frame. Rationale correct.
- `TreeUnpickler.decodeAnnotationTerm` (l. 37) — called from `Tasty.Annotation.args(using Frame)` inside `Sync.defer` at Tasty.scala:200-201. A real Frame IS in scope at this call site.

The flow-allow comment overstates: "the one legitimate flow-allow site" is half-true. The path through `decodeAnnotationTerm` could thread a real Frame through `Annotation.DecodeContext` (Tasty.scala:237-243) → new field → `readTypeForTree(view, session, frame)`. Not a Phase 10 defect (the OnceCell path genuinely needs flow-allow, and an unknown-tag inside an annotation arg still ends up running through unsafe sync execution), but Phase 11 prep should consider tightening the rationale or plumbing Frame through the annotation path.

## 4. Em-dashes in TypeUnpicklerTest.scala — PASS

`grep -n` finds two em-dashes at l. 281, 289 (annotation-term comments). `git diff 7cd758d64^ 7cd758d64 -- ...TypeUnpicklerTest.scala | grep '^+' | grep -E '—|–'` returns empty. Both em-dashes are pre-existing, not introduced by Phase 10.

## Overall

Ready for Phase 11. The bridge design is sound, Frame plumbing is complete and correct, em-dashes are pre-existing, and the single Frame.internal site is the right one. Phase 11 prep should weigh whether to tighten the readTypeForTree flow-allow rationale (or thread Frame through DecodeContext) given the annotation-arg path also reaches it.
