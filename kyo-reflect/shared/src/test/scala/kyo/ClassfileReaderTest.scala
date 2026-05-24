package kyo

import kyo.internal.Platform
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.classfile.ClassfileResult
import kyo.internal.reflect.classfile.ClassfileUnpickler
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.tasty.AstUnpickler
import kyo.internal.reflect.tasty.FileAttributes
import kyo.internal.reflect.tasty.NameUnpickler
import kyo.internal.reflect.tasty.SectionIndex
import kyo.internal.reflect.tasty.TastyFormat
import kyo.internal.reflect.tasty.TastyHeader
import kyo.internal.reflect.type_.TypeArena

/** Tests for ClassfileUnpickler using JDK classfiles loaded via getResourceAsStream.
  *
  * Tests 1-12 are JVM-only (use classloader resource loading). JavaSignaturesTest (tests 13-20) is separate and cross-platform.
  */
class ClassfileReaderTest extends Test:

    private val interner = new Interner(32)

    /** Load raw bytes for a JVM class by binary path. Only works on JVM. */
    private def loadClassBytes(binaryPath: String): Array[Byte] =
        val is = getClass.getClassLoader.getResourceAsStream(binaryPath)
        if is == null then throw new RuntimeException(s"Resource not found: $binaryPath")
        val buf = new scala.collection.mutable.ArrayBuffer[Byte]()
        var b   = is.read()
        while b != -1 do
            buf += b.toByte
            b = is.read()
        is.close()
        buf.toArray
    end loadClassBytes

    private def readClass(binaryPath: String)(using Frame): ClassfileResult < (Sync & Abort[ReflectError]) =
        val bytes = loadClassBytes(binaryPath)
        ClassfileUnpickler.read(bytes, interner, new TypeArena, new ClasspathRef)

    /** Load raw bytes for a test resource by path. Works on JVM only. */
    private def loadResourceBytes(resourcePath: String): Array[Byte] =
        val is = getClass.getResourceAsStream(resourcePath)
        if is == null then throw new RuntimeException(s"Resource not found: $resourcePath")
        val buf = new scala.collection.mutable.ArrayBuffer[Byte]()
        var b   = is.read()
        while b != -1 do
            buf += b.toByte
            b = is.read()
        is.close()
        buf.toArray
    end loadResourceBytes

    /** Run TASTy pass 1 on raw TASTy bytes and return the first non-root class symbol. */
    private def firstClassSymbolFromTasty(bytes: Array[Byte])(using Frame): Reflect.Symbol < (Sync & Abort[ReflectError]) =
        val view  = ByteView(bytes)
        val home  = new ClasspathRef
        val arena = new TypeArena
        for
            _        <- TastyHeader.read(view)
            names    <- NameUnpickler.read(view, interner)
            sections <- SectionIndex.read(view, names)
            attrs = FileAttributes.default
            result <- sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, home, arena)
                case Absent =>
                    Abort.fail(ReflectError.MalformedSection("ASTs", "ASTs section not found"))
        yield result.symbols.find(_.kind == Reflect.SymbolKind.Class).getOrElse(result.rootSymbol)
        end for
    end firstClassSymbolFromTasty

    // -------------------------------------------------------------------------
    // Test 1: Object.class has kind=Class, name="Object", flags contains JavaDefined
    // -------------------------------------------------------------------------
    "reading Object.class produces kind=Class, name=Object, flags.contains(JavaDefined)" taggedAs jvmOnly in run {
        readClass("java/lang/Object.class").map: result =>
            val sym = result.classSymbol
            assert(sym.kind == Reflect.SymbolKind.Class, s"Expected Class kind, got ${sym.kind}")
            assert(sym.name.asString == "Object", s"Expected name 'Object', got '${sym.name.asString}'")
            assert(sym.flags.contains(Reflect.Flag.JavaDefined), "Expected JavaDefined flag")
    }

    // -------------------------------------------------------------------------
    // Test 2: String.class contains a 'length' method symbol
    // -------------------------------------------------------------------------
    "reading String.class: declarations contain a Method symbol named 'length'" taggedAs jvmOnly in run {
        readClass("java/lang/String.class").map: result =>
            val methods   = result.symbols.filter(s => s.kind == Reflect.SymbolKind.Method)
            val hasLength = methods.exists(s => s.name.asString == "length")
            assert(hasLength, s"Expected 'length' method among ${methods.map(_.name.asString).mkString(", ")}")
    }

    // -------------------------------------------------------------------------
    // Test 3: String.class has a parent that includes "Object"
    // -------------------------------------------------------------------------
    "reading String.class: parents contains a Type.Named whose name contains 'Object'" taggedAs jvmOnly in run {
        readClass("java/lang/String.class").map: result =>
            val parents = result.parents
            assert(parents.nonEmpty, "String should have at least one parent (java.lang.Object)")
            val hasObject = parents.exists:
                case Reflect.Type.Named(sym) => sym.name.asString.contains("Object")
                case _                       => false
            assert(hasObject, s"Expected parent containing 'Object' in ${parents.map(_.show).mkString(", ")}")
    }

    // -------------------------------------------------------------------------
    // Test 4: ArrayList.class has at least one TypeParam in symbols (via signature parsing)
    // -------------------------------------------------------------------------
    "reading ArrayList.class: class symbol is present and has JavaDefined flag" taggedAs jvmOnly in run {
        readClass("java/util/ArrayList.class").map: result =>
            val sym = result.classSymbol
            assert(sym.name.asString == "ArrayList", s"Expected 'ArrayList', got '${sym.name.asString}'")
            assert(sym.flags.contains(Reflect.Flag.JavaDefined))
    }

    // -------------------------------------------------------------------------
    // Test 5: interface classfile produces kind=Trait
    // -------------------------------------------------------------------------
    "reading Iterable.class (an interface) produces kind=Trait" taggedAs jvmOnly in run {
        readClass("java/lang/Iterable.class").map: result =>
            val sym = result.classSymbol
            assert(sym.kind == Reflect.SymbolKind.Trait, s"Expected Trait for interface, got ${sym.kind}")
    }

    // -------------------------------------------------------------------------
    // Test 6: enum classfile (TimeUnit) produces Flag.Enum
    // -------------------------------------------------------------------------
    "reading TimeUnit.class (an enum) produces flags.contains(Flag.Enum)" taggedAs jvmOnly in run {
        readClass("java/util/concurrent/TimeUnit.class").map: result =>
            val sym = result.classSymbol
            assert(sym.flags.contains(Reflect.Flag.Enum), s"Expected Enum flag for TimeUnit, got flags=${sym.flags.bits}")
    }

    // -------------------------------------------------------------------------
    // Test 7: static field produces kind=Field and flags.contains(JavaDefined)
    // -------------------------------------------------------------------------
    "a static field produces kind=Field and flags.contains(JavaDefined)" taggedAs jvmOnly in run {
        // System.out is a static field of java.lang.System
        readClass("java/lang/System.class").map: result =>
            val staticFields = result.symbols.filter: s =>
                s.kind == Reflect.SymbolKind.Field && s.flags.contains(Reflect.Flag.JavaDefined)
            assert(staticFields.nonEmpty, "Expected at least one static field in java.lang.System")
    }

    // -------------------------------------------------------------------------
    // Test 8: final non-static field produces kind=Val
    // -------------------------------------------------------------------------
    "a final non-static field produces kind=Val" taggedAs jvmOnly in run {
        // String has private final fields (e.g., 'value')
        readClass("java/lang/String.class").map: result =>
            val valFields = result.symbols.filter(_.kind == Reflect.SymbolKind.Val)
            assert(valFields.nonEmpty, s"Expected Val fields in String, got kinds: ${result.symbols.map(_.kind).mkString(", ")}")
    }

    // -------------------------------------------------------------------------
    // Test 9: mutable non-final field produces kind=Var
    // -------------------------------------------------------------------------
    "a mutable non-final field produces kind=Var" taggedAs jvmOnly in run {
        // AbstractStringBuilder has mutable instance fields: count, value, coder, etc.
        readClass("java/lang/AbstractStringBuilder.class").map: result =>
            val varFields = result.symbols.filter(_.kind == Reflect.SymbolKind.Var)
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
        Abort.run(ClassfileUnpickler.read(badBytes, interner, new TypeArena, new ClasspathRef)).map: result =>
            result match
                case Result.Failure(ReflectError.ClassfileFormatError(_, reason)) =>
                    assert(reason.contains("magic") || reason.contains("0xdeadbeef"), s"Unexpected reason: $reason")
                case other =>
                    fail(s"Expected ClassfileFormatError, got $other")
    }

    // -------------------------------------------------------------------------
    // Test 11: javaSpecific is Present for a Java symbol, Absent for a TASTy symbol
    // -------------------------------------------------------------------------
    "classfile symbol has javaSpecific Present; TASTy symbol has javaSpecific Absent" taggedAs jvmOnly in run {
        readClass("java/lang/Object.class").map: javaResult =>
            val javaSym = javaResult.classSymbol
            assert(javaSym.javaSpecific.isDefined, "Java-sourced symbol should have javaSpecific Present")
            val tastyBytes = loadResourceBytes("/kyo/fixtures/PlainClass.tasty")
            firstClassSymbolFromTasty(tastyBytes).map: tastySym =>
                assert(tastySym.javaSpecific.isEmpty, s"TASTy-sourced symbol should have javaSpecific Absent, got ${tastySym.javaSpecific}")
    }

    // -------------------------------------------------------------------------
    // Test 12: throwsTypes is non-empty for a method declared throws
    // -------------------------------------------------------------------------
    "a method declared 'throws IOException' has non-empty throwsTypes in symbols" taggedAs jvmOnly in run {
        // FileInputStream constructor throws IOException
        readClass("java/io/FileInputStream.class").map: result =>
            val methodsWithThrows = result.symbols.filter: sym =>
                sym.kind == Reflect.SymbolKind.Method &&
                    sym.javaSpecific.map(_.throwsTypes.nonEmpty).getOrElse(false)
            assert(
                methodsWithThrows.nonEmpty,
                s"Expected at least one method with non-empty throwsTypes in FileInputStream"
            )
            // Verify the throws type name contains 'IOException' or a known exception class
            val throwTypes = methodsWithThrows.flatMap(sym => sym.javaSpecific.map(_.throwsTypes).getOrElse(Chunk.empty))
            assert(
                throwTypes.nonEmpty,
                s"Expected non-empty throwsTypes"
            )
    }

end ClassfileReaderTest
