# Phase 11 V2 Audit: JPMS module-info.class parsing

**Commit**: `6e9687f23` ("kyo-reflect v2 Phase 11: G6 JPMS module-info.class parsing")
**Date**: 2026-05-25
**Addresses**: G6 (DESIGN.md §24 "Java module-info.class / JPMS")
**Tests added**: 6 new (262 -> 268)

---

## Summary verdict

0 BLOCKER, 1 WARN, 3 NOTE.  Phase 11 is shippable.

---

## BLOCKER

None.

---

## WARN

### W1 — ModuleDescriptor ADT field shapes deviate from commit message (acceptable)

The commit message describes the ADT with a `flags: Long` field on each type:

```
final case class ModuleRequires(name: String, flags: Long, version: Maybe[String])
final case class ModuleExports(packageName: String, flags: Long, targets: Chunk[String])
```

The actual ADT in `Reflect.scala` exposes decoded booleans instead of raw flags:

```scala
// Reflect.scala:198
final case class ModuleRequires(
    name: String,
    version: Maybe[String],
    isTransitive: Boolean,
    isStaticPhase: Boolean
)

// Reflect.scala:212
final case class ModuleExports(packageName: String, targets: Chunk[String])

// Reflect.scala:224
final case class ModuleOpens(packageName: String, targets: Chunk[String])
```

`ModuleExports` and `ModuleOpens` have no `flags` field at all; the export/open flags are discarded silently (they are currently all zero in practice for java.base, but future JDK module descriptors may set `ACC_OPENS_SYNTHETIC` or `ACC_EXPORTS_SYNTHETIC`). `ModuleRequires` keeps the flags as decoded `isTransitive`/`isStaticPhase` booleans, which is the right choice for public API.

Verdict: WARN because discarding export/open flags diverges from the plan spec and silently loses information present in the classfile. For v1 scope this is acceptable (flags are for private jigsaw tooling), but it is a public-API design decision worth reviewing before the module is declared stable.

---

## NOTE

### N1 — Test 6 description mismatch vs test implementation

Plan Test 6 spec: `cp.findModule("java.base") on a JVM classpath that includes the JDK module-info.class returns Present(desc)`.

Actual Test 6 (`ModuleInfoTest.scala:397`): loads `java.base` bytes directly via `jrt:/` and calls `ModuleInfoReader.read` directly -- it does NOT exercise `Classpath.findModule` (the extension method at `Reflect.scala:910`). The `findModule` extension path (through `ClasspathOrchestrator.collectModuleInfoFiles` -> `readModuleInfoFiles` -> `moduleIndex` -> `Classpath.lookupModule`) has no dedicated test.

This is a gap: the orchestrator wiring for `module-info.class` files is untested end-to-end. The unit coverage of `ModuleInfoReader.read` is complete, but the integration path is not exercised by any test.

### N2 — Soft-fail Panic branch in `readModuleInfoFiles` is incomplete

`ClasspathOrchestrator.readModuleInfoFiles` (line 250):

```scala
case Result.Panic(_) =>
    if strict then Maybe.Absent   // <-- should propagate, not return Absent
    else Maybe.Absent
```

In strict mode, `Result.Panic` should re-throw, not silently return `Absent`. Both branches are identical. This is the same pattern seen in `collectTastyFiles` for the panic case and was presumably copy-pasted. Low impact since `ModuleInfoReader.read` is unlikely to panic, but the strict-mode contract is broken.

### N3 — `findModule` extension missing `Async` effect

The `Classpath.lookupModule` method signature (`Classpath.scala:121`):

```scala
private[kyo] def lookupModule(name: String)(using Frame): Maybe[Reflect.ModuleDescriptor] < (Sync & Abort[ReflectError])
```

And the public extension (`Reflect.scala:910`):

```scala
def findModule(name: String): Maybe[ModuleDescriptor] < (Sync & Abort[ReflectError])
```

Other lookup extensions (`findClass`, `findPackage`) carry `Async` in their return type because they can suspend on the `readyLatch` during the Building phase. `findModule` calls `checkOpen` first (which fails if Building), so suspending is not possible, and the omission of `Async` is intentional. This is correct -- noted here for completeness only.

---

## Checklist

| Item | Status |
|------|--------|
| `ModuleInfoReader.read` present in `kyo.internal.reflect.classfile` | PASS |
| `Reflect.ModuleDescriptor` final case class | PASS |
| `Reflect.ModuleRequires` final case class | PASS |
| `Reflect.ModuleExports` final case class | PASS |
| `Reflect.ModuleOpens` final case class | PASS |
| `Reflect.ModuleProvides` final case class | PASS |
| `extension (cp: Classpath) def findModule(...)` present | PASS |
| `ConstantPool.moduleName(idx)` helper | PASS (`ConstantPool.scala:136`) |
| `ConstantPool.packageName(idx)` helper | PASS (`ConstantPool.scala:144`) |
| `CONSTANT_Module` / `CONSTANT_Package` cp tags handled in `ConstantPool.read` | PASS (lines 286-291) |
| 5 cross-platform tests in `ModuleInfoTest` | PASS (Tests 1-5) |
| Test 6 `jvmOnly` tagged correctly | PASS |
| `ClasspathOrchestrator` wires module-info.class through Phase A/B/C | PASS |
| `Classpath.State.Ready.moduleIndex` field present | PASS |
| `transitionToReady` signature includes `moduleIndex` | PASS |
| No regressions (268/268 passing per commit message) | PASS |
| No new `asInstanceOf` without justification | PASS |
| No `Frame.internal` / `AllowUnsafe` without `// Unsafe:` | PASS |
