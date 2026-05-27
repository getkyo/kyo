# Phase 3 Prep — Symbol Pass 1 + Skeleton AST

## 1. Verbatim API Signatures

### Phase 1 / 2 APIs Phase 3 Will Call

All Phase 1 and Phase 2 files are present on disk. Confirmed paths and signatures:

#### ByteView (sealed trait)
Source: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala`

```scala
sealed trait ByteView:
    def peekByte(at: Int): Byte           // absolute position, no cursor advance
    def readByte(): Byte                  // read at cursor, advance by 1; returns signed Byte, mask with & 0xff for unsigned
    def readNat(): Int                    // unsigned LEB128 big-endian base-128 (delegates to Varint.readNat)
    def readInt(): Int                    // signed 2's complement big-endian base-128
    def readLongNat(): Long               // unsigned LEB128 64-bit
    def readEnd(): Int                    // reads Nat length L, returns cursor + L (absolute end address)
    def subView(from: Int, until: Int): ByteView  // sub-window; cursor reset to `from`
    def goto(addr: Int): Unit             // move cursor to absolute position
    def remaining: Int                    // end - cursor
    def position: Int                     // current cursor

object ByteView:
    def apply(bytes: Array[Byte]): Heap                       // full-array view
    def apply(bytes: Array[Byte], start: Int, end: Int): Heap // slice view

final class Heap(val bytes: Array[Byte], val start: Int, val end: Int) extends ByteView
```

Important: `readByte()` returns a signed `Byte`. Phase 3 code reading tags must use `view.readByte() & 0xff` to get an unsigned value.

#### Varint (object)
Source: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/Varint.scala`

```scala
object Varint:
    def readNat(view: ByteView): Int       // unsigned big-endian base-128
    def readLongNat(view: ByteView): Long  // unsigned 64-bit
    def readInt(view: ByteView): Int       // signed 2's complement big-endian base-128
    def readLongInt(view: ByteView): Long  // signed 2's complement 64-bit
```

#### TastyFormat (object)
Source: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TastyFormat.scala`

Full list of constants relevant to Phase 3 (category 1 modifiers and category 5 definition tags):

```scala
object TastyFormat:
    // Modifier tags (Category 1, tag-only, value < 60)
    final val PRIVATE: Int       = 6
    final val PROTECTED: Int     = 8
    final val ABSTRACT: Int      = 9
    final val FINAL: Int         = 10
    final val SEALED: Int        = 11
    final val CASE: Int          = 12
    final val IMPLICIT: Int      = 13
    final val LAZY: Int          = 14
    final val OVERRIDE: Int      = 15
    final val INLINEPROXY: Int   = 16
    final val INLINE: Int        = 17
    final val STATIC: Int        = 18
    final val OBJECT: Int        = 19
    final val TRAIT: Int         = 20
    final val ENUM: Int          = 21
    final val LOCAL: Int         = 22
    final val SYNTHETIC: Int     = 23
    final val ARTIFACT: Int      = 24
    final val MUTABLE: Int       = 25
    final val FIELDaccessor: Int = 26
    final val CASEaccessor: Int  = 27
    final val COVARIANT: Int     = 28
    final val CONTRAVARIANT: Int = 29
    final val HASDEFAULT: Int    = 31
    final val STABLE: Int        = 32
    final val MACRO: Int         = 33
    final val ERASED: Int        = 34
    final val OPAQUE: Int        = 35
    final val EXTENSION: Int     = 36
    final val GIVEN: Int         = 37
    final val PARAMsetter: Int   = 38
    final val EXPORTED: Int      = 39
    final val OPEN: Int          = 40
    final val PARAMalias: Int    = 41
    final val TRANSPARENT: Int   = 42
    final val INFIX: Int         = 43
    final val INVISIBLE: Int     = 44
    final val EMPTYCLAUSE: Int   = 45
    final val SPLITCLAUSE: Int   = 46
    final val TRACKED: Int       = 47
    final val INTO: Int          = 49
    // Category 2 (tag + Nat): SHAREDterm=60, SHAREDtype=61, TERMREFdirect=62, TYPEREFdirect=63,
    //   TERMREFpkg=64, TYPEREFpkg=65, RECthis=66, BYTEconst=67, SHORTconst=68, CHARconst=69,
    //   INTconst=70, LONGconst=71, FLOATconst=72, DOUBLEconst=73, STRINGconst=74
    // Category 3 (tag + AST): THIS=90, QUALTHIS=91, CLASSconst=92, BYNAMEtype=93, BYNAMEtpt=94,
    //   NEW=95, THROW=96, IMPLICITarg=97, PRIVATEqualified=98, PROTECTEDqualified=99, RECtype=100, ...
    // Category 4 (tag + Nat + AST): IDENT=110, IDENTtpt=111, SELECT=112, SELECTtpt=113,
    //   TERMREFsymbol=114, TERMREF=115, TYPEREFsymbol=116, TYPEREF=117, SELFDEF=118, NAMEDARG=119
    // Category 5 (tag + Length + payload): all tags >= 128
    final val firstLengthTreeTag: Int = 128 // == PACKAGE
    final val PACKAGE: Int   = 128
    final val VALDEF: Int    = 129
    final val DEFDEF: Int    = 130
    final val TYPEDEF: Int   = 131
    final val IMPORT: Int    = 132
    final val TYPEPARAM: Int = 133
    final val PARAM: Int     = 134
    final val TEMPLATE: Int  = 156
    final val ANNOTATION: Int = 173
    // ... remaining cat-5 tags (APPLY=136 through HOLE=255)

    // Attribute tags
    final val SCALA2STANDARDLIBRARYattr: Int = 1
    final val EXPLICITNULLSattr: Int         = 2
    final val CAPTURECHECKEDattr: Int        = 3
    final val WITHPUREFUNSattr: Int          = 4
    final val JAVAattr: Int                  = 5
    final val OUTLINEattr: Int               = 6
    final val SOURCEFILEattr: Int            = 129

    def isBooleanAttrTag(tag: Int): Boolean  // tags 1-32
    def isStringAttrTag(tag: Int): Boolean   // tags 129-160
    def isVersionCompatible(...): Boolean
    object NameTags: ...
