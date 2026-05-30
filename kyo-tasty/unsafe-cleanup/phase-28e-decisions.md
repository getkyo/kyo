# Phase 28e Decisions: Interner AtomicInteger/AtomicReference -> kyo wrappers

## Changes Made

### `Interner.scala` (main)
- Changed to `private` constructor; added `Interner.init(using AllowUnsafe)` factory.
- `growCount: AtomicInteger` -> `AtomicInt.Unsafe` (kyo wrapper).
- `shards: Array[AtomicReference[AtomicReferenceArray[Entry]]]` -> `Array[AtomicRef.Unsafe[AtomicReferenceArray[Entry]]]`.
- `shardLoadCounters: Array[AtomicInteger]` -> `Array[AtomicInt.Unsafe]`.
- `AtomicReferenceArray[Entry]` stays as raw `j.u.c.a.AtomicReferenceArray` (no kyo wrapper for arrays).
- Added `shardLocks: Array[AnyRef]` -- separate per-shard monitor objects to replace the `shardRef.synchronized` pattern.  `AtomicRef.Unsafe` is opaque outside its definition scope; `.synchronized` is an `AnyRef` method not exposed on opaque types.
- `internInShard` and `growShard` updated to pass `shardLock: AnyRef` through and call `shardLock.synchronized` instead of `shardRef.synchronized`.
- `shardSize(idx: Int)` now requires `(using AllowUnsafe)` because it calls `shardLoadCounters(idx).get()`.
- Removed old `import java.util.concurrent.atomic.AtomicInteger` and `AtomicReference`; kept `AtomicReferenceArray`.

### `Tasty.scala` (main)
- `globalInterner` construction changed from `new Interner(...)` to `Interner.init(...)` with block-scoped `import AllowUnsafe.embrace.danger` annotated `// flow-allow: §839 case 3:`.

### `ModuleInfoReader.scala` (main)
- `read()` now constructs the local interner via `Sync.Unsafe.defer(Interner.init(...)).flatMap: interner =>`.

### `ClasspathOrchestrator.scala` (main)
- `runPhaseAB` interner construction wrapped in `Sync.Unsafe.defer(Interner.init(...)).flatMap { interner => Scope.run: ... }`.

### Test files (22 files)
- All `new Interner(numShards = ..., initialShardCapacity = ...)` replaced with `Interner.init(numShards = ..., initialShardCapacity = ...)`.
- `ConstantPoolTest.scala`: added `import AllowUnsafe.embrace.danger` (was missing; needed for class-level `private val interner = Interner.init(...)`).

## j.u.c.a Count

`grep -rln 'java.util.concurrent.atomic' kyo-tasty/shared/src/main | wc -l` = **4** (matches expected metric).
Files: `Interner.scala` (AtomicReferenceArray import), `DeclarationTable.scala`, `ConstantPool.scala`, `ClasspathOrchestrator.scala`.

## import-danger Count

`grep -rn 'import AllowUnsafe.embrace.danger' kyo-tasty/{shared,jvm,js,native}/src/main | wc -l` = **42** (was 41; +1 for `Tasty.globalInterner` module-load init).

## Test Results

- JVM: 482 tests, 0 failures
- JS: 393 tests, 0 failures
- Native: 395 tests, 0 failures

## Em-dash

0 in diff.

## HEAD SHA

`0f007f4361f298ebb74d910a8116fea187ef6aed` (unchanged; no commit made).
