# Phase 11 v2 Prep: G6 JPMS module-info.class parsing

Plan reference: `execution-plan-v2.md` lines 471-515
IMPROVEMENT-ANALYSIS.md G6: lines 191-196

---

## Current State (as of HEAD + working tree)

Phase 11 is substantially implemented in the working tree but NOT yet committed. Five files have uncommitted changes and one file is untracked:

| File | Status |
|---|---|
| `shared/src/main/scala/kyo/Reflect.scala` | Modified (ModuleDescriptor + component types + findModule extension added) |
| `shared/src/main/scala/kyo/internal/reflect/classfile/ConstantPool.scala` | Modified (CONSTANT_Module and CONSTANT_Package entry parsing + `moduleName`/`packageName` accessors added) |
| `shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala` | Modified (`lookupModule` method + `moduleIndex: Map[String, ModuleDescriptor]` in State.Ready) |
| `shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` | Modified (module-info file collection, reading, and index threading) |
| `shared/src/main/scala/kyo/internal/reflect/classfile/ModuleInfoReader.scala` | Untracked new file (complete implementation) |
| `shared/src/test/scala/kyo/ModuleInfoTest.scala` | Missing -- not yet created |

The only remaining work is writing `ModuleInfoTest.scala` (6 tests, 1 jvmOnly).

---

## API Signatures (verbatim, from working tree)

### Reflect.scala (lines 198-268, 909-910)

```scala
final case class ModuleRequires(
    name: String,
    version: Maybe[String],
    isTransitive: Boolean,
    isStaticPhase: Boolean
)

final case class ModuleExports(
    packageName: String,
    targets: Chunk[String]
)

final case class ModuleOpens(
    packageName: String,
    targets: Chunk[String]
)

final case class ModuleProvides(
    serviceName: String,
    implementations: Chunk[String]
)

final case class ModuleDescriptor(
    name: String,
    version: Maybe[String],
    requires: Chunk[ModuleRequires],
    exports: Chunk[ModuleExports],
    opens: Chunk[ModuleOpens],
    uses: Chunk[String],
    provides: Chunk[ModuleProvides]
)

// extension on Classpath (Reflect.scala ~line 909):
extension (cp: Classpath)
    def findModule(name: String): Maybe[ModuleDescriptor] < (Sync & Abort[ReflectError]) =
        cp.lookupModule(name)
```

### ModuleInfoReader.scala (entry point, line 36)

```scala
object ModuleInfoReader:
    def read(bytes: Array[Byte])(using Frame): Reflect.ModuleDescriptor < (Sync & Abort[ReflectError])
```

### Classpath.scala (line 121)

```scala
private[kyo] def lookupModule(name: String)(using Frame): Maybe[Reflect.ModuleDescriptor] < (Sync & Abort[ReflectError])
```

---

## Module Attribute Byte Layout (JVMS §4.7.25)

The `Module` attribute appears in the class-level attributes of a `module-info.class` file. Its payload (after the u4 attribute_length) is:

```
module_name_index       u2   // CONSTANT_Module -> UTF-8 with dotted module name (e.g., "java.base")
module_flags            u2   // reserved; currently 0
module_version_index    u2   // CONSTANT_Utf8 with version string, or 0 if absent

requires_count          u2
for each requires:
    requires_index      u2   // CONSTANT_Module
    requires_flags      u2   // ACC_TRANSITIVE=0x0020, ACC_STATIC_PHASE=0x0040
    requires_version_idx u2  // CONSTANT_Utf8 or 0

exports_count           u2
for each exports:
    exports_index       u2   // CONSTANT_Package (slash-separated, e.g., "java/lang")
    exports_flags       u2   // reserved
    exports_to_count    u2
    for each exports_to:
        exports_to_index u2  // CONSTANT_Module

opens_count             u2
for each opens:
    opens_index         u2   // CONSTANT_Package
    opens_flags         u2   // reserved
    opens_to_count      u2
    for each opens_to:
        opens_to_index  u2   // CONSTANT_Module

uses_count              u2
for each uses:
    uses_index          u2   // CONSTANT_Class (binary name with '/')

provides_count          u2
for each provides:
    provides_index      u2   // CONSTANT_Class (service interface)
    provides_with_count u2
    for each provides_with:
        provides_with_index u2  // CONSTANT_Class (implementation)
```