```

#### Interner
Source: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Interner.scala`

```scala
final class Interner(numShards: Int = 32):
    def intern(bytes: Array[Byte], offset: Int, length: Int): Interner.Entry

object Interner:
    final class Entry(
        val hash: Int,
        val bytes: Array[Byte],
        val offset: Int,
        val length: Int,
        val string: Memo[String]   // lazy UTF-8 decode
    )
```

Reference equality on two `Entry` values implies byte-level equality.

#### NameUnpickler
Source: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/NameUnpickler.scala`

```scala
object NameUnpickler:
    def read(view: ByteView, interner: Interner)(using Frame): Array[Reflect.Name] < Abort[ReflectError]
```

Returns a 0-based `Array[Reflect.Name]` (opaque = `Interner.Entry`). NameRef N is `names(N)` (0-based, matching dotty convention from `TastyUnpickler`).

#### AttributeUnpickler
Source: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AttributeUnpickler.scala`

```scala
object AttributeUnpickler:
    def read(view: ByteView, names: Array[Reflect.Name])(using Frame): FileAttributes < Abort[ReflectError]

final case class FileAttributes(
    explicitNulls: Boolean,
    captureChecked: Boolean,
    isJava: Boolean,
    isOutline: Boolean,
    scala2StandardLibrary: Boolean,
    sourceFile: Maybe[String]
)
object FileAttributes:
    val default: FileAttributes  // all false, Absent sourceFile
```

#### SectionIndex
Source: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/SectionIndex.scala`

```scala
final class SectionIndex:
    def get(name: String): Maybe[(Int, Int)]  // (offset, length) within file bytes

object SectionIndex:
    def read(view: ByteView, names: Array[Reflect.Name])(using Frame): SectionIndex < Abort[ReflectError]
```

#### Reflect.Symbol / SymbolKind / Flag (public skeleton)
Source: `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`

```scala
// Existing opaque type alias for Name
opaque type Name = Interner.Entry
object Name:
    def apply(s: String): Name
    extension (n: Name) def asString: String

// Existing Flags/Flag types
final class Flags(val bits: Long) extends AnyVal:
    def contains(flag: Flag): Boolean
    def |(other: Flags): Flags
object Flags:
    val empty: Flags

final class Flag(val bit: Long, val name: String)
object Flag:
    // Phase 0 stubs (bits 0-15); Phase 3 adds remaining ~26 flags:
    val Inline: Flag      = Flag(1L << 0, "Inline")
    val Private: Flag     = Flag(1L << 1, "Private")
    val Protected: Flag   = Flag(1L << 2, "Protected")
    val Public: Flag      = Flag(1L << 3, "Public")
    val Final: Flag       = Flag(1L << 4, "Final")
    val Sealed: Flag      = Flag(1L << 5, "Sealed")
    val Abstract: Flag    = Flag(1L << 6, "Abstract")
    val Given: Flag       = Flag(1L << 7, "Given")
    val Implicit: Flag    = Flag(1L << 8, "Implicit")
    val Opaque: Flag      = Flag(1L << 9, "Opaque")
    val Case: Flag        = Flag(1L << 10, "Case")
    val Module: Flag      = Flag(1L << 11, "Module")
    val Synthetic: Flag   = Flag(1L << 12, "Synthetic")
    val JavaDefined: Flag = Flag(1L << 13, "JavaDefined")
    val Enum: Flag        = Flag(1L << 14, "Enum")
    val JavaRecord: Flag  = Flag(1L << 15, "JavaRecord")
    // Phase 3 adds (bits 16+):
    // Open, ParamAccessor, Lazy, Override, Mutable, Erased, Tracked, Tailrec,
    // Infix, Transparent, Trait, CaseAccessor, FieldAccessor, Macro, InlineProxy,
    // Extension, Exported, CoVariant, ContraVariant, HasDefault, Stable, Local,
    // Artifact, Invisible, Into, PARAMsetter, PARAMalias

// Existing SymbolKind enum
enum SymbolKind derives CanEqual:
    case Package, Class, Trait, Object, Method, Field, Val, Var,
         TypeAlias, OpaqueType, AbstractType, TypeParam, Parameter,
         Unresolved

// Existing Symbol class (private[Reflect] constructor)
final class Symbol private[Reflect] (
    val kind: SymbolKind,
    val flags: Flags,
    val name: Name,
    val owner: Symbol,
    private[Reflect] val home: Classpath  // Phase 3 changes to ClasspathRef
    // Phase 3 adds: private val origin: Symbol.Origin
):
    def fullName: Name           // Phase 3 wires owner-chain walk
    def binaryName: String       // Phase 3 wires owner-chain walk
    def isInline: Boolean
    def isContextual: Boolean
    def isOpaque: Boolean
    def isPackageObject: Boolean // Phase 3 wires: flags.contains(Flag.Module) && name.asString == "package"
    def isModule: Boolean
    def isJava: Boolean
    def javaSpecific: Maybe[JavaMetadata]
    // resolving accessors remain stubs in Phase 3

