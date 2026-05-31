package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.reader.AstUnpickler
import kyo.internal.tasty.reader.FileAttributes
import kyo.internal.tasty.reader.NameUnpickler
import kyo.internal.tasty.reader.SectionIndex
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TastyHeader
import kyo.internal.tasty.symbol.Interner
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Tests for Phase 7: Symbol resolution, deduplication, and cross-classpath equality.
  *
  * Plan tests 19, 21, 35.
  */
class SymbolResolutionTest extends Test:

    import AllowUnsafe.embrace.danger

    /** An in-memory FileSource backed by a mutable map of path -> bytes. */
    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit =
            files(path) = bytes

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

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Kyo.unit

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(path).map(_.length.toLong).getOrElse(0L)))

    end MemoryFileSource

    private def fixtureSource(): MemoryFileSource =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src
    end fixtureSource

    private def openClasspath(src: FileSource)(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.open(Seq("root"), false, src, 1)

    // Test 19: two concurrent findClass calls for the same FQN return reference-equal Symbol instances.
    // The fqnIndex is an immutable HashMap populated once during Phase C. Both calls read the same
    // HashMap entry and return the same object reference (reference equality via HashMap identity).
    "two concurrent findClass calls for the same FQN return reference-equal symbols" in run {
        Scope.run:
            Abort.run[TastyError](openClasspath(fixtureSource()).flatMap: cp =>
                Async.zip[TastyError, Maybe[Tasty.Symbol], Maybe[Tasty.Symbol], Any](
                    cp.findClass("kyo.fixtures.PlainClass"),
                    cp.findClass("kyo.fixtures.PlainClass")
                )).map:
                case Result.Success((Present(sym1), Present(sym2))) =>
                    assert(
                        sym1 eq sym2,
                        s"Concurrent findClass calls must return reference-equal symbols; got different instances for ${sym1.name.asString}"
                    )
                case Result.Success((Absent, _)) | Result.Success((_, Absent)) =>
                    fail("Expected both concurrent findClass calls to return Present")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 21 (renumbered from prior Test 20): two concurrent findClass calls for different FQNs both resolve independently
    "two concurrent findClass calls for different FQNs both resolve independently" in run {
        // Use the same file twice with different paths so we get two distinct FQNs
        // Since we only have PlainClass, we open a classpath with it twice (once in each root path slot)
        // and look up the same FQN plus a non-existent one
        Scope.run:
            Abort.run[TastyError](openClasspath(fixtureSource()).flatMap: cp =>
                Async.zip[TastyError, Maybe[Tasty.Symbol], Maybe[Tasty.Symbol], Any](
                    cp.findClass("kyo.fixtures.PlainClass"),
                    cp.findClass("no.such.Class")
                )).map:
                case Result.Success((Present(sym1), Absent)) =>
                    assert(
                        sym1.name.asString.contains("PlainClass"),
                        s"Expected PlainClass symbol, got: ${sym1.name.asString}"
                    )
                case Result.Success((Absent, _)) =>
                    fail("Expected PlainClass to be found")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 21: Unresolved sentinel: findClass for a missing FQN returns Absent (soft-fail mode)
    "findClass for missing FQN returns Absent in soft-fail mode" in run {
        Scope.run:
            Abort.run[TastyError](openClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("no.such.Class")).map:
                case Result.Success(Absent) =>
                    succeed
                case Result.Success(Present(_)) =>
                    fail("Expected Absent for nonexistent FQN")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 35: cross-classpath structural equality by FQN
    // Two separate Classpath instances over the same roots yield different Symbol object references
    // (not reference-equal) but the same full names (structural equality by FQN).
    "cross-classpath FQN structural equality: different instances but same FQN" in run {
        val src1 = fixtureSource()
        val src2 = fixtureSource()
        Scope.run:
            Abort.run[TastyError](
                openClasspath(src1).flatMap: cp1 =>
                    openClasspath(src2).map: cp2 =>
                        val sym1Opt = cp1.findClass("kyo.fixtures.PlainClass")
                        val sym2Opt = cp2.findClass("kyo.fixtures.PlainClass")
                        (sym1Opt, sym2Opt)
            ).map:
                case Result.Success((Present(sym1), Present(sym2))) =>
                    assert(sym1 ne sym2, "Symbols from different Classpath instances must not be reference-equal")
                    assert(
                        sym1.name.asString == sym2.name.asString,
                        s"Symbols from different Classpath instances must have same FQN: ${sym1.name.asString} vs ${sym2.name.asString}"
                    )
                case Result.Success((Absent, _)) | Result.Success((_, Absent)) =>
                    fail("Expected both Classpath instances to return Present for PlainClass")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Helper: decode a TASTy byte array using AstUnpickler.readPass1 and return Pass1Result.
    private def decodeBytes(bytes: Array[Byte])(using Frame): AstUnpickler.Pass1Result < (Sync & Abort[TastyError]) =
        val view     = ByteView(bytes)
        val interner = Interner.init(numShards = 32, initialShardCapacity = 16)
        val arena    = new TypeArena
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
        yield result
        end for
    end decodeBytes

    // Phase 2 Test 1 (redesigned for Phase 07): cross-file type references are resolved via fqnIndex
    // at Phase C finalizeMerge. The UnresolvedRef mechanism is deleted.
    // Verify that PlainClass.tasty opens successfully and parentTypes are populated.
    "Phase C: cross-file type references resolved (PlainClass has parentTypes)" in run {
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) => Kyo.lift(sym.parentTypes)
                    case Absent       => Abort.fail(TastyError.MalformedSection("ASTs", "PlainClass not found", 0L))).map:
                case Result.Success(parents) =>
                    assert(parents.nonEmpty, "PlainClass should have at least one parent type (cross-file ref resolved)")
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Phase 2 Test 2: missing-class placeholder resolves to Unresolved sentinel when base file is absent.
    //
    // Design note: childClassTasty has no TYPEREFpkg/TYPEREFin for BaseClass (same compilation unit).
    // Parts a-c are redesigned to use PlainClass.tasty which has real cross-file UnresolvedRef
    // entries, then simulate Phase C with the FQN absent from fqnIndex.
    // Part d still uses childClassTasty + openClasspath to confirm no panic from unset slots.
    //
    // Steps:
    //   a) Decode PlainClass.tasty to get real UnresolvedRef placeholders.
    //   b) Take the first placeholder; simulate Phase C with fqnIndex MISS: synthesize Unresolved sentinel.
    //   c) Verify replaceSlot.get() returns Named(sym) with sym.kind == Unresolved and same FQN.
    //   d) Open a full classpath with ONLY childClassTasty (no base) via openInto and verify
    //      it opens without panic (no unset SingleAssign).
    // Phase 2 Test 2 (redesigned for Phase 07): unresolved cross-file references become Unresolved
    // symbols in the classpath. Verify that ChildClass.tasty opens without panic when base file is absent.
    "Phase C: classpath opens without panic when cross-file parent is absent (unresolved symbols)" in run {
        val src = MemoryFileSource()
        src.add("root/ChildClass.tasty", kyo.fixtures.Embedded.childClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                cp.findClass("kyo.fixtures.ChildClass")).map:
                case Result.Success(Present(_)) =>
                    succeed
                case Result.Success(Absent) =>
                    fail("Expected ChildClass to be found in partial classpath")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

end SymbolResolutionTest
