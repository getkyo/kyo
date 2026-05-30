# Phase 14b Audit

## Summary

Phase 14b is structurally sound. The two new tests (T4, T5) honestly document what they prove: ADT field presence (T3) for two error cases that have no core-library call sites. The empty-classpath lookup in T4 adds real wiring value by confirming the soft-fail contract of `lookupClass`. The `CommentsUnpickler.read` WARN fix is correct: `view` is the named parameter of `read`, it is the same `Heap` instance that `readSync` advances, and `view.position.toLong` reflects where the AIOOBE fired. No code quality violations found. Two dead-code cases (`SymbolNotFound`, `ParameterizedTypeNotAllowed`) are undeniably present in the ADT but both have documented rationale and the decisions doc is clear about the fallback used.

## Findings

### 1. T3 substance - WARN

Both new tests use direct construction. T4's `err.fqn == "missing.X"` proves the field compiles and is accessible; it does not prove the error fires on any real decode path. The empty-classpath lookup in T4 is a genuine wiring assertion: it confirms `lookupClass` returns `Success(Absent)` rather than `Failure(SymbolNotFound)`, pinning the soft-fail contract. T5 is purely an ADT field coverage check with no wiring value whatsoever. Neither test exercises a production code path that raises the error. This is documented and justified in the decisions doc, but the tests should be understood as ADT-field tests (T3), not path-coverage tests. The WARN is that both error cases are effectively dead in core-library code; see Finding 2 and 3 for disposition.

### 2. SymbolNotFound wiring gap - WARN

`lookupClass` returns `Success(Absent)` for missing symbols by design. The only call site that raises `SymbolNotFound` is `tasty/examples/RuntimeReflectionExample.scala`, which is example code, not core-library code. This is BY DESIGN: core-library returns Absent; user wrappers convert to error. The error case is not dead in the sense of being unreachable from user code, but it IS dead as an internal error. No `lookupClassStrict` variant exists. The current design is coherent; the wiring gap should be noted as a known intentional boundary rather than a defect. Route to a future phase only if a strict-lookup API is desired. No deletion recommended.

### 3. ParameterizedTypeNotAllowed wiring gap - BLOCKER

`grep -rn 'ParameterizedTypeNotAllowed' kyo-tasty/shared/src/main/` returns exactly one hit: the ADT declaration in `TastyError.scala`. There is no constructor call site in any `src/main/` file. The error case is completely dead in the implementation tree. The decisions doc (D2) says it has "no call sites in core library source" and that constructing a synthetic TASTy binary is "disproportionate." That justifies the direct-construction test strategy but does not justify the error case's continued existence if nothing raises it. The correct action is either (a) wire it in the type reader where `APPLIEDtype` is encountered in an illegal position, or (b) delete the case and the test. Leaving an ADT case with zero production call sites is technical debt that misleads future readers.

### 4. CommentsUnpickler.read view.position correctness - OK

`ByteView.Heap` uses a single `var cursor: Int` field that is advanced by every `readByte()`, `readNat()`, and related operation. `readSync` operates directly on `view` (the same `Heap` instance passed to `read`); there is no derived sub-cursor. When `readSync` throws `ArrayIndexOutOfBoundsException`, the catch in `read` reads `view.position.toLong`, which is `cursor.toLong` at the point of the throw. This gives the byte offset where the read failed, consistent with the `PositionsUnpickler` pattern. The fix is correct and the offset is useful.

### 5. Test cross-platform - OK

Both new tests are in `shared/src/test/scala/kyo/TastyErrorTest.scala`. Neither test uses file I/O, JVM-specific APIs, or mmap. `Tasty.Classpath.fromPickles(Seq.empty)` is implemented entirely via `Classpath.allocate` and `transitionToReady`, which are cross-platform. No `jvmOnly` tag is needed or missing.

### 6. Code quality - OK

No em-dashes, semicolons, `asInstanceOf`, or `Option/Some/None` in the diff. The `addrMap.get(addr)` call in `CommentsUnpickler.readSync` (pre-existing) uses `Some`/`None` pattern matching via Scala stdlib `Map.get`, which is outside the kyo-tasty convention scope. No new violations introduced by Phase 14b.

### 7. Maybe.fromOption / Result patterns - OK

Test 4's `Abort.run[TastyError](...).map { result => result match { ... } }` exhaustively pattern-matches `Result.Success(Maybe.Absent)`, `Result.Success(Maybe.Present(...))`, `Result.Failure(...)`, `Result.Panic(...)`. The `Maybe.Absent` and `Maybe.Present` patterns are correct kyo-style (not `None`/`Some`). The `lookupClass` return type is `Maybe[Tasty.Symbol]` and the test matches it correctly.

## Recommendations

- BLOCKER: Route `ParameterizedTypeNotAllowed` to a future phase with a binary decision: wire the case in `TypeUnpickler` where `APPLIEDtype` appears in an illegal position, or delete the case from the ADT and remove Test 5. Add to steering.
- WARN: Note `SymbolNotFound` has no core-library raise site; document in steering as intentional (user-wrapper boundary), no follow-up wiring needed unless a `lookupClassStrict` API is planned.
- NOTE: `CommentsUnpickler.readSync` uses `addrMap.get(addr) match { case Some(...) => ... case None => ... }`. Flagged as pre-existing, not introduced in this phase; consider converting to `Maybe` in a later cleanup pass.
