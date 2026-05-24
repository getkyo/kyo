# Phase 1 Prep — Binary Primitives + TASTy Header

## 1. Verbatim API Signatures

### Sync.defer
File: `kyo-core/shared/src/main/scala/kyo/Sync.scala`, line 50
```scala
inline def defer[A, S](inline f: Safepoint ?=> A < S)(using inline frame: Frame): A < (Sync & S)
```
Use for wrapping any side-effecting decode step (array reads, cursor mutation). Do NOT use for pure
computation that creates no side effects — just chain directly in the Kyo monad.

### Abort.fail
File: `kyo-prelude/shared/src/main/scala/kyo/Abort.scala`, line 56
```scala
inline def fail[E](inline value: E)(using inline frame: Frame): Nothing < Abort[E]
```
Usage in Phase 1: `Abort.fail(ReflectError.CorruptedFile(...))`,
`Abort.fail(ReflectError.UnsupportedVersion(...))`, `Abort.fail(ReflectError.MalformedSection(...))`.
Per feedback rule `feedback_no_explicit_abort_fail_types.md`: do NOT write `Abort.fail[ReflectError](...)`;
let inference pick E from the argument.

### Span operations
File: `kyo-data/shared/src/main/scala/kyo/Span.scala`
```scala
// object Span, line 38
def empty[A: ClassTag as ct]: Span[A]             // line 47
inline def apply[A: ClassTag](): Span[A]           // line 58 — empty
inline def apply[A: ClassTag](a0: A): Span[A]      // line 67 — single element
def from[A: ClassTag](array: Array[A]): Span[A]    // line 205 — copy from Array
def fromUnsafe[A](array: Array[A]): Span[A]        // line 184 — zero-copy, caller must not mutate
```
`Span[Byte]` is `opaque type Span[+A] = Array[? <: A]`. ByteView internals use `Array[Byte]`
directly; only cross-module return types (e.g., `Pickle.bytes`) expose `Span[Byte]`. Use
`Span.from[Byte](arr)` (copies) or `Span.fromUnsafe[Byte](arr)` (zero-copy, safe when arr is
never mutated after hand-off).

### Chunk constructors
File: `kyo-data/shared/src/main/scala/kyo/Chunk.scala`
```scala
// object Chunk, line 512
def empty[A]: Chunk[A]                            // line 673
def from[A](values: Array[A]): Chunk.Indexed[A]   // line 684
def from[A](source: IterableOnce[A]): Chunk[A]    // line 703
```
`Chunk` is used for public API return values (e.g., `Reflect.Annotation.argsPickle: Chunk[Byte]`).
Internal ByteView always uses `Array[Byte]` for performance.

---

## 2. Verbatim TASTy Format Constants

**Source**: Coursier cache at
`~/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/tasty-core_3/3.8.3/tasty-core_3-3.8.3-sources.jar`
Inner path: `dotty/tools/tasty/TastyFormat.scala`

### Magic bytes
```scala
// TastyFormat.scala, line 313
final val header: Array[Int] = Array(0x5C, 0xA1, 0xAB, 0x1F)
```
The 32-bit magic value is `0x5CA1AB1F`. The spec says "Header = 0x5CA1AB1F" (file comment line 26).
Written as four separate bytes in the order: `0x5C`, `0xA1`, `0xAB`, `0x1F` — most-significant byte
first (big-endian). kyo-reflect must check all four in order and fail with `CorruptedFile` on any mismatch.

### Version triple (Scala 3.8.3)
```scala
// TastyFormat.scala, lines 321-344
final val MajorVersion: Int = 28
final val MinorVersion: Int = 8
final val ExperimentalVersion: Int = 0
```

