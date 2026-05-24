# Phase 2 Prep — Name Table + Section Index + Attributes

## 1. Verbatim API Signatures

### Phase 1 APIs Phase 2 Will Call

All Phase 1 files are present on disk. Confirmed paths:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala`
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/Varint.scala`
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TastyFormat.scala`

#### ByteView (sealed trait, line 13)

```scala
// ByteView.scala, line 13
sealed trait ByteView:
    def peekByte(at: Int): Byte              // absolute position, no cursor advance
    def readByte(): Byte                     // read cursor, advance by 1
    def readNat(): Int                       // unsigned LEB128, delegates to Varint
    def readInt(): Int                       // signed 2's complement big-endian base-128
    def readLongNat(): Long                  // unsigned LEB128, 64-bit
    def readEnd(): Int                       // reads Nat length, returns cursor + length (absolute end)
    def subView(from: Int, until: Int): ByteView
    def goto(addr: Int): Unit
    def remaining: Int                       // end - cursor
    def position: Int

// ByteView.scala, line 50
object ByteView:
    def apply(bytes: Array[Byte]): Heap                      // full array view
    def apply(bytes: Array[Byte], start: Int, end: Int): Heap // slice view

// ByteView.scala, line 64
final class Heap(val bytes: Array[Byte], val start: Int, val end: Int) extends ByteView
```

Important: `readByte()` returns `Byte` (signed, -128..127). Callers that need unsigned values must mask: `view.readByte() & 0xff`.

#### Varint (object, Varint.scala line 19)

```scala
object Varint:
    def readNat(view: ByteView): Int        // unsigned, big-endian base-128
    def readLongNat(view: ByteView): Long   // unsigned, 64-bit
    def readInt(view: ByteView): Int        // signed 2's complement, NOT zigzag
    def readLongInt(view: ByteView): Long   // signed 2's complement, 64-bit
```

#### TastyFormat (object, TastyFormat.scala line 17)

```scala
object TastyFormat:
    val MagicBytes: Array[Int]            // Array(0x5C, 0xA1, 0xAB, 0x1F)
    final val MajorVersion: Int = 28
    final val MinorVersion: Int = 8
    final val ExperimentalVersion: Int = 0
    final val ASTsSection: String = "ASTs"
    final val PositionsSection: String = "Positions"
    final val CommentsSection: String = "Comments"
    final val AttributesSection: String = "Attributes"

    object NameTags:
        final val UTF8: Int           = 1
        final val QUALIFIED: Int      = 2
        final val EXPANDED: Int       = 3
        final val EXPANDPREFIX: Int   = 4
        final val UNIQUE: Int         = 10
        final val DEFAULTGETTER: Int  = 11
        final val SUPERACCESSOR: Int  = 20
        final val INLINEACCESSOR: Int = 21
        final val BODYRETAINER: Int   = 22
        final val OBJECTCLASS: Int    = 23
        final val SIGNED: Int         = 63
        final val TARGETSIGNED: Int   = 62

    // attribute tag constants (see Section 3 below)
    final val SCALA2STANDARDLIBRARYattr: Int = 1
    final val EXPLICITNULLSattr: Int         = 2
    final val CAPTURECHECKEDattr: Int        = 3
    final val WITHPUREFUNSattr: Int          = 4
    final val JAVAattr: Int                  = 5
    final val OUTLINEattr: Int               = 6
    final val SOURCEFILEattr: Int            = 129
```

Note: attribute tag constants are NOT currently in TastyFormat.scala in kyo-reflect. Phase 2 must add them. Do NOT add WITHPUREFUNSattr (internal compiler flag, not surfaced in FileAttributes).

#### Utf8 (platform-specific, shared declaration)

```scala
// kyo-reflect/{jvm,js,native}/src/main/scala/kyo/internal/reflect/binary/Utf8.scala
object Utf8:
    def decode(bytes: Array[Byte], offset: Int, length: Int): String
```

### AtomicReference Usage Pattern

`java.util.concurrent.atomic.AtomicReference` is available cross-platform (JVM, JS via Scala.js, Native via Scala Native's java.util shim). Phase 2 uses it for:

```scala
// Memo.scala — lazy one-time computation
final class Memo[A](init: () => A):
    private val ref = new java.util.concurrent.atomic.AtomicReference[A | Null](null)
    def get(): A =
        val cached = ref.get()
        if cached != null then cached.asInstanceOf[A]
        else
            val v = init()
            ref.compareAndSet(null, v)
            ref.get().asInstanceOf[A]  // always return what's in ref to handle races

