# Phase 5 Prep: Classfile Reader

Preparation document for the implementing agent. Read every section before writing a single line of
code. This document is the authoritative reference for Phase 5; do not invent alternatives to the
patterns specified here.

---

## 1. Verbatim API Signatures from Phase 1-4 Code

Phase 5 calls into code committed by earlier phases. All signatures are verified from disk.

### 1.1 ByteView (`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala`)

```scala
sealed trait ByteView:
    def peekByte(at: Int): Byte          // read at absolute position, no cursor advance
    def readByte(): Byte                 // read at cursor, advance cursor by 1
    def readNat(): Int                   // delegates to Varint.readNat (TASTy LEB128)
    def readInt(): Int                   // delegates to Varint.readInt
    def readLongNat(): Long              // delegates to Varint.readLongNat
    def readEnd(): Int                   // reads Nat as payload length, returns cursor+len
    def subView(from: Int, until: Int): ByteView  // zero-copy slice, cursor reset to `from`
    def goto(addr: Int): Unit            // move cursor to absolute position
    def remaining: Int                   // bytes from cursor to end
    def position: Int                    // current cursor position

object ByteView:
    def apply(bytes: Array[Byte]): ByteView.Heap
    def apply(bytes: Array[Byte], start: Int, end: Int): ByteView.Heap

    final class Heap(val bytes: Array[Byte], val start: Int, val end: Int) extends ByteView
    sealed abstract class Mapped extends ByteView  // stub; wired in Phase 7
```

IMPORTANT: ByteView uses TASTy LEB128 (big-endian, stop-bit on highest bit SET). That is NOT the
encoding used in classfiles. Phase 5 must NOT call `readNat()`/`readInt()`/`readLongNat()` from
ByteView for classfile data. Classfiles use big-endian fixed-width integers. Phase 5 reads them
directly via `readByte()` composed as shown in Section 2.

Reading u1/u2/u4/u8 from a ByteView in Phase 5:
```scala
def readU1(view: ByteView): Int = view.readByte() & 0xff
def readU2(view: ByteView): Int = (readU1(view) << 8) | readU1(view)
def readU4(view: ByteView): Int = (readU1(view) << 24) | (readU1(view) << 16) | (readU1(view) << 8) | readU1(view)
def readU8(view: ByteView): Long = (readU4(view).toLong << 32) | (readU4(view).toLong & 0xffffffffL)
```

### 1.2 Interner (`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Interner.scala`)

```scala
final class Interner(numShards: Int = 32):
    def intern(bytes: Array[Byte], offset: Int, length: Int): Interner.Entry

object Interner:
    final class Entry(
        val hash: Int,
        val bytes: Array[Byte],
        val offset: Int,
        val length: Int,
        val string: Memo[String]   // lazy UTF-8 decode, cached after first call
    )
```

To create a `Reflect.Name` from an `Interner.Entry`:
```scala
Reflect.Name.wrap(entry)   // private[kyo], accessible within kyo package
```

### 1.3 Reflect.Type ADT (`kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, lines 166-198)

The cases Phase 5 produces directly:

```scala
enum Type:
    case Named(symbol: Symbol)                        // resolved class/interface ref
    case Applied(base: Type, args: Chunk[Type])       // parameterized type
    case Array(elem: Type)                            // Java T[] and Scala Array[T]
    case Wildcard(lo: Type, hi: Type)                 // Java wildcard; field order: lo then hi
    case Function(params: Chunk[Type], result: Type, isContext: Boolean)  // used if needed
    case TypeLambda(params: Chunk[Symbol], body: Type)
    // ... others are Scala-only; Phase 5 will not produce them for Java symbols
```

Wildcard mapping (per DESIGN.md §9 and execution-plan.md line 344):
- `+T` (covariant upper-bounded): `Wildcard(lo = Named(nothingSym), hi = T)`
- `-T` (contravariant lower-bounded): `Wildcard(lo = T, hi = Named(objectSym))`
- `*` (unbounded): `Wildcard(lo = Named(nothingSym), hi = Named(objectSym))`

### 1.4 Reflect.Symbol and Symbol.make (`kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, lines 202-297)

```scala
final class Symbol private[Reflect] (
    val kind:   SymbolKind,
    val flags:  Flags,
    val name:   Name,
    val owner:  Symbol,
    private[Reflect] val home: ClasspathRef,
    private[kyo] val origin: Symbol.Origin
)

object Symbol:
    private[kyo] def make(
        kind:   SymbolKind,
        flags:  Flags,
        name:   Name,
        owner:  Symbol,
        home:   ClasspathRef,
        origin: Origin
    ): Symbol

    sealed trait Origin derives CanEqual
    final case class TastyOrigin(addrMap: Map[Int, Reflect.Symbol], bodyStart: Int, bodyEnd: Int) extends Origin
    case object JavaOrigin extends Origin   // Phase 5 uses this for all Java symbols
```

The internal factory in `kyo.internal.reflect.symbol.Symbol`:
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

Phase 5 uses `Reflect.Symbol.JavaOrigin` as the `origin` for every symbol it creates.

### 1.5 Flags.fromJvmAccessFlags (`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Flags.scala`, lines 71-83)

