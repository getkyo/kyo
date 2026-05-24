# kyo-reflect Execution Plan

Phase 0 is committed: `build.sbt` wires `kyo-reflect` as a cross JVM/JS/Native project that depends on `kyo-core`; `Reflect.scala` has the full public type skeleton (stubs returning `NotImplemented`); `ReflectError.scala` has the closed error ADT (cases: `CorruptedFile`, `UnsupportedVersion`, `MalformedSection`, `ClassfileFormatError`, `SnapshotFormatError`, `SnapshotVersionMismatch`, `SnapshotIoError`, `FileNotFound`, `ClasspathClosed`, `SymbolNotFound`); four example files compile against the skeleton. The skeleton contains two bugs to fix in Phase 0.5 before real implementation can start: the TASTy version constant is wrong (`Version(28, 9, 1)` should be `Version(28, 8, 0)` for Scala 3.8.3), and the fixture sub-module `kyo-reflect-fixtures` does not yet exist. Phases 1 through 7 (with 5b and 6b) deliver the full implementation: binary primitives, name tables, symbol pass 1, type model, classfile reader, Java/Scala unification, the `Reads` macro, record interop, and the complete query/file-source/snapshot/concurrency stack. Every phase ends with green tests and a commit.

---

## Phase 0.5: Bug fixes + fixtures sub-module

**Dependencies**: none (prerequisite before Phase 1 can start).

**Files to produce**:
- `kyo-reflect-fixtures/shared/src/main/scala/kyo/fixtures/FixtureClasses.scala` , Scala source with one instance of every TASTy tag category: a plain `class`, a `trait`, an `object`, a `case class`, a `sealed abstract class`, an `enum`, an opaque type alias, a type alias, an abstract type member, a method with type params, an inline method, a given/implicit, a val, a var, a lazy val, a method with default params, a generic class, a generic method with bounds, a nested class, a package object.
- `kyo-reflect-fixtures/jvm/src/main/scala/kyo/fixtures/JvmFixtureClasses.scala` , JVM-only: a Java-style class with no Scala-specific features, used as a known TASTy baseline.
- `kyo-reflect-fixtures/shared/src/test/scala/kyo/fixtures/FixtureCompilationTest.scala` , one compile-only test that imports `kyo.fixtures.*` to confirm the fixture module compiles.
- `kyo-reflect/shared/src/test/scala/kyo/Test.scala` , verbatim copy of `kyo-actor/shared/src/test/scala/kyo/Test.scala`; package remains `kyo`; no edits to content; all test classes in kyo-reflect extend this; placed here so Phase 0.5's `FixtureCompilationTest` can extend it.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` , change `Version(28, 9, 1)` to `Version(28, 8, 0)` on the `supportedTastyVersion` line (Scala 3.8.3 uses MajorVersion=28, MinorVersion=8, ExperimentalVersion=0).
- `build.sbt` , add `lazy val kyoReflectFixtures = crossProject(JSPlatform, JVMPlatform, NativePlatform).crossType(CrossType.Full).in(file("kyo-reflect-fixtures"))`, no kyo deps, added as `% Test` dependency of `kyo-reflect` via `.dependsOn(kyoReflectFixtures % Test)` in the cross-project configuration.

**Files to delete**: none.

**Public API additions**: none (version constant fix only).

**Public API modifications**:
- `Reflect.supportedTastyVersion` changes value from `Version(28, 9, 1)` to `Version(28, 8, 0)`.

**Public API removals**: none.

**Tests**:
1. `FixtureCompilationTest` , all fixture classes import without error, confirming the fixtures module compiles cross-platform.
2. `FixtureCompilationTest` , `kyo/Test.scala` compiles without error (test base class available for all subsequent phases).

**Total tests**: 2

**Verification command**:
```
sbt 'kyo-reflect-fixtures/compile; kyo-reflect-fixturesJS/Test/compile; kyo-reflect-fixturesNative/Test/compile'
```
Plus cross-platform compile of kyo-reflect itself:
```
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

**Supervisor checks**:
- `Reflect.supportedTastyVersion` is exactly `Version(28, 8, 0)` in `Reflect.scala`.
- `kyo-reflect-fixtures` appears in `build.sbt` as a cross-project.
- `kyo-reflect` in `build.sbt` now has `.dependsOn(\`kyo-reflect-fixtures\` % Test)` (or equivalent cross-project form).
- `sbt 'kyo-reflectJS/Test/compile'` and `sbt 'kyo-reflectNative/Test/compile'` produce zero errors.
- The fixtures source file contains at least one instance of each of: `class`, `trait`, `object`, `enum`, `opaque type`, `type` alias, abstract type member, `inline def`, `given`.

---

## Phase 1: Binary primitives + TASTy header

**Dependencies**: Phase 0.5 (needs correct `Version` type in `Reflect.scala`; `TastyHeader` reads into `Reflect.Version`; fixtures are the golden test input).

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala` , sealed trait `ByteView` with `Heap(bytes: Array[Byte], start: Int, end: Int)` and `Mapped` stubs (Mapped is platform-specific; the trait and Heap are shared); cursor is an `Int` var in the concrete `Heap` instance; exposes `peekByte(at: Int): Byte`, `readByte(): Byte`, `readNat(): Int` (LEB128 unsigned via Varint), `readInt(): Int` (LEB128 signed using zigzag encoding, matching `dotty.tools.tasty.TastyBuffer.readInt` semantics: the raw LEB128 natural is decoded first, then `(n >>> 1) ^ -(n & 1)` maps it to the signed value), `readLongNat(): Long`, `readEnd(): Int` (reads length-prefixed end address), `subView(from: Int, until: Int): ByteView`, `goto(addr: Int): Unit`, `remaining: Int`, `position: Int`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/Varint.scala` , standalone object with `readNat(view: ByteView): Int` and `readInt(view: ByteView): Int` tight-loop LEB128 decoders (~30 LOC each); also `readLongNat(view: ByteView): Long` for 64-bit values.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/Utf8.scala` , object `Utf8` with `decode(bytes: Array[Byte], offset: Int, length: Int): String`; JVM impl uses `new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8)`; JS impl uses `js.Dynamic.global.TextDecoder`; Native impl uses `scalanative.unsafe.fromCString` / libc UTF-8 path. All three platform-specific impls live in the respective platform source directories; the shared file declares an `expect` or `@extern`-style dispatch.
- `kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/binary/Utf8.scala` , JVM-specific `String` construction.
- `kyo-reflect/js/src/main/scala/kyo/internal/reflect/binary/Utf8.scala` , JS-specific `TextDecoder` path.
- `kyo-reflect/native/src/main/scala/kyo/internal/reflect/binary/Utf8.scala` , Native-specific libc path.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TastyFormat.scala` , object with all TASTy tag constants (copied from `dotty.tools.tasty.TastyFormat` constants relevant to kyo-reflect: AST tags 0-127 and 128-255 length-prefixed, magic bytes `0x5CA1AB1F`, name tag constants, modifier tag constants), plus `MajorVersion = 28`, `MinorVersion = 8`, `ExperimentalVersion = 0` as `val`s.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TastyHeader.scala` , object `TastyHeader` with `read(view: ByteView): TastyHeader.Data < Abort[ReflectError]` that reads magic (4 bytes, fails with `CorruptedFile` on mismatch), version triple (3 LEB128 naturals), UUID (16 bytes as `String` hex), tooling-version UTF-8 string; emits `ReflectError.UnsupportedVersion` if version is not compatible (policy: `major == supportedMajor && experimental == 0 || experimental == supportedExperimental && minor <= supportedMinor`); returns `TastyHeader.Data(major, minor, experimental, uuid, toolingVersion)`.
- `kyo-reflect/shared/src/test/scala/kyo/ByteViewTest.scala` , tests for `ByteView` and `Varint`.
- `kyo-reflect/shared/src/test/scala/kyo/Utf8Test.scala` , tests for `Utf8.decode`.
- `kyo-reflect/shared/src/test/scala/kyo/TastyHeaderTest.scala` , tests for `TastyHeader.read`.

**Files to modify**: none beyond Phase 0.5 fixes.

**Files to delete**: none.

**Public API additions**: none (all new types are in `kyo.internal`).

**Public API modifications**: none.

**Public API removals**: none.

**Tests**:
1. `ByteViewTest` , `peekByte(at)` reads the byte at that offset without advancing `position`.
2. `ByteViewTest` , `readByte()` advances `position` by 1 and returns the correct byte.
3. `ByteViewTest` , `readByte()` on a view at `end` produces `ArrayIndexOutOfBoundsException` (this is expected parser behavior; parsers only call `readByte` when they know bytes remain).
4. `ByteViewTest` , `subView(from, until)` returns a view with `start = from`, `end = until`, `position = from`, sharing the same underlying `Array[Byte]`.
5. `ByteViewTest` , `goto(addr)` sets `position` to `addr`.
6. `ByteViewTest` , `remaining` returns `end - position`.
7. `VarintTest` , `readNat` decodes `0` encoded as `Array(0)`.
8. `VarintTest` , `readNat` decodes `127` encoded as `Array(127)`.
9. `VarintTest` , `readNat` decodes `128` encoded as `Array(0x80.toByte, 0x01.toByte)` (multi-byte LEB128).
10. `VarintTest` , `readNat` decodes `16383` (two-byte maximum) correctly.
11. `VarintTest` , `readNat` decodes a known 5-byte encoding (value 2147483647 = Int.MaxValue).
12. `VarintTest` , `readInt` decodes `-1` correctly (signed LEB128).
13. `VarintTest` , `readInt` decodes `Int.MinValue` correctly.
14. `VarintTest` , `readLongNat` decodes `Long.MaxValue` as a 9-byte LEB128 sequence.
15. `Utf8Test` , `Utf8.decode` for ASCII-only bytes returns the expected `String` without using `new String(bytes, ...)` directly (test that the platform path produces the right result).
16. `Utf8Test` , `Utf8.decode` for a 3-byte UTF-8 sequence (e.g., `U+00E9 é`) returns the correct single-character `String`.
17. `Utf8Test` , `Utf8.decode` for a 4-byte UTF-8 sequence (e.g., `U+1F600 😀`) returns the correct surrogate-pair `String` on JVM, emoji character on JS/Native.
18. `Utf8Test` , `Utf8.decode(bytes, offset=1, length=3)` only decodes the sub-range, not the whole array.
19. `TastyHeaderTest` , reading the correct magic bytes + `Version(28, 8, 0)` triple succeeds and returns a `Data` with those values.
20. `TastyHeaderTest` , reading bytes with wrong magic `0xDEADBEEF` produces `Abort.fail(ReflectError.CorruptedFile(...))`.
21. `TastyHeaderTest` , reading a version with `major = 99` produces `Abort.fail(ReflectError.UnsupportedVersion(...))`.
22. `TastyHeaderTest` , reading a version with `major = 28`, `minor = 7` (older minor) produces success (backward compatible per policy).
23. `TastyHeaderTest` , reading a version with `major = 28`, `minor = 9`, `experimental = 0` succeeds (minor <= supported).
24. `TastyHeaderTest` , reading a version with `experimental = 1` when `supportedExperimental = 0` produces `Abort.fail(ReflectError.UnsupportedVersion(...))`.

**Total tests**: 24

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.ByteViewTest kyo.VarintTest kyo.Utf8Test kyo.TastyHeaderTest'
```
Plus:
```
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

**Supervisor checks**:
- `kyo/internal/reflect/binary/ByteView.scala`, `Varint.scala`, `TastyFormat.scala`, `TastyHeader.scala` are all present.
- Platform-specific `Utf8.scala` files exist in `jvm/`, `js/`, `native/` subdirectories.
- `TastyFormat.MajorVersion == 28`, `MinorVersion == 8`, `ExperimentalVersion == 0`.
- All 24 tests pass on JVM; JS and Native compile without errors.
- `ByteView.subView` shares the same underlying array (reference equality on the `bytes` field).

---

## Phase 2: Name table + section index + attributes

**Dependencies**: Phase 1 (`ByteView`, `Varint`, `Utf8`, `TastyHeader`, `TastyFormat` tag constants all required; tests build on `TastyHeaderTest` fixture patterns).

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Interner.scala` , sharded intern table: `final class Interner` with `Interner(shards: Int = 32)` constructor; internally `Array[AtomicReference[Array[Entry]]]` linear-probe tables, one per shard; each `Entry` stores `(hash: Int, bytes: Array[Byte], offset: Int, length: Int, name: Reflect.Name)`; `def intern(bytes: Array[Byte], offset: Int, length: Int): Reflect.Name` computes hash, selects shard, probes for existing entry via `Arrays.equals`, returns existing or inserts; `Reflect.Name` is backed by a lazy `String` via `Memo[String]`; equality uses byte-level comparison without `String` materialization.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Memo.scala` , `final class Memo[A](init: () => A)` with `private val ref = new AtomicReference[A | Null](null)` and double-checked `def get(): A`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/SingleAssign.scala` , `final class SingleAssign[A]` with `private val ref = new AtomicReference[A | Null](null)`; `def set(a: A): Unit` (throws `IllegalStateException` if already set); `def get(): A` (throws if not set).
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/NameUnpickler.scala` , `object NameUnpickler` with `def read(view: ByteView, interner: Interner): Array[Reflect.Name] < Abort[ReflectError]`; reads the `Names` section: count (LEB128 nat), then for each entry a tag byte then the name bytes; simple names (tag `UTF8`) are decoded via `Utf8.decode` and interned; the following name tag variants are decoded (all constants from `TastyFormat`): QUALIFIED=1, EXPANDED=2, EXPANDPREFIX=3, UNIQUE=4, DEFAULTGETTER=5, SUPERACCESSOR=8, INLINEACCESSOR=9, OBJECTCLASS=10, BODYRETAINER=11, SIGNED=63, TARGETSIGNED=62; each compound variant is decoded to a canonical `String` representation then interned; unrecognized tags emit `ReflectError.MalformedSection("Names", ...)`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AttributeUnpickler.scala` , `object AttributeUnpickler` with `def read(view: ByteView, names: Array[Reflect.Name]): FileAttributes < Abort[ReflectError]`; reads the optional `Attributes` section (Scala 3.3+); returns a `FileAttributes(explicitNulls: Boolean, captureChecked: Boolean, isJava: Boolean, isOutline: Boolean, scala2StandardLibrary: Boolean, sourceFile: Maybe[String])`; absent section returns `FileAttributes.default`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/SectionIndex.scala` , `final class SectionIndex(sections: Map[String, (Int, Int)])` mapping section name to `(offset, length)` within the TASTy file bytes; `def get(name: String): Maybe[(Int, Int)]`; built by `SectionIndex.read(view: ByteView): SectionIndex < Abort[ReflectError]` that parses the section table following the header (count, then per-section name + offset + length).
- `kyo-reflect/shared/src/test/scala/kyo/NameUnpicklerTest.scala` , tests for name table decoding using fixture TASTy bytes loaded as classpath resources.
- `kyo-reflect/shared/src/test/scala/kyo/InternerTest.scala` , tests for sharded intern.
- `kyo-reflect/shared/src/test/scala/kyo/AttributeUnpicklerTest.scala` , tests for attribute parsing.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` , update `Name` backing to use the `Interner`-produced opaque type (the public `opaque type Name` stays, but the internal representation switches from `String` to an `Interner.Entry`-backed type that materializes `String` lazily via `Memo[String]`). The skeleton's `opaque type Name = String` becomes `opaque type Name = Interner.Entry` with `Name.asString` triggering lazy decode. All public accessors remain the same shape.