Constant pool tag values (JVMS §4.4):
- `CONSTANT_Module` = 19
- `CONSTANT_Package` = 20
- `CONSTANT_Class` = 7
- `CONSTANT_Utf8` = 1

The `ConstantPool` in the working tree already handles tags 19 and 20 (ClassfileFormat.CONSTANT_Module and CONSTANT_Package). The `moduleName(idx)` method dereferences a CONSTANT_Module to its UTF-8 string. The `packageName(idx)` method dereferences a CONSTANT_Package to its slash-form UTF-8 string; `resolvePackageName` in `ModuleInfoReader` converts to dotted form.

---

## File and Line Anchors

| Location | Path | Line(s) |
|---|---|---|
| `ModuleInfoReader.read` entry point | `shared/src/main/scala/kyo/internal/reflect/classfile/ModuleInfoReader.scala` | 36-41 |
| `decodeModuleAttribute` (Module attr payload parse) | same | 175-205 |
| `readRequires` loop | same | 233-251 |
| `readExports` loop | same | 253-270 |
| `readOpens` loop | same | 272-289 |
| `readUses` loop | same | 291-304 |
| `readProvides` loop | same | 306-322 |
| `Reflect.ModuleDescriptor` case class | `shared/src/main/scala/kyo/Reflect.scala` | 260-268 |
| `Reflect.ModuleRequires` | same | 198-203 |
| `Reflect.ModuleExports` | same | 212-215 |
| `Reflect.ModuleOpens` | same | 224-227 |
| `Reflect.ModuleProvides` | same | 236-239 |
| `findModule` extension | same | 909-910 |
| `Classpath.lookupModule` | `shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala` | 120-125 |
| `State.Ready.moduleIndex` field | same | 160 |
| `transitionToReady` (moduleIndex param) | same | 207 |
| `ClasspathOrchestrator` module collection | `shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` | 91, 109-112, 217-274 |
| `ConstantPool.moduleName` | `shared/src/main/scala/kyo/internal/reflect/classfile/ConstantPool.scala` | 135-143 |
| `ConstantPool.packageName` | same | 144-152 |

---

## jrt:/ JDK Access Pattern (Test 6)

On JVM with JDK 9+, the `jrt:/` filesystem provides access to JDK module descriptors. The pattern for test 6:

```scala
import java.nio.file.FileSystems
import java.nio.file.Files

val fs = FileSystems.getFileSystem(java.net.URI.create("jrt:/"))
val path = fs.getPath("modules", "java.base", "module-info.class")
val bytes = Files.readAllBytes(path)
ModuleInfoReader.read(bytes).map: desc =>
    assert(desc.name == "java.base")
```

This is JVM-only because `FileSystems.getFileSystem(URI("jrt:/"))` is not available on JS or Native. Tag the test with `taggedAs jvmOnly`.

The `jrt:/` filesystem is available since JDK 9. The project requires JDK 25 (per project memory), so `jrt:/` is guaranteed present.

Alternative: use `ClassLoader.getSystemResourceAsStream("java/lang/module/ModuleDescriptor.class")` for a class from `java.base` -- but this bypasses the `ModuleInfoReader` (gives a classfile, not a module-info). The `jrt:/` filesystem is the correct approach for `module-info.class`.

---

## Fixture Suggestions for Tests 1-5 (Synthetic Bytes)

All five non-jvmOnly tests use synthesized bytes. A minimal `module-info.class` must have:

```
magic:          CA FE BA BE
minor_version:  00 00
major_version:  00 39          // 57 = Java 13 (JPMS stable; 53=Java 9 minimum)
constant_pool_count: varies
access_flags:   80 00          // ACC_MODULE = 0x8000
this_class:     CP index for module-info class
super_class:    00 00          // no superclass
interfaces_count: 00 00
fields_count:     00 00
methods_count:    00 00
attributes_count: 00 01        // 1 attribute: Module
attribute_name_index: CP idx for "Module"
attribute_length: u4 (byte count of Module payload)
<Module attribute payload>
```

### Test 1 Fixture: `module foo.bar requires java.base;`