```scala
def fromJvmAccessFlags(acc: Int): Reflect.Flags =
    var bits = 0L
    if (acc & 0x0001) != 0 then bits |= Flag.Public.bit
    if (acc & 0x0002) != 0 then bits |= Flag.Private.bit
    if (acc & 0x0004) != 0 then bits |= Flag.Protected.bit
    if (acc & 0x0010) != 0 then bits |= Flag.Final.bit
    if (acc & 0x0200) != 0 then bits |= Flag.Abstract.bit
    if (acc & 0x1000) != 0 then bits |= Flag.Synthetic.bit
    if (acc & 0x4000) != 0 then bits |= Flag.Enum.bit
    if (acc & 0x0008) != 0 then bits |= Flag.JavaDefined.bit // ACC_STATIC
    new Reflect.Flags(bits)
```

Phase 5 calls `Flags.fromJvmAccessFlags` for class-level and member-level access flags.
Phase 5 also needs to OR in `Flag.JavaDefined.bit` always (every Java symbol is Java-defined),
and check the class-level flags for additional bits like `ACC_INTERFACE` and `ACC_RECORD`.

### 1.6 SymbolKind (`kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, lines 117-121)

```scala
enum SymbolKind derives CanEqual:
    case Package, Class, Trait, Object, Method, Field, Val, Var,
        TypeAlias, OpaqueType, AbstractType, TypeParam, Parameter,
        Unresolved
```

Java mapping (from DESIGN.md §7, Table "Scala / Java SymbolKind matrix"):
- `ACC_INTERFACE` set: `Trait`
- `ACC_INTERFACE` clear, `ACC_ENUM` clear: `Class`
- `ACC_ENUM` set (class-level): `Class` with `Flag.Enum`
- Method (in method_info table): `Method`
- Field, `ACC_STATIC` set: `Field` (with `Flag.JavaDefined` already included)
- Field, `ACC_STATIC` clear, `ACC_FINAL` set: `Val`
- Field, `ACC_STATIC` clear, `ACC_FINAL` clear: `Var`
- Type parameter: `TypeParam`

### 1.7 JavaMetadata (`kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`, lines 143-148)

```scala
final case class JavaMetadata(
    throwsTypes:      Chunk[Type],
    annotations:      Chunk[JavaAnnotation],
    enclosingMethod:  Maybe[(Symbol, Name)],
    accessFlags:      Int,
    recordComponents: Chunk[(Name, Type)]
)
```

### 1.8 ReflectError (`kyo-reflect/shared/src/main/scala/kyo/ReflectError.scala`)

```scala
enum ReflectError:
    case ClassfileFormatError(path: String, reason: String)
    case SymbolNotFound(fqn: String)
    case NotImplemented(feature: String)
    // ... others
```

Phase 5 uses `ClassfileFormatError` for magic mismatch, corrupt constant pool, bad signature, etc.

---

## 2. JVM Classfile Binary Format Reference

Source: JVM Specification (JVMS) Chapter 4, "The class File Format". All byte offsets and layouts
are verbatim from the spec.

### 2.1 Magic and Version

```
ClassFile {
    u4  magic;             // 0xCAFEBABE (big-endian). Read as readU4(); must equal 0xcafebabe.
    u2  minor_version;     // e.g. 0 for Java 8+
    u2  major_version;     // 52 = Java 8, 61 = Java 17, 65 = Java 21, 69 = Java 25
}
```

Minimum acceptable version: major=45, minor=3 (Java 1.1) per tasty-query precedent.
Phase 5 accepts anything >= 45.3. Newer bytecodes are always backward-compatible for our purposes.

### 2.2 Constant Pool

Immediately after the version:

```
u2 constant_pool_count;   // number of entries + 1 (pool is 1-indexed; slot 0 unused)
cp_info constant_pool[constant_pool_count - 1];   // indices 1..count-1
```

Each `cp_info` entry:
```
cp_info {
    u1 tag;
    u1 info[];   // variable length, depends on tag
}
```

Tag values and layouts (JVMS §4.4):

| Tag | Name | Layout after tag byte |
|-----|------|-----------------------|
| 1 | CONSTANT_Utf8 | u2 length; u1 bytes[length] |
| 3 | CONSTANT_Integer | u4 bytes (big-endian signed) |
| 4 | CONSTANT_Float | u4 bytes (IEEE 754 float) |
| 5 | CONSTANT_Long | u4 high_bytes; u4 low_bytes |
| 6 | CONSTANT_Double | u4 high_bytes; u4 low_bytes |
| 7 | CONSTANT_Class | u2 name_index (-> Utf8, binary name with '/' separators) |
| 8 | CONSTANT_String | u2 string_index (-> Utf8) |
| 9 | CONSTANT_Fieldref | u2 class_index; u2 name_and_type_index |
| 10 | CONSTANT_Methodref | u2 class_index; u2 name_and_type_index |
| 11 | CONSTANT_InterfaceMethodref | u2 class_index; u2 name_and_type_index |
| 12 | CONSTANT_NameAndType | u2 name_index; u2 descriptor_index |
| 15 | CONSTANT_MethodHandle | u1 reference_kind; u2 reference_index |
| 16 | CONSTANT_MethodType | u2 descriptor_index |
| 17 | CONSTANT_Dynamic | u2 bootstrap_method_attr_index; u2 name_and_type_index |
| 18 | CONSTANT_InvokeDynamic | u2 bootstrap_method_attr_index; u2 name_and_type_index |
| 19 | CONSTANT_Module | u2 name_index |
| 20 | CONSTANT_Package | u2 name_index |

CRITICAL GOTCHA: Long (tag 5) and Double (tag 6) entries occupy TWO slots in the pool array. After
reading a Long or Double, advance the index by 2, not 1. Slot `idx+1` is invalid and must never be
referenced. Classic source of off-by-one bugs in classfile readers.

### 2.3 Access Flags (class-level, JVMS §4.1 Table 4.1-B)

```
u2 access_flags;
```