### isVersionCompatible algorithm (verbatim from TastyFormat.scala, lines 373-390)
```scala
def isVersionCompatible(
  fileMajor: Int, fileMinor: Int, fileExperimental: Int,
  compilerMajor: Int, compilerMinor: Int, compilerExperimental: Int
): Boolean = (
  fileMajor == compilerMajor &&
    (  fileMinor == compilerMinor && fileExperimental == compilerExperimental
    || fileMinor <  compilerMinor && fileExperimental == 0
  )
)
```
In English: the file is readable iff:
- `fileMajor == supportedMajor`, AND EITHER
  - full equality on minor + experimental, OR
  - `fileMinor < supportedMinor` AND `fileExperimental == 0` (stable older)

Note: the plan's version-check formula (Section 66 of execution-plan.md) says "experimental == 0 ||
experimental == supportedExperimental". The verbatim dotty code is more restrictive: an experimental file
(experimental != 0) is ONLY readable if `fileMinor == compilerMinor && fileExperimental == compilerExperimental`.
A file with experimental != 0 and a lower minor is NOT readable. Implement the verbatim dotty formula.

### AST tag enumeration (verbatim, TastyFormat.scala)
Tree Category 1 (tags 1–59, format: tag only):
```scala
final val UNITconst = 2;   final val FALSEconst = 3;  final val TRUEconst = 4
final val NULLconst = 5;   final val PRIVATE = 6;      final val PROTECTED = 8
final val ABSTRACT = 9;    final val FINAL = 10;        final val SEALED = 11
final val CASE = 12;       final val IMPLICIT = 13;     final val LAZY = 14
final val OVERRIDE = 15;   final val INLINEPROXY = 16;  final val INLINE = 17
final val STATIC = 18;     final val OBJECT = 19;       final val TRAIT = 20
final val ENUM = 21;       final val LOCAL = 22;        final val SYNTHETIC = 23
final val ARTIFACT = 24;   final val MUTABLE = 25;      final val FIELDaccessor = 26
final val CASEaccessor = 27; final val COVARIANT = 28;  final val CONTRAVARIANT = 29
final val HASDEFAULT = 31; final val STABLE = 32;       final val MACRO = 33
final val ERASED = 34;     final val OPAQUE = 35;       final val EXTENSION = 36
final val GIVEN = 37;      final val PARAMsetter = 38;  final val EXPORTED = 39
final val OPEN = 40;       final val PARAMalias = 41;   final val TRANSPARENT = 42
final val INFIX = 43;      final val INVISIBLE = 44;    final val EMPTYCLAUSE = 45
final val SPLITCLAUSE = 46; final val TRACKED = 47;     final val SUBMATCH = 48
final val INTO = 49
```

Tree Category 2 (tags 60–89, format: tag Nat):
```scala
final val SHAREDterm = 60;    final val SHAREDtype = 61
final val TERMREFdirect = 62; final val TYPEREFdirect = 63
final val TERMREFpkg = 64;    final val TYPEREFpkg = 65
final val RECthis = 66;       final val BYTEconst = 67
final val SHORTconst = 68;    final val CHARconst = 69
final val INTconst = 70;      final val LONGconst = 71
final val FLOATconst = 72;    final val DOUBLEconst = 73
final val STRINGconst = 74;   final val IMPORTED = 75;  final val RENAMED = 76
```

Tree Category 3 (tags 90–109, format: tag AST):
```scala
final val THIS = 90;    final val QUALTHIS = 91;   final val CLASSconst = 92
final val BYNAMEtype = 93; final val BYNAMEtpt = 94; final val NEW = 95
final val THROW = 96;   final val IMPLICITarg = 97; final val PRIVATEqualified = 98
final val PROTECTEDqualified = 99; final val RECtype = 100; final val SINGLETONtpt = 101
final val BOUNDED = 102; final val EXPLICITtpt = 103; final val ELIDED = 104
```

Tree Category 4 (tags 110–127, format: tag Nat AST):
```scala
final val IDENT = 110;     final val IDENTtpt = 111;    final val SELECT = 112
final val SELECTtpt = 113; final val TERMREFsymbol = 114; final val TERMREF = 115
final val TYPEREFsymbol = 116; final val TYPEREF = 117;  final val SELFDEF = 118
final val NAMEDARG = 119
```

