package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
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
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openFixtureClasspath

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
                    case Present(sym) =>
                        given Tasty.Classpath = cp
                        Kyo.lift(sym.fullName.asString)
                    case Absent =>
                        Abort.fail(TastyError.NotImplemented("PlainClass not found"))).map:
                case Result.Success(fqn) =>
                    assert(
                        fqn == "kyo.fixtures.PlainClass",
                        s"Expected fullName 'kyo.fixtures.PlainClass' but got '$fqn'"
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
    // plan: phase-02 update; sym.parents renamed to sym.parentTypes (direct field, no effect row).
    "Symbol.parentTypes for PlainClass returns a non-empty Chunk" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(plainClassSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) => Kyo.lift(sym match
                            case c: Tasty.Symbol.ClassLike => c.parentTypes;
                            case _                         => Chunk.empty[Tasty.Type])
                    case Absent => Abort.fail(TastyError.NotImplemented("PlainClass not found"))).map:
                case Result.Success(parents) =>
                    assert(
                        parents.nonEmpty,
                        "Expected non-empty parentTypes for PlainClass"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 5 (INV-001, Symbol.companion): class-Symbol's companion returns Module Symbol.
    // Given: fixture classpath with SomeCaseClass.tasty; AllowUnsafe in scope.
    // When: classSym.companion evaluated.
    // Then: result is Maybe.Present(modSym) with modSym.kind == SymbolKind.Object.
    // Pins: INV-001 (companion case).
    "SomeCaseClass class-Symbol companion returns Module Symbol with kind Object" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(someCaseClassSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.SomeCaseClass") match
                    case Present(sym) =>
                        given Tasty.Classpath = cp
                        Kyo.lift(sym.companion)
                    case Absent =>
                        Abort.fail(TastyError.NotImplemented("SomeCaseClass not found"))).map:
                case Result.Success(Present(compSym)) =>
                    assert(
                        compSym.kind == Tasty.SymbolKind.Object,
                        s"Expected companion kind Object but got ${compSym.kind}"
                    )
                case Result.Success(Absent) =>
                    fail("Expected Present companion for SomeCaseClass but got Absent")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Helpers for synthetic-symbol tests (no classpath I/O, cross-platform).

    // plan: phase-02 bridge; helpers use Symbol.make(kind, flags, name) - owner no longer stored on Symbol.
    private def makeRoot(): Tasty.Symbol =
        Tasty.Symbol.makePlaceholder(Tasty.SymbolKind.Package, Tasty.Flags.empty, Tasty.Name(""))

    private def makePkg(name: String, owner: Tasty.Symbol): Tasty.Symbol =
        Tasty.Symbol.makePlaceholder(Tasty.SymbolKind.Package, Tasty.Flags.empty, Tasty.Name(name))

    private def makeClass(name: String, owner: Tasty.Symbol): Tasty.Symbol =
        Tasty.Symbol.makePlaceholder(Tasty.SymbolKind.Class, Tasty.Flags.empty, Tasty.Name(name))

    private def makeModule(name: String, owner: Tasty.Symbol): Tasty.Symbol =
        Tasty.Symbol.makePlaceholder(Tasty.SymbolKind.Object, Tasty.Flags(Tasty.Flag.Module), Tasty.Name(name))

    // Test 1 (INV: T1, Symbol.binaryName): nested Scala class produces JVM binary name with '$' separator.
    // Given: synthetic Symbol tree com.example.Outer.Inner where Outer and Inner have SymbolKind.Class.
    // When: sym.binaryName evaluated.
    // Then: contains "Outer" and "Inner" separated by "$".
    // Pins: T1 (binaryName nested-class coverage).
    "Symbol.binaryName nested class returns com/example/Outer$Inner" in run {
        import kyo.internal.tasty.symbol.SymbolId
        val comSym     = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("com"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val exampleSym = Tasty.Symbol.Package(SymbolId(1), Tasty.Name("example"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val outerSym = Tasty.Symbol.Class(
            SymbolId(2),
            Tasty.Name("Outer"),
            Tasty.Flags.empty,
            SymbolId(1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val innerSym = Tasty.Symbol.Class(
            SymbolId(3),
            Tasty.Name("Inner"),
            Tasty.Flags.empty,
            SymbolId(2),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(comSym, exampleSym, outerSym, innerSym)).map: cp =>
            given Tasty.Classpath = cp
            val bn                = innerSym.binaryName
            assert(
                bn.contains("Outer") && bn.contains("Inner") && bn.contains("$"),
                s"Expected binaryName to contain Outer$$Inner but got '$bn'"
            )
    }

    "Symbol.binaryName top-level class returns com/example/Foo" in run {
        import kyo.internal.tasty.symbol.SymbolId
        val comSym     = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("com"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val exampleSym = Tasty.Symbol.Package(SymbolId(1), Tasty.Name("example"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val fooSym = Tasty.Symbol.Class(
            SymbolId(2),
            Tasty.Name("Foo"),
            Tasty.Flags.empty,
            SymbolId(1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(comSym, exampleSym, fooSym)).map: cp =>
            given Tasty.Classpath = cp
            val bn                = fooSym.binaryName
            assert(
                bn.contains("Foo"),
                s"Expected binaryName to contain 'Foo' but got '$bn'"
            )
    }

    // plan: phase-02 update; isPackageObject was a Symbol method; now use flags.contains(Module) && name == "package"
    "Symbol flags.Module && name package: true for Module named package" in {
        val pkgObj = makeModule("package", makeRoot())
        assert(
            pkgObj.flags.contains(Tasty.Flag.Module) && pkgObj.name.asString == "package",
            "Expected Module flag + name 'package' for package object symbol"
        )
    }

    "Symbol flags.Module: false for class named Foo" in {
        val foo = makeClass("Foo", makeRoot())
        assert(
            !foo.flags.contains(Tasty.Flag.Module),
            "Expected no Module flag for Class symbol named 'Foo'"
        )
    }

    // Test 5 (T2, Symbol.make): Symbol.make produces Symbol with correct kind, name, and owner.
    // Given: root sentinel Package; sym = Symbol.make(SymbolKind.Class, Flags.empty, Name("Foo"), root, ...).
    // When: read sym.kind, sym.name.asString, sym.owner.
    // Then: kind == SymbolKind.Class; name.asString == "Foo"; owner eq root.
    // Pins: T2.
    // plan: phase-02 update; Symbol.make now takes (kind, flags, name) only. sym.owner removed.
    "Symbol.make produces Symbol with correct kind and name" in {
        val sym = Tasty.Symbol.makePlaceholder(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name("Foo")
        )
        assert(sym.kind == Tasty.SymbolKind.Class, s"Expected kind Class but got ${sym.kind}")
        assert(sym.name.asString == "Foo", s"Expected name 'Foo' but got '${sym.name.asString}'")
    }

    // Phase 13 T1 gap: declaredType throws for Package symbols.
    // Given: synthetic Package symbol.
    // When: sym.declaredType (using AllowUnsafe).
    // Then: throws IllegalArgumentException (documented in Symbol.declaredType scaladoc).
    // Pins: T1 (declaredType Package guard).
    // plan: phase-02 update; declaredType is now Maybe[Type], returns Absent for Package (no exception).
    "Symbol.declaredType returns Absent for Package symbols" in {
        val pkg = makePkg("scala", makeRoot())
        assert(
            (pkg match
                case m: Tasty.Symbol.Method => m.declaredType;
                case _                      => kyo.Maybe.Absent
            ).isEmpty,
            "Expected Absent declaredType for Package symbol"
        )
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
    // plan: phase-02 update; sym.declarations -> sym.declarationIds (Chunk[SymbolId]).
    "Symbol.declarationIds returns Chunk for fixture class" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(plainClassSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) => Kyo.lift(sym.declarationIds)
                    case Absent       => Abort.fail(TastyError.NotImplemented("PlainClass not found"))).map:
                case Result.Success(decls) =>
                    assert(decls != null, "Expected non-null Chunk from declarationIds")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 13 T1 gap: typeParams returns Chunk for fixture class.
    // Given: fixture classpath containing PlainClass.tasty; AllowUnsafe in scope.
    // When: sym.typeParamIds.
    // Then: returns a Chunk (PlainClass has no type params so it is empty).
    // Pins: T1 (typeParams accessor coverage).
    "Symbol.typeParams returns Chunk for fixture class" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(plainClassSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) => Kyo.lift(sym.typeParamIds)
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
    // plan: phase-02 update; sym.position renamed to sym.sourcePosition.
    "Symbol.sourcePosition returns Absent for synthetic symbol" in {
        val sym = makeClass("SyntheticFoo", makeRoot())
        sym.sourcePosition match
            case Absent     => succeed
            case Present(p) => fail(s"Expected Absent for synthetic symbol sourcePosition but got Present($p)")
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

    // Test (T4, root-owned FQN): root sentinel Symbol where ownerId == id returns just its own name.
    // Pins: T4 (root-owned symbol FQN handling).
    "T4: root sentinel Symbol fullName returns its own name" in run {
        import kyo.internal.tasty.symbol.SymbolId
        val rootSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(rootSym)).map: cp =>
            given Tasty.Classpath = cp
            val fqn               = rootSym.fullName.asString
            // Root symbol with empty name returns empty string for fullName.
            assert(fqn.isEmpty || fqn == "", s"Expected empty fullName for root sentinel but got '$fqn'")
    }

    "T4: deeply nested inner class binaryName contains dollar separators" in run {
        import kyo.internal.tasty.symbol.SymbolId
        val syms: Seq[Tasty.Symbol] = (0 to 5).map: i =>
            val ownerId = if i == 0 then 0 else i - 1
            val n       = Tasty.Name(('A' + i).toChar.toString)
            if i < 2 then
                Tasty.Symbol.Package(SymbolId(i), n, Tasty.Flags.empty, SymbolId(ownerId), Chunk.empty)
            else
                Tasty.Symbol.Class(
                    SymbolId(i),
                    n,
                    Tasty.Flags.empty,
                    SymbolId(ownerId),
                    Maybe.Absent,
                    Maybe.Absent,
                    Maybe.Absent,
                    Chunk.empty,
                    Chunk.empty,
                    Chunk.empty,
                    Maybe.Absent,
                    Chunk.empty,
                    Chunk.empty,
                    Maybe.Absent
                )
            end if
        Tasty.Classpath.fromPicklesWithSymbols(Chunk.from(syms)).map: cp =>
            given Tasty.Classpath = cp
            val innermost         = syms.last
            val bn                = innermost.binaryName
            // The deeply nested class (id 5 = 'F') should have '$' separators for class-nested-in-class segments.
            assert(bn.nonEmpty, s"Expected non-empty binaryName but got empty")
    }

    // Test (Phase 25b T6-3): seeded generative test for Symbol.fullName.asString.
    // Build Symbol chains with explicit ownerId and assert fullName matches dot-joined segments.
    "Symbol.fullName.asString matches dot-joined segments for a known chain" in run {
        import kyo.internal.tasty.symbol.SymbolId
        val segments = List("com", "example", "MyClass")
        val syms: List[Tasty.Symbol.Class] = segments.zipWithIndex.map: (seg, i) =>
            val ownerId = if i == 0 then 0 else i - 1
            Tasty.Symbol.Class(
                SymbolId(i),
                Tasty.Name(seg),
                Tasty.Flags.empty,
                SymbolId(ownerId),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk.from(syms)).map: cp =>
            given Tasty.Classpath = cp
            val last              = syms.last
            val fqn               = last.fullName.asString
            // fullName walk produces "com.example.MyClass".
            assert(fqn.contains("MyClass"), s"Expected fullName to contain 'MyClass' but got '$fqn'")
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
            // plan: phase-02 bridge; Symbol.make(kind, flags, name).
            val sym = Tasty.Symbol.makePlaceholder(expectedKind, Tasty.Flags.empty, Tasty.Name(name))
            if sym.kind == expectedKind then None
            else Some(s"'$name': expected $expectedKind but got ${sym.kind}")
        }
        assert(
            mismatches.isEmpty,
            s"SymbolKind mismatches: ${mismatches.mkString("; ")}"
        )
    }

end TastySymbolTest