| Bit value | Name | Meaning |
|-----------|------|---------|
| 0x0001 | ACC_PUBLIC | Declared public |
| 0x0010 | ACC_FINAL | Declared final |
| 0x0020 | ACC_SUPER | Treat superclass methods specially (always set in modern code) |
| 0x0200 | ACC_INTERFACE | Is an interface |
| 0x0400 | ACC_ABSTRACT | Declared abstract |
| 0x1000 | ACC_SYNTHETIC | Synthetic |
| 0x2000 | ACC_ANNOTATION | Is an annotation type |
| 0x4000 | ACC_ENUM | Is an enum |
| 0x0010 | ACC_RECORD | Is a record (Java 14+; NOTE: same bit 0x10 as ACC_FINAL? No — JVMS 4.1 Table 4.1-B for Java 16+: ACC_RECORD = 0x0010 is NOT the same as ACC_FINAL) |

Correction: JVMS Java 16+ Table 4.1-B:
- ACC_RECORD = 0x0010 conflicts with ACC_FINAL = 0x0010 in class context.
- Per the spec (JVMS 4.1), for classes with ACC_RECORD set, ACC_FINAL is also set. Distinguish via
  the presence of the `Record` attribute, not the flag alone.
- Recommended: check for the `Record` attribute by name. If present, set `Flag.JavaRecord`.

Field-level access flags (JVMS §4.5 Table 4.5-A):

| Bit value | Name | Meaning |
|-----------|------|---------|
| 0x0001 | ACC_PUBLIC | |
| 0x0002 | ACC_PRIVATE | |
| 0x0004 | ACC_PROTECTED | |
| 0x0008 | ACC_STATIC | |
| 0x0010 | ACC_FINAL | |
| 0x0040 | ACC_VOLATILE | |
| 0x0080 | ACC_TRANSIENT | |
| 0x1000 | ACC_SYNTHETIC | |
| 0x4000 | ACC_ENUM | |

Method-level access flags (JVMS §4.6 Table 4.6-A):

| Bit value | Name | Meaning |
|-----------|------|---------|
| 0x0001 | ACC_PUBLIC | |
| 0x0002 | ACC_PRIVATE | |
| 0x0004 | ACC_PROTECTED | |
| 0x0008 | ACC_STATIC | |
| 0x0010 | ACC_FINAL | |
| 0x0020 | ACC_SYNCHRONIZED | |
| 0x0040 | ACC_BRIDGE | Synthetic bridge |
| 0x0080 | ACC_VARARGS | Varargs |
| 0x0100 | ACC_NATIVE | |
| 0x0400 | ACC_ABSTRACT | |
| 0x0800 | ACC_STRICT | Floating-point mode (deprecated in Java 17) |
| 0x1000 | ACC_SYNTHETIC | |

### 2.4 ClassFile Structural Layout After Version

```
u2    constant_pool_count
cp_info constant_pool[constant_pool_count - 1]
u2    access_flags
u2    this_class              // cp index -> CONSTANT_Class -> Utf8 (binary name of this class)
u2    super_class             // cp index -> CONSTANT_Class, or 0 for java/lang/Object
u2    interfaces_count
u2    interfaces[interfaces_count]   // cp indices -> CONSTANT_Class
u2    fields_count
field_info fields[fields_count]
u2    methods_count
method_info methods[methods_count]
u2    attributes_count
attribute_info attributes[attributes_count]
```

### 2.5 field_info and method_info

Both have identical structure:
```
field_info / method_info {
    u2  access_flags
    u2  name_index         // cp index -> Utf8
    u2  descriptor_index   // cp index -> Utf8 (field or method descriptor)
    u2  attributes_count
    attribute_info attributes[attributes_count]
}
```

### 2.6 attribute_info (generic wrapper)

```
attribute_info {
    u2  attribute_name_index   // cp index -> Utf8
    u4  attribute_length       // byte length of info[] that follows
    u1  info[attribute_length]
}
```

To skip an unknown attribute: read `u2` name index, read `u4` length, skip `length` bytes.

### 2.7 Attributes Phase 5 Must Decode

**Signature attribute** (JVMS §4.7.9):
```
Signature_attribute {
    u2 attribute_name_index;   // "Signature"
    u4 attribute_length;       // always 2
    u2 signature_index;        // cp index -> Utf8 containing the generic signature string
}
```

**InnerClasses attribute** (JVMS §4.7.6):
```
InnerClasses_attribute {
    u2 attribute_name_index;
    u4 attribute_length;
    u2 number_of_classes;
    {  u2 inner_class_info_index;   // cp index -> CONSTANT_Class (or 0 if anonymous)
       u2 outer_class_info_index;   // cp index -> CONSTANT_Class (or 0 if local/anonymous)
       u2 inner_name_index;         // cp index -> Utf8 simple name (or 0 if anonymous)
       u2 inner_class_access_flags;
    } classes[number_of_classes];
}
```

**EnclosingMethod attribute** (JVMS §4.7.7):
```
EnclosingMethod_attribute {
    u2 attribute_name_index;
    u4 attribute_length;       // always 4
    u2 class_index;            // cp index -> CONSTANT_Class
    u2 method_index;           // cp index -> CONSTANT_NameAndType (or 0 if not enclosed in a method)
}
```

**Record attribute** (JVMS §4.7.30, Java 16+):
```
Record_attribute {
    u2 attribute_name_index;
    u4 attribute_length;
    u2 components_count;
    {  u2 name_index;        // cp index -> Utf8
       u2 descriptor_index;  // cp index -> Utf8
       u2 attributes_count;
       attribute_info attributes[attributes_count];   // may include Signature
    } components[components_count];
}
```