Tree Category 5 (tags 128–255, format: tag Length payload — the length-prefixed nodes):
```scala
final val firstLengthTreeTag = PACKAGE
final val PACKAGE = 128;   final val VALDEF = 129;   final val DEFDEF = 130
final val TYPEDEF = 131;   final val IMPORT = 132;   final val TYPEPARAM = 133
final val PARAM = 134;     final val APPLY = 136;    final val TYPEAPPLY = 137
final val TYPED = 138;     final val ASSIGN = 139;   final val BLOCK = 140
final val IF = 141;        final val LAMBDA = 142;   final val MATCH = 143
final val RETURN = 144;    final val WHILE = 145;    final val TRY = 146
final val INLINED = 147;   final val SELECTouter = 148; final val REPEATED = 149
final val BIND = 150;      final val ALTERNATIVE = 151; final val UNAPPLY = 152
final val ANNOTATEDtype = 153; final val ANNOTATEDtpt = 154; final val CASEDEF = 155
final val TEMPLATE = 156;  final val SUPER = 157;    final val SUPERtype = 158
final val REFINEDtype = 159; final val REFINEDtpt = 160; final val APPLIEDtype = 161
final val APPLIEDtpt = 162; final val TYPEBOUNDS = 163; final val TYPEBOUNDStpt = 164
final val ANDtype = 165;   final val ORtype = 167;   final val POLYtype = 169
final val TYPELAMBDAtype = 170; final val LAMBDAtpt = 171; final val PARAMtype = 172
final val ANNOTATION = 173; final val TERMREFin = 174; final val TYPEREFin = 175
final val SELECTin = 176;  final val EXPORT = 177;   final val QUOTE = 178
final val SPLICE = 179;    final val METHODtype = 180; final val APPLYsigpoly = 181
final val QUOTEPATTERN = 182; final val SPLICEPATTERN = 183
final val MATCHtype = 190; final val MATCHtpt = 191; final val MATCHCASEtype = 192
final val FLEXIBLEtype = 193
final val HOLE = 255
```

Name tags (inner class NameTags, TastyFormat.scala lines ~401–429):
```scala
final val UTF8 = 1;           final val QUALIFIED = 2
final val EXPANDED = 3;       final val EXPANDPREFIX = 4
final val UNIQUE = 10;        final val DEFAULTGETTER = 11
final val SUPERACCESSOR = 20; final val INLINEACCESSOR = 21
final val BODYRETAINER = 22;  final val OBJECTCLASS = 23
final val SIGNED = 63;        final val TARGETSIGNED = 62
```

Section name constants (TastyFormat.scala, lines 394–397):
```scala
final val ASTsSection       = "ASTs"
final val PositionsSection  = "Positions"
final val CommentsSection   = "Comments"
final val AttributesSection = "Attributes"
```

---

## 3. File:Line Anchors — Existing Skeleton

### Reflect.scala (`kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`)

| Symbol | Kind | Lines |
|--------|------|-------|
| `Reflect.Version` | `final case class Version(major: Int, minor: Int, experimental: Int)` | 18–19 |
| `Reflect.supportedTastyVersion` | `val supportedTastyVersion: Version = Version(28, 8, 0)` | 22 |
| `Reflect.Name` | `opaque type Name = String` | 26 |
| `Reflect.Name.apply` | `def apply(s: String): Name = s` | 28 |
| `Reflect.Name.asString` | `extension (n: Name) def asString: String = n` | 31 |
| `Reflect.Flags` | `final class Flags(val bits: Long) extends AnyVal` | 34 |
| `Reflect.Flag` | `final class Flag(val bit: Long, val name: String)` | 41 |
| `Reflect.Classpath` | `opaque type Classpath = ClasspathState` | 191 |
| `Reflect.Pickle` | `final case class Pickle(uuid: String, version: Version, bytes: Chunk[Byte])` | 187 |

