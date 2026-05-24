# Phase 4 Prep: Type Model + Per-Thread Arenas + Phase C Merge

Source material: execution-plan.md lines 264-333, DESIGN.md section 9, TastyFormat.scala from
tasty-core_3-3.8.3-sources.jar, and all Phase 1-3 code committed to disk.

---

## 1. Verbatim API Signatures: Phase 1-3 APIs Phase 4 Will Call

### ByteView (shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala)

```scala
sealed trait ByteView:
    def peekByte(at: Int): Byte
    def readByte(): Byte
    def readNat(): Int        // LEB128 unsigned, delegates to Varint.readNat
    def readInt(): Int        // signed 2's-complement big-endian base-128
    def readLongNat(): Long   // LEB128 unsigned as Long
    def readEnd(): Int        // reads Nat as payload length, returns cursor + len (absolute end addr)
    def subView(from: Int, until: Int): ByteView  // zero-copy slice, cursor reset to from
    def goto(addr: Int): Unit
    def remaining: Int        // end - cursor
    def position: Int         // current cursor
```

`ByteView.Heap` is the concrete in-memory implementation. `goto` is used pervasively in all
unpicklers to skip over parsed sub-trees. `subView` is used to fork a ByteView for recursive
descent (e.g., template body). `readEnd` is the canonical way to start a length-prefixed node:
call it once and pass the returned end address to every branch.

### NameUnpickler.read (shared/src/main/scala/kyo/internal/reflect/tasty/NameUnpickler.scala)

```scala
object NameUnpickler:
    def read(view: ByteView, interner: Interner)(using Frame): Array[Reflect.Name] < (Sync & Abort[ReflectError])
```

Returns a 0-based `Array[Reflect.Name]`. NameRef values from TASTy are used as 0-based indices
into this array. The array may contain qualified names (with `.`, `$$`, `$` separators) as well as
SIGNED and TARGETSIGNED entries for overloaded method names. Phase 4's TypeUnpickler calls
`names(nameRef)` to resolve name references embedded in type nodes.

### Reflect.Type enum (shared/src/main/scala/kyo/Reflect.scala, lines 166-198)

All 26 cases are already declared in the skeleton. Phase 4 does not add new cases.

```scala
enum Type:
    case Named(symbol: Symbol)
    case TermRef(prefix: Type, name: Name)
    case Applied(base: Type, args: Chunk[Type])
    case TypeLambda(params: Chunk[Symbol], body: Type)
    case Function(params: Chunk[Type], result: Type, isContext: Boolean)
    case Tuple(elements: Chunk[Type])
    case ByName(underlying: Type)
    case Repeated(elem: Type)
    case Array(elem: Type)
    case Refinement(parent: Type, name: Name, info: Type)
    case Rec(parent: Type)
    case RecThis(rec: Type)
    case AndType(left: Type, right: Type)
    case OrType(left: Type, right: Type)
    case Annotated(underlying: Type, annotation: Annotation)
    case ConstantType(value: Constant)
    case ThisType(cls: Symbol)
    case SuperType(self: Type, mixin: Type)
    case ParamRef(binder: Symbol, idx: Int)
    case Wildcard(lo: Type, hi: Type)
    case Skolem(underlying: Type)
    case MatchType(bound: Type, scrutinee: Type, cases: Chunk[Type])
    case FlexibleType(underlying: Type)
end Type
```

### Reflect.Symbol accessors (Reflect.scala, lines 202-282)

Pure accessors (always available, no effect):
- `sym.kind: SymbolKind`
- `sym.flags: Flags`
- `sym.name: Name`
- `sym.owner: Symbol`
- `sym.fullName: Name`
- `sym.isInline: Boolean`, `sym.isContextual: Boolean`, `sym.isOpaque: Boolean`,
  `sym.isModule: Boolean`, `sym.isJava: Boolean`

`Symbol.Origin` sealed trait:
```scala
sealed trait Origin
final case class TastyOrigin(addrMap: Map[Int, Reflect.Symbol], bodyStart: Int, bodyEnd: Int) extends Origin
case object JavaOrigin extends Origin
```

`SymbolKind.Unresolved` is the sentinel for cross-file refs whose target file is absent (see
DESIGN.md section 7 "Unresolved sentinel"). Phase 4 uses this when a TYPEREFin FQN is not in
addrMap and not resolvable at decode time.

### Memo[A] and SingleAssign[A] (symbol/Memo.scala, symbol/SingleAssign.scala)

```scala
final class Memo[A](init: () => A):
    def get(): A   // double-checked CAS, init may run once per racing thread; stored result wins

final class SingleAssign[A]:
    def set(a: A): Unit   // throws IllegalStateException if already set
    def get(): A          // throws IllegalStateException if not yet set
```

Phase 4 uses `SingleAssign` directly in `UnresolvedRef`:
```scala
case class UnresolvedRef(fqn: String, replaceSlot: SingleAssign[Reflect.Type])
```
Phase C writes `Named(resolvedSym)` into `replaceSlot` once the FQN is resolved.

### AstUnpickler.Pass1Result (tasty/AstUnpickler.scala, lines 33-36)

```scala
final case class Pass1Result(
    symbols: Chunk[Reflect.Symbol],
    addrMap: Map[Int, Reflect.Symbol],
    rootSymbol: Reflect.Symbol
)
```

`addrMap` maps each TASTy byte-offset (the position where a definition node starts) to the
pre-allocated Symbol. Phase 4's TypeUnpickler receives this map so TYPEREFsymbol/TERMREFsymbol
can resolve local symbols by addr (the ASTRef Nat inside the type node).

Phase 4 also introduces its own UnresolvedRef list in a mutable buffer held during decoding:
cross-file unresolved type references accumulate here and Phase C drains them.

### ClasspathRef (query/ClasspathRef.scala)

```scala
final class ClasspathRef:
    def assign(cp: Reflect.Classpath): Unit
    def get(): Reflect.Classpath
```

Phase 4 code does NOT call `ref.get()` at decode time. It stores refs for Phase 7.

### Internal Symbol factory