**RuntimeVisibleAnnotations / RuntimeInvisibleAnnotations** (JVMS §4.7.16, §4.7.17):
```
RuntimeVisibleAnnotations_attribute {
    u2 attribute_name_index;
    u4 attribute_length;
    u2 num_annotations;
    annotation annotations[num_annotations];
}

annotation {
    u2 type_index;               // cp index -> Utf8 (descriptor "Ljava/lang/annotation/Retention;")
    u2 num_element_value_pairs;
    { u2 element_name_index;     // cp index -> Utf8
      element_value value;
    } element_value_pairs[num_element_value_pairs];
}

element_value {
    u1 tag;    // 'B','C','D','F','I','J','S','Z','s','e','c','@','['
    union {
        // for B,C,D,F,I,J,S,Z,s: u2 const_value_index -> cp
        // for 'e': u2 type_name_index, u2 const_name_index
        // for 'c': u2 class_info_index -> cp (Utf8 descriptor)
        // for '@': annotation value
        // for '[': u2 num_values; element_value[num_values]
    }
}
```

**Exceptions attribute** (JVMS §4.7.5) — source of `throwsTypes` in `JavaMetadata`:
```
Exceptions_attribute {
    u2 attribute_name_index;
    u4 attribute_length;
    u2 number_of_exceptions;
    u2 exception_index_table[number_of_exceptions];  // each -> CONSTANT_Class
}
```

**Code attribute** (JVMS §4.7.3): Phase 5 SKIPS this attribute entirely. Read `u4` length, skip
`length` bytes. Never decode method bodies.

---

## 3. Generic Signature Attribute Grammar

Source: JVMS §4.7.9.1. This is the most complex part of Phase 5.

The Signature attribute holds a string produced by `javac` for any class, method, or field that has
generic type parameters, parameterized types, type variables, or wildcard bounds.

### 3.1 Grammar (JVMS §4.7.9.1)

```
ClassSignature:
    [TypeParameters] SuperclassSignature {SuperinterfaceSignature}

TypeParameters:
    < TypeParameter {TypeParameter} >

TypeParameter:
    Identifier ClassBound {InterfaceBound}

ClassBound:
    : [ReferenceTypeSignature]

InterfaceBound:
    : ReferenceTypeSignature

SuperclassSignature:
    ClassTypeSignature

SuperinterfaceSignature:
    ClassTypeSignature

FieldSignature:
    ReferenceTypeSignature

MethodSignature:
    [TypeParameters] ( {JavaTypeSignature} ) Result {ThrowsSignature}

Result:
    JavaTypeSignature | VoidDescriptor

ThrowsSignature:
    ^ ClassTypeSignature | ^ TypeVariableSignature

JavaTypeSignature:
    ReferenceTypeSignature | BaseType

BaseType:
    B | C | D | F | I | J | S | Z

ReferenceTypeSignature:
    ClassTypeSignature | TypeVariableSignature | ArrayTypeSignature

ClassTypeSignature:
    L [PackageSpecifier] SimpleClassTypeSignature {ClassTypeSignatureSuffix} ;

PackageSpecifier:
    Identifier / {PackageSpecifier}

SimpleClassTypeSignature:
    Identifier [TypeArguments]

ClassTypeSignatureSuffix:
    . SimpleClassTypeSignature

TypeVariableSignature:
    T Identifier ;

TypeArguments:
    < {TypeArgument} >

TypeArgument:
    [WildcardIndicator] ReferenceTypeSignature | *

WildcardIndicator:
    + | -

ArrayTypeSignature:
    [ JavaTypeSignature

VoidDescriptor:
    V
```

### 3.2 Parser Structure for Phase 5

The parser is a single-pass character scanner with a mutable index over the signature string. This
is exactly the pattern tasty-query's `JavaSignatures.scala` uses.

Key invariants:
- `consume(char: Char): Boolean` — if `peek == char`, advance and return true.
- `expect(char: Char)` — advance or fail.
- `identifier()` — consume chars until `'.' | ';' | '[' | '/' | '<' | '>' | ':'`.
- `binaryName()` — like identifier but also consumes `'/'` (used for class names like `java/util/List`).

BaseType to Reflect.Type mapping:
| Char | Type |
|------|------|
| B | `Named(byteSym)` (scala.Byte) |
| C | `Named(charSym)` (scala.Char) |
| D | `Named(doubleSym)` |
| F | `Named(floatSym)` |
| I | `Named(intSym)` |
| J | `Named(longSym)` |
| S | `Named(shortSym)` |
| Z | `Named(booleanSym)` |

These primitive symbols must be pre-resolved from the classpath (java.lang.* primitives) or stored
as well-known symbol stubs. The simplest approach: create placeholder `Unresolved` symbols with
the correct FQN (e.g., `"scala.Int"`) and let Phase 7 resolve them. OR build a small
`WellKnownTypes` helper that maps char -> a pre-built `Reflect.Symbol` with
`kind = SymbolKind.Class, flags = Flag.JavaDefined`.

TypeArgument rules (per DESIGN.md §9 and execution-plan.md line 344):
```
peek == '*'  =>  consume; Wildcard(Named(nothingSym), Named(objectSym))
peek == '+'  =>  consume; upper = referenceType; Wildcard(Named(nothingSym), upper)
peek == '-'  =>  consume; lower = referenceType; Wildcard(lower, Named(objectSym))
otherwise    =>  referenceType; no wildcard wrapper
```

### 3.3 Erased (Raw) Types

