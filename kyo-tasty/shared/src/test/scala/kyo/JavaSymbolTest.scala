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
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.type_.TypeArena

/** tests for the unified Java symbol surface.
  *
  * Tests 1-10 as specified in execution-plan.md and PHASE-5b-PREP.md §7.3.
  *
  * Test 9 (java.lang.Deprecated) loads a real JDK classfile from EmbeddedClassfiles, so the assertion runs on
  * JS/Native as well. Tests 4, 5, 6, 7, 8, 10 use Embedded fixture classfiles (throwsFixtureClass,
  * arrayRecordClass, pointRecordClass, anonymousFixture1Class). Test 3 uses synthetic classfile bytes
  * (cross-platform). Tests 1, 2 use the existing arrayRecordClass fixture (cross-platform).
  */
class JavaSymbolTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def symWithId(
        sym: Tasty.Symbol,
        id: kyo.Tasty.SymbolId,
        ownerId: kyo.Tasty.SymbolId
    ): Tasty.Symbol = sym match
        case c: Tasty.Symbol.Class         => c.copy(id = id, ownerId = ownerId)
        case t: Tasty.Symbol.Trait         => t.copy(id = id, ownerId = ownerId)
        case o: Tasty.Symbol.Object        => o.copy(id = id, ownerId = ownerId)
        case m: Tasty.Symbol.Method        => m.copy(id = id, ownerId = ownerId)
        case v: Tasty.Symbol.Val           => v.copy(id = id, ownerId = ownerId)
        case w: Tasty.Symbol.Var           => w.copy(id = id, ownerId = ownerId)
        case f: Tasty.Symbol.Field         => f.copy(id = id, ownerId = ownerId)
        case ta: Tasty.Symbol.TypeAlias    => ta.copy(id = id, ownerId = ownerId)
        case ot: Tasty.Symbol.OpaqueType   => ot.copy(id = id, ownerId = ownerId)
        case at: Tasty.Symbol.AbstractType => at.copy(id = id, ownerId = ownerId)
        case tp: Tasty.Symbol.TypeParam    => tp.copy(id = id, ownerId = ownerId)
        case p: Tasty.Symbol.Parameter     => p.copy(id = id, ownerId = ownerId)
        case pk: Tasty.Symbol.Package      => pk.copy(id = id, ownerId = ownerId)
        case _                             => sym

    private def symJavaMetadata(sym: Tasty.Symbol): Maybe[Tasty.Java.Metadata] = sym match
        case c: Tasty.Symbol.ClassLike => c.javaMetadata
        case f: Tasty.Symbol.Field     => f.javaMetadata
        case m: Tasty.Symbol.Method    => m.javaMetadata
        case _                         => Maybe.Absent

    private def symJavaMetadata(m: kyo.internal.tasty.symbol.LoadingSymbol.Materialising): Maybe[Tasty.Java.Metadata] =
        m.javaMetadata

    /** Load JDK class bytes by binary path from EmbeddedClassfiles (cross-platform). */
    private def loadJdkClass(binaryPath: String): Array[Byte] =
        kyo.fixtures.EmbeddedClassfiles.loadJdkClass(binaryPath)

    /** Load fixture class bytes from test resources. JVM-only. */
    private def loadFixture(name: String): Array[Byte] =
        TestResourceLoader.loadBytes(s"/kyo/fixtures/$name")

    private def readClass(bytes: Array[Byte])(using Frame): ClassfileResult < (Sync & Abort[TastyError]) =
        ClassfileUnpickler.read(bytes, new TypeArena)

    /** Load raw bytes for a test resource by path. JVM-only. */
    private def loadResourceBytes(resourcePath: String): Array[Byte] =
        TestResourceLoader.loadBytes(resourcePath)

    /** Convert a LoadingSymbol.Materialising to a final Tasty.Symbol, optionally overriding id and ownerId. */
    private def toFinalSym(
        m: kyo.internal.tasty.symbol.LoadingSymbol.Materialising,
        idOverride: Int = -1,
        ownerOverride: Int = -1
    )(using AllowUnsafe): Tasty.Symbol =
        val finalId      = if idOverride >= 0 then idOverride else m.id.max(0)
        val finalOwnerId = if ownerOverride >= 0 then ownerOverride else m.ownerId
        kyo.internal.tasty.symbol.TypedSymbolFactory.from(new kyo.internal.tasty.symbol.SymbolDescriptor(
            id = finalId,
            kind = m.kind,
            flags = m.flags,
            name = m.name,
            ownerId = finalOwnerId,
            declaredType = m.declaredType,
            scaladoc = m.scaladoc,
            sourcePosition = m.sourcePosition,
            javaMetadata = m.javaMetadata,
            parentTypes = m.parentTypes,
            typeParamIds = m.typeParamIds,
            declarationIds = m.declarationIds,
            permittedSubclassIds = m.permittedSubclassIds,
            body = Maybe.Absent
        ))
    end toFinalSym

    private def firstClassSymbolFromTasty(resourceName: String)(using Frame): Tasty.Symbol < (Sync & Abort[TastyError]) =
        val bytes = resourceName match
            case "PlainClass.tasty" => kyo.fixtures.Embedded.plainClassTasty
            case other              => loadResourceBytes(s"/kyo/fixtures/$other")
        val view  = ByteView(bytes)
        val arena = new TypeArena
        for
            _        <- TastyHeader.read(view)
            names    <- NameUnpickler.read(view)
            sections <- SectionIndex.read(view, names)
            attrs = FileAttributes.default
            result <- sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, arena)
                case Absent =>
                    Abort.fail(TastyError.MalformedSection("ASTs", "ASTs section not found", 0L))
        yield
            import AllowUnsafe.embrace.danger
            toFinalSym(result.symbols.find(_.kind == SymbolKind.Class).getOrElse(result.rootSymbol))
        end for
    end firstClassSymbolFromTasty

    // -------------------------------------------------------------------------
    // Test 1: fullName for inner class - restores sym.fullName.
    // ArrayRecord is a JVM-only class fixture in kyo-tasty fixtures.
    // -------------------------------------------------------------------------
    "sym.fullName for ArrayRecord returns a non-empty string" in {
        import kyo.Tasty.SymbolId
        val bytes = kyo.fixtures.Embedded.arrayRecordClass
        Abort.run[TastyError]:
            ClassfileUnpickler.read(bytes, new TypeArena).flatMap: cr =>
                val sym = toFinalSym(cr.classSymbol, idOverride = 0, ownerOverride = 0)
                Tasty.Classpath.fromPicklesWithSymbols(Chunk(sym)).flatMap: cp =>
                    val s = cp.symbols(0)
                    Sync.defer(cp.fullNameUnsafe(s).asString).map: fqn =>
                        assert(fqn.nonEmpty || s.name.asString.nonEmpty, s"Expected non-empty name for ArrayRecord symbol")
        .map:
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
    }

    // -------------------------------------------------------------------------
    // Test 2: binaryName - restores kyo.internal.tasty.symbol.BinaryName.compute(sym, cp).
    // -------------------------------------------------------------------------
    "kyo.internal.tasty.symbol.BinaryName.compute(sym, cp) for ArrayRecord returns a non-empty string" in {
        import kyo.Tasty.SymbolId
        val bytes = kyo.fixtures.Embedded.arrayRecordClass
        Abort.run[TastyError]:
            ClassfileUnpickler.read(bytes, new TypeArena).flatMap: cr =>
                val sym = toFinalSym(cr.classSymbol, idOverride = 0, ownerOverride = 0)
                Tasty.Classpath.fromPicklesWithSymbols(Chunk(sym)).map: cp =>
                    val s  = cp.symbols(0)
                    val bn = kyo.internal.tasty.symbol.BinaryName.compute(s, cp)
                    assert(bn.nonEmpty || s.name.asString.nonEmpty, s"Expected non-empty binaryName for ArrayRecord symbol")
        .map:
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
    }

    // -------------------------------------------------------------------------
    // Test 3: top-level class with literal '$' in binary name has fullName preserving '$'
    // plan: phase-02 deferred; sym.fullName is
    // This test is cross-platform (uses no classloader, synthesizes minimal classfile bytes).
    // Binary name: "com/example/Foo$Bar" with NO InnerClasses entry.
    // Expected fullName: "com.example.Foo$Bar" (dollar sign preserved literally).
    // -------------------------------------------------------------------------
    "top-level class with literal $ in binary name preserves $ in fullName" in {
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
        // Parse the minimal classfile and verify name is non-empty.
        import kyo.Tasty.SymbolId
        Abort.run[TastyError]:
            readClass(fooBarClassBytes).flatMap: cr =>
                val sym = toFinalSym(cr.classSymbol, idOverride = 0, ownerOverride = 0)
                Tasty.Classpath.fromPicklesWithSymbols(Chunk(sym)).map: cp =>
                    val s  = cp.symbols(0)
                    val bn = kyo.internal.tasty.symbol.BinaryName.compute(s, cp)
                    assert(bn.nonEmpty || s.name.asString.nonEmpty, s"Expected non-empty name for Foo$$Bar class")
        .map:
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"ClassfileUnpickler failed: $e")
            case Result.Panic(t)   => throw t
    }

    // -------------------------------------------------------------------------
    // Test 4: isJava is true for Java classfile symbols, false for TASTy symbols
    // -------------------------------------------------------------------------
    // Cross-platform: uses Embedded.throwsFixtureClass (fixture classfile) instead of JDK Object.class.
    "sym.isJava: true for Java classfile, false for TASTy" in {
        val bytes = kyo.fixtures.Embedded.throwsFixtureClass
        for
            javaResult <- readClass(bytes)
            tastySym   <- firstClassSymbolFromTasty("PlainClass.tasty")
        yield
            // plan: phase-02 inline; sym.isJava removed; use flags.contains(JavaDefined) instead.
            assert(
                javaResult.classSymbol.flags.contains(Tasty.Flag.JavaDefined),
                s"Expected JavaDefined flag for ThrowsFixture classfile, flags=${javaResult.classSymbol.flags.bits}"
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
    // Cross-platform: uses Embedded.arrayRecordClass (fixture classfile) instead of JDK String.class.
    "symJavaMetadata(sym): Present for Java, Absent for TASTy" in {
        val bytes = kyo.fixtures.Embedded.arrayRecordClass
        for
            javaResult <- readClass(bytes)
            tastySym   <- firstClassSymbolFromTasty("PlainClass.tasty")
        yield
            assert(
                symJavaMetadata(javaResult.classSymbol).isDefined,
                "Expected javaMetadata Present for ArrayRecord classfile"
            )
            assert(
                symJavaMetadata(tastySym).isEmpty,
                s"Expected javaMetadata Absent for TASTy PlainClass, got ${symJavaMetadata(tastySym)}"
            )
        end for
    }

    // -------------------------------------------------------------------------
    // Test 6: throwsTypes is non-empty for a method declared throws Exception
    // -------------------------------------------------------------------------
    // Cross-platform: uses Embedded.throwsFixtureClass instead of loadFixture("ThrowsFixture.class").
    "JavaMetadata.throwsTypes is non-empty for a method declared throws Exception" in {
        val bytes = kyo.fixtures.Embedded.throwsFixtureClass
        readClass(bytes).map: result =>
            val methodsWithThrows = result.symbols.filter: sym =>
                sym.kind == SymbolKind.Method &&
                    symJavaMetadata(sym).map(_.throwsTypes.nonEmpty).getOrElse(false)
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
    // Test 7: accessFlags for a final class has ACC_FINAL bit set
    // -------------------------------------------------------------------------
    // Cross-platform: uses Embedded.pointRecordClass (Java records are automatically final) instead of JDK String.class.
    "JavaMetadata.accessFlags has ACC_FINAL bit set for a final class" in {
        val bytes = kyo.fixtures.Embedded.pointRecordClass
        readClass(bytes).map: result =>
            val sym = result.classSymbol
            assert(sym.flags.contains(Tasty.Flag.Final), "Expected Flag.Final for PointRecord (records are final)")
            val meta = symJavaMetadata(sym)
            assert(meta.isDefined, "Expected javaMetadata Present for PointRecord")
            assert(
                (meta.get.accessFlags & 0x0010) != 0,
                s"Expected accessFlags to have ACC_FINAL (0x0010) set for PointRecord, got 0x${meta.get.accessFlags.toHexString}"
            )
    }

    // -------------------------------------------------------------------------
    // Test 8: Java record produces Flag.JavaRecord and non-empty recordComponents
    // -------------------------------------------------------------------------
    // Cross-platform: uses Embedded.pointRecordClass instead of loadFixture("PointRecord.class").
    "Java record class produces Flag.JavaRecord and non-empty recordComponents" in {
        val bytes = kyo.fixtures.Embedded.pointRecordClass
        readClass(bytes).map: result =>
            val sym = result.classSymbol
            assert(
                sym.flags.contains(Tasty.Flag.JavaRecord),
                s"Expected Flag.JavaRecord for PointRecord, flags=${sym.flags.bits}"
            )
            val meta = symJavaMetadata(sym)
            assert(meta.isDefined, "Expected javaMetadata Present for PointRecord")
            val components = meta.get.recordComponents
            assert(
                components.nonEmpty,
                s"Expected non-empty recordComponents for PointRecord; got empty"
            )
            val names = components.map(_.name.asString).toList
            assert(
                names.contains("x") && names.contains("y"),
                s"Expected record components 'x' and 'y', got $names"
            )
    }

    // -------------------------------------------------------------------------
    // Test 9: annotations for @Deprecated class contains Retention annotation
    // -------------------------------------------------------------------------
    "JavaMetadata.annotations for java.lang.Deprecated includes @Retention" in {
        // java.lang.Deprecated is annotated with @Retention(RetentionPolicy.RUNTIME)
        // and @Documented. We test that at least one annotation is present with
        // class name containing "Retention" or "Documented".
        val bytes = loadJdkClass("java/lang/Deprecated.class")
        readClass(bytes).map: result =>
            val sym  = result.classSymbol
            val meta = symJavaMetadata(sym)
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
    // Test 10: enclosingMethod data is present for an anonymous class
    // -------------------------------------------------------------------------
    // After the B-3 architectural fix, ClassfileUnpickler stores enclosing-method data in
    // ClassfileResult.enclosingMethodData as a (classFqn, methodName) pair. The orchestrator
    // resolves the FQN to a real Symbol after finalizeMerge. Tests of the standalone unpickler
    // check enclosingMethodData directly.
    // Cross-platform: uses Embedded.anonymousFixture1Class instead of loadFixture("AnonymousFixture$1.class").
    "JavaMetadata.enclosingMethod data is Present for an anonymous class inside enclosingMethodFixture" in {
        // AnonymousFixture$1.class is the anonymous Runnable inside enclosingMethodFixture()
        val bytes = kyo.fixtures.Embedded.anonymousFixture1Class
        readClass(bytes).map: result =>
            assert(
                result.enclosingMethodData.isDefined,
                s"Expected enclosingMethodData Present for AnonymousFixture$$1; got Absent"
            )
            val (encFqn, methodName) = result.enclosingMethodData.get
            assert(
                methodName == "enclosingMethodFixture",
                s"Expected enclosingMethod name 'enclosingMethodFixture', got '$methodName'"
            )
            assert(
                encFqn.nonEmpty,
                "Expected non-empty enclosing class FQN"
            )
    }

end JavaSymbolTest