Constant pool entries needed:
1. CONSTANT_Utf8 "module-info" (for this_class)
2. CONSTANT_Class -> #1
3. CONSTANT_Utf8 "Module" (attribute name)
4. CONSTANT_Module -> CONSTANT_Utf8 "foo.bar"
5. CONSTANT_Utf8 "foo.bar"
6. CONSTANT_Module -> CONSTANT_Utf8 "java.base"
7. CONSTANT_Utf8 "java.base"

Module attribute payload:
- module_name_index = CP#4 (CONSTANT_Module "foo.bar")
- module_flags = 0x0000
- module_version_index = 0x0000 (no version)
- requires_count = 1
- requires[0]: requires_index=CP#6, requires_flags=0x0000, requires_version_index=0

### Test 2 Fixture: `module foo.bar exports foo.bar to baz.qux;`

Add:
- CONSTANT_Package -> CONSTANT_Utf8 "foo/bar"
- CONSTANT_Module -> CONSTANT_Utf8 "baz.qux"

Exports section: exports_count=1, exports[0]: exports_index=CP#Package("foo/bar"), exports_flags=0, exports_to_count=1, exports_to[0]=CP#Module("baz.qux").

Verify: `desc.exports.head.packageName == "foo.bar"` and `desc.exports.head.targets == Chunk("baz.qux")`.

### Test 3 Fixture: `module foo.bar uses com.example.Service;`

Add:
- CONSTANT_Class -> CONSTANT_Utf8 "com/example/Service"

Uses section: uses_count=1, uses[0]=CP#Class("com/example/Service").

Verify: `desc.uses == Chunk("com.example.Service")`.

### Test 4 Fixture: `module foo.bar provides com.example.Service with com.example.Impl;`

Add:
- CONSTANT_Class -> CONSTANT_Utf8 "com/example/Service"
- CONSTANT_Class -> CONSTANT_Utf8 "com/example/Impl"

Provides section: provides_count=1, provides[0]: provides_index=CP#Class("com/example/Service"), provides_with_count=1, provides_with[0]=CP#Class("com/example/Impl").

Verify: `desc.provides.head.serviceName == "com.example.Service"` and `desc.provides.head.implementations == Chunk("com.example.Impl")`.

### Test 5 Fixture: Wrong magic

```scala
val badBytes = Array[Byte](0xDE.toByte, 0xAD.toByte, 0xBE.toByte, 0xEF.toByte, 0, 0, 0, 57, ...)
```

Verify: `Abort.fail(ReflectError.ClassfileFormatError(_, msg))` where `msg` contains "magic" or "CAFEBABE".

---

## Building Synthetic Bytes: Helper Pattern

Follow the same approach as `Scala2PickleTest` -- a small builder object or private helpers in the test class. The pattern for building a valid constant pool with CONSTANT_Module and CONSTANT_Package:

```scala
// CONSTANT_Utf8: tag=1, u2 length, UTF-8 bytes
def cpUtf8(s: String): Array[Byte] =
    val bytes = s.getBytes("UTF-8")
    Array(1.toByte) ++ u2(bytes.length) ++ bytes

// CONSTANT_Class: tag=7, u2 name_index
def cpClass(nameIdx: Int): Array[Byte] = Array(7.toByte) ++ u2(nameIdx)

// CONSTANT_Module: tag=19, u2 name_index (points to CONSTANT_Utf8)
def cpModule(nameIdx: Int): Array[Byte] = Array(19.toByte) ++ u2(nameIdx)

// CONSTANT_Package: tag=20, u2 name_index (points to CONSTANT_Utf8, slash-form)
def cpPackage(nameIdx: Int): Array[Byte] = Array(20.toByte) ++ u2(nameIdx)
```

The constant pool count in the classfile is `(number of entries + 1)` because CP indices start at 1.

---

## Edge Cases

### module-info.class WITHOUT Module attribute

The plan does not include a test for this, but `ModuleInfoReader.readAttributesForModule` returns `Abort.fail(ReflectError.ClassfileFormatError(path, "No Module attribute found in module-info.class"))` when none is found (line 143). This is a plausible input from a malformed build tool output. Consider adding a NOTE-level remark in the test file comment; a dedicated test is not required by the plan.

### CONSTANT_Module tag collision (tag 19)