A class ref without `<` following is a raw type. Example: `Ljava/util/List;` with no angle
brackets produces `Named(listSym)`, NOT `Applied(Named(listSym), Chunk.empty)`. Raw types and
parameterized types with args are distinct. Callers can distinguish by checking whether the result
is `Named` vs `Applied`.

### 3.4 ClassTypeSignatureSuffix (Inner Class Member Access)

The `.` after the closing `>` or identifier in a `ClassTypeSignature` denotes an inner-class suffix:
`Ljava/util/Map<Ljava/lang/String;Ljava/lang/Object;>.Entry;` parses the outer type, then
appends `.Entry` as an inner class access. tasty-query models this as `TypeRef(outerType, innerName)`.
kyo-reflect's Phase 5 should model it as a nested `Named` lookup or defer to Phase 7's resolver.

---

## 4. Lazy Constant-Pool Design

Per DESIGN.md §10 and execution-plan.md line 343.

### 4.1 The Pattern

During constant pool reading (pass 1 over the pool), UTF-8 entries are stored lazily:
- Record `(offset, length)` into the file byte array.
- Decode to `Interner.Entry` (and thus `Reflect.Name`) only on first access via `utf8(idx)`.
- Cache the decoded `Interner.Entry` in the pool slot.

This avoids decoding thousands of UTF-8 strings that Phase 5 never inspects (e.g., method
descriptor strings for methods that are filtered out, or string constant values).

### 4.2 ConstantPool Entry ADT

```scala
sealed trait Entry
object Entry:
    // Lazy UTF-8: stores raw bytes, decodes on first utf8() call
    final class Utf8Lazy(val bytes: Array[Byte], val offset: Int, val length: Int) extends Entry:
        // AtomicReference wraps the cached result; null = not yet decoded
        private val cached = new java.util.concurrent.atomic.AtomicReference[Interner.Entry | Null](null)
        def decode(interner: Interner): Interner.Entry =
            var r = cached.get()
            if r == null then
                r = interner.intern(bytes, offset, length)
                cached.set(r)
            r

    // Already-decoded (first access replaced the Lazy slot with this)
    case class Utf8Decoded(entry: Interner.Entry) extends Entry

    case class ClassRef(nameIdx: Int) extends Entry
    case class NameAndType(nameIdx: Int, descriptorIdx: Int) extends Entry
    case class Fieldref(classIdx: Int, nameAndTypeIdx: Int) extends Entry
    case class Methodref(classIdx: Int, nameAndTypeIdx: Int) extends Entry
    case class InterfaceMethodref(classIdx: Int, nameAndTypeIdx: Int) extends Entry
    case class Integer(value: Int) extends Entry
    case class Float(value: scala.Float) extends Entry
    case class Long(value: scala.Long) extends Entry       // occupies 2 pool slots
    case class Double(value: scala.Double) extends Entry   // occupies 2 pool slots
    case class StringConst(stringIdx: Int) extends Entry
    case class MethodHandle(referenceKind: Int, referenceIdx: Int) extends Entry
    case class MethodType(descriptorIdx: Int) extends Entry
    case class Dynamic(bootstrapIdx: Int, nameAndTypeIdx: Int) extends Entry
    case class InvokeDynamic(bootstrapIdx: Int, nameAndTypeIdx: Int) extends Entry
    case class Module(nameIdx: Int) extends Entry
    case class Package(nameIdx: Int) extends Entry
    case object Hole extends Entry   // sentinel for the second slot of Long/Double entries
```

### 4.3 ConstantPool.utf8 Method

```scala
def utf8(idx: Int): String < Abort[ReflectError] =
    entries(idx) match
        case lazy_: Entry.Utf8Lazy => Sync.defer(lazy_.decode(interner).string.get())
        case Entry.Utf8Decoded(e)  => Kyo.pure(e.string.get())
        case _                     => Abort.fail(ReflectError.ClassfileFormatError(path, s"Expected Utf8 at pool[$idx]"))
```

The plan shows `ConstantPool.read` returns `ConstantPool < Abort[ReflectError]` (execution-plan.md
line 343). Wrap the whole reading pass in `Sync.defer`.

### 4.4 classRef helper

```scala
def classRef(idx: Int): String < Abort[ReflectError] =
    entries(idx) match
        case Entry.ClassRef(nameIdx) => utf8(nameIdx)  // binary name e.g. "java/util/ArrayList"
        case _ => Abort.fail(ReflectError.ClassfileFormatError(path, s"Expected Class at pool[$idx]"))
```

---

## 5. Cross-Platform Guarantee: No JVM-Specific APIs

Phase 5's four files MUST compile and run on JVM, JS, and Native. Prohibited:
- `java.io.*`, `java.nio.*` file I/O
- `java.util.jar.*`, `java.util.zip.*`
- `java.lang.reflect.*`
- Any FFI or Platform-specific import

Permitted:
- `Array[Byte]` (universal)
- `ByteView` (pure byte arithmetic)
- `Interner` (pure Scala, no JVM deps)
- `java.util.concurrent.atomic.AtomicReference` (available on all three platforms in Scala's
  standard library abstraction; used in `Interner` and `Memo` already)
- `java.nio.charset.StandardCharsets.UTF_8` (used in Utf8.scala and Interner; confirmed working
  on all platforms via Phase 1)
- `math.min`, `math.max`, standard Scala collections

UTF-8 decoding: use the existing `Utf8.scala` and `Interner` infrastructure (already cross-platform
verified in Phase 1).

