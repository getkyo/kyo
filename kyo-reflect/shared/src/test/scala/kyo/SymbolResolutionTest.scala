package kyo

import kyo.internal.reflect.query.Classpath as InternalClasspath
import kyo.internal.reflect.query.ClasspathOrchestrator
import kyo.internal.reflect.query.FileSource
import scala.collection.mutable

/** Tests for Phase 7: Symbol resolution, deduplication, and cross-classpath equality.
  *
  * Plan tests 19-21, 35.
  */
class SymbolResolutionTest extends Test:

    /** An in-memory FileSource backed by a mutable map of path -> bytes. */
    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit =
            files(path) = bytes

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(ReflectError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[ReflectError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer:
                        files.remove(from)
                        files(to) = bytes
                case None =>
                    Abort.fail(ReflectError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
            Kyo.unit

        def list(dir: String, suffix: String)(using Frame): Chunk[String] < (Sync & Abort[ReflectError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && k.endsWith(suffix)).toSeq)

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[ReflectError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(path).map(_.length.toLong).getOrElse(0L)))

    end MemoryFileSource

    private def fixtureSource(): MemoryFileSource =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src
    end fixtureSource

    private def openClasspath(src: FileSource)(using Frame): Reflect.Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
        InternalClasspath.allocate.flatMap: rawCp =>
            Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                ClasspathOrchestrator.openInto(Seq("root"), false, src, 1, rawCp).map: _ =>
                    Reflect.Classpath.wrap(rawCp)

    // Test 19: two concurrent findClass calls for the same FQN return reference-equal Symbol instances.
    // The Ready-state fqnIndex is an immutable HashMap built once during Phase C; any two reads for
    // the same key return the same object reference. Reference equality is the chosen dedup invariant
    // (Resolver.scala was deleted; the immutable HashMap provides the same guarantee without Async overhead).
    "two concurrent findClass calls for the same FQN return reference-equal symbols" in run {
        Scope.run:
            Abort.run[ReflectError](openClasspath(fixtureSource()).flatMap: cp =>
                Async.zip[ReflectError, Maybe[Reflect.Symbol], Maybe[Reflect.Symbol], Any](
                    cp.findClass("kyo.fixtures.PlainClass"),
                    cp.findClass("kyo.fixtures.PlainClass")
                )).map:
                case Result.Success((Present(sym1), Present(sym2))) =>
                    assert(
                        sym1 eq sym2,
                        s"Concurrent findClass calls must return reference-equal symbols; got different instances for ${sym1.fullName.asString}"
                    )
                case Result.Success((Absent, _)) | Result.Success((_, Absent)) =>
                    fail("Expected both concurrent findClass calls to return Present")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 20: two concurrent findClass calls for different FQNs both resolve independently
    "two concurrent findClass calls for different FQNs both resolve independently" in run {
        // Use the same file twice with different paths so we get two distinct FQNs
        // Since we only have PlainClass, we open a classpath with it twice (once in each root path slot)
        // and look up the same FQN plus a non-existent one
        Scope.run:
            Abort.run[ReflectError](openClasspath(fixtureSource()).flatMap: cp =>
                Async.zip[ReflectError, Maybe[Reflect.Symbol], Maybe[Reflect.Symbol], Any](
                    cp.findClass("kyo.fixtures.PlainClass"),
                    cp.findClass("no.such.Class")
                )).map:
                case Result.Success((Present(sym1), Absent)) =>
                    assert(
                        sym1.fullName.asString.contains("PlainClass"),
                        s"Expected PlainClass symbol, got: ${sym1.fullName.asString}"
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
            Abort.run[ReflectError](openClasspath(fixtureSource()).flatMap: cp =>
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
            Abort.run[ReflectError](
                openClasspath(src1).flatMap: cp1 =>
                    openClasspath(src2).flatMap: cp2 =>
                        cp1.findClass("kyo.fixtures.PlainClass").flatMap: sym1Opt =>
                            cp2.findClass("kyo.fixtures.PlainClass").map: sym2Opt =>
                                (sym1Opt, sym2Opt)
            ).map:
                case Result.Success((Present(sym1), Present(sym2))) =>
                    assert(sym1 ne sym2, "Symbols from different Classpath instances must not be reference-equal")
                    assert(
                        sym1.fullName.asString == sym2.fullName.asString,
                        s"Symbols from different Classpath instances must have same FQN: ${sym1.fullName.asString} vs ${sym2.fullName.asString}"
                    )
                case Result.Success((Absent, _)) | Result.Success((_, Absent)) =>
                    fail("Expected both Classpath instances to return Present for PlainClass")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

end SymbolResolutionTest