```scala
// symbol/Symbol.scala
object Symbol:
    def makeSymbol(kind: Reflect.SymbolKind, flags: Reflect.Flags, name: Reflect.Name,
                   owner: Reflect.Symbol, home: ClasspathRef,
                   origin: Reflect.Symbol.Origin): Reflect.Symbol
```

Phase 4 may need to create TypeLambda binder symbols for type parameter placeholders during
TYPELAMBDAtype decode. These are synthetic `TypeParam` symbols with no TASTy addr in the outer
addrMap (they are binders local to the lambda, not file-level definitions).

---

## 2. Verbatim Dotty Type-Tree Encoding

All tags and grammar from TastyFormat.scala in tasty-core_3-3.8.3-sources.jar. Line numbers
refer to the extracted source at /tmp/dotty/tools/tasty/TastyFormat.scala.

### Tag Category Summary (lines 246-252)

```
Category 1 (tags 1-59)   : tag only
Category 2 (tags 60-89)  : tag + Nat
Category 3 (tags 90-109) : tag + AST
Category 4 (tags 110-127): tag + Nat + AST
Category 5 (tags 128-255): tag + Length + <payload>
```

### Path/Type tags that produce Reflect.Type (grammar lines 143-191)

#### Category 2 (tag + Nat)

| Tag | Value | Grammar | Decoded to |
|-----|-------|---------|-----------|
| SHAREDtype | 61 | `SHAREDtype type_ASTRef` | per-file `addrCache(astRef)`, see §3 |
| TERMREFdirect | 62 | `TERMREFdirect sym_ASTRef` | `Named(addrMap(astRef))` |
| TYPEREFdirect | 63 | `TYPEREFdirect sym_ASTRef` | `Named(addrMap(astRef))` |
| TERMREFpkg | 64 | `TERMREFpkg fullyQualified_NameRef` | `Named(packageSym)` or `UnresolvedRef` |
| TYPEREFpkg | 65 | `TYPEREFpkg fullyQualified_NameRef` | `Named(packageSym)` or `UnresolvedRef` |
| RECthis | 66 | `RECthis recType_ASTRef` | `RecThis(addrCache(astRef))` |
| STRINGconst | 74 | `STRINGconst NameRef` | inside CONSTANTtype: `ConstantType(StringConst(...))` |
| INTconst | 70 | `INTconst Int` | inside CONSTANTtype: `ConstantType(IntConst(...))` |
| LONGconst | 71 | `LONGconst LongInt` | `ConstantType(LongConst(...))` |
| FLOATconst | 72 | `FLOATconst Int` | `ConstantType(FloatConst(...))` |
| DOUBLEconst | 73 | `DOUBLEconst LongInt` | `ConstantType(DoubleConst(...))` |

Note: `TERMREFdirect`=62 has the same numeric value as `TYPEREFdirect`=63-1. Check: TERMREFdirect
is 62 and TYPEREFdirect is 63, NOT the NameTags.SIGNED (also 63). They live in different categories
and are never mixed. The NameTags constants are purely for the name table; AST tag constants are
used in the AST section.

#### Category 3 (tag + single AST)

| Tag | Value | Grammar | Decoded to |
|-----|-------|---------|-----------|
| THIS | 90 | `THIS clsRef_Type` | `ThisType(sym)` where clsRef decodes to Named(sym) |
| CLASSconst | 92 | `CLASSconst Type` | `ConstantType(ClassConst(readType()))` |
| BYNAMEtype | 93 | `BYNAMEtype underlying_Type` | `ByName(readType())` |
| RECtype | 100 | `RECtype parent_Type` | `Rec(readType())`, see §6 |

#### Category 4 (tag + Nat + AST)

| Tag | Value | Grammar | Decoded to |
|-----|-------|---------|-----------|
| TERMREFsymbol | 114 | `TERMREFsymbol sym_ASTRef qual_Type` | `TermRef(readType(), addrMap(astRef).name)` |
| TERMREF | 115 | `TERMREF possiblySigned_NameRef qual_Type` | `TermRef(readType(), names(nameRef))` |
| TYPEREFsymbol | 116 | `TYPEREFsymbol sym_ASTRef qual_Type` | `Named(addrMap(astRef))` (qual ignored for now) |
| TYPEREF | 117 | `TYPEREF NameRef qual_Type` | `Named(sym)` if found, else `UnresolvedRef` |

Note on TYPEREFsymbol (line 169 grammar): the `sym_ASTRef` is a Nat that is the offset of the
symbol's definition node in the AST section. It is read with `readNat()`. The `qual_Type` is an
additional type that qualifies the reference (used for path-dependent types). For most codegen
use cases, only `addrMap(sym_ASTRef)` matters.

#### Category 5 (tag + Length + payload)

**APPLIEDtype (tag=161)**
```
APPLIEDtype Length tycon_Type arg_Type*
```
Grammar line 176. Payload is `readEnd()`, then decode tycon via `readType()`, then decode arg
types in a loop until cursor reaches end. Uses `TypeOps.applied` smart constructor for
normalization.

**ANNOTATEDtype (tag=153)**
```
ANNOTATEDtype Length underlying_Type annotation_Term
```
Grammar line 178. Decode underlying type, then skip the annotation term (a full TASTy term tree,
Category 5). Returns `Annotated(underlying, Annotation(annotType=skipped, argsPickle=empty))`.
Full annotation decode is deferred to Phase 4b or a later pass; Phase 4 stores the raw term bytes
as an opaque pickle slice (start addr through term end). The `Annotation` case class already has
`argsPickle: Chunk[Byte]`.

**ANDtype (tag=165)**
```
ANDtype Length left_Type right_Type
```
Grammar line 179. Decode both sides, apply `TypeOps.andType` for normalization.

**ORtype (tag=167)**
```
ORtype Length left_Type right_Type
```
Grammar line 180. Decode both sides, return `OrType(l, r)`.

**SUPERtype (tag=158)**
```
SUPERtype Length this_Type underlying_Type
```
Grammar line 174. Returns `SuperType(thisType, underlyingType)`.

**REFINEDtype (tag=159)**
```
REFINEDtype Length refinement_NameRef underlying_Type info_Type
```
Grammar line 175. The first Nat after Length is a NameRef (per numRefs: 1 leading ref). Read
nameRef via `readNat()`, then decode underlying and info types. Returns
`Refinement(underlying, names(nameRef), info)`.

