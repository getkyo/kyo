# Phase 2 (G13 Phase C UnresolvedRef resolution) Prep

## Verbatim API signatures

**Pass1Result.placeholders**
- Type: `Chunk[UnresolvedRef]`
- Source: `AstUnpickler.scala:52` (field declaration in `final case class Pass1Result`)
- Populated at: `AstUnpickler.scala:122-123`
  ```scala
  placeholders = Chunk.from(typeSession.placeholders),
  ```

**UnresolvedRef structure**
- Source: `UnresolvedRef.scala:16`
  ```scala
  final case class UnresolvedRef(fqn: String, replaceSlot: SingleAssign[Reflect.Type])
  ```
- `fqn`: the fully-qualified name string to look up in `fqnIndex`
- `replaceSlot`: a write-once `SingleAssign[Reflect.Type]` that Phase C must call `.set(...)` on exactly once

**mergeResults signature**
- Source: `ClasspathOrchestrator.scala:168-170`
  ```scala
  private def mergeResults(
      fileResults: Chunk[FileResult],
      cp: Classpath
  )(using Frame): Unit < Sync
  ```

**SingleAssign slot type and its set/get methods**
- Source: `SingleAssign.scala:14-46`
- Class: `final class SingleAssign[A]`
- `set(a: A)(using AllowUnsafe): Unit` — CAS from Unset to `a`; throws `IllegalStateException` if already set
- `get()(using AllowUnsafe): A` — returns value; throws `IllegalStateException` if not yet set
- Both require `AllowUnsafe` — the Phase C `mergeResults` body already imports `AllowUnsafe.embrace.danger` at line 201

**fqnIndex — where the Ready-state Classpath HashMap lives**
- Built in `mergeResults` as a local `mutable.HashMap.empty[String, Reflect.Symbol]` (line 174)
- Passed to `Classpath.transitionToReady` as `fqnIndex.toMap` (line 211), stored as `State.Ready.fqnIndex: Map[String, Reflect.Symbol]` (Classpath.scala:100)
- During Phase C resolution, the mutable `fqnIndex` local (fully populated by the `for fr <- fileResults` loop at lines 181-197) is the source of truth for lookups — not the not-yet-transitioned `State.Ready`

**TypeArena's intern path for resolved types**
- Source: `TypeArena.scala:19-22`
  ```scala
  def intern(t: Reflect.Type): Reflect.Type =
      val key = TypeKey.of(t)
      map.getOrElseUpdate(key, t)
  ```
- `canonical.merge(fr.arena)` (line 196) is called before placeholder resolution in the current code.
  After the merge, calling `canonical.intern(Reflect.Type.Named(sym))` would insert the resolved type
  into the canonical arena. However: `Type.Named` interning uses `identityHashCode(sym)` as the hash
  key (TypeArena.scala:143), so re-interning a `Type.Named` for a different symbol produces a distinct
  entry -- no aliasing hazard.

---

## File:line anchors

**Where the placeholder accumulator currently writes (`typeSession.placeholders +=`)**

Three sites in `TypeUnpickler.scala`:
- Line 201: `TERMREFpkg` tag handler — `ctx.placeholders += ref`
- Line 208: `TYPEREFpkg` tag handler — `ctx.placeholders += ref`
- Line 415: `TYPEREFin` tag handler — `ctx.placeholders += ref`

Template parents that trigger these paths are decoded via `decodeTemplateParents` called from
`AstUnpickler.scala:232` inside the `TYPEDEF + TEMPLATE` branch (line 220-249).
VALDEF signature types call `decodeOneTypeIfPresent` (line 179), which also enters `TypeUnpickler`.

**Where `mergeResults` walks the per-fiber results**
- `ClasspathOrchestrator.scala:181-197`: the `for fr <- fileResults do` loop that populates `fqnIndex`,
  merges arenas, and collects errors. The placeholder resolution loop must be added AFTER this loop
  (all arenas merged, `fqnIndex` fully populated) and BEFORE `Classpath.transitionToReady` (line 206).

**Where `FileResult` currently lacks placeholders**
- `ClasspathOrchestrator.scala:39-43`: `FileResult` has three fields: `fqns`, `arena`, `errors`.
  No `placeholders` field. Must add `placeholders: Chunk[UnresolvedRef]`.

**Where `decodeTastyBytes` discards the placeholders**
- `ClasspathOrchestrator.scala:144-147`: the `yield` block constructs `FileResult(pairs, arena, Seq.empty)`;
  `pass1Result.placeholders` is available but not threaded out.