**Files to delete**: none.

**Public API additions**: none.

**Public API modifications**:
- `Reflect.Name` internal representation switches from `String` to `Interner.Entry`; the public surface (`asString`, `CanEqual`) is unchanged and must continue to compile.

**Public API removals**: none.

**Tests**:
1. `InternerTest` , two `intern` calls for the same byte sequence return reference-equal `Name` instances.
2. `InternerTest` , two `intern` calls for different byte sequences return non-equal `Name` instances.
3. `InternerTest` , `intern` from two different shards (different hash values) produces distinct entries.
4. `InternerTest` , `Name.asString` returns the correct UTF-8 decoded string.
5. `InternerTest` , `Name.asString` called twice returns the same (reference-equal) `String` (Memo caching).
6. `InternerTest` , `CanEqual[Name, Name]` holds for two names with the same bytes.
7. `NameUnpicklerTest` , loading the fixture TASTy file: the `Names` section is present and non-empty.
8. `NameUnpicklerTest` , the name `"FixtureClasses"` (or the fixture top-level class name) is in the decoded name array.
9. `NameUnpicklerTest` , a qualified name entry (two simple-name parts joined by `"."`) decodes to a dotted string.
10. `NameUnpicklerTest` , a corrupt section (truncated mid-name) produces `Abort.fail(ReflectError.MalformedSection("Names", ...))`.
11. `NameUnpicklerTest` , all decoded names are interned: `Arrays.equals` on underlying bytes is true for duplicate names in the file.
12. `AttributeUnpicklerTest` , a TASTy file with no `Attributes` section returns `FileAttributes.default` with all flags `false`.
13. `AttributeUnpicklerTest` , a synthesized `Attributes` section with `isJava = true` is parsed correctly.
14. `AttributeUnpicklerTest` , a synthesized `Attributes` section with `explicitNulls = true` sets that flag.
15. `AttributeUnpicklerTest` , `sourceFile` attribute is decoded as `Present("Foo.scala")` when present, `Absent` otherwise.

**Total tests**: 15

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.InternerTest kyo.NameUnpicklerTest kyo.AttributeUnpicklerTest'
```
Plus:
```
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