### ReflectError.scala (`kyo-reflect/shared/src/main/scala/kyo/ReflectError.scala`)

| Case | Lines |
|------|-------|
| `ReflectError.CorruptedFile(path: String, at: Long, reason: String)` | 9 |
| `ReflectError.UnsupportedVersion(found: Reflect.Version, supported: Reflect.Version)` | 10 |
| `ReflectError.MalformedSection(name: String, reason: String)` | 12 |
| `ReflectError.FileNotFound(path: String)` | 8 |
| `ReflectError.NotImplemented(feature: String)` | 20 |

Phase 1 wires `CorruptedFile` (bad magic), `UnsupportedVersion` (version mismatch), and
`MalformedSection` (truncated header).

Note on `CorruptedFile.at: Long`: the `at` field is a Long byte offset. During header reading, pass
the current byte position (0–3 for magic check failures, or the position of the faulty field afterward).

---

## 4. Edge Cases and Gotchas

### LEB128 encoding — TASTy uses big-endian base-128, NOT standard little-endian LEB128
This is the most critical difference from standard LEB128. From TastyBuffer / TastyReader:
- **Unsigned (Nat / readNat / readLongNat)**: continuation bytes have bit 0x80 CLEAR (not set);
  the terminating (last) byte has 0x80 SET. This is the OPPOSITE of standard LEB128.
  - Reading loop: `x = (x << 7) | (b & 0x7f); continue while (b & 0x80) == 0`
  - Verbatim from `TastyReader.readLongNat` (TastyReader.scala lines ~68-77):
    ```scala
    var b = 0L; var x = 0L
    while { b = bytes(bp); x = (x << 7) | (b & 0x7f); bp += 1; (b & 0x80) == 0 } ()
    x
    ```

- **Signed (Int / readInt / readLongInt)**: 2's complement encoding, NOT zigzag. From
  `TastyReader.readLongInt` (TastyReader.scala lines ~81-89):
  ```scala
  var b = bytes(bp)
  var x: Long = (b << 1).toByte >> 1  // sign extend with bit 6
  bp += 1
  while ((b & 0x80) == 0) {
    b = bytes(bp)
    x = (x << 7) | (b & 0x7f)
    bp += 1
  }
  x
  ```

**IMPORTANT**: The execution plan (line 59) claims signed integers use zigzag encoding
`(n >>> 1) ^ -(n & 1)`. This is WRONG. The actual dotty implementation uses 2's complement
big-endian base-128, not zigzag. The zigzag formula does not appear anywhere in the dotty TASTy
sources. Implement `readLongInt` as the sign-extend-on-first-byte approach from `TastyReader.scala`.

### UUID — 16 bytes, two uncompressed Longs, big-endian
From `TastyHeaderUnpickler.readFullHeader` (TastyHeaderUnpickler.scala line ~103):
```scala
val uuid = new UUID(readUncompressedLong(), readUncompressedLong())
```
`readUncompressedLong` reads 8 bytes big-endian (TastyReader.scala):
```scala
def readUncompressedLong(): Long = {
  var x: Long = 0
  for (_ <- 0 to 7) x = (x << 8) | (readByte() & 0xff)
  x
}
```
UUID is NOT LEB128-encoded. It is 16 raw bytes, most-significant byte first.
Surface as a hex string: `f"%08x%08x".format(msb, lsb)` or similar cross-platform approach.

### Header byte order
Magic bytes read in order: `0x5C`, `0xA1`, `0xAB`, `0x1F` (indices 0–3). Check them sequentially
per the dotty loop in `readFullHeader`:
```scala
for (i <- 0 until header.length) check(readByte() == header(i), "not a TASTy file")
```
Note: `readByte()` in dotty returns `bytes(bp) & 0xff` (unsigned). In Scala/JVM `Array[Byte]`
stores signed bytes; mask with `& 0xff` before comparing to the `Array[Int]` constants.

