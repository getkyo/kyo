# kyo-reflect v2 Phase 4 Prep

Phase: G24 -- Wire `Symbol.companion` via FQN lookup.
Plan reference: `execution-plan-v2.md` lines 145-182.
OI reference: `IMPROVEMENT-OPEN-ITEMS.md` OI-18 (G24 nested-object FQN convention).

---

## Verbatim API signatures

### The companion stub (replacement site)

`kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` line 276:

```scala
def companion(using Frame): Maybe[Symbol] < (Sync & Abort[ReflectError]) = stub("Symbol.companion")
```

The `stub` call unconditionally returns `ReflectError.NotImplemented("Symbol.companion")`. The replacement must:
1. Call `home.checkOpen` (returns `ClasspathClosed` after scope exit).
2. Compute the companion FQN.
3. Delegate to `home.assign`-ed classpath's `lookupClass(companionFqn)` via the `ClasspathRef`.

### ClasspathRef (the `home` field)

The `Symbol` field `private[Reflect] val home: ClasspathRef` is the handle for reaching the classpath after it is in Ready state. `ClasspathRef` exposes `assign(cp: Classpath)` and is used through `cp.lookupClass(fqn)` (the extension on `Classpath`).

`checkOpen` is on the underlying `kyo.internal.reflect.query.Classpath` object, not on `ClasspathRef`. The pattern used by the `Classpath` extension methods is:

```scala
def findClass(fqn: String): Maybe[Symbol] < (Sync & Async & Abort[ReflectError]) = cp.lookupClass(fqn)
```

For `companion`, the impl must reach `cp.lookupClass(companionFqn)` where `cp` is the classpath assigned to `sym.home`. The existing `checkOpen` pattern is:

```scala
// in kyo.internal.reflect.query.Classpath
private[kyo] def checkOpen(using Frame): Unit < (Sync & Abort[ReflectError]) = ...
```

The `companion` impl lives inside `final class Symbol` which has `home: ClasspathRef`. The `ClasspathRef.get` method (or equivalent) yields the `Classpath` opaque type; `cp.lookupClass(fqn)` is then called on it.

Check `ClasspathRef` for the exact access pattern before writing:

```
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathRef.scala
```

### Symbol fields relevant to FQN computation

```scala
final class Symbol private[Reflect] (
    val kind: SymbolKind,       // Class, Trait, Object, Method, ...
    val flags: Flags,           // Flag.Module set on objects
    val name: Name,             // simple name, e.g. "Foo" or "Foo$"
    val owner: Symbol,          // enclosing package/class/object
    private[Reflect] val home: ClasspathRef,
    private[kyo] val origin: Symbol.Origin,
    private[kyo] val javaMetadata: Maybe[JavaMetadata]
)
```

`sym.fullName.asString` returns the dotted FQN, e.g., `"kyo.fixtures.SomeCaseClass"`.
`sym.isJava` is `flags.contains(Flag.JavaDefined)`.
`sym.kind == SymbolKind.Object` is set for Scala objects (companions included).

---

## File:line anchors

| Location | File | Line |
|----------|------|------|
| `companion` stub (replacement) | `shared/src/main/scala/kyo/Reflect.scala` | 276 |
| `stub` helper definition | `shared/src/main/scala/kyo/Reflect.scala` | 616 |
| `checkOpen` definition | `shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala` | 36 |
| `lookupClass` definition | `shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala` | 48 |
| `fqnIndex` in `Ready` state | `shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala` | 128 |
| `Flag.JavaDefined` | `shared/src/main/scala/kyo/Reflect.scala` | 90 |
| `SymbolKind` enum | `shared/src/main/scala/kyo/Reflect.scala` | 126-130 |
| `companion` scaladoc with `@note` (to remove) | `shared/src/main/scala/kyo/Reflect.scala` | 270-275 |

---

## FQN computation pattern

The plan (line 154) and OI-18 together define the correct algorithm:

**For a `Class` or `Trait` symbol**: look up the companion object.

The naive `fqn + "$"` works only for top-level classes. The safe version avoids double-dollar:

```
companionFqn = sym.owner.fullName.asString + "." + sym.name.asString + "$"
```

When `sym.owner` is the root sentinel (a top-level class), `owner.fullName.asString` is the package name, which makes this identical to `sym.fullName.asString + "$"` for top-level classes.

Then call `home.lookupClass(companionFqn)` and return `Present(s)` or `Absent`.

**For an `Object` symbol** (looking up the companion class):

```
companionFqn = sym.fullName.asString.stripSuffix("$")
```

If the stripped FQN equals `sym.fullName.asString` (no trailing `$`), the symbol is an object with a non-`$` name (package objects, anonymous objects) -- return `Absent`.

Then call `home.lookupClass(companionFqn)` and return `Present(s)` or `Absent`.