**Supervisor checks**:
- `Interner.scala`, `Memo.scala`, `SingleAssign.scala`, `NameUnpickler.scala`, `AttributeUnpickler.scala`, `SectionIndex.scala` all present.
- `Reflect.Name` internal representation change does not break any example file compilation (`kyo-reflect/shared/src/main/scala/kyo/reflect/examples/*.scala` must still compile).
- `NameUnpicklerTest` uses fixture bytes loaded via `getClass.getResourceAsStream` (classpath resource from `kyo-reflect-fixtures` % Test); confirm the resource is found at runtime.
- For Scala Native, test resources must be embedded at link time: set `Test/resourceDirectory` to include the fixture `.tasty` bytes, and use the Native-specific `scalanative.runtime.resource.EmbeddedResource` lookup API (or the equivalent `getClass.getResourceAsStream` shim provided by Scala Native's test runner) to load them. Confirm the resource path resolves on Native before writing any Native-dependent test.
- All 15 tests pass on JVM; JS and Native compile.

---

## Phase 3: Symbol pass 1 + skeleton AST

**Dependencies**: Phase 2 (`ByteView`, `Varint`, `NameUnpickler`, `SectionIndex`, `TastyFormat` tag constants, `Interner`, `FileAttributes`; the `Addr -> Symbol` table uses data structures from this phase; `SymbolKind` and `Flags` stubs already in skeleton but must be completed).

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/SymbolKind.scala` , (the enum is already in `Reflect.scala` skeleton; this is the companion object in `kyo.internal` with the TASTy-tag-to-SymbolKind mapping table: `def fromTagAndFlags(tag: Int, flags: Long): SymbolKind`).
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Flags.scala` , complete `Flag` object with all ~42 flags; add the missing ones: `Open`, `ParamAccessor`, `Lazy`, `Override`, `Mutable`, `Erased`, `Tracked`, `Tailrec`, `Infix`, `Transparent`, `Trait`, `JavaRecord` (already present), `Enum` (already present); add `def fromTastyModifierTag(tag: Int): Flags` conversion; add `def fromJvmAccessFlags(acc: Int): Flags` conversion used by classfile reader.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Symbol.scala` , internal companion object that builds `Reflect.Symbol` instances; `def makeSymbol(kind: SymbolKind, flags: Flags, name: Name, owner: Symbol, home: ClasspathRef): Reflect.Symbol` (uses package-private constructor on `Reflect.Symbol`); `Addr -> Symbol` table is `mutable.HashMap[Int, Reflect.Symbol]` held in the per-file pass context.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Annotation.scala` , `final case class Annotation(annotationType: Reflect.Type, argsPickle: Chunk[Byte])` (the argsPickle is a raw byte slice per the skeleton; this file just makes it concrete internal-package-visible).
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/DeclarationTable.scala` , per-class declaration table: `Dict[Name, Symbol]` flat-array for classes with 8 or fewer members (no hashing); `mutable.HashMap[Name, Symbol]` for larger classes. Both variants are wrapped in an `AtomicRef[Map]` and CAS-swapped on population completion so readers see either the empty map or the fully-populated map, never a partially-populated intermediate state.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Constant.scala` , `object Constant` with `def fromTastyTag(tag: Int, view: ByteView): Reflect.Constant < Abort[ReflectError]` decoding constant leaf nodes (UNITconst, FALSEconst, TRUEconst, BYTEconst, SHORTconst, CHARconst, INTconst, LONGconst, FLOATconst, DOUBLEconst, STRINGconst, NULLconst, CLASSconst, ENUMconst). For `CLASSconst`: decode the type reference embedded in the TASTy to produce `ClassLiteral(typeRef: Reflect.Type)` without resolving the class symbol at decode time; consumers that need the live `Class[?]` object call `cp.findClass(fqn)` separately. This is the cross-platform path: no `java.lang.Class` reference at decode time.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala` , `object AstUnpickler` implementing pass 1 (skeleton-eager + lazy bodies): `def readPass1(view: ByteView, names: Array[Reflect.Name], attrs: FileAttributes, home: ClasspathRef): Pass1Result < Abort[ReflectError]`; walks the `ASTs` section; on `PACKAGE`, `CLASSDEF`, `TYPEDEF`, `VALDEF`, `DEFDEF` tags: allocates one `Reflect.Symbol` via `Symbol.makeSymbol`; eagerly decodes name (NameRef lookup), flags (modifier tags), parent type references (as `UnresolvedRef(nameRef)` placeholders stored in `Pass1Result.placeholders`), and one-level-deep member NameRefs; records `DEFDEF`/class body as `(startAddr, endAddr)` pair on the symbol record; skips over length-prefixed sub-trees it doesn't understand; returns `Pass1Result(symbols: Chunk[Reflect.Symbol], addrMap: Map[Int, Reflect.Symbol], placeholders: Chunk[UnresolvedRef])`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathRef.scala` , `final class ClasspathRef` (forward reference placeholder for `Reflect.Classpath`; set via `SingleAssign` during Phase 7 orchestration; `Reflect.Symbol.home` holds one; accessors call `ref.get()` to reach the actual state). Note: `ClasspathRef` is a forward-declared `SingleAssign` slot. Phase 3 does NOT depend on Phase 7's Classpath implementation; the type `Classpath` exists from Phase 0's skeleton as an opaque type. Phase 7 transitions it from stub to real state machine without changing Symbol's home reference. Constraint: no Phase 3 code may call `home.checkOpen` or `home.get` (the slot is unset until Phase 7 assigns it); all Phase 3 call-sites that need home only store or forward the `ClasspathRef` reference; audit `computeFullName` and `computeBinaryName` to confirm neither calls through to home.
- `kyo-reflect/shared/src/test/scala/kyo/AstUnpicklerTest.scala` , tests for pass 1 AST traversal.
- `kyo-reflect/shared/src/test/scala/kyo/FlagsTest.scala` , tests for Flag conversions.
- `kyo-reflect/shared/src/test/scala/kyo/DeclarationTableTest.scala` , tests for per-class declaration table (flat-array vs HashMap cutover, AtomicRef CAS-swap visibility).

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` , wire `Symbol.computeFullName` and `Symbol.computeBinaryName` to walk the owner chain (instead of returning just `sym.name`); add `isPackageObject` implementation using `flags.contains(Flag.Module) && name.asString == "package"`; `Symbol` private constructor gains `origin: Symbol.Origin` field. Phase 3 declares the complete `Symbol.Origin` sealed trait with BOTH cases: `TastyOrigin(addrMap: Map[Int, Reflect.Symbol], bodyStart: Int, bodyEnd: Int)` and `JavaOrigin` (no TASTy-specific fields); Phase 5 only adds construction sites that produce `JavaOrigin` instances, it does not add new ADT cases.

**Files to delete**: none.

**Public API additions**: none.

**Public API modifications**:
- `Symbol.computeFullName` now walks the owner chain to build dotted names.
- `Symbol.isPackageObject` now returns a real value instead of `false`.

**Public API removals**: none.

**Tests**:
1. `FlagsTest` , `Flag.Inline.bit` is a power-of-two Long, no two flags share a bit.
2. `FlagsTest` , `Flags.empty.contains(Flag.Inline)` is `false`.
3. `FlagsTest` , `(new Flags(Flag.Inline.bit | Flag.Private.bit)).contains(Flag.Private)` is `true`.
4. `FlagsTest` , `fromTastyModifierTag` maps the `PRIVATE` modifier tag (value 5) to a `Flags` containing `Flag.Private`.
5. `FlagsTest` , `fromTastyModifierTag` maps the `INLINE` modifier tag to a `Flags` containing `Flag.Inline`.
6. `FlagsTest` , all ~42 flag `bit` fields are distinct (no duplicates in the full flag list).
7. `AstUnpicklerTest` , pass 1 on the fixture TASTy file returns at least one symbol with `kind == SymbolKind.Class`.
8. `AstUnpicklerTest` , the fixture's top-level class name is in the returned symbol set (name matches `"FixtureClasses"` or the fixture top class).
9. `AstUnpicklerTest` , a `def` inside the fixture class produces a symbol with `kind == SymbolKind.Method`.
10. `AstUnpicklerTest` , a `val` inside the fixture class produces a symbol with `kind == SymbolKind.Val`.
11. `AstUnpicklerTest` , a `trait` in the fixture produces a symbol with `kind == SymbolKind.Trait`.
12. `AstUnpicklerTest` , an `object` in the fixture produces a symbol with `kind == SymbolKind.Object`.
13. `AstUnpicklerTest` , an `enum` in the fixture produces a symbol with `kind == SymbolKind.Class` and `flags.contains(Flag.Enum)`.
14. `AstUnpicklerTest` , an `inline def` produces a symbol with `flags.contains(Flag.Inline)`.
15. `AstUnpicklerTest` , a type parameter produces a symbol with `kind == SymbolKind.TypeParam`.
16. `AstUnpicklerTest` , `sym.owner` for a method symbol is the class symbol (owner chain is wired in pass 1).
17. `AstUnpicklerTest` , `sym.fullName` for a nested class is the dotted form (e.g., `"kyo.fixtures.FixtureClasses.NestedClass"`).
18. `AstUnpicklerTest` , body slices `(bodyStart, bodyEnd)` for a `DEFDEF` are non-zero (body is recorded, not decoded).
19. `AstUnpicklerTest` , cross-forward-reference type parameter: class `C[T1 <: T2, T2]` produces two TypeParam symbols; assert `addrMap(T1Addr).name.asString == "T1"` and `addrMap(T2Addr).name.asString == "T2"` (exact name-equality check confirms correct symbol allocation order without decode-order errors).
20. `AstUnpicklerTest` , passing a truncated TASTy bytes (chopped mid-section) produces `Abort.fail(ReflectError.MalformedSection("ASTs", ...))`.
21. `DeclarationTableTest` , a class with 4 members uses the flat-array `Dict[Name, Symbol]` path: all members are retrievable by name in O(n) linear scan, and the table's internal representation uses no `HashMap`.
22. `DeclarationTableTest` , a class with 9 members (above the ≤8 threshold) uses the `mutable.HashMap[Name, Symbol]` path: all members are retrievable by name.
23. `DeclarationTableTest` , `AtomicRef` CAS-swap visibility: a second thread that reads the table before population completes sees either the empty map or the fully-populated map (never a partial state); verified via a `kyo.Latch`-based two-fiber fixture (writer holds the latch open until all entries are inserted then releases; reader waits on the latch before reading); wrap in `Async.timeout(1.second)` so a hang fails the test rather than blocking CI.

**Total tests**: 23

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.FlagsTest kyo.AstUnpicklerTest kyo.DeclarationTableTest'
```
Plus:
```
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

**Supervisor checks**:
- `AstUnpickler.scala`, `Symbol.scala` (internal), `Flags.scala`, `SymbolKind.scala`, `Annotation.scala`, `Constant.scala`, `ClasspathRef.scala`, `DeclarationTable.scala` are all present.
- `Reflect.Symbol.computeFullName` walks the owner chain: verify by running `AstUnpicklerTest` test 17.
- `Flag` object has all ~42 constants including `Open`, `ParamAccessor`, `Lazy`, `Override`, `Mutable`, `Erased`, `Tracked`, `Tailrec`, `Infix`, `Transparent`.
- All 23 tests pass on JVM; JS and Native compile.

---

## Phase 4: Type model + per-fiber arenas + Phase C merge

**Dependencies**: Phase 3 (`Symbol`, `Name`, `AstUnpickler` pass 1 producing placeholder types, `ClasspathRef`; type nodes reference symbols via `Named(sym)`; `Pass1Result.placeholders` requires `UnresolvedRef` types that will be resolved here; `TypeArena` merge produces canonical types stored back on symbols).

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeArena.scala` , `final class TypeArena` with `private val map = new mutable.HashMap[TypeKey, Reflect.Type]`; `def intern(t: Reflect.Type): Reflect.Type` (look up or insert); `def merge(canonical: TypeArena): Unit` (bottom-up recursive with `inProgress` cycle-break map per DESIGN.md §9 pseudocode); `object TypeArena` with `def canonical(): TypeArena` factory; `TypeKey` is a `final class` with structural hash and equality matching the `Type` ADT cases.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeOps.scala` , `object TypeOps` with smart constructors that normalize at build time: `def applied(base: Reflect.Type, args: Chunk[Reflect.Type]): Reflect.Type` (applies FunctionN, TupleN, ContextFunctionN, Array normalizations); `def andType(l: Reflect.Type, r: Reflect.Type): Reflect.Type` (collapses `AndType(Singleton, X)` to `X`); `def mkArray(elem: Reflect.Type): Reflect.Type` (returns `Type.Array(elem)`).
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TypeUnpickler.scala` , `object TypeUnpickler` with `def readType(view: ByteView, names: Array[Reflect.Name], addrMap: Map[Int, Reflect.Symbol], arena: TypeArena): Reflect.Type < Abort[ReflectError]` decoding TASTy type nodes: `TERMREFpkg`, `TYPEREFpkg`, `TERMREFsymbol`, `TYPEREFsymbol`, `TERMREFin`, `TYPEREFin`, `APPLIEDtype` (uses `TypeOps.applied`), `TYPELAMBDAtype`, `METHODtype`, `ERASEDMETHODtype`, `IMPLICITMETHODtype`, `METHODtypes` variants, `BYNAMEtype`, `REPEATEDtype`, `RECtype`, `RECthis`, `ANDtype`, `ORtype`, `ANNOTATEDtype`, `MATCHtype`, `FLEXIBLEtype`, `SHAREDtype` (per-file `Addr -> Type` cache), `SKOLEMtype`, `WILDCARDtype`, `SUPERtype`, `THIStype`, `PARAMref`, `CONSTANTtype`, cross-file `TYPEREFin` with unresolved FQN stored as `UnresolvedRef` placeholder type (internal sentinel, resolved in Phase C); uses `arena.intern` on each constructed type.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/UnresolvedRef.scala` , internal-only `case class UnresolvedRef(fqn: String, replaceSlot: SingleAssign[Reflect.Type])` (a mutable slot; Phase C writes `Named(resolvedSym)` into it; `Type` nodes holding an `UnresolvedRef` are updated in-place via the `SingleAssign`). This type is NOT part of `Reflect.Type`; it lives only in the internal pass context.
- `kyo-reflect/shared/src/test/scala/kyo/TypeArenaTest.scala` , tests for `TypeArena` intern and merge.
- `kyo-reflect/shared/src/test/scala/kyo/TypeUnpicklerTest.scala` , tests for type node decoding.
- `kyo-reflect/shared/src/test/scala/kyo/TypeOpsTest.scala` , tests for smart constructors.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala` , wire `TypeUnpickler.readType` for signature/parent positions in pass 1; parent types now produce actual `Reflect.Type` values (or `UnresolvedRef` placeholders) instead of plain `NameRef` integers.

**Files to delete**: none.

**Public API additions**: none.

**Public API modifications**: none (all changes are internal; public `Reflect.Type` ADT already present in skeleton).

**Public API removals**: none.

**Tests**:
1. `TypeArenaTest` , `intern` called twice with structurally identical `Type.Named(sym)` returns the same reference.
2. `TypeArenaTest` , `intern` on `Type.Applied(base, args)` with different arg lists returns different references.
3. `TypeArenaTest` , `merge` of two arenas containing the same structural type produces a canonical arena with one entry.
4. `TypeArenaTest` , `merge` correctly handles a `Type.Rec(parent)` containing `Type.RecThis(rec)` back-reference (cycle, no infinite loop).
5. `TypeArenaTest` , after merge, structurally-equal types from two arenas are reference-equal.
6. `TypeOpsTest` , `TypeOps.applied(Named(scala.Function2), Chunk(A, B, C))` produces `Function(Chunk(A, B), C, false)`.
7. `TypeOpsTest` , `TypeOps.applied(Named(scala.Tuple2), Chunk(A, B))` produces `Tuple(Chunk(A, B))`.
8. `TypeOpsTest` , `TypeOps.applied(Named(scala.ContextFunction1), Chunk(A, B))` produces `Function(Chunk(A), B, true)`.
9. `TypeOpsTest` , `TypeOps.applied(Named(scala.Array), Chunk(T))` produces `Type.Array(T)`.
10. `TypeOpsTest` , `TypeOps.andType(Named(scala.Singleton), X)` collapses to `X`.
11. `TypeOpsTest` , `TypeOps.andType(X, Named(scala.Singleton))` collapses to `X`.
12. `TypeUnpicklerTest` , decoding a `TYPEREFsymbol` node for `scala.Int` returns `Named(intSymbol)`.
13. `TypeUnpicklerTest` , decoding a `BYNAMEtype` wrapping `scala.Int` returns `ByName(Named(intSymbol))`.
14. `TypeUnpicklerTest` , decoding a `REPEATEDtype` returns `Repeated(elem)`.
15. `TypeUnpicklerTest` , decoding an `APPLIEDtype` for `List[String]` returns `Applied(Named(listSym), Chunk(Named(stringSym)))`.
16. `TypeUnpicklerTest` , decoding a `SHAREDtype` reference to a previously-decoded type returns the same reference (no duplication).
17. `TypeUnpicklerTest` , decoding a `TYPELAMBDAtype` returns `TypeLambda(params, body)` with `params.size == 1` for a one-param lambda.
18. `TypeUnpicklerTest` , decoding an `ANNOTATEDtype` returns `Annotated(underlying, annotation)`.
19. `TypeUnpicklerTest` , decoding an `ORtype` returns `OrType(left, right)`.
20. `TypeUnpicklerTest` , decoding an `ANDtype` returns `AndType(left, right)` (or normalized form via `TypeOps`).
21. `TypeUnpicklerTest` , decoding a `MATCHtype` returns `MatchType(bound, scrutinee, cases)`. Fixture: `type Tup[X] = X match { case Int => String; case _ => Int }` compiled into the fixture module; assert `cases.size == 2` and `scrutinee` is the named type parameter `X`.
22. `TypeUnpicklerTest` , decoding a `CONSTANTtype` wrapping integer `42` returns `ConstantType(IntConst(42))`.
23. `TypeUnpicklerTest` , decoding `RECtype` with `RECthis` produces a cycle-safe result (no stack overflow).
24. `TypeUnpicklerTest` , a cross-file `TYPEREFin` whose FQN is not in the local `addrMap` produces an `UnresolvedRef` placeholder (verified by checking `Pass1Result.placeholders.nonEmpty`).

**Total tests**: 24

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.TypeArenaTest kyo.TypeUnpicklerTest kyo.TypeOpsTest'
```
Plus:
```
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

**Supervisor checks**:
- `TypeArena.scala`, `TypeOps.scala`, `TypeUnpickler.scala`, `UnresolvedRef.scala` all present.
- The `Type.Rec` cycle test (test 4) completes without stack overflow.
- `TypeOps.applied` normalization covers all four cases (FunctionN, TupleN, ContextFunctionN, Array).
- All 24 tests pass on JVM; JS and Native compile.
- `AstUnpickler.scala` now uses `TypeUnpickler.readType` for parent and signature positions.

---

## Phase 5: Classfile reader

**Dependencies**: Phase 4 (`TypeArena`, `TypeOps` for `Type.Array` normalization, `Interner`, `Flags` with `fromJvmAccessFlags`, `ByteView`, `Symbol.makeSymbol`; classfile symbols populate the same `addrMap`/arena as TASTy symbols for Phase C merge compatibility). Depends on Phase 4 ONLY for the `Type` ADT case classes that `JavaSignatures` constructs from JVM generic-signature strings. Does not use Phase 4's `TypeUnpickler` (TASTy-specific) or `TypeArena` hash-consing infrastructure (the classfile reader builds `Type` values directly; canonicalization across files happens in Phase 7's classpath-level intern, not in Phase 5).

