# Phase 5b Prep: Java/Scala Unification

Preparation document for the implementing agent. Read every section before writing a single line of
code. This document is the authoritative reference for Phase 5b; do not invent alternatives to the
patterns specified here.

Source of truth: `execution-plan.md` lines 400-459; `DESIGN.md` §11 (lines 560-668); `PHASE-5-PREP.md`
(Phase 5 contract); committed Phase 1-4 code on disk.

---

## 1. Verbatim API Signatures Phase 5b Calls

### 1.1 Phase 5 Classfile Reader Output Contract

Phase 5 (not yet committed at Phase 5b start) produces four files in
`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/`. Phase 5b receives their
output. The contract:

```scala
// ClassfileUnpickler.scala (Phase 5 deliverable)
object ClassfileUnpickler:
    def read(
        bytes:    Array[Byte],
        interner: Interner,
        arena:    TypeArena,
        home:     ClasspathRef
    ): ClassfileResult < Abort[ReflectError]

final case class ClassfileResult(
    classSymbol:     Reflect.Symbol,
    innerClassTable: Map[String, (String, String)],  // key=innerBinaryName -> (outerBinaryName, innerSimpleName)
    symbols:         Chunk[Reflect.Symbol],
    arena:           TypeArena
)
```

`innerClassTable` is populated from the raw InnerClasses attribute: Phase 5 reads the 4-tuple
`(inner_class_info_index, outer_class_info_index, inner_name_index, inner_class_access_flags)` and
resolves binary-name strings via the constant pool. The map key is the inner class binary name
(e.g., `"java/util/Map$Entry"`). The value tuple is `(outerBinaryName, innerSimpleName)`, e.g.,
`("java/util/Map", "Entry")`.

If `outer_class_info_index == 0` (local or anonymous): `outerBinaryName = ""`.
If `inner_name_index == 0` (anonymous): `innerSimpleName = ""`.

```scala
// JavaSignatures.scala (Phase 5 deliverable)
object JavaSignatures:
    def parseFieldSignature(
        sig:      String,
        interner: Interner,
        addrMap:  Map[Int, Reflect.Symbol]   // type-param scope; for class references produce Unresolved
    ): Reflect.Type < Abort[ReflectError]

    def parseMethodSignature(
        sig:      String,
        interner: Interner,
        addrMap:  Map[Int, Reflect.Symbol]
    ): Reflect.Type.Function < Abort[ReflectError]

    def parseClassSignature(
        sig:      String,
        interner: Interner,
        addrMap:  Map[Int, Reflect.Symbol]
    ): (Chunk[Reflect.Type], Chunk[Reflect.Type]) < Abort[ReflectError]
    // returns (superclasses, interfaces)
```

Note: `addrMap: Map[Int, Reflect.Symbol]` is named from the TASTy context but in Phase 5/5b it is
used only as an opaque token passed to `JavaSignatures`. The actual type-param scope inside
`JavaSignatures` is a `Map[String, Reflect.Symbol]` keyed on identifier strings; the `addrMap`
parameter carries this via a wrapper or is repurposed. The implementing agent must not introduce a
naming mismatch. See Phase 5 concern 10.2.

### 1.2 Symbol.makeSymbol (internal factory)

File: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Symbol.scala`, line 17.

```scala
object Symbol:
    def makeSymbol(
        kind:   Reflect.SymbolKind,
        flags:  Reflect.Flags,
        name:   Reflect.Name,
        owner:  Reflect.Symbol,
        home:   ClasspathRef,
        origin: Reflect.Symbol.Origin
    ): Reflect.Symbol = Reflect.Symbol.make(kind, flags, name, owner, home, origin)
```

Phase 5b uses `Reflect.Symbol.JavaOrigin` as origin for all Java symbols (same as Phase 5):

```scala
Reflect.Symbol.JavaOrigin   // case object, no parameters
```

### 1.3 Reflect.Symbol Public API

File: `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, lines 202-229.

```scala
final class Symbol private[Reflect] (
    val kind:  SymbolKind,
    val flags: Flags,
    val name:  Name,
    val owner: Symbol,
    private[Reflect] val home: ClasspathRef,
    private[kyo] val origin: Symbol.Origin
):
    def fullName: Name             // computed: walks owner chain, dots as separator
    def binaryName: String         // computed: dotted with '$' for nested (see §1.6 below)
    def isJava: Boolean            // = flags.contains(Flag.JavaDefined)
    def javaSpecific: Maybe[JavaMetadata] = Absent  // overridden in Phase 5b's JavaSymbol subclass or wired differently
```

CRITICAL: `computeFullName` (Reflect.scala line 237) walks the owner chain and joins with `.`.
`computeBinaryName` (line 250) currently also uses `.` — this is the pre-Phase-5b behavior.
Phase 5b's `FqnCanonicalizer` does NOT change `computeBinaryName`; instead the binary name for
Java-sourced symbols must be stored directly on the symbol (or computed from the InnerClasses table
at construction time so the owner chain already reflects the correct nesting topology).

IMPORTANT DESIGN CHOICE: Phase 5b must wire the owner chain for Java inner-class symbols so that
`computeFullName` produces `"java.util.Map.Entry"` (dotted) naturally. The `FqnCanonicalizer` is
an intermediate tool used during symbol construction to determine the correct owner (outer class
symbol), not a post-hoc transformer of `fullName` output. Specifically:

- Before making inner-class symbols, call `FqnCanonicalizer.toFullName(binaryName, innerClassTable)`
  to get the dotted FQN.
- Split on `.` to find the parent owner symbol.
- Set the `owner` field of the inner-class symbol to the outer class symbol.
- Then `computeFullName` produces the correct dotted form automatically.

### 1.4 JavaMetadata Constructor

File: `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, lines 142-148.

```scala
final case class JavaMetadata(
    throwsTypes:      Chunk[Type],           // from Exceptions attribute on methods
    annotations:      Chunk[JavaAnnotation], // from RuntimeVisibleAnnotations + RuntimeInvisibleAnnotations
    enclosingMethod:  Maybe[(Symbol, Name)], // from EnclosingMethod attribute; (enclosingClassSym, methodName)
    accessFlags:      Int,                   // raw JVM access_flags bitmask
    recordComponents: Chunk[(Name, Type)]    // from Record attribute; empty unless Flag.JavaRecord
)
```

The only constructor is the case class apply. Provide all five fields. For symbols where a field is
inapplicable (e.g., `throwsTypes` for a class symbol, `recordComponents` for a non-record), pass
`Chunk.empty` or `Absent` respectively.

### 1.5 JavaAnnotation and JavaAnnotation.Value

File: `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, lines 150-162.

