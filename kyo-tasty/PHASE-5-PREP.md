# Phase 5 Prep — PositionsUnpickler IntMap boxing elimination

This document is the authoritative prep reference for the implementing agent executing Phase 5 of
`execution-plan-perf.md`. Read every section before writing a single line of code.

---

## Verbatim type definitions and call sites

### PositionsUnpickler.readSync (line 58)

```scala
private def readSync(
    view: ByteView,
    addrMap: scala.collection.Map[Int, Reflect.Symbol],
    sourceFile: Maybe[String]
): Map[Reflect.Symbol, Reflect.Position] =
```

The public `read` wrapper at line 43 carries the same `addrMap` parameter type:

```scala
def read(
    view: ByteView,
    addrMap: scala.collection.Map[Int, Reflect.Symbol],
    sourceFile: Maybe[String]
)(using Frame): Map[Reflect.Symbol, Reflect.Position] < (Sync & Abort[ReflectError]) =
```

### PositionsUnpickler.scala line 107 — the boxing site

```scala
addrMap.get(curIndex) match
    case Some(sym) =>
        val (line, col) = offsetToLineCol(curStart, lineStarts)
        builder += (sym -> Reflect.Position(sourceFile, line, col))
    case None => () // sub-expression node or unmapped address; skip
end match
```

`curIndex` is declared at line 87 as `var curIndex = 0` (type `Int`). When `addrMap` is
`scala.collection.Map[Int, Reflect.Symbol]` backed by `immutable.HashMap[Int, Reflect.Symbol]`,
the `get(curIndex: Int)` call autoboxes the `Int` key to `java.lang.Integer` on every
iteration of the Assoc stream loop. This is the boxing site identified in PERF-VERIFICATION.md §6.

### CommentsUnpickler.read addrMap parameter

```scala
def read(
    view: ByteView,
    addrMap: scala.collection.Map[Int, Reflect.Symbol]
)(using Frame): Map[Reflect.Symbol, String] < (Sync & Abort[ReflectError]) =
```

Private `readSync` at line 47 mirrors the same parameter type:

```scala
private def readSync(view: ByteView, addrMap: scala.collection.Map[Int, Reflect.Symbol]): Map[Reflect.Symbol, String] =
```

Hot access site is line 60: `addrMap.get(addr)` where `addr: Int` is read from the stream;
same autoboxing pattern as PositionsUnpickler.

### TypeUnpickler.TreeTypeSession.addrMap and DecodeCtx.addrMap

`TreeTypeSession` (lines 96-108):

```scala
final private[tasty] class TreeTypeSession(
    val names: Array[Reflect.Name],
    val addrMap: scala.collection.Map[Int, Reflect.Symbol],
    val arena: TypeArena,
    val home: ClasspathRef,
    val sectionBytes: Array[Byte],
    val sectionOffset: Int
):
    val addrCache: mutable.HashMap[Int, Reflect.Type]              = new mutable.HashMap()
    val inProgressRec: mutable.HashMap[Int, Reflect.Type.Rec]      = new mutable.HashMap()
    val binderAddrMap: mutable.HashMap[Int, Chunk[Reflect.Symbol]] = new mutable.HashMap()
    val placeholders: mutable.ArrayBuffer[UnresolvedRef]           = new mutable.ArrayBuffer()
end TreeTypeSession
```

`DecodeCtx` (private, lines 206-217):

```scala
final private class DecodeCtx(
    val names: Array[Reflect.Name],
    val addrMap: scala.collection.Map[Int, Reflect.Symbol],
    val arena: TypeArena,
    val home: ClasspathRef,
    val addrCache: mutable.HashMap[Int, Reflect.Type],
    val inProgressRec: mutable.HashMap[Int, Reflect.Type.Rec],
    val binderAddrMap: mutable.HashMap[Int, Chunk[Reflect.Symbol]],
    val placeholders: mutable.ArrayBuffer[UnresolvedRef],
    val sectionBytes: Array[Byte],
    val sectionOffset: Int
)
```

### TypeUnpickler.scala:172 — intentional .toMap snapshot

```scala
private[kyo] def readTypeIntoSession(view: ByteView, session: DecodeSession): Reflect.Type =
    // Use the live addrMap snapshot at call time so locally-defined symbols found so far are visible.
    val ctx = DecodeCtx(
        session.names,
        session.liveAddrMap.toMap,
        session.arena,
        session.home,
        session.addrCache,
        session.inProgressRec,
        session.binderAddrMap,
        session.placeholders,
        null,
        0
    )
    readTypeNode(view, ctx)
end readTypeIntoSession
```