**Non-Goals for Phase 5**: `module-info.class`. The classfile unpickler skips any file whose simple name is `module-info.class` entirely. JPMS module declarations are out of v1 scope (DESIGN.md §1). Rationale: kyo-reflect reads class metadata; module-info declares which packages a module exports, which is a runtime ClassLoader concern, not a metadata concern. JDK classes resolved via `jrt:/` work without parsing module-info because the URI scheme encodes the module path. Impl agents must not add module-info handling.

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileFormat.scala` , constant-pool tag constants (`CONSTANT_Utf8 = 1`, `CONSTANT_Integer = 3`, ..., `CONSTANT_InvokeDynamic = 18`), access flag masks (`ACC_PUBLIC = 0x0001`, ..., `ACC_RECORD = 0x0010`, `ACC_ENUM = 0x4000`), attribute name strings.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ConstantPool.scala` , `final class ConstantPool(entries: Array[ConstantPool.Entry])` with lazy UTF-8 decode on `Utf8(offset, length)` entries; `def utf8(idx: Int): String`, `def classRef(idx: Int): String` (binary name), `def methodType(idx: Int): (String, String)`, `def fieldRef(idx: Int): (String, String, String)`; entries allocated by `ConstantPool.read(view: ByteView, interner: Interner): ConstantPool < Abort[ReflectError]`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/JavaSignatures.scala` , `object JavaSignatures` with `def parseFieldSignature(sig: String, interner: Interner, addrMap: Map[Int, Reflect.Symbol]): Reflect.Type < Abort[ReflectError]`, `def parseMethodSignature(sig: String, interner: Interner, addrMap: Map[Int, Reflect.Symbol]): Reflect.Type.Function < Abort[ReflectError]`, `def parseClassSignature(sig: String, ...): (Chunk[Reflect.Type], Chunk[Reflect.Type]) < Abort[ReflectError]`; parses the JVM generic-signature grammar (§JVMS 4.7.9.1): TypeParams, ClassTypeSignature, ArrayTypeSignature, TypeVariableSignature, BaseType, ReferenceTypeSignature, WildcardIndicator; produces `Reflect.Type` values using `TypeOps.mkArray` for arrays, `Type.Applied` for parameterized types, `Type.Wildcard(lower, upper)` for bounded wildcards (field order is `lower` then `upper`; `+T` covariant maps to `Wildcard(Nothing, T)`; `-T` contravariant maps to `Wildcard(T, Object)`; `*` unbounded maps to `Wildcard(Nothing, Object)`), `Type.Named` for resolved class refs (via `addrMap` lookup or `UnresolvedRef`).
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileUnpickler.scala` , `object ClassfileUnpickler` with `def read(bytes: Array[Byte], interner: Interner, arena: TypeArena, home: ClasspathRef): ClassfileResult < Abort[ReflectError]`; reads magic (`0xCAFEBABE`), version, constant pool, access flags, this/super class, interfaces, fields (each to `Reflect.Symbol` with `kind = Val | Var | Field`), methods (each to `Reflect.Symbol` with `kind = Method`), class-level attributes (`InnerClasses`, `Signature`, `Record`, `RuntimeVisibleAnnotations`, `RuntimeInvisibleAnnotations`, `EnclosingMethod`); populates `JavaMetadata` on each symbol; returns `ClassfileResult(classSymbol: Reflect.Symbol, innerClassTable: Map[String, (String, String)], symbols: Chunk[Reflect.Symbol], arena: TypeArena)`.
- `kyo-reflect/shared/src/test/scala/kyo/ClassfileReaderTest.scala` , tests loading real classfiles from the test classpath.
- `kyo-reflect/shared/src/test/scala/kyo/JavaSignaturesTest.scala` , tests for generic-signature parser.

**Files to modify**: none.

**Files to delete**: none.

**Public API additions**: none.

**Public API modifications**: none.

**Public API removals**: none.

**Tests**:
1. `ClassfileReaderTest` , reading `Object.class` from the test classpath produces a symbol with `kind == SymbolKind.Class`, `name.asString == "Object"`, `flags.contains(Flag.JavaDefined)`.
2. `ClassfileReaderTest` , `java.lang.String.length()` method: `declarations` of the String symbol contains a symbol with `name.asString == "length"` and `kind == SymbolKind.Method`.
3. `ClassfileReaderTest` , `java.lang.String` has `parents` containing a symbol whose name includes `"Object"`.
4. `ClassfileReaderTest` , reading `java.util.ArrayList.class` produces `typeParams` with at least one `TypeParam` symbol.
5. `ClassfileReaderTest` , `ACC_INTERFACE` classfile produces a symbol with `kind == SymbolKind.Trait`.
6. `ClassfileReaderTest` , `ACC_ENUM` classfile (e.g., `java.util.concurrent.TimeUnit`) produces `flags.contains(Flag.Enum)`.
7. `ClassfileReaderTest` , a static field produces a symbol with `kind == SymbolKind.Field` and `flags.contains(Flag.JavaDefined)`.
8. `ClassfileReaderTest` , a `final` non-static field produces `kind == SymbolKind.Val`.
9. `ClassfileReaderTest` , a mutable non-final field produces `kind == SymbolKind.Var`.
10. `ClassfileReaderTest` , `ClassfileUnpickler.read` on a byte array with wrong magic `0xDEADBEEF` produces `Abort.fail(ReflectError.ClassfileFormatError(...))`.
11. `ClassfileReaderTest` , `javaSpecific` is `Present` for a Java-sourced symbol and `Absent` for a TASTy-sourced symbol.
12. `ClassfileReaderTest` , `throwsTypes` in `JavaMetadata` is non-empty for a method declared `throws IOException`.
13. `JavaSignaturesTest` , `parseFieldSignature("Ljava/util/List<Ljava/lang/String;>;", ...)` produces `Applied(Named(listSym), Chunk(Named(strSym)))`.
14. `JavaSignaturesTest` , `parseFieldSignature("[I", ...)` produces `Type.Array(Named(intSym))`.
15. `JavaSignaturesTest` , `parseFieldSignature("[[Ljava/lang/String;", ...)` produces `Array(Array(Named(strSym)))`.
16. `JavaSignaturesTest` , wildcard `Ljava/util/List<+Ljava/lang/Number;>;` produces `Applied(Named(listSym), Chunk(Wildcard(Named(nothingSym), Named(numberSym))))`.
17. `JavaSignaturesTest` , wildcard `Ljava/util/List<-Ljava/lang/Number;>;` produces `Applied(Named(listSym), Chunk(Wildcard(Named(numberSym), Named(objectSym))))`.
18. `JavaSignaturesTest` , raw type `Ljava/util/List;` (no angle brackets) produces `Named(listSym)` (no `Applied` wrapper).
19. `JavaSignaturesTest` , `parseMethodSignature("<T:Ljava/lang/Object;>(Ljava/util/List<TT;>;)TT;", ...)` produces a `Function` type with one `TypeParam` for `T`.
20. `JavaSignaturesTest` , a corrupt signature string (unclosed `<`) produces `Abort.fail(ReflectError.ClassfileFormatError(...))`.

**Total tests**: 20

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.ClassfileReaderTest kyo.JavaSignaturesTest'
```
Plus:
```
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