### Tooling version string — comes BEFORE the UUID
From `TastyHeaderUnpickler.readFullHeader`: the read order is:
1. 4 magic bytes
2. `majorVersion` (Nat)
3. `fileMinor` (Nat)
4. `fileExperimental` (Nat)
5. tooling version: `length` (Nat) then `length` raw bytes (UTF-8)
6. UUID: 16 bytes (two uncompressed Longs)

The plan (line 66) correctly states this order.

### Version-check rule (corrected)
The plan line 66 says: "experimental == 0 || experimental == supportedExperimental".
The dotty source (TastyFormat.scala `isVersionCompatible`) says:
```
fileMajor == compilerMajor &&
  (  fileMinor == compilerMinor && fileExperimental == compilerExperimental
  || fileMinor <  compilerMinor && fileExperimental == 0
  )
```
A file with experimental != 0 and fileMinor < compilerMinor is NOT readable. Only files with
experimental == 0 and older minor are stable-backwards-compatible.

### Cross-platform UTF-8 decode
Three platform implementations required (per execution plan lines 61–64):
- **JVM** (`kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/binary/Utf8.scala`):
  ```scala
  new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8)
  ```
- **JS** (`kyo-reflect/js/src/main/scala/kyo/internal/reflect/binary/Utf8.scala`):
  ```scala
  val arr = bytes.slice(offset, offset + length)
  js.Dynamic.global.TextDecoder.newInstance().decode(arr).asInstanceOf[String]
  // or: new scala.scalajs.js.typedarray.Int8Array from the slice
  ```
  JS TextDecoder takes a `Uint8Array`. Need to copy the slice into a typed array before passing.
- **Native** (`kyo-reflect/native/src/main/scala/kyo/internal/reflect/binary/Utf8.scala`):
  Use `scalanative.unsafe.fromCString` after ensuring null termination, OR use
  `java.nio.charset.StandardCharsets.UTF_8` (Scala Native provides the JDK charset APIs since 0.5).
  Simplest: `new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8)` — this
  works on Scala Native 0.5+ with JDK charset support enabled.
  The shared file declares the signature via `expect`-object pattern or a conditional import.

### Old-format pre-MajorVersion-28 check
From `TastyHeaderUnpickler.readFullHeader` (line ~89): if `fileMajor <= 27`, dotty throws immediately
with "old behavior before tasty-core 3.0.0-M4". kyo-reflect should fail with
`ReflectError.UnsupportedVersion(found, supported)` in this case too — no special handling needed
beyond the standard version check.

### readEnd semantics
`readEnd()` in dotty (TastyReader.scala):
```scala
def readEnd(): Addr = addr(readNat() + bp)
```
It reads a Nat (the length), then returns `currentPosition + length` as the end address.
In kyo-reflect `ByteView.readEnd()`: reads a Nat from the cursor, then returns `cursor + nat` as an
absolute end index. The caller uses the returned end to either iterate or skip to it with `goto`.

---

## 5. Test-Data Suggestions

### Varint / LEB128 (big-endian base-128, stop-bit on last byte)
Use `readLongNat` encoding: continuation bytes have 0x80 CLEAR, terminating byte has 0x80 SET.

| Value | Expected encoding (hex bytes) | Notes |
|-------|-------------------------------|-------|
| 0 | `80` | single terminating byte with value 0 |
| 127 | `FF` | 0x7F \| 0x80 = 0xFF |
| 128 | `01 80` | 0x01 (continuation), 0x80 (stop) |
| 16383 | `7F FF` | max 2-byte |
| 16384 | `01 00 80` | 3 bytes |
| Int.MaxValue (2147483647) | `07 7F 7F 7F FF` | 5 bytes |
| Long.MaxValue | `00 FF FF FF FF FF FF FF FF FF` — 9 bytes (first byte 0, 8 more) | verify carefully |