**For Java symbols**: return `Absent` immediately (`sym.isJava` check before FQN computation).

**For all other kinds** (Method, Field, Val, Var, TypeAlias, etc.): return `Absent`.

---

## Edge cases

### Case class with no companion

Technically a `case class` always has a companion in Scala (the `apply` / `unapply` object), so `Absent` from `lookupClass` should not occur for any fixture `case class`. However: if a `case class` is compiled separately without its companion `.tasty` file in the classpath roots, `lookupClass` returns `Absent` and `companion` correctly returns `Absent`. No special handling needed.

A truly companionless plain class (e.g., `class PlainClass` without an `object PlainClass` in the classpath) should return `Absent`. This is Test 3 in Phase 4.

### Top-level objects (where the object IS the only symbol)

`object SomeObject` in `kyo.fixtures` has `sym.kind == SymbolKind.Object` and `sym.fullName.asString == "kyo.fixtures.SomeObject"`. There is no trailing `$` in the TASTy FQN (TASTy stores the Scala name without `$`). Stripping `$` from `"kyo.fixtures.SomeObject"` returns the unchanged string; `lookupClass("kyo.fixtures.SomeObject")` would find the object itself (since `fqnIndex` maps it under `"kyo.fixtures.SomeObject"`). This is incorrect.

Correct handling: for `Object` kind, the companion FQN to look up is the class variant, which TASTy encodes without `$`. The lookup is `sym.owner.fullName.asString + "." + sym.name.asString` (no dollar). If `lookupClass` returns the same symbol back (reference equality `s eq sym`), return `Absent`.

Alternatively: check `fqnIndex` for a `Class`-or-`Trait`-kind symbol at that FQN. The `lookupClass` result alone is not sufficient to distinguish "companion class found" from "object found self".

Concrete recommendation: after `lookupClass(companionFqn)` returns `Present(s)`, check `s.kind` -- accept only `Class` or `Trait` (for object-to-class direction) or only `Object` (for class-to-object direction). This prevents returning the symbol itself as its own companion.

### Nested classes inside companions (e.g., Foo.Bar where Bar is a case class)

Example: `class Outer` contains `class Inner` and `object Inner`. Their TASTy FQNs are `kyo.fixtures.Outer.Inner` (class) and `kyo.fixtures.Outer.Inner` (object -- same dotted FQN but different kind). In `fqnIndex`, the key `"kyo.fixtures.Outer.Inner"` can only map to one symbol. The TASTy encoder uses `kyo.fixtures.Outer$.Inner` or differentiates via `$` only in the classfile binary name, not in the dotted FQN stored in `fqnIndex`.

Actual behavior: `AstUnpickler` stores symbols by their dotted name from the TASTy `Name` table. For a class `Inner` and companion `object Inner` inside `class Outer`, the TASTy names are `Inner` (class) and `Inner` (object, with `Flag.Module`). The `fqnIndex` key for both is `"kyo.fixtures.Outer.Inner"`. Only one entry can exist per key in the HashMap.

Implication: for nested types where both the class and object share the same dotted FQN, `fqnIndex` will contain only the last-inserted entry. The companion lookup for one may fail. This is a known limitation of the single-key FQN index; it affects Phase 4 the same as it would affect any nested companion lookup. The plan does not require resolving this in Phase 4. The test fixtures do not cover this case for Phase 4. The `Outer.tasty` fixture (`Embedded.outerTasty`) contains `class Outer` with `class Inner` and `object InnerCompanion` (distinct names, so no key collision). Test 3 (plain class, no companion) is sufficient for the negative case.

### Java classes (Absent always)

`sym.isJava` is `true` when `flags.contains(Flag.JavaDefined)`. Java `.class` files do not have Scala companions. The guard at the top of `companion` should check `if sym.isJava then Kyo(Absent)` before any FQN computation. This covers `Symbol.JavaOrigin` symbols.

---

## Test-data suggestions

| Test | Fixture | FQN | Expected |
|------|---------|-----|----------|
| Test 1: `case class` companion | `Embedded.someCaseClassTasty` | `"kyo.fixtures.SomeCaseClass"` | `Present(objectSym)` with `kind == Object` |
| Test 1 (object side): `object SomeCaseClass` companion class | Same `.tasty` file (compiled together) | `"kyo.fixtures.SomeCaseClass"` (object) | `Present(classSym)` with `kind == Class` |
| Test 2: `object` companion | `Embedded.someObjectTasty` | `"kyo.fixtures.SomeObject"` | `Present(classSym)` or `Absent` (no plain class `SomeObject` exists) |
| Test 3: plain class no companion | `Embedded.plainClassTasty` or `Embedded.baseClassTasty` | `"kyo.fixtures.PlainClass"` | `Absent` |
| Test 4: post-close | Any open-then-close fixture | Any | `Abort.fail(ReflectError.ClasspathClosed)` |