**Supervisor checks**:
- `ClassfileFormat.scala`, `ConstantPool.scala`, `JavaSignatures.scala`, `ClassfileUnpickler.scala` all present in `kyo/internal/reflect/classfile/`.
- `ClassfileUnpickler.read` returns `JavaMetadata`-populated symbols (test 11 passes).
- `JavaSignatures` handles all four wildcard and array forms (tests 14-18 pass).
- All 20 tests pass on JVM; JS and Native compile. Note: Phase 5 tests that load JDK classfiles (tests 1-12) assume JDK 25 and access `java/lang/Object.class` via `getClass.getClassLoader.getResourceAsStream("java/lang/Object.class")`; this must resolve at test runtime. For `jrt:/` URI-based tests (JVM `JvmFileSource`), `FileSystems.getFileSystem(URI.create("jrt:/"))` is called; this requires a JDK (not a JRE) to be present, which is the case for the kyo build (Temurin 25 required per the project's JDK requirement). Tests that cannot tolerate the `jrt:/` filesystem dependency use pre-baked `.class` bytes from `kyo-reflect-fixtures` as a fallback.

---

## Phase 5b: Java/Scala unification

**Dependencies**: Phase 5 (classfile reader producing Java symbols; Phase 4 type model; Phase 3 TASTy symbol pass; the unification work is glue between the two decoders , FQN canonicalization, `Type.Array` normalization path, `JavaMetadata` wiring, SymbolKind matrix completeness tests).

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/FqnCanonicalizer.scala` , `object FqnCanonicalizer` with `def toFullName(binaryName: String, innerClassTable: Map[String, (String, String)]): String` implementing the InnerClasses-attribute heuristic (DESIGN.md §10): consult table first; if found, recurse on outer and append `.innerSimpleName`; otherwise treat `$` as literal; `def toBinaryName(fullName: String, innerClassTable: Map[String, (String, String)]): String` (reverse mapping).
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/JavaAnnotationUnpickler.scala` , `object JavaAnnotationUnpickler` with `def readAnnotations(view: ByteView, constantPool: ConstantPool, interner: Interner, addrMap: Map[Int, Reflect.Symbol]): Chunk[Reflect.JavaAnnotation] < Abort[ReflectError]`; decodes `RuntimeVisibleAnnotations` and `RuntimeInvisibleAnnotations` attribute structures into `Reflect.JavaAnnotation` values.
- `kyo-reflect/shared/src/test/scala/kyo/JavaSymbolTest.scala` , tests for unified Java symbol surface.
- `kyo-reflect/shared/src/test/scala/kyo/UnifiedModelTest.scala` , tests for mixed Java/Scala queries.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileUnpickler.scala` , wire `FqnCanonicalizer.toFullName` for `sym.fullName` computation; wire `JavaAnnotationUnpickler.readAnnotations` for annotation population; add `Record` attribute parsing (JVMS `Record` attribute: list of components each with name, descriptor, optional Signature, RuntimeVisibleAnnotations; stored in `JavaMetadata.recordComponents`); set `Flag.JavaRecord` when `ACC_RECORD` present.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Flags.scala` , add `fromJvmAccessFlags` entries for `ACC_RECORD = 0x0010` → `Flag.JavaRecord` and `ACC_ENUM = 0x4000` → `Flag.Enum`.

**Files to delete**: none.

**Public API additions**: none.

**Public API modifications**:
- `Reflect.Symbol.fullName` for Java-sourced symbols now returns canonicalized dotted form (e.g., `"java.util.Map.Entry"` not `"java.util.Map$Entry"`).
- `Reflect.Symbol.binaryName` returns JVM binary form (`"java/util/Map$Entry"`).

**Public API removals**: none.

**Tests**:
1. `JavaSymbolTest` , `sym.fullName.asString` for a Java inner class (loaded from `java.util.Map$Entry.class`) returns `"java.util.Map.Entry"` (dotted, not `$`).
2. `JavaSymbolTest` , `sym.binaryName` for the same class returns `"java/util/Map$Entry"` (JVM form).
3. `JavaSymbolTest` , a top-level Java class with a literal `$` in its name (e.g., constructed via a synthetic bytes fixture) has its `fullName` preserve the `$` as a literal character.
4. `JavaSymbolTest` , `sym.isJava` is `true` for Java-sourced symbols and `false` for TASTy-sourced symbols.
5. `JavaSymbolTest` , `sym.javaSpecific` is `Present` for Java symbols and `Absent` for Scala symbols.
6. `JavaSymbolTest` , `JavaMetadata.throwsTypes` is non-empty for a method declared `throws Exception` (verify using a fixture classfile bytes with known throws clause).
7. `JavaSymbolTest` , `JavaMetadata.accessFlags` for `java.lang.String` has the `ACC_FINAL` bit set (0x0010 for classfile version, i.e., `flags.contains(Flag.Final)`).
8. `JavaSymbolTest` , a Java record class (fixture bytes with `ACC_RECORD` flag + `Record` attribute) produces `Flag.JavaRecord` in `flags` and non-empty `recordComponents` in `javaSpecific`.
9. `JavaSymbolTest` , `JavaMetadata.annotations` for a class annotated with a runtime-visible annotation contains a `JavaAnnotation` with the correct annotation class name.
10. `JavaSymbolTest` , `JavaMetadata.enclosingMethod` is `Present` for an anonymous class generated inside a method (fixture bytes); assert `enclosingMethod.get.methodName.asString == "enclosingMethodFixture"` where `enclosingMethodFixture` is the name of the method in the fixture classfile bytes that encloses the anonymous class.
11. `UnifiedModelTest` , `SymbolKind.Package` appears for both Java and Scala package symbols.
12. `UnifiedModelTest` , `SymbolKind.Class` appears for Java `class` and Scala `class`.
13. `UnifiedModelTest` , `SymbolKind.Trait` appears for Java `interface` and Scala `trait`.
14. `UnifiedModelTest` , `SymbolKind.Object` appears only for Scala `object`; no Java symbol has `kind == Object`.
15. `UnifiedModelTest` , `SymbolKind.TypeAlias`, `OpaqueType`, `AbstractType` appear only for TASTy-sourced symbols.
16. `UnifiedModelTest` , `Type.Array(elem)` is returned for both Java `int[]` and Scala `Array[Int]` (both reach `TypeOps.mkArray` path).
17. `UnifiedModelTest` , a Scala `case class` decoded from TASTy has `flags.contains(Flag.Case)`.
18. `UnifiedModelTest` , full SymbolKind matrix: for each `SymbolKind` case except `Unresolved`, there exists at least one symbol in the fixture/test classpath with that kind (covers 13 of 14 cases). `SymbolKind.Unresolved` is tested in Phase 7 `SymbolResolutionTest` t21 using a synthesized partial-classpath fixture (a TASTy file that references a class whose `.class`/`.tasty` file is absent from the roots). Phase 5b does not construct `Unresolved` symbols; their creation requires the Phase 7 classpath resolution pipeline.

**Total tests**: 18

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.JavaSymbolTest kyo.UnifiedModelTest'
```
Plus:
```
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

**Supervisor checks**:
- `FqnCanonicalizer.scala` and `JavaAnnotationUnpickler.scala` are present.
- Test 1 (`Map.Entry` dotted form) passes.
- Test 8 (Java record) passes using fixture bytes , confirm the fixture bytes used in the test are checked in as a resource under `kyo-reflect-fixtures`.
- Test 18 (full SymbolKind matrix) passes, proving all 14 `SymbolKind` cases are covered.
- All 18 tests pass on JVM; JS and Native compile.

---

## Phase 6: Reflect.Reads derivation macro

**Dependencies**: Phase 5b (the full `Symbol` API is now wired; `FieldSet` constants in `Reflect.FieldSet` already present in skeleton; `Reads` trait in skeleton with stub `derived`; the macro inspects the `Reflect.Symbol` API surface which must be stable before macro implementation starts; `TypeArena` and resolved symbol graph are stable enough for the `touchedFields` analysis to be meaningful).

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/ReflectMacro.scala` , `object ReflectMacro` with `def derivedImpl[A](using q: Quotes, t: Type[A]): Expr[Reflect.Reads[A]]`; implements the four deliverables from DESIGN.md §13:
  1. Product-type (case class) derivation via `quotes.reflect.TypeRepr` inspection (not `Mirror`); for each field, `Expr.summon[Reflect.Reads[FieldType]]` or direct accessor emit for primitive fields. If `TypeRepr` does not produce a `classSymbol` for the inspected type, the macro calls `report.errorAndAbort("requires a named class symbol")`.
  2. `symbolKinds` inference applies a single rule with two branches: if the `Reads` body uses only pure accessor fields (`name`, `flags`, `kind`, `fullName`, `binaryName`, `isJava`, `annotations`, `javaSpecific`) then `Set(SymbolKind.values*)` (all kinds accepted); if the body accesses any structural field (`parents`, `declarations`, `typeParams`) then `Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object)` (narrowed to kinds that carry those fields).
  3. `touchedFields` static analysis: `Trees.traverseGoto` over the generated body, match `Select(q, methodName)` where `q.tpe <:< TypeRepr.of[Reflect.Symbol]`; union `FieldSet` bits; `Trees.exists` pre-check guard per hygiene rule 1.
  4. Recursive case classes: `lazy val instance: Reads[Node]` emission per §13.
  5. Sum-type guard: `report.errorAndAbort` with clear message pointing at hand-written worked example.
  6. Higher-kinded guard: detect abstract type params at expansion and `report.errorAndAbort`.
  7. Hygiene guard 2: when traversing `Match` nodes for touched-fields analysis, skip `.pattern`; visit only scrutinee, guard, rhs.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/ReadsInstances.scala` , built-in `Reflect.Reads` instances object (named `ReadsInstances` to avoid basename collision with the public `Reflect.Reads` trait in `Reflect.scala`): `given nameReads: Reads[Reflect.Name]`, `given flagsReads: Reads[Reflect.Flags]`, `given kindReads: Reads[Reflect.SymbolKind]`, `given typeReads: Reads[Reflect.Type]`, `given symbolReads: Reads[Reflect.Symbol]`, `given booleanReads: Reads[Boolean]`, `given intReads: Reads[Int]`, `given longReads: Reads[Long]`, `given stringReads: Reads[String]`, `given chunkReads[T](using Reads[T]): Reads[Chunk[T]]`, `given maybeReads[T](using Reads[T]): Reads[Maybe[T]]`, tuple Reads for arities 2-22.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/TouchedFields.scala` , `object TouchedFields` with `def analyze(readBody: quotes.reflect.Term): Reflect.FieldSet` (the tree-walk logic extracted from `ReflectMacro` for testability).
- `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala` , tests for macro derivation.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` , replace `inline def derived[A]: Reads[A] = scala.compiletime.error(...)` with `inline def derived[A]: Reads[A] = ${ kyo.internal.ReflectMacro.derivedImpl[A] }`.

**Files to delete**: none.

**Public API additions**:
- `Reflect.Reads.derived[A]` now works (previously was a compile error stub).

**Public API modifications**: none (signature unchanged, implementation replaces `compiletime.error`).

**Public API removals**: none.

