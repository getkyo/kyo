# Phase 4 Prep — Defer toMap in AstUnpickler

## Verbatim type definitions

### Pass1Result (AstUnpickler.scala lines 58–66)

```scala
final case class Pass1Result(
    symbols: Chunk[Reflect.Symbol],
    addrMap: Map[Int, Reflect.Symbol],
    placeholders: Chunk[UnresolvedRef],
    rootSymbol: Reflect.Symbol,
    parentsBySymbol: Map[Reflect.Symbol, Chunk[Reflect.Type]],
    childrenByOwner: Map[Reflect.Symbol, Chunk[Reflect.Symbol]],
    typeBySymbol: Map[Reflect.Symbol, Reflect.Type]
)
```

`Pass1Result` is declared inside `object AstUnpickler` (`package kyo.internal.reflect.tasty`). No explicit visibility modifier on the case class itself; it is reachable from the `kyo` test package via direct import.

### FileResult (ClasspathOrchestrator.scala lines 49–59)

```scala
final private case class FileResult(
    fqns: Chunk[(String, Reflect.Symbol)],
    arena: TypeArena,
    errors: Seq[ReflectError],
    placeholders: Chunk[UnresolvedRef],
    parentsBySymbol: Map[Reflect.Symbol, Chunk[Reflect.Type]],
    childrenByOwner: Map[Reflect.Symbol, Chunk[Reflect.Symbol]],
    typeBySymbol: Map[Reflect.Symbol, Reflect.Type],
    commentsBySymbol: Map[Reflect.Symbol, String],
    positionsBySymbol: Map[Reflect.Symbol, Reflect.Position]
)
```

`FileResult` is `final private` inside `object ClasspathOrchestrator`. Phase 4 modifies three of its nine fields.

### TastyOrigin._addrMap (Reflect.scala line 776)

```scala
private[kyo] val _addrMap: kyo.internal.reflect.symbol.SingleAssign[Map[Int, Reflect.Symbol]] =
    new kyo.internal.reflect.symbol.SingleAssign
```

Public accessor (Reflect.scala line 779):

```scala
def addrMap(using AllowUnsafe): Map[Int, Reflect.Symbol] =
    if _addrMap.isSet then _addrMap.get()
    else Map.empty
```

Phase 4 changes the `SingleAssign` type parameter from `Map[Int, Reflect.Symbol]` to `mutable.HashMap[Int, Reflect.Symbol]`. Phase 5 supersedes this single field with `scala.collection.immutable.IntMap[Reflect.Symbol]`. The other three `Pass1Result` / `FileResult` map fields stay `mutable.HashMap` permanently after Phase 4.

### The four .toMap sites (AstUnpickler.scala)

- Line 153: `val finalAddrMap = addrMap.toMap`
- Line 176: `parentsBySymbol = parentsBySymbol.view.mapValues(identity).toMap,`
- Line 177: `childrenByOwner = childrenByOwner.view.mapValues(buf => Chunk.from(buf.toSeq)).toMap,`
- Line 178: `typeBySymbol = typeBySymbol.view.mapValues(identity).toMap`

Lines 176–178 are inside the `Pass1Result(...)` constructor call that closes `runPass1`. Line 153 materializes `finalAddrMap` before the loop at lines 154–159 that calls `o._addrMap.set(finalAddrMap)` on every `TastyOrigin` in the file.

### Downstream consumers

**`Pass1Result.addrMap`** flows:

1. `AstUnpickler.runPass1` (line 173): stored in `Pass1Result.addrMap`.
2. `ClasspathOrchestrator.decodeTastyBytes` (line 286): `CommentsUnpickler.read(commentsView, pass1Result.addrMap)` — parameter currently `Map[Int, Reflect.Symbol]`.
3. `ClasspathOrchestrator.decodeTastyBytes` (line 292): `PositionsUnpickler.read(posView, pass1Result.addrMap, attrs.sourceFile)` — parameter currently `Map[Int, Reflect.Symbol]`.
4. `AstUnpicklerTest.scala` (lines 334, 339, 344): `.find { case (_, sym) => ... }`, direct key lookup `r.addrMap(aAddr.get)`.

**`Pass1Result.addrMap` also sets `TastyOrigin._addrMap`** at AstUnpickler.scala line 158: `o._addrMap.set(finalAddrMap)`. After Phase 4 this sets a `mutable.HashMap[Int, Reflect.Symbol]`.

**`TastyOrigin.addrMap`** (post-set) is consumed by:

5. `TreeUnpickler.scala` line 37: `val addrMap = origin.addrMap` (under `AllowUnsafe`), then passed to `TypeUnpickler.TreeTypeSession` at line 54 as a `Map[Int, Reflect.Symbol]` parameter.

**`FileResult.parentsBySymbol`, `FileResult.childrenByOwner`, `FileResult.typeBySymbol`** flow:

6. `ClasspathOrchestrator.finalizeMerge` (lines 434, 439, 458): `for (sym, parents) <- fr.parentsBySymbol`, `for (sym, children) <- fr.childrenByOwner`, `for (sym, t) <- fr.typeBySymbol`. For-comprehension destructuring desugars to `.iterator` on `scala.collection.Map`; works identically on `mutable.HashMap`.

`FileResult.commentsBySymbol` and `FileResult.positionsBySymbol` are not in scope for Phase 4 — they stay `Map[Reflect.Symbol, ...]` (returned as immutable Maps from CommentsUnpickler and PositionsUnpickler).

### PositionsUnpickler addrMap parameter

```scala
def read(
    view: ByteView,
    addrMap: Map[Int, Reflect.Symbol],
    sourceFile: Maybe[String]
)(using Frame): Map[Reflect.Symbol, Reflect.Position] < (Sync & Abort[ReflectError])
```

Usage in `readSync` at line 107: `addrMap.get(curIndex)` where `curIndex: Int`. Currently `Map[Int, Reflect.Symbol]` (immutable). Phase 4 passes `mutable.HashMap[Int, Reflect.Symbol]` from `pass1Result.addrMap`; the parameter must widen to `scala.collection.Map[Int, Reflect.Symbol]`. Phase 5 changes this to `scala.collection.immutable.IntMap[Reflect.Symbol]` (a totally separate type — no supertype relationship to `mutable.HashMap`), so `scala.collection.Map` is the correct intermediate for Phase 4.

### CommentsUnpickler addrMap parameter

```scala
def read(
    view: ByteView,
    addrMap: Map[Int, Reflect.Symbol]
)(using Frame): Map[Reflect.Symbol, String] < (Sync & Abort[ReflectError])
```

Usage in `readSync` at line 60: `addrMap.get(addr)`. Same situation as PositionsUnpickler. Phase 4 widens to `scala.collection.Map[Int, Reflect.Symbol]`. Phase 5 supersedes with `IntMap`.

---

## What Phase 4 changes precisely

### AstUnpickler.scala

- `Pass1Result.addrMap: Map[Int, Reflect.Symbol]` → `addrMap: mutable.HashMap[Int, Reflect.Symbol]`
- `Pass1Result.parentsBySymbol: Map[Reflect.Symbol, Chunk[Reflect.Type]]` → `parentsBySymbol: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Type]]`
- `Pass1Result.childrenByOwner: Map[Reflect.Symbol, Chunk[Reflect.Symbol]]` → `childrenByOwner: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Symbol]]`
- `Pass1Result.typeBySymbol: Map[Reflect.Symbol, Reflect.Type]` → `typeBySymbol: mutable.HashMap[Reflect.Symbol, Reflect.Type]`
- Line 153: remove `val finalAddrMap = addrMap.toMap`. Use `addrMap` directly (already `mutable.HashMap`). Line 158 becomes `o._addrMap.set(addrMap)`.
- Line 176: remove `.view.mapValues(identity).toMap`. Pass `parentsBySymbol` directly (already `mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Type]]`).
- Line 177: remove `.toMap`. The `mutable.ArrayBuffer` values still need conversion to `Chunk`. Build a new `mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Symbol]]` before the `Pass1Result` constructor call, e.g.:
  ```scala
  val childrenChunks = new mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Symbol]]()
  for (owner, buf) <- childrenByOwner do
      childrenChunks(owner) = Chunk.from(buf.toSeq)
  ```
  The existing local `childrenByOwner: mutable.HashMap[Reflect.Symbol, mutable.ArrayBuffer[Reflect.Symbol]]` is unchanged; `childrenChunks` is the new local passed to `Pass1Result`.
- Line 178: remove `.view.mapValues(identity).toMap`. Pass `typeBySymbol` directly (already `mutable.HashMap[Reflect.Symbol, Reflect.Type]`).

### ClasspathOrchestrator.scala

