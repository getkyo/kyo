# Phase 28d decisions: OnceCell internals to AtomicRef.Unsafe

## OnceCell internal implementation

Replaced `java.util.concurrent.atomic.AtomicReference` with `kyo.AtomicRef.Unsafe[AnyRef]`.
The class is now `final class OnceCell[A] private (init: () => A, ref: AtomicRef.Unsafe[AnyRef])`.
The companion `object OnceCell` gains `def init[A](init: () => A)(using AllowUnsafe): OnceCell[A]`
which calls `AtomicRef.Unsafe.init[AnyRef](Unset)`. Constructor is private; no public `new OnceCell`.
The `debugIdempotent` and `Unset` members moved to the companion object (no semantic change).

## Interner.intern AllowUnsafe propagation

`Interner.intern` and the private `Interner.internInShard` now take `(using AllowUnsafe)`.
This is required because `internInShard` calls `OnceCell.init(...)` on new-entry insertion.
Added `import kyo.AllowUnsafe` to Interner.scala imports.

All callers of `Interner.intern` were already in AllowUnsafe scope:
- `NameUnpickler.readUnsafe` (line 73): has `import AllowUnsafe.embrace.danger`
- `NameUnpickler.internString` (line 228): added `(using AllowUnsafe)` to signature
- `ClassfileUnpickler.buildOneMemberSymbol` (line 1616): already `(using Frame, AllowUnsafe)`
- `ConstantPool.Utf8Lazy.decode` (line 25): added `(using AllowUnsafe)` to signature
- `Scala2PickleReader.makePickleSym` (line 558): already `(using AllowUnsafe)`
- `Tasty.Name.apply` (line 57): added `(using AllowUnsafe)` to signature

## Tasty.Name.apply propagation

`Name.apply(s: String)` now takes `(using AllowUnsafe)` because it calls `globalInterner.intern`.
This rippled into a small number of private helper methods not yet in AllowUnsafe scope:
- `AstUnpickler.extractPackageName`: added `(using AllowUnsafe)`
- `TreeUnpickler.extractPackageName`: added `(using AllowUnsafe)`
- `TreeUnpickler.nameFromRef`: added `(using AllowUnsafe)`
- `TreeUnpickler.readTemplate`: added `(using AllowUnsafe)`
- `TreeUnpickler.readTemplateBody`: added `(using AllowUnsafe)`

All of the above are private methods called only from methods that already hold AllowUnsafe
(either via `import AllowUnsafe.embrace.danger` or `(using AllowUnsafe)` parameter).

## Callsite migration list

All `new OnceCell` sites migrated to `OnceCell.init`:

| File | Change |
|---|---|
| `kyo/Tasty.scala:928-929` | `new OnceCell[Name]` and `new OnceCell[Tree]` -> `OnceCell.init[Name]` / `OnceCell.init[Tree]` |
| `kyo/internal/tasty/symbol/Interner.scala:92` | `new OnceCell(() => ...)` -> `OnceCell.init(() => ...)` |
| `kyo/OnceCellTest.scala` | All 6 `new OnceCell[...]` -> `OnceCell.init[...]`; tests 5/6/8 use `Sync.Unsafe.defer(OnceCell.init(...))` since they are inside `run { ... }` without a local danger import |

## Test files updated

- `OnceCellTest.scala`: migrated all `new OnceCell` to `OnceCell.init`; tests inside `run { }` wrap construction in `Sync.Unsafe.defer`
- `DeclarationTableTest.scala`: added `import AllowUnsafe.embrace.danger` inside test bodies that call `Tasty.Name(...)` directly
- `AttributeUnpicklerTest.scala`: added `import AllowUnsafe.embrace.danger` inside test body that calls `interner.intern` directly

## Metrics

- `java.util.concurrent.atomic` import count in shared/main: 4 (was 5; OnceCell.scala dropped)
- `import AllowUnsafe.embrace.danger` count in main sources: 41 (unchanged from Phase 28b baseline)
- Em-dash / en-dash: 0
- HEAD SHA: 3c0c335474c63ddb689a1e1e663f0748ec9a6b3b (unchanged)