**Tests**:
1. `ReadsDerivationTest` , `case class Simple(name: Reflect.Name, flags: Reflect.Flags) derives Reflect.Reads` compiles.
2. `ReadsDerivationTest` , derived `Reads[Simple]` has `touchedFields` containing `FieldSet.Name | FieldSet.Flags` and no other bits.
3. `ReadsDerivationTest` , derived `Reads[Simple].symbolKinds` is `Set(SymbolKind.values*)` (all kinds accepted since only pure accessors used).
4. `ReadsDerivationTest` , derived `Reads[Simple].needsBodies` is `false`.
5. `ReadsDerivationTest` , `Reads[Simple].read(sym)` returns a `Simple` with `name == sym.name` and `flags == sym.flags` for a fixture symbol.
6. `ReadsDerivationTest` , `case class WithParents(name: Reflect.Name, parents: Chunk[Reflect.Type]) derives Reflect.Reads` compiles and `touchedFields` contains `FieldSet.Name | FieldSet.Parents`.
7. `ReadsDerivationTest` , `Reads[WithParents].symbolKinds` is `Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object)` (narrowed because `parents` accessor accessed).
8. `ReadsDerivationTest` , `case class Node(name: Reflect.Name, children: Chunk[Node]) derives Reflect.Reads` compiles (recursive case class handled via `lazy val`).
9. `ReadsDerivationTest` , deriving `Reads` on a `sealed trait` produces a compile error containing the phrase "hand-written".
10. `ReadsDerivationTest` , deriving `Reads` on `case class Foo[A](xs: Chunk[A]) derives Reflect.Reads` produces a compile error about abstract type parameter.
11. `ReadsDerivationTest` , `case class Custom(special: Int, name: Reflect.Name) derives Reflect.Reads` given `given Reads[Int]` in scope: the derived instance uses the given `Reads[Int]` for the `special` field.
12. `ReadsDerivationTest` , `Reads[Chunk[Reflect.Symbol]].read(sym)` (built-in chunk instance) maps over declarations and returns a Chunk of Symbols.
13. `ReadsDerivationTest` , `Reads[Maybe[Reflect.Symbol]].read(sym)` returns `Absent` for an unresolved symbol's companion (which returns `Absent`).
14. `ReadsDerivationTest` , `Reads[(Reflect.Name, Reflect.Flags)]` tuple reads both fields.
15. `ReadsDerivationTest` , transitive `touchedFields`: `case class Outer(inner: Inner, name: Reflect.Name) derives Reflect.Reads` where `Inner` reads `parents` , `Outer`'s `touchedFields` includes `FieldSet.Parents`.
16. `ReadsDerivationTest` , a `Match` node in a hand-written `Reads.read` body containing a `Bind` pattern does not cause macro hygiene assertions to fire (hygiene guard 2 test: skip `.pattern`); additionally assert that the derived `touchedFields` for this `Reads` instance excludes any `FieldSet` bits that appear only in the `Bind` pattern and not in the guard or RHS of the match cases.
17. `ReadsDerivationTest` , all built-in `Reads` instances resolve implicitly: `summon[Reads[Reflect.Name]]`, `summon[Reads[Reflect.Flags]]`, `summon[Reads[Reflect.SymbolKind]]`, `summon[Reads[Reflect.Type]]`, `summon[Reads[Reflect.Symbol]]` all compile.
18. `ReadsDerivationTest` , `Reads.read` on a real fixture symbol (from `AstUnpicklerTest` fixture) returns the expected product value for a simple two-field case class.

**Total tests**: 18

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.ReadsDerivationTest'
```
Plus:
```
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

**Supervisor checks**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/ReflectMacro.scala` is present and wired in `Reflect.scala`.
- `Reads.derived` in `Reflect.scala` is `${ kyo.internal.ReflectMacro.derivedImpl[A] }`, not the old `compiletime.error`.
- Test 9 (sum-type produces compile error with "hand-written") passes.
- Test 10 (abstract type param produces compile error) passes.
- All 18 tests pass on JVM; JS and Native compile.
- The example files in `kyo-reflect/shared/src/main/scala/kyo/reflect/examples/` still compile (no regressions to the public API).

---

## Phase 6b: Record interop

**Dependencies**: Phase 6 (`Reads` macro working, built-in instances present; `Reflect.symbolToRecord` stub in skeleton needs replacing; `kyo.Record` from kyo-data must be available , confirm `kyo-reflect` already transitively depends on `kyo-data` via `kyo-core`; the `symbolToRecord` field-to-accessor table in DESIGN.md §12 must be implemented).

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/RecordReads.scala` , `object RecordReads` with `given recordReads[F](using fields: Fields[F]): Reflect.Reads[Record[F]]` built-in instance; delegates to `Reflect.symbolToRecord[F](sym)` for the `read` body; `touchedFields` is the union of touched fields for all field types in `F`; `symbolKinds` is `Set(SymbolKind.values*)`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/SymbolToRecordMacro.scala` , `object SymbolToRecordMacro` with `def symbolToRecordImpl[F](sym: Expr[Reflect.Symbol])(using q: Quotes, t: Type[F]): Expr[Record[F] < (Sync & Abort[ReflectError])]`; walks the field intersection `F`; for each field name maps to the accessor per the table in DESIGN.md §12; emits `for/yield` threading `Sync & Abort[ReflectError]` for effectful accessors; emits direct reads for pure accessors; `report.errorAndAbort` on unrecognized field names; validates field value types match expected accessor return types.
- `kyo-reflect/shared/src/test/scala/kyo/RecordInteropTest.scala` , tests for `symbolToRecord` and `Reads[Record[F]]`.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` , replace `inline def symbolToRecord[F](sym: Symbol): Any < ...` with `inline def symbolToRecord[F](sym: Symbol): Record[F] < (Sync & Abort[ReflectError]) = ${ kyo.internal.SymbolToRecordMacro.symbolToRecordImpl[F]('sym) }`.

**Files to delete**: none.

**Public API additions**:
- `Reflect.symbolToRecord[F]` now works (previously was a compile error stub).
- `given Reflect.Reads[Record[F]]` built-in instance.

**Public API modifications**: none.

**Public API removals**: none.

**Tests**:
1. `RecordInteropTest` , `type View = "name" ~ Reflect.Name & "flags" ~ Reflect.Flags` and `Reflect.symbolToRecord[View](sym)` compiles and returns a `Record[View]`.
2. `RecordInteropTest` , `record.get("name")` on the result of test 1 equals `sym.name` for a fixture symbol.
3. `RecordInteropTest` , `record.get("flags")` on the result of test 1 equals `sym.flags` for a fixture symbol.
4. `RecordInteropTest` , `type WithParents = "name" ~ Reflect.Name & "parents" ~ Chunk[Reflect.Type]` and `Reflect.symbolToRecord[WithParents](sym)` for a class symbol returns non-empty parents.
5. `RecordInteropTest` , `type WithDecls = "declarations" ~ Chunk[Reflect.Symbol]` and the result has non-empty declarations for a class symbol.
6. `RecordInteropTest` , `type WithCompanion = "companion" ~ Maybe[Reflect.Symbol]` for a case class with a companion object returns `Present(companionSym)`.
7. `RecordInteropTest` , `type WithJavaSpecific = "javaSpecific" ~ Maybe[Reflect.JavaMetadata]` for a Java symbol returns `Present(meta)`.
8. `RecordInteropTest` , `type WithIsJava = "isJava" ~ Boolean` returns `true` for a Java symbol and `false` for a Scala symbol.
9. `RecordInteropTest` , `type BadField = "nonexistent" ~ String` produces a compile error with `report.errorAndAbort`.
10. `RecordInteropTest` , `type TypeMismatch = "name" ~ Int` (wrong type for `name`) produces a compile error.
11. `RecordInteropTest` , `summon[Reflect.Reads[Record["name" ~ Reflect.Name & "kind" ~ Reflect.SymbolKind]]]` resolves (built-in `Reads[Record[F]]`).
12. `RecordInteropTest` , `Reflect.Reads[Record[F]].touchedFields` for `"name" ~ Name & "parents" ~ Chunk[Type]` contains `FieldSet.Name | FieldSet.Parents`.
13. `RecordInteropTest` , `Reflect.symbolToRecord` used inside a `derives Reflect.Reads` case class with a `Record[F]` field compiles: `case class Wrap(api: Record["name" ~ Reflect.Name], notes: String) derives Reflect.Reads`.
14. `RecordInteropTest` , the `Record.stage[T].using[TypeClass]` bridging idiom from DESIGN.md §11 compiles: given `trait Printer[A] { def print(a: A): String }` with instances for `Reflect.Name` and `Reflect.Flags`, `sig.mapFields(...)` on a `Record["name" ~ Name & "flags" ~ Flags]` produces a `Record["name" ~ String & "flags" ~ String]`.

**Total tests**: 14

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.RecordInteropTest'
```
Plus:
```
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