For signed readLongInt: the encoding uses 2's complement sign-extension, not zigzag.
- `-1`: single byte `FF` (0x7F | 0x80), first-byte sign extension: `(0xFF << 1).toByte >> 1` = -1, no continuation since b=0xFF has 0x80 set
- `Int.MinValue` (`-2147483648`): multi-byte, verify against `TastyBuffer.writeLongInt` output

### UUID round-trip
Bytes (16 hex): `5C A1 AB 1F 00 11 22 33 44 55 66 77 88 99 AA BB`
First 8 bytes (msb): `0x5CA1AB1F00112233L`
Last 8 bytes (lsb): `0x445566778899AABBL`
Expected hex string: `"5ca1ab1f001122334455667788..." ` (format to taste)

### Header sequences
1. **Valid header** (28.8.0, zero-length tooling string, fixed UUID):
   `[5C A1 AB 1F] [82 80 80 80 80] ...` — note: version Nats are encoded as big-endian base-128.
   For 28: `(28 & 0x7F) | 0x80 = 0x9C` — single terminating byte.
   For 8: `0x88`. For 0: `0x80`.
   Minimal header bytes: `5C A1 AB 1F 9C 88 80 80 <16 UUID bytes>`
   (tooling string length 0 = single byte `0x80`, then 16 UUID bytes)

2. **Wrong magic** (e.g., `DE AD BE EF ...`): must produce `ReflectError.CorruptedFile`
3. **Major version = 99** (encoded as `0xE3 0x80`): must produce `ReflectError.UnsupportedVersion`
4. **Minor = 7, experimental = 0**: succeeds (stable backwards compatible)
5. **Minor = 9, experimental = 0**: fails (forward incompatible — minor > supported)
6. **Minor = 8, experimental = 1**: fails (experimental mismatch, since supportedExperimental = 0)
7. **Truncated after magic** (only 4 bytes total): must produce `ReflectError.MalformedSection` or
   `ArrayIndexOutOfBoundsException` — the plan calls for `AIOOBE` to be allowed at parser level
   (see execution-plan.md line 84); wrap in `Abort.fail(ReflectError.MalformedSection(...))` at the
   `TastyHeader.read` boundary.

### UTF-8 tricky sequences
- ASCII only: `[72 65 6C 6C 6F]` = "hello"
- 2-byte UTF-8: `[C3 A9]` = "é" (U+00E9)
- 3-byte UTF-8: `[E4 B8 AD]` = "中" (U+4E2D)
- 4-byte UTF-8 / surrogate pair: `[F0 9F 98 80]` = "😀" (U+1F600)
  - On JVM: surrogate pair, `String.length == 2`
  - On JS / Native: single character, `String.length == 1` (platform-specific)
- Offset decode: array = `[FF E4 B8 AD FF]`, offset=1, length=3 → "中"

---

## 6. Anti-Flakiness Deltas

Phase 1 is pure decode logic: no concurrency, no IO timing, no file system access.

Potential flake sources and mitigations:

1. **Platform-specific byte sign extension**: `Array[Byte]` values are signed on JVM. Always mask
   with `& 0xff` or `& 0x7f` before arithmetic to avoid sign-extension bugs. Tests that pass on JVM
   but fail on JS/Native are almost always this.

2. **UTF-8 surrogate pairs on JVM vs JS/Native**: U+1F600 has `String.length == 2` on JVM (UTF-16
   surrogate pair) and `String.length == 1` on JS/Native (single code point). Tests must not assert
   `String.length` cross-platform for 4-byte sequences; assert the character content instead.

3. **TextDecoder availability on JS**: `js.Dynamic.global.TextDecoder` is available in all modern
   JS runtimes (Node 11+, all browsers). If targeting older Node, fall back to `Buffer.from(...)` in
   a platform-specific way. Tests run against Node.js in the kyo build; no issue expected.

4. **No file I/O in tests**: ALL test byte arrays must be constructed inline in test bodies as
   `Array[Byte]` literals. No reading from classpath resources or temp files. This ensures tests
   run identically on all three platforms.

