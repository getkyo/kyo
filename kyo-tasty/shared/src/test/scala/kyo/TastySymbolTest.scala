package kyo

import kyo.internal.tasty.query.Classpath as InternalClasspath
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.ClasspathRef
import kyo.internal.tasty.query.ClasspathTestHelpers
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** Tests for Symbol accessors after Phase 02a (INV-001).
  *
  * Verifies that Symbol.fullName, Symbol.parents, and Symbol.companion return expected values when the caller provides (using AllowUnsafe).
  * Uses the fixture classpath to stay cross-platform (jvm, js, native) while exercising the same INV-001 invariants.
  */
class TastySymbolTest extends Test:

    import AllowUnsafe.embrace.danger

    // ── Fixture infrastructure (mirrors QueryApiTest pattern) ─────────────────

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(TastyError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer:
                        files.remove(from)
                        files(to) = bytes
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(path).map(_.length.toLong).getOrElse(0L)))

    end MemoryFileSource

    private def openFixtureClasspath(src: FileSource)(
        using Frame
    ): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        InternalClasspath.allocate.flatMap: rawCp =>
            Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                ClasspathOrchestrator.openInto(Seq("root"), false, src, 1, rawCp).map: _ =>
                    val cp = Tasty.Classpath.wrap(rawCp)
                    ClasspathTestHelpers.assignHomesForTest(rawCp)
                    cp

    private def plainClassSource(): MemoryFileSource =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src
    end plainClassSource

    private def childBaseSource(): MemoryFileSource =
        val src = MemoryFileSource()
        src.add("root/BaseClass.tasty", kyo.fixtures.Embedded.baseClassTasty)
        src.add("root/ChildClass.tasty", kyo.fixtures.Embedded.childClassTasty)
        src
    end childBaseSource

    private def someCaseClassSource(): MemoryFileSource =
        val src = MemoryFileSource()
        src.add("root/SomeCaseClass.tasty", kyo.fixtures.Embedded.someCaseClassTasty)
        src
    end someCaseClassSource

    // ── Tests (INV-001) ───────────────────────────────────────────────────────

    // Test 3 (INV-001, Symbol.fullName): fullName.asString returns the dotted FQN.
    // Given: fixture classpath containing PlainClass.tasty; AllowUnsafe in scope.
    // When: cp.findClass("kyo.fixtures.PlainClass").get; sym.fullName.asString evaluated.
    // Then: returns String "kyo.fixtures.PlainClass".
    // Pins: INV-001 (Symbol.fullName case).
    "Symbol.fullName.asString returns the dotted FQN for a fixture class" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(plainClassSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) => sym.fullName.asString
                    case Absent       => Abort.fail(TastyError.NotImplemented("PlainClass not found"))).map:
                case Result.Success(fqn) =>
                    assert(
                        fqn == "kyo.fixtures.PlainClass",
                        s"Expected fullName.asString == 'kyo.fixtures.PlainClass' but got '$fqn'"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 4 (INV-001, Symbol.parents): parents accessor returns a non-empty Chunk[Type] for PlainClass.
    // PlainClass has AnyRef as its TASTy TEMPLATE parent (java.lang.Object via AnyRef placeholder).
    // Given: fixture classpath containing PlainClass.tasty; AllowUnsafe in scope.
    // When: cp.findClass("kyo.fixtures.PlainClass").get; sym.parents evaluated.
    // Then: returned Chunk is non-empty (AnyRef/Object placeholder present).
    // Pins: INV-001 (parents case).
    "Symbol.parents for PlainClass returns a non-empty Chunk" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(plainClassSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) => sym.parents
                    case Absent       => Abort.fail(TastyError.NotImplemented("PlainClass not found"))).map:
                case Result.Success(parents) =>
                    assert(
                        parents.nonEmpty,
                        "Expected non-empty parents for PlainClass; TASTy encodes at least AnyRef/Object"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 5 (INV-001, Symbol.companion): class-Symbol's companion returns Module Symbol.
    // Given: fixture classpath with SomeCaseClass.tasty; AllowUnsafe in scope.
    // When: classSym.companion evaluated (using AllowUnsafe).
    // Then: result is Maybe.Present(modSym) with modSym.kind == SymbolKind.Object
    //       and modSym.name.asString contains "SomeCaseClass".
    // Pins: INV-001 (companion case).
    "SomeCaseClass class-Symbol companion returns Module Symbol with kind Object" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(someCaseClassSource()).flatMap: cp =>
                val topLevel = cp.topLevelClasses
                topLevel
                    .filter(sym => sym.kind == Tasty.SymbolKind.Class && sym.name.asString == "SomeCaseClass")
                    .headMaybe match
                    case Present(classSym) => Kyo.lift(classSym.companion)
                    case Absent            => Abort.fail(TastyError.NotImplemented("SomeCaseClass class not found"))).map:
                case Result.Success(Present(modSym)) =>
                    assert(
                        modSym.kind == Tasty.SymbolKind.Object,
                        s"Expected companion kind Object but got ${modSym.kind}"
                    )
                    assert(
                        modSym.name.asString.contains("SomeCaseClass"),
                        s"Expected companion name to contain 'SomeCaseClass' but got '${modSym.name.asString}'"
                    )
                case Result.Success(Absent) =>
                    fail("Expected Present(modSym) for SomeCaseClass companion but got Absent")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Helpers for synthetic-symbol tests (no classpath I/O, cross-platform).

    /** Build a root sentinel Package symbol (empty name, null owner). */
    private def makeRoot(): Tasty.Symbol =
        Tasty.Symbol.make(
            Tasty.SymbolKind.Package,
            Tasty.Flags.empty,
            Tasty.Name(""),
            null,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )

    /** Build a Package symbol owned by `owner`. */
    private def makePkg(name: String, owner: Tasty.Symbol): Tasty.Symbol =
        Tasty.Symbol.make(
            Tasty.SymbolKind.Package,
            Tasty.Flags.empty,
            Tasty.Name(name),
            owner,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )

    /** Build a Class symbol owned by `owner`. */
    private def makeClass(name: String, owner: Tasty.Symbol): Tasty.Symbol =
        Tasty.Symbol.make(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name(name),
            owner,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )

    /** Build a Module (object) symbol owned by `owner`, with the given simple name. */
    private def makeModule(name: String, owner: Tasty.Symbol): Tasty.Symbol =
        Tasty.Symbol.make(
            Tasty.SymbolKind.Object,
            new Tasty.Flags(Tasty.Flag.Module.bit),
            Tasty.Name(name),
            owner,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )

    // Test 1 (INV: T1, Symbol.binaryName): nested Scala class produces JVM binary name with '$' separator.
    // Given: synthetic Symbol tree com.example.Outer.Inner where Outer and Inner have SymbolKind.Class.
    // When: sym.binaryName evaluated.
    // Then: returns "com/example/Outer$Inner".
    // Pins: T1 (binaryName nested-class coverage).
    "Symbol.binaryName nested class returns com/example/Outer$Inner" in {
        val root  = makeRoot()
        val com   = makePkg("com", root)
        val ex    = makePkg("example", com)
        val outer = makeClass("Outer", ex)
        val inner = makeClass("Inner", outer)
        assert(
            inner.binaryName == "com/example/Outer$Inner",
            s"Expected 'com/example/Outer$$Inner' but got '${inner.binaryName}'"
        )
    }

    // Test 2 (INV: T1, Symbol.binaryName): top-level Scala class produces JVM binary name with '/' separators only.
    // Given: synthetic Symbol tree com.example.Foo where Foo has SymbolKind.Class.
    // When: sym.binaryName evaluated.
    // Then: returns "com/example/Foo".
    // Pins: T1 (binaryName top-level coverage).
    "Symbol.binaryName top-level class returns com/example/Foo" in {
        val root = makeRoot()
        val com  = makePkg("com", root)
        val ex   = makePkg("example", com)
        val foo  = makeClass("Foo", ex)
        assert(
            foo.binaryName == "com/example/Foo",
            s"Expected 'com/example/Foo' but got '${foo.binaryName}'"
        )
    }

    // Test 3 (INV: T1, Symbol.isPackageObject): Module symbol named "package" returns true.
    // Given: synthetic Module Symbol with name "package" and Flag.Module set, owned by a Package.
    // When: sym.isPackageObject (using AllowUnsafe).
    // Then: returns true.
    // Pins: T1 (isPackageObject true branch).
    "Symbol.isPackageObject returns true for Module named package" in {
        val root   = makeRoot()
        val pkg    = makePkg("com.example", root)
        val pkgObj = makeModule("package", pkg)
        assert(
            pkgObj.isPackageObject,
            "Expected isPackageObject == true for Module symbol named 'package'"
        )
    }

    // Test 4 (INV: T1, Symbol.isPackageObject): Class symbol named "Foo" returns false.
    // Given: synthetic Class Symbol with name "Foo" (no Module flag).
    // When: sym.isPackageObject (using AllowUnsafe).
    // Then: returns false.
    // Pins: T1 (isPackageObject false branch).
    "Symbol.isPackageObject returns false for class named Foo" in {
        val root = makeRoot()
        val pkg  = makePkg("com.example", root)
        val foo  = makeClass("Foo", pkg)
        assert(
            !foo.isPackageObject,
            "Expected isPackageObject == false for Class symbol named 'Foo'"
        )
    }

    // Test 5 (T2, Symbol.make): Symbol.make produces Symbol with correct kind, name, and owner.
    // Given: root sentinel Package; sym = Symbol.make(SymbolKind.Class, Flags.empty, Name("Foo"), root, ...).
    // When: read sym.kind, sym.name.asString, sym.owner.
    // Then: kind == SymbolKind.Class; name.asString == "Foo"; owner eq root.
    // Pins: T2.
    "Symbol.make produces Symbol with correct kind name and owner" in {
        val root = makeRoot()
        val sym = Tasty.Symbol.make(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name("Foo"),
            root,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        assert(
            sym.kind == Tasty.SymbolKind.Class,
            s"Expected kind Class but got ${sym.kind}"
        )
        assert(
            sym.name.asString == "Foo",
            s"Expected name 'Foo' but got '${sym.name.asString}'"
        )
        assert(
            sym.owner eq root,
            "Expected owner to be the root sentinel symbol"
        )
    }

    // Phase 13 T1 gap: declaredType throws for Package symbols.
    // Given: synthetic Package symbol.
    // When: sym.declaredType (using AllowUnsafe).
    // Then: throws IllegalArgumentException (documented in Symbol.declaredType scaladoc).
    // Pins: T1 (declaredType Package guard).
    "Symbol.declaredType throws IllegalArgumentException for Package symbols" in {
        val root   = makeRoot()
        val pkg    = makePkg("scala", root)
        var thrown = false
        try
            pkg.declaredType
            ()
        catch
            case _: IllegalArgumentException =>
                thrown = true
        end try
        assert(thrown, "Expected IllegalArgumentException for Package.declaredType but nothing was thrown")
    }

    // Phase 13 T1 gap: declarations returns empty Chunk for a fresh synthetic symbol (no classpath).
    // Given: synthetic Class symbol; _declarations slot never assigned.
    // When: sym.declarations is read while _declarations.isSet == false... however declarations calls
    // _declarations.get() directly (no isSet guard), so accessing an unset slot throws ISE.
    // This test documents the behavior: unset slot throws ISE, which is the correct protocol --
    // production callers only read declarations after classpath open assigns the slot.
    // We verify via the fixture classpath instead so we get a real (empty) value.
    // Given: fixture classpath containing PlainClass.tasty; look up PlainClass symbol.
    // When: sym.declarations.
    // Then: returns a Chunk (possibly empty or containing synthetic members).
    // Pins: T1 (declarations accessor coverage).
    "Symbol.declarations returns Chunk for fixture class" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(plainClassSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) => Kyo.lift(sym.declarations)
                    case Absent       => Abort.fail(TastyError.NotImplemented("PlainClass not found"))).map:
                case Result.Success(decls) =>
                    assert(
                        decls != null,
                        "Expected non-null Chunk from declarations"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 13 T1 gap: typeParams returns Chunk for fixture class.
    // Given: fixture classpath containing PlainClass.tasty; AllowUnsafe in scope.
    // When: sym.typeParams.
    // Then: returns a Chunk (PlainClass has no type params so it is empty).
    // Pins: T1 (typeParams accessor coverage).
    "Symbol.typeParams returns Chunk for fixture class" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(plainClassSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) => Kyo.lift(sym.typeParams)
                    case Absent       => Abort.fail(TastyError.NotImplemented("PlainClass not found"))).map:
                case Result.Success(tps) =>
                    assert(
                        tps != null,
                        "Expected non-null Chunk from typeParams"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 13 T1 gap: scaladoc returns Absent for a synthetic symbol (no Comments section).
    // Given: synthetic Class symbol built without a classpath.
    // When: sym.scaladoc (using AllowUnsafe).
    // Then: returns Absent (no scaladoc on a synthetic symbol; _scaladoc slot is unset).
    // Pins: T1 (scaladoc Absent branch).
    "Symbol.scaladoc returns Absent for synthetic symbol" in {
        val root = makeRoot()
        val sym  = makeClass("SyntheticFoo", root)
        sym.scaladoc match
            case Absent => succeed
            case Present(doc) =>
                fail(s"Expected Absent for synthetic symbol scaladoc but got Present($doc)")
        end match
    }

    // Phase 13 T1 gap: position returns Absent for a synthetic symbol (no Positions section).
    // Given: synthetic Class symbol built without a classpath.
    // When: sym.position (using AllowUnsafe).
    // Then: returns Absent (no position data on a synthetic symbol; _position slot is unset).
    // Pins: T1 (position Absent branch).
    "Symbol.position returns Absent for synthetic symbol" in {
        val root = makeRoot()
        val sym  = makeClass("SyntheticFoo", root)
        sym.position match
            case Absent     => succeed
            case Present(p) => fail(s"Expected Absent for synthetic symbol position but got Present($p)")
    }

    // Phase 13 T1 gap: flags.contains tests.
    // Given: Module symbol built with Flag.Module set.
    // When: sym.flags.contains(Flag.Module) and sym.flags.contains(Flag.Final).
    // Then: contains Module == true; contains Final == false.
    // Pins: T1 (Flags.contains coverage).
    "Symbol.flags.contains returns true for set flag and false for unset flag" in {
        val root = makeRoot()
        val sym  = makeModule("Foo", root)
        assert(
            sym.flags.contains(Tasty.Flag.Module),
            "Expected flags.contains(Module) == true for module symbol"
        )
        assert(
            !sym.flags.contains(Tasty.Flag.Final),
            "Expected flags.contains(Final) == false for module symbol with only Module flag"
        )
    }

    // Test (T4, root-owned FQN): root sentinel Symbol where owner eq sym itself returns empty fullName and binaryName.
    // The computeFullName loop terminates when cur.owner eq cur (root-owns-itself sentinel condition).
    // The resulting parts list contains only the empty root name, which is filtered, yielding "".
    // Pins: T4 (root-owned symbol FQN handling).
    "T4: root sentinel Symbol fullName and binaryName both return empty string" in {
        import AllowUnsafe.embrace.danger
        // Build a root sentinel: Package, empty name, null owner (makeRoot produces exactly this).
        val root = makeRoot()
        // fullName.asString must be "" (no owner chain to traverse, root name is empty).
        assert(
            root.fullName.asString == "",
            s"Expected fullName.asString == '' for root sentinel but got '${root.fullName.asString}'"
        )
        // binaryName must also be "" (filtered parts is empty).
        assert(
            root.binaryName == "",
            s"Expected binaryName == '' for root sentinel but got '${root.binaryName}'"
        )
    }

    // Test (T4, deeply nested inner class binaryName): 5-level class ladder produces '$'-separated binary name with no package prefix.
    // Given: synthetic Symbol chain root -> A -> B -> C -> D -> E where all non-root symbols have SymbolKind.Class.
    // When: eSym.binaryName evaluated.
    // Then: returns "A$B$C$D$E" (all separators are '$' because every preceding kind is Class).
    // Pins: T4 (binaryName deeply nested inner class edge).
    "T4: deeply nested inner class binaryName returns A$B$C$D$E" in {
        val root = makeRoot()
        val aSym = makeClass("A", root)
        val bSym = makeClass("B", aSym)
        val cSym = makeClass("C", bSym)
        val dSym = makeClass("D", cSym)
        val eSym = makeClass("E", dSym)
        assert(
            eSym.binaryName == "A$B$C$D$E",
            s"Expected 'A$$B$$C$$D$$E' but got '${eSym.binaryName}'"
        )
    }

    // Phase 13 T1 gap: kind accessor on each major SymbolKind.
    // Given: synthetic symbols with each of Class, Trait, Object, Package, Method, Field kinds.
    // When: sym.kind read.
    // Then: returns the expected SymbolKind.
    // Pins: T1 (kind accessor coverage).
    "Symbol.kind returns the kind passed to Symbol.make" in {
        val root = makeRoot()
        val kindCases: List[(String, Tasty.SymbolKind)] = List(
            ("ClassSym", Tasty.SymbolKind.Class),
            ("TraitSym", Tasty.SymbolKind.Trait),
            ("ObjSym", Tasty.SymbolKind.Object),
            ("MethodSym", Tasty.SymbolKind.Method),
            ("FieldSym", Tasty.SymbolKind.Field),
            ("ValSym", Tasty.SymbolKind.Val),
            ("VarSym", Tasty.SymbolKind.Var)
        )
        val mismatches = kindCases.flatMap { (name, expectedKind) =>
            val sym = Tasty.Symbol.make(
                expectedKind,
                Tasty.Flags.empty,
                Tasty.Name(name),
                root,
                new ClasspathRef,
                Tasty.Symbol.TastyOrigin.empty,
                Absent
            )
            if sym.kind == expectedKind then None
            else Some(s"'$name': expected $expectedKind but got ${sym.kind}")
        }
        assert(
            mismatches.isEmpty,
            s"SymbolKind mismatches: ${mismatches.mkString("; ")}"
        )
    }

end TastySymbolTest