Float/Double from bytes:
```scala
java.lang.Float.intBitsToFloat(intValue)       // cross-platform
java.lang.Double.longBitsToDouble(longValue)   // cross-platform
```

---

## 6. File:Line Anchors for Files Phase 5 References

All paths are relative to the worktree root:
`/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/`

| File | Key lines |
|------|-----------|
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala` | 12-44 trait, 63-89 Heap |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/Varint.scala` | DO NOT USE for classfiles |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Interner.scala` | 27 intern(), 163-169 Entry |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Symbol.scala` | 17-26 makeSymbol |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Flags.scala` | 71-83 fromJvmAccessFlags |
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathRef.scala` | 14-22 ClasspathRef |
| `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 117-121 SymbolKind, 166-198 Type, 202-297 Symbol |
| `kyo-reflect/shared/src/main/scala/kyo/ReflectError.scala` | 7-21 full enum |

Phase 5 creates new files in:
```
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/
    ClassfileFormat.scala
    ConstantPool.scala
    JavaSignatures.scala
    ClassfileUnpickler.scala
kyo-reflect/shared/src/test/scala/kyo/
    ClassfileReaderTest.scala
    JavaSignaturesTest.scala
```

---

## 7. Edge Cases and Gotchas

### 7.1 Constant Pool is 1-Indexed

The pool has `count` entries but indices run from 1 to `count-1`. Slot 0 is unused (never written,
never read). All cross-references in the classfile use 1-based indices.

Allocation:
```scala
val entries = new Array[Entry | Null](count)  // size = count; entries[0] unused
```

An index of 0 in a field like `outer_class_info_index` of InnerClasses means "absent", not a pool
reference. Check for 0 before dereferencing.

### 7.2 Long and Double Take Two Slots

After reading a `CONSTANT_Long` (tag 5) or `CONSTANT_Double` (tag 6) entry, advance pool index by 2.
Slot `i+1` holds a sentinel (`Entry.Hole`); reading it is a format error.

```scala
// In the pool reading loop:
case 5 | 6 =>
    entries(index) = readLongOrDouble(tag, view)
    entries(index + 1) = Entry.Hole
    index += 2
case _ =>
    entries(index) = readOther(tag, view)
    index += 1