```scala
final case class JavaAnnotation(
    annotationClass: Symbol,
    values: Map[Name, JavaAnnotation.Value]
)

object JavaAnnotation:
    enum Value:
        case StringVal(s: String)
        case IntVal(i: Int)
        case LongVal(l: Long)
        case BoolVal(b: Boolean)
        case ClassVal(tpe: Type)
        case EnumVal(enumType: Symbol, constant: Name)
        case ArrayVal(elements: Chunk[Value])
        case AnnotationVal(nested: JavaAnnotation)
    end Value
end JavaAnnotation
```

### 1.6 Reflect.SymbolKind Enum

File: `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, lines 117-121.

```scala
enum SymbolKind derives CanEqual:
    case Package, Class, Trait, Object, Method, Field, Val, Var,
        TypeAlias, OpaqueType, AbstractType, TypeParam, Parameter,
        Unresolved
end SymbolKind
```

Total: 14 cases. Full matrix in §7 below.

### 1.7 Flags API

File: `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, lines 61-113.

```scala
object Flag:
    val Public:      Flag = Flag(1L << 3,  "Public")
    val Final:       Flag = Flag(1L << 4,  "Final")
    val Sealed:      Flag = Flag(1L << 5,  "Sealed")
    val Abstract:    Flag = Flag(1L << 6,  "Abstract")
    val Enum:        Flag = Flag(1L << 14, "Enum")
    val JavaRecord:  Flag = Flag(1L << 15, "JavaRecord")
    val JavaDefined: Flag = Flag(1L << 13, "JavaDefined")
    val Trait:       Flag = Flag(1L << 26, "Trait")
    val Synthetic:   Flag = Flag(1L << 12, "Synthetic")
    val Module:      Flag = Flag(1L << 11, "Module")
    val Case:        Flag = Flag(1L << 10, "Case")
    // ... (full list at lines 68-113)
```

`Flag.JavaRecord` is already defined at bit 15 (line 84). No new flag is needed.

File: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Flags.scala`, lines 71-82.

```scala
def fromJvmAccessFlags(acc: Int): Reflect.Flags =
    var bits = 0L
    if (acc & 0x0001) != 0 then bits |= Flag.Public.bit
    if (acc & 0x0002) != 0 then bits |= Flag.Private.bit
    if (acc & 0x0004) != 0 then bits |= Flag.Protected.bit
    if (acc & 0x0010) != 0 then bits |= Flag.Final.bit
    if (acc & 0x0200) != 0 then bits |= Flag.Abstract.bit   // NOTE: doubles as Trait for interfaces
    if (acc & 0x1000) != 0 then bits |= Flag.Synthetic.bit
    if (acc & 0x4000) != 0 then bits |= Flag.Enum.bit
    if (acc & 0x0008) != 0 then bits |= Flag.JavaDefined.bit // ACC_STATIC
    new Reflect.Flags(bits)
```

Phase 5b modifies `fromJvmAccessFlags` to add entries for `ACC_RECORD` (detect via Record
attribute presence, not the bit) and `ACC_INTERFACE` (map to `Flag.Trait`). See §3.

### 1.8 TypeOps.mkArray

File: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeOps.scala`, line 55.

```scala
def mkArray(elem: Reflect.Type): Reflect.Type = Reflect.Type.Array(elem)
```

Both Java `int[]` and Scala `Array[Int]` reach this. Java arrays from the classfile reader call
this directly; Scala arrays from TASTy are normalized by `TypeOps.applied` which detects
`scala.Array` and produces `Array(args.head)`.

### 1.9 TypeArena

File: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeArena.scala`, lines 17-103.

```scala
final class TypeArena:
    def intern(t: Reflect.Type): Reflect.Type   // hash-cons; returns canonical instance
    def merge(canonical: TypeArena): Unit       // called in Phase C merge
    def values: Iterable[Reflect.Type]

object TypeArena:
    def canonical(): TypeArena = new TypeArena  // create a fresh arena
```

Phase 5b, like Phase 5, does NOT call `arena.intern` during type construction. Types are built
directly from `Type.Named(...)`, `Type.Applied(...)`, etc. The `arena` parameter in
`ClassfileResult` is threaded through so Phase 7 can merge arenas across files.

---

## 2. InnerClasses Attribute Spec and FQN Resolution Protocol

### 2.1 JVMS §4.7.6 — InnerClasses Attribute Binary Layout

```
InnerClasses_attribute {
    u2 attribute_name_index;     // cp index -> Utf8 "InnerClasses"
    u4 attribute_length;         // byte count of the body below
    u2 number_of_classes;
    { u2 inner_class_info_index;   // cp index -> CONSTANT_Class -> binary name of the inner class
                                   // 0 if the class is itself anonymous (only present when the
                                   // enclosing class mentions it)
      u2 outer_class_info_index;   // cp index -> CONSTANT_Class -> binary name of the directly
                                   // enclosing class; 0 if local or anonymous
      u2 inner_name_index;         // cp index -> Utf8 simple class name (no package, no $)
                                   // 0 if anonymous
      u2 inner_class_access_flags; // access flags specific to the inner class declaration
    } classes[number_of_classes];
}
```

Reading with Phase 5's helpers:

```
number_of_classes = readU2(view)
for i in 0 until number_of_classes:
    innerClassInfoIdx  = readU2(view)   // 0 means anonymous
    outerClassInfoIdx  = readU2(view)   // 0 means local or anonymous
    innerNameIdx       = readU2(view)   // 0 means anonymous
    innerAccessFlags   = readU2(view)
    // resolve names:
    innerBinaryName = if innerClassInfoIdx != 0 then pool.classRef(innerClassInfoIdx) else ""
    outerBinaryName = if outerClassInfoIdx != 0 then pool.classRef(outerClassInfoIdx) else ""
    innerSimpleName = if innerNameIdx      != 0 then pool.utf8(innerNameIdx)          else ""
    // store in table keyed by innerBinaryName (skip entries where innerBinaryName is "")