// SingleAssign.scala — one-time assignment with failure on double-set
final class SingleAssign[A]:
    private val ref = new java.util.concurrent.atomic.AtomicReference[A | Null](null)
    def set(a: A): Unit =
        if !ref.compareAndSet(null, a) then
            throw new IllegalStateException(s"SingleAssign already set")
    def get(): A =
        val v = ref.get()
        if v == null then throw new IllegalStateException("SingleAssign not yet set")
        else v.asInstanceOf[A]

// Interner.scala — sharded linear-probe table
// Each shard is AtomicReference[Array[Entry]]
// Grow: build a new Array, copy entries, CAS (retry on failure — the loop retries from scratch after any CAS failure)
private val shards: Array[java.util.concurrent.atomic.AtomicReference[Array[Interner.Entry]]] =
    Array.tabulate(numShards)(_ => new java.util.concurrent.atomic.AtomicReference(new Array[Interner.Entry](initialCapacity)))
```

### Arrays.equals Usage

```scala
// For byte-slice equality without String materialization
java.util.Arrays.equals(
    a: Array[Byte], aFrom: Int, aTo: Int,
    b: Array[Byte], bFrom: Int, bTo: Int
): Boolean
```

This is the JDK 9+ overload. On JS (Scala.js) and Native (Scala Native), the four-argument `java.util.Arrays.equals(Array[Byte], Array[Byte]): Boolean` is available but the six-argument range overload may not be. Safe cross-platform approach: compare length first, then use the two-array overload on full-array slices OR write a manual loop.

Cross-platform safe pattern:
```scala
def bytesEqual(a: Array[Byte], aOff: Int, aLen: Int, b: Array[Byte], bOff: Int, bLen: Int): Boolean =
    aLen == bLen && {
        var i = 0
        var eq = true
        while eq && i < aLen do
            if a(aOff + i) != b(bOff + i) then eq = false
            i += 1
        eq
    }
```

### Memo[String] Origin

`Memo[String]` is defined in Phase 2 (`Memo.scala`). It is NOT from Phase 1. The `Interner.Entry` stores a `Memo[String]` that decodes the interned bytes to a `String` on first `.get()` call.

### Kyo Core APIs Used

```scala
// Abort.fail — let inference pick E
Abort.fail(ReflectError.MalformedSection("Names", reason))

// Sync.defer — for side-effecting decode steps
Sync.defer { view.readByte() }

// Maybe — for optional results
Maybe[String]   // Present("Foo.scala") or Absent
```

---

## 2. Verbatim Dotty Name-Table Specs

Source: `tasty-core_3-3.8.3-sources.jar`, `dotty/tools/tasty/TastyFormat.scala` (lines 14-53).

### Name-Record Grammar (verbatim from TastyFormat.scala, lines 33-53)

```
Name  = UTF8              Utf8
        QUALIFIED         Length qualified_NameRef selector_NameRef     -- A.B
        EXPANDED          Length qualified_NameRef selector_NameRef     -- A$$B
        EXPANDPREFIX      Length qualified_NameRef selector_NameRef     -- A$B

        UNIQUE            Length separator_NameRef uniqid_Nat underlying_NameRef?
        DEFAULTGETTER     Length underlying_NameRef index_Nat

        SUPERACCESSOR     Length underlying_NameRef    -- super$A
        INLINEACCESSOR    Length underlying_NameRef    -- inline$A
        OBJECTCLASS       Length underlying_NameRef    -- A$  (module class name)
        BODYRETAINER      Length underlying_NameRef    -- A$retainedBody

        SIGNED            Length original_NameRef resultSig_NameRef ParamSig*
        TARGETSIGNED      Length original_NameRef target_NameRef resultSig_NameRef ParamSig*

ParamSig = Int  -- negative: abs value = length of type param section
                -- positive: NameRef for fully qualified name of term param