object Symbol:
    // Phase 3 internal companion adds:
    // def makeSymbol(kind, flags, name, owner, home): Reflect.Symbol
    // The Addr->Symbol map is mutable.HashMap[Int, Reflect.Symbol] in the per-file pass context
```

#### Memo / SingleAssign (helpers)
Source: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Memo.scala`, `SingleAssign.scala`

```scala
final class Memo[A](init: () => A):
    def get(): A   // double-check CAS; init may run more than once under contention

final class SingleAssign[A]:
    def set(a: A): Unit    // throws IllegalStateException if already set
    def get(): A           // throws IllegalStateException if not yet set
```

---

## 2. Verbatim TASTy AST Encoding

Source: `dotty/tools/tasty/TastyFormat.scala` in `tasty-core_3-3.8.3-sources.jar` (MajorVersion=28, MinorVersion=8).

### Tag Category Summary

```
Category 1 (tags  1-59):   tag byte only; no length, no payload
Category 2 (tags 60-89):   tag byte + Nat (LEB128)
Category 3 (tags 90-109):  tag byte + sub-AST (recursively decoded)
Category 4 (tags 110-127): tag byte + Nat + sub-AST
Category 5 (tags 128-255): tag byte + Length (Nat) + payload bytes
```

Category 5 is the structurally-skippable family. Reading the Length Nat gives you the exact number of bytes in the payload; advancing the cursor by that many bytes skips the entire node without parsing it.

### Definition Tags (all Category 5)

These are the tags that create symbols in pass 1.

```
PACKAGE   = 128    tag Length Path TopLevelStat*
                   -- Path is the package name reference (a TERMREFpkg or similar term)
                   -- TopLevelStat* are the children (nested packages, class defs, etc.)
                   -- Length covers Path + all TopLevelStat* bytes

VALDEF    = 129    tag Length NameRef type_Term rhs_Term? Modifier*
                   -- NameRef: 0-based index into names array (a Nat)
                   -- type_Term: type annotation tree
                   -- rhs_Term: optional right-hand side (absent for abstract vals)
                   -- Modifier*: modifier tags (category 1 or ANNOTATION tag)
                   -- SymbolKind: Val (no MUTABLE), Var (with MUTABLE modifier)

DEFDEF    = 130    tag Length NameRef Param* returnType_Term rhs_Term? Modifier*
                   -- NameRef: method name (Nat)
                   -- Param*: type params (TYPEPARAM) and term params (PARAM / EMPTYCLAUSE / SPLITCLAUSE)
                   -- returnType_Term: return type tree
                   -- rhs_Term: optional body (absent for abstract methods)
                   -- Modifier*: modifier tags
                   -- SymbolKind: Method

TYPEDEF   = 131    tag Length NameRef (type_Term | Template) Modifier*
                   -- NameRef: class/type name (Nat)
                   -- If next sub-tree is TEMPLATE (156): this is a class/trait/object definition
                   -- If next sub-tree is a type tree (IDENTtpt, APPLIEDtpt, etc.): type alias
                   -- Modifier*: modifier tags determine Trait vs Class vs Object via TRAIT, OBJECT flags
                   -- SymbolKind: Class (no TRAIT, no OBJECT), Trait (TRAIT), Object (OBJECT),
                   --             TypeAlias (no Template), OpaqueType (OPAQUE flag + type term),
                   --             AbstractType (TYPEBOUNDS with no rhs alias)

TYPEPARAM = 133    tag Length NameRef type_Term Modifier*
                   -- NameRef: type parameter name (Nat)
                   -- type_Term: bounds (usually TYPEBOUNDS)
                   -- SymbolKind: TypeParam

PARAM     = 134    tag Length NameRef type_Term Modifier*
                   -- NameRef: parameter name (Nat)
                   -- type_Term: parameter type
                   -- SymbolKind: Parameter
```

### Template Structure (for class body skipping)

```
TEMPLATE  = 156    tag Length TypeParam* TermParam* parent_Term* Self? EndParents? Stat*
                   -- TypeParam*:   zero or more TYPEPARAM nodes (class type parameters)
                   -- TermParam*:   zero or more PARAM / EMPTYCLAUSE / SPLITCLAUSE nodes (constructor params)
                   -- parent_Term*: parent type references
                   -- Self?:        optional SELFDEF node
                   -- EndParents?:  optional SPLITCLAUSE marking end of header
                   -- Stat*:        member definitions (body)
```

In pass 1, after eagerly decoding the class name and flags from TYPEDEF, the reader forks into the TEMPLATE to extract: type params, constructor params, parent type refs (as UnresolvedRef placeholders), and one-level-deep member NameRefs. The remainder of the TEMPLATE body is recorded as `(bodyStart, bodyEnd)` and skipped.

### Modifier Tags (all Category 1 — tag byte only, no payload)