```

The resulting `Map[String, (String, String)]` has:
- key: inner binary name e.g. `"java/util/Map$Entry"`
- value: `(outerBinaryName, innerSimpleName)` e.g. `("java/util/Map", "Entry")`

### 2.2 FQN Resolution Protocol

Given a binary name (using `/` separators) and the innerClassTable:

Step 1: Convert `/` to `.` to get the candidate dotted name: `"java.util.Map$Entry"`.

Step 2: Look up the binary name (with `/` replaced by `/`, keeping `$`) in the innerClassTable.
- If NOT found: this class is a top-level class. Its dotted FQN is simply the binary name with
  `/` replaced by `.` and with all `$` treated as literal characters. Example:
  `"com/example/Foo$Bar"` with no InnerClasses entry -> FQN = `"com.example.Foo$Bar"`.
- If FOUND with `outerBinaryName == ""`: anonymous or local class. Keep the raw form with `$`.
  FQN = binary name with `/` -> `.`, `$` preserved. This is the fallback for callers.
- If FOUND with `outerBinaryName != ""` and `innerSimpleName != ""`: named inner class.
  1. Recursively resolve the outer's FQN: `FqnCanonicalizer.toFullName(outerBinaryName, table)`.
  2. Append `.` + `innerSimpleName`.
  Result: `"java.util.Map.Entry"`.

Step 3: Recursion terminates when the binary name has no entry in the table (top-level class).

Note: InnerClasses entries form a tree (can be multi-level: `A.B.C` maps `A$B$C` -> `A$B` -> `A`).
The recursion handles arbitrary depth.

Example walk for `"java/util/Map$Entry"`:
```
table("java/util/Map$Entry") = ("java/util/Map", "Entry")
outerFqn = toFullName("java/util/Map", table)
  table("java/util/Map") = not found
  -> "java.util.Map"
result = "java.util.Map" + "." + "Entry" = "java.util.Map.Entry"
```

### 2.3 Reverse Mapping: toBinaryName

```scala
def toBinaryName(fullName: String, innerClassTable: Map[String, (String, String)]): String =
    // Walk the table values to find the entry whose resolved dotted name equals fullName.
    // For performance Phase 5b may build a reverse index at construction time.
    // Return the key (binary name with '/' separators).
```

The reverse mapping is needed for `Symbol.binaryName` on Java-sourced symbols where the owner
chain uses dotted separators. Phase 5b should store the original binary name on the symbol
(e.g., in a side table or by using a custom `Symbol.Origin` extension) to avoid recomputing it.

---

## 3. FqnCanonicalizer Design

New file: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/FqnCanonicalizer.scala`

```scala
package kyo.internal.reflect.symbol

object FqnCanonicalizer:

    /**
     * Convert a JVM binary name (with '/' separators and '$' for nesting)
     * to a dotted fully-qualified name using the InnerClasses attribute table.
     *
     * innerClassTable: key = inner binary name (slashes, not dots); value = (outerBinaryName, innerSimpleName)
     * An entry with outerBinaryName == "" is anonymous/local; preserve '$' form.
     *
     * Examples:
     *   toFullName("java/util/Map$Entry", table) = "java.util.Map.Entry"
     *   toFullName("java/lang/Object", emptyTable) = "java.lang.Object"
     *   toFullName("com/example/Foo$1", table with ("com/example/Foo$1", ("", ""))) = "com.example.Foo$1"
     */
    def toFullName(binaryName: String, innerClassTable: Map[String, (String, String)]): String =
        innerClassTable.get(binaryName) match
            case None =>
                // Not in table: top-level class. Dots only from '/' replacement.
                binaryName.replace('/', '.')
            case Some(("", _)) | Some((_, "")) =>
                // Anonymous or local: outer is unknown or name is absent. Keep '$' form.
                binaryName.replace('/', '.')
            case Some((outerBinaryName, innerSimpleName)) =>
                // Named inner class: recurse on outer.
                val outerFqn = toFullName(outerBinaryName, innerClassTable)
                outerFqn + "." + innerSimpleName

    /**
     * Convert a dotted FQN back to JVM binary name.
     * Requires a pre-built reverse index (dotted FQN -> binary name).
     * Phase 5b builds this at ClassfileResult construction time.
     */
    def toBinaryName(fqn: String, reverseIndex: Map[String, String]): String =
        reverseIndex.getOrElse(fqn, fqn.replace('.', '/'))
```

Design notes (from DESIGN.md §11 "FQN canonicalization"):
- Caller-facing names always use dotted form: `java.util.Map.Entry`, not `java.util.Map$Entry`.
- `Symbol.binaryName` returns the JVM form: `"java/util/Map$Entry"`.
- `cp.findClass` accepts only dotted form.
- `cp.findClassByBinary(binaryName)` is a separate entry point for JVM form.

---

## 4. Java Record Support

### 4.1 JVMS §4.7.30 — Record Attribute Binary Layout (Java 16+)

```
Record_attribute {
    u2 attribute_name_index;    // cp -> Utf8 "Record"
    u4 attribute_length;
    u2 components_count;
    { u2 name_index;            // cp -> Utf8 (component field name, e.g. "x")
      u2 descriptor_index;      // cp -> Utf8 (JVM field descriptor, e.g. "I" for int)
      u2 attributes_count;
      attribute_info attributes[attributes_count];  // may include:
          // "Signature" -> generic type sig
          // "RuntimeVisibleAnnotations"
          // "RuntimeInvisibleAnnotations"
    } components[components_count];
}
```

Reading protocol:

```
components_count = readU2(view)
result = new ArrayBuffer[(Name, Type)]
for i in 0 until components_count:
    nameIdx       = readU2(view)
    descriptorIdx = readU2(view)
    attrCount     = readU2(view)
    var sigType: Option[Reflect.Type] = None
    for j in 0 until attrCount:
        attrNameIdx = readU2(view)
        attrLen     = readU4(view)
        attrName    = pool.utf8(attrNameIdx)
        if attrName == "Signature":
            sigIdx  = readU2(view)
            sigStr  = pool.utf8(sigIdx)
            sigType = Some(JavaSignatures.parseFieldSignature(sigStr, ...))
        else:
            skip attrLen bytes
    componentName = Name.wrap(interner.intern(nameBytes, 0, nameBytes.length))
    componentType = sigType.getOrElse(parseErasedDescriptor(pool.utf8(descriptorIdx)))
    result += (componentName, componentType)
```

### 4.2 ACC_RECORD Flag Detection

DO NOT use the `ACC_RECORD = 0x0010` flag bit to determine if a class is a record. This bit
conflicts with `ACC_FINAL = 0x0010` at the class level. Records are always final, so both bits
are set simultaneously.

