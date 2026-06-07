package kyo

import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.TastyState
import scala.collection.mutable

/** followup for W-04-02: exercises the Present path of `Tasty.bodyTree(Var)`.
  *
  * W-04-02 noted that the existing leaf 80 in `ValVarBodyTreeTest` only exercises the Absent path because `SomeObject.tasty` contains no
  * `Symbol.Var` with a body slot. This test loads `fixtureClassesPackageTasty` which contains `var topLevelVar: Int = 0` and verifies that
  * the Var's bodyTree returns `Maybe.Present(_)`.
  */
class VarBodyPresentTest extends kyo.test.Test[Any]:

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

    private def openFixtureClassesSrc(): MemoryFileSource =
        val src = MemoryFileSource()
        // Load the package-level TASTy which contains `var topLevelVar: Int = 0`
        src.add("root/FixtureClasses$package.tasty", kyo.fixtures.Embedded.fixtureClassesPackageTasty)
        src
    end openFixtureClassesSrc

    private def openFixtureClassesCp(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, openFixtureClassesSrc(), 1)
    end openFixtureClassesCp

    "VarBodyPresentTest: Tasty.bodyTree(Var) returns Present(Tree) for a var with an initializer" in {
        Scope.run:
            Abort.run[TastyError](
                ClasspathOrchestrator.coldLoadBinding(
                    Seq("root"),
                    Tasty.ErrorMode.SoftFail,
                    Maybe.Absent,
                    openFixtureClassesSrc(),
                    1
                ).flatMap: binding =>
                    val cp  = binding.cp
                    val ctx = binding.decodeCtx.getOrElse(DecodeContext.fresh())
                    val varWithBodyOpt = cp.symbols.collectFirst:
                        case v: Tasty.Symbol.Var if ctx.bodyStore.get(v.id) != null => v
                    varWithBodyOpt match
                        case None =>
                            // The fixture contains `var topLevelVar: Int = 0`; the body MUST be present.
                            // A None here means the decoder failed to store the body, which is a real bug.
                            Kyo.lift(
                                fail("No Var with body found in FixtureClasses-package fixture; expected topLevelVar with body bytes")
                            )
                        case Some(v) =>
                            TastyState.bindingLocal.let(Maybe.Present(binding)):
                                Tasty.bodyTree(v).map: result =>
                                    assert(
                                        result.isDefined,
                                        s"Tasty.bodyTree(Var) must return Present for var '${v.name.asString}' which has body bytes"
                                    )
                                    succeed
                    end match
            ).map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

end VarBodyPresentTest