| Tag byte | TastyFormat constant | Meaning | Flag mapped |
|----------|---------------------|---------|-------------|
| 6  | PRIVATE       | private modifier            | Flag.Private |
| 8  | PROTECTED     | protected modifier          | Flag.Protected |
| 9  | ABSTRACT      | abstract modifier           | Flag.Abstract |
| 10 | FINAL         | final modifier              | Flag.Final |
| 11 | SEALED        | sealed modifier             | Flag.Sealed |
| 12 | CASE          | case class/object           | Flag.Case |
| 13 | IMPLICIT      | implicit modifier           | Flag.Implicit |
| 14 | LAZY          | lazy val                    | Flag.Lazy |
| 15 | OVERRIDE      | override modifier           | Flag.Override |
| 16 | INLINEPROXY   | inline proxy binding        | Flag.InlineProxy |
| 17 | INLINE        | inline method/val           | Flag.Inline |
| 18 | STATIC        | Java static member          | Flag.JavaDefined (combined) |
| 19 | OBJECT        | object / module class       | Flag.Module |
| 20 | TRAIT         | trait definition            | Flag.Trait |
| 21 | ENUM          | enum class or enum case     | Flag.Enum |
| 22 | LOCAL         | private[this]/protected[this] | Flag.Local |
| 23 | SYNTHETIC     | compiler-generated          | Flag.Synthetic |
| 24 | ARTIFACT      | Java synthetic              | Flag.Artifact |
| 25 | MUTABLE       | var declaration             | Flag.Mutable |
| 26 | FIELDaccessor | getter/setter field         | Flag.FieldAccessor |
| 27 | CASEaccessor  | case class accessor         | Flag.CaseAccessor |
| 28 | COVARIANT     | covariant type param (+)    | Flag.CoVariant |
| 29 | CONTRAVARIANT | contravariant type param (-)| Flag.ContraVariant |
| 31 | HASDEFAULT    | param with default arg      | Flag.HasDefault |
| 32 | STABLE        | stable method (path-ok)     | Flag.Stable |
| 33 | MACRO         | inline method with splices  | Flag.Macro |
| 34 | ERASED        | erased parameter/def        | Flag.Erased |
| 35 | OPAQUE        | opaque type alias           | Flag.Opaque |
| 36 | EXTENSION     | extension method            | Flag.Extension |
| 37 | GIVEN         | given definition            | Flag.Given |
| 38 | PARAMsetter   | setter part of var param    | Flag.PARAMsetter |
| 39 | EXPORTED      | export forwarder            | Flag.Exported |
| 40 | OPEN          | open class                  | Flag.Open |
| 41 | PARAMalias    | param alias of super param  | Flag.PARAMalias |
| 42 | TRANSPARENT   | transparent inline          | Flag.Transparent |
| 43 | INFIX         | infix method                | Flag.Infix |
| 44 | INVISIBLE     | invisible during typing     | Flag.Invisible |
| 49 | INTO          | legal conversion target     | Flag.Into |
| 47 | TRACKED       | tracked class parameter     | Flag.Tracked |

Modifier tags 98 (PRIVATEqualified) and 99 (PROTECTEDqualified) are Category 3 tags (tag + sub-AST) — they carry a qualifier type tree. The pass 1 reader must advance past the sub-AST using category-3 decoding rules when it encounters these.

The ANNOTATION modifier tag (173) is Category 5 (tag + Length + payload). Pass 1 records the raw `(startAddr, endAddr)` bytes of the annotation payload and skips past it.

### Constant Leaf Tags

```
UNITconst   = 2    (cat 1, no payload)
FALSEconst  = 3    (cat 1)
TRUEconst   = 4    (cat 1)
NULLconst   = 5    (cat 1)
BYTEconst   = 67   (cat 2, one Nat)
SHORTconst  = 68   (cat 2, one Nat)
CHARconst   = 69   (cat 2, one Nat)
INTconst    = 70   (cat 2, one Nat)
LONGconst   = 71   (cat 2, one Nat)
FLOATconst  = 72   (cat 2, one Nat)
DOUBLEconst = 73   (cat 2, one Nat)
STRINGconst = 74   (cat 2, one NameRef Nat)
CLASSconst  = 92   (cat 3, one sub-AST = type reference)
ENUMconst does not exist as a separate tag; enum cases produce VALDEF or CLASSDEF nodes
```

### Length-Prefix Encoding Detail

For any Category 5 tag:
1. Read 1 byte: the tag value (128-255).
2. Read a Nat (LEB128): this is the payload byte count `L`.
3. The current `view.position` after reading `L` is `payloadStart`.
4. `payloadEnd = payloadStart + L`.
5. The entire node occupies `[tagByte, ..., payloadEnd)` in the byte stream.
6. To skip: after reading the tag and calling `view.readEnd()`, call `view.goto(end)`.

Dotty's `TastyReader.readEnd()` (mirrored by our `ByteView.readEnd()`) reads the length Nat and returns `cursor + length` — the absolute end address. Using `view.goto(end)` then positions the cursor exactly at the first byte after the node.

---

## 3. Forward-Reference Resolution Protocol

### Dotty Reference

In `dotty/tools/dotc/core/tasty/TreeUnpickler.scala` (scala3-compiler 3.7.0):

```scala
// Line 70: per-file map from TASTy byte address to Symbol
private val symAtAddr = new mutable.HashMap[Addr, Symbol]

// Line 125: register a symbol at its TASTy address
private def registerSym(addr: Addr, sym: Symbol) =
    symAtAddr(addr) = sym

// Line 296: look up a symbol by address (forward or backward reference)
def symbolAt(addr: Addr)(using Context): Symbol = symAtAddr.get(addr) match {
    ... // returns the symbol, or creates it if not yet seen
}

// Lines 799-820: indexStats — the pre-scan that populates symAtAddr
def indexStats(end: Addr)(using Context): FlagSet = {
    while (currentAddr.index < end.index)
        nextByte match {
            case VALDEF | DEFDEF | TYPEDEF | TYPEPARAM | PARAM =>
                val sym = symbolAtCurrent()  // creates symbol, registers in symAtAddr
                skipTree()
            case IMPORT | EXPORT =>
                skipTree()
            case PACKAGE =>
                processPackage { (pid, end) => indexStats(end) }
            case _ =>
                skipTree()
        }
    ...
}
```