**Where the slot for cross-file parent types is left uninitialized**
- Nowhere explicitly -- the `UnresolvedRef` is constructed with a fresh `new SingleAssign[Reflect.Type]`
  at TypeUnpickler.scala:200, 207, 414. If `replaceSlot.set(...)` is never called, any downstream
  `replaceSlot.get()` will throw `IllegalStateException("SingleAssign not yet set")`. Currently no
  caller calls `.get()` on these slots (the placeholder list is discarded), so no exception is thrown
  today -- but it will be thrown as soon as G21 wires parent types via these slots.

---

## Edge cases & gotchas

**What happens if an UnresolvedRef's FQN is not in fqnIndex (missing class)?**

The plan requires synthesizing an `unresolvedSym` with `kind = SymbolKind.Unresolved` and calling
`replaceSlot.set(Type.Named(unresolvedSym))`. The `makeUnresolvedSym` helper already exists in
`TypeUnpickler` (called at TypeUnpickler.scala:189, 195, 202, 208, etc.) -- but that helper is
`private` to `TypeUnpickler`. Phase C in `mergeResults` does not have access to it. A parallel helper
must be added to `ClasspathOrchestrator` or to a shared internal utility, or `makeUnresolvedSym`
must be exposed as `private[reflect]`. Check the exact visibility before writing the loop.

**What happens for cyclic refs (A's parent is B; B's parent is A)?**

`UnresolvedRef` placeholders are for cross-file FQN references. Both A and B would be in `fqnIndex`
after the Phase B decode (each file is decoded independently and contributes its own symbols). The
resolution loop does `fqnIndex.get(fqn)` for each placeholder and writes `Type.Named(sym)` where
`sym` is already a fully-allocated `Reflect.Symbol`. There is no recursion in Phase C placeholder
resolution itself -- it's a flat `fqnIndex.get` per slot. True cyclic parent types (`A extends B`
and `B extends A`) are a semantic error in the source; kyo-reflect's Phase C does not validate this
and will resolve each direction independently, producing two `Type.Named` values pointing at each
other. No stack overflow risk.

**What about UnresolvedRefs in soft-fail mode (corrupted file scenarios)?**

In soft-fail mode, `readAndDecodeTastyFile` catches errors and returns
`FileResult(Seq.empty, TypeArena.canonical(), Seq(err))` (line 113). The placeholder list for failed
files is never populated -- the decode short-circuited before producing any `UnresolvedRef` entries.
After the fix, failed files contribute `Chunk.empty` to `placeholders`. The resolution loop iterates
an empty chunk for those files: no-op, no exception.

**SingleAssign double-assignment behavior (does it throw?)**

Yes. `set(a)(using AllowUnsafe)` calls `ref.compareAndSet(Unset, a.asInstanceOf[AnyRef])`. If that
CAS fails (meaning `set` was already called), it throws `new IllegalStateException("SingleAssign already set")`.
This is intentional write-once semantics. The Phase C resolution loop must call `replaceSlot.set`
exactly once per `UnresolvedRef`. If the same `UnresolvedRef` instance appears twice in the
`placeholders` chunk (which cannot happen because each `UnresolvedRef` is allocated with `new` at the
decode site), double-set would throw. Because each decode site allocates a fresh `new SingleAssign`,
the same instance cannot appear twice -- but the resolution loop should NOT re-use the same `replaceSlot`
across multiple placeholders.

**TypeArena merge semantics if the same Type is re-interned post-resolution**

`canonical.merge(fr.arena)` is called at line 196, before placeholder resolution. The merged arena
contains `Type.Named(unresolvedSym)` values (placeholders). After Phase C writes `Type.Named(resolvedSym)`
into `replaceSlot`, the canonical arena still holds the old `Type.Named(unresolvedSym)` value from
the merge. This is expected: the `replaceSlot` is a free-standing `SingleAssign`, not a reference
inside the arena's map. The canonical arena's `Type.Named(unresolvedSym)` entries are orphaned after
resolution -- they are unreachable once G21 reads parent types from `replaceSlot.get()` instead of
from the arena. No corruption, but also no deduplication of the resolved `Type.Named(resolvedSym)`
in the canonical arena unless an explicit `canonical.intern(...)` call is made. The plan does not
require this: `replaceSlot.get()` returns the type directly, bypassing the arena.

---

## Test-data suggestions

**Need a 2-file TASTy fixture where file A extends a class defined in file B (cross-file parent)**

Current fixtures in `kyo-reflect/shared/src/test/resources/kyo/fixtures/`:
- `PlainClass.tasty` -- a single .tasty file
- `.class` files: `AnonymousFixture$1.class`, `AnonymousFixture.class`, `ArrayRecord.class`,
  `PointRecord.class`, `ThrowsFixture.class`