CORRECT approach: detect a Java record by the PRESENCE of the `Record` attribute in the class's
attribute list. If `Record` attribute is found:
1. Set `Flag.JavaRecord` in the class symbol's flags.
2. Parse the `Record` attribute to populate `JavaMetadata.recordComponents`.

`Flag.JavaRecord` is already defined in `Reflect.scala` at bit 15 (line 84).

### 4.3 Flags.fromJvmAccessFlags Update

File: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Flags.scala`

Phase 5b adds two entries to `fromJvmAccessFlags`:

```scala
// ADD these lines to fromJvmAccessFlags for class-level context:
if (acc & 0x0200) != 0 then bits |= Flag.Trait.bit   // ACC_INTERFACE -> Trait
// Flag.JavaRecord is NOT added here; it is set by ClassfileUnpickler when Record attribute found
```

Note: `ACC_ENUM = 0x4000` already maps to `Flag.Enum.bit` in the existing implementation (line 79).
The `ACC_INTERFACE` -> `Flag.Trait` mapping is the only addition to `fromJvmAccessFlags`.

`Flag.JavaRecord` is set separately in `ClassfileUnpickler` after scanning class attributes:

```scala
val hasRecordAttr = classAttributes.exists(_.name == "Record")
val classFlags =
    if hasRecordAttr
    then Flags.fromJvmAccessFlags(accessFlags) | new Reflect.Flags(Flag.JavaRecord.bit | Flag.JavaDefined.bit)
    else Flags.fromJvmAccessFlags(accessFlags) | new Reflect.Flags(Flag.JavaDefined.bit)
```

---

## 5. JavaAnnotation Extraction

New file: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/JavaAnnotationUnpickler.scala`

### 5.1 JVMS §4.7.16 — RuntimeVisibleAnnotations Layout

Both `RuntimeVisibleAnnotations` and `RuntimeInvisibleAnnotations` share the same internal format:

```
{attribute_name_index, attribute_length} already consumed by the attribute dispatcher

u2 num_annotations;
annotation[num_annotations]

annotation {
    u2 type_index;               // cp -> Utf8 field descriptor e.g. "Ljava/lang/Deprecated;"
    u2 num_element_value_pairs;
    { u2 element_name_index;     // cp -> Utf8 element name e.g. "value"
      element_value value;
    } element_value_pairs[num_element_value_pairs];
}
```

### 5.2 JVMS §4.7.16.1 — element_value Tags

```
element_value {
    u1 tag;
    union {
        // Primitive and String constant:
        'B' | 'C' | 'D' | 'F' | 'I' | 'J' | 'S' | 'Z' | 's' =>
            u2 const_value_index;   // cp -> CONSTANT_Integer/Long/Float/Double/Utf8

        // Enum constant:
        'e' =>
            u2 type_name_index;     // cp -> Utf8 field descriptor of the enum type
            u2 const_name_index;    // cp -> Utf8 simple name of the enum constant

        // Class literal:
        'c' =>
            u2 class_info_index;    // cp -> Utf8 class descriptor e.g. "Ljava/lang/String;"

        // Nested annotation:
        '@' =>
            annotation value;       // recursive

        // Array:
        '[' =>
            u2 num_values;
            element_value[num_values];
    }
}
```

### 5.3 Tag-to-JavaAnnotation.Value Mapping

| Tag char | JVMS meaning | JavaAnnotation.Value case |
|----------|-------------|---------------------------|
| `'B'` | byte constant (pool CONSTANT_Integer) | `IntVal(pool.integer(idx).toByte)` |
| `'C'` | char constant (pool CONSTANT_Integer) | `IntVal(pool.integer(idx))` (raw int) |
| `'D'` | double constant (pool CONSTANT_Double) | no direct case; use `StringVal` of repr or extend Value |
| `'F'` | float constant (pool CONSTANT_Float) | no direct case; map to `StringVal` or extend Value |
| `'I'` | int constant (pool CONSTANT_Integer) | `IntVal(pool.integer(idx))` |
| `'J'` | long constant (pool CONSTANT_Long) | `LongVal(pool.long_(idx))` |
| `'S'` | short constant (pool CONSTANT_Integer) | `IntVal(pool.integer(idx).toShort)` |
| `'Z'` | boolean constant (pool CONSTANT_Integer; 0=false, 1=true) | `BoolVal(pool.integer(idx) != 0)` |
| `'s'` | String constant (pool CONSTANT_Utf8) | `StringVal(pool.utf8(idx))` |
| `'e'` | enum constant | `EnumVal(resolveOrUnresolved(typeNameDesc), Name(constName))` |
| `'c'` | class literal | `ClassVal(parseTypeFromDescriptor(classDesc))` |
| `'@'` | nested annotation | `AnnotationVal(readAnnotation(...))` |
| `'['` | array | `ArrayVal(Chunk.from(readNValues(...)))` |

For `'D'` and `'F'`, the `JavaAnnotation.Value` enum does not have `DoubleVal` or `FloatVal` cases.
The implementing agent should use `StringVal(d.toString)` for these types as a pragmatic fallback,
or propose extending the Value enum with `DoubleVal` and `FloatVal` (requires DESIGN approval).
The safest path is to use `StringVal` for now — annotation double/float values are rare.

### 5.4 JavaAnnotationUnpickler API

```scala
package kyo.internal.reflect.classfile

object JavaAnnotationUnpickler:

    def readAnnotations(
        view:         ByteView,         // positioned at the start of annotation data (after attr header)
        constantPool: ConstantPool,
        interner:     Interner,
        addrMap:      Map[Int, Reflect.Symbol]  // for resolving annotation class symbols
    ): Chunk[Reflect.JavaAnnotation] < Abort[ReflectError]
```

Invoke twice per symbol: once for `RuntimeVisibleAnnotations`, once for `RuntimeInvisibleAnnotations`,
then concatenate with `Chunk.concat(visible, invisible)`.

### 5.5 Annotation Class Resolution

The `type_index` in an annotation gives a field descriptor like `"Ljava/lang/Deprecated;"`. Strip
the leading `L` and trailing `;`, replace `/` with `.` to get the dotted FQN, then look up in the
classpath. For Phase 5b (before Phase 7), produce an `Unresolved` symbol via:

```scala
Symbol.makeSymbol(
    kind   = Reflect.SymbolKind.Unresolved,
    flags  = Reflect.Flags.empty,
    name   = Reflect.Name(fqn),
    owner  = Reflect.Symbol.root,  // or the home's root sentinel
    home   = home,
    origin = Reflect.Symbol.JavaOrigin
)
```