Notes:
- `SomeCaseClass` compiles into a single `.tasty` file that encodes both the class and the companion object in the same TASTy tree. Both should be registered in `fqnIndex`. Verify that `fqnIndex` contains both `"kyo.fixtures.SomeCaseClass"` (class kind) and the companion is findable.
- `PlainClass` (`class PlainClass(val x: Int)`) has no companion object in `FixtureClasses.scala`; `Embedded.plainClassTasty` is safe for Test 3.
- `BaseClass` (`class BaseClass`) has no companion object; `Embedded.baseClassTasty` is equally safe for Test 3.
- For Test 2 (object side), `SomeObject` has no corresponding `class SomeObject` in the fixture, so the expected result is `Absent`. If the plan's test 2 language says "Present(classSym)", a new fixture with a paired `object Point` + `class Point` may be needed, or use `SomeCaseClass`'s companion object to look up the class (round-trip: class to object = Test 1, object to class = Test 2).

---

## Anti-flakiness deltas

1. **Do not depend on iteration order of `fqnIndex`**: the companion lookup is a single `HashMap.get(companionFqn)` call, not a scan. Order-independent by design.

2. **Assert exact FQN**: test assertions must use `companionSym.fullName.asString == "kyo.fixtures.SomeCaseClass"`, not a substring match. The companion object and class share the same simple name; a substring match could accept the wrong symbol.

3. **Assert `sym.kind` explicitly**: after `Present(s)` is returned, assert `s.kind == SymbolKind.Object` (for class-to-companion direction) or `s.kind == SymbolKind.Class` (for object-to-companion direction). Without this, the test may pass even if the companion stub returns `Present(sym)` (the symbol itself).

4. **Reference equality is not required**: unlike the Resolver's Cache.memo guarantee (`sym1 eq sym2`), companion lookup returns the symbol from `fqnIndex` which is the canonical instance. Reference equality will hold in practice for the same classpath, but the test should not assert it -- `fullName` equality is the correct predicate per the plan.

5. **Post-close test must use a `Scope.run` that exits before the assertion**: open the classpath, capture the symbol, close the scope (exiting `Scope.run`), then call `sym.companion`. If `Scope.run` is still open the classpath is not yet closed and `companion` will not return `ClasspathClosed`.

---

## Concerns

**C-1: `SomeCaseClass` companion object FQN in `fqnIndex`**

A Scala 3 `case class SomeCaseClass(...)` compiles its companion into the same `.tasty` file. The companion object's dotted name in the TASTy Names section is `"SomeCaseClass"` (simple name) with `Flag.Module` set. `AstUnpickler` maps this to `fqnIndex` key `"kyo.fixtures.SomeCaseClass"`. Both the class and the companion object get the same key. Only one survives in the HashMap. This may cause Test 1 to fail if the object is inserted after the class (overwriting it) or vice versa.

Before implementing, verify by running `cp.findClass("kyo.fixtures.SomeCaseClass")` against a classpath opened from `someCaseClassTasty` and inspecting the returned symbol's `kind`. If it returns the class, the object is not in `fqnIndex` and a different fixture strategy (or a two-FQN lookup pattern) is needed.

Potential fix: use the TASTy module-class naming convention. In TASTy, the companion module class has the `$` suffix in the _internal_ symbol name even though the FQN is dotted. The `fqnIndex` key for the companion object may be `"kyo.fixtures.SomeCaseClass$"` (with suffix). If so, Plan line 154's `fqn + "$"` lookup is the correct pattern and the concern dissolves. Verify against the live classpath before writing the implementation.

**C-2: `ClasspathRef` access pattern for `companion`**

The `companion` method body is inside `final class Symbol` which has `home: ClasspathRef`. `checkOpen` is on the `Classpath` object, not `ClasspathRef`. The pattern for calling `checkOpen` inside a Symbol method requires accessing the classpath through `home`. Review `ClasspathRef.scala` to confirm the exact call chain before writing.

**C-3: OI-18 nested-object double-dollar**

OI-18's resolution says to use `sym.owner.fullName.asString + "." + sym.name.asString + "$"` rather than `sym.fullName.asString + "$"`. This avoids `"Outer$$Inner$"` for nested objects. However, this pattern requires `sym.owner` to be non-null (always true except for the root sentinel). The Plan Test 1 only covers top-level case class; a nested companion test is not in Phase 4's required tests. Implement the OI-18-safe version anyway to avoid a future regression.

**C-4: The `@note Not implemented` scaladoc block**

The companion scaladoc (`Reflect.scala` lines 270-275) contains `@note Not implemented in v1. Always fails at runtime with ReflectError.NotImplemented. Deferred per DESIGN.md §24`. This note must be removed when the stub is replaced (per Phase 3 plan note: "All 6 previous stub scaladoc `@note` comments removed"). The Phase 4 impl must remove the `@note` block from the `companion` scaladoc.