NameRef  = Nat  -- ordinal in name table, starting from 1
Utf8Ref  = Nat  -- ordinal of a UTF8 entry in name table, starting from 1
```

### UTF8 Record Layout

```
tag=1 (UTF8)  length_Nat  byte* (length raw UTF-8 bytes)
```

- Read `tag = view.readByte() & 0xff`
- If `tag == 1` (UTF8): read `length = view.readNat()`, then `length` raw bytes
- Decode via `Utf8.decode(bytes, offset, length)` and intern

### Compound Name Record Layout

All compound name records follow:
```
tag_Byte  length_Nat  <payload up to cursor+length>
```

The length is read via `view.readEnd()` which calls `readNat()` and returns `cursor + nat` (absolute end). The payload is `view.subView(current_position, end)` for reading sub-fields.

### NameRef Decoding

All `NameRef` fields are read as `view.readNat()`. NameRefs are 1-based indices into the already-decoded name table array. NameRef 0 is invalid. Entry at index `ref - 1` in the array is the resolved name.

### Name-Tag Canonical String Representations

These are the string forms that `NameUnpickler` should produce for each compound tag:

| Tag | Value | String Form |
|-----|-------|-------------|
| UTF8 | 1 | raw UTF-8 string |
| QUALIFIED | 2 | `"<prefix>.<selector>"` (prefix and selector are NameRefs) |
| EXPANDED | 3 | `"<prefix>$$<selector>"` |
| EXPANDPREFIX | 4 | `"<prefix>$<selector>"` |
| UNIQUE | 10 | `"<separator><num>"` or `"<underlying><separator><num>"` |
| DEFAULTGETTER | 11 | `"<underlying>$default$<index>"` |
| SUPERACCESSOR | 20 | `"super$<underlying>"` |
| INLINEACCESSOR | 21 | `"inline$<underlying>"` |
| OBJECTCLASS | 23 | `"<underlying>$"` |
| BODYRETAINER | 22 | `"<underlying>$retainedBody"` |
| SIGNED | 63 | `"<original>:<resultSig>(<paramSig1>,<paramSig2>,...)"` |
| TARGETSIGNED | 62 | `"<original>[<target>]:<resultSig>(<paramSig1>,...)"` |

Note: these canonical forms are implementation choices for kyo-reflect's `Name.asString`. The dotty compiler does NOT mandate this string format for SIGNED/TARGETSIGNED; it only needs to be round-trippable and human-readable for debug.

### Section-Index Layout (verbatim from TastyFormat.scala, line 30)

```
Section = NameRef Length Bytes
Length  = Nat         -- length of rest of entry in bytes
```

The section table immediately follows the name table in the file. The file macro-format:

```
File = Header majorVersion_Nat minorVersion_Nat experimentalVersion_Nat VersionString UUID
       nameTable_Length Name* Section*
```

So: after the header and UUID, a `nameTable_Length` Nat is read (= total byte count of name table). Then `nameTable_Length` bytes are the name table. Then sections follow until end of file.

Each section:
1. `sectionNameRef = view.readNat()` — NameRef into the already-decoded name table
2. `sectionLength = view.readNat()` — byte count of section payload
3. section payload starts at current position, length = sectionLength bytes

`SectionIndex.read` must iterate until `view.remaining == 0`, collecting `(name: String, offset: Int, length: Int)` per section.

---

## 3. Verbatim AttributeUnpickler Format

Source: `dotty/tools/tasty/TastyFormat.scala`, lines 282-301 and 635-654.

### Attribute Grammar (verbatim)

```
Standard Section: "Attributes" Attribute*

Attribute = SCALA2STANDARDLIBRARYattr  -- tag=1, no payload
            EXPLICITNULLSattr          -- tag=2, no payload
            CAPTURECHECKEDattr         -- tag=3, no payload
            WITHPUREFUNSattr           -- tag=4, no payload (not surfaced)
            JAVAattr                   -- tag=5, no payload
            OUTLINEattr                -- tag=6, no payload
            SOURCEFILEattr Utf8Ref     -- tag=129, payload=Nat (NameRef to UTF8 name)

Attribute Category 1 (tags 1-32):   tag only (boolean flags)
Attribute Category 3 (tags 129-160): tag + Utf8Ref (Nat)
```

### Attribute Tag Constants (verbatim from TastyFormat.scala lines 635-654)

```scala
// TastyFormat.scala, line 635
// Attribute Category 1 (tags 1-32): tag only
def isBooleanAttrTag(tag: Int): Boolean = 1 <= tag && tag <= 32
final val SCALA2STANDARDLIBRARYattr = 1   // file is part of Scala 2 stdlib
final val EXPLICITNULLSattr = 2           // compiled with -Yexplicit-nulls
final val CAPTURECHECKEDattr = 3          // compiled with -Ycc
final val WITHPUREFUNSattr = 4            // compiled with -Ywith-pure-funs (internal, skip)
final val JAVAattr = 5                    // this is a Java-originated TASTy file
final val OUTLINEattr = 6                 // outline TASTy (no bodies)