Phase 7 replaces these with real symbols during classpath resolution.

---

## 6. EnclosingMethod Attribute Spec

### 6.1 JVMS §4.7.7 — EnclosingMethod Attribute Binary Layout

```
EnclosingMethod_attribute {
    u2 attribute_name_index;    // cp -> Utf8 "EnclosingMethod"
    u4 attribute_length;        // always 4
    u2 class_index;             // cp -> CONSTANT_Class -> binary name of the enclosing class
    u2 method_index;            // cp -> CONSTANT_NameAndType (name + descriptor)
                                // 0 if the class is enclosed in an instance initializer, static
                                // initializer, or initializer block (not a named method)
}
```

### 6.2 Lookup Protocol into the Symbol Graph

When Phase 5b encounters an `EnclosingMethod` attribute:

1. Resolve `class_index` to a binary name via `pool.classRef(class_index)`.
2. Convert to dotted FQN via `FqnCanonicalizer.toFullName(binaryName, innerClassTable)`.
3. Produce an `Unresolved` symbol for the enclosing class (Phase 7 resolves it).
4. If `method_index != 0`: resolve `pool.nameAndType(method_index)` to get `(methodName, descriptor)`.
   Create a `Reflect.Name` from `methodName`.
5. Store as `JavaMetadata.enclosingMethod = Present((enclosingClassSym, Name(methodName)))`.
6. If `method_index == 0`: `enclosingMethod = Absent`.

The JavaMetadata signature for `enclosingMethod` is `Maybe[(Symbol, Name)]` where `Symbol` is the
enclosing class and `Name` is the method name. The method descriptor is not stored (not needed for
Phase 5b purposes; method overload disambiguation is a Phase 7 concern).

---

## 7. SymbolKind Matrix Tests

### 7.1 Full Matrix (from DESIGN.md §7 lines 309-324)

| Kind | Scala (TASTy) | Java (classfile) | Notes |
|------|--------------|------------------|-------|
| Package | yes | yes | Both have packages |
| Class | yes | yes | Java `class`; Scala `class` |
| Trait | yes | yes | Java `interface` maps to Trait |
| Object | yes | NO | Scala `object` only. No Java symbol ever has kind=Object |
| Method | yes | yes | Both |
| Field | yes | yes | Scala: backing field; Java: static field |
| Val | yes | yes (from final) | Java non-static final field maps to Val |
| Var | yes | yes (from mutable) | Java non-static non-final field maps to Var |
| TypeAlias | yes | NO | Scala `type X = Y` only |
| OpaqueType | yes | NO | Scala `opaque type` only |
| AbstractType | yes | NO | Scala abstract type members only |
| TypeParam | yes | yes | Both have generics |
| Parameter | yes | yes | Method/constructor parameters |
| Unresolved | yes | yes | Soft-fail sentinel (Phase 7 creates; see §7.3) |

### 7.2 Java-to-SymbolKind Mapping Rules

```
Class-level:
    ACC_INTERFACE (0x0200) set    -> Trait + Flag.Trait
    ACC_INTERFACE clear, ACC_ENUM clear -> Class
    ACC_ENUM (0x4000) set (class) -> Class with Flag.Enum
    Record attribute present      -> Class with Flag.JavaRecord (+ Flag.Final from ACC_FINAL)

Field-level:
    ACC_STATIC (0x0008) set       -> Field
    ACC_STATIC clear, ACC_FINAL (0x0010) set -> Val
    ACC_STATIC clear, ACC_FINAL clear -> Var

Method-level:
    all methods                   -> Method

Type parameter (from Signature):
    <T:...>                       -> TypeParam

Unresolved:
    cross-file ref not in classpath -> Unresolved (Phase 7 only)
```

### 7.3 Test Enumeration (18 Tests)

Tests 1-10 go in `JavaSymbolTest.scala`; tests 11-18 go in `UnifiedModelTest.scala`.

**JavaSymbolTest (10 tests)**:

1. `sym.fullName.asString` for `java.util.Map$Entry.class` (loaded from JDK) returns
   `"java.util.Map.Entry"` (dotted, not `$`).
2. `sym.binaryName` for the same class returns `"java/util/Map$Entry"` (JVM form with slash).
3. A top-level Java class with a literal `$` in its name (synthetic fixture bytes with binary
   name `"com/example/Foo$Bar"` but NO InnerClasses entry) has `fullName.asString == "com.example.Foo$Bar"`.
4. `sym.isJava == true` for Java-sourced symbols; `sym.isJava == false` for TASTy-sourced symbols.
5. `sym.javaSpecific` is `Present` for Java symbols, `Absent` for Scala symbols.
6. `JavaMetadata.throwsTypes` is non-empty for a method declared `throws Exception` (fixture bytes
   with known Exceptions attribute; assert `throwsTypes.nonEmpty`).
7. `JavaMetadata.accessFlags` for `java.lang.String` has bit 0x0010 set (`flags.contains(Flag.Final)`).
8. A Java record class (fixture bytes with `ACC_RECORD` flag + `Record` attribute) produces
   `Flag.JavaRecord` in `flags` and non-empty `recordComponents` in `javaSpecific`.
9. `JavaMetadata.annotations` for a class annotated with a runtime-visible annotation contains a
   `JavaAnnotation` with the correct annotation class name (use `@java.lang.Deprecated` which is
   `RetentionPolicy.RUNTIME` and available in any JDK).
10. `JavaMetadata.enclosingMethod` is `Present` for an anonymous class (fixture bytes); assert
    `enclosingMethod.get._2.asString == "enclosingMethodFixture"`.

**UnifiedModelTest (8 tests)**:

11. `SymbolKind.Package` appears for both Java and Scala package symbols (use fixture classpath
    with both a `.tasty` and a `.class` file under the same package).
12. `SymbolKind.Class` appears for Java `class` and Scala `class`.
13. `SymbolKind.Trait` appears for Java `interface` and Scala `trait`.
14. `SymbolKind.Object` appears ONLY for Scala `object`; no Java symbol has `kind == Object`.
15. `SymbolKind.TypeAlias`, `OpaqueType`, `AbstractType` appear only for TASTy-sourced symbols
    (assert no Java symbol from the fixture classpath has any of these kinds).