### kyo-reflect Pass 1 Equivalent

The kyo-reflect `AstUnpickler.readPass1` implements the same pre-scan pattern without dotty's lazy completers:

1. Maintain a `val addrMap = new mutable.HashMap[Int, Reflect.Symbol]()` (address = byte offset in the AST section).
2. Maintain an `ownerStack: mutable.ArrayDeque[Reflect.Symbol]` initialized with a synthetic root Package symbol.
3. Walk the AST section byte-by-byte:
   - On a Category 5 tag: call `view.readEnd()` to get `end`. Record `payloadStart = view.position`.
   - On `PACKAGE`, `VALDEF`, `DEFDEF`, `TYPEDEF`, `TYPEPARAM`, `PARAM`: read NameRef (a Nat), build the Symbol, register `addrMap(nodeStartAddr) = sym`, push `sym` onto ownerStack.
   - Recurse into the payload to discover immediate children, then pop ownerStack on exit.
   - For unknown Category 5 tags: `view.goto(end)` — skip entirely.
   - For Category 1-4 tags: skip according to the category rules (see Section 8 gotcha).

Forward references work because the full pre-scan of a given scope (e.g., all type params of `class C[T1 <: T2, T2]`) runs before any type decoding. When the type bound of `T1` references `T2`, `T2` is already in `addrMap` because the pre-scan walked all sibling TYPEPARAM nodes first. This is identical to dotty's `forkAt(templateStart).indexTemplateParams()` pattern (TreeUnpickler line 691), which pre-indexes all type params of a TEMPLATE before any completer runs.

Key constraint: pass 1 must allocate symbols in AST order (left-to-right byte order), not in type-dependency order. The `addrMap` lookup handles out-of-order references.

---

## 4. Body Slice Mechanism

### DESIGN.md §6.4 Statement

> TASTy tag categories 128 to 255 are length-prefixed. Any compliant reader can structurally skip a node without understanding it. We exploit this:
>
> 1. Pass 1 (eager): walk the AST section, allocate one Symbol per definition. For each definition, eagerly decode name, flags, type signature, parents (for classes), member name list (one level deep). Bodies of DEFDEF and class bodies past the member name list are recorded as (startAddr, endAddr) slices and skipped via length-prefix.
>
> 2. Pass 2 (lazy, on demand): `Symbol.body` accessor decodes its pickled slice into a Tree. In v1 the accessor returns `Abort.fail(ReflectError.NotImplemented("tree body decode deferred to v2"))`.

### Implementation Pattern

For each `DEFDEF` node, after decoding the method's name and parameter list:

```
payloadStart = view.position after readEnd()
// decode: NameRef (name), Param* (params), returnType_Term
// once parameter decoding is done:
bodyStart = view.position  // first byte of rhs_Term? or Modifier*
// skip to end:
view.goto(end)
bodyEnd = end
// Store (bodyStart, bodyEnd) in TastyOrigin
```

For class TYPEDEF/TEMPLATE pairs, the TEMPLATE body starts after the parent type references and SELFDEF. Pass 1 reads member NameRefs at one level deep (for the DeclarationTable) and records the remaining body bytes as `(bodyStart, bodyEnd)`.

The `(startAddr, endAddr)` pair is stored in `Symbol.Origin.TastyOrigin`. Pass 2 would call `view.subView(bodyStart, bodyEnd)` to obtain a fresh ByteView positioned at the body start.

### Dotty Analog

Dotty does not have an exact equivalent because it uses `LazyType` completers that decode the entire TYPEDEF payload on demand. The kyo-reflect pass 1 is more aggressive: it decodes name + flags + signature eagerly but defers the body. This matches the comment in dotty's `readLaterWithOwner` (TreeUnpickler line 1752):

```scala
def readLaterWithOwner[T <: AnyRef](end: Addr, op: TreeReader => Context ?=> T)(using Context): Symbol => Trees.Lazy[T] = {
    // stores (startAddr, endAddr) for lazy body decoding
}
```

kyo-reflect's equivalent is `TastyOrigin(addrMap, bodyStart, bodyEnd)`.

---

## 5. DeclarationTable Design

### kyo-data `Dict` API

Source: `kyo-data/shared/src/main/scala/kyo/Dict.scala`

```scala
opaque type Dict[K, V] = Span[K | V] | HashMap[K, V]

object Dict:
    private[kyo] val threshold = 8     // cutover threshold (confirmed from source line 65)
    def empty[K, V]: Dict[K, V]
    def apply[K, V](entries: (K, V)*): Dict[K, V]
    def from[K, V](map: Map[K, V]): Dict[K, V]

extension [K, V](self: Dict[K, V]):
    def size: Int
    def isEmpty: Boolean
    def apply(key: K): V                // throws NoSuchElementException if absent
    def get(key: K): Maybe[V]           // safe lookup
    def contains(key: K): Boolean
    def update(key: K, value: V): Dict[K, V]
    def remove(key: K): Dict[K, V]
    def foreach(f: (K, V) => Unit): Unit
```

`Dict` already implements the flat-array (Span) vs HashMap dual representation at `threshold = 8`. A class with 8 or fewer members uses `Span[K | V]` with O(n) linear scan; above 8 it uses `HashMap[K, V]`.

### DeclarationTable Wrapper

The plan's `DeclarationTable` wraps `Dict[Name, Symbol]` in an `AtomicRef` for CAS-swap visibility:

```scala
// Internal state: either empty Dict or fully-populated Dict; never partial
final class DeclarationTable:
    private val ref: AtomicRef[Dict[Reflect.Name, Reflect.Symbol]] = AtomicRef(Dict.empty)

    // Called exactly once by pass 1, atomically replaces the empty dict
    def populate(entries: Dict[Reflect.Name, Reflect.Symbol]): Unit
        // CAS from empty to fully-populated; throws if called twice

    def get(name: Reflect.Name): Maybe[Reflect.Symbol]
    def all: Dict[Reflect.Name, Reflect.Symbol]
```

The CAS guarantees that any reader sees either `Dict.empty` (pre-population) or the fully-populated dict (post-population), never a partial intermediate state.

Note: `Dict` is immutable — the pass 1 builder collects members in a `DictBuilder` and calls `b.result()` to get the final immutable `Dict`, then CAS-swaps it in atomically. No concurrent mutation of the Dict itself occurs.

`DictBuilder` API (from `kyo-data`):

```scala
object DictBuilder:
    def init[K, V]: DictBuilder[K, V]
    // DictBuilder.add(k, v): Unit
    // DictBuilder.result(): Dict[K, V]
```

---

## 6. ClasspathRef Pattern

### Design

`ClasspathRef` is a `SingleAssign[Classpath]`-backed forward-reference slot on each Symbol:

```scala
// kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathRef.scala
final class ClasspathRef:
    private val slot = new SingleAssign[Reflect.Classpath]

    // Called by Phase 7 orchestration when the Classpath is fully constructed
    def assign(cp: Reflect.Classpath): Unit = slot.set(cp)

    // Called by Phase 4+ resolving accessors that need the live Classpath
    def get(): Reflect.Classpath = slot.get()
```

### Lifecycle Constraint

Phase 3 creates `ClasspathRef` instances and stores them in each `Symbol.home` field. **No Phase 3 code path may call `home.get()` or `home.assign()`.**

Phase 3 code that receives a `ClasspathRef` parameter may only:
- Store it in a Symbol's `home` field.
- Forward it to child symbols as their `home`.

This must be audited in both `AstUnpickler` and the modified `Symbol.computeFullName` / `Symbol.computeBinaryName`. Neither owner-chain walk calls through `home` — they read only `sym.name` and `sym.owner`, both of which are populated eagerly in pass 1 without involving `home`.

### Phase 7 Assignment

Phase 7's classpath orchestration, after constructing the `Classpath` object, calls `classPathRef.assign(cp)` for each symbol batch. From that point, resolving accessors (`declaredType`, `parents`, etc.) may call `sym.home.get()`.

---

## 7. Symbol.Origin ADT

Phase 3 declares the **complete** `Symbol.Origin` sealed trait with both cases. Phase 5 only adds construction sites for `JavaOrigin`; it does not add new ADT cases.

```scala
// Inside object Reflect, alongside Symbol definition
object Symbol:
    sealed trait Origin
    final case class TastyOrigin(
        addrMap: Map[Int, Reflect.Symbol],  // per-file Addr -> Symbol map from pass 1
        bodyStart: Int,                      // byte offset of body start in AST section
        bodyEnd: Int                         // byte offset of body end in AST section
    ) extends Origin
    case object JavaOrigin extends Origin    // no TASTy-specific fields; populated by Phase 5
```

The `addrMap` field on `TastyOrigin` is the per-file pass 1 result, shared by reference among all symbols decoded from the same TASTy file (they all need to look up siblings). This is intentional: it is read-only after pass 1 completes.

`bodyStart == bodyEnd` is a valid sentinel meaning "no body" (abstract method, abstract class, external declaration).

---

## 8. Edge Cases and Gotchas

### 8.1 SHAREDtype / SHAREDterm Dedup Cache

TASTy uses `SHAREDtype` (tag=61, Category 2) and `SHAREDterm` (tag=60, Category 2) to reference a previously-serialized sub-tree:

```
SHAREDterm  path_ASTRef   -- ASTRef is a Nat = byte offset in AST section
SHAREDtype  type_ASTRef   -- ASTRef is a Nat = byte offset in AST section
```

The lookup-or-decode protocol for pass 1 (when decoding type signatures during pass 1):

```
val sharedTypeCache = new mutable.HashMap[Int, Reflect.Type]()  // per-file

def decodeType(view: ByteView): Reflect.Type =
    val nodeAddr = view.position
    val tag = view.readByte() & 0xff
    tag match
        case TastyFormat.SHAREDtype =>
            val refAddr = view.readNat()
            sharedTypeCache.getOrElseUpdate(refAddr, {
                val fork = view.subView(refAddr, astSectionEnd)
                decodeType(fork)
            })
        case TastyFormat.SHAREDterm =>
            val refAddr = view.readNat()
            sharedTypeCache.getOrElseUpdate(refAddr, {
                val fork = view.subView(refAddr, astSectionEnd)
                decodeType(fork)
            })
        case other => ...
```

The cache is per-file (not global) because ASTRef values are byte offsets within the file's AST section. Two files may have the same offset value pointing to different content.

During pass 1, type decoding is shallow: only enough to build `UnresolvedRef` placeholders. Full type decoding happens in Phase 4. Therefore the `sharedTypeCache` in pass 1 stores `UnresolvedRef` values, not full `Reflect.Type` values.

### 8.2 Skipping Unknown Category 5 Tags

The pass 1 reader encounters Category 5 tags it does not recognize (e.g., `APPLY=136`, `BLOCK=140`, `IF=141`). It must skip them correctly:

```scala
// Correct:
val end = view.readEnd()     // reads the length Nat, returns cursor + length
view.goto(end)               // skip payload

// WRONG — never try to parse the payload of an unrecognized node:
// (this fails because you don't know the payload structure)
```