The `.toMap` at line 173 (`session.liveAddrMap.toMap`) is intentional: it snapshots the
mutable live addrMap at the moment of each type-node decode during Pass 1, so type decode sees
only symbols already walked (not future symbols still being walked). This snapshot becomes the
`addrMap` field on the ephemeral `DecodeCtx` used for that one `readTypeNode` call. The
`DecodeCtx` is discarded when `readTypeNode` returns. Do NOT remove or change this `.toMap`.
Phase 5 replaces it with `IntMap.from(session.liveAddrMap.iterator)` for the boxing
elimination, but the snapshot pattern itself is preserved.

### Pass1Result.addrMap (post-Phase-4)

```scala
final case class Pass1Result(
    symbols: Chunk[Reflect.Symbol],
    addrMap: mutable.HashMap[Int, Reflect.Symbol],
    placeholders: Chunk[UnresolvedRef],
    rootSymbol: Reflect.Symbol,
    parentsBySymbol: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Type]],
    childrenByOwner: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Symbol]],
    typeBySymbol: mutable.HashMap[Reflect.Symbol, Reflect.Type]
)
```

The `addrMap` field is `mutable.HashMap[Int, Reflect.Symbol]` after Phase 4. Phase 5
supersedes this to `IntMap[Reflect.Symbol]`.

### TastyOrigin._addrMap (post-Phase-4)

```scala
private[kyo] val _addrMap: kyo.internal.reflect.symbol.SingleAssign[scala.collection.mutable.HashMap[Int, Reflect.Symbol]] =
    new kyo.internal.reflect.symbol.SingleAssign
```

The accessor:

```scala
def addrMap(using AllowUnsafe): scala.collection.Map[Int, Reflect.Symbol] =
    if _addrMap.isSet then _addrMap.get()
    else scala.collection.mutable.HashMap.empty
```

Phase 5 supersedes the `_addrMap` slot type to `SingleAssign[IntMap[Reflect.Symbol]]`.

### AstUnpickler line 153 — addrMap snapshot into TastyOrigin

```scala
// Populate the addrMap SingleAssign on every TastyOrigin in this file now that the full addrMap is known.
// This allows TreeUnpickler (called lazily from Symbol.body) to resolve IDENT/SELECT addr references.
// Unsafe: SingleAssign.set is an unsafe-tier helper; AllowUnsafe is embraced here.
import AllowUnsafe.embrace.danger
for sym <- allSymbols do
    sym.origin match
        case o: Reflect.Symbol.TastyOrigin =>
            if !o._addrMap.isSet then
                o._addrMap.set(addrMap)
        case _ => ()
end for
```

`addrMap` here is the local `val addrMap = new mutable.HashMap[Int, Reflect.Symbol]()` built
by `walkStats` (declared at line 118). After Phase 5 this loop sets `intMap` (the converted
`IntMap`) instead of the raw `mutable.HashMap`.

---

## What Phase 5 changes precisely

Type changes (all parameters and fields previously typed as
`mutable.HashMap[Int, Reflect.Symbol]` or `scala.collection.Map[Int, Reflect.Symbol]`):

- **PositionsUnpickler.read / readSync**: `addrMap` parameter type changes from
  `scala.collection.Map[Int, Reflect.Symbol]` to `scala.collection.immutable.IntMap[Reflect.Symbol]`.

- **CommentsUnpickler.read / readSync**: same change.

- **TypeUnpickler.TreeTypeSession.addrMap**: field type changes from
  `scala.collection.Map[Int, Reflect.Symbol]` to `IntMap[Reflect.Symbol]`. The field is
  read-only in this class (see Concerns section for confirmation).

- **TypeUnpickler.DecodeCtx.addrMap**: field type changes from
  `scala.collection.Map[Int, Reflect.Symbol]` to `IntMap[Reflect.Symbol]`. Read-only.

- **TypeUnpickler.readTypeIntoSession line 173**: `session.liveAddrMap.toMap` becomes
  `IntMap.from(session.liveAddrMap.iterator)`. This replaces the `immutable.HashMap` snapshot
  with an `IntMap` snapshot, extending the boxing elimination to the pass-1 type-decode path
  as well.

- **Pass1Result.addrMap**: `mutable.HashMap[Int, Reflect.Symbol]` (Phase 4) superseded by
  `IntMap[Reflect.Symbol]`.