// Attribute Category 2 (tags 33-128): unassigned

// Attribute Category 3 (tags 129-160): tag + Utf8Ref
def isStringAttrTag(tag: Int): Boolean = 129 <= tag && tag <= 160
final val SOURCEFILEattr = 129            // source file name (Utf8Ref into name table)

// Attribute Category 4 (tags 161-255): unassigned
```

### FileAttributes Case Class

```scala
// AttributeUnpickler.scala
final case class FileAttributes(
    explicitNulls:        Boolean,
    captureChecked:       Boolean,
    isJava:               Boolean,
    isOutline:            Boolean,
    scala2StandardLibrary: Boolean,
    sourceFile:           Maybe[String]
)

object FileAttributes:
    val default: FileAttributes = FileAttributes(
        explicitNulls = false,
        captureChecked = false,
        isJava = false,
        isOutline = false,
        scala2StandardLibrary = false,
        sourceFile = Absent
    )
```

### AttributeUnpickler Read Protocol

```scala
object AttributeUnpickler:
    def read(view: ByteView, names: Array[Reflect.Name]): FileAttributes < Abort[ReflectError] =
        // view is already positioned at the start of the Attributes section payload
        // read until view.remaining == 0
        var explicitNulls        = false
        var captureChecked       = false
        var isJava               = false
        var isOutline            = false
        var scala2StandardLibrary = false
        var sourceFile: Maybe[String] = Absent
        while view.remaining > 0 do
            val tag = view.readByte() & 0xff
            tag match
                case TastyFormat.SCALA2STANDARDLIBRARYattr => scala2StandardLibrary = true
                case TastyFormat.EXPLICITNULLSattr         => explicitNulls = true
                case TastyFormat.CAPTURECHECKEDattr         => captureChecked = true
                case TastyFormat.WITHPUREFUNSattr           => () // skip, not surfaced
                case TastyFormat.JAVAattr                   => isJava = true
                case TastyFormat.OUTLINEattr                => isOutline = true
                case TastyFormat.SOURCEFILEattr             =>
                    val nameRef = view.readNat()  // Utf8Ref (1-based)
                    sourceFile = Present(names(nameRef - 1).asString)
                case unknown =>
                    // Forward-compatible: unknown boolean tags (1-32) have no payload; skip
                    // Unknown string tags (129-160) have one Nat payload; skip it
                    if TastyFormat.isBooleanAttrTag(unknown) then () // no payload
                    else if TastyFormat.isStringAttrTag(unknown) then view.readNat() // skip Utf8Ref
                    else Abort.fail(ReflectError.MalformedSection("Attributes",
                        s"Unknown attribute tag $unknown at position ${view.position}"))
        FileAttributes(explicitNulls, captureChecked, isJava, isOutline, scala2StandardLibrary, sourceFile)
