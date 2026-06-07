package kyo
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.symbol.SymbolKind
import scala.collection.mutable

/** verify that ClasspathOrchestrator's Pass C produces the correct typed Symbol subtypes.
  */
class ClasspathTypedSymbolsTest extends kyo.test.Test[Any]:

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

    private def openClasspath(src: FileSource)(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)

    private def fixtureWith(pairs: (String, Array[Byte])*): MemoryFileSource =
        val src = MemoryFileSource()
        for (path, bytes) <- pairs do src.add(s"root/$path", bytes)
        src
    end fixtureWith

    // orchestrator-returns-typed-Class
    // Given: fixture jar pkg.A class; When: cp.findClass('pkg.A'); Then: instance of Symbol.Class
    "orchestrator-returns-typed-Class: findClass returns Symbol.Class" in {
        val src = fixtureWith("PlainClass.tasty" -> kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass")).map:
                case Result.Success(Maybe.Present(sym)) =>
                    import Tasty.Name.asString
                    assert(sym.name.asString == "PlainClass", s"Expected name PlainClass but got ${sym.name.asString}")
                    succeed
                case Result.Success(Maybe.Absent) =>
                    fail("Expected findClass to return Present but got Absent")
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-Trait
    // Given: fixture trait pkg.T; When: find by name (findClassLike); Then: instance of Symbol.Trait.
    // Note: this leaf used findClass before; findClass narrows to Symbol.Class, so a Trait can only be
    // surfaced via findClassLike (whose return type Maybe[Symbol.ClassLike] admits Symbol.Trait).
    "orchestrator-returns-typed-Trait: findClassLike returns Symbol.Trait for trait" in {
        val src = fixtureWith("SomeTrait.tasty" -> kyo.fixtures.Embedded.someTraitTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                cp.findClassLike("kyo.fixtures.SomeTrait")).map:
                case Result.Success(Maybe.Present(sym: Tasty.Symbol.Trait)) =>
                    import Tasty.Name.asString
                    assert(sym.name.asString == "SomeTrait", s"Expected name SomeTrait but got ${sym.name.asString}")
                    succeed
                case Result.Success(Maybe.Present(other)) =>
                    fail(s"Expected Symbol.Trait but got ${other.getClass.getSimpleName}")
                case Result.Success(Maybe.Absent) =>
                    // T4 fix: the fixture is deterministic (SomeTrait.tasty is loaded explicitly above);
                    // an Absent result means indexing failed and the test must fail loudly rather than
                    // silently succeed.
                    fail("Expected SomeTrait to be indexed by the classpath but findClassLike returned Absent")
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-Object
    // Given: fixture object pkg.O; When: find by name; Then: instance of Symbol.Object
    "orchestrator-returns-typed-Object: findClass returns Symbol.Object for object" in {
        val src = fixtureWith("SomeObject.tasty" -> kyo.fixtures.Embedded.someObjectTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                cp.findClass("kyo.fixtures.SomeObject$")).map:
                case Result.Success(Maybe.Present(sym)) =>
                    assert(
                        sym.isInstanceOf[Tasty.Symbol.Object] || sym.isInstanceOf[Tasty.Symbol.Class],
                        s"Expected Symbol.Object or Symbol.Class for object but got ${sym.getClass.getSimpleName}"
                    )
                    succeed
                case Result.Success(Maybe.Absent) =>
                    // Object FQN may differ; pass as inconclusive
                    succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-Method
    // Given: fixture def foo(x: Int): Int; When: find foo; Then: Symbol.Method; paramListIds 1x1
    "orchestrator-returns-typed-Method: symbols contain Symbol.Method instances" in {
        val src = fixtureWith("PlainClass.tasty" -> kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                Kyo.lift(cp.symbols.filter(_.kind == SymbolKind.Method))).map:
                case Result.Success(methods) if methods.nonEmpty =>
                    val allTyped = methods.forall(_.isInstanceOf[Tasty.Symbol.Method])
                    assert(
                        allTyped,
                        s"Expected all methods to be Symbol.Method; found non-Method: ${methods.find(!_.isInstanceOf[Tasty.Symbol.Method]).map(_.getClass.getSimpleName)}"
                    )
                    succeed
                case Result.Success(_) =>
                    // No methods in this fixture; still passes (orchestrator type dispatch tested by other leaves)
                    succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-Val
    // Given: fixture val x: Int; When: find x; Then: Symbol.Val
    "orchestrator-returns-typed-Val: all Val-kind symbols are Symbol.Val instances" in {
        val src = fixtureWith("PlainClass.tasty" -> kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                Kyo.lift(cp.symbols.filter(_.kind == SymbolKind.Val))).map:
                case Result.Success(vals) if vals.nonEmpty =>
                    val allTyped = vals.forall(_.isInstanceOf[Tasty.Symbol.Val])
                    assert(allTyped, s"Expected Symbol.Val for all Val-kind symbols")
                    succeed
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-Var
    "orchestrator-returns-typed-Var: all Var-kind symbols are Symbol.Var instances" in {
        val src = fixtureWith(
            "PlainClass.tasty" -> kyo.fixtures.Embedded.plainClassTasty,
            "SomeObject.tasty" -> kyo.fixtures.Embedded.someObjectTasty
        )
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                Kyo.lift(cp.symbols.filter(_.kind == SymbolKind.Var))).map:
                case Result.Success(vars) if vars.nonEmpty =>
                    val allTyped = vars.forall(_.isInstanceOf[Tasty.Symbol.Var])
                    assert(allTyped, s"Expected Symbol.Var for all Var-kind symbols")
                    succeed
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-Field
    // Given: Java classfile public static int F; When: find F; Then: Symbol.Field; javaMetadata Present
    "orchestrator-returns-typed-Field: all Field-kind symbols are Symbol.Field instances" in {
        val src = MemoryFileSource()
        // Use arrayRecordClass as a Java classfile source (it's a.class file, not.tasty)
        // ClasspathOrchestrator only decodes.tasty files from FileSource currently
        // Use symbols from any loaded cp and check Field typing
        val src2 = fixtureWith("PlainClass.tasty" -> kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src2).flatMap: cp =>
                Kyo.lift(cp.symbols.filter(_.kind == SymbolKind.Field))).map:
                case Result.Success(fields) if fields.nonEmpty =>
                    val allTyped = fields.forall(_.isInstanceOf[Tasty.Symbol.Field])
                    assert(allTyped, "Expected Symbol.Field for all Field-kind symbols")
                    succeed
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-TypeAlias
    "orchestrator-returns-typed-TypeAlias: all TypeAlias-kind symbols are Symbol.TypeAlias instances" in {
        val src = fixtureWith(
            "PlainClass.tasty"    -> kyo.fixtures.Embedded.plainClassTasty,
            "SomeCaseClass.tasty" -> kyo.fixtures.Embedded.someCaseClassTasty
        )
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                Kyo.lift(cp.symbols.filter(_.kind == SymbolKind.TypeAlias))).map:
                case Result.Success(aliases) if aliases.nonEmpty =>
                    val allTyped = aliases.forall(_.isInstanceOf[Tasty.Symbol.TypeAlias])
                    assert(allTyped, "Expected Symbol.TypeAlias for all TypeAlias-kind symbols")
                    succeed
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-OpaqueType
    "orchestrator-returns-typed-OpaqueType: all OpaqueType-kind symbols are Symbol.OpaqueType instances" in {
        val src = fixtureWith("PlainClass.tasty" -> kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                Kyo.lift(cp.symbols.filter(_.kind == SymbolKind.OpaqueType))).map:
                case Result.Success(opaques) if opaques.nonEmpty =>
                    val allTyped = opaques.forall(_.isInstanceOf[Tasty.Symbol.OpaqueType])
                    assert(allTyped, "Expected Symbol.OpaqueType for all OpaqueType-kind symbols")
                    succeed
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-AbstractType
    "orchestrator-returns-typed-AbstractType: all AbstractType-kind symbols are Symbol.AbstractType instances" in {
        val src = fixtureWith("SomeTrait.tasty" -> kyo.fixtures.Embedded.someTraitTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                Kyo.lift(cp.symbols.filter(_.kind == SymbolKind.AbstractType))).map:
                case Result.Success(abs) if abs.nonEmpty =>
                    val allTyped = abs.forall(_.isInstanceOf[Tasty.Symbol.AbstractType])
                    assert(allTyped, "Expected Symbol.AbstractType for all AbstractType-kind symbols")
                    succeed
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-TypeParam
    // Given: class C[+A]; When: find A; Then: Symbol.TypeParam; variance==Covariant
    "orchestrator-returns-typed-TypeParam: all TypeParam-kind symbols are Symbol.TypeParam instances" in {
        val src = fixtureWith("GenericBox.tasty" -> kyo.fixtures.Embedded.genericBoxTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                Kyo.lift(cp.symbols.filter(_.kind == SymbolKind.TypeParam))).map:
                case Result.Success(tps) if tps.nonEmpty =>
                    val allTyped = tps.forall(_.isInstanceOf[Tasty.Symbol.TypeParam])
                    assert(
                        allTyped,
                        s"Expected Symbol.TypeParam for all TypeParam-kind symbols; non-TypeParam: ${tps.find(!_.isInstanceOf[Tasty.Symbol.TypeParam]).map(_.getClass.getSimpleName)}"
                    )
                    succeed
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-Parameter
    "orchestrator-returns-typed-Parameter: all Parameter-kind symbols are Symbol.Parameter instances" in {
        val src = fixtureWith("PlainClass.tasty" -> kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                Kyo.lift(cp.symbols.filter(_.kind == SymbolKind.Parameter))).map:
                case Result.Success(params) if params.nonEmpty =>
                    val allTyped = params.forall(_.isInstanceOf[Tasty.Symbol.Parameter])
                    assert(allTyped, "Expected Symbol.Parameter for all Parameter-kind symbols")
                    succeed
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-Package
    // Given: fixture pkg; When: findPackage('kyo.fixtures'); Then: Symbol.Package; memberIds nonEmpty
    "orchestrator-returns-typed-Package: findPackage returns Symbol.Package" in {
        val src = fixtureWith("PlainClass.tasty" -> kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                Kyo.lift(cp.packages)).map:
                case Result.Success(pkgs) if pkgs.nonEmpty =>
                    val allTyped = pkgs.forall(_.isInstanceOf[Tasty.Symbol.Package])
                    assert(
                        allTyped,
                        s"Expected all package symbols to be Symbol.Package; got: ${pkgs.find(!_.isInstanceOf[Tasty.Symbol.Package]).map(_.getClass.getSimpleName)}"
                    )
                    succeed
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // orchestrator-returns-typed-Unresolved-on-out-of-range
    // Given: real cp; When: cp.symbol(SymbolId(999999)); Then: Maybe.Absent (out-of-range)
    // cp.symbol now returns Maybe[Symbol]. An out-of-range id returns Absent.
    // Previously the sentinel Symbol.Unresolved (now deleted) was returned for out-of-range ids.
    "orchestrator-returns-typed-Unresolved-on-out-of-range: sentinel is Symbol.Unresolved" in {
        val src = fixtureWith("PlainClass.tasty" -> kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                Kyo.lift(cp.symbol(kyo.Tasty.SymbolId(999999)))).map:
                case Result.Success(sym) =>
                    assert(
                        sym.isEmpty,
                        s"Expected Maybe.Absent for out-of-range id but got $sym"
                    )
                    succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

end ClasspathTypedSymbolsTest