**Supervisor checks**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/SymbolToRecordMacro.scala` and `RecordReads.scala` present.
- `Reflect.symbolToRecord` in `Reflect.scala` is `${ kyo.internal.SymbolToRecordMacro.symbolToRecordImpl[F]('sym) }`.
- Tests 9 and 10 (compile errors on bad field name and type mismatch) pass.
- Test 13 (Record field inside derives Reads) passes.
- All 14 tests pass on JVM; JS and Native compile.

---

## Phase 7: Query API + file sources + snapshot cache + cross-platform orchestration

**Dependencies**: Phases 1-6b. This phase wires all pieces into the real `Classpath.open` implementation, replaces all stubs, and delivers Phase A/B/C parallel orchestration (DESIGN.md §15), file sources per platform (DESIGN.md §14), snapshot KRFL read/write (DESIGN.md §16), and the `Query` combinator API (DESIGN.md §12).

**Files to produce**:

*Query layer*:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala` , `final class Classpath private[reflect] (private val state: AtomicRef[Classpath.State])` with `State.Building`, `State.Ready`, `State.Closed` sealed enum; `checkOpen` method; `Cache` for FQN lookups via `Cache.memo` from `kyo-core/shared/src/main/scala/kyo/Cache.scala` (signature: `def memo[A, B, S](capacity: Int)(f: A => B < S): A => B < (S & Async)` ); `findClass`, `findPackage`, `packages`, `topLevelClasses`, `errors` implementations; `Scope.ensure` finalizer that CAS-transitions to `Closed`; KRFL snapshot integration; Phase A/B/C orchestration call sequence.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Resolver.scala` , `object Resolver` with `def resolve(fqn: String, cp: Classpath): Maybe[Reflect.Symbol] < (Sync & Abort[ReflectError])` using `Cache.memo`; deduplicates concurrent callers via `kyo.Promise` (from `kyo-core/shared/src/main/scala/kyo/Promise.scala`) per kyo-core's `Cache` semantics; error enumeration: produces `ClasspathClosed` only when the classpath is closed; a missing FQN returns `Absent` (never an error in soft-fail mode).
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Query.scala` , `final class Query[A] private[reflect] (...)` with `filter`, `where`, `withFlag`, `named`, `extending`, `map`, `stream`, `run` per DESIGN.md §12; `stream` returns `Stream[A, Sync & Abort[ReflectError]]`; `run` returns `Chunk[A] < (Sync & Abort[ReflectError])`; combinators compose into an intermediate plan evaluated on `.run`/`.stream`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/FileSource.scala` , shared `trait FileSource` with `read(path: String): Array[Byte] < (Sync & Abort[ReflectError])`, `list(dir: String, suffix: String): Chunk[String] < (Sync & Abort[ReflectError])`, `exists(path: String): Boolean < Sync`. Note: `exists` returns `Boolean < Sync` (no `Abort`) rather than `Boolean < (Sync & Abort[ReflectError])` because callers use it as a fast short-circuit guard before attempting `read`; a non-existent path is a valid `false` result, not an error, so absorbing any I/O exception into `false` keeps the call-site effect row lighter. Callers that need an error on absence use `read` directly.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` , `object ClasspathOrchestrator` with `def open(roots: Seq[String], strict: Boolean, source: FileSource, cp: Classpath): Unit < (Sync & Abort[ReflectError])` implementing Phase A (`Async.foreach` header sweep with inner `Scope.run`), Phase B (parallel body decode with per-fiber `TypeArena`), Phase C (single-threaded merge + placeholder resolution + `Classpath.state` CAS to `Ready`).

*Platform file sources*:
- `kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JvmFileSource.scala` , `object JvmFileSource extends FileSource`: `read` via `java.nio.file.Files.readAllBytes`; `jrt:/` URI support for JDK modules (detect `System.getProperty("java.home")` + `FileSystems.getFileSystem(URI.create("jrt:/"))`); `list` walks JAR entries for `.tasty` and `.class` suffix.
- `kyo-reflect/js/src/main/scala/kyo/internal/reflect/query/JsFileSource.scala` , `object JsFileSource extends FileSource`: on Node.js (`js.typeOf(js.Dynamic.global.process) != "undefined" && js.typeOf(js.Dynamic.global.process.platform) != "undefined"`): `read` uses `fs.readFileSync(path)` which returns a Node.js `Buffer`; the `Buffer` is then read as an `Int8Array` view and copied element-by-element into a Scala `Array[Byte]`; `list` via `fs.readdirSync`; on browser: `read` and `list` return `Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))`.
- `kyo-reflect/native/src/main/scala/kyo/internal/reflect/query/NativeFileSource.scala` , `object NativeFileSource extends FileSource`: `read` via POSIX `open`/`read`/`close` FFI (Scala Native `@extern`); `list` via `opendir`/`readdir`/`closedir` FFI; follows symlinks via `stat`.

*Snapshot*:

**Excluded from Phase 7 snapshot**: incremental snapshot refresh. Any change to any input file produces a different FNV-1a digest (of sorted `(path, mtime, size)` tuples) and triggers a full re-decode and fresh snapshot write. Rationale: incremental refresh would require per-file digest tracking, partial-snapshot reuse, cascading invalidation of cross-file type references, and stale-arena handling; estimated 2-3x complexity versus full digest for approximately 150ms saving per single-file-change build. The full-digest design is provably correct; incremental introduces invalidation bugs that cannot be easily tested. Revisit in v2 if real usage shows the cost is high enough. Impl agents must not add incremental refresh to Phase 7.

- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotFormat.scala` , KRFL constants: magic `"KRFL"` as 4-byte LE, version encoding, section IDs (`NAMES`, `SYMBOLS`, `TYPES`, `TYPES_EXTRA`, `PARENTS`, `MEMBERS`, `FILES`, `BODY_BYTES`, `ERRORS`), byte-order flag; `object SnapshotFormat`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotWriter.scala` , `object SnapshotWriter` with `def write(cp: Classpath, cacheDir: String, digest: Array[Byte], source: FileSource): Unit < (Sync & Abort[ReflectError])`; writes sections in order per DESIGN.md §16; uses tmp-file + atomic rename strategy (`${digest}-${pid}-${nonce}.krfl` → `${digest}.krfl`); little-endian byte order; includes all sections; excludes `home` from Symbol serialization. I/O errors during snapshot write (e.g., unwritable cache directory, out-of-space) produce `ReflectError.SnapshotIoError(cause: String)`. A missing or unreadable cache root (the directory does not exist and cannot be created) also produces `ReflectError.SnapshotIoError`.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotReader.scala` , `object SnapshotReader` with `def read(path: String, source: FileSource, cp: Classpath): Unit < (Sync & Abort[ReflectError])`; validates magic, version, byte-order flag; reads section index; reconstructs `Symbol` graph, `TypeArena`, name table; assigns `home = cp` to all symbols; emits `ReflectError.SnapshotVersionMismatch` on major-version mismatch; falls through to `open` on minor-version too-new. JVM preferred read path: `java.lang.foreign.Arena.ofShared().allocate(size, 1)` returns a `MemorySegment`; the file bytes are loaded into the segment via `MemorySegment.copy`; body slices reference offsets into the segment directly (zero-copy); the `Arena` is closed explicitly when the enclosing `Scope` finalizes (deterministic release, not GC-dependent). Rationale: JDK 25 makes `MemorySegment` available; aligned with `kyo-offheap`'s `Memory[Byte]` API; supports snapshots larger than 2 GB cleanly; explicit Arena close produces deterministic release rather than relying on GC finalization. Native uses POSIX `mmap` FFI. JS (Node.js) reads into an `Array[Byte]` fallback; JS browser degrades to the `open` path (no snapshot read).
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/DigestComputer.scala` , `object DigestComputer` with `def compute(roots: Seq[String], source: FileSource): Array[Byte] < (Sync & Abort[ReflectError])` using FNV-1a 64-bit hash of sorted `(path, mtime, size)` tuples (approximately 30 LOC of pure Scala, identical on all platforms, no external dependency; hash is non-cryptographic and is sufficient for cache-invalidation purposes; users who need cryptographic-strength digests may supply a custom digest function via a future `Classpath.openCached(roots, digest = customDigest)` hook, which is NOT part of v1); `def computeParanoid(roots: Seq[String], source: FileSource): Array[Byte] < (Sync & Abort[ReflectError])` using FNV-1a 64-bit hash of file contents. Per-platform mtime source: JVM and Native use real filesystem `mtime` via `Files.getLastModifiedTime` / POSIX `stat`; JS-Node uses `fs.statSync(path).mtimeMs`; JS-browser: `openCached` is unavailable on browser (no filesystem access); calling it on browser returns `Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))`.
- `kyo-reflect/shared/src/main/scala/kyo/reflect/Snapshot.scala` , public `object Reflect.Snapshot` with `def evictOlderThan(d: Duration): Unit < (Sync & Scope)` that walks the cache directory and removes snapshots and tmp files older than `d`. On JS-browser this method is an explicit no-op (returns immediately without error) because there is no filesystem cache on the browser platform. The platform dispatch is handled by a `FileSource`-level guard: if the platform's `FileSource` does not support listing (browser path), `evictOlderThan` skips the walk silently.

*Tests*:
- `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala` , tests for `findClass`, `findPackage`, `query`, `errors`, `topLevelClasses`, `packages`.
- `kyo-reflect/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala` , tests for KRFL write-then-read round-trip.
- `kyo-reflect/shared/src/test/scala/kyo/SymbolResolutionTest.scala` , tests for concurrent FQN resolution via `Cache.memo`.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` , replace all `stub(...)` calls in `Classpath.open`, `Classpath.openCached`, extension methods `findClass`, `findPackage`, `packages`, `topLevelClasses`, `errors` with real implementations delegating to `ClasspathOrchestrator` and `Classpath` internal class.
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` , update `Classpath` opaque type to alias the new internal `Classpath` class; update all extension methods.
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` , add `extension (cp: Classpath) def query[A](using Reads[A]): Query[A]` implementation.
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` , add `extension (cp: Classpath) def findClassByBinary(binaryName: String): Maybe[Symbol] < (Sync & Abort[ReflectError])` implementation delegating to `findClass` after canonicalization.

**Files to delete**: none (stubs replaced in-place, not in separate files).

**Public API additions**:
- `Reflect.Classpath.open` , real implementation.
- `Reflect.Classpath.openCached` , real implementation with snapshot cache.
- `Reflect.Classpath.fromPickles` , real implementation for browser/in-memory path.
- `Reflect.Classpath.findClass`, `findPackage`, `packages`, `topLevelClasses`, `errors` , real implementations.
- `extension (cp: Classpath) def query[A](using Reads[A]): Query[A]` , new combinator entry point.
- `Reflect.Snapshot.evictOlderThan` , new public method.
- `def findClassByBinary(binaryName: String): Maybe[Symbol] < (Sync & Abort[ReflectError])` , separate entry point for the JVM binary-name form (e.g., `"java/util/Map$Entry"`); implemented by canonicalizing the binary name to a dotted FQN via `FqnCanonicalizer.toFullName` then delegating to `findClass`.

**Public API modifications**: none (signatures unchanged; implementations replace stubs).

**Public API removals**: none.

**Tests**:
1. `QueryApiTest` , `Reflect.Classpath.fromPickles(Seq.empty)` succeeds and returns a classpath where `findClass("anything")` returns `Absent`.
2. `QueryApiTest` , `cp.findClass("kyo.fixtures.FixtureClasses")` on a classpath opened from fixture TASTy returns `Present(sym)` with `sym.kind == SymbolKind.Class`.
3. `QueryApiTest` , `cp.findClass("nonexistent.Class.XYZ")` returns `Absent`.
4. `QueryApiTest` , `cp.findPackage("kyo.fixtures")` returns `Present(pkg)` with `pkg.kind == SymbolKind.Package`.
5. `QueryApiTest` , `cp.topLevelClasses` returns a non-empty `Chunk[Symbol]` for a classpath with fixture TASTy.
6. `QueryApiTest` , `cp.packages` returns at least `"kyo.fixtures"` as a package symbol.
7. `QueryApiTest` , `cp.errors` returns `Chunk.empty` for a clean classpath.
8. `QueryApiTest` , `cp.errors` returns at least one `ReflectError` for a classpath with one corrupt TASTy file (synthesized fixture).
9. `QueryApiTest` , `cp.query[Simple].run` (where `case class Simple(name: Name, flags: Flags) derives Reads`) returns all symbols in the fixture classpath.
10. `QueryApiTest` , `cp.query[Simple].where(_.kind == SymbolKind.Method).run` returns only method symbols.
11. `QueryApiTest` , `cp.query[Simple].withFlag(Flag.Inline).run` returns only symbols with `Flag.Inline`.
12. `QueryApiTest` , `cp.query[Simple].named("toString").run` returns only symbols named `toString`.
13. `QueryApiTest` , `cp.query[Simple].map(_.name).run` applies the mapping and returns `Chunk[Name]`.
14. `QueryApiTest` , `cp.query[Simple].stream.run` returns the same result as `.run`.
15. `QueryApiTest` , Classpath after its outer `Scope.run` has exited: `sym.declaredType` returns `Abort.fail(ReflectError.ClasspathClosed)`.
16. `QueryApiTest` , Classpath `state` transitions: `Building` before Phase C completes, `Ready` after `open` returns, `Closed` after scope exits.
17. `QueryApiTest` , strict mode: `Classpath.open(roots, strict = true)` on a classpath with one corrupt file fails with `Abort.fail(ReflectError.CorruptedFile(...))`.
18. `QueryApiTest` , soft-fail (default) mode: `Classpath.open(roots)` with one corrupt file succeeds; `cp.errors` is non-empty; other symbols still resolve.
19. `SymbolResolutionTest` , two concurrent `findClass("kyo.fixtures.FixtureClasses")` calls produce reference-equal `Symbol` instances (deduplication via `Cache.memo`).
20. `SymbolResolutionTest` , two concurrent `findClass` calls for different FQNs both resolve independently.
21. `SymbolResolutionTest` , `Unresolved` sentinel: `cp.findClass("no.such.Class")` returns `Absent` in soft-fail mode; the symbol in a partial-classpath fixture has `kind == SymbolKind.Unresolved` and `sym.declaredType` returns `Abort.fail(ReflectError.SymbolNotFound(...))`.
22. `SnapshotRoundTripTest` , write snapshot to a temp dir, read it back, compare `topLevelClasses` by FQN (structural equality).
23. `SnapshotRoundTripTest` , reading a snapshot with wrong magic produces `ReflectError.SnapshotFormatError`.
24. `SnapshotRoundTripTest` , reading a snapshot with different major version produces `ReflectError.SnapshotVersionMismatch` and falls through to full decode.
24a. `SnapshotRoundTripTest` , attempting to write a snapshot to an unwritable directory (e.g., a path under `/dev/null/impossible`) produces `Abort.fail(ReflectError.SnapshotIoError(...))`.
24b. `QueryApiTest` , `Classpath.open(Seq("/nonexistent/root"), ...)` produces `Abort.fail(ReflectError.FileNotFound("/nonexistent/root"))` (missing root is an immediate error in both strict and soft-fail modes).
25. `SnapshotRoundTripTest` , two concurrent snapshot writers for the same input produce one valid snapshot file (last-write-wins atomic rename; no corrupt output). Implementation: launch both writers with `Async.parallel(2)` (no `Thread.sleep` calls); bound the test with `Async.timeout(1.second)` so a hang fails the test rather than blocking CI; flakiness budget is zero (the atomic rename guarantee must be unconditional).
26. `SnapshotRoundTripTest` , `openCached` on a warm cache hit returns the same symbol graph as cold `open` (structural equality by FQN).
27. `SnapshotRoundTripTest` , `openCached` on a cold miss writes a snapshot file to the cache dir.
28. `SnapshotRoundTripTest` , `Reflect.Snapshot.evictOlderThan(0.millis)` removes all snapshot and tmp files from the cache dir.
29. `SnapshotRoundTripTest` , `DigestComputer.compute` for the same roots returns the same digest byte array (deterministic).
30. `SnapshotRoundTripTest` , `DigestComputer.compute` for two different file sets returns different digest byte arrays.
31. `QueryApiTest` , Phase A/B/C orchestration: a classpath with 3 fixture TASTy files is loaded with `concurrency = 3`; all symbols are present after `open` returns.
32. `QueryApiTest` , Phase B interruption: a classpath is opened with n fixture TASTy files where exactly one file is synthetically corrupted; after `open` returns, assert `cp.topLevelClasses.size == n-1` (all valid files decoded) AND `cp.errors.size == 1` (exactly one error accumulated, no more).
33. `QueryApiTest` , `cp.findClassByBinary("java/util/Map$Entry")` returns the same `Symbol` as `cp.findClass("java.util.Map.Entry")` (reference-equal after canonicalization via `FqnCanonicalizer.toFullName`).
34. `QueryApiTest` , `cp.findClassByBinary("no/such/Class$Nested")` returns `Absent`.
35. `SymbolResolutionTest` , cross-classpath FQN structural equality: open two `Classpath` instances over the same roots; look up the same FQN in each; verify `sym1 ne sym2` (not reference-equal across classpaths) AND `sym1.fullName == sym2.fullName` (structural equality by FQN).
36. `QueryApiTest` , Phase B interrupt with file-handle release: simulate 200 files where file number 3 throws during decode; each file's inner `Scope.run` increments an `AtomicInt` counter on acquire and decrements it on finalizer; after the parallel decode returns the error, assert the counter equals `0` (all file handles released, no descriptor leak).

**Total tests**: 38

**Verification command**:
```
sbt 'project kyo-reflect; testOnly kyo.QueryApiTest kyo.SymbolResolutionTest kyo.SnapshotRoundTripTest'
```
Cross-platform (one at a time per `feedback_sequential_test_runs`):
```
sbt 'kyo-reflectJS/test'
sbt 'kyo-reflectNative/test'
```

**Supervisor checks**:
- All `stub(...)` calls removed from `Reflect.scala`; all extension methods delegate to real implementations.
- `ClasspathOrchestrator.scala`, `JvmFileSource.scala`, `JsFileSource.scala`, `NativeFileSource.scala`, `SnapshotWriter.scala`, `SnapshotReader.scala`, `DigestComputer.scala` all present.
- Test 15 (ClasspathClosed after scope exits) passes.
- Test 19 (dedup via Cache.memo) passes.
- Test 25 (concurrent writers produce one valid snapshot) passes.
- All 38 tests pass on JVM; all tests pass on JS (node); all tests pass on Native.
- Example files in `kyo-reflect/shared/src/main/scala/kyo/reflect/examples/*.scala` compile and, for the JVM codegen example, produce a result when run against the fixture classpath.

---

## Summary table

| Phase | Name | New tests | Cumulative |
|-------|------|-----------|------------|
| 0.5   | Bug fixes + fixtures | 2 | 2 |
| 1     | Binary primitives + TASTy header | 24 | 26 |
| 2     | Name table + section index + attributes | 15 | 41 |
| 3     | Symbol pass 1 + skeleton AST | 23 | 64 |
| 4     | Type model + arenas + Phase C merge | 24 | 88 |
| 5     | Classfile reader | 20 | 108 |
| 5b    | Java/Scala unification | 18 | 126 |
| 6     | Reads derivation macro | 18 | 144 |
| 6b    | Record interop | 14 | 158 |
| 7     | Query + file sources + snapshot + orchestration | 38 | 196 |

Total: **196 tests** across 10 implementation phases.

---

## DESIGN.md section coverage cross-reference

| DESIGN.md section | Covered by phase |
|-------------------|-----------------|
| §5 Binary Primitives (ByteView, Varint, Utf8) | Phase 1 |
| §6.1 TASTy header (magic, version, UUID) | Phase 1 |
| §6.2 Name table (NameRef array, UTF-8, qualified names) | Phase 2 |
| §6.3 Sections + Attributes (explicitNulls, isJava, etc.) | Phase 2 |
| §6.4 AST unpickling (skeleton-eager pass, lazy bodies, forward refs, SHAREDtype) | Phase 3, Phase 4 |
| §6 Reads-driven pruning (needsBodies, touchedFields hint to unpickler) | Phase 6 |
| §7 Symbol model (SymbolKind, Flags, fullName, binaryName, Memo, SingleAssign, Symbol cache) | Phase 3 |
| §7 Java records (Flag.JavaRecord, recordComponents in JavaMetadata) | Phase 5b |
| §7 Scala 3 enum mapping (Flag.Enum, Flag.Case) | Phase 3 |
| §7 Unresolved sentinel (SymbolKind.Unresolved, soft-fail, strict mode) | Phase 3, Phase 7 |
| §7 Symbol home (ClasspathRef, checkOpen, ClasspathClosed) | Phase 3, Phase 7 |
| §8 Name intern table (sharded, 32 segments, byte-slice equality, lazy String via Memo) | Phase 2 |
| §9 Type model (all 26 Type cases) | Phase 4 |
| §9 Hash-consing per-thread arenas (TypeArena, TypeKey) | Phase 4 |
| §9 Phase C merge (bottom-up recursive, cycle-break, canonical arena) | Phase 4, Phase 7 |
| §9 Normalization smart constructors (FunctionN, TupleN, ContextFunctionN, Array, AndType/Singleton) | Phase 4 |
| §10 Classfile reader (ClassfileFormat, ConstantPool, ClassfileUnpickler) | Phase 5 |
| §10 Generic signature parser (JavaSignatures) | Phase 5 |
| §10 InnerClasses attribute + FQN canonicalization | Phase 5b |
| §11 Java/Scala unified model (same Symbol+Type ADT, isJava flag) | Phase 5b |
| §11 FQN canonicalization contract (dotted fullName, binaryName) | Phase 5b |
| §11 Type ADT coverage for Java types (all rows of the Java type table) | Phase 5b |
| §11 JavaMetadata side door (throwsTypes, annotations, enclosingMethod, accessFlags, recordComponents) | Phase 5, Phase 5b |
| §11 Record interop and symbolToRecord bridging idiom | Phase 6b |
| §12 Public API (Classpath.open, openCached, fromPickles, findClass, findPackage, packages, topLevelClasses, query, errors) | Phase 7 |
| §12 Query combinators (filter, where, withFlag, named, extending, map, stream, run) | Phase 7 |
| §12 symbolToRecord field-to-accessor mapping table | Phase 6b |
| §12 classFqn helper | Phase 0 skeleton (already correct) |
| §13 Reads trait + derivation macro | Phase 6 |
| §13 Built-in Reads instances (Name, Flags, SymbolKind, Type, Symbol, Chunk, Maybe, primitives, tuples) | Phase 6 |
| §13 touchedFields static analysis + transitivity | Phase 6 |
| §13 Recursive case classes (lazy val) | Phase 6 |
| §13 Hygiene guards (Trees.exists, skip Match.pattern) | Phase 6 |
| §13 ADT sum-type guard + worked example | Phase 6 |
| §13 Reads × symbolToRecord composition | Phase 6b |
| §14 FileSource trait + JVM/JS/Native implementations | Phase 7 |
| §14 jrt:/ URI support (JVM) | Phase 7 |
| §14 Browser fromPickles fallback (JS) | Phase 7 |
| §14 POSIX open/read FFI (Native) | Phase 7 |
| §15 Phase A header sweep (Async.foreach + inner Scope.run) | Phase 7 |
| §15 Phase B parallel body decode (per-fiber TypeArena, UnresolvedRef placeholders) | Phase 7 |
| §15 Symbol home assignment (ClasspathBuilder, state: Building → Ready → Closed) | Phase 7 |
| §15 Phase C merge + placeholder resolution | Phase 7 |
| §15 Failure modes (Phase A/B/C error accumulation, strict vs soft-fail) | Phase 7 |
| §15 Interrupt handling (inner Scope.run finalizers on fiber interrupt) | Phase 7 |
| §16 KRFL format (magic, version, flags, inputDigest, compilerVersion, section index, 9 sections) | Phase 7 |
| §16 Snapshot open path (digest, cache lookup, loadSnapshot / openFresh + writeSnapshot) | Phase 7 |
| §16 Atomic-rename concurrent write strategy | Phase 7 |
| §16 Input digest policy (FNV-1a 64-bit hash of mtime+size tuples; non-cryptographic; ParanoidContent opt-in uses FNV-1a of file contents) | Phase 7 |
| §16 Endianness (LE + byteOrder header byte) | Phase 7 |
| §16 Versioning policy (major invalidates, minor add-only) | Phase 7 |
| §16 Eviction (Reflect.Snapshot.evictOlderThan) | Phase 7 |
| §16 Browser no-op cache (JS browser degrades to open) | Phase 7 |
| §16 JVM mmap for snapshot (MemorySegment via Arena.ofShared; deterministic release via Arena.close) | Phase 7 |
| §16 Native mmap for snapshot (POSIX mmap FFI) | Phase 7 |
| §16 JS read-into-Array fallback for snapshot | Phase 7 |