- `FileResult.parentsBySymbol: Map[Reflect.Symbol, Chunk[Reflect.Type]]` → `mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Type]]`
- `FileResult.childrenByOwner: Map[Reflect.Symbol, Chunk[Reflect.Symbol]]` → `mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Symbol]]`
- `FileResult.typeBySymbol: Map[Reflect.Symbol, Reflect.Type]` → `mutable.HashMap[Reflect.Symbol, Reflect.Type]`
- `FileResult` does NOT gain an `addrMap` field. The addrMap path is `AstUnpickler` → `TastyOrigin` only.
- The two error-path `FileResult(...)` calls in `readAndDecodeTastyFile` (lines 228–237 and 244–253) pass `Map.empty` for the three affected fields. After Phase 4 these must pass `mutable.HashMap.empty[Reflect.Symbol, Chunk[Reflect.Type]]`, `mutable.HashMap.empty[Reflect.Symbol, Chunk[Reflect.Symbol]]`, and `mutable.HashMap.empty[Reflect.Symbol, Reflect.Type]` respectively.
- `finalizeMerge`: for-comprehension reads of `fr.parentsBySymbol`, `fr.childrenByOwner`, `fr.typeBySymbol` require no change — for-loop destructuring works on `scala.collection.Map` (supertype of both `immutable.Map` and `mutable.HashMap`).

### Reflect.scala (TastyOrigin._addrMap)

- `SingleAssign[Map[Int, Reflect.Symbol]]` → `SingleAssign[mutable.HashMap[Int, Reflect.Symbol]]`
- `addrMap` accessor return type: widen from `Map[Int, Reflect.Symbol]` to `scala.collection.Map[Int, Reflect.Symbol]` so that callers (TreeUnpickler, TreeTypeSession) continue to compile after their own parameter types widen to `scala.collection.Map`.
- The `else Map.empty` fallback in the accessor becomes `else mutable.HashMap.empty` (or any `scala.collection.Map[Int, Reflect.Symbol]` value; `mutable.HashMap.empty` is the most consistent choice).

### PositionsUnpickler.scala

- `read` parameter `addrMap: Map[Int, Reflect.Symbol]` → `addrMap: scala.collection.Map[Int, Reflect.Symbol]`
- `readSync` parameter widens identically.
- Phase 5 then changes to `IntMap[Reflect.Symbol]`.

### CommentsUnpickler.scala

- `read` parameter `addrMap: Map[Int, Reflect.Symbol]` → `addrMap: scala.collection.Map[Int, Reflect.Symbol]`
- `readSync` parameter widens identically.
- Phase 5 then changes to `IntMap[Reflect.Symbol]`.

### TypeUnpickler.scala (not in plan text but required to compile)

- `TreeTypeSession.addrMap: Map[Int, Reflect.Symbol]` (line 98) → `scala.collection.Map[Int, Reflect.Symbol]`. Receives the value from `TastyOrigin.addrMap` in TreeUnpickler.scala line 54.
- `DecodeCtx.addrMap: Map[Int, Reflect.Symbol]` (line 208) → `scala.collection.Map[Int, Reflect.Symbol]`. All `DecodeCtx` constructor sites that pass the addrMap argument (TypeUnpickler.scala lines 73, 125, 141) must be verified for compatibility. All `.get(astRef)` calls on `ctx.addrMap` inside `readTypeNode` work identically on `scala.collection.Map`.

---

## Downstream consumer impact

| Consumer | File:line | Access pattern | Works with mutable.HashMap? |
|---|---|---|---|
| `CommentsUnpickler.read` | CommentsUnpickler.scala:34 | `addrMap: Map[Int, Reflect.Symbol]` parameter | No — widen to `scala.collection.Map` |
| `CommentsUnpickler.readSync` | CommentsUnpickler.scala:47 | `addrMap.get(addr)` | Yes once parameter is widened |
| `PositionsUnpickler.read` | PositionsUnpickler.scala:43 | `addrMap: Map[Int, Reflect.Symbol]` parameter | No — widen to `scala.collection.Map` |
| `PositionsUnpickler.readSync` | PositionsUnpickler.scala:58 | `addrMap.get(curIndex)` | Yes once parameter is widened |
| `TreeUnpickler` | TreeUnpickler.scala:37 | `origin.addrMap` then passed to `TreeTypeSession` | Needs `TastyOrigin.addrMap` accessor to return `scala.collection.Map` |
| `TypeUnpickler.TreeTypeSession.addrMap` | TypeUnpickler.scala:98 | field `val addrMap: Map[Int, ...]` | No — widen to `scala.collection.Map` |
| `TypeUnpickler.DecodeCtx.addrMap` | TypeUnpickler.scala:208 | field `val addrMap: Map[Int, ...]`; `.get(astRef)` | Widen to `scala.collection.Map` |
| `finalizeMerge` parentsBySymbol | ClasspathOrchestrator.scala:434 | `for (sym, parents) <- fr.parentsBySymbol` | Yes — for-comprehension on `scala.collection.Map` supertype |
| `finalizeMerge` childrenByOwner | ClasspathOrchestrator.scala:439 | `for (sym, children) <- fr.childrenByOwner` | Yes |
| `finalizeMerge` typeBySymbol | ClasspathOrchestrator.scala:458 | `for (sym, t) <- fr.typeBySymbol` | Yes |
| `AstUnpicklerTest` | AstUnpicklerTest.scala:334 | `r.addrMap.find { case (_, sym) => ... }` | Yes — `.find` is on `scala.collection.Map` |
| `AstUnpicklerTest` | AstUnpicklerTest.scala:344 | `r.addrMap(aAddr.get)` | Yes — `apply` is on `scala.collection.Map` |