16. `Type.Array(elem)` is returned for both Java `int[]` (from classfile) and Scala `Array[Int]`
    (from TASTy), both reaching `TypeOps.mkArray`.
17. A Scala `case class` decoded from TASTy has `flags.contains(Flag.Case)`.
18. Full SymbolKind matrix coverage: for each of the 13 SymbolKind cases (all except `Unresolved`),
    there exists at least one symbol in the fixture/JDK classpath with that kind. `SymbolKind.Unresolved`
    is deferred to Phase 7's `SymbolResolutionTest` t21 (synthesized partial-classpath fixture).
    Phase 5b does NOT construct Unresolved symbols itself; it uses Unresolved only as placeholders
    for cross-classpath references (annotation classes, enclosing classes).

### 7.4 SymbolKind Coverage Plan for Test 18

| Kind | Where to find |
|------|--------------|
| Package | Java: `java.lang` package; Scala: any TASTy file's package |
| Class | Java: `java.lang.Object`; Scala: `PlainClass.tasty` fixture |
| Trait | Java: `java.lang.Runnable.class`; Scala: needs a `.tasty` trait fixture |
| Object | Scala only: needs a `.tasty` object fixture |
| Method | Java: any method on `Object`; Scala: any method in TASTy |
| Field | Java: `java.lang.System.out` (static field); Scala: backing field |
| Val | Java: `java.lang.Integer.MAX_VALUE` (static final) or non-static final; Scala: val |
| Var | Java: any mutable non-final instance field; Scala: var |
| TypeAlias | Scala only: needs a `.tasty` file with `type X = Y` |
| OpaqueType | Scala only: needs a `.tasty` file with `opaque type` |
| AbstractType | Scala only: needs a `.tasty` file with `type X` (abstract) |
| TypeParam | Java: `java.util.ArrayList`'s `<E>` param; Scala: any generic class |
| Parameter | Java: any method parameter; Scala: any method param |

If existing fixtures are insufficient, add pre-compiled `.tasty` bytes for Trait, Object, TypeAlias,
OpaqueType, AbstractType under `kyo-reflect/shared/src/test/resources/kyo/fixtures/`.
Currently only `PlainClass.tasty` exists in that directory.

---

## 8. File and Line Anchors

All paths are absolute to the worktree:
`/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/`

### 8.1 Files Phase 5b Creates (new files)

```
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/FqnCanonicalizer.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/JavaAnnotationUnpickler.scala
kyo-reflect/shared/src/test/scala/kyo/JavaSymbolTest.scala
kyo-reflect/shared/src/test/scala/kyo/UnifiedModelTest.scala
```

### 8.2 Files Phase 5b Modifies

| File | Lines of interest | Change |
|------|-------------------|--------|
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ClassfileUnpickler.scala` | (Phase 5 deliverable, all lines) | Wire `FqnCanonicalizer.toFullName` for `sym.fullName`; wire `JavaAnnotationUnpickler.readAnnotations`; add `Record` attribute parsing; set `Flag.JavaRecord` when `Record` attribute present |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Flags.scala` | 71-82 | Add `if (acc & 0x0200) != 0 then bits |= Flag.Trait.bit` for `ACC_INTERFACE`; do NOT add `ACC_RECORD` (use attribute presence) |

### 8.3 Files Phase 5b Reads (but does not modify)