JVM class format prior to Java 9 does not use tag 19. If a pre-Java-9 classfile is accidentally passed to `ModuleInfoReader`, the version check (`majorVersion < 53`) catches it before the constant pool is parsed. The `ConstantPool.read` path handles tag 19 entries only when present.

### Empty module (no requires, exports, etc.)

A `module-info.class` with all counts at zero is valid. All chunk fields will be `Chunk.empty`. No special handling needed; the recursive readers return immediately when `total == 0`.

### module-info.class for unnamed module

The unnamed module in Java has no `module-info.class`. This case is outside the scope of `ModuleInfoReader`. No edge-case handling needed.

---

## Anti-Flakiness Deltas

### Test 6 (jvmOnly): `jrt:/java.base` module-info

- The `jrt:/` filesystem is process-global and does not hold file locks. `Files.readAllBytes` on a `jrt:/` path is safe for concurrent test runs.
- `java.base`'s `module-info.class` is built into the JDK and will not change during a test run.
- No cleanup needed.
- The test should use `assert(desc.name == "java.base")` as the primary assertion. Additional assertions on `desc.requires` (e.g., `desc.requires.isEmpty` because `java.base` has no requires) can be added but are fragile across JDK minor versions; prefer to assert only `name`.

### Tests 1-5 (synthetic bytes)

- All bytes are constructed in-memory; no file system I/O; no flakiness risk.
- Each test is self-contained with no shared state.

---

## Concerns

### C1: ConstantPool.moduleName and ConstantPool.packageName not yet committed

The `moduleName` and `packageName` methods on `ConstantPool` are in the working tree (modified, not committed). They are prerequisites for `ModuleInfoReader` to compile. They must be committed as part of Phase 11 or as a prerequisite commit.

### C2: ClasspathOrchestrator.collectModuleInfoFiles roots only

The `collectModuleInfoFiles` method uses `source.list(root, "module-info.class")` which is a file-discovery call. For test 6, the `jrt:/` filesystem is accessed directly via `Files.readAllBytes` rather than through the orchestrator's classpath-scanning logic. This means test 6 tests `ModuleInfoReader.read` directly, not the full `cp.findModule` integration. A full integration test (registering a `jrt:/modules/java.base` path as a classpath root) would be more thorough but is harder to set up and not required by the plan.

### C3: `discard` helper used in ModuleInfoReader

`ModuleInfoReader.scala` calls `discard(readU2(view))` to skip fields. Verify `discard` is in scope (likely from kyo package). If not, replace with `val _ = readU2(view)` or a local discard val.

```scala
// Check: grep for discard in ModuleInfoReader or ClassfileUnpickler
```

The `ClassfileUnpickler` uses `readU2(view)` without discarding (stores results). `ModuleInfoReader` needs to skip some fields. If `discard` is not a standard kyo utility, the implementation should use `val _unused = readU2(view)` or a local `@nowarn` annotated val. This should be verified before the test run.

### C4: Module attribute attrLen not validated

In `readAttributesForModule`, the `attrLen` is read but not used to bound the decode. If the Module attribute payload is shorter than expected (e.g., truncated requires entry), the ByteView reader will throw an `ArrayIndexOutOfBoundsException` rather than produce a `ReflectError`. The `Sync.defer` wrapper in `ModuleInfoReader.read` does not catch this. A follow-up hardening pass should wrap the attribute decode in `Abort.run[Throwable]`.

---

## Completion Checklist

1. Write `shared/src/test/scala/kyo/ModuleInfoTest.scala` with tests 1-5 (all platforms) and test 6 (jvmOnly).
2. Verify `discard` is available or replace with equivalent (C3 above).
3. Stage all five modified files + the new test file.
4. Run: `sbt 'project kyo-reflect; testOnly kyo.ModuleInfoTest'`
5. Commit as "kyo-reflect v2 Phase 11: G6 JPMS module-info.class parsing".

---

## Verification Command

```
sbt 'project kyo-reflect; testOnly kyo.ModuleInfoTest'
```

Supervisor checks (from plan, lines 509-513):
- `ModuleInfoReader.scala` present -- DONE (untracked)
- `Reflect.ModuleDescriptor` and component types present -- DONE (modified Reflect.scala)
- `findModule` extension present -- DONE
- Test 6 passes on JVM (requires JDK 25 `module-info.class` available via `jrt:/`) -- PENDING (test not written)