5. **No `System.currentTimeMillis` or randomness**: pure deterministic decode. No flake source there.

6. **LEB128 encoding formula**: the plan incorrectly describes zigzag encoding; the impl will use
   the correct 2's complement approach. If the implementation accidentally uses zigzag, the
   `readInt(-1)` and `readInt(Int.MinValue)` test cases will catch it immediately.

---

## 7. Concerns

### Concern 1 (BLOCKING): Signed integer encoding is NOT zigzag
**Plan line 59** states: "LEB128 signed using zigzag encoding, matching `dotty.tools.tasty.TastyBuffer.readInt` semantics: the raw LEB128 natural is decoded first, then `(n >>> 1) ^ -(n & 1)` maps it to the signed value".

This is incorrect. The actual dotty implementation (`TastyReader.readLongInt`, `TastyBuffer.writeLongInt`) uses 2's complement big-endian base-128, not zigzag. There is no zigzag formula in the dotty TASTy codebase. The sign is conveyed by sign-extending bit 6 of the first byte, then shifting left 7 bits per subsequent byte.

Implementing zigzag would produce wrong signed values for every negative integer and any positive integer > 63. The test cases at plan lines 92–93 (`readInt(-1)`, `readInt(Int.MinValue)`) would catch this immediately if the test vectors are computed from the correct encoding.

Recommendation: the implementer should use `TastyReader.readLongInt` as the verbatim reference and compute test vectors by examining actual TASTy file bytes or by running `TastyBuffer.writeLongInt` against known values.

### Concern 2 (MINOR): Version check — experimental handling
**Plan line 66** states the version policy as "experimental == 0 || experimental == supportedExperimental". The dotty formula requires BOTH `fileMinor == compilerMinor AND fileExperimental == compilerExperimental` for experimental files to be readable. A file with experimental=1 and minor=7 (older than current 8) is NOT readable, contrary to what the plan's OR condition might imply. The implementation should copy the dotty `isVersionCompatible` logic verbatim to avoid subtle mismatches.

Plan test 24 (`experimental = 1` when `supportedExperimental = 0`) correctly expects failure. But
plan test 23 (`minor = 9, experimental = 0`) says "succeeds", which contradicts the dotty formula:
minor=9 > supported minor=8 means `fileMinor < compilerMinor` is false, so this would FAIL. The
test plan description appears to say "minor <= supported" but then gives `minor=9` as the example.
If `supportedMinor = 8` and `fileMinor = 9`, the file is forward-incompatible and must fail.
Verify test 23's intent before implementing; if `minor=9` is meant to test forward-incompatibility
failure, the test assertion should be `Abort.fail(UnsupportedVersion(...))` not success.

### Concern 3 (MINOR): ByteView as sealed trait vs final class
The DESIGN.md (Section 5, line 175) shows `ByteView` as a `final class`. The execution plan (line 59) promotes it to a `sealed trait ByteView` with a `Heap(bytes, start, end)` case. These are not in conflict (trait is the plan's more recent decision), but the impl should match the plan's trait design, not the design doc's class. The prep doc uses the plan as authoritative.

### Concern 4 (MINOR): Mapped variant of ByteView
The plan mentions a `Mapped` stub for mmap support (line 59). Phase 1 is JVM-only for the mmap path; `Mapped` should be a sealed stub (no actual mmap wiring) so the sealed hierarchy compiles. Do not implement mmap in Phase 1.

### Concern 5 (INFORMATIONAL): `readByte()` returns Byte (signed) vs Int (unsigned)
The dotty `TastyReader.readByte()` returns `Int` (masked with `& 0xff`). kyo-reflect's `ByteView.readByte()` signature per the plan returns `Byte` (signed). This is fine for correctness as long as callers mask appropriately. Tests for magic byte comparison must account for the sign: `0x5C.toByte`, `0xA1.toByte` (= -95), etc. Alternatively, provide a `readUByte(): Int` helper that masks and use it in the magic check.