| File | Key lines |
|------|-----------|
| `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 61-113 Flags/Flag; 117-121 SymbolKind; 142-162 JavaMetadata/JavaAnnotation; 202-297 Symbol |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Symbol.scala` | 17-26 makeSymbol |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Flags.scala` | 71-82 fromJvmAccessFlags |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeOps.scala` | 55 mkArray |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/TypeArena.scala` | 17-103 TypeArena |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala` | 12-44 trait; use only readByte() for classfile u1/u2/u4 |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Interner.scala` | 27 intern() |
| `kyo-reflect/shared/src/main/scala/kyo/ReflectError.scala` | full enum |
| Classfile reader (Phase 5 deliverable) | `ClassfileUnpickler.scala`, `ConstantPool.scala`, `JavaSignatures.scala`, `ClassfileFormat.scala` |
| `kyo-reflect/shared/src/test/resources/kyo/fixtures/PlainClass.tasty` | existing TASTy fixture |

---

## 9. Edge Cases and Gotchas

### 9.1 Java `interface` maps to `SymbolKind.Trait`

The JVMS gives Java interfaces the `ACC_INTERFACE` flag (0x0200). kyo-reflect models them as
`SymbolKind.Trait` per the matrix in DESIGN.md §7. This includes annotation types (which also
have `ACC_ANNOTATION = 0x2000`). An annotation type is both an interface and an annotation; model
it as `Trait` with the additional `ACC_ANNOTATION` bit preserved in `JavaMetadata.accessFlags`.

### 9.2 InnerClasses Attribute May Be Absent

A classfile may lack the `InnerClasses` attribute entirely (pre-Java-1.1 classes, or classes with
no nested types). In this case `innerClassTable` is empty, and `FqnCanonicalizer.toFullName`
falls into the top-level branch, producing FQN = binary name with `/` -> `.`. If the binary name
contains `$`, those `$` characters appear literally in the FQN — this is correct behavior for
classes that genuinely have `$` in their name (e.g., Scala companion objects compiled as
`Foo$.class`).

Also: a class may be referenced in ANOTHER class's `InnerClasses` attribute without its own
attribute. Always use the innerClassTable from the class being processed (the one whose `read()`
call we're in), not a global table. Phase 7 merges tables across files.

### 9.3 Anonymous Classes

An anonymous class entry has:
- `inner_class_info_index != 0` (points to the anonymous class)
- `outer_class_info_index == 0`
- `inner_name_index == 0`

Store in innerClassTable as `(innerBinaryName -> ("", ""))`. `FqnCanonicalizer` detects the
`outerBinaryName == ""` branch and preserves the `$`-form FQN.

### 9.4 Java Synthetic Methods (Bridge, Lambda)

Methods with `ACC_SYNTHETIC = 0x1000` and/or `ACC_BRIDGE = 0x0040` are synthetic. They are
present in `ClassfileResult.symbols` with `Flag.Synthetic` set. Callers filter via
`flags.contains(Flag.Synthetic)`. Phase 5b does not skip them during parsing (must read to
keep `view` cursor synchronized with the classfile layout).

### 9.5 ACC_RECORD vs ACC_FINAL Collision

At class level, `ACC_RECORD = 0x0010` has the same bit value as `ACC_FINAL = 0x0010`. Java records
are always final, so both "flags" are semantically set. Do NOT check the bit. Check for the
presence of the `Record` attribute by name. Only set `Flag.JavaRecord` when the attribute is found.

### 9.6 ConstantPool Null Reference for `outer_class_info_index = 0`

When `outer_class_info_index == 0` in InnerClasses, it does NOT refer to pool index 0. Index 0
is always unused in the constant pool (JVMS: pool is 1-indexed). The value 0 is the sentinel
meaning "absent". Check for 0 before calling `pool.classRef(0)` — that call would throw a
format error.

### 9.7 ACC_INTERFACE Also Implies ACC_ABSTRACT

Interfaces always have `ACC_ABSTRACT = 0x0400` set. When setting `SymbolKind.Trait` from
`ACC_INTERFACE`, also set `Flag.Abstract` (since `fromJvmAccessFlags` maps 0x0400 to `Flag.Abstract`).
No special handling needed — it falls out naturally from `fromJvmAccessFlags`.

### 9.8 Java Enum Class vs Enum Constant

A Java `enum` class has `ACC_ENUM` at the class level and `SymbolKind.Class` with `Flag.Enum`.
Each enum constant is a static field with `ACC_STATIC | ACC_ENUM | ACC_FINAL` at the field level,
mapping to `SymbolKind.Field` with `Flag.Enum | Flag.JavaDefined | Flag.Final`. Do not confuse
enum-class-level `ACC_ENUM` with enum-constant-field-level `ACC_ENUM`.

### 9.9 `computeBinaryName` in Reflect.scala Uses Dots, Not Slashes

The current `Symbol.computeBinaryName` (Reflect.scala line 250) walks the owner chain and joins
with `.` — it does NOT produce JVM binary form with `/`. This is a known discrepancy; DESIGN.md
states `binaryName` returns `"java/util/Map$Entry"` but the current implementation would return
`"java.util.Map.Entry"` (same as `fullName`).

Phase 5b must fix `computeBinaryName` to produce the correct JVM binary form. Two approaches:
(a) Store the original binary name on Java symbols as metadata and return it directly.
(b) Walk the owner chain, use `/` between package segments and `$` between class segments.

Approach (a) is simpler and more reliable. Add a `binaryNameOverride: String` field to the
Java-specific path, or store it in a companion `mutable.HashMap[Symbol, String]` (keyed by
identity) in the classfile loader. Either approach is acceptable; the agent must pick one and
apply it consistently.

### 9.10 Cross-Platform: No JVM-Specific I/O

All new Phase 5b files are in `shared/` and must compile and pass (or compile only for
platform-restricted tests) on JVM, JS, and Native. Prohibited APIs: `java.io.*`, `java.nio.file.*`,
`java.lang.reflect.*`. Permitted: `Array[Byte]`, `ByteView`, `Interner`, standard Scala collections,
`java.util.concurrent.atomic.AtomicReference`.

---

## 10. Test-Data Suggestions

### 10.1 Required Java Fixture Classes

Phase 5b needs pre-compiled `.class` files for tests 3, 6, 8, and 10. These cannot be loaded via
`getResourceAsStream` from the JDK (they don't exist there). Compile them at build time or include
pre-baked bytes.

**Recommended fixture sources** (Java source, compile with `javac --release 17` minimum):

```java
// PlainJavaClass.java — top-level with literal $ in name won't work; use Foo.java + Foo$Bar.java
// OR synthesize a classfile byte array in the test itself (simplest for cross-platform)

// ThrowsFixture.java
public class ThrowsFixture {
    public void method() throws Exception {}
}

// PointRecord.java (Java 16+)
public record PointRecord(int x, int y) {}

// AnonymousFixture.java
public class AnonymousFixture {
    public Runnable enclosingMethodFixture() {
        return new Runnable() {
            public void run() {}
        };
    }
}
```

### 10.2 Fixture Compilation and Storage

Compile to:
```
kyo-reflect/shared/src/test/resources/kyo/fixtures/ThrowsFixture.class
kyo-reflect/shared/src/test/resources/kyo/fixtures/PointRecord.class
kyo-reflect/shared/src/test/resources/kyo/fixtures/AnonymousFixture.class
kyo-reflect/shared/src/test/resources/kyo/fixtures/AnonymousFixture$1.class  // the anonymous class
```

Or embed as inline `val bytes: Array[Byte] = Array(...)` in a companion test object. The inline
approach is more portable for cross-platform since `getClass.getResourceAsStream` is JVM-only.

### 10.3 JDK Classes for Tests 1, 2, 7, 9

Use `getClass.getClassLoader.getResourceAsStream("java/util/Map.class")` for the InnerClasses
test. For `java.lang.Deprecated` annotation test (test 9), `java.lang.Deprecated` is annotated
with `@Documented` and `@Retention(RetentionPolicy.RUNTIME)` — load `java.lang.Deprecated.class`
and check that `annotations` contains an entry with annotation class name `"java.lang.annotation.Retention"`.

### 10.4 Cross-Platform: Pre-baked Bytes

For the cross-platform compile verification, the fixture loading inside `JavaSymbolTest` must be
guarded. Pattern:

```scala
// In JavaSymbolTest.scala (shared/):
import scala.scalanative.meta.LinktimeInfo  // or use a platform-independent guard
// Platform guard via a shared abstract val, or use:
val isJvm = System.getProperty("java.vm.name") != null

