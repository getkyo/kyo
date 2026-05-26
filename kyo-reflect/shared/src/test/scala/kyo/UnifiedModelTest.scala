package kyo

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

/** Phase 5b tests for the unified Java/Scala model: SymbolKind matrix and type normalization.
  *
  * Tests 11-18 as specified in execution-plan.md Phase 5b and PHASE-5b-PREP.md §7.3.
  *
  * Tests using JDK classfiles or TASTy fixtures from resources are JVM-only.
  */
class UnifiedModelTest extends Test:

    private val interner = new Interner(numShards = 32, initialShardCapacity = 16)

    /** Load JDK class bytes by binary path. JVM-only. */
    private def loadJdkClass(binaryPath: String): Array[Byte] =
        TestResourceLoader.loadBytes(binaryPath)

    /** Load fixture bytes from test resources (TASTy or .class files). */
    private def loadFixture(name: String): Array[Byte] =
        name match
            case "PlainClass.tasty" => kyo.fixtures.Embedded.plainClassTasty
            case other              => TestResourceLoader.loadBytes(s"/kyo/fixtures/$other")
        end match
    end loadFixture

    private def readClass(binaryPath: String)(using Frame): ClassfileResult < (Sync & Abort[ReflectError]) =
        val bytes = loadJdkClass(binaryPath)
        ClassfileUnpickler.read(bytes, interner, new TypeArena, new ClasspathRef)

    private def readClassBytes(bytes: Array[Byte])(using Frame): ClassfileResult < (Sync & Abort[ReflectError]) =
        ClassfileUnpickler.read(bytes, interner, new TypeArena, new ClasspathRef)

    /** Run TASTy pass 1 on fixture file bytes. Returns symbols from the result. */
    private def tastySymbols(fileName: String)(using Frame): AstUnpickler.Pass1Result < (Sync & Abort[ReflectError]) =
        val bytes = loadFixture(fileName)
        val view  = ByteView(bytes)
        val home  = new ClasspathRef
        val arena = TypeArena.canonical()
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
        yield result
        end for
    end tastySymbols

    // -------------------------------------------------------------------------
    // Test 11: SymbolKind.Package appears for both Java and Scala contexts
    // -------------------------------------------------------------------------
    "SymbolKind.Package: Java classfile owner chain and Scala TASTy both produce Package symbols" taggedAs jvmOnly in run {
        // Java: the class symbol for an inner class has Package symbols in its owner chain.
        val mapEntryBytes = loadJdkClass("java/util/Map$Entry.class")
        readClassBytes(mapEntryBytes).map: javaResult =>
            val javaClassSym = javaResult.classSymbol
            // Walk owner chain to find Package symbols (use eq for reference equality on Symbol)
            def collectOwners(sym: Reflect.Symbol, acc: List[Reflect.Symbol]): List[Reflect.Symbol] =
                if sym == null || sym.owner == null || (sym.owner eq sym) then acc
                else collectOwners(sym.owner, sym.owner :: acc)
            val owners     = collectOwners(javaClassSym, Nil)
            val hasJavaPkg = owners.exists(_.kind == Reflect.SymbolKind.Package)
            assert(
                hasJavaPkg,
                s"Expected a Package symbol in owner chain of java.util.Map.Entry; owners: ${owners.map(o => o.name.asString + ":" + o.kind).mkString(", ")}"
            )
            // Scala: PlainClass.tasty lives in package kyo.fixtures
            tastySymbols("PlainClass.tasty").map: tastyResult =>
                val allSyms     = tastyResult.symbols
                val hasScalaPkg = allSyms.exists(_.kind == Reflect.SymbolKind.Package)
                assert(
                    hasScalaPkg,
                    s"Expected a Package symbol in Scala TASTy symbols; kinds: ${allSyms.map(_.kind).distinct.mkString(", ")}"
                )
    }

    // -------------------------------------------------------------------------
    // Test 12: SymbolKind.Class for Java class and Scala class
    // -------------------------------------------------------------------------
    "SymbolKind.Class appears for Java class and Scala class" taggedAs jvmOnly in run {
        readClass("java/lang/Object.class").map: javaResult =>
            assert(
                javaResult.classSymbol.kind == Reflect.SymbolKind.Class,
                s"Expected Class for java.lang.Object, got ${javaResult.classSymbol.kind}"
            )
            tastySymbols("PlainClass.tasty").map: tastyResult =>
                val scalaClass = tastyResult.symbols.find(_.kind == Reflect.SymbolKind.Class)
                assert(
                    scalaClass.isDefined,
                    s"Expected Class symbol in PlainClass.tasty; kinds: ${tastyResult.symbols.map(_.kind).mkString(", ")}"
                )
    }

    // -------------------------------------------------------------------------
    // Test 13: SymbolKind.Trait for Java interface and Scala trait
    // -------------------------------------------------------------------------
    "SymbolKind.Trait appears for Java interface and Scala trait" taggedAs jvmOnly in run {
        readClass("java/lang/Runnable.class").map: javaResult =>
            assert(
                javaResult.classSymbol.kind == Reflect.SymbolKind.Trait,
                s"Expected Trait for java.lang.Runnable (interface), got ${javaResult.classSymbol.kind}"
            )
            // SomeTrait.tasty is in the kyo-reflect-fixtures module, accessible at /kyo/fixtures/SomeTrait.tasty
            tastySymbols("SomeTrait.tasty").map: tastyResult =>
                val scalaTrait = tastyResult.symbols.find(_.kind == Reflect.SymbolKind.Trait)
                assert(
                    scalaTrait.isDefined,
                    s"Expected Trait symbol in SomeTrait.tasty; kinds: ${tastyResult.symbols.map(_.kind).mkString(", ")}"
                )
    }

    // -------------------------------------------------------------------------
    // Test 14: SymbolKind.Object appears ONLY for Scala object; no Java symbol has Object kind
    // -------------------------------------------------------------------------
    "SymbolKind.Object appears only for Scala object; no Java symbol has Object kind" taggedAs jvmOnly in run {
        readClass("java/lang/Object.class").map: javaObjectResult =>
            // java.lang.Object is a Class, not a Scala Object
            assert(
                javaObjectResult.classSymbol.kind != Reflect.SymbolKind.Object,
                "java.lang.Object should have kind=Class, not Object"
            )
            // java.lang.System has static fields but no kind=Object members
            readClass("java/lang/System.class").map: javaSystemResult =>
                val javaSyms        = javaSystemResult.classSymbol :: javaSystemResult.symbols.toList
                val javaObjectKinds = javaSyms.filter(_.kind == Reflect.SymbolKind.Object)
                assert(
                    javaObjectKinds.isEmpty,
                    s"Expected no Java symbols with kind=Object, found: ${javaObjectKinds.map(_.name.asString).mkString(", ")}"
                )
                // SomeObject.tasty has kind=Object for the module symbol
                tastySymbols("SomeObject.tasty").map: tastyResult =>
                    val scalaObject = tastyResult.symbols.find(_.kind == Reflect.SymbolKind.Object)
                    assert(
                        scalaObject.isDefined,
                        s"Expected Object symbol in SomeObject.tasty; kinds: ${tastyResult.symbols.map(_.kind).distinct.mkString(", ")}"
                    )
    }

    // -------------------------------------------------------------------------
    // Test 15: TypeAlias, OpaqueType, AbstractType appear only for TASTy-sourced symbols
    // -------------------------------------------------------------------------
    "TypeAlias, OpaqueType, AbstractType appear only in TASTy-sourced symbols" taggedAs jvmOnly in run {
        // No Java classfile should produce these kinds
        readClass("java/lang/Object.class").map: javaResult =>
            val allJavaSyms    = javaResult.classSymbol :: javaResult.symbols.toList
            val scalaOnlyKinds = Set(Reflect.SymbolKind.TypeAlias, Reflect.SymbolKind.OpaqueType, Reflect.SymbolKind.AbstractType)
            val badJavaSyms    = allJavaSyms.filter(s => scalaOnlyKinds.contains(s.kind))
            assert(
                badJavaSyms.isEmpty,
                s"Unexpected Scala-only kinds in Java classfile: ${badJavaSyms.map(s => s.name.asString + ":" + s.kind).mkString(", ")}"
            )

            // TASTy fixtures do produce these kinds.
            // FixtureClasses$package.tasty contains both TypeAlias and OpaqueType at top level.
            // Container.tasty contains AbstractType (trait Container { type Item }).
            tastySymbols("FixtureClasses$package.tasty").map: tastyResult =>
                val allTastySyms = tastyResult.symbols
                val hasTypeAlias = allTastySyms.exists(_.kind == Reflect.SymbolKind.TypeAlias)
                assert(
                    hasTypeAlias,
                    s"Expected TypeAlias in FixtureClasses package; kinds: ${allTastySyms.map(_.kind).distinct.mkString(", ")}"
                )

                val hasOpaque = allTastySyms.exists(_.kind == Reflect.SymbolKind.OpaqueType)
                assert(
                    hasOpaque,
                    s"Expected OpaqueType in FixtureClasses package; kinds: ${allTastySyms.map(_.kind).distinct.mkString(", ")}"
                )

                tastySymbols("Container.tasty").map: containerResult =>
                    val hasAbstract = containerResult.symbols.exists(_.kind == Reflect.SymbolKind.AbstractType)
                    assert(
                        hasAbstract,
                        s"Expected AbstractType in Container.tasty; kinds: ${containerResult.symbols.map(_.kind).distinct.mkString(", ")}"
                    )
    }

    // -------------------------------------------------------------------------
    // Test 16: Type.Array is decoded from a Java record with int[] component
    // -------------------------------------------------------------------------
    "Type.Array is decoded from ArrayRecord classfile: record component 'values' has type Type.Array" in run {
        // ArrayRecord is a Java record with a single int[] component named "values".
        // The classfile bytes are embedded cross-platform in Embedded.arrayRecordClass.
        // ClassfileUnpickler.buildRecordComponents calls parseErasedDescriptorType which
        // must produce Type.Array for the "[I" descriptor of the int[] field.
        readClassBytes(kyo.fixtures.Embedded.arrayRecordClass).map: result =>
            val components = result.classSymbol.javaSpecific
                .map(_.recordComponents)
                .getOrElse(Chunk.empty)
            assert(
                components.nonEmpty,
                s"Expected non-empty recordComponents for ArrayRecord; got empty. classSymbol=${result.classSymbol.name.asString}"
            )
            val valuesComponent = components.find(_._1.asString == "values")
            assert(
                valuesComponent.isDefined,
                s"Expected component named 'values' in ArrayRecord; components: ${components.map(_._1.asString).mkString(", ")}"
            )
            val (_, tpe) = valuesComponent.get
            tpe match
                case Reflect.Type.Array(_) =>
                    succeed
                case other =>
                    fail(s"Expected Type.Array for 'values' component, got $other")
            end match
    }

    // -------------------------------------------------------------------------
    // Test 17: Scala case class has Flag.Case
    // -------------------------------------------------------------------------
    "a Scala case class decoded from TASTy has flags.contains(Flag.Case)" taggedAs jvmOnly in run {
        tastySymbols("SomeCaseClass.tasty").map: result =>
            val caseClass = result.symbols.find: sym =>
                sym.kind == Reflect.SymbolKind.Class && sym.flags.contains(Reflect.Flag.Case)
            assert(
                caseClass.isDefined,
                s"Expected a Class symbol with Flag.Case in SomeCaseClass.tasty; symbols: ${result.symbols.map(s =>
                        s.name.asString + ":" + s.kind + "[case=" + s.flags.contains(Reflect.Flag.Case) + "]"
                    ).mkString(", ")}"
            )
    }

    // -------------------------------------------------------------------------
    // Test 18: Full SymbolKind matrix coverage (all 13 non-Unresolved kinds present)
    // -------------------------------------------------------------------------
    "full SymbolKind matrix: each non-Unresolved kind has at least one symbol in fixtures" taggedAs jvmOnly in run {
        // Collect kinds from a classfile result (classSymbol + members + owner chain)
        def kindsFromClassResult(r: ClassfileResult): Set[Reflect.SymbolKind] =
            val buf = scala.collection.mutable.Set[Reflect.SymbolKind]()
            buf += r.classSymbol.kind
            r.symbols.toList.foreach(s => buf += s.kind)
            var cur = r.classSymbol.owner
            while cur != null && !(cur.owner eq cur) && cur.owner != null do
                buf += cur.kind
                cur = cur.owner
            end while
            if cur != null then buf += cur.kind
            buf.toSet
        end kindsFromClassResult

        def kindsFromTastyResult(r: AstUnpickler.Pass1Result): Set[Reflect.SymbolKind] =
            r.symbols.toList.map(_.kind).toSet

        for
            objResult    <- readClass("java/lang/Object.class")
            runnableRes  <- readClass("java/lang/Runnable.class")
            systemRes    <- readClass("java/lang/System.class")
            stringRes    <- readClass("java/lang/String.class")
            sbRes        <- readClass("java/lang/AbstractStringBuilder.class")
            someObjRes   <- tastySymbols("SomeObject.tasty")
            pkgRes       <- tastySymbols("FixtureClasses$package.tasty")
            containerRes <- tastySymbols("Container.tasty")
            genericRes   <- tastySymbols("GenericBox.tasty")
            plainRes     <- tastySymbols("PlainClass.tasty")
        yield
            val foundKinds: Set[Reflect.SymbolKind] =
                kindsFromClassResult(objResult) ++
                    kindsFromClassResult(runnableRes) ++
                    kindsFromClassResult(systemRes) ++
                    kindsFromClassResult(stringRes) ++
                    kindsFromClassResult(sbRes) ++
                    kindsFromTastyResult(someObjRes) ++
                    kindsFromTastyResult(pkgRes) ++
                    kindsFromTastyResult(containerRes) ++
                    kindsFromTastyResult(genericRes) ++
                    kindsFromTastyResult(plainRes)

            val expectedKinds = Set[Reflect.SymbolKind](
                Reflect.SymbolKind.Package,
                Reflect.SymbolKind.Class,
                Reflect.SymbolKind.Trait,
                Reflect.SymbolKind.Object,
                Reflect.SymbolKind.Method,
                Reflect.SymbolKind.Field,
                Reflect.SymbolKind.Val,
                Reflect.SymbolKind.Var,
                Reflect.SymbolKind.TypeAlias,
                Reflect.SymbolKind.OpaqueType,
                Reflect.SymbolKind.AbstractType,
                Reflect.SymbolKind.TypeParam,
                Reflect.SymbolKind.Parameter
            )
            val missing = expectedKinds -- foundKinds
            assert(missing.isEmpty, s"Missing SymbolKind coverage: $missing. Found: $foundKinds")
        end for
    }

end UnifiedModelTest