```

### 7.3 Skip the Code Attribute

For every method, Phase 5 reads the attribute list to find `Signature`, `Exceptions`,
`RuntimeVisibleAnnotations`, and `RuntimeInvisibleAnnotations`. Any attribute with name `"Code"`
must be skipped by reading its `u4` length and skipping that many bytes. Do NOT attempt to decode
the bytecode.

### 7.4 InnerClasses: Only Extract Raw Tuples

Phase 5 extracts the raw `(inner_class_info_index, outer_class_info_index, inner_name_index,
inner_class_access_flags)` tuples and resolves them to binary name strings (via constant pool
lookups). It stores these in `ClassfileResult.innerClassTable: Map[String, (String, String)]`
for Phase 5b / Phase 7 to use for FQN canonicalization.

An entry with `outer_class_info_index == 0` means the inner class is local or anonymous. Store
with `outerBinaryName = ""`.

An entry with `inner_name_index == 0` means anonymous class. Store with `innerSimpleName = ""`.

### 7.5 Pre-Java-5 Classfiles: Missing Signature Attribute

If no `Signature` attribute exists for a class, field, or method, Phase 5 falls back to the
erased descriptor from `descriptor_index`. For fields this is the JVM field descriptor (e.g.,
`"Ljava/util/List;"` with no generics). Phase 5 parses the erased descriptor using the simpler
descriptor grammar (not the generic signature grammar). The erased descriptor parser:

```
FieldDescriptor: BaseType | ObjectType | ArrayType
ObjectType: L ClassName ;    (where ClassName uses '/' separators, no generics)
ArrayType: [ ComponentType
BaseType: B | C | D | F | I | J | S | Z
```

The erased ObjectType is just `classRef(binaryName)` without any `Applied` wrapper.

### 7.6 module-info.class: Skip Entirely

Per execution-plan.md line 339: if the simple name of the `.class` file being read is
`"module-info.class"`, return immediately with an empty result (or a designated sentinel).
Do not attempt to parse it. JPMS module declarations are out of v1 scope.

Check: `bytes` passed to `ClassfileUnpickler.read` produces a `this_class` binary name ending in
`/module-info`. The simplest guard: check magic and version, then check `thisClassBinaryName`
for the suffix `module-info`, and return early.

### 7.7 ACC_RECORD vs ACC_FINAL Bit Collision

At the class level, the JVM spec 16+ assigns `ACC_RECORD = 0x0010`, which is the same bit value as
`ACC_FINAL`. These two flags co-exist for record classes (records are always final). kyo-reflect
should NOT determine "is this a record?" from the bit flag alone. Instead:
- Check whether the `Record` attribute is present by name in the class-level attributes list.
- If present, set `Flag.JavaRecord`.
- If absent, do not set `Flag.JavaRecord`, regardless of the 0x0010 bit.

### 7.8 Static Initializer `<clinit>` and Constructor `<init>`

These are valid method names in classfiles. They should be read as `Method` symbols. Their names
contain `<` and `>`, which are normally delimiter characters in signatures but NOT in plain method
names (which come from `name_index -> Utf8`, not from the signature parser). No special handling
needed; just intern the name bytes verbatim.

### 7.9 Synthetic and Bridge Methods

Methods with `ACC_SYNTHETIC` or `ACC_BRIDGE` are synthetic. Phase 5 reads them (to avoid
out-of-sync member counts), sets `Flag.Synthetic`, and includes them in `ClassfileResult.symbols`.
Callers can filter via `flags.contains(Flag.Synthetic)`.

### 7.10 Type Parameter Symbol Creation

When parsing a `TypeParameter` in the generic signature grammar, Phase 5 must create a
`SymbolKind.TypeParam` symbol. These symbols are owned by the class or method symbol they belong to.
They must be added to the `addrMap` equivalent for the classfile context so that later references
via `T Identifier ;` can resolve to the correct symbol.

The classfile reader does not have a TASTy-style `addrMap` (an Int-keyed map). Instead, it
maintains a `mutable.Map[String, Reflect.Symbol]` (keyed on the type parameter name string) scoped
to the current class or method signature parse. After parsing, this map is discarded; the type
param symbols are embedded by reference in the produced `Type` values.

---

## 8. Test Data Suggestions

### 8.1 Loading JDK Classes at Test Time

Tests 1-12 in `ClassfileReaderTest` load JDK classfiles. The execution-plan specifies:

```scala
getClass.getClassLoader.getResourceAsStream("java/lang/Object.class")
```

This works on JVM with Temurin 25 (required by the project). The resource stream gives raw bytes;
read them into `Array[Byte]` then wrap in `ByteView.apply(bytes)`.

For `java.util.ArrayList` (test 4), `java.lang.String` (test 2-3), `java.util.concurrent.TimeUnit`
(test 6): all loadable via the same `getResourceAsStream` pattern with the binary path.

### 8.2 Pre-Baked Bytes for Cross-Platform Tests

JS and Native cannot use `getClass.getClassLoader.getResourceAsStream`. Tests 1-12 are JVM-only.
For cross-platform compilation, gate these tests with:

```scala
// In test file, gated by platform:
// jvm/src/test/scala/ or shared with a jvmOnly guard
```

However, the execution-plan says to verify that `kyo-reflectJS/Test/compile` and
`kyo-reflectNative/Test/compile` pass (not that the tests run). It is acceptable to have
`ClassfileReaderTest` be JVM-only (placed in `shared/` but guarded with `assume(isJvm)` or
actually placed in `jvm/` test sources).

RECOMMENDATION: Put `ClassfileReaderTest` in `shared/src/test/scala/kyo/` but wrap JDK class
loading calls with a platform guard:

```scala
// Use a resource from kyo-reflect-fixtures instead for cross-platform tests
// OR accept that ClassfileReaderTest only runs on JVM via sbt's testOnly on JVM target
```

`JavaSignaturesTest` is pure string-parsing; it runs cross-platform with no issues.

### 8.3 Fixture Classfile

The execution-plan mentions `kyo-reflect-fixtures` sub-module. Check if it exists:

```
kyo-reflect/fixtures/ (or kyo-reflect-fixtures/)
```

If a `fixtures` sub-project already exists with `.class` resources, use them. If not, add a tiny
Java source file to `kyo-reflect/shared/src/test/resources/` as a pre-compiled `.class` binary.
The simplest fixture: compile `TestRecord.java` (a Java 16+ record) and `TestGeneric.java`
(a class with type parameters) at build time via the `javac` task in `build.sbt`.

For Phase 5 specifically, the minimum needed in test resources:
- `java/lang/Object.class` via classloader (JVM only)
- A pre-compiled `.class` file with a generic signature (for cross-platform unit tests of
  `ConstantPool` and `JavaSignatures`)

Embed pre-compiled bytes directly as a `val` in the test object:
```scala
// In JavaSignaturesTest.scala:
// These are pure string-parsing tests; no classfile bytes needed.
// ConstantPool unit tests can use a hand-crafted minimal byte array.
```

---

## 9. Anti-Flakiness Notes

### 9.1 Pure Byte Arithmetic

`ClassfileUnpickler`, `ConstantPool`, `ClassfileFormat`, and `JavaSignatures` are entirely
deterministic. Given the same bytes, they produce the same symbols. No concurrency, no
non-deterministic I/O in the parsing core.

### 9.2 Cross-Platform UTF-8

The `Interner` already handles cross-platform UTF-8 via the existing `Utf8.scala` per-platform
implementations (verified in Phase 1). Phase 5 MUST use `interner.intern(bytes, off, len)` for
all string data from classfile UTF-8 entries, never `new String(bytes, off, len, UTF_8)` directly
(except inside the existing `Utf8.scala`).

### 9.3 JVM-Only Tests

Tests that load JDK classfiles via `getResourceAsStream` are JVM-only. Do not fail the build on
JS/Native; gate appropriately. The verification command for Phase 5 from the execution-plan is:

```
sbt 'project kyo-reflect; testOnly kyo.ClassfileReaderTest kyo.JavaSignaturesTest'
```

Plus compile-only checks:
```
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

`JavaSignaturesTest` MUST compile and pass on all platforms.

### 9.4 Constant Pool Index Bounds

Always validate that `idx >= 1 && idx < entries.length` before `entries(idx)`. An invalid index
must produce `Abort.fail(ReflectError.ClassfileFormatError(...))`, not an
`ArrayIndexOutOfBoundsException`.

---

## 10. Concerns and Open Questions

### 10.1 TypeArena Not Used in Phase 5

The execution-plan (line 337) states: "Does not use Phase 4's TypeArena hash-consing infrastructure
(the classfile reader builds Type values directly; canonicalization across files happens in Phase 7's
classpath-level intern)." This means Phase 5 creates `Type` case class instances directly via
`Type.Named(...)`, `Type.Applied(...)`, etc., without going through a TypeArena. The `arena`
parameter in `ClassfileUnpickler.read` signature (execution-plan line 345) is passed through to
`ClassfileResult` but Phase 5 itself does not call arena methods during type construction.

Verify: does `TypeArena` exist as committed code from Phase 4? If Phase 4 is not yet committed,
Phase 5 should define a minimal type alias `type TypeArena = Any` as a placeholder in its own
file, OR simply omit the `arena` parameter and adjust the `ClassfileResult` case class.

### 10.2 addrMap Parameter in JavaSignatures

The execution-plan signature for `JavaSignatures.parseFieldSignature` includes
`addrMap: Map[Int, Reflect.Symbol]`. For classfile symbols, there is no TASTy address map. The
`addrMap` parameter name is misleading in the classfile context; what is actually needed is a
`Map[String, Reflect.Symbol]` keyed on binary name (for resolving class references encountered in
signatures). The implementing agent should clarify whether `addrMap: Map[Int, Reflect.Symbol]` is
intentional (for compatibility with Phase C merge) or should be renamed
`classIndex: Map[String, Reflect.Symbol]` for Phase 5. The plan text says "via addrMap lookup or
UnresolvedRef" — in classfile context, "addrMap" means the local type-parameter scope, not the
TASTy address-to-symbol map.

Recommended: use a separate `typeParamScope: Map[String, Reflect.Symbol]` inside `JavaSignatures`
for the current type parameter environment. For external class references (class names in signatures
like `java/util/List`), look up via a `resolver: String => Reflect.Symbol` callback, or produce
`Unresolved` symbols.

### 10.3 ClassfileResult Depends on Phase 4 TypeArena

The execution-plan line 345 signature is:
```
ClassfileResult(classSymbol: Reflect.Symbol, innerClassTable: Map[String, (String, String)],
                symbols: Chunk[Reflect.Symbol], arena: TypeArena)
```

If `TypeArena` is not committed from Phase 4 at Phase 5 start time, Phase 5 will not compile.
Check Phase 4 status before starting.

### 10.4 Tasty-Query Reference Implementation

The tasty-query 1.3.0 sources (extracted at `/tmp/tasty-query-1_3-src/`) contain the full
classfile reader. Key files to consult if confused:
- `/tmp/tasty-query-1_3-src/tastyquery/reader/classfiles/ClassfileReader.scala` — constant pool,
  magic, attribute scanning, inner classes, annotation parsing.
- `/tmp/tasty-query-1_3-src/tastyquery/reader/classfiles/JavaSignatures.scala` — complete
  generic signature parser (~370 LOC).
- `/tmp/tasty-query-1_3-src/tastyquery/reader/classfiles/ClassfileBuffer.scala` — byte buffer
  (maps to our ByteView).
- `/tmp/tasty-query-1_3-src/tastyquery/reader/classfiles/Constants.scala` — attribute name strings.

Do NOT import or depend on tasty-query. These are reference only.

### 10.5 Flags.fromJvmAccessFlags is Incomplete for Class-Level Flags

The existing `Flags.fromJvmAccessFlags` (Flags.scala line 71) handles only a subset of JVM access
flag bits relevant for field/method contexts. For class-level access flags, Phase 5 needs to
additionally handle:
- `ACC_INTERFACE` (0x0200) -> `Flag.Trait` (SymbolKind.Trait, not Flag.Trait specifically, but
  the kind selection)
- `ACC_ABSTRACT` (0x0400) -> `Flag.Abstract`
- `ACC_ANNOTATION` (0x2000) -> no direct kyo-reflect flag; treat as Trait
- `ACC_ENUM` (0x4000) -> `Flag.Enum` (already in `fromJvmAccessFlags`)

Phase 5 should always OR in `Flag.JavaDefined` for every symbol it creates, since
`fromJvmAccessFlags` does not include this automatically (it uses `Flag.JavaDefined` for
`ACC_STATIC` bit only, which is wrong for class-level context). Phase 5 should apply:

```scala
val baseFlags = Flags.fromJvmAccessFlags(accessFlags) | new Reflect.Flags(Flag.JavaDefined.bit)
// Then add Trait if ACC_INTERFACE:
val finalFlags = if (accessFlags & 0x0200) != 0 then baseFlags | new Reflect.Flags(Flag.Trait.bit) else baseFlags
```

This is a known limitation in Phase 3's `fromJvmAccessFlags` — it was designed for field/method
context, not class-level. Phase 5 should compensate locally without modifying `Flags.scala`
(per CLAUDE.md: make changes one at a time and avoid unnecessary scope expansion).

---

## Summary for Implementing Agent

Phase 5 adds four files to `kyo/internal/reflect/classfile/`:

1. `ClassfileFormat.scala` — constants only, no logic.
2. `ConstantPool.scala` — lazy UTF-8 decode, index validation, typed accessors.
3. `JavaSignatures.scala` — recursive-descent parser of the JVMS §4.7.9.1 grammar producing
   `Reflect.Type` values.
4. `ClassfileUnpickler.scala` — top-level reader: magic, version, pool, access flags, fields,
   methods, class attributes; produces `ClassfileResult` with populated symbols.

All four files are pure byte arithmetic + standard Scala collections. No JVM-specific I/O.
Cross-platform by construction.

The two test files:
- `ClassfileReaderTest` (20 tests): loads JDK classes via `getResourceAsStream` (JVM only for
  execution, but in `shared/` for compilation).
- `JavaSignaturesTest` (tests 13-20): string-parsing only, cross-platform.

Total 20 tests. Verification:
```
sbt 'project kyo-reflect; testOnly kyo.ClassfileReaderTest kyo.JavaSignaturesTest'
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```
