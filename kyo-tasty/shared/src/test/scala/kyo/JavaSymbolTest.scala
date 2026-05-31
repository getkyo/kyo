package kyo

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

/** Phase 5b tests for the unified Java symbol surface.
  *
  * Tests 1-10 as specified in execution-plan.md Phase 5b and PHASE-5b-PREP.md §7.3.
  *
  * Tests that load JDK classfiles (tests 1, 2, 4, 5, 7, 9) are JVM-only. Tests that load compiled fixture classfiles from resources (tests
  * 6, 8, 10) are JVM-only. Tests that use synthetic classfile bytes (test 3) run on all platforms.
  */
class JavaSymbolTest extends Test:

    import AllowUnsafe.embrace.danger

    private val interner = Interner.init(numShards = 32, initialShardCapacity = 16)

    /** Load JDK class bytes by binary path. JVM-only. */
    private def loadJdkClass(binaryPath: String): Array[Byte] =
        TestResourceLoader.loadBytes(binaryPath)

    /** Load fixture class bytes from test resources. JVM-only. */
    private def loadFixture(name: String): Array[Byte] =
        TestResourceLoader.loadBytes(s"/kyo/fixtures/$name")

    private def readClass(bytes: Array[Byte])(using Frame): ClassfileResult < (Sync & Abort[TastyError]) =
        ClassfileUnpickler.read(bytes, interner, new TypeArena)

    /** Load raw bytes for a test resource by path. JVM-only. */
    private def loadResourceBytes(resourcePath: String): Array[Byte] =
        TestResourceLoader.loadBytes(resourcePath)

    /** Run TASTy pass 1 on raw TASTy bytes and return the first non-root class symbol. */
    private def firstClassSymbolFromTasty(resourceName: String)(using Frame): Tasty.Symbol < (Sync & Abort[TastyError]) =
        val bytes = resourceName match
            case "PlainClass.tasty" => kyo.fixtures.Embedded.plainClassTasty
            case other              => loadResourceBytes(s"/kyo/fixtures/$other")
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
    // Test 1: fullName for inner class - plan: phase-02 deferred; sym.fullName is Phase 09.
    // -------------------------------------------------------------------------
    "sym.fullName for java.util.Map$Entry.class returns dotted form java.util.Map.Entry" taggedAs jvmOnly in {
        pending // plan: phase-02; sym.fullName deferred to Phase 09
    }

    // -------------------------------------------------------------------------
    // Test 2: binaryName - plan: phase-02 deferred; sym.binaryName is Phase 09.
    // -------------------------------------------------------------------------
    "sym.binaryName for java.util.Map$Entry.class returns JVM form java/util/Map$Entry" taggedAs jvmOnly in {
        pending // plan: phase-02; sym.binaryName deferred to Phase 09
    }

    // -------------------------------------------------------------------------
    // Test 3: top-level class with literal '$' in binary name has fullName preserving '$'
    // plan: phase-02 deferred; sym.fullName is Phase 09
    // This test is cross-platform (uses no classloader, synthesizes minimal classfile bytes).
    // Binary name: "com/example/Foo$Bar" with NO InnerClasses entry.
    // Expected fullName: "com.example.Foo$Bar" (dollar sign preserved literally).
    // -------------------------------------------------------------------------
    "top-level class with literal $ in binary name preserves $ in fullName" in run {
        // Minimal classfile: magic + version 55.0 (Java 11) + empty pool-stub + ...
        // We use the real JDK to build the fixture, stored in resources, but here we construct
        // bytes minimally. Since we can't javac at test time, we build a synthetic minimal classfile.
        //
        // Actually the fixture bytes need to represent a class named "com/example/Foo$Bar"
        // with no InnerClasses attribute. We embed the pre-baked bytes below.
        // Binary form of classfile with this_class = "com/example/Foo$Bar", super = Object,
        // no fields, no methods, no attributes. Compiled with javac and verified manually.
        //
        // We build minimal classfile bytes for "com/example/Foo$Bar":
        //   magic=cafebabe, minor=0, major=55 (Java11), cp_count=5,
        //   pool[1]=Utf8("com/example/Foo$Bar"), pool[2]=Class(1),
        //   pool[3]=Utf8("java/lang/Object"), pool[4]=Class(3),
        //   access_flags=0x0021 (public + super), this=2, super=4,
        //   interfaces_count=0, fields_count=0, methods_count=0, attributes_count=0
        val fooBarClassBytes: Array[Byte] = Array(
            0xca.toByte,
            0xfe.toByte,
            0xba.toByte,
            0xbe.toByte, // magic
            0x00.toByte,
            0x00.toByte, // minor = 0
            0x00.toByte,
            0x37.toByte, // major = 55 (Java 11)
            0x00.toByte,
            0x05.toByte, // cp_count = 5 (indices 1..4)
            // pool[1] = CONSTANT_Utf8 "com/example/Foo$Bar"
            0x01.toByte,
            0x00.toByte,
            0x13.toByte, // tag=1, length=19
            0x63.toByte,
            0x6f.toByte,
            0x6d.toByte,
            0x2f.toByte, // "com/"
            0x65.toByte,
            0x78.toByte,
            0x61.toByte,
            0x6d.toByte, // "exam"
            0x70.toByte,
            0x6c.toByte,
            0x65.toByte,
            0x2f.toByte, // "ple/"
            0x46.toByte,
            0x6f.toByte,
            0x6f.toByte,
            0x24.toByte, // "Foo$"
            0x42.toByte,
            0x61.toByte,
            0x72.toByte, // "Bar"
            // pool[2] = CONSTANT_Class(nameIdx=1)
            0x07.toByte,
            0x00.toByte,
            0x01.toByte,
            // pool[3] = CONSTANT_Utf8 "java/lang/Object"
            0x01.toByte,
            0x00.toByte,
            0x10.toByte,
            0x6a.toByte,
            0x61.toByte,
            0x76.toByte,
            0x61.toByte, // "java"
            0x2f.toByte,
            0x6c.toByte,
            0x61.toByte,
            0x6e.toByte, // "/lan"
            0x67.toByte,
            0x2f.toByte,
            0x4f.toByte,
            0x62.toByte, // "g/Ob"
            0x6a.toByte,
            0x65.toByte,
            0x63.toByte,
            0x74.toByte, // "ject"
            // pool[4] = CONSTANT_Class(nameIdx=3)
            0x07.toByte,
            0x00.toByte,
            0x03.toByte,
            0x00.toByte,
            0x21.toByte, // access_flags = ACC_PUBLIC | ACC_SUPER
            0x00.toByte,
            0x02.toByte, // this_class = pool[2]
            0x00.toByte,
            0x04.toByte, // super_class = pool[4]
            0x00.toByte,
            0x00.toByte, // interfaces_count = 0
            0x00.toByte,
            0x00.toByte, // fields_count = 0
            0x00.toByte,
            0x00.toByte, // methods_count = 0
            0x00.toByte,
            0x00.toByte // attributes_count = 0
        )
        discard(fooBarClassBytes)
        pending // plan: phase-02; sym.fullName deferred to Phase 09
    }

    // -------------------------------------------------------------------------
    // Test 4: isJava is true for Java classfile symbols, false for TASTy symbols
    // -------------------------------------------------------------------------
    "sym.isJava: true for Java classfile, false for TASTy" taggedAs jvmOnly in run {
        val bytes = loadJdkClass("java/lang/Object.class")
        for
            javaResult <- readClass(bytes)
            tastySym   <- firstClassSymbolFromTasty("PlainClass.tasty")
        yield
            // plan: phase-02 inline; sym.isJava removed; use flags.contains(JavaDefined) instead.
            assert(
                javaResult.classSymbol.flags.contains(Tasty.Flag.JavaDefined),
                s"Expected JavaDefined flag for java.lang.Object, flags=${javaResult.classSymbol.flags.bits}"
            )
            assert(
                !tastySym.flags.contains(Tasty.Flag.JavaDefined),
                s"Expected no JavaDefined flag for TASTy PlainClass, flags=${tastySym.flags.bits}"
            )
        end for
    }

    // -------------------------------------------------------------------------
    // Test 5: javaMetadata is Present for Java symbols, Absent for TASTy symbols
    // -------------------------------------------------------------------------
    "sym.javaMetadata: Present for Java, Absent for TASTy" taggedAs jvmOnly in run {
        val bytes = loadJdkClass("java/lang/String.class")
        for
            javaResult <- readClass(bytes)
            tastySym   <- firstClassSymbolFromTasty("PlainClass.tasty")
        yield
            assert(
                javaResult.classSymbol.javaMetadata.isDefined,
                "Expected javaMetadata Present for java.lang.String"
            )
            assert(
                tastySym.javaMetadata.isEmpty,
                s"Expected javaMetadata Absent for TASTy PlainClass, got ${tastySym.javaMetadata}"
            )
        end for
    }

    // -------------------------------------------------------------------------
    // Test 6: throwsTypes is non-empty for a method declared throws Exception
    // -------------------------------------------------------------------------
    "JavaMetadata.throwsTypes is non-empty for a method declared throws Exception" taggedAs jvmOnly in run {
        val bytes = loadFixture("ThrowsFixture.class")
        readClass(bytes).map: result =>
            val methodsWithThrows = result.symbols.filter: sym =>
                sym.kind == Tasty.SymbolKind.Method &&
                    sym.javaMetadata.map(_.throwsTypes.nonEmpty).getOrElse(false)
            assert(
                methodsWithThrows.nonEmpty,
                s"Expected at least one method with throwsTypes in ThrowsFixture; symbols=${result.symbols.map(s =>
                        s.name.asString + ":" + s.kind
                    ).mkString(", ")}"
            )
            val throwsMethod = methodsWithThrows.find(_.name.asString == "throwsMethod")
            assert(throwsMethod.isDefined, "Expected method named 'throwsMethod' with throws clause")
    }

    // -------------------------------------------------------------------------
    // Test 7: accessFlags for java.lang.String has ACC_FINAL bit set
    // -------------------------------------------------------------------------
    "JavaMetadata.accessFlags for java.lang.String has ACC_FINAL bit set" taggedAs jvmOnly in run {
        val bytes = loadJdkClass("java/lang/String.class")
        readClass(bytes).map: result =>
            val sym = result.classSymbol
            assert(sym.flags.contains(Tasty.Flag.Final), "Expected Flag.Final for java.lang.String")
            val meta = sym.javaMetadata
            assert(meta.isDefined, "Expected javaMetadata Present for java.lang.String")
            assert(
                (meta.get.accessFlags & 0x0010) != 0,
                s"Expected accessFlags to have ACC_FINAL (0x0010) set, got 0x${meta.get.accessFlags.toHexString}"
            )
    }

    // -------------------------------------------------------------------------
    // Test 8: Java record produces Flag.JavaRecord and non-empty recordComponents
    // -------------------------------------------------------------------------
    "Java record class produces Flag.JavaRecord and non-empty recordComponents" taggedAs jvmOnly in run {
        val bytes = loadFixture("PointRecord.class")
        readClass(bytes).map: result =>
            val sym = result.classSymbol
            assert(
                sym.flags.contains(Tasty.Flag.JavaRecord),
                s"Expected Flag.JavaRecord for PointRecord, flags=${sym.flags.bits}"
            )
            val meta = sym.javaMetadata
            assert(meta.isDefined, "Expected javaMetadata Present for PointRecord")
            val components = meta.get.recordComponents
            assert(
                components.nonEmpty,
                s"Expected non-empty recordComponents for PointRecord; got empty"
            )
            val names = components.map(_._1.asString).toList
            assert(
                names.contains("x") && names.contains("y"),
                s"Expected record components 'x' and 'y', got $names"
            )
    }

    // -------------------------------------------------------------------------
    // Test 9: annotations for @Deprecated class contains Retention annotation
    // -------------------------------------------------------------------------
    "JavaMetadata.annotations for java.lang.Deprecated includes @Retention" taggedAs jvmOnly in run {
        // java.lang.Deprecated is annotated with @Retention(RetentionPolicy.RUNTIME)
        // and @Documented. We test that at least one annotation is present with
        // class name containing "Retention" or "Documented".
        val bytes = loadJdkClass("java/lang/Deprecated.class")
        readClass(bytes).map: result =>
            val sym  = result.classSymbol
            val meta = sym.javaMetadata
            assert(meta.isDefined, "Expected javaMetadata Present for java.lang.Deprecated")
            val annotations = meta.get.annotations
            assert(annotations.nonEmpty, s"Expected at least one annotation on java.lang.Deprecated; got none")
            val hasRetention = annotations.exists: ann =>
                ann.annotationClass.name.asString.contains("Retention") ||
                    // plan: phase-02 inline; sym.name.asString used instead of fullName.asString
                    ann.annotationClass.name.asString.contains("Retention")
            assert(
                hasRetention,
                s"Expected @Retention annotation on java.lang.Deprecated; found: ${annotations.map(_.annotationClass.name.asString).mkString(", ")}"
            )
    }

    // -------------------------------------------------------------------------
    // Test 10: enclosingMethod is Present for an anonymous class
    // -------------------------------------------------------------------------
    "JavaMetadata.enclosingMethod is Present for an anonymous class inside enclosingMethodFixture" taggedAs jvmOnly in run {
        // AnonymousFixture$1.class is the anonymous Runnable inside enclosingMethodFixture()
        val bytes = loadFixture("AnonymousFixture$1.class")
        readClass(bytes).map: result =>
            val sym  = result.classSymbol
            val meta = sym.javaMetadata
            assert(meta.isDefined, "Expected javaMetadata Present for AnonymousFixture$1")
            val enclosing = meta.get.enclosingMethod
            assert(enclosing.isDefined, s"Expected enclosingMethod Present for AnonymousFixture$$1; got Absent")
            val methodName = enclosing.get._2.asString
            assert(
                methodName == "enclosingMethodFixture",
                s"Expected enclosingMethod name 'enclosingMethodFixture', got '$methodName'"
            )
    }

end JavaSymbolTest