Category 5 is defined as tags 128-255 (i.e., `tag >= TastyFormat.firstLengthTreeTag`). Any tag in this range that is not PACKAGE, VALDEF, DEFDEF, TYPEDEF, TYPEPARAM, PARAM, or TEMPLATE must be skipped with `readEnd()` + `goto`.

For Categories 1-4, skipping rules:
- Category 1 (1-59): no payload, nothing to skip after the tag byte.
- Category 2 (60-89): one Nat follows; skip with `view.readNat()`.
- Category 3 (90-109): one sub-AST follows; skip recursively with `skipTree()`.
- Category 4 (110-127): one Nat + one sub-AST; skip with `view.readNat()` then `skipTree()`.

A `skipTree()` helper must handle all four categories correctly. This mirrors dotty's `TastyReader.skipTree(tag: Int)`.

### 8.3 Owner Chain Reconstruction

TASTy encodes ownership structurally: a `CLASSDEF` or `DEFDEF` node's payload contains child nodes (its members). The pass 1 reader uses an explicit owner stack to track the current enclosing symbol:

```
ownerStack: mutable.ArrayDeque[Reflect.Symbol]
// push when entering a definition node's payload
// pop when exiting (view.position reaches `end`)
```

The current owner for a new symbol is `ownerStack.last`. This is how `sym.owner` gets set during symbol creation in `makeSymbol`.

For PACKAGE nodes: the package symbol is pushed. All top-level classes within the package have the package symbol as owner.

Root sentinel: the stack is initialized with a synthetic "root" package symbol (unnamed, `SymbolKind.Package`, `Flags.empty`, owner = itself). This is the owner of top-level packages.

---

## 9. Test Data Suggestions

### Primary Input: kyo-reflect-fixtures TASTy

Source: `kyo-reflect-fixtures/shared/src/main/scala/kyo/fixtures/FixtureClasses.scala`

The fixtures module compiles to `.tasty` files in the test resource classpath. Tests load the bytes via the cross-platform resource path pattern (see Section 10).

**Fixtures and what to look up:**

| Fixture | SymbolKind | Expected Flags | Test purpose |
|---------|-----------|---------------|-------------|
| `PlainClass` | Class | none | Simple class, 1 field (val x: Int), primary constructor |
| `SomeTrait` | Trait | Trait | Trait with abstract method |
| `SomeObject` | Object | Module | Scala object |
| `Color` | Class | Sealed, Enum | Enum with cases |
| `Color.Red` | Val | Case, Enum | Enum case without parameters |
| `GenericBox[A]` | Class | none | Generic class; type param A is TypeParam |
| `Outer.Inner` | Class | none | Nested class; owner chain verification |
| `identityMethod` | Method | none | Top-level def with type param |
| `inlineAdd` | Method | Inline | Inline method |
| `topLevelVal` | Val | none | Top-level val |
| `topLevelVar` | Var | Mutable | Top-level var |
| `lazyValue` | Val | Lazy | Lazy val |
| `` `package` `` | Object | Module | Package-object-style object |

**Specific test classes from plan:**

- `PlainClass`: simple case. Single field, single primary constructor. Use for basic symbol allocation test.
- `GenericBox[A]`: generic class. Verify that the type parameter `A` produces a `TypeParam` symbol, and that `GenericBox`'s `addrMap` contains `A`'s address.
- `Color` (sealed enum): verify that `CLASSDEF` with `Flag.Sealed | Flag.Enum` produces `SymbolKind.Class` with those flags.

### TASTy File Name Convention

The compiled fixture TASTy file for `kyo.fixtures.PlainClass` is named `PlainClass.tasty` and appears in the test classpath under the package path `kyo/fixtures/PlainClass.tasty`.

The top-level TASTy file for the entire `FixtureClasses.scala` compilation unit is likely named after the file, not each class individually. In Scala 3, each top-level class gets its own `.tasty` file named after the class. `FixtureClasses.scala` contains multiple top-level definitions; each gets a separate `.tasty` file: `PlainClass.tasty`, `SomeTrait.tasty`, `SomeObject.tasty`, etc.

For the `AstUnpicklerTest`, test 8 checks that "a top-level class name is in the returned symbol set." The recommended approach: load `PlainClass.tasty` and assert that the returned symbols include one with `name.asString == "PlainClass"` and `kind == SymbolKind.Class`.

---

## 10. Anti-Flakiness Deltas

### Resource Path Lookup (Cross-Platform)

TASTy bytes for fixtures must be loaded via classpath resource, not file I/O, to work on all three platforms (JVM, JS, Native):

```scala
// JVM: getClass.getResourceAsStream("/kyo/fixtures/PlainClass.tasty")
// JS/Native: scala.scalajs.js.typedarray / scala.scalanative — no direct resource API

// Cross-platform pattern used in kyo-reflect tests:
// In Test.scala (shared), the test runner provides a helper:
// def fixtureBytes(name: String): Array[Byte]
// which maps to the appropriate platform resource-loading mechanism.
```

The correct approach for kyo-reflect is to follow the pattern already established in `kyo-reflect/shared/src/test/scala/kyo/Test.scala`. If that file has a `fixtureBytes` or resource-loading helper, use it. If not, Phase 3 must add one that delegates to platform-specific implementations:

```
kyo-reflect/jvm/src/test/scala/kyo/TestPlatform.scala   -> uses getResourceAsStream
kyo-reflect/js/src/test/scala/kyo/TestPlatform.scala    -> uses xhr / node fs
kyo-reflect/native/src/test/scala/kyo/TestPlatform.scala -> uses C fread via Bindings
```