```

Note: `isBooleanAttrTag` and `isStringAttrTag` are currently defined as `def` methods in dotty's TastyFormat, not as constants. Phase 2 must either add them to kyo-reflect's `TastyFormat.scala` or inline the range checks.

---

## 4. File:Line Anchors

### Files Phase 2 Creates (none exist yet)

| File | Package | Purpose |
|------|---------|---------|
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Interner.scala` | `kyo.internal.reflect.symbol` | Sharded intern table |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Memo.scala` | `kyo.internal.reflect.symbol` | Lazy one-time compute |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/SingleAssign.scala` | `kyo.internal.reflect.symbol` | One-time assign |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/NameUnpickler.scala` | `kyo.internal.reflect.tasty` | Name table decoder |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AttributeUnpickler.scala` | `kyo.internal.reflect.tasty` | Attributes section decoder |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/SectionIndex.scala` | `kyo.internal.reflect.tasty` | Section table index |
| `kyo-reflect/shared/src/test/scala/kyo/InternerTest.scala` | `kyo` | 7 intern tests |
| `kyo-reflect/shared/src/test/scala/kyo/NameUnpicklerTest.scala` | `kyo` | 5 name decode tests |
| `kyo-reflect/shared/src/test/scala/kyo/AttributeUnpicklerTest.scala` | `kyo` | 4 attribute tests |

### Files Phase 2 Modifies

| File | Lines Affected | Change |
|------|---------------|--------|
| `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 26-31 | `opaque type Name = String` → `opaque type Name = Interner.Entry`; update `Name.apply`, `Name.asString`, `CanEqual` |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TastyFormat.scala` | after line 207 | Add attribute tag constants: `SCALA2STANDARDLIBRARYattr`, `EXPLICITNULLSattr`, `CAPTURECHECKEDattr`, `WITHPUREFUNSattr`, `JAVAattr`, `OUTLINEattr`, `SOURCEFILEattr`; add `isBooleanAttrTag` and `isStringAttrTag` helpers |

### Existing Reflect.scala Lines Referenced

```
line 26: opaque type Name = String           -- changes to Interner.Entry
line 27: object Name:
line 28:   def apply(s: String): Name = s    -- changes: intern the string's bytes
line 29:   given CanEqual[Name, Name] = ...  -- stays, equality becomes byte-level
line 30:   extension (n: Name)
line 31:     def asString: String = n        -- changes: n.string (triggers Memo.get)
```

---

## 5. Edge Cases and Gotchas

### SIGNED Name Record — Recursive Read Protocol

SIGNED is the most complex name tag. Verbatim grammar:

```
SIGNED Length original_NameRef resultSig_NameRef ParamSig*
ParamSig = Int  -- if negative: abs = type param section length; if positive: NameRef
```

Full read protocol for SIGNED:
```
1. tag = readByte() & 0xff  -- == 63
2. end = view.readEnd()     -- reads Nat, returns cursor + nat
3. original   = view.readNat()  -- NameRef, 1-based, already in names table
4. resultSig  = view.readNat()  -- NameRef
5. while view.position < end:
      paramSig = view.readInt()   -- signed Int (NOT Nat)
      -- if paramSig < 0: abs value = type param section length (no NameRef lookup)
      -- if paramSig > 0: treat as NameRef into names table
      -- if paramSig == 0: invalid, emit MalformedSection
6. canonical string: "<original>:<resultSig>(<comma-separated paramSigs>)"
```

For TARGETSIGNED (tag=62):
```
1. tag = readByte() & 0xff  -- == 62
2. end = view.readEnd()
3. original   = view.readNat()   -- NameRef
4. target     = view.readNat()   -- NameRef (the @targetName value)
5. resultSig  = view.readNat()   -- NameRef
6. while view.position < end:
      paramSig = view.readInt()  -- same signed Int semantics as SIGNED
7. canonical string: "<original>[<target>]:<resultSig>(<paramSigs>)"
```

Critical: `paramSig` is decoded via `view.readInt()` (signed), NOT `view.readNat()` (unsigned). Negative values have special meaning (type param section length).

### UNIQUE Name Record — Optional underlying_NameRef

```
UNIQUE Length separator_NameRef uniqid_Nat underlying_NameRef?
```

The `underlying_NameRef` is optional: it is present if and only if `view.position < end` after reading `uniqid_Nat`. Check `remaining` against `end` before attempting to read it.

### OBJECTCLASS and Reflect.Name Representation

`OBJECTCLASS Length underlying_NameRef` encodes the name of a module class: `<underlying>$`.

The public `Reflect.Name.asString` for an OBJECTCLASS entry returns `"<underlying>$"` (with the dollar suffix). The `$` is part of the canonical name string. Phase 3 (flag decode) uses `Flag.Module` to identify module class symbols; callers MUST NOT rely on the trailing `$` to detect modules.

### Sharded Intern Table — Implementation

Design doc Section 8: 32 shards, each `AtomicReference[Array[Entry]]`, linear-probe.

#### Hash-to-Shard Mapping
Use LOW bits of the hash:
```scala
val shardIdx = hash & (numShards - 1)  // requires numShards to be a power of 2
```
(Low bits are faster and avoid bias from hash functions that distribute well in high bits.)

#### Entry Structure
```scala
final class Entry(
    val hash: Int,
    val bytes: Array[Byte],
    val offset: Int,
    val length: Int,
    val string: Memo[String]  // lazy UTF-8 decode
)
```

`Reflect.Name` becomes `opaque type Name = Interner.Entry`.

#### CAS Grow Strategy
When `load factor > 0.75` (filled slots / array length):
1. Allocate new `Array[Entry](oldLength * 2)`.
2. Re-hash all non-null existing entries into the new array.
3. `shard.compareAndSet(old, new)`: if CAS fails (another thread grew concurrently), restart the `intern` call from step 1 (read the now-current array, re-probe, re-try grow if still needed).

#### Concurrent Insert of Same Name
Two threads probing for the same name concurrently:
- Thread A probes, finds empty slot, inserts Entry.
- Thread B probes the SAME slot, finds Entry already there, returns it.

The probe loop must be:
```
1. Probe from slot (hash & mask):
   a. If slot == null: try to CAS null -> newEntry. If CAS succeeds: return newEntry.
      If CAS fails: re-read that slot; some other thread inserted. If it's the same key, return it.
      If it's a different key (collision), continue probing.
   b. If slot.hash == hash && bytesEqual(slot, ...) : return existing entry (dedup).
   c. Otherwise: linear-probe to next slot.