- **TastyOrigin._addrMap**: `SingleAssign[mutable.HashMap[Int, Reflect.Symbol]]` (Phase 4)
  superseded by `SingleAssign[IntMap[Reflect.Symbol]]`. The accessor return type
  `scala.collection.Map[Int, Reflect.Symbol]` may be narrowed to `IntMap[Reflect.Symbol]`
  (see Concerns §2).

- **AstUnpickler runPass1 — conversion site**: after `walkStats` returns, the local
  `val addrMap = new mutable.HashMap[Int, Reflect.Symbol]()` (mutated in-place during the
  walk) is converted to `IntMap` once:

  ```scala
  val intMap = scala.collection.immutable.IntMap.from(addrMap.iterator)
  ```

  After this line, `intMap` (not `addrMap`) is used in the `_addrMap.set(...)` loop and in
  `Pass1Result(addrMap = intMap, ...)`. The local `addrMap: mutable.HashMap` is still used
  for the `walkStats` call and the `DecodeSession` construction (both of which happen before
  the conversion).

---

## Why IntMap not mutable.HashMap[Int, V]

`mutable.HashMap[Int, V]` and `immutable.HashMap[Int, V]` both autobox `Int` keys when calling
`.get(Int)`. Scala's generic hashmap implementations use `AnyRef`-typed storage; every `get`
call boxes the primitive `Int` to `java.lang.Integer`.

`scala.collection.immutable.IntMap[V]` is a specialized primitive-keyed HAMT. Its `.get(Int)`
follows bit-partitioned trie branches using raw `Int` bits with no `java.lang.Integer`
allocation on the call path.

The Phase 5 win: PERF-VERIFICATION.md §6 identifies `addrMap.get(curIndex)` at line 107 as
the boxing source. The profile shows `java.lang.Integer` at 0.73% of allocation samples
(partial profile: 2.0%) and `scala.runtime.BoxesRunTime.boxToInteger` at 0.32% of CPU samples.
Eliminating autoboxing removes a chunk of GC pressure across all 5,949 files.

---

## Build-time considerations

`scala.collection.immutable.IntMap` is part of `scala-library`. It is a Scala 2 carryover
fully accessible in Scala 3 at `scala.collection.immutable.IntMap`. No new dependency is
needed.

Import alias used consistently throughout the modified files:

```scala
import scala.collection.immutable.IntMap
```

---

## Downstream impact

After Phase 5:

- `PositionsUnpickler.readSync` at line 107: `addrMap.get(curIndex)` operates on `IntMap`;
  no autoboxing.

- `CommentsUnpickler.readSync` line 60: `addrMap.get(addr)` operates on `IntMap`; no
  autoboxing.

- `TreeTypeSession.addrMap` and `DecodeCtx.addrMap`: typed as `IntMap`. All constructors of
  `DecodeCtx` that pass `addrMap` must pass `IntMap`. The three `DecodeCtx(...)` construction
  sites in `readTypeForTree` (lines 125-136 and 141-152) receive `session.addrMap` which is
  already typed as `IntMap` after Phase 5; no change needed at those sites beyond the type
  flowing through.

- `TreeUnpickler.decodeSync` line 37: `val addrMap = origin.addrMap`. The accessor return type
  is `scala.collection.Map[Int, Reflect.Symbol]`. If the accessor is narrowed to
  `IntMap[Reflect.Symbol]` (see Concerns §2), `TreeUnpickler` at line 54
  (`new TypeUnpickler.TreeTypeSession(names, addrMap, ...)`) compiles cleanly. If the accessor
  stays as the wide `scala.collection.Map` return type, `addrMap` would need an explicit
  `.asInstanceOf[IntMap[Reflect.Symbol]]` — which is a cast and prohibited by CLAUDE.md. The
  correct resolution is to narrow the accessor return type (see Concerns §2).

- The `mergeResults` / `mergeOneInto` flow does not change. `Pass1Result.addrMap` is consumed
  only by the TastyOrigin stash loop inside `runPass1`, before `Pass1Result` is returned to the
  merger fiber. `FileResult` never carried `addrMap`.

---

## Edge cases and gotchas

- `IntMap.from(mutable.HashMap[Int, Reflect.Symbol].iterator)` is O(n) and produces a
  balanced HAMT. Standard conversion; no surprise overhead.

- `IntMap` is immutable: once produced after `walkStats`, no further mutation. Safe to share
  across fiber boundaries (though in practice `TastyOrigin._addrMap` is write-once and read
  only after `SingleAssign.set` under `AllowUnsafe`).