Key type-system point: `mutable.HashMap[K,V]` does NOT extend `scala.collection.immutable.Map[K,V]`. Both extend `scala.collection.Map[K,V]`. In Kyo source files the unqualified `Map` refers to `scala.collection.immutable.Map` via the Scala default prelude. Any parameter typed as plain `Map[K,V]` must be changed to `scala.collection.Map[K,V]` (fully qualified) to accept a `mutable.HashMap[K,V]` argument.

---

## Edge cases and gotchas

### Mutable state and thread safety

`mutable.HashMap` is not thread-safe. Phase 4 stores `mutable.HashMap` in three contexts:

**Pass1Result fields** (decoder fiber scope): `Pass1Result` is produced inside a single decoder fiber. After `runPass1` returns, the result is used to build `FileResult`, which is put to the result channel and consumed by the single-threaded merger fiber. The channel put/take provides a happens-before edge; no concurrent access occurs.

**FileResult fields** (pipeline scope): `FileResult` is written by one decoder fiber and consumed by the merger fiber. After the merger calls `finalizeMerge`, all reads are single-threaded (`Sync.defer`, Phase C). No concurrent access.

**TastyOrigin._addrMap** (classpath lifetime): set once in `AstUnpickler.runPass1` (line 158) via `SingleAssign.set`. After Phase C completes, `addrMap` is read by `TreeUnpickler` during lazy `Symbol.body` decodes. Lazy body decodes are only possible on a `Ready` classpath; the `OpenState` guard prevents body access while Phase C is running. All mutation is complete before the classpath is exposed to callers.

These `mutable.HashMap` instances are single-threaded by construction. The kyo rule "no Var for shared mutable state" (feedback_atomic_not_var) applies to concurrent shared state; it is not violated here.

### childrenByOwner local variable in runPass1

Inside `runPass1`, the local `childrenByOwner` (line 163) has type `mutable.HashMap[Reflect.Symbol, mutable.ArrayBuffer[Reflect.Symbol]]`. The `Pass1Result.childrenByOwner` field (after Phase 4) has type `mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Symbol]]`. The value conversion from `mutable.ArrayBuffer` to `Chunk` must happen explicitly via a new local (`childrenChunks`). Do not rename the existing local; it is also used earlier in `runPass1`.

### SingleAssign is invariant in T

`SingleAssign[T]` is invariant. Changing the field from `SingleAssign[Map[Int, Reflect.Symbol]]` to `SingleAssign[mutable.HashMap[Int, Reflect.Symbol]]` requires updating the `_addrMap` declaration and any site that calls `_addrMap.set(...)` or `_addrMap.get()`. The only `set` site is AstUnpickler.scala line 158; the only `get` site is the `addrMap` accessor.

### readTypeIntoSession uses liveAddrMap (not the finalAddrMap)

`TypeUnpickler.DecodeSession.liveAddrMap` (TypeUnpickler.scala line 193) is already `mutable.HashMap[Int, Reflect.Symbol]` — it holds the reference to the same mutable map being built by `walkStats`. The `readTypeIntoSession` call at line 172 takes a snapshot via `session.liveAddrMap.toMap` (line 173). After Phase 4, `readTypeIntoSession` must handle `liveAddrMap.toMap` producing an `immutable.HashMap` for the `DecodeCtx.addrMap` field. This is a separate `.toMap` call inside `DecodeSession` (not the four `.toMap` calls Phase 4 removes). That `.toMap` is intentional (snapshot semantics during type decode) and is NOT removed in Phase 4. Verify that the implementation agent does not accidentally remove it.

---

## Test-data suggestions

The plan calls for 3 new tests in `AstUnpicklerTest.scala`. The file currently has 20 tests (Tests 7–22 in numbering from the file comments).

**T1: Pass1Result fields are mutable.HashMap instances (compile-time check)**

After `runPass1(bytes)`, assign the four map fields to explicitly typed `mutable.HashMap` variables:

```scala
val addrMapH: mutable.HashMap[Int, Reflect.Symbol]              = r.addrMap
val parentsByH: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Type]] = r.parentsBySymbol
val childrenByH: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Symbol]] = r.childrenByOwner
val typeByH: mutable.HashMap[Reflect.Symbol, Reflect.Type]      = r.typeBySymbol
```

If Phase 4 types are correct, these assignments compile. If not, the compiler rejects them — which is the failure signal. The test body asserts `addrMapH.nonEmpty` and spot-checks one known key (e.g., the `PlainClass` symbol from `GenericBox.tasty`). No `isInstanceOf` needed, consistent with feedback_no_casts.

**T2: Merger accepts FileResult with mutable.HashMap fields**

Use the existing `openFixtureClasspath` pattern from `QueryApiTest` (exercises the full orchestrator pipeline). After Phase 4, the pipeline produces `FileResult` values with `mutable.HashMap` fields and the merger processes them. The existing 38 `QueryApiTest` tests verify behavioral equivalence end-to-end. One dedicated T2 test: open the fixture classpath, look up `"kyo.fixtures.PlainClass"` in the FQN index, assert the result is `Present` with `kind == Class`. This confirms the merger read the `mutable.HashMap` fields correctly and produced the expected FQN index entry.

**T3: TastyOrigin.addrMap behavioral equivalence**

After opening the fixture classpath (via `openFixtureClasspath` or `QueryApiTest` helper), find the `PlainClass` symbol, access its `origin` as a `TastyOrigin`, call `origin.addrMap` (requires `AllowUnsafe`), and assert the returned map is non-empty. Optionally: assert that some symbol whose address is in the map can be retrieved by key and its name matches expectations. This is a direct regression check for the `_addrMap.set` path.

T1 and T3 belong in `AstUnpicklerTest.scala` (which already has direct access to `Pass1Result` via `runPass1`). T2 belongs in `QueryApiTest.scala`.

---

## Anti-flakiness deltas

All three tests are deterministic:
- T1 and T3 operate on embedded fixture TASTy bytes (`kyo.fixtures.Embedded.plainClassTasty` or similar). No I/O, no concurrency, no timing dependency.
- T2 uses `openFixtureClasspath` under the controlled Kyo test harness. The fixture classpath is read-only and stable.

No anti-flakiness measures are needed.

---

## Concerns

1. **TypeUnpickler.scala sites not in the plan text.** The plan for Phase 4 (execution-plan-perf.md lines 183–213) lists `AstUnpickler.scala`, `ClasspathOrchestrator.scala`, and `TastyOrigin.scala` as files to modify. It does not list `TypeUnpickler.scala`, `PositionsUnpickler.scala`, or `CommentsUnpickler.scala`. However, those files have `Map[Int, Reflect.Symbol]` parameters that must change to `scala.collection.Map` for the code to compile. The implementation agent must update them. The supervisor should verify that these unlisted files are modified and the change is minimal (parameter type widening only, no behavioral change).

2. **`readTypeIntoSession` snapshot `.toMap` must NOT be removed.** `TypeUnpickler.readTypeIntoSession` at line 172 calls `session.liveAddrMap.toMap` to take a snapshot for `DecodeCtx`. This is a different `.toMap` from the four Phase 4 removes. It is intentional (each type decode node sees a consistent snapshot of the addrMap as-of that point in the AST walk). Do not remove it.

3. **Phase 5 supersedes `_addrMap` but not the other three fields.** After Phase 5, `TastyOrigin._addrMap` becomes `IntMap[Reflect.Symbol]` (immutable). The Phase 4 change to `mutable.HashMap` for `_addrMap` is transient for this one field. `parentsBySymbol`, `childrenByOwner`, and `typeBySymbol` in both `Pass1Result` and `FileResult` remain `mutable.HashMap` permanently — Phase 5 does not touch them.

4. **Error-path `FileResult` constructors must be updated.** Two error-path `FileResult(...)` calls in `readAndDecodeTastyFile` (lines 228–237 and 244–253) use `Map.empty` for the three affected fields. Leaving them as `Map.empty` after Phase 4 causes a compile error (type mismatch: `immutable.HashMap` vs `mutable.HashMap`). Both must be updated to `mutable.HashMap.empty`.

5. **Test count.** `AstUnpicklerTest` currently has 20 tests. Phase 4 adds T1 and T3 (T2 goes to `QueryApiTest`), bringing the total to 22 in `AstUnpicklerTest` and adding 1 to `QueryApiTest` (which currently has 38 tests).
