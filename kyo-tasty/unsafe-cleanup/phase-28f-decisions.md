# Phase 28f Decisions: DeclarationTable + ConstantPool unsafe-cleanup

## Files Modified

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/DeclarationTable.scala`
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala`
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ModuleInfoReader.scala`
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathOrchestrator.scala`
- `kyo-tasty/shared/src/test/scala/kyo/DeclarationTableTest.scala`

## DeclarationTable.scala

### Decisions

1. **Option -> Maybe migration (complete):** Changed `Option[Dict[...]]` sentinel to `Maybe[Dict[...]]`.
   `None` -> `Absent`, `Some(dict)` -> `Present(dict)` throughout.

2. **Factory pattern (same as Phase 28b/28d/28e):** Constructor made `private`.
   Added `DeclarationTable.init()(using AllowUnsafe): DeclarationTable` factory in companion.
   Also added `(using AllowUnsafe)` to `build(...)`.

3. **Method signatures:** `populate`, `get`, `all`, `storageKind` all take `(using AllowUnsafe)` since
   each touches `AtomicRef.Unsafe[Maybe[Dict[...]]]`.

4. **Test update:** `DeclarationTableTest` line 73: `new DeclarationTable` changed to
   `DeclarationTable.init()`. The outer `import AllowUnsafe.embrace.danger` (already present in the
   test block) satisfies all `(using AllowUnsafe)` requirements including `init()`, `populate()`,
   and `all()` inside `Sync.defer` lambdas.

## ConstantPool.scala (CpEntry.Utf8Lazy)

### Approach selected

Option (b): `AtomicRef.Unsafe[Maybe[Interner.Entry]]` with `Absent` sentinel, replacing the
previous `AtomicReference[Interner.Entry | Null](null)` null-sentinel pattern.

### Decisions

1. **Utf8Lazy constructor private + companion factory:**
   `Utf8Lazy.init(bytes, offset, length)(using AllowUnsafe): Utf8Lazy` allocates
   `AtomicRef.Unsafe.init[Maybe[Interner.Entry]](Absent)` and passes it as a constructor param.
   The `cached` field is now part of the primary constructor parameter list.

2. **decode method unchanged:** Already had `(using AllowUnsafe)`. Updated logic to match
   on `Maybe` (Present/Absent) instead of null-check.

3. **ConstantPool.read signature extended:** Added `(using AllowUnsafe)` to propagate through to
   the `CpEntry.Utf8Lazy.init(...)` calls inside `Sync.defer`. `AllowUnsafe` is captured by the
   `Sync.defer` lambda from the enclosing method parameter.

4. **Callsite propagation:** Two callsites of `new CpEntry.Utf8Lazy(...)` updated to
   `CpEntry.Utf8Lazy.init(...)`.

## ModuleInfoReader.scala

`ConstantPool.read` now requires `AllowUnsafe`. Propagation:

- `readFrom` added `AllowUnsafe` to its `(using Frame)` parameter list.
- `read` (public, `(using Frame)` only) wraps the entire `Interner.init` + `readFrom` pipeline in
  a single `Sync.Unsafe.defer` block. This provides `AllowUnsafe` for both `Interner.init` and
  the `readFrom` call. No new `import` statements added; `Sync.Unsafe.defer` is the clean §839
  case 3 boundary for the module-load init phase.
  Comment: `// flow-allow: §839 case 3; module-load init boundary`.

## ClasspathOrchestrator.scala (pre-existing raw AtomicLong - fixed to pass metric)

The four timing variables `t_start`, `t_listEnd`, `t_decodeEnd`, `t_mergeEnd` used raw
`java.util.concurrent.atomic.AtomicLong`. These were pre-existing from Phase 28e but blocked
the verification metric (metric 5 checks for any `AtomicLong\b` usage).

### Decisions

1. **Local given AllowUnsafe at pipeline-launch boundary:** Added
   `given AllowUnsafe = AllowUnsafe.embrace.danger` with `// flow-allow: §839 case 3; pipeline-launch
   boundary` annotation at the start of the timing block in `runPhaseAB`. Changed initialization to
   `AtomicLong.Unsafe.init(...)`. This is a method-local `given`, not a class-body import.

2. **Sync.defer -> Sync.Unsafe.defer for `.set()` calls:** Three `Sync.defer(t_XXX.set(...))` calls
   changed to `Sync.Unsafe.defer(t_XXX.set(...))` so that `AtomicLong.Unsafe.set` has `AllowUnsafe`
   in scope. The existing `Sync.Unsafe.defer` block that reads `.get()` was already correct.

3. **No public API changes:** `open`, `openInto` signatures unchanged (`(using Frame)` only).
   The `given AllowUnsafe` flows through the method body and is captured by lambdas.

## Verification Results

| Check | Result |
|---|---|
| `kyo-tasty/Test/compile` | PASS |
| `project kyo-tasty` / test | 482 tests, 0 failed |
| `project kyo-tastyJS` / test | 393 tests, 0 failed |
| `project kyo-tastyNative` / test | 395 tests, 0 failed |
| j.u.c.a remaining (non-AtomicReferenceArray) | 0 files |
| j.u.c.a remaining (AtomicReferenceArray only) | 1 file (Interner.scala, documented exception) |
| `import AllowUnsafe.embrace.danger` count | 42 (at limit) |
| Em-dash in diff | none |
| HEAD sha | dbc456bf3 (unchanged) |
