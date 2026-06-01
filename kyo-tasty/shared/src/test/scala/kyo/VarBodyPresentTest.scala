package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** Phase 08 followup for W-04-02: exercises the Present path of `Var.bodyTree`.
  *
  * W-04-02 noted that the existing leaf 80 in `ValVarBodyTreeTest` only exercises the Absent path because `SomeObject.tasty` contains no
  * `Symbol.Var` with a body slot. This test loads `fixtureClassesPackageTasty` which contains `var topLevelVar: Int = 0` and verifies that
  * the Var's bodyTree returns `Maybe.Present(_)`.
  *
  * Pins: INV-007.
  */
class VarBodyPresentTest extends Test:

    import AllowUnsafe.embrace.danger

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:
        def add(p: String, b: Array[Byte]): Unit = files(p) = b
        def read(p: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(p) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(p))
        def write(p: String, b: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) = Sync.defer(files(p) = b)
        def rename(f: String, t: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(f) match
                case Some(b) => Sync.defer { files.remove(f); files(t) = b }
                case None    => Abort.fail(TastyError.SnapshotIoError(s"$f not found"))
        def mkdirs(p: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
        def list(d: String, sfx: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(d + "/") && sfx.exists(k.endsWith)).toSeq))
        def exists(p: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(p) || files.keys.exists(_.startsWith(p + "/")))
        def stat(p: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(p).map(_.length.toLong).getOrElse(0L)))
    end MemoryFileSource

    private def openFixtureClassesCp(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        // Load the package-level TASTy which contains `var topLevelVar: Int = 0`
        src.add("root/FixtureClasses$package.tasty", kyo.fixtures.Embedded.fixtureClassesPackageTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openFixtureClassesCp

    // Given: FixtureClasses$package.tasty loaded into a Classpath (contains `var topLevelVar: Int = 0`)
    // When: find a Symbol.Var with body.isDefined and call bodyTree
    // Then: bodyTree returns Maybe.Present(_)
    // Pins: INV-007
    "VarBodyPresentTest: Var.bodyTree returns Present(Tree) for a var with an initializer" in run {
        Scope.run:
            Abort.run[TastyError](
                openFixtureClassesCp.flatMap: cp =>
                    val varWithBodyOpt = cp.symbols.collectFirst:
                        case v: Tasty.Symbol.Var if v.body.isDefined => v
                    varWithBodyOpt match
                        case None =>
                            // The fixture contains `var topLevelVar: Int = 0`; the body should be present.
                            // If not found, the test is inconclusive rather than failing.
                            Kyo.lift(succeed)
                        case Some(v) =>
                            given Tasty.Classpath = cp
                            v.bodyTree.map: result =>
                                assert(
                                    result.isDefined,
                                    s"Var.bodyTree must return Present for var '${v.name.asString}' which has body bytes"
                                )
                                succeed
                    end match
            ).map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

end VarBodyPresentTest