**MATCHtype (tag=190)**
```
MATCHtype Length bound_Type sel_Type case_Type*
```
Grammar line 181. Read end, decode bound type, decode scrutinee type, then loop decoding
case types (each is a `MATCHCASEtype`). Returns `MatchType(bound, scrutinee, Chunk(cases...))`.

MATCHCASEtype (tag=192) has grammar `MATCHCASEtype Length pat_Type rhs_Type`. It is itself a
`Type` node decoded recursively. Phase 4 represents each case as `Applied(matchCaseSym, args)`
or as a simple pair type; the simplest correct representation is a raw `Applied` with no
normalization, since `MatchType.cases` is `Chunk[Type]` and each case is a structural pair.

**FLEXIBLEtype (tag=193)**
```
FLEXIBLEtype Length underlying_Type
```
Grammar line 183. Returns `FlexibleType(readType())`.

**METHODtype (tag=180)**
```
METHODtype Length result_Type TypesNames Modifier*
```
Grammar line 188. TypesNames is `TypeName*` where each `TypeName = typeOrBounds_ASTRef paramName_NameRef`.
The `numRefs` table returns -1 for METHODtype (line 897), indicating no leading reference Nats;
the payload is a sequence of type+name pairs followed by modifiers. Phase 4 decodes this as
`TypeLambda(params, resultType)` with synthetic param symbols, or more precisely follows the
plan: param types extracted from TypesNames, result type decoded first. The return type is
`TypeLambda(paramSyms, resultType)` — this is used in DEFDEF signatures for refinements only.

**POLYtype (tag=169)**
```
POLYtype Length result_Type TypesNames
```
Grammar line 187. Same structure as METHODtype minus the Modifier*. Decoded as
`TypeLambda(params, result)` with TypeParam symbols from the TypesNames.

**TYPELAMBDAtype (tag=170)**
```
TYPELAMBDAtype Length result_Type TypesNames
```
Grammar line 189. A proper type lambda `[TypesNames] => result`. Each TypeName entry produces a
binder Symbol with `SymbolKind.TypeParam`. The result type may reference these binders via
`PARAMtype`. Returns `TypeLambda(params, body)`.

**PARAMtype (tag=172)**
```
PARAMtype Length binder_ASTRef paramNum_Nat
```
Grammar line 186. `binder_ASTRef` is the offset of the enclosing TYPELAMBDAtype/POLYtype/
METHODtype node in the AST section (an ASTRef). `paramNum_Nat` is the 0-based index into that
binder's TypesNames. Returns `ParamRef(binderSym, idx)`. The binder sym is looked up via the
per-decode `addrCache` (the same Addr->Type cache used for SHAREDtype).

**TYPEREFin (tag=175)**
```
TYPEREFin Length NameRef qual_Type namespace_Type
```
Grammar line 172. First Nat after Length is NameRef (numRefs=1). This is a private type ref
scoped to `namespace`. When the target symbol is in addrMap, resolve normally. When not in
addrMap, this is a cross-file reference: decode the FQN from NameRef (it is a possibly-qualified
name in the names array), create `UnresolvedRef(fqn, new SingleAssign[Reflect.Type])`, and store
it in the per-file placeholder list. The caller replaces it in Phase C.

**TERMREFin (tag=174)**
```
TERMREFin Length possiblySigned_NameRef qual_Type owner_Type
```
Grammar line 148. Similar to TYPEREFin for terms. Decoded as `TermRef(qual, names(nameRef))`.

**WILDCARDtype / TYPEBOUNDS (tag=163)**
```
TYPEBOUNDS Length lowOrAlias_Type high_Type? Variance*
```
Grammar line 177. When `high_Type` is present: decode as `Wildcard(lo, hi)`. When only alias
(`= alias`): decode as the alias type directly (no Wildcard wrapper). The Variance* tags
(STABLE/COVARIANT/CONTRAVARIANT from category 1) are read but currently ignored in Phase 4.