```

This guarantees: for any two threads interning the same byte sequence, both return the same `Entry` object (reference equality).

#### Name Lookup by Byte-Slice (vs String equality)

Phase 2 uses `bytesEqual(a, aOff, aLen, b, bOff, bLen)` (manual loop, cross-platform). The dotty equivalent is `tasty-query`'s `NameCache` which uses `ConcurrentHashMap[String, Name]` — it DOES materialize strings and uses String equality. kyo-reflect avoids String materialization during lookup; only `Name.asString` triggers it.

---

## 6. Test-Data Suggestions

### Round-Trip UTF8 Names

| Input String | UTF-8 hex bytes | Name-record bytes (tag + length + payload) |
|---|---|---|
| `"hello"` | `68 65 6C 6C 6F` (5 bytes) | `[01] [85] [68 65 6C 6C 6F]` — tag=UTF8(1), length=5 encoded as `(5|0x80)=0x85` |
| `"scala"` | `73 63 61 6C 61` (5 bytes) | `[01] [85] [73 63 61 6C 61]` |
| `"中"` | `E4 B8 AD` (3 bytes) | `[01] [83] [E4 B8 AD]` |

Note: LEB128 length for small values: `n` encoded as `n | 0x80` (single terminating byte).

### QUALIFIED Name Record (two NameRefs)

Assume names[0] = "scala" (NameRef=1), names[1] = "collection" (NameRef=2).
A QUALIFIED name for "scala.collection":
```
tag=2 (QUALIFIED)
Length payload = 2 Nats = (1|0x80)(2|0x80) = [0x81, 0x82] = 2 bytes
Full record: [02] [82] [81] [82]
  -- tag=2, length=2, qualified_NameRef=1, selector_NameRef=2
```
Decoded string: `"scala.collection"`.

### SIGNED Name Record

Assume names[0]="apply" (ref=1), names[1]="scala.Int" (ref=2), names[2]="scala.String" (ref=3).
SIGNED name for `apply: (String): Int`:
```
tag=63 (SIGNED)
original_NameRef=1, resultSig_NameRef=2, paramSigs=[3]
Payload: [81] [82] (signed int 3 encoded as positive = [83])
-- readInt(3): single byte, sign bit clear, stop bit set: (3<<1).toByte >> 1 = 3, then & 0x80 != 0 so stop. Actually: first byte = 3|0x80 = 0x83. readInt: b = 0x83, x = (0x83<<1).toByte >> 1 = 6.toByte >> 1 = 3. b & 0x80 != 0, stop. x = 3.
payload bytes: [01|0x80=0x81] [02|0x80=0x82] [03|0x80=0x83]  (three NameRef Nats)
Length = 3
Full record: [3F] [83] [81] [82] [83]
```

### Concurrency Test Pattern

```scala
// InternerTest — concurrent intern correctness
val interner = Interner(shards = 32)
val bytes = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)
val N = 100
val M = 10 // distinct names