There is no fixture pair where one .tasty file's class extends a class in another .tasty file.
`PlainClass.tasty` is a single file and has no cross-file parent references.

**Suggestion: add a two-file fixture pair**

Add two source files to `kyo-reflect-fixtures` (or equivalent fixture source location):
- `fixtures/Base.scala`: `package kyo.fixtures; class Base`
- `fixtures/Child.scala`: `package kyo.fixtures; class Child extends Base`

Compile them separately so they produce `Base.tasty` and `Child.tasty` as distinct files.
`Child.tasty` will contain a `TYPEREFpkg` or `TYPEREFin` reference to `kyo.fixtures.Base`, causing
`TypeUnpickler` to emit an `UnresolvedRef(fqn="kyo.fixtures.Base", ...)` in `placeholders`.
When Phase C opens both files together, `fqnIndex` will contain `"kyo.fixtures.Base" -> baseSym`
and the placeholder will resolve to `Type.Named(baseSym)`.

Check whether the kyo-reflect-fixtures module has a source directory and build step -- the existing
`.class` fixtures suggest there is a compile step somewhere. Locate that before adding sources.

---

## Anti-flakiness deltas

**Don't depend on iteration order over the placeholders chunk**

`Pass1Result.placeholders` is a `Chunk[UnresolvedRef]` (line 52 of AstUnpickler.scala). `Chunk` in
kyo preserves insertion order. The resolution loop (`for placeholder <- allPlaceholders do ...`) will
iterate in insertion order, but correctness does not depend on that order: each `UnresolvedRef` holds
its own `SingleAssign` slot. Resolution is independent per slot. No ordering dependency.

**Don't rely on string-based FQN comparison**

The `fqnIndex` key is the `String` returned by `nameToString(sym.fullName)` -- which calls
`n.asString` on the `Reflect.Name` opaque type (line 219). `UnresolvedRef.fqn` is also a `String`
computed by `ctx.names(nameRef).asString` in `TypeUnpickler`. Both paths go through the same
`Interner`-backed `Name.asString` path (the `Interner` is shared across all files via the `interner`
local in `runPhaseAB` at line 86). String equality (`fqnIndex.get(ref.fqn)`) is correct because
the FQN strings are either interned (same reference) or equal by value. Do not use `eq` for FQN
comparison -- `get` on a `HashMap[String, ...]` uses `.equals`, which is correct.

---

## Concerns

**`makeUnresolvedSym` visibility**: The helper that creates a synthetic `Reflect.Symbol` with
`kind = SymbolKind.Unresolved` lives in `TypeUnpickler` as a `private` method. Phase C code in
`ClasspathOrchestrator.mergeResults` will need to synthesize the same kind of symbol for the
not-found case. Options: (a) move `makeUnresolvedSym` to a shared internal utility object (e.g.,
`SymbolFactory` or `InternalSymbol`), (b) expose it as `private[reflect]` on `TypeUnpickler`'s
companion, or (c) duplicate the three-line body inline in `mergeResults`. Option (a) is cleanest.
Verify the current `makeUnresolvedSym` body before choosing; if it only calls `InternalSymbol.makeSymbol`
with fixed arguments it is trivially inlinable.

**`FileResult` is a `private final case class`**: Adding a `placeholders` field requires modifying
the case class definition at `ClasspathOrchestrator.scala:39-43` and all three construction sites:
line 147 (success path in `decodeTastyBytes`), line 113 (soft-fail decode error), and line 119
(soft-fail panic). The two error paths should pass `Chunk.empty[UnresolvedRef]`.

**`AllowUnsafe` scope in `mergeResults`**: The existing `import AllowUnsafe.embrace.danger` at line
201 covers the `cp.stateRef.unsafe.get()` call. The new `replaceSlot.set(...)` calls also require
`AllowUnsafe`. Since the import is at the top of the `Sync.defer` block body, it covers the entire
block -- the new placeholder loop can use `replaceSlot.set(...)` without adding another import,
as long as the loop is inside the same `Sync.defer { ... }` block.

**Ordering: placeholder resolution MUST happen after `canonical.merge`**: Each `UnresolvedRef` was
created by `TypeUnpickler` in a per-fiber arena. By the time `mergeResults` runs, the per-fiber
arenas have been merged into `canonical` (line 196). The `replaceSlot` holds a `SingleAssign` that
is independent of the arena -- it just needs a `Reflect.Symbol` from `fqnIndex`. Ordering is:
(1) populate `fqnIndex`, (2) merge all arenas, (3) resolve all placeholders. The current code does
(1) and (2) inside the `for fr <- fileResults` loop; step (3) must follow the loop.
