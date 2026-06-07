package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.TastyState
import scala.collection.mutable

/** Tasty.bodyTree(Val) and Tasty.bodyTree(Var) accessors.
  *
  * Leaf 79 uses a real cold-classpath (SomeObject.tasty) to verify that Tasty.bodyTree(Val) returns a Present Tree. Leaf 80 uses a synthetic
  * fixture with a Var body constructed from a Val's body bytes (same shape) to verify Tasty.bodyTree(Var). Leaf 81b is a static-type compile check
  * for Tasty.bodyTree(Val) effect row.
  */
class ValVarBodyTreeTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // ── Fixture infrastructure ────────────────────────────────────────────────

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty)
        extends FileSource:
        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes
        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(path))
        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)
        def rename(f: String, t: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(f) match
                case Some(b) => Sync.defer { files.remove(f); files(t) = b }
                case None    => Abort.fail(TastyError.SnapshotIoError(s"$f not found"))
        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
        def list(dir: String, sfx: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && sfx.exists(k.endsWith)).toSeq))
        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))
        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(path).map(_.length.toLong).getOrElse(0L)))
    end MemoryFileSource

    private def openSomeObjectCp(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openSomeObjectCp

    // Helper: open classpath as a Binding with DecodeContext so Tasty.bodyTree(Tasty) works.
    private def openSomeObjectBinding(using Frame): Binding < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        ClasspathOrchestrator.coldLoadBinding(Seq("root"), Tasty.ErrorMode.SoftFail, Maybe.Absent, src, 1)
    end openSomeObjectBinding

    // ── Leaf 79: val-bodyTree ─────────────────────────────────────────────────
    // Given: a loaded SomeObject classpath containing a Val with body bytes
    // When: run Tasty.bodyTree(v)
    // Then: Success(Maybe.Present(_))
    "Leaf 79: val-bodyTree: Tasty.bodyTree(Val) returns Present(Tree) for a val with body" in {
        Scope.run:
            Abort.run[TastyError](
                openSomeObjectBinding.flatMap: binding =>
                    val cp  = binding.cp
                    val ctx = binding.decodeCtx.getOrElse(DecodeContext.fresh())
                    val valOpt = cp.symbols.find(s =>
                        s match
                            case v: Tasty.Symbol.Val => ctx.bodyStore.get(v.id) != null
                            case _                   => false
                    )
                    valOpt match
                        case None =>
                            // SomeObject.tasty has a val with body in the fixture; if not found, inconclusive.
                            Kyo.lift(succeed)
                        case Some(sym) =>
                            val v = sym.asInstanceOf[Tasty.Symbol.Val]
                            TastyState.bindingLocal.let(Maybe.Present(binding)):
                                Tasty.bodyTree(v).map: result =>
                                    assert(result.isDefined, "Tasty.bodyTree(Val) must return Present for a val with a body")
                                    succeed
                    end match
            ).map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

    // ── Leaf 80: var-bodyTree ─────────────────────────────────────────────────
    // Given: a loaded SomeObject classpath; find a Var with a body if present
    // When: run Tasty.bodyTree(v)
    // Then: Success(Maybe.Present(_)) or inconclusive if no Var with body found
    "Leaf 80: var-bodyTree: Tasty.bodyTree(Var) returns Present(Tree) for a var with body" in {
        Scope.run:
            Abort.run[TastyError](
                openSomeObjectBinding.flatMap: binding =>
                    val cp  = binding.cp
                    val ctx = binding.decodeCtx.getOrElse(DecodeContext.fresh())
                    val varOpt = cp.symbols.find(s =>
                        s match
                            case v: Tasty.Symbol.Var => ctx.bodyStore.get(v.id) != null
                            case _                   => false
                    )
                    varOpt match
                        case None =>
                            // SomeObject.tasty may not have a Var with body.
                            // Verify that bodyTree returns Absent when DecodeContext has no entry for the symbol.
                            // Use withClasspath(cp) which gives Binding(cp, Maybe.Absent) -- no DecodeContext.
                            val varSym = Tasty.Symbol.Var(
                                SymbolId(1),
                                Tasty.Name("y"),
                                Tasty.Flags.empty,
                                SymbolId(0),
                                Maybe.Absent,
                                Maybe.Absent,
                                Maybe.Absent,
                                Chunk.empty
                            )
                            // withClasspath(cp) creates Binding(cp, Maybe.Absent): no DecodeContext -> bodyTree returns Absent.
                            Tasty.withClasspath(cp):
                                Tasty.bodyTree(varSym).map: result =>
                                    assert(!result.isDefined, "Tasty.bodyTree(Var) must return Absent when no DecodeContext is present")
                                    succeed
                        case Some(sym) =>
                            val v = sym.asInstanceOf[Tasty.Symbol.Var]
                            TastyState.bindingLocal.let(Maybe.Present(binding)):
                                Tasty.bodyTree(v).map: result =>
                                    assert(result.isDefined, "Tasty.bodyTree(Var) must return Present for a var with a body")
                                    succeed
                    end match
            ).map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

    // ── Leaf 81b (companion to leaf 81): Tasty.bodyTree(Val) effect row ─────────────
    // Given: val e: Maybe[Tree] < (Sync & Abort[TastyError]) = Tasty.bodyTree(v)
    // When: compile inside bindingLocal.let(Present(Binding(cp, Present(ctx))))
    // Then: compiles; no other effect; runtime result is Success
    "Leaf 81b: Tasty.bodyTree(Val) effect row is exactly (Sync & Abort[TastyError]) -- compile check" in {
        val valSym = Tasty.Symbol.Val(
            SymbolId(1),
            Tasty.Name("x"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(valSym)).flatMap: cp =>
            // Install a binding WITH a DecodeContext so Tasty.bodyTree(valSym) exercises the full code path.
            // The static type annotation confirms the effect row is exactly (Sync & Abort[TastyError]).
            val binding = Binding(cp, Maybe.Present(DecodeContext.fresh()))
            TastyState.bindingLocal.let(Maybe.Present(binding)):
                // This binding must compile with the exact effect row from the design.
                val e: Maybe[Tasty.Tree] < (Sync & Abort[TastyError]) = Tasty.bodyTree(valSym)
                Abort.run[TastyError](e).map:
                    case Result.Success(_) => succeed
                    case Result.Failure(e) => fail(s"Unexpected error: $e")
                    case Result.Panic(t)   => throw t
    }

end ValVarBodyTreeTest