// Spawn N fibers each interning M names; assert intern table has exactly M entries
// and all results for the same name are reference-equal (eq)
val results = Async.parallel(
    (0 until N).map(_ =>
        Sync.defer {
            (0 until M).map(i =>
                interner.intern(s"name_$i".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, ???)
            )
        }
    )
)
// After: for each i, all N results(j)(i) must be reference-equal (eq) to results(0)(i)
// Interner.size must equal M
```

Use `Async.parallel` or `Latch` (not Thread.sleep) for synchronization:

```scala
// Anti-flake pattern: use Latch to ensure all fibers start before racing
val latch = new java.util.concurrent.CountDownLatch(1)
val fibers = (0 until N).map(_ => Async.run(Sync.defer { latch.await(); interner.intern(bytes, 0, bytes.length) }))
latch.countDown()
val names = fibers.map(_.safe.get)
assert(names.forall(_ eq names.head))
```

Note: In kyo-reflect tests, `Sync.defer` + `Async.run` is the pattern; avoid raw `Thread` creation.

### Attribute Test Byte Sequences

Synthesized Attributes section payload (no names needed for boolean-only flags):

```scala
// isJava = true, explicitNulls = true
val attrs = Array[Byte](
    5.toByte,   // JAVAattr
    2.toByte    // EXPLICITNULLSattr
)
// Pass to AttributeUnpickler.read(ByteView(attrs), names = Array.empty)
// Expect: FileAttributes(explicitNulls=true, isJava=true, ...)
```

```scala
// sourceFile = "Foo.scala" — requires a names array with that string at index 0
val namesArray = Array(Reflect.Name(??))  // intern "Foo.scala" first
val attrs = Array[Byte](
    (129).toByte,   // SOURCEFILEattr (0x81)
    (1 | 0x80).toByte  // Utf8Ref = 1, encoded as single terminating LEB128 byte
)
```

---

## 7. Anti-Flakiness Deltas

### Sharded Interner Concurrency Test
- Use `Async.parallel` or `CountDownLatch` NOT `Thread.sleep` for synchronization.
- The test must verify BOTH that results are reference-equal AND that the intern table does not contain duplicate entries for the same byte sequence.
- Run with at least 32 concurrent workers to exercise cross-shard and same-shard racing.
- Test on JVM only for the concurrency assertion (JS is single-threaded; Native uses green threads unless the native test runner uses posix threads). Mark the concurrency test `@js.annotation.JSExportTopLevel` or use a platform guard to skip on JS/Native if Async does not parallelize there.

### UTF-8 Platform Differences for Non-BMP Characters

U+1F600 (😀, 4-byte UTF-8 `F0 9F 98 80`):
- JVM: `String.length == 2` (UTF-16 surrogate pair `😀`)
- JS/Native: `String.length == 1` (single code point)

The `NameUnpicklerTest` MUST NOT assert `String.length` on 4-byte sequences. Assert `String.codePointAt(0) == 0x1F600` instead (works on all platforms). Alternatively use a name that fits in BMP for test fixtures.

### Arrays.equals Cross-Platform

The 6-argument range overload `Arrays.equals(byte[], int, int, byte[], int, int)` is JDK 9+. On Scala Native (which emulates JDK via a subset), it may not be available. Use the manual `bytesEqual` loop from Section 5 to be safe, OR verify at test time that the overload is present.

### NameUnpickler: Classpath Resource Loading

Phase 2 tests use `getClass.getResourceAsStream` for fixture TASTy bytes. On Scala Native, resources must be embedded at link time. Ensure:
1. `Test/resourceDirectory` includes the fixture `.tasty` bytes in `kyo-reflect/shared/src/test/resources/`.
2. The resource path in `getResourceAsStream` uses leading `/` for absolute classpath lookup: `getClass.getResourceAsStream("/kyo/fixtures/FixtureClasses.tasty")`.
3. On Native, Scala Native's test runner provides a `getClass.getResourceAsStream` shim backed by `EmbeddedResource`; the path resolution is the same as JVM if the sbt resource config is correct.

### Forward-Compatibility in AttributeUnpickler

The spec says: "Attribute tags cannot be repeated" and "Attributes are ordered by the tag ordinal." kyo-reflect must handle future (unknown) tags gracefully by using the category-based skip logic (Section 3, isBooleanAttrTag / isStringAttrTag) rather than failing on unknown tags 1-32 or 129-160. Only tags outside all defined categories (33-128 and 161-255, currently unassigned) should produce `MalformedSection`.

---

## 8. Concerns

### Concern 1 (BLOCKING): Name Table Count Field

The plan (line 135) says `NameUnpickler.read` reads "count (LEB128 nat), then for each entry a tag byte then the name bytes." However, the TastyFormat.scala macro-format shows:

```
File = Header ... nameTable_Length Name* Section*
```

There is a `nameTable_Length` (a Nat measuring the total BYTE COUNT of the name table), not a count of entries. The name table is delimited by byte count, not entry count. The reader must read until `cursor >= nameTableEnd`, not loop `count` times.

Verbatim from TastyHeaderUnpickler: after the UUID, the next field in the file is `readNat()` which gives the byte length of the name table (from `TastyBuffer.writeNat` calls in the compiler's pickle writer). The name-entry count is NOT stored; the reader infers end by byte position.

If Phase 2 implements a fixed-count loop, it will mis-parse any real TASTy file. The read loop must use byte-position termination.

### Concern 2 (MODERATE): Reflect.Name Internal Representation Change

The plan changes `opaque type Name = String` to `opaque type Name = Interner.Entry`. This is a breaking internal change. Any code that currently passes a `Reflect.Name` through an `opaque type` boundary via the `String` representation will need updating.

In the Phase 0 skeleton, `Name.apply(s: String): Name = s` constructs a Name from a String. After Phase 2, `Name.apply` must call the interner: `interner.intern(s.getBytes(UTF_8), 0, ...)`. This requires either:
1. A module-level `Interner` instance accessible from `Reflect.Name`'s companion, OR
2. Deprecating `Name.apply(String)` in favor of interner-only construction.

The plan says "public accessors remain the same shape" — `Name.apply(String)` must still work. A global default `Interner` (e.g., `private val globalInterner = Interner()` in `Reflect`'s companion) can serve `Name.apply` without changing the public signature.

### Concern 3 (MODERATE): `CanEqual[Name, Name]` Semantics After Opaque Type Change

Pre-Phase 2: `CanEqual.derived` works for `opaque type Name = String` because `String` has `CanEqual`.  
Post-Phase 2: `CanEqual[Name, Name]` must be implemented via byte-level equality, NOT reference equality.

The plan says "equality uses byte-level comparison without String materialization." This means `CanEqual` can NOT just use `Interner.Entry`'s reference equality. Either:
- Implement `given Eq[Name]: Eq[Name]` that calls `bytesEqual`, OR
- Rely on reference equality ONLY if the interner guarantees that the same byte sequence always returns the same `Entry` object (which it does — that's the invariant). In that case, reference equality on `Entry` instances IS byte-level equality after interning.

The correct approach: since the interner guarantees unique `Entry` per unique byte sequence, reference equality on interned `Name` values IS correct byte equality. The `CanEqual` can safely delegate to reference equality. Document this invariant explicitly in `Interner.scala`.

### Concern 4 (MINOR): SectionIndex — NameRef Resolution for Section Names

Section names (e.g., `"ASTs"`, `"Attributes"`) are stored as NameRefs into the name table. The section-index reader must resolve NameRefs via the already-decoded `names: Array[Reflect.Name]` from `NameUnpickler`. This means `SectionIndex.read` takes both `view: ByteView` AND `names: Array[Reflect.Name]`.

The plan signature is:
```scala
SectionIndex.read(view: ByteView): SectionIndex < Abort[ReflectError]
```

This is incomplete: the section NameRefs cannot be resolved without the name table. The signature must be:
```scala
SectionIndex.read(view: ByteView, names: Array[Reflect.Name]): SectionIndex < Abort[ReflectError]
```

The supervisor should verify this before implementation.

### Concern 5 (MINOR): UNIQUE Name — Optional underlying_NameRef

The grammar for UNIQUE is:
```
UNIQUE Length separator_NameRef uniqid_Nat underlying_NameRef?
```

The `underlying_NameRef` is optional. The reader must check `view.position < end` after reading `uniqid_Nat` before attempting to read the optional field. The plan does not explicitly address this optional field; the implementer must handle both the 2-field and 3-field forms.

### Concern 6 (INFORMATIONAL): WITHPUREFUNSattr Not Surfaced

`WITHPUREFUNSattr` (tag=4) is a compiler-internal flag for `-Ywith-pure-funs` (a captured checking experiment). It is NOT in `FileAttributes`. The `AttributeUnpickler` must read and skip it silently (it's a Category 1 boolean tag = no payload). Do NOT add a `withPureFuns: Boolean` field to `FileAttributes`.

### Concern 7 (MINOR): Phase 1 TastyHeader.scala Apparently Not Yet on Disk

Only `TastyFormat.scala`, `ByteView.scala`, and `Varint.scala` are confirmed present. `TastyHeader.scala` and `Utf8.scala` (all platform variants) are required by Phase 2 (specifically, `Utf8.decode` is called by `NameUnpickler` to decode UTF8 name entries). Before Phase 2 implementation begins, confirm that all Phase 1 files listed in `execution-plan.md` lines 58-69 are on disk and compile cleanly.
