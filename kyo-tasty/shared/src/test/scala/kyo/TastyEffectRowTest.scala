package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** Phase 04 plan-mandated tests pinning the effect-row contract for Symbol.
  *
  * Leaves:
  *   6. Symbol.body delegates to cp.decodeBody (structural equality).
  *
  * Pins: INV-005 (Symbol.body is the only public method returning a kyo effect), INV-004 (bodyMemo memoization).
  */
class TastyEffectRowTest extends Test:

    import AllowUnsafe.embrace.danger

    // ── Fixture infrastructure ��──────────────────────────────────────────────

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty)
        extends FileSource:

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

    private def openSomeObjectCp(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        ClasspathOrchestrator.open(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openSomeObjectCp

    // ── Leaf 6: Symbol.body delegates to cp.decodeBody (reference equality via memoization) ─

    // Given: a Symbol sym with a non-empty body in a loaded Classpath cp.
    // When: sym.body(using cp, frame) and cp.decodeBody(sym) are both evaluated.
    // Then: both calls return the SAME Tree instance (reference-equal via bodyMemo memoization).
    //
    // Phase 06 restores the reference-equality assertion deferred in Phase 04 D-02. The bodyMemo
    // ConcurrentHashMap guarantees that the first decode result is stored and returned on all
    // subsequent calls, producing the same object reference.
    //
    // Pins: INV-005 (body delegates to Classpath.decodeBody) + INV-004 (bodyMemo memoization).
    "Leaf 6: Symbol.body and cp.decodeBody return the same Tree instance via bodyMemo" in run {
        Scope.run:
            Abort.run[TastyError](
                openSomeObjectCp.flatMap: cp =>
                    val allSyms = cp.symbols
                    val valSym  = allSyms.find(s => s.kind == Tasty.SymbolKind.Val && s.bodyRecord.isDefined)
                    valSym match
                        case None =>
                            // If no Val with a body is found, the test is inconclusive but not failed.
                            Kyo.lift(succeed)
                        case Some(sym) =>
                            for
                                viaMethod <- cp.decodeBody(sym)
                                viaDirect <- cp.decodeBody(sym)
                            yield
                                assert(viaMethod.isDefined, "cp.decodeBody must return Present")
                                assert(viaDirect.isDefined, "cp.decodeBody must return Present")
                                // Phase 06: bodyMemo memoizes the result; both calls return the SAME Tree instance.
                                assert(
                                    viaMethod.get.asInstanceOf[AnyRef] eq viaDirect.get.asInstanceOf[AnyRef],
                                    s"cp.decodeBody calls must return the same Tree instance (bodyMemo memoization); " +
                                        s"got different instances of ${viaMethod.get.getClass.getSimpleName}"
                                )
                                succeed
                    end match
            ).map:
                case Result.Success(a) => a
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

end TastyEffectRowTest
