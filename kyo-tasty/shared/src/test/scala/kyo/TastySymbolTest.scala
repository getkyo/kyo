package kyo

import kyo.internal.tasty.query.Classpath as InternalClasspath
import kyo.internal.tasty.query.ClasspathOrchestrator
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

end TastySymbolTest
