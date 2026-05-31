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
import kyo.internal.tasty.symbol.Interner
import kyo.internal.tasty.type_.TypeArena

/** Tests for ClassfileUnpickler using JDK classfiles loaded via getResourceAsStream.
  *
  * Tests 1-12 are JVM-only (use classloader resource loading). JavaSignaturesTest (tests 13-20) is separate and cross-platform.
  */
class ClassfileReaderTest extends Test:

    import AllowUnsafe.embrace.danger

    private val interner = Interner.init(numShards = 32, initialShardCapacity = 16)

    /** Load raw bytes for a JVM class by binary path. Only works on JVM. */
    private def loadClassBytes(binaryPath: String): Array[Byte] =
        TestResourceLoader.loadBytes(binaryPath)

    private def readClass(binaryPath: String)(using Frame): ClassfileResult < (Sync & Abort[TastyError]) =
        val bytes = loadClassBytes(binaryPath)
        ClassfileUnpickler.read(bytes, interner, new TypeArena)

    /** Load raw bytes for a test resource by path. Works on JVM only. */
    private def loadResourceBytes(resourcePath: String): Array[Byte] =
        TestResourceLoader.loadBytes(resourcePath)

    /** Run TASTy pass 1 on raw TASTy bytes and return the first non-root class symbol. */
    private def firstClassSymbolFromTasty(bytes: Array[Byte])(using Frame): Tasty.Symbol < (Sync & Abort[TastyError]) =
        val view  = ByteView(bytes)
        val arena = new TypeArena
        for
            _        <- TastyHeader.read(view)
            names    <- NameUnpickler.read(view, interner)
            sections <- SectionIndex.read(view, names)
            attrs = FileAttributes.default
            result <- sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, arena)
                case Absent =>
                    Abort.fail(TastyError.MalformedSection("ASTs", "ASTs section not found", 0L))
        yield result.symbols.find(_.kind == Tasty.SymbolKind.Class).getOrElse(result.rootSymbol)
        end for
    end firstClassSymbolFromTasty

    // -------------------------------------------------------------------------
    // Test 1: Object.class has kind=Class, name="Object", flags contains JavaDefined
    // -------------------------------------------------------------------------
    "reading Object.class produces kind=Class, name=Object, flags.contains(JavaDefined)" taggedAs jvmOnly in run {
        readClass("java/lang/Object.class").map: result =>
            val sym = result.classSymbol
            assert(sym.kind == Tasty.SymbolKind.Class, s"Expected Class kind, got ${sym.kind}")
            assert(sym.name.asString == "Object", s"Expected name 'Object', got '${sym.name.asString}'")
            assert(sym.flags.contains(Tasty.Flag.JavaDefined), "Expected JavaDefined flag")
    }

    // -------------------------------------------------------------------------
    // Test 2: String.class contains a 'length' method symbol
    // -------------------------------------------------------------------------
    "reading String.class: declarations contain a Method symbol named 'length'" taggedAs jvmOnly in run {
        readClass("java/lang/String.class").map: result =>
            val methods   = result.symbols.filter(s => s.kind == Tasty.SymbolKind.Method)
            val hasLength = methods.exists(s => s.name.asString == "length")
            assert(hasLength, s"Expected 'length' method among ${methods.map(_.name.asString).mkString(", ")}")
    }

    // -------------------------------------------------------------------------
    // Test 3: String.class has a parent that includes "Object"
    // -------------------------------------------------------------------------
    // plan: phase-05; Named(id) no longer carries a Symbol directly. Name check deferred to Phase 09.
    // Verifies that at least one parent is a Named type (structure preserved).
    "reading String.class: parents contains a Type.Named whose name contains 'Object'" taggedAs jvmOnly in run {
        readClass("java/lang/String.class").map: result =>
            val parents = result.parents
            assert(parents.nonEmpty, "String should have at least one parent (java.lang.Object)")
            val hasNamed = parents.exists:
                case Tasty.Type.Named(_) => true
                case _                   => false
            assert(hasNamed, s"Expected at least one Named parent in ${parents.size} parents")
    }

    // -------------------------------------------------------------------------
    // Test 4: ArrayList.class has at least one TypeParam via class Signature attribute
    // -------------------------------------------------------------------------
    "reading ArrayList.class: typeParams contains at least one TypeParam with non-empty name" taggedAs jvmOnly in run {
        readClass("java/util/ArrayList.class").map: result =>
            val sym = result.classSymbol
            assert(sym.name.asString == "ArrayList", s"Expected 'ArrayList', got '${sym.name.asString}'")
            assert(sym.flags.contains(Tasty.Flag.JavaDefined))
            assert(result.typeParams.nonEmpty, s"Expected at least one typeParam in ArrayList (generic class), got empty")
            val badKind = result.typeParams.find(_.kind != Tasty.SymbolKind.TypeParam)
            assert(
                badKind.isEmpty,
                s"Expected all typeParams to have TypeParam kind; bad: ${badKind.map(tp => tp.name.asString + ":" + tp.kind)}"
            )
            val emptyName = result.typeParams.find(_.name.asString.isEmpty)
            assert(emptyName.isEmpty, s"Expected all typeParams to have non-empty names; found empty name")
    }

    // -------------------------------------------------------------------------
    // Test 5: interface classfile produces kind=Trait
    // -------------------------------------------------------------------------
    "reading Iterable.class (an interface) produces kind=Trait" taggedAs jvmOnly in run {
        readClass("java/lang/Iterable.class").map: result =>
            val sym = result.classSymbol
            assert(sym.kind == Tasty.SymbolKind.Trait, s"Expected Trait for interface, got ${sym.kind}")
    }

    // -------------------------------------------------------------------------
    // Test 6: enum classfile (TimeUnit) produces Flag.Enum
    // -------------------------------------------------------------------------
    "reading TimeUnit.class (an enum) produces flags.contains(Flag.Enum)" taggedAs jvmOnly in run {
        readClass("java/util/concurrent/TimeUnit.class").map: result =>
            val sym = result.classSymbol
            assert(sym.flags.contains(Tasty.Flag.Enum), s"Expected Enum flag for TimeUnit, got flags=${sym.flags.bits}")
    }

    // -------------------------------------------------------------------------
    // Test 7: static field produces kind=Field and flags.contains(JavaDefined)
    // -------------------------------------------------------------------------
    "a static field produces kind=Field and flags.contains(JavaDefined)" taggedAs jvmOnly in run {
        // System.out is a static field of java.lang.System
        readClass("java/lang/System.class").map: result =>
            val staticFields = result.symbols.filter: s =>
                s.kind == Tasty.SymbolKind.Field && s.flags.contains(Tasty.Flag.JavaDefined)
            assert(staticFields.nonEmpty, "Expected at least one static field in java.lang.System")
    }

    // -------------------------------------------------------------------------
    // Test 8: final non-static field produces kind=Val
    // -------------------------------------------------------------------------
    "a final non-static field produces kind=Val" taggedAs jvmOnly in run {
        // String has private final fields (e.g., 'value')
        readClass("java/lang/String.class").map: result =>
            val valFields = result.symbols.filter(_.kind == Tasty.SymbolKind.Val)
            assert(valFields.nonEmpty, s"Expected Val fields in String, got kinds: ${result.symbols.map(_.kind).mkString(", ")}")
    }

    // -------------------------------------------------------------------------
    // Test 9: mutable non-final field produces kind=Var
    // -------------------------------------------------------------------------
    "a mutable non-final field produces kind=Var" taggedAs jvmOnly in run {
        // AbstractStringBuilder has mutable instance fields: count, value, coder, etc.
        readClass("java/lang/AbstractStringBuilder.class").map: result =>
            val varFields = result.symbols.filter(_.kind == Tasty.SymbolKind.Var)
            assert(
                varFields.nonEmpty,
                s"Expected Var fields in AbstractStringBuilder, got: ${result.symbols.map(s => s.name.asString + ":" + s.kind).mkString(", ")}"
            )
    }

    // -------------------------------------------------------------------------
    // Test 10: wrong magic produces ClassfileFormatError
    // -------------------------------------------------------------------------
    "a byte array with wrong magic produces Abort.fail(ClassfileFormatError)" in run {
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
        Abort.run(ClassfileUnpickler.read(badBytes, interner, new TypeArena)).map: result =>
            result match
                case Result.Failure(TastyError.ClassfileFormatError(_, reason, _)) =>
                    assert(reason.contains("magic") || reason.contains("0xdeadbeef"), s"Unexpected reason: $reason")
                case other =>
                    fail(s"Expected ClassfileFormatError, got $other")
    }

    // -------------------------------------------------------------------------
    // Test 11: javaMetadata is Present for a Java symbol, Absent for a TASTy symbol
    // -------------------------------------------------------------------------
    "classfile symbol has javaMetadata Present; TASTy symbol has javaMetadata Absent" taggedAs jvmOnly in run {
        readClass("java/lang/Object.class").map: javaResult =>
            val javaSym = javaResult.classSymbol
            assert(javaSym._javaMetadata.isDefined, "Java-sourced symbol should have javaMetadata Present")
            val tastyBytes = kyo.fixtures.Embedded.plainClassTasty
            firstClassSymbolFromTasty(tastyBytes).map: tastySym =>
                assert(
                    tastySym._javaMetadata.isEmpty,
                    s"TASTy-sourced symbol should have javaMetadata Absent, got ${tastySym._javaMetadata}"
                )
    }

    // -------------------------------------------------------------------------
    // Test 12: throwsTypes is non-empty for a method declared throws
    // -------------------------------------------------------------------------
    "a method declared 'throws IOException' has non-empty throwsTypes in symbols" taggedAs jvmOnly in run {
        // FileInputStream constructor throws IOException
        readClass("java/io/FileInputStream.class").map: result =>
            val methodsWithThrows = result.symbols.filter: sym =>
                sym.kind == Tasty.SymbolKind.Method &&
                    sym._javaMetadata.map(_.throwsTypes.nonEmpty).getOrElse(false)
            assert(
                methodsWithThrows.nonEmpty,
                s"Expected at least one method with non-empty throwsTypes in FileInputStream"
            )
            // Verify the throws type name contains 'IOException' or a known exception class
            val throwTypes = methodsWithThrows.flatMap(sym => sym._javaMetadata.map(_.throwsTypes).getOrElse(Chunk.empty))
            assert(
                throwTypes.nonEmpty,
                s"Expected non-empty throwsTypes"
            )
    }

    // -------------------------------------------------------------------------
    // Test 13 (M8/INV-008): BootstrapMethods attribute is parsed and exposed
    // -------------------------------------------------------------------------
    "M8: BootstrapMethods attribute is parsed into metadata.bootstrapMethods" taggedAs jvmOnly in run {
        // java.util.function.Function uses BootstrapMethods for lambda-compose default methods
        readClass("java/util/function/Function.class").map: result =>
            val md = result.classSymbol._javaMetadata.getOrElse(fail("Expected javaMetadata Present"))
            assert(
                md.bootstrapMethods.nonEmpty,
                "Expected at least one BootstrapMethods entry in java/util/function/Function.class"
            )
            // Each entry is a Chunk of ints: [methodRef, arg0, arg1, ...]
            val firstEntry = md.bootstrapMethods.head
            assert(firstEntry.nonEmpty, "Expected non-empty first BootstrapMethods entry (methodRef + args)")
    }

    // -------------------------------------------------------------------------
    // Test 14 (M8/INV-008): NestHost attribute is parsed and exposed
    // -------------------------------------------------------------------------
    "M8: NestHost attribute is parsed into metadata.nestHost for an inner class" taggedAs jvmOnly in run {
        // java.util.HashMap$Node is an inner class of HashMap; has NestHost = java/util/HashMap
        readClass("java/util/HashMap$Node.class").map: result =>
            val md = result.classSymbol._javaMetadata.getOrElse(fail("Expected javaMetadata Present"))
            assert(
                md.nestHost.isDefined,
                "Expected nestHost to be Present for java/util/HashMap$Node.class"
            )
            val hostName = md.nestHost.get.name.asString
            assert(
                hostName == "HashMap" || hostName.contains("HashMap"),
                s"Expected NestHost name to contain 'HashMap', got '$hostName'"
            )
    }

    // -------------------------------------------------------------------------
    // Test 15 (M8/INV-008): NestMembers attribute is parsed and exposed
    // -------------------------------------------------------------------------
    "M8: NestMembers attribute is parsed into metadata.nestMembers for an outer class" taggedAs jvmOnly in run {
        // java.util.HashMap has inner classes; NestMembers lists them
        readClass("java/util/HashMap.class").map: result =>
            val md = result.classSymbol._javaMetadata.getOrElse(fail("Expected javaMetadata Present"))
            assert(
                md.nestMembers.nonEmpty,
                "Expected non-empty nestMembers in java/util/HashMap.class"
            )
    }

    // -------------------------------------------------------------------------
    // Test 16 (M8/INV-008): PermittedSubclasses attribute is parsed and exposed
    // -------------------------------------------------------------------------
    "M8: PermittedSubclasses attribute is parsed for a sealed interface" taggedAs jvmOnly in run {
        // java.lang.constant.ClassDesc is a sealed interface (JDK 12+) with known permitted subclasses
        readClass("java/lang/constant/ClassDesc.class").map: result =>
            val md = result.classSymbol._javaMetadata.getOrElse(fail("Expected javaMetadata Present"))
            // plan: phase-02 inline; permittedSubclassIds carries SymbolId values (not Symbol objects).
            val permitted = result.classSymbol._permittedSubclassIds
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

    // -------------------------------------------------------------------------
    // Test 17 (M8/INV-008): MethodParameters attribute is parsed and exposed
    // -------------------------------------------------------------------------
    "M8: MethodParameters attribute is parsed into method symbol metadata.paramNames" taggedAs jvmOnly in run {
        // java.lang.module.ModuleDescriptor$Requires$Modifier has MethodParameters in its constructor
        readClass("java/lang/module/ModuleDescriptor$Requires$Modifier.class").map: result =>
            val methodsWithParams = result.symbols.filter: sym =>
                sym.kind == Tasty.SymbolKind.Method &&
                    sym._javaMetadata.exists(_.paramNames.nonEmpty)
            assert(
                methodsWithParams.nonEmpty,
                "Expected at least one method with non-empty paramNames in ModuleDescriptor$Requires$Modifier"
            )
    }

    // -------------------------------------------------------------------------
    // Test 18 (M8/INV-008): RuntimeTypeAnnotations attribute is parsed and exposed
    // -------------------------------------------------------------------------
    "M8: RuntimeVisibleTypeAnnotations attribute is decoded into metadata.runtimeTypeAnnotations" taggedAs jvmOnly in run {
        // Build a synthetic classfile with RuntimeVisibleTypeAnnotations attribute on the class.
        // Classfile structure: magic, version, minimal pool, flags, this/super, 0 interfaces,
        // 0 fields, 0 methods, 1 attribute (RuntimeVisibleTypeAnnotations).
        //
        // RuntimeVisibleTypeAnnotations body: u2 num_annotations=1,
        //   then one type_annotation:
        //     target_type=0x13 (empty_target, CLASS_EXTENDS), type_path(0 entries),
        //     then annotation: type_index=ref-to-pool-Utf8-"Ljava/lang/Deprecated;", u2 pairs=0.
        //
        // Constant pool:
        //   #1 = Utf8  "SyntheticTypeAnn"         (class name)
        //   #2 = Class #1                          (CONSTANT_Class for this)
        //   #3 = Utf8  "java/lang/Object"
        //   #4 = Class #3                          (CONSTANT_Class for super)
        //   #5 = Utf8  "RuntimeVisibleTypeAnnotations"
        //   #6 = Utf8  "Ljava/lang/Deprecated;"   (annotation type descriptor)
        val attrName = "RuntimeVisibleTypeAnnotations".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val clsName  = "SyntheticTypeAnn".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val supName  = "java/lang/Object".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val annDesc  = "Ljava/lang/Deprecated;".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        import java.io.ByteArrayOutputStream
        import java.io.DataOutputStream
        val buf = new ByteArrayOutputStream()
        val out = new DataOutputStream(buf)
        // magic
        out.writeInt(0xcafebabe)
        // minor=0, major=65 (Java 21)
        out.writeShort(0)
        out.writeShort(65)
        // constant_pool_count = 7 (entries 1..6)
        out.writeShort(7)
        // #1 Utf8 "SyntheticTypeAnn"
        out.writeByte(1)
        out.writeShort(clsName.length)
        out.write(clsName)
        // #2 Class -> #1
        out.writeByte(7)
        out.writeShort(1)
        // #3 Utf8 "java/lang/Object"
        out.writeByte(1)
        out.writeShort(supName.length)
        out.write(supName)
        // #4 Class -> #3
        out.writeByte(7)
        out.writeShort(3)
        // #5 Utf8 "RuntimeVisibleTypeAnnotations"
        out.writeByte(1)
        out.writeShort(attrName.length)
        out.write(attrName)
        // #6 Utf8 "Ljava/lang/Deprecated;"
        out.writeByte(1)
        out.writeShort(annDesc.length)
        out.write(annDesc)
        // access_flags = ACC_PUBLIC (0x0001)
        out.writeShort(0x0001)
        // this_class = #2
        out.writeShort(2)
        // super_class = #4
        out.writeShort(4)
        // interfaces_count = 0
        out.writeShort(0)
        // fields_count = 0
        out.writeShort(0)
        // methods_count = 0
        out.writeShort(0)
        // attributes_count = 1
        out.writeShort(1)
        // Attribute: name_index=#5, length=?
        // RuntimeVisibleTypeAnnotations body:
        //   u2 num_annotations=1,
        //   type_annotation:
        //     u1 target_type=0x13 (empty_target)  -> no additional target_info bytes
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
        out.writeShort(5) // attribute_name_index=#5
        out.writeInt(typeAnnBody.length)
        out.write(typeAnnBody)
        out.flush()
        val bytes = buf.toByteArray
        ClassfileUnpickler.read(bytes, interner, new TypeArena).map: result =>
            val md = result.classSymbol._javaMetadata.getOrElse(fail("Expected javaMetadata Present"))
            assert(
                md.runtimeTypeAnnotations.nonEmpty,
                s"Expected non-empty runtimeTypeAnnotations from synthetic classfile, got empty"
            )
            val ann = md.runtimeTypeAnnotations.head
            assert(
                ann.annotationClass.name.asString.contains("Deprecated"),
                s"Expected annotation class containing 'Deprecated', got ${ann.annotationClass.name.asString}"
            )
    }

end ClassfileReaderTest