- `DecodeSession.liveAddrMap` remains `mutable.HashMap` throughout Phase 5. It is mutated
  in-place by `walkStats` as new symbols are discovered. The Phase 5 change does not touch
  `DecodeSession` itself; the conversion to `IntMap` happens once at the end of `runPass1`,
  after `walkStats` returns.

- `TypeUnpickler.readTypeIntoSession` line 173: the snapshot changes from
  `session.liveAddrMap.toMap` (produces `immutable.HashMap`) to
  `IntMap.from(session.liveAddrMap.iterator)` (produces `IntMap`). Each call during
  `walkStats` produces a fresh `IntMap` snapshot. The same allocation cost existed before;
  `IntMap.from` is O(n) like `toMap`. The GC savings from the boxing elimination on lookups
  outweigh the cost of the conversion.

- `TreeTypeSession.addrMap`: the field is read-only in the class body. It is passed into
  ephemeral `DecodeCtx` instances by value. No code path reassigns `TreeTypeSession.addrMap`
  after construction. Safe to change to `IntMap`.

---

## Test-data suggestions

Plan calls for 1 new test in `PositionsUnpicklerTest.scala`:

**T-P5-1**: `PositionsUnpickler.readSync` with an `IntMap` addrMap of 10,000 entries returns
correct position mappings for all 10,000 entries. (Functional correctness at scale; allocation
assertion deferred to Phase 8 re-profiling.)

Build the test addrMap:

```scala
val addrMap: IntMap[Reflect.Symbol] =
    IntMap.from((0 until 10000).map(i => i -> mockSymbol(i)))
```

Construct a synthetic Positions section payload whose Assoc stream has 10,000 entries, each
with `addrDelta = 1` and `hasStart = true` so `curIndex` increments by 1 per iteration.
Verify `result.size == 10000` and spot-check 5 arbitrary `(symbol, position)` pairs for
correct line/column derivation.

---

## Anti-flakiness deltas

Pure functional test, no external I/O, no concurrency, no timing. No flakiness concerns.

---

## Concerns

**1. TypeUnpickler.readTypeIntoSession line 173 snapshot change.**
The snapshot changes from `immutable.HashMap[Int, Reflect.Symbol]` (via `.toMap`) to
`IntMap[Reflect.Symbol]` (via `IntMap.from`). The `DecodeCtx.addrMap` field type change to
`IntMap` is required by this. The boxing elimination extends to the pass-1 type-decode path
as an unplanned additional win; it requires no scope expansion because `DecodeCtx` is internal
to `TypeUnpickler` and the change flows naturally from the `TreeTypeSession.addrMap` type
change.

**2. TastyOrigin.addrMap accessor return type.**
Currently `scala.collection.Map[Int, Reflect.Symbol]` (wide). After Phase 5, `_addrMap` holds
`IntMap[Reflect.Symbol]`. The correct action is to narrow the accessor to return
`IntMap[Reflect.Symbol]` directly:

```scala
def addrMap(using AllowUnsafe): IntMap[Reflect.Symbol] =
    if _addrMap.isSet then _addrMap.get()
    else IntMap.empty
```

This lets `TreeUnpickler.decodeSync` at line 37 (`val addrMap = origin.addrMap`) receive an
`IntMap` without any cast, and then pass it to `new TreeTypeSession(names, addrMap, ...)` at
line 54 without a type mismatch. Do NOT use `.asInstanceOf`; narrow the accessor return type.

**3. Does TypeUnpickler MUTATE TreeTypeSession.addrMap?**
No. The `TreeTypeSession` class body (lines 96-108 of TypeUnpickler.scala) has no setter or
method that reassigns `addrMap`. `TreeUnpickler.decodeSync` constructs a `TreeTypeSession`
at line 54 and then passes `session.addrMap` into ephemeral `DecodeCtx` instances at lines
125-136 and 141-152; those `DecodeCtx` instances also have no mutation path for `addrMap`.
All access patterns are `.get(addr)` lookups inside `readTypeNode`. Phase 5's type change for
`TreeTypeSession.addrMap` is safe.

**4. DecodeSession.liveAddrMap stays mutable.**
`DecodeSession.liveAddrMap` must remain `mutable.HashMap` because `walkStats` mutates it
incrementally (`addrMap(nodeAddr) = sym` at every definition site). Phase 5 does not change
`DecodeSession`. The conversion to `IntMap` happens once after `walkStats` returns.
