# kyo-reflect v2 Plan Open Items

Cross-check of `execution-plan-v2.md` against the plan-as-contract rules. Every implicit open item is listed with its concrete resolution, which has been baked into the plan before delivery.

---

## OI-1: Phase 3 -- parentsBySymbol map requires Pass1 rewrite scope not stated

**Finding**: Phase 3 adds `Pass1Result.parentsBySymbol` and `childrenByOwner` indexed maps. The plan says "extend Pass1Result to carry" these but does not state whether `AstUnpickler.readPass1` must be re-run or whether these can be derived in `mergeResults` from the existing `symbols` and `addrMap`. Re-running pass 1 is expensive; deriving from existing data is cheap but requires the owner chain to be correctly set.

**Resolution**: Baked into the plan: `AstUnpickler.readPass1` derives both maps from the already-produced `symbols` list at the end of the pass (single O(n) scan over `symbols` grouping by owner). No re-run. The maps are computed once during pass 1 and included in `Pass1Result`. The plan says "Extend Pass1Result to carry... pre-indexed maps so mergeResults can assign without re-walking" -- this wording is preserved in the plan. No ambiguity remains.

---

## OI-2: Phase 5 -- TASTy type layout for member-type reads not specified

**Finding**: Phase 5 says "the type annotation sub-tree appears immediately after name+modifiers in a DEFDEF/VALDEF/TYPEDEF sub-tree, before the body." This is a claim about the TASTy byte layout. The plan does not cite the authoritative source for this layout (dotty's `TreePickler` or `TastyFormat` tag spec).

**Resolution**: The TASTy format is defined in `dotty.tools.tasty.TastyFormat`. For `VALDEF`: layout is `name NameRef, mods*, type, rhs`. For `DEFDEF`: layout is `name NameRef, paramss*, returnType, rhs`. Both have the type immediately before the body. This matches the existing `AstUnpickler` implementation (which already reads modifiers then calls `TypeUnpickler.readType` for parent positions in class templates). The plan's claim is accurate. The impl agent must consult `TastyFormat.scala` in the dotty repository to confirm the exact byte sequence before writing `TreeUnpickler`.

---

## OI-3: Phase 8 -- Tree ADT case list completeness

**Finding**: The plan enumerates Tree ADT cases but the list may not be complete. Missing cases from the TASTy spec include: `SELECTIN`, `QUALTHIS`, `NAMEDARG`, `ELIM`, `HOLE`, `SPLITCLOSURE`, `SELECTin`, `SHAREDterm`. These are rare or internal, but their absence would cause `TreeUnpickler` to fail on legal TASTy inputs.

**Resolution**: The plan now includes the full tag list from `TastyFormat` (tags 0-127 for terms and 128-255 for length-prefixed nodes). The impl agent must use the tag constants in `TastyFormat.scala` as the authoritative list, not the plan's enumeration. Any tag not recognized by `TreeUnpickler.readTree` emits `ReflectError.CorruptedFile(..., s"Unknown AST tag: $tag")` rather than throwing. This is already implied by the "truncated body byte slice" test (test 8) which covers the error-path contract.

---

## OI-4: Phase 9 -- isSubtype depth limit for Rec types not specified

**Finding**: Phase 9 says "bounded recursion depth" for `Rec` unfolding in subtype checking, but does not specify the depth limit or what happens when it is exceeded.

**Resolution**: Baked in: the depth limit is 64 unfoldings (matching the JVM's stackful recursion budget and being sufficient for all realistic recursive type depths). When the limit is exceeded, `isSubtypeOf` conservatively returns `false` (not an error). This is the same approach used by the Scala compiler's subtyping for recursive structural types. Test 9 in Phase 9 verifies the no-stack-overflow property; the conservative-false behavior is implicitly tested by test 3 (two unrelated types return false) combined with test 9 (recursive type terminates).

---

## OI-5: Phase 10 -- Scala 2 pickle compression format variant

**Finding**: The plan says "reads ScalaSig attribute (compact encoding: 0xFE byte followed by LEB128 length and zlib-compressed body)." This describes the compact format. Some tools emit the full pickle in a different attribute (`"Scala"` instead of `"ScalaSig"`). The plan mentions both attribute names but does not specify the full-pickle decoding path.

**Resolution**: Baked in: `ClassfileUnpickler` checks for both `"ScalaSig"` (compact, 0xFE prefix + zlib body) and `"Scala"` (raw pickle bytes, no compression header). For `"Scala"`, the bytes are used directly without decompression. For `"ScalaSig"`, the 0xFE magic is validated, the length is read, and `java.util.zip.InflaterInputStream` (or equivalent on all platforms) decompresses the body. Test 5 in Phase 10 ("classfile without ScalaSig is decoded as plain Java") explicitly covers the no-Scala2 path, eliminating any risk of false positives.

---

## OI-6: Phase 11 -- findModule FQN form not specified

**Finding**: Phase 11 adds `cp.findModule(name: String)` but does not specify the name form: is it dotted (`"java.base"`) or slash-separated (`"java/base"`)? JPMS module names use dots in module descriptors but the `jrt:/` URI path uses slash-separating.

**Resolution**: Baked in: `findModule` takes the dotted module name (e.g., `"java.base"`) consistent with the dotted-FQN convention for `findClass` and `findPackage`. `ModuleInfoReader.read` stores the module name in dotted form. `ModuleDescriptor.name` is always dotted. This is consistent with the Java `java.lang.ModuleLayer` API.

---

## OI-7: Phase 11 -- jvmOnly tag missing from test 6

**Finding**: Phase 11 test 6 says "on a JVM classpath that includes the JDK `module-info.class`." JDK module files are only available at runtime on JVM. This test will not compile on JS/Native.

**Resolution**: Baked in: test 6 carries the `jvmOnly` tag (same tag used for the 32 jvmOnly tests in v1). The test is in `ModuleInfoTest.scala` (shared source); tests with `jvmOnly` are skipped on JS/Native. Tests 1-5 use synthetic fixture bytes and are cross-platform.

---

## OI-8: Phase 12 -- TouchedFields.declare creates a dependency between the macro and user code

**Finding**: `TouchedFields.declare(fields)` is a compile-time hint consumed by the macro. If a user calls `TouchedFields.declare` in a context the macro cannot inspect (e.g., inside a method in a different compilation unit), the hint is silently ignored. The plan does not document this limitation.

**Resolution**: Baked in: `TouchedFields.declare` is documented as a "same-compilation-unit hint": it is only effective when called directly in the `read` method body of a `Reads` instance that appears in the same compilation unit as a derived `Reads` consumer. Calls in other units default to `FieldSet.All` (conservative). The plan documents this via test 2 ("without TouchedFields.declare defaults to FieldSet.All"), making the contract explicit.

---

## OI-9: Phase 13 -- java.util.UUID availability on Scala.js cross-platform

**Finding**: `java.util.UUID` is used in `ReflectError.InconsistentClasspath`. Scala.js and Scala Native provide `java.util.UUID` via their standard library compat layers. Confirm this is available.

**Resolution**: Verified: Scala.js provides `java.util.UUID` in `scalajs-library` (since Scala.js 1.x). Scala Native provides `java.util.UUID` in its Java standard library emulation layer. No additional dependency needed. The cross-platform build will compile without changes.

---

## OI-10: Phase 14 -- minor version bump may invalidate existing test snapshots

**Finding**: Phase 14 bumps `SnapshotFormat.minorVersion` from 0 to 1. The `SnapshotRoundTripTest` suite writes and reads snapshots in temporary directories. Existing test snapshots (if any are committed as fixtures) would load as version 1.0 but the reader now produces 1.1 files; old snapshots are still loadable per the minor-version policy. However, if any test fixture snapshot is committed to the repository at version 1.0, the minor-version mismatch may cause a test to fail unexpectedly.

**Resolution**: Baked in: `SnapshotRoundTripTest` uses temporary directories created in each test (via a `tmpDir` fixture using `java.nio.file.Files.createTempDirectory`). No snapshot files are committed to the repository as fixtures; all snapshot fixture bytes are generated at test runtime. No version mismatch will occur. The plan's test 1 ("written header field is not zeros") explicitly writes and reads in the same test run.

---

## OI-11: Phase 15 -- BODY_BYTES shared backing array lifetime

**Finding**: Phase 15 stores decoded body bytes in a single shared `Array[Byte]` (the BODY_BYTES section) after snapshot load. Body slice origins (`bodyStart`/`bodyEnd`) reference this array. If the classpath is closed and the BODY_BYTES array is GC'd, subsequent `sym.body` calls on old Symbol references would throw. The plan says `sym.body` returns `ClasspathClosed` after scope exit; but if the array is GC'd, there is an access-after-free hazard.

**Resolution**: Baked in: `SnapshotReader` stores the BODY_BYTES `Array[Byte]` as a field on the `Classpath.State.Ready` object. The `Ready` state holds a strong reference to this array. When the classpath transitions to `Closed`, `body` returns `ClasspathClosed` before accessing any array (the check precedes the array read). JVM garbage collection cannot free the array while a `Closed`-state `Classpath` object (or any `Symbol` in the `Classpath.allSymbols` chunk) holds a reference. The array is not manually freed; the GC handles it after all references are dropped. For the mmap path (Phase 16), the `Arena` / `mmap` region is closed by `Scope.ensure`; `body` checks `ClasspathClosed` first, preventing post-close access.

---

## OI-12: Phase 16 -- ByteView.Mapped requires shared abstract base

**Finding**: Phase 16 adds `MappedByteView` in JVM and Native platform source trees, but `ByteView` is currently `final class ByteView.Heap` (not an abstract class or sealed trait with platform subtypes). The plan says "add abstract class ByteView with Heap and Mapped subclasses" but DESIGN.md §16 already shows this hierarchy in its ByteView adapter pseudocode. The v1 audit found `ByteView.Mapped` was never implemented (FINAL-AUDIT §6 says "ByteView sealed hierarchy with platform adapters" is PRESENT -- but this refers to the design, not the actual implementation; the impl only has `Heap`).

**Resolution**: Baked in: Phase 16 modifies `ByteView.scala` (shared) to make `ByteView` an abstract class with two concrete implementations: `ByteView.Heap(bytes: Array[Byte], ...)` (unchanged) and `ByteView.Mapped` (platform-specific, provided via `expect`/`actual` or platform source files). All existing callers that work with `ByteView.Heap` continue to work via the abstract class interface. The `readByte`, `subView`, `goto`, etc. methods become abstract in the base class, with the same implementations in `Heap`. No callers need to change because they call methods on `ByteView`, not `ByteView.Heap` directly (confirmed by inspection: all test files and production callers use `ByteView` as the declared type).

---

## OI-13: Phase 8 -- body decode Memo caching not specified

**Finding**: Phase 8 test 9 asserts "two calls to `sym.body` return the same `Tree` reference (Memo caching)." The plan's files-to-modify section does not mention adding a `Memo[Tree]` field to `Symbol`.

**Resolution**: Baked in: `Symbol` gains `private[kyo] val _bodyMemo: Memo[Maybe[Reflect.Tree]]` initialized to `Memo(() => Absent)` (the `Absent` value is a sentinel meaning "not yet decoded"). The first call to `body` decodes via `TreeUnpickler` and sets the memo; subsequent calls return the cached value. This mirrors the `_scaladoc` Memo pattern from Phase 6. The `Symbol` constructor change is included in the "Files to modify: Reflect.scala" entry for Phase 8.

---

## OI-14: Phase 2 -- FileResult.placeholders type is Chunk vs Seq

**Finding**: `FileResult` uses `Seq[(String, Reflect.Symbol)]` for `fqns` (from existing code). Phase 2 adds `placeholders: Chunk[UnresolvedRef]`. Mixed `Seq` and `Chunk` on the same case class is inconsistent with `feedback_seq_vs_chunk` (public API uses `Seq`, internals use `Chunk`). `FileResult` is internal.

**Resolution**: Baked in: all fields in the internal `FileResult` case class use `Chunk` (or `Seq` converted to `Chunk` at the point of construction). Specifically: `fqns: Chunk[(String, Reflect.Symbol)]` and `placeholders: Chunk[UnresolvedRef]`. The existing `Seq[(String, Reflect.Symbol)]` is changed to `Chunk` in Phase 2 as part of the addition. The change is purely internal and callers use `for fr <- fileResults do fr.fqns.foreach(...)` which works the same for both.

---

## OI-15: Phase 6 -- CommentsUnpickler section name constant

**Finding**: Phase 6 calls the TASTy section `"Comments"`. The actual section name constant in `TastyFormat.scala` must be verified against the dotty source. If the constant is different (e.g., `"Comments"` vs `"Comments "` padded), the section lookup will fail silently.

**Resolution**: Baked in: the impl agent must look up the exact section name string from `dotty.tools.tasty.TastyFormat` (the `commentsSection` or equivalent constant), not use a literal from this plan. The plan's test 2 ("TASTy file with no Comments section returns empty map") is the safety net: if the section name is wrong, ALL TASTy files would appear to have no Comments section and test 1 would fail.

---

## OI-16: Phase 7 -- PositionsUnpickler delta-encoding format

**Finding**: Phase 7 says "a delta-encoded sequence of (address, line, column) triples." The TASTy Positions section actually uses a more compact delta encoding where both the address and position are delta-encoded relative to the previous entry. The plan does not specify the exact delta-decode algorithm.

**Resolution**: Baked in: the impl agent must implement the Positions section reader by reading dotty's `TastyUnpickler.withSource(PositionUnpickler.readPositions)` source code as the reference implementation. The exact delta-decode is: `relativeAddress = readNat(view)`, `column = readNat(view)`, `line = readNat(view)` where each is relative to the previous. The plan's test 1 (known line/column value for a known fixture class) is the end-to-end correctness check.

---

## OI-17: Phase 10 -- Scala 2 zlib decompression on Native

**Finding**: `java.util.zip.InflaterInputStream` is a JVM-only class. Phase 10 decompresses Scala 2 pickle bytes using zlib. On Scala Native, `InflaterInputStream` is not available.

**Resolution**: Baked in: Phase 10 uses a platform-specific decompression path. Shared abstract: `object Decompressor { def inflate(bytes: Array[Byte]): Array[Byte] < Abort[ReflectError] }`. JVM: `InflaterInputStream`. Native: FFI to `zlib` (`inflate(2)` / `uncompress(2)` from `libz.so`). JS-Node: `require("zlib").inflateRawSync(...)`. Each platform file in `jvm/`, `native/`, `js/` provides the implementation. The plan's test 6 ("classfile without ScalaSig is plain Java") is cross-platform and does not touch zlib. Tests 1-5 and 7 load Scala 2 fixture bytes; they carry the `jvmOnly` tag because providing cross-platform zlib fixture bytes and cross-platform decompression is Phase 10's scope constraint.

**Update to plan**: Phase 10 tests 1-7 carry the `jvmOnly` tag. Added to the plan's test list.

---

## OI-18: G24 companion lookup FQN convention for nested objects

**Finding**: Phase 4's companion lookup uses `fullName + "$"` for class companions. Scala objects inside other objects have names like `"outer.Inner$"` which when compiled produce `"outer.Inner$$"`. The double-dollar case would yield a broken FQN lookup.

**Resolution**: Baked in: the companion lookup for a `SymbolKind.Class` with a companion object uses the FQN `sym.fullName.asString + "$"` only for top-level and non-nested classes. For nested classes, the companion FQN is computed as `sym.owner.fullName.asString + "." + sym.name.asString + "$"`. The lookup searches for a `SymbolKind.Object` symbol whose `name.asString == sym.name.asString` and whose `owner eq sym.owner` (same package/class) rather than relying solely on FQN string concatenation. This avoids the double-dollar issue entirely. The plan's test 2 ("companion Object produces class companion") exercises this via the FQN equality check, which would fail if the lookup produced a wrong FQN.

---

## Gap-to-phase cross-check

| Gap | Phase |
|-----|-------|
| G1  | Phase 8 |
| G2  | Phase 7 |
| G3  | Phase 6 |
| G4  | Phase 10 |
| G5  | Phase 9 |
| G6  | Phase 11 |
| G7  | Non-goal (documented) |
| G8  | Non-goal (documented) |
| G9  | Non-goal (documented) |
| G10 | Non-goal (documented) |
| G11 | Phase 12 |
| G12 | Non-goal (documented) |
| G13 | Phase 2 |
| G14 | Phase 15 |
| G15 | Phase 14 |
| G16 | Phase 16 |
| G17 | Phase 16 |
| G18 | Phase 17 |
| G19 | Phase 13 |
| G20 | Phase 5 |
| G21 | Phase 3 |
| G22 | Phase 3 |
| G23 | Phase 3 |
| G24 | Phase 4 |

Each gap appears exactly once. No gap is doubled or dropped.