test("sym.fullName for inner class") {
    assume(isJvm, "JDK classfile loading requires JVM")
    // ...
}
```

Tests that use only fixture `.class` bytes embedded as `Array[Byte]` literals can run on all
platforms without the `assume` guard.

### 10.5 TASTy Fixtures for UnifiedModelTest

To cover `SymbolKind.Trait`, `Object`, `TypeAlias`, `OpaqueType`, `AbstractType` from Scala in
test 18, add additional `.tasty` fixture files. The existing `PlainClass.tasty` covers `Class`.
Needed:
- `PlainTrait.tasty` — a Scala `trait PlainTrait`
- `PlainObject.tasty` — a Scala `object PlainObject`
- `PlainAlias.tasty` — a Scala file with `type PlainAlias = String`
- `PlainOpaque.tasty` — a Scala file with `opaque type PlainOpaque = Int`
- `PlainAbstractType.tasty` — a Scala file with `abstract class PlainAbstractContainer { type T }`

Compile these as part of the Phase 5b fixture setup and check in the `.tasty` bytes to the
fixtures directory.

---

## 11. Anti-Flakiness Deltas

### 11.1 Pure Byte Arithmetic

`FqnCanonicalizer` is a pure `Map` lookup + string operation. No I/O, no concurrency.
`JavaAnnotationUnpickler` is a sequential byte-scan over a fixed `ByteView` slice. Both are
deterministic given the same bytes.

### 11.2 Fixed Pre-Compiled Fixture Bytes

Fixture `.class` files are checked in as binary resources. They never change between runs.
No `javac` invocation at test time. Tests are hermetic.

### 11.3 JVM-Only Tests

Tests that call `getClass.getClassLoader.getResourceAsStream` are JVM-only. Guard with
`assume(isJvm, ...)`. The Phase 5b verification command requires:
```
sbt 'project kyo-reflect; testOnly kyo.JavaSymbolTest kyo.UnifiedModelTest'
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```
This means tests must compile on JS and Native but may have runtime `assume` guards.

### 11.4 Constant Pool Bounds Validation

All new pool accesses in `JavaAnnotationUnpickler` must validate `idx >= 1 && idx < entries.length`
before dereferencing, producing `Abort.fail(ReflectError.ClassfileFormatError(...))` on invalid
index (not `ArrayIndexOutOfBoundsException`).

### 11.5 Recursive Annotation Safety

Nested annotation (`'@'` tag) calls `readAnnotation` recursively. Java does not allow circular
annotation definitions at the language level, but a corrupt classfile could. Guard with a depth
limit (e.g., `maxDepth = 8`). If exceeded, produce `ClassfileFormatError`.

---

## 12. Concerns

### 12.1 binaryName Discrepancy in computeBinaryName

`Symbol.computeBinaryName` (Reflect.scala line 250) currently joins owner-chain names with `.`,
NOT with `/` + `$`. For Java inner-class symbols the method will produce `"java.util.Map.Entry"`,
not `"java/util/Map$Entry"`. This is a bug for Phase 5b's contract (DESIGN.md §11 says
`sym.binaryName` returns `"java/util/Map$Entry"`).

Phase 5b must fix this. The cleanest fix: during `ClassfileUnpickler.read`, store the original
binary name (with `/` separators) in a side-channel, and override `binaryName` for Java symbols.
Since `Symbol` is a `final class` with a fixed set of fields, the override must be done either by:
(a) Adding a `binaryNameCache: String` to `Symbol` (requires modifying `Reflect.scala`),
(b) Using a WeakHashMap companion or thread-local in the classfile reader,
(c) Recomputing from the owner chain with correct separators (requires knowing which owners are
    packages vs classes, which is derivable from `SymbolKind`).

Approach (c) is cleanest: in `computeBinaryName`, use `.` between package-to-package transitions,
`/` between packages, and `$` between class-to-class transitions. Then replace top-level `.` with
`/`. This requires distinguishing `Package` symbols from `Class/Trait` symbols, which is available
via `kind`. This approach requires no data model changes.

Present this concern to the supervisor before modifying `Reflect.scala`. The plan (execution-plan.md
line 419-420) states this as a required behavior, so it must be fixed; the question is which approach.

### 12.2 JavaAnnotation.Value Missing Float/Double Cases

`JavaAnnotation.Value` (Reflect.scala line 152-160) has `StringVal`, `IntVal`, `LongVal`,
`BoolVal`, `ClassVal`, `EnumVal`, `ArrayVal`, `AnnotationVal` — but no `FloatVal` or `DoubleVal`.
JVMS element_value tags include `'D'` (double) and `'F'` (float). Phase 5b must either:
(a) Extend `JavaAnnotation.Value` with `FloatVal(f: Float)` and `DoubleVal(d: Double)` (requires
    modifying `Reflect.scala` which is a public API change),
(b) Map float/double to `StringVal(value.toString)` as a lossy but compiling fallback.

Option (a) is correct but requires supervisor approval. Option (b) is safe for now (float/double
annotation values are rare in practice: `@SomeAnnotation(weight=3.14)` is unusual).

Recommendation: implement (b) as a temporary measure and file a DESIGN concern in a comment for
Phase 7 to address.

### 12.3 Phase 5 Classfile Files Not Yet on Disk

At Phase 5b start, the files `ClassfileUnpickler.scala`, `ConstantPool.scala`,
`JavaSignatures.scala`, and `ClassfileFormat.scala` do not exist in the worktree (Phase 5 is not
yet committed). Phase 5b DEPENDS ON Phase 5 being fully committed first. The implementing agent
must verify Phase 5 files exist before starting. If Phase 5 is incomplete, block and report.

### 12.4 addrMap Parameter Naming Confusion

`JavaSignatures.parseFieldSignature` takes `addrMap: Map[Int, Reflect.Symbol]` but in the classfile
context this parameter is not meaningful (classfiles don't have TASTy addresses). Phase 5b may need
to pass an empty map or a map of `0 -> someSym` as a dummy. Check the Phase 5 implementation to
see what it actually uses the map for; if it only uses it for type-param scope, the parameter name
is misleading but functionally harmless for Phase 5b's annotation parsing use case.

### 12.5 Symbol.root Accessibility

`Reflect.Symbol.root` is referenced in §5.5 for constructing Unresolved symbols. This sentinel
must exist and be accessible. Check Reflect.scala for its definition. If it is not defined,
Phase 5b must use a different sentinel (e.g., the class symbol's owner chain root).

```
grep -n "root\|sentinel" kyo-reflect/shared/src/main/scala/kyo/Reflect.scala
```

VERIFIED: `Symbol.root` does NOT exist in Reflect.scala (grep confirms no `root` val or method in
`object Symbol`). Phase 5b must use the current class's owner chain root, or the classfile reader's
own sentinel. The AstUnpickler pattern uses a `rootSym` constructed at the start of `read()`:

```scala
val rootSym = Symbol.makeSymbol(
    kind   = Reflect.SymbolKind.Package,
    flags  = Reflect.Flags.empty,
    name   = Reflect.Name(""),
    owner  = null,   // root owns itself sentinel
    home   = home,
    origin = Reflect.Symbol.JavaOrigin
)
```

For Unresolved symbols in Phase 5b, use `null` owner (matching the sentinel pattern in
`computeFullName`: `while (cur ne null) && (cur.owner ne cur)`). Or pass the home's package root.
Check `AstUnpickler.scala` for the pattern used in Phase 3.