Shared test code calls `TestPlatform.loadResource(path)`.

Alternatively: the `kyo-reflect-fixtures` module is a dependency of `kyo-reflect` test scope. The TASTy files produced by compiling `kyo-reflect-fixtures` are on the test classpath. On JVM, `Thread.currentThread.getContextClassLoader.getResourceAsStream(path)` works. The existing `kyo/TastyHeaderTest.scala` should show the established pattern — check it before implementing.

### No File I/O Timing

Tests must not use `Thread.sleep` or wall-clock delays. The `DeclarationTableTest` concurrency test uses `kyo.Latch` (from kyo-core) for coordination, wrapped in `Async.timeout(1.second)` to fail on hang.

### No Concurrency in Pass 1 Itself

`AstUnpickler.readPass1` is single-threaded within a file. The `addrMap` (`mutable.HashMap`) is not thread-safe and does not need to be — it is constructed in pass 1 and then published (via the immutable `Pass1Result`) to any consumers. No concurrent writes to `addrMap` occur.

---

## 11. Concerns

### Concern 1 (MINOR): `Classpath` Opaque Type vs `ClasspathRef`

The current `Reflect.scala` has:
```scala
opaque type Classpath = ClasspathState
final private class ClasspathState  // placeholder
```

And `Symbol` currently has `private[Reflect] val home: Classpath`. Phase 3 changes `home` to `ClasspathRef` (a new concrete class in `kyo.internal.reflect.query`). This is a breaking change to the `Symbol` private constructor signature.

The plan acknowledges this in the "Files to modify" section: `Reflect.Symbol` gains `origin: Symbol.Origin` and `home` changes type to `ClasspathRef`. Implementer must update both the private constructor and the `Symbol.makeSymbol` factory. The public API is unaffected (`home` is `private[Reflect]`).

### Concern 2 (MINOR): `Dict` is Immutable — DeclarationTable Needs a Builder

`Dict[K, V]` from kyo-data is immutable. `DeclarationTable` cannot accumulate members by mutating a Dict. Pass 1 must:
1. Collect members into a `scala.collection.mutable.ArrayBuffer[(Reflect.Name, Reflect.Symbol)]`.
2. After processing all members of a class, call `Dict(entries*)` to build the immutable Dict.
3. CAS-swap the `AtomicRef` from `Dict.empty` to the built Dict.

Alternatively, use `DictBuilder` if it is available in kyo-data (check `DictBuilder.scala`). The `Dict.apply(entries*)` varargs constructor is confirmed from the source.

### Concern 3 (MODERATE): SymbolKind Mapping from TYPEDEF

`TYPEDEF` is used for classes, traits, objects, type aliases, opaque types, and abstract types. The discriminant is:
- If the next sub-tree after the NameRef is `TEMPLATE` (156): class-like.
  - Then check modifier tags: `TRAIT` -> `SymbolKind.Trait`, `OBJECT` -> `SymbolKind.Object`, else `SymbolKind.Class`.
- If the next sub-tree is a type tree (not TEMPLATE):
  - Check for `OPAQUE` modifier: `SymbolKind.OpaqueType`.
  - Check for `TYPEBOUNDS` with no alias: `SymbolKind.AbstractType`.
  - Otherwise: `SymbolKind.TypeAlias`.

The `TYPEPARAM` tag always produces `SymbolKind.TypeParam`. The `PARAM` tag always produces `SymbolKind.Parameter`. The `VALDEF` tag produces `SymbolKind.Val` or `SymbolKind.Var` depending on whether the `MUTABLE` modifier is present.

For `DEFDEF`: always `SymbolKind.Method`. Constructors are `DEFDEF` nodes with name `<init>` (the dotty convention; in TASTy, constructors have a special NameRef that decodes to `<init>`).

The `fromTagAndFlags(tag: Int, flags: Long): SymbolKind` function in `SymbolKind.scala` must handle the TYPEDEF ambiguity. Because the discriminant (TEMPLATE vs type tree) requires peeking at the next byte after the NameRef, the `AstUnpickler` must resolve this at pass 1 time and pass the resolved kind to `makeSymbol`. The `fromTagAndFlags` function handles the modifier-based disambiguation (Trait vs Object vs Class) but cannot handle the TEMPLATE peek — that logic lives in the unpickler.

### Concern 4 (LOW): Phase 3 does not implement SectionIndex.read dependency

`AstUnpickler.readPass1` receives `view: ByteView` already positioned at the start of the AST section payload (the caller uses `SectionIndex` to locate the section, then passes a `subView`). This means `SectionIndex` is a Phase 2 dependency that must be verified present before Phase 3 implementation begins. Per PROGRESS.md, Phase 2 is "pending" — Phase 3 cannot begin until Phase 2 lands.

### Concern 5 (LOW): PRIVATEqualified / PROTECTEDqualified are Category 3, Not Category 1

Modifier tags 98 (`PRIVATEqualified`) and 99 (`PROTECTEDqualified`) are Category 3 (tag + sub-AST), not Category 1 (tag only). They appear in the modifier position of VALDEF/DEFDEF/TYPEDEF nodes but require reading a qualifier type tree. The modifier-reading loop in pass 1 must check for these and skip the sub-AST:

```scala
case TastyFormat.PRIVATEqualified | TastyFormat.PROTECTEDqualified =>
    skipTree()  // skip the qualifier type
    flags |= Flag.Private.bit  // or Protected
```

Failing to handle these will corrupt the cursor and produce garbage symbols for any class with a private-in-qualifier member.
