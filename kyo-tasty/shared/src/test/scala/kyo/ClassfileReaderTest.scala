package kyo
import kyo.internal.Platform
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.classfile.ClassfileResult
import kyo.internal.tasty.classfile.ClassfileUnpickler
import kyo.internal.tasty.reader.AstUnpickler
import kyo.internal.tasty.reader.FileAttributes
import kyo.internal.tasty.reader.NameUnpickler
import kyo.internal.tasty.reader.SectionIndex
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TastyHeader
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.type_.TypeArena

/** Tests for ClassfileUnpickler using JDK classfiles and embedded fixture classfiles.
  *
  * Some leaves use inline synthetic bytes or embedded fixture classfiles for cross-platform
  * execution without requiring a JVM classloader.
  */
class ClassfileReaderTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def symJavaMetadata(symbol: Tasty.Symbol): Maybe[Tasty.Java.Metadata] = symbol match
        case c: Tasty.Symbol.ClassLike => c.javaMetadata
        case f: Tasty.Symbol.Field     => f.javaMetadata
        case m: Tasty.Symbol.Method    => m.javaMetadata
        case _                         => Maybe.Absent

    private def symJavaMetadata(symbol: kyo.internal.tasty.symbol.LoadingSymbol.Materialising): Maybe[Tasty.Java.Metadata] =
        symbol.javaMetadata

    private def symPermittedSubclassIds(symbol: Tasty.Symbol): Maybe[Chunk[kyo.Tasty.SymbolId]] = symbol match
        case c: Tasty.Symbol.Class => c.permittedSubclassIds
        case t: Tasty.Symbol.Trait => t.permittedSubclassIds
        case _                     => Maybe.Absent

    private def symPermittedSubclassIds(symbol: kyo.internal.tasty.symbol.LoadingSymbol.Materialising): Maybe[Chunk[kyo.Tasty.SymbolId]] =
        symbol.permittedSubclassIds.map(_.map(kyo.Tasty.SymbolId(_)))

    /** Load raw bytes for a JDK class by binary path from EmbeddedClassfiles (cross-platform). */
    private def loadClassBytes(binaryPath: String): Array[Byte] =
        kyo.fixtures.EmbeddedClassfiles.loadJdkClass(binaryPath)

    private def readClass(binaryPath: String)(using Frame): ClassfileResult < (Sync & Abort[TastyError]) =
        val bytes = loadClassBytes(binaryPath)
        readClassfile(bytes, new TypeArena)

    /** Test bridge: lift the Result-returning ClassfileUnpickler.read into the test's effect row. */
    private def readClassfile(bytes: Array[Byte], arena: TypeArena)(using Frame): ClassfileResult < (Sync & Abort[TastyError]) =
        Sync.Unsafe.defer {
            given AllowUnsafe = AllowUnsafe.embrace.danger
            Abort.get(ClassfileUnpickler.read(bytes, arena))
        }

    /** Load raw bytes for a test resource by path. Works on JVM only. */
    private def loadResourceBytes(resourcePath: String): Array[Byte] =
        TestResourceLoader.loadBytes(resourcePath)

    /** Run TASTy pass 1 on raw TASTy bytes and return the first non-root class symbol. */
    private def firstClassSymbolFromTasty(bytes: Array[Byte])(using Frame): LoadingSymbol.Materialising < (Sync & Abort[TastyError]) =
        val view  = ByteView(bytes)
        val arena = new TypeArena
        for
            _        <- Sync.Unsafe.defer(Abort.get(TastyHeader.read(view)))
            names    <- NameUnpickler.read(view)
            sections <- SectionIndex.read(view, names)
            attrs = FileAttributes.default
            result <- sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, arena)
                case Absent =>
                    Abort.fail(TastyError.MalformedSection("ASTs", "ASTs section not found", 0L))
        yield result.symbols.find(_.kind == SymbolKind.Class).getOrElse(result.rootSymbol)
        end for
    end firstClassSymbolFromTasty

    "reading a Java classfile produces kind=Class and flags.contains(JavaDefined)" in {
        readClassfile(kyo.fixtures.Embedded.throwsFixtureClass, new TypeArena).map { result =>
            val symbol = result.classSymbol
            assert(symbol.kind == SymbolKind.Class, s"Expected Class kind, got ${symbol.kind}")
            assert(symbol.name.asString.nonEmpty, s"Expected non-empty class name, got empty string")
            assert(symbol.flags.contains(Tasty.Flag.JavaDefined), "Expected JavaDefined flag")
        }
    }

    "reading a Java classfile: declarations contain Method symbols" in {
        readClassfile(kyo.fixtures.Embedded.throwsFixtureClass, new TypeArena).map { result =>
            val methods = result.symbols.filter(s => s.kind == SymbolKind.Method)
            assert(methods.nonEmpty, s"Expected at least one Method symbol in ThrowsFixture; got none")
            val methodNames = methods.map(_.name.asString).toSeq
            assert(
                methodNames.exists(_.nonEmpty),
                s"Expected at least one method with non-empty name; got $methodNames"
            )
        }
    }

    "reading a Java classfile: parents contains a Type.Named (superclass reference)" in {
        readClassfile(kyo.fixtures.Embedded.throwsFixtureClass, new TypeArena).map { result =>
            val parents = result.parents
            assert(parents.nonEmpty, "ThrowsFixture should have at least one parent (java.lang.Object)")
            val hasNamed = parents.exists {
                case Tasty.Type.Named(_) => true
                case _                   => false
            }
            assert(hasNamed, s"Expected at least one Named parent in ${parents.size} parents")
        }
    }

    "reading ArrayList.class: typeParams contains at least one TypeParam with non-empty name" in {
        readClass("java/util/ArrayList.class").map { result =>
            val symbol = result.classSymbol
            assert(symbol.name.asString == "ArrayList", s"Expected 'ArrayList', got '${symbol.name.asString}'")
            assert(symbol.flags.contains(Tasty.Flag.JavaDefined))
            assert(result.typeParams.nonEmpty, s"Expected at least one typeParam in ArrayList (generic class), got empty")
            val badKind = result.typeParams.find(_.kind != SymbolKind.TypeParam)
            assert(
                badKind.isEmpty,
                s"Expected all typeParams to have TypeParam kind; bad: ${badKind.map(tp => tp.name.asString + ":" + tp.kind)}"
            )
            val emptyName = result.typeParams.find(_.name.asString.isEmpty)
            assert(emptyName.isEmpty, s"Expected all typeParams to have non-empty names; found empty name")
        }
    }

    "reading an interface classfile produces kind=Trait" in {
        // Minimal classfile for interface "kyo/fixtures/MinimalIface":
        //   magic, version 55 (Java 11), pool:
        //     #1 Utf8 "kyo/fixtures/MinimalIface"
        //     #2 Class #1
        //     #3 Utf8 "java/lang/Object"
        //     #4 Class #3
        //   access_flags = ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT = 0x0601
        //   this = #2, super = #4, 0 interfaces, 0 fields, 0 methods, 0 attrs
        val clsName = "kyo/fixtures/MinimalIface".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val supName = "java/lang/Object".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val buffer  = new java.io.ByteArrayOutputStream()
        def writeInt(v: Int): Unit =
            buffer.write((v >>> 24) & 0xff); buffer.write((v >>> 16) & 0xff)
            buffer.write((v >>> 8) & 0xff); buffer.write(v & 0xff)
        def writeShort(v: Int): Unit =
            buffer.write((v >>> 8) & 0xff); buffer.write(v & 0xff)
        def writeByte(v: Int): Unit = buffer.write(v & 0xff)
        writeInt(0xcafebabe)
        writeShort(0); writeShort(55)
        writeShort(5)                                                   // pool count = 5 (entries 1..4)
        writeByte(1); writeShort(clsName.length); buffer.write(clsName) // #1 Utf8
        writeByte(7); writeShort(1)                                     // #2 Class -> #1
        writeByte(1); writeShort(supName.length); buffer.write(supName) // #3 Utf8
        writeByte(7); writeShort(3)                                     // #4 Class -> #3
        writeShort(0x0601)                                              // ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT
        writeShort(2); writeShort(4)                                    // this=#2, super=#4
        writeShort(0); writeShort(0); writeShort(0); writeShort(0)      // 0 ifaces, fields, methods, attrs
        val bytes = buffer.toByteArray
        readClassfile(bytes, new TypeArena).map { result =>
            val symbol = result.classSymbol
            assert(symbol.kind == SymbolKind.Trait, s"Expected Trait for interface classfile, got ${symbol.kind}")
        }
    }

    "reading an enum classfile produces flags.contains(Flag.Enum)" in {
        val clsName = "kyo/fixtures/MinimalEnum".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val supName = "java/lang/Enum".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val buffer  = new java.io.ByteArrayOutputStream()
        def writeInt(v: Int): Unit =
            buffer.write((v >>> 24) & 0xff); buffer.write((v >>> 16) & 0xff)
            buffer.write((v >>> 8) & 0xff); buffer.write(v & 0xff)
        def writeShort(v: Int): Unit =
            buffer.write((v >>> 8) & 0xff); buffer.write(v & 0xff)
        def writeByte(v: Int): Unit = buffer.write(v & 0xff)
        writeInt(0xcafebabe); writeShort(0); writeShort(55); writeShort(5)
        writeByte(1); writeShort(clsName.length); buffer.write(clsName) // #1 class name
        writeByte(7); writeShort(1)                                     // #2 Class -> #1
        writeByte(1); writeShort(supName.length); buffer.write(supName) // #3 super name
        writeByte(7); writeShort(3)                                     // #4 Class -> #3
        // ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_ENUM = 0x0021 | 0x0010 | 0x4000 = 0x4031
        writeShort(0x4031); writeShort(2); writeShort(4)
        writeShort(0); writeShort(0); writeShort(0); writeShort(0)
        val bytes = buffer.toByteArray
        readClassfile(bytes, new TypeArena).map { result =>
            val symbol = result.classSymbol
            assert(
                symbol.flags.contains(Tasty.Flag.Enum),
                s"Expected Enum flag for synthetic enum classfile, got flags=${symbol.flags.bits}"
            )
        }
    }

    "a static field produces kind=Field and flags.contains(JavaDefined)" in {
        // Minimal classfile for class "kyo/fixtures/StaticFieldHolder" with one static int field "VALUE":
        //   pool: #1 Utf8 "kyo/fixtures/StaticFieldHolder", #2 Class #1,
        //          #3 Utf8 "java/lang/Object", #4 Class #3,
        //          #5 Utf8 "VALUE", #6 Utf8 "I"
        //   access_flags = 0x0021 (ACC_PUBLIC | ACC_SUPER)
        //   1 field: access_flags=0x0019 (ACC_PUBLIC|ACC_STATIC|ACC_FINAL), name=#5, descriptor=#6, 0 attrs
        val clsName   = "kyo/fixtures/StaticFieldHolder".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val supName   = "java/lang/Object".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val fieldName = "VALUE".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val fieldDesc = "I".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val buffer    = new java.io.ByteArrayOutputStream()
        def writeInt(v: Int): Unit =
            buffer.write((v >>> 24) & 0xff); buffer.write((v >>> 16) & 0xff)
            buffer.write((v >>> 8) & 0xff); buffer.write(v & 0xff)
        def writeShort(v: Int): Unit =
            buffer.write((v >>> 8) & 0xff); buffer.write(v & 0xff)
        def writeByte(v: Int): Unit = buffer.write(v & 0xff)
        writeInt(0xcafebabe)
        writeShort(0); writeShort(55)
        writeShort(7)                                                       // pool count 7 (entries 1..6)
        writeByte(1); writeShort(clsName.length); buffer.write(clsName)     // #1
        writeByte(7); writeShort(1)                                         // #2 Class -> #1
        writeByte(1); writeShort(supName.length); buffer.write(supName)     // #3
        writeByte(7); writeShort(3)                                         // #4 Class -> #3
        writeByte(1); writeShort(fieldName.length); buffer.write(fieldName) // #5
        writeByte(1); writeShort(fieldDesc.length); buffer.write(fieldDesc) // #6
        writeShort(0x0021)                                                  // access_flags ACC_PUBLIC | ACC_SUPER
        writeShort(2); writeShort(4)                                        // this=#2, super=#4
        writeShort(0)                                                       // 0 interfaces
        writeShort(1)                                                       // 1 field
        // field: ACC_PUBLIC | ACC_STATIC | ACC_FINAL = 0x0019
        writeShort(0x0019); writeShort(5); writeShort(6); writeShort(0) // flags, name, desc, 0 attrs
        writeShort(0); writeShort(0)                                    // 0 methods, 0 attrs
        val bytes = buffer.toByteArray
        readClassfile(bytes, new TypeArena).map { result =>
            val staticFields = result.symbols.filter { s =>
                s.kind == SymbolKind.Field && s.flags.contains(Tasty.Flag.JavaDefined)
            }
            assert(
                staticFields.nonEmpty,
                s"Expected at least one static field in synthetic classfile; symbols=${result.symbols.map(s =>
                        s.name.asString + ":" + s.kind
                    ).mkString(", ")}"
            )
        }
    }

    // Java record component fields are private final, which decodes as Val.
    "a final non-static field produces kind=Val" in {
        readClassfile(kyo.fixtures.Embedded.pointRecordClass, new TypeArena).map { result =>
            val valFields = result.symbols.filter(_.kind == SymbolKind.Val)
            assert(
                valFields.nonEmpty,
                s"Expected Val fields in PointRecord (record fields are final), got kinds: ${result.symbols.map(_.kind).mkString(", ")}"
            )
        }
    }

    // A field without ACC_FINAL decodes as Var.
    "a mutable non-final field produces kind=Var" in {
        // Minimal classfile for "kyo/fixtures/MutableFieldHolder" with one non-final int field "count":
        //   pool: #1 Utf8 class name, #2 Class #1, #3 Utf8 "java/lang/Object", #4 Class #3, #5 Utf8 "count", #6 Utf8 "I"
        //   access_flags = ACC_PUBLIC | ACC_SUPER (0x0021)
        //   1 field: ACC_PUBLIC (0x0001) only -- not ACC_FINAL => Var
        val clsName   = "kyo/fixtures/MutableFieldHolder".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val supName   = "java/lang/Object".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val fieldName = "count".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val fieldDesc = "I".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val buffer    = new java.io.ByteArrayOutputStream()
        def writeInt(v: Int): Unit =
            buffer.write((v >>> 24) & 0xff); buffer.write((v >>> 16) & 0xff)
            buffer.write((v >>> 8) & 0xff); buffer.write(v & 0xff)
        def writeShort(v: Int): Unit =
            buffer.write((v >>> 8) & 0xff); buffer.write(v & 0xff)
        def writeByte(v: Int): Unit = buffer.write(v & 0xff)
        writeInt(0xcafebabe)
        writeShort(0); writeShort(55)
        writeShort(7) // pool entries 1..6
        writeByte(1); writeShort(clsName.length); buffer.write(clsName)
        writeByte(7); writeShort(1)
        writeByte(1); writeShort(supName.length); buffer.write(supName)
        writeByte(7); writeShort(3)
        writeByte(1); writeShort(fieldName.length); buffer.write(fieldName)
        writeByte(1); writeShort(fieldDesc.length); buffer.write(fieldDesc)
        writeShort(0x0021); writeShort(2); writeShort(4)                // class flags, this, super
        writeShort(0)                                                   // 0 interfaces
        writeShort(1)                                                   // 1 field
        writeShort(0x0001); writeShort(5); writeShort(6); writeShort(0) // ACC_PUBLIC (no FINAL), name, desc, 0 attrs
        writeShort(0); writeShort(0)                                    // 0 methods, 0 class attrs
        val bytes = buffer.toByteArray
        readClassfile(bytes, new TypeArena).map { result =>
            val varFields = result.symbols.filter(_.kind == SymbolKind.Var)
            assert(
                varFields.nonEmpty,
                s"Expected Var fields in synthetic non-final classfile, got: ${result.symbols.map(s => s.name.asString + ":" + s.kind).mkString(", ")}"
            )
        }
    }

    "a byte array with wrong magic produces Abort.fail(ClassfileFormatError)" in {
        val badBytes = Array[Byte](
            0xde.toByte,
            0xad.toByte,
            0xbe.toByte,
            0xef.toByte, // wrong magic
            0x00.toByte,
            0x00.toByte, // minor
            0x00.toByte,
            0x3d.toByte // major = 61 (Java 17)
        )
        Abort.run(readClassfile(badBytes, new TypeArena)).map { result =>
            result match
                case Result.Failure(TastyError.ClassfileFormatError(_, reason, _)) =>
                    assert(reason.contains("magic") || reason.contains("0xdeadbeef"), s"Unexpected reason: $reason")
                case other =>
                    fail(s"Expected ClassfileFormatError, got $other")
        }
    }

    "classfile symbol has javaMetadata Present; TASTy symbol has javaMetadata Absent" in {
        readClassfile(kyo.fixtures.Embedded.throwsFixtureClass, new TypeArena).map { javaResult =>
            val javaSym = javaResult.classSymbol
            assert(symJavaMetadata(javaSym).isDefined, "Java-sourced symbol should have javaMetadata Present")
            val tastyBytes = kyo.fixtures.Embedded.plainClassTasty
            firstClassSymbolFromTasty(tastyBytes).map { tastySym =>
                assert(
                    symJavaMetadata(tastySym).isEmpty,
                    s"TASTy-sourced symbol should have javaMetadata Absent, got ${symJavaMetadata(tastySym)}"
                )
            }
        }
    }

    "a method declared 'throws IOException' has non-empty throwsTypes in symbols" in {
        // FileInputStream constructor throws IOException
        readClass("java/io/FileInputStream.class").map { result =>
            val methodsWithThrows = result.symbols.filter { symbol =>
                symbol.kind == SymbolKind.Method &&
                symJavaMetadata(symbol).map(_.throwsTypes.nonEmpty).getOrElse(false)
            }
            assert(
                methodsWithThrows.nonEmpty,
                s"Expected at least one method with non-empty throwsTypes in FileInputStream"
            )
            // Verify the throws type name contains 'IOException' or a known exception class
            val throwTypes = methodsWithThrows.flatMap(symbol => symJavaMetadata(symbol).map(_.throwsTypes).getOrElse(Chunk.empty))
            assert(
                throwTypes.nonEmpty,
                s"Expected non-empty throwsTypes"
            )
        }
    }

    "BootstrapMethods attribute is parsed into metadata.bootstrapMethods" in {
        // java.util.function.Function uses BootstrapMethods for lambda-compose default methods
        readClass("java/util/function/Function.class").map { result =>
            val md = symJavaMetadata(result.classSymbol).getOrElse(fail("Expected javaMetadata Present"))
            assert(
                md.bootstrapMethods.nonEmpty,
                "Expected at least one BootstrapMethods entry in java/util/function/Function.class"
            )
            // Each entry is a Chunk of ints: [methodRef, arg0, arg1.]
            val firstEntry = md.bootstrapMethods.head
            assert(firstEntry.nonEmpty, "Expected non-empty first BootstrapMethods entry (methodRef + args)")
        }
    }

    // ClassfileUnpickler stores the NestHost binary fully-qualified name in ClassfileResult.nestHostFullName;
    // the orchestrator resolves the fully-qualified name to a real Symbol after finalizeMerge.
    "NestHost attribute is parsed into nestHostFullName for an inner class" in {
        // java.util.HashMap$Node is an inner class of HashMap; has NestHost = java/util/HashMap
        readClass("java/util/HashMap$Node.class").map { result =>
            assert(
                result.nestHostFullName.isDefined,
                "Expected nestHostFullName to be Present for java/util/HashMap$Node.class"
            )
            val hostFullName = result.nestHostFullName.get
            assert(
                hostFullName.contains("HashMap"),
                s"Expected nestHostFullName to contain 'HashMap', got '$hostFullName'"
            )
        }
    }

    // ClassfileUnpickler stores NestMembers fully-qualified names in ClassfileResult.nestMemberFullNames.
    "NestMembers attribute is parsed into nestMemberFullNames for an outer class" in {
        // java.util.HashMap has inner classes; NestMembers lists them
        readClass("java/util/HashMap.class").map { result =>
            assert(
                result.nestMemberFullNames.nonEmpty,
                "Expected non-empty nestMemberFullNames in java/util/HashMap.class"
            )
        }
    }

    "PermittedSubclasses attribute is parsed for a sealed interface" in {
        // java.lang.constant.ClassDesc is a sealed interface (JDK 12+) with known permitted subclasses
        readClass("java/lang/constant/ClassDesc.class").map { result =>
            val md        = symJavaMetadata(result.classSymbol).getOrElse(fail("Expected javaMetadata Present"))
            val permitted = symPermittedSubclassIds(result.classSymbol)
            assert(
                permitted.isDefined,
                "Expected permittedSubclassIds to be Present for java/lang/constant/ClassDesc.class"
            )
            val subs = permitted.get
            assert(
                subs.nonEmpty,
                s"Expected non-empty permittedSubclassIds for ClassDesc, got empty"
            )
        }
    }

    "MethodParameters attribute is parsed into method symbol metadata.paramNames" in {
        // java.lang.module.ModuleDescriptor$Requires$Modifier has MethodParameters in its constructor
        readClass("java/lang/module/ModuleDescriptor$Requires$Modifier.class").map { result =>
            val methodsWithParams = result.symbols.filter { symbol =>
                symbol.kind == SymbolKind.Method &&
                symJavaMetadata(symbol).exists(_.paramNames.nonEmpty)
            }
            assert(
                methodsWithParams.nonEmpty,
                "Expected at least one method with non-empty paramNames in ModuleDescriptor$Requires$Modifier"
            )
        }
    }

    "RuntimeVisibleTypeAnnotations attribute is decoded into metadata.runtimeTypeAnnotations" in {
        // Build a synthetic classfile with RuntimeVisibleTypeAnnotations attribute on the class.
        // Classfile structure: magic, version, minimal pool, flags, this/super, 0 interfaces,
        // 0 fields, 0 methods, 1 attribute (RuntimeVisibleTypeAnnotations).
        // RuntimeVisibleTypeAnnotations body: u2 num_annotations=1, one type_annotation with
        // target_type=0x13 (empty_target, CLASS_EXTENDS), type_path(0 entries), and
        // annotation: type_index=ref-to-Ljava/lang/Deprecated;, u2 pairs=0.
        val attrName = "RuntimeVisibleTypeAnnotations".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val clsName  = "SyntheticTypeAnn".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val supName  = "java/lang/Object".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val annDesc  = "Ljava/lang/Deprecated;".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val buffer   = new java.io.ByteArrayOutputStream()
        def writeInt(v: Int): Unit =
            buffer.write((v >>> 24) & 0xff)
            buffer.write((v >>> 16) & 0xff)
            buffer.write((v >>> 8) & 0xff)
            buffer.write(v & 0xff)
        end writeInt
        def writeShort(v: Int): Unit =
            buffer.write((v >>> 8) & 0xff)
            buffer.write(v & 0xff)
        def writeByte(v: Int): Unit = buffer.write(v & 0xff)
        // magic
        writeInt(0xcafebabe)
        // minor=0, major=65 (Java 21)
        writeShort(0)
        writeShort(65)
        // constant_pool_count = 7 (entries 1.6)
        writeShort(7)
        // #1 Utf8 "SyntheticTypeAnn"
        writeByte(1)
        writeShort(clsName.length)
        buffer.write(clsName)
        // #2 Class -> #1
        writeByte(7)
        writeShort(1)
        // #3 Utf8 "java/lang/Object"
        writeByte(1)
        writeShort(supName.length)
        buffer.write(supName)
        // #4 Class -> #3
        writeByte(7)
        writeShort(3)
        // #5 Utf8 "RuntimeVisibleTypeAnnotations"
        writeByte(1)
        writeShort(attrName.length)
        buffer.write(attrName)
        // #6 Utf8 "Ljava/lang/Deprecated;"
        writeByte(1)
        writeShort(annDesc.length)
        buffer.write(annDesc)
        // access_flags = ACC_PUBLIC (0x0001)
        writeShort(0x0001)
        // this_class = #2
        writeShort(2)
        // super_class = #4
        writeShort(4)
        // interfaces_count = 0
        writeShort(0)
        // fields_count = 0
        writeShort(0)
        // methods_count = 0
        writeShort(0)
        // attributes_count = 1
        writeShort(1)
        // Attribute: name_index=#5, length=?
        // RuntimeVisibleTypeAnnotations body:
        //   u2 num_annotations=1,
        //   type_annotation:
        //     u1 target_type=0x13 (empty_target) -> no additional target_info bytes
        //     type_path: u1 path_length=0
        //     annotation: u2 type_index=#6, u2 num_element_value_pairs=0
        val typeAnnBody = Array[Byte](
            0x00,
            0x01,        // num_annotations=1
            0x13.toByte, // target_type=0x13 (CLASS_EXTENDS empty_target)
            0x00.toByte, // type_path length=0
            0x00,
            0x06, // type_index=#6
            0x00,
            0x00 // num_element_value_pairs=0
        )
        writeShort(5) // attribute_name_index=#5
        writeInt(typeAnnBody.length)
        buffer.write(typeAnnBody)
        val bytes = buffer.toByteArray
        readClassfile(bytes, new TypeArena).map { result =>
            val md = symJavaMetadata(result.classSymbol).getOrElse(fail("Expected javaMetadata Present"))
            assert(
                md.runtimeTypeAnnotations.nonEmpty,
                s"Expected non-empty runtimeTypeAnnotations from synthetic classfile, got empty"
            )
            val annotation = md.runtimeTypeAnnotations.head
            assert(
                annotation.annotationClass.name.asString.contains("Deprecated"),
                s"Expected annotation class containing 'Deprecated', got ${annotation.annotationClass.name.asString}"
            )
        }
    }

end ClassfileReaderTest