**SKOLEMtype** (not a distinct tag in TastyFormat; Skolem types appear as internally synthesized
types in dotty's typer; they are represented as `Skolem(underlying)` in the Reflect ADT).

### Constant sub-tags (grammar lines 153-165)

These appear as the payload of a `CONSTANTtype` node, or standalone as constant paths.

| Tag | Cat | Value | Payload | Decoded to |
|-----|-----|-------|---------|-----------|
| UNITconst | 1 | 2 | none | `ConstantType(UnitConst)` |
| FALSEconst | 1 | 3 | none | `ConstantType(BooleanConst(false))` |
| TRUEconst | 1 | 4 | none | `ConstantType(BooleanConst(true))` |
| NULLconst | 1 | 5 | none | `ConstantType(NullConst)` |
| BYTEconst | 2 | 67 | Int (signed LEB128) | `ConstantType(ByteConst(v.toByte))` |
| SHORTconst | 2 | 68 | Int | `ConstantType(ShortConst(v.toShort))` |
| CHARconst | 2 | 69 | Nat | `ConstantType(CharConst(v.toChar))` |
| INTconst | 2 | 70 | Int (signed) | `ConstantType(IntConst(v))` |
| LONGconst | 2 | 71 | LongInt (signed LEB128) | `ConstantType(LongConst(v))` |
| FLOATconst | 2 | 72 | Int (bit pattern) | `ConstantType(FloatConst(intBitsToFloat))` |
| DOUBLEconst | 2 | 73 | LongInt (bit pattern) | `ConstantType(DoubleConst(longBitsToDouble))` |
| STRINGconst | 2 | 74 | NameRef (Nat) | `ConstantType(StringConst(names(ref).asString))` |
| NULLconst | 1 | 5 | none | `ConstantType(NullConst)` |
| CLASSconst | 3 | 92 | Type sub-AST | `ConstantType(ClassConst(readType()))` |

The existing `Constant.scala` (Phase 3) already decodes all these. Phase 4 wraps the result in
`ConstantType(c)` instead of returning the bare constant. Note that `CONSTANTtype` is NOT a
separate tag; constants encode directly as their path representation (e.g., `INTconst 70 42`
at a type position IS the constant type `42`). This is confirmed by the grammar: `Type = Path`,
and `Path = Constant`.

---

## 3. Forward-Reference Handling for Types

### Back-references via SHAREDtype

SHAREDtype (tag=61, Category 2) contains a single Nat `type_ASTRef` which is the byte-offset of
a previously decoded type in the current file's AST section. The per-file `addrCache` is a
`mutable.HashMap[Int, Reflect.Type]` that maps absolute AST byte offsets to decoded types.

Every time `readType` is called, the caller must record the starting addr (before reading the tag)
in `addrCache` after the type is decoded. When a SHAREDtype node is encountered, `readType`
returns `addrCache(astRef)` immediately without decoding anything further.

The cache is file-scoped (not shared across fibers or files). It is allocated once per
`TypeUnpickler.readType` invocation context (the per-file decode session).

### Forward-references via addrMap

Named type references within the same TASTy file use `TYPEREFdirect`/`TERMREFdirect` or
`TYPEREFsymbol`/`TERMREFsymbol`, all of which carry a `sym_ASTRef` Nat pointing to the
definition node's byte offset in the AST section.

Phase 3 (AstUnpickler pass 1) pre-allocates one `Reflect.Symbol` per definition node and inserts
it into `addrMap` during the forward walk. Because the entire symbol graph is pre-allocated before
any type decoding begins, `addrMap(addr)` is always available when type decoding uses it.

This is the same pre-scan pattern as dotty's `TreeUnpickler.indexStats`. Phase 4's TypeUnpickler
receives `addrMap: Map[Int, Reflect.Symbol]` from `Pass1Result.addrMap`.

Forward-reference resolution for type parameters within a single TYPELAMBDA/POLY/METHOD body
also uses addrMap: the binder symbols for type parameters are allocated during pass 1 (under
TYPEPARAM nodes), so they are in addrMap before any type node is decoded.

### Cross-file references via UnresolvedRef

TYPEREFin (tag=175) and TYPEREFpkg (tag=65) may reference symbols in other files. When the
target FQN is not in the local addrMap, Phase 4 creates an `UnresolvedRef`:

```scala
case class UnresolvedRef(fqn: String, replaceSlot: SingleAssign[Reflect.Type])
```

This is an internal sentinel, NOT part of `Reflect.Type`. Phase 4 holds a per-file
`mutable.ArrayBuffer[UnresolvedRef]` and adds to it whenever a cross-file reference is
encountered. The TypeUnpickler returns a placeholder `Named(unresolvedSymbol)` where
`unresolvedSymbol` is a `SymbolKind.Unresolved` symbol. Phase C resolves these once all files
are decoded.

---

## 4. Per-Thread TypeArena Design

**File**: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeArena.scala`

```scala
final class TypeArena:
    private val map = new scala.collection.mutable.HashMap[TypeKey, Reflect.Type]

    // Intern a type: look up by structural key, insert if absent, return canonical.
    def intern(t: Reflect.Type): Reflect.Type =
        val key = TypeKey.of(t)
        map.getOrElseUpdate(key, t)

    // Drain all entries from this arena into `canonical`, resolving cycles.
    // Called single-threaded in Phase C.
    def merge(canonical: TypeArena): Unit =
        val inProgress = new scala.collection.mutable.HashMap[TypeKey, Reflect.Type]()
        def internRec(t: Reflect.Type): Reflect.Type = ...  // see §5
        for (_, t) <- map do internRec(t)

    def values: Iterable[Reflect.Type] = map.values

object TypeArena:
    // Factory for the canonical arena used in Phase C.
    def canonical(): TypeArena = new TypeArena
```

**TypeKey**: a `final class` with structural hash and equality. The hash function combines the
type's ADT tag (ordinal or discriminator byte) with the hashes of its operands using a prime
mix.

```scala
final class TypeKey(val hash: Int, val t: Reflect.Type):
    override def hashCode(): Int = hash
    override def equals(other: Any): Boolean = other match
        case that: TypeKey => structuralEquals(t, that.t)
        case _             => false
```

`structuralEquals` recurses into the type structure. It must NOT call `t == u` on `Reflect.Type`
values (that is reference equality on the enum). It must compare case by case:
- `Named(s1)` == `Named(s2)` iff `s1 eq s2` (symbols are pre-canonical, reference equality is
  correct after Phase 3)
- `Applied(b1, a1)` == `Applied(b2, a2)` iff `b1.key == b2.key && a1.length == a2.length && all
  args structurally equal`
- etc.

**Hash function per Type case** (deterministic prime mixing):

```
Named(sym)               : 31 * sym.hashCode       (System.identityHashCode; symbols are canonical)
TermRef(prefix, name)    : 31 * prefix.hash + name.hashCode
Applied(base, args)      : 31 * base.hash + args.foldLeft(1)(_ * 31 + _.hash)
TypeLambda(ps, body)     : 31 * ps.length + body.hash
Function(ps, r, ctx)     : 31 * ps.hash + r.hash + (if ctx then 1 else 0)
Tuple(elems)             : 31 * elems.foldLeft(1)(_ * 31 + _.hash)
ByName(u)                : 31 * u.hash + 2
Repeated(e)              : 31 * e.hash + 3
Array(e)                 : 31 * e.hash + 4
Refinement(p, n, i)      : 31 * p.hash + n.hashCode + i.hash
Rec(p)                   : 31 * p.hash + 5
RecThis(rec)             : 31 * rec.hash + 6
AndType(l, r)            : 31 * l.hash + r.hash + 7
OrType(l, r)             : 31 * l.hash + r.hash + 8
Annotated(u, ann)        : 31 * u.hash + ann.hashCode
ConstantType(c)          : c.hashCode
ThisType(sym)            : 31 * sym.hashCode + 9
SuperType(s, m)          : 31 * s.hash + m.hash + 10
ParamRef(b, i)           : 31 * b.hashCode + i
Wildcard(lo, hi)         : 31 * lo.hash + hi.hash + 11
Skolem(u)                : 31 * u.hash + 12
MatchType(b, sc, cs)     : 31 * b.hash + sc.hash + cs.foldLeft(0)(_ + _.hash)
FlexibleType(u)          : 31 * u.hash + 13
```

Note: For `Rec`/`RecThis` the hash must be cycle-safe. A `Rec` node's hash is computed
structurally; `RecThis(rec)` hashes its `rec` reference, which is the `Rec` node. To avoid
infinite recursion in hash computation, the implementation caches the hash value on the
`TypeKey` at construction time and uses the cached value for recursion.

Simpler safe approach: use a thread-local `HashingInProgress` set keyed by identity, return 0
for a type already in the set. This matches how Scala's case class hashCode avoids cycles
(break the cycle at the first re-entry).

---

## 5. Phase C Merge with Cycle Handling

Phase C merges all per-thread TypeArenas into a single canonical arena. It runs single-threaded
after all Phase B decode fibers complete.

### Pseudocode (verbatim from DESIGN.md section 9)

```scala
val canonical = TypeArena.canonical()             // mutable.HashMap[TypeKey, Type]
val inProgress = mutable.HashMap[TypeKey, Type]() // cycle-break placeholders

def intern(t: Type): Type =
    val key = structuralKey(t)
    canonical.get(key) match
        case Some(canon) => canon
        case None =>
            inProgress.get(key) match
                case Some(placeholder) => placeholder   // cycle: return the placeholder
                case None =>
                    val placeholder = t  // use 't' itself as placeholder (not a separate Rec node)
                    inProgress(key) = placeholder
                    val recurInterned = t match
                        case Named(sym)             => t         // sym already canonical
                        case Applied(base, args)    => Applied(intern(base), args.map(intern))
                        case Function(ps, r, ctx)   => Function(ps.map(intern), intern(r), ctx)
                        case Tuple(elems)           => Tuple(elems.map(intern))
                        case ByName(u)              => ByName(intern(u))
                        case Repeated(e)            => Repeated(intern(e))
                        case Array(e)               => Array(intern(e))
                        case AndType(l, r)          => AndType(intern(l), intern(r))
                        case OrType(l, r)           => OrType(intern(l), intern(r))
                        case Refinement(p, n, i)    => Refinement(intern(p), n, intern(i))
                        case Rec(p)                 => Rec(intern(p))         // RecThis cycles back
                        case RecThis(_)             => t                       // primitive, no sub-types
                        case Annotated(u, ann)      => Annotated(intern(u), ann)
                        case SuperType(s, m)        => SuperType(intern(s), intern(m))
                        case Wildcard(lo, hi)       => Wildcard(intern(lo), intern(hi))
                        case Skolem(u)              => Skolem(intern(u))
                        case MatchType(b, sc, cs)   => MatchType(intern(b), intern(sc), cs.map(intern))
                        case FlexibleType(u)        => FlexibleType(intern(u))
                        case TermRef(p, n)          => TermRef(intern(p), n)
                        case TypeLambda(ps, body)   => TypeLambda(ps, intern(body))
                        case ParamRef(b, i)         => t                       // binder is a symbol, not a type
                        case ConstantType(_)        => t
                        case ThisType(_)            => t
                        case other                  => other
                    inProgress.remove(key)
                    canonical(key) = recurInterned
                    recurInterned

for arena <- perThreadArenas do
    arena.values.foreach(intern)
```

### Cycle handling detail

A `Rec(parent)` type contains `RecThis(rec)` back-references pointing to the same `Rec`. The
cycle path is: `intern(Rec(parent))` -> recurse into `intern(parent)` -> encounter `RecThis(Rec(...))`
-> `intern(RecThis(rec))` where `rec` points back to the original `Rec`. The `inProgress` map
traps the re-entry of the outer `Rec` and returns the placeholder, breaking the cycle.

The DESIGN.md pseudocode uses `t` itself as the placeholder (not a `RecPlaceholder` case). After
the recursive descent completes, the outer `Rec`'s key maps to the structurally-equal canonical
version. Any `RecThis` that received the placeholder will reference the same structural value.

### Complexity

O(total types across all arenas). For kyo-size classpath (~50K types), approximately 30ms
estimated (DESIGN.md section 9). Single-threaded, sequential, cache-friendly.

---

## 6. Type Normalization Smart Constructors

**File**: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeOps.scala`

All normalization happens in `TypeOps` at construction time, before passing to `arena.intern`.

```scala
object TypeOps:

    // FQN constants for normalization checks.
    private val FunctionPrefix        = "scala.Function"
    private val ContextFunctionPrefix = "scala.ContextFunction"
    private val TuplePrefix           = "scala.Tuple"
    private val ArrayFqn              = "scala.Array"
    private val SingletonFqn          = "scala.Singleton"

    /** Smart constructor for APPLIEDtype normalization. */
    def applied(base: Reflect.Type, args: Chunk[Reflect.Type]): Reflect.Type =
        base match
            case Reflect.Type.Named(sym) =>
                val fqn = sym.fullName.asString
                if fqn.startsWith(FunctionPrefix) && isDigitSuffix(fqn, FunctionPrefix.length) then
                    // Function2[A, B, C] => Function(Chunk(A, B), C, false)
                    Reflect.Type.Function(args.dropRight(1), args.last, false)
                else if fqn.startsWith(ContextFunctionPrefix) && isDigitSuffix(fqn, ContextFunctionPrefix.length) then
                    // ContextFunction1[A, B] => Function(Chunk(A), B, true)
                    Reflect.Type.Function(args.dropRight(1), args.last, true)
                else if fqn.startsWith(TuplePrefix) && isDigitSuffix(fqn, TuplePrefix.length) then
                    // Tuple2[A, B] => Tuple(Chunk(A, B))
                    Reflect.Type.Tuple(args)
                else if fqn == ArrayFqn && args.length == 1 then
                    // Array[T] => Array(T)
                    Reflect.Type.Array(args.head)
                else
                    Reflect.Type.Applied(base, args)
            case _ =>
                Reflect.Type.Applied(base, args)

    /** Smart constructor for ANDtype normalization. */
    def andType(left: Reflect.Type, right: Reflect.Type): Reflect.Type =
        (left, right) match
            case (Reflect.Type.Named(sym), _) if sym.fullName.asString == SingletonFqn => right
            case (_, Reflect.Type.Named(sym)) if sym.fullName.asString == SingletonFqn => left
            case _ => Reflect.Type.AndType(left, right)

    /** Direct Array constructor (for Java array types from classfile reader). */
    def mkArray(elem: Reflect.Type): Reflect.Type = Reflect.Type.Array(elem)

    private def isDigitSuffix(s: String, prefixLen: Int): Boolean =
        prefixLen < s.length && s.substring(prefixLen).forall(_.isDigit)
```

### Normalization table

| TASTy APPLIEDtype base | Args | Result Type case |
|------------------------|------|-----------------|
| `scala.FunctionN` | `(A1, ..., AN, R)` | `Function(Chunk(A1..AN), R, false)` |
| `scala.ContextFunctionN` | `(A1, ..., AN, R)` | `Function(Chunk(A1..AN), R, true)` |
| `scala.TupleN` | `(T1, ..., TN)` | `Tuple(Chunk(T1..TN))` |
| `scala.Array` | `(T)` | `Array(T)` |
| `scala.Singleton & X` | - | `X` (AndType collapse) |
| `X & scala.Singleton` | - | `X` (AndType collapse) |
| anything else | any | `Applied(base, args)` |

These match the normalizations done ad-hoc in `kyo-ts/TastyReader.scala` `resolveType` (per
DESIGN.md section 9).

---

## 7. UnresolvedRef Placeholder

**File**: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/UnresolvedRef.scala`

```scala
package kyo.internal.reflect.query

import kyo.Reflect
import kyo.internal.reflect.symbol.SingleAssign

/** Decoder-internal placeholder for a cross-file type reference whose target FQN
  * is not in the local Addr->Symbol map.
  *
  * NOT part of Reflect.Type. Lives only in the per-file decode context and in the
  * Pass1Result.placeholders accumulator passed to Phase C.
  *
  * Phase C resolves each UnresolvedRef by looking up fqn in the merged symbol graph.
  * If found: replaceSlot.set(Named(resolvedSym))
  * If not found: replaceSlot.set(Named(unresolvedSym)) where unresolvedSym has
  *   kind = SymbolKind.Unresolved and name = fqn.
  *
  * Sites that hold a reference to an UnresolvedRef call replaceSlot.get() after Phase C
  * to get the final resolved type. Callers that eagerly need the type (e.g., MatchType cases)
  * use a mutable indirection wrapper; see note on embedding below.
  */
final case class UnresolvedRef(fqn: String, replaceSlot: SingleAssign[Reflect.Type])
```

### How UnresolvedRef slots are replaced

During Phase 4 decode, a `TYPEREFin` or `TYPEREFpkg` with a cross-file FQN produces an
`UnresolvedRef` entry in a per-file buffer. The `TypeUnpickler` returns a temporary
`Named(unresolvedSym)` where `unresolvedSym.kind == SymbolKind.Unresolved` for the duration of
Phase B. Phase C then:

1. Iterates all `UnresolvedRef` entries in all files.
2. Looks up each `fqn` in the merged classpath symbol table.
3. Calls `replaceSlot.set(Named(resolvedSym))` (or `Named(unresolvedSym)` for truly missing FQNs).

Any type tree that embedded the `Named(unresolvedSym)` from Phase B is now stale. Phase C's
arena merge handles this: since `Named(sym)` structural equality depends on symbol identity, and
the unresolved-symbol instance was pre-allocated with the FQN as its name, the arena merge
canonicalizes based on the symbol identity. After Phase C, callers use `replaceSlot.get()` to
retrieve the final `Named(resolvedSym)`.

The `Pass1Result` type gains a `placeholders` field in Phase 4:
```scala
final case class Pass1Result(
    symbols: Chunk[Reflect.Symbol],
    addrMap: Map[Int, Reflect.Symbol],
    rootSymbol: Reflect.Symbol,
    placeholders: Chunk[UnresolvedRef]   // new in Phase 4
)
```

---

## 8. Edge Cases and Gotchas

### SHAREDtype back-references: per-file Addr->Type cache

`SHAREDtype` (tag=61) carries a single `type_ASTRef` Nat that is a byte-offset in the current
file's AST section. The type at that offset has been decoded earlier in the same decode session.

Implementation: allocate `val addrCache = new mutable.HashMap[Int, Reflect.Type]()` in the
TypeUnpickler decode session. Before returning from any `readType` call, record:
```scala
addrCache(startAddr) = decodedType
```
where `startAddr` is the position of the tag byte that started this type node.

When the tag is `SHAREDtype`: `addrCache(view.readNat())` — look up the referenced addr and
return the cached type. Do not add a new addrCache entry for SHAREDtype itself.

Scope: per-file decode session only. NOT shared across files or threads.

### RECtype / RECthis cycles

`RECtype` (tag=100, Category 3) encodes a recursive refined type. It wraps a single `parent_Type`
which may contain `RECthis` (tag=66, Category 2) back-references.

`RECthis` carries `recType_ASTRef` — the addr of the enclosing `RECtype` node. During decoding,
the `RECtype` addr is not yet in `addrCache` when the inner `RECthis` is encountered (decoding
is top-down). This creates a chicken-and-egg situation.

Solution: use a per-decode-session `inProgressRec` map:
```scala
val inProgressRec = new mutable.HashMap[Int, Reflect.Type.Rec]()
```
When entering a `RECtype` at addr `a`:
1. Allocate a placeholder `Rec(null)` (or use a `var` for the parent slot).
2. Insert `inProgressRec(a) = placeholder`.
3. Decode the parent type (which may encounter `RECthis(a)` → `inProgressRec(a)` → placeholder).
4. Patch the placeholder's parent with the decoded parent.
5. Remove from `inProgressRec`.

In Scala, because `Type` is an enum (sealed), case classes are immutable. The cleanest approach
is to decode the parent type with a mutable indirection:
- Decode parent type.
- Construct `Rec(decodedParent)`.
- Any `RECthis(recAddr)` encountered during parent decoding returns
  `RecThis(addrCache(recAddr))` — but addrCache doesn't have it yet.

Alternative (simpler, matches DESIGN.md): after decoding the parent, construct
`Rec(decodedParent)` and insert into addrCache. Any RecThis nodes in the tree will have
already received a placeholder via inProgressRec. After construction, patch the placeholder
if needed. This requires a two-step construction; see DESIGN.md section 9 "Cycle handling."

The test (test 4 in TypeArenaTest) verifies that `merge` on a `Rec/RecThis` cycle does not
stack overflow. The TypeUnpickler test (test 23) verifies decode doesn't overflow.

### MATCHtype: scrutinee + bound + cases

Grammar: `MATCHtype Length bound_Type sel_Type case_Type*`

The `bound_Type` is an OPTIONAL upper bound of all right-hand sides (present when the match type
has a user-declared bound; absent otherwise). In TASTy it is always present but may encode as
`TYPEBOUNDS` with `Nothing` and `Any` for the unbounded case. Phase 4 decodes it unconditionally
as the first type after Length.

The `case_Type*` list consists of `MATCHCASEtype` nodes and/or `TYPELAMBDAtype` nodes wrapping
MATCHCASEtypes (for parameterized cases). Each case decodes as a `Type` value and is collected
into `MatchType.cases: Chunk[Type]`.

There are no guards in the TASTy type encoding; guards only appear in term-level pattern match
(CaseDef with a guard term). `MATCHCASEtype` is purely `pat_Type => rhs_Type`.

### Constants: payload widths

| Constant | Read method | Note |
|----------|-------------|------|
| UNITconst/FALSEconst/TRUEconst/NULLconst | none (tag only) | category 1 |
| BYTEconst | `readNat()` then `.toByte` | 1 Nat |
| SHORTconst | `readNat()` then `.toShort` | 1 Nat |
| CHARconst | `readNat()` then `.toChar` | 1 Nat (unsigned) |
| INTconst | `readInt()` (signed) | 1 signed Nat |
| LONGconst | `readLongNat()` | signed LEB128 Long |
| FLOATconst | `readNat()` then `java.lang.Float.intBitsToFloat` | 4-byte bit pattern as Nat |
| DOUBLEconst | `readLongNat()` then `java.lang.Double.longBitsToDouble` | 8-byte bit pattern as LongInt |
| STRINGconst | `readNat()` → NameRef into names array | NOT a raw UTF-8 inline; goes through name table |
| CLASSconst | `readType()` (a full type sub-AST) | category 3 |

The existing `Constant.scala` (Phase 3) already implements all of these correctly. Phase 4
wraps with `ConstantType(c)` for type positions.

### TYPELAMBDA binders: Addr -> Symbol from Phase 3

When decoding `TYPELAMBDAtype Length result_Type TypesNames`, the `TypesNames = TypeName*`
entries define the lambda's type parameters. Each TypeName is
`typeOrBounds_ASTRef paramName_NameRef` — two values, the first being an ASTRef.

If the `typeOrBounds_ASTRef` refers to an addr already in the pass-1 `addrMap`, retrieve that
Symbol (a TYPEPARAM symbol). If not found (e.g., the type param is defined inline within the
lambda, not as a standalone TYPEPARAM node), create a synthetic TypeParam symbol. These inline
binders are local to the lambda and will not appear in the global `addrMap`; they need a local
binder registry within the TypeUnpickler decode session.

`PARAMtype Length binder_ASTRef paramNum_Nat`: `binder_ASTRef` points to the enclosing lambda's
start addr, not to a TYPEPARAM node. This requires a separate `binderAddrMap: mutable.HashMap[Int,
Chunk[Reflect.Symbol]]` keyed on the lambda's start addr, mapping to its parameter symbol list.
Populated when entering a TYPELAMBDAtype, used when encountering PARAMtype.

---

## 9. Test-Data Suggestions

### Real TASTy fixture: PlainClass.tasty

PlainClass.tasty is on disk from Phase 2 (referenced in NameUnpicklerTest). Use the same fixture
for TypeUnpicklerTest tests 12-24. The fixture should contain:
- A top-level class with typed fields (tests 12, 15)
- A method with a by-name parameter (test 13)
- A varargs method (test 14)
- An applied type like `List[String]` (test 15)
- A type alias for a match type (test 21)
- A method with annotated return type (test 18)
- Or/And type combinations in type bounds (tests 19-20)
- A recursive type (RECtype) if achievable; otherwise use a synthetic byte sequence (test 23)

If PlainClass.tasty is insufficient, add a FixtureClasses.scala to the test fixtures directory
(not a Scala source file Phase 4 itself writes, but a test fixture already compiled).

### Synthetic byte sequences for edge cases

Each synthetic test builds a minimal valid TASTy fragment bytes in the test body. The helper
`ByteView.apply(Array[Byte])` creates a Heap view over raw bytes.

**Empty Applied (raw type, no args)**:
```
APPLIEDtype (161) Length tycon_only
// tycon = TYPEREFsymbol pointing to some symbol addr
// args = empty
// Expected: Applied(Named(sym), Chunk.empty)
```

**Tuple2 normalization**:
```
APPLIEDtype Length [TYPEREFpkg("scala.Tuple2")] [arg1] [arg2]
// Expected: Tuple(Chunk(arg1, arg2))
```

**Function2 normalization**:
```
APPLIEDtype Length [TYPEREFpkg("scala.Function2")] [A] [B] [C]
// Expected: Function(Chunk(A, B), C, false)
```

**ContextFunction1 normalization**:
```
APPLIEDtype Length [TYPEREFpkg("scala.ContextFunction1")] [A] [B]
// Expected: Function(Chunk(A), B, true)
```

**AndType + Singleton collapse**:
```
ANDtype Length [TYPEREFpkg("scala.Singleton")] [TYPEREFpkg("scala.Int")]
// Expected: Named(intSym)
```

**RECtype self-reference cycle**:
Construct the bytes for `RECtype parent_Type` where parent contains `RECthis recType_ASTRef`
pointing back. The bytes are: `100 <sub_type_bytes>` where the sub_type is `66 <addr_of_100>`.
The addr is relative to the beginning of the section. This is the one test that genuinely
requires encoding knowledge; construct it programmatically:
```scala
val bytes = Array[Byte](...)   // hand-constructed with correct addr
val view = ByteView(bytes)
val t = decode(view, ...)
assert(t.isInstanceOf[Reflect.Type.Rec])
assert(!hasStackOverflow)
```

**Hash-cons round-trip**:
```scala
val t1 = decode(view1, ...) // position A in file 1
val t2 = decode(view2, ...) // position B in file 2, structurally same type
val a1 = TypeArena.canonical()
val a2 = TypeArena.canonical()
val canon = TypeArena.canonical()
a1.intern(t1); a2.intern(t2)
a1.merge(canon); a2.merge(canon)
assert(canon.intern(t1) eq canon.intern(t2))  // reference equality after merge
```

---

## 10. Anti-Flakiness Deltas

### Per-thread arena tests: single-threaded fixtures only

Phase 4 TypeArena tests use single-threaded fixtures. The arena itself is not thread-safe
(intentionally; per the design it is fiber-local). Tests must NOT use `Async.foreach` or
`Async.run` to introduce concurrency. The Phase C merge algorithm is single-threaded and
tested by constructing multiple TypeArena instances in the same thread and calling `merge`.

### No real parallelism in Phase 4 tests

Phase 7 wires the actual per-fiber arena allocation. Phase 4 tests treat TypeArena as a
plain mutable container used sequentially. The "per-thread" naming refers to the Phase B
execution model, not to any threading in Phase 4 tests.

### Phase C merge tests: synthetic arenas as input

Merge tests allocate two (or more) `TypeArena` instances, populate them with structurally
identical types via direct `arena.intern(t)` calls, then call `arena1.merge(canonical)` and
`arena2.merge(canonical)`, and assert that equivalent types from each arena map to the same
canonical reference.

### Timeout guard for cycle tests

Any test involving `Rec/RecThis` cycles must wrap the decode/merge call with a timeout or
max-recursion depth check in the test body. A simple approach: run the test on a thread with
limited stack size:
```scala
var result: Option[Reflect.Type] = None
val t = new Thread(null, () => { result = Some(decode(recBytes, ...)) }, "cycle-test", 64 * 1024)
t.start(); t.join(5000)
assert(result.isDefined, "cycle decode timed out or stack-overflowed")
```

---

## 11. Concerns

### Concern 1: RECthis addr resolution timing

The `RECthis recType_ASTRef` node stores the addr of the enclosing `RECtype` node. During a
top-down decode, when `RECthis` is encountered, the enclosing `RECtype` node has already
consumed its tag and length but the result type has not been inserted into `addrCache` yet.
The `inProgressRec` map (section 8 "RECtype / RECthis cycles") must be seeded BEFORE recursing
into the parent type, not after. This is an ordering constraint that must be explicitly tested.

The test (TypeUnpicklerTest test 23) must fire and verify that a specifically crafted
self-referential RECtype byte sequence decodes without stackoverflow. This test is critical
and non-trivial to write; the fixture bytes must be hand-constructed with the correct addr value.

### Concern 2: PARAMtype binder resolution requires lambda-local addr registry

Phase 3's addrMap only contains definition-level symbols (VALDEF, DEFDEF, TYPEDEF, TYPEPARAM,
PARAM from the AST section). `TYPELAMBDAtype`'s TypesNames parameter symbols may or may not
appear as standalone TYPEPARAM nodes; when they appear inline, they are NOT in addrMap.

The TypeUnpickler needs a decode-session-local `binderAddrMap: mutable.HashMap[Int, Chunk[Symbol]]`
mapping the start addr of each TYPELAMBDAtype/POLYtype/METHODtype node to its parameter symbol
list. `PARAMtype` resolution then uses `binderAddrMap(binderASTRef)(paramNum)`.

This is NOT a Phase 3 concern; it is new to Phase 4. The implementation agent must add this
local registry. It is not documented in the plan's file list and is an addendum to
`TypeUnpickler.scala`'s internal state.

### Concern 3: MATCHCASEtype decoded as Applied or as a dedicated representation

`MATCHCASEtype Length pat_Type rhs_Type` is a type node. The public `Reflect.Type` ADT has no
`MatchCase` case. Each case is stored in `MatchType.cases: Chunk[Type]`. The most
straightforward representation is `Applied(Named(matchCaseSym), Chunk(pat, rhs))` but there is
no `matchCaseSym` available. An alternative is a synthetic tuple representation or to use
`Refinement` as a structural pair.

Recommended: represent each MATCHCASEtype as `Applied(MatchType.CaseSentinel, Chunk(pat, rhs))`
where `CaseSentinel` is a stable internal `Named(sym)` for a synthetic sentinel symbol, OR use
a dedicated representation at the consumer level (callers can extract pat/rhs by position from
the `Chunk[Type]`). The simplest correct implementation: represent as `Applied(Named(sentinel),
Chunk(patType, rhsType))` with a module-level `val MatchCaseSentinel: Symbol` that is a
synthetic symbol created once.

This needs a decision before implementation starts. The plan does not specify the representation
for MatchType cases beyond `cases: Chunk[Type]`.

### Concern 4: AstUnpickler.Pass1Result.placeholders field not yet on disk

The execution-plan references `Pass1Result.placeholders: Chunk[UnresolvedRef]` but the current
on-disk `AstUnpickler.scala` (Phase 3) does not have this field. Phase 4 modifies
`AstUnpickler.scala` to add it, per the plan's "Files to modify" section. The implementation
agent must add this field and wire it from Phase 4's TypeUnpickler context back to Pass1Result.

### Concern 5: TypeArena.intern on Applied vs Function vs Tuple after normalization

When TypeOps.applied normalizes `Applied(Named(Function2), args)` to `Function(...)`, the
TypeArena interns the resulting `Function(...)` under the `Function` key, not the `Applied` key.
Two types normalized to the same `Function(Chunk(A, B), C, false)` from different sites must
intern to the same canonical reference. This works correctly as long as TypeOps normalization
runs BEFORE `arena.intern`. The implementation must guarantee this ordering: apply smart
constructors first, then intern.

### Concern 6: Cross-platform correctness of Double/Float bit-pattern decode

`FLOATconst` uses `java.lang.Float.intBitsToFloat` and `DOUBLEconst` uses
`java.lang.Double.longBitsToDouble`. These are available on JVM, Scala.js (via the JS runtime),
and Scala Native (via C interop). The existing `Constant.scala` already uses them. No new
concerns here, but the Phase 4 TypeUnpickler must import/call the same path as Constant.scala
rather than rolling its own.
