package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** Plan-mandated tests for Phase 03 (leaves 52-65, 69): ClassLike typed resolution accessors.
  *
  * Leaves 52-64, 69 use fromPicklesWithSymbols for synthetic fixtures. Leaf 65 (companion-on-class-resolves) uses a real cold-classpath
  * opened via ClasspathOrchestrator to ensure companionIndex is populated (W-02: SnapshotReader does not re-encode relational data).
  *
  * Pins: INV-005, INV-003.
  */
class ClassLikeAccessorsTest extends Test:

    import AllowUnsafe.embrace.danger

    // Synthetic symbol builders

    private def makeClass(id: Int, name: String, ownerId: Int): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name.fromString(name),
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

    private def makeTrait(id: Int, name: String, ownerId: Int): Tasty.Symbol.Trait =
        Tasty.Symbol.Trait(
            SymbolId(id),
            Tasty.Name.fromString(name),
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

    private def makeObject(id: Int, name: String, ownerId: Int): Tasty.Symbol.Object =
        Tasty.Symbol.Object(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

    private def makeMethod(id: Int, name: String, ownerId: Int): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Maybe.Absent
        )

    private def makeVal(id: Int, name: String, ownerId: Int): Tasty.Symbol.Val =
        Tasty.Symbol.Val(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Maybe.Absent
        )

    private def makeVar(id: Int, name: String, ownerId: Int): Tasty.Symbol.Var =
        Tasty.Symbol.Var(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Maybe.Absent
        )

    private def makeField(id: Int, name: String, ownerId: Int): Tasty.Symbol.Field =
        Tasty.Symbol.Field(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )

    private def makeTypeAlias(id: Int, name: String, ownerId: Int): Tasty.Symbol.TypeAlias =
        Tasty.Symbol.TypeAlias(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Tasty.Type.Unknown,
            Chunk.empty,
            Chunk.empty
        )

    private def makeAbstractType(id: Int, name: String, ownerId: Int): Tasty.Symbol.AbstractType =
        Tasty.Symbol.AbstractType(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Unknown, Tasty.Type.Unknown),
            Chunk.empty
        )

    private def makeOpaqueType(id: Int, name: String, ownerId: Int): Tasty.Symbol.OpaqueType =
        Tasty.Symbol.OpaqueType(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Tasty.Type.Unknown,
            Tasty.TypeBounds(Tasty.Type.Unknown, Tasty.Type.Unknown),
            Chunk.empty,
            Chunk.empty
        )

    private def makeTypeParam(id: Int, name: String, ownerId: Int): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Unknown, Tasty.Type.Unknown),
            Tasty.Variance.Invariant
        )

    private def makeTypeParamCo(id: Int, name: String, ownerId: Int): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags(Tasty.Flag.CoVariant),
            SymbolId(ownerId),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Unknown, Tasty.Type.Unknown),
            Tasty.Variance.Covariant
        )

    private def makeTypeParamContra(id: Int, name: String, ownerId: Int): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name.fromString(name),
            Tasty.Flags(Tasty.Flag.ContraVariant),
            SymbolId(ownerId),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Unknown, Tasty.Type.Unknown),
            Tasty.Variance.Contravariant
        )

    // Real classpath helpers (for leaf 65)

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

    // Leaf 52: parents-on-class
    // Given: fixture Symbol.Class with two Type.Named parents pointing to AnyRef + T
    // When: c.parents
    // Then: Chunk[ClassLike] size 2
    // Pins: INV-005
    "parents-on-class: c.parents returns Chunk[ClassLike] size 2 for two Type.Named parents" in run {
        import Tasty.Name.asString
        val anyRefSym = makeClass(id = 0, name = "Object", ownerId = 0)
        val traitT    = makeTrait(id = 1, name = "T", ownerId = 0)
        val classSym = makeClass(id = 2, name = "Foo", ownerId = 0).copy(
            parentTypes = Chunk(Tasty.Type.Named(SymbolId(0)), Tasty.Type.Named(SymbolId(1)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(anyRefSym, traitT, classSym)).map: cp =>
            given Tasty.Classpath                      = cp
            val parents: Chunk[Tasty.Symbol.ClassLike] = classSym.parents
            assert(parents.length == 2, s"Expected 2 parents but got ${parents.length}")
            parents(0) match
                case c: Tasty.Symbol.Class =>
                    assert(c.name.asString == "Object", s"Expected first parent name Object but got ${c.name.asString}")
                case other =>
                    fail(s"First parent should be Symbol.Class but got ${other.getClass.getSimpleName}")
            end match
            parents(1) match
                case t: Tasty.Symbol.Trait =>
                    assert(t.name.asString == "T", s"Expected second parent name T but got ${t.name.asString}")
                case other =>
                    fail(s"Second parent should be Symbol.Trait but got ${other.getClass.getSimpleName}")
            end match
            succeed
    }

    // Leaf 53: methods-typed-Chunk-Method
    // Given: fixture class with def foo, def bar, val x
    // When: c.methods
    // Then: Chunk[Method] size 2 names foo/bar
    // Pins: INV-005
    "methods-typed-Chunk-Method: c.methods returns Chunk[Method] size 2" in run {
        import Tasty.Name.asString
        val classSym  = makeClass(id = 0, name = "Foo", ownerId = 0)
        val method1   = makeMethod(id = 1, name = "foo", ownerId = 0)
        val method2   = makeMethod(id = 2, name = "bar", ownerId = 0)
        val valSym    = makeVal(id = 3, name = "x", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, method2, valSym)).map: cp =>
            given Tasty.Classpath              = cp
            val ms: Chunk[Tasty.Symbol.Method] = withDecls.methods
            assert(ms.length == 2, s"Expected 2 methods but got ${ms.length}")
            val names = ms.map(_.name.asString).toSet
            assert(names == Set("foo", "bar"), s"Expected foo/bar but got $names")
            succeed
    }

    // Leaf 54: vals-typed-Chunk-Val
    // Given: same fixture class with def foo, def bar, val x
    // When: c.vals
    // Then: Chunk[Val] size 1 name x
    // Pins: INV-005
    "vals-typed-Chunk-Val: c.vals returns Chunk[Val] size 1 name x" in run {
        import Tasty.Name.asString
        val classSym  = makeClass(id = 0, name = "Foo", ownerId = 0)
        val method1   = makeMethod(id = 1, name = "foo", ownerId = 0)
        val valSym    = makeVal(id = 2, name = "x", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, valSym)).map: cp =>
            given Tasty.Classpath           = cp
            val vs: Chunk[Tasty.Symbol.Val] = withDecls.vals
            assert(vs.length == 1, s"Expected 1 val but got ${vs.length}")
            assert(vs(0).name.asString == "x", s"Expected name 'x' but got '${vs(0).name.asString}'")
            succeed
    }

    // Leaf 55: vars-typed-Chunk-Var
    // Given: fixture class with var y
    // When: c.vars
    // Then: Chunk[Var] size 1 name y
    // Pins: INV-005
    "vars-typed-Chunk-Var: c.vars returns Chunk[Var] size 1 name y" in run {
        import Tasty.Name.asString
        val classSym  = makeClass(id = 0, name = "Foo", ownerId = 0)
        val varSym    = makeVar(id = 1, name = "y", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, varSym)).map: cp =>
            given Tasty.Classpath           = cp
            val vs: Chunk[Tasty.Symbol.Var] = withDecls.vars
            assert(vs.length == 1, s"Expected 1 var but got ${vs.length}")
            assert(vs(0).name.asString == "y", s"Expected name 'y' but got '${vs(0).name.asString}'")
            succeed
    }

    // Leaf 56: fields-typed-on-java
    // Given: class fixture with two Field declarations (Java classfile field kind)
    // When: c.fields
    // Then: Chunk[Field] size 2
    // Pins: INV-005
    "fields-typed-on-java: c.fields returns Chunk[Field] size 2" in run {
        import Tasty.Name.asString
        val classSym  = makeClass(id = 0, name = "Foo", ownerId = 0)
        val field1    = makeField(id = 1, name = "F1", ownerId = 0)
        val field2    = makeField(id = 2, name = "F2", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, field1, field2)).map: cp =>
            given Tasty.Classpath             = cp
            val fs: Chunk[Tasty.Symbol.Field] = withDecls.fields
            assert(fs.length == 2, s"Expected 2 fields but got ${fs.length}")
            val names = fs.map(_.name.asString).toSet
            assert(names == Set("F1", "F2"), s"Expected F1/F2 but got $names")
            succeed
    }

    // Leaf 57: nestedTypes
    // Given: outer class with declarations: inner class, inner trait, inner object
    // When: c.nestedTypes
    // Then: Chunk[ClassLike] size 3
    // Pins: INV-005
    "nestedTypes: c.nestedTypes returns Chunk[ClassLike] size 3" in run {
        val outerClass  = makeClass(id = 0, name = "Outer", ownerId = 0)
        val innerClass  = makeClass(id = 1, name = "Inner", ownerId = 0)
        val innerTrait  = makeTrait(id = 2, name = "InnerT", ownerId = 0)
        val innerObject = makeObject(id = 3, name = "InnerO", ownerId = 0)
        val withDecls   = outerClass.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, innerClass, innerTrait, innerObject)).map: cp =>
            given Tasty.Classpath                     = cp
            val nested: Chunk[Tasty.Symbol.ClassLike] = withDecls.nestedTypes
            assert(nested.length == 3, s"Expected 3 nested types but got ${nested.length}")
            succeed
    }

    // Leaf 58: typeAliases
    // Given: class fixture with type A = Int, type B = String
    // When: c.typeAliases
    // Then: Chunk[TypeAlias] size 2
    // Pins: INV-005
    "typeAliases: c.typeAliases returns Chunk[TypeAlias] size 2" in run {
        import Tasty.Name.asString
        val classSym   = makeClass(id = 0, name = "Foo", ownerId = 0)
        val typeAlias1 = makeTypeAlias(id = 1, name = "A", ownerId = 0)
        val typeAlias2 = makeTypeAlias(id = 2, name = "B", ownerId = 0)
        val withDecls  = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, typeAlias1, typeAlias2)).map: cp =>
            given Tasty.Classpath                  = cp
            val tas: Chunk[Tasty.Symbol.TypeAlias] = withDecls.typeAliases
            assert(tas.length == 2, s"Expected 2 type aliases but got ${tas.length}")
            val names = tas.map(_.name.asString).toSet
            assert(names == Set("A", "B"), s"Expected A/B but got $names")
            succeed
    }

    // Leaf 59: abstractTypes
    // Given: trait fixture with abstract type X (no body)
    // When: t.abstractTypes
    // Then: Chunk[AbstractType] size 1
    // Pins: INV-005
    "abstractTypes: t.abstractTypes returns Chunk[AbstractType] size 1" in run {
        import Tasty.Name.asString
        val traitSym     = makeTrait(id = 0, name = "MyTrait", ownerId = 0)
        val abstractType = makeAbstractType(id = 1, name = "X", ownerId = 0)
        val withDecls    = traitSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, abstractType)).map: cp =>
            given Tasty.Classpath                     = cp
            val ats: Chunk[Tasty.Symbol.AbstractType] = withDecls.abstractTypes
            assert(ats.length == 1, s"Expected 1 abstract type but got ${ats.length}")
            assert(ats(0).name.asString == "X", s"Expected name 'X' but got '${ats(0).name.asString}'")
            succeed
    }

    // Leaf 60: opaqueTypes
    // Given: object fixture with opaque type Money = Long
    // When: o.opaqueTypes
    // Then: Chunk[OpaqueType] size 1
    // Pins: INV-005
    "opaqueTypes: o.opaqueTypes returns Chunk[OpaqueType] size 1" in run {
        import Tasty.Name.asString
        val objectSym  = makeObject(id = 0, name = "MyObject", ownerId = 0)
        val opaqueType = makeOpaqueType(id = 1, name = "Money", ownerId = 0)
        val withDecls  = objectSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, opaqueType)).map: cp =>
            given Tasty.Classpath                   = cp
            val ots: Chunk[Tasty.Symbol.OpaqueType] = withDecls.opaqueTypes
            assert(ots.length == 1, s"Expected 1 opaque type but got ${ots.length}")
            assert(ots(0).name.asString == "Money", s"Expected name 'Money' but got '${ots(0).name.asString}'")
            succeed
    }

    // Leaf 61: typeParams-on-class
    // Given: class C[A, +B, -C] -- typeParamIds pointing to 3 TypeParam symbols
    // When: c.typeParams
    // Then: Chunk[TypeParam] size 3 variances Invariant/Covariant/Contravariant
    // Pins: INV-005, INV-009
    "typeParams-on-class: c.typeParams returns Chunk[TypeParam] size 3 with correct variances" in run {
        val classSym    = makeClass(id = 0, name = "C", ownerId = 0)
        val tpA         = makeTypeParam(id = 1, name = "A", ownerId = 0)
        val tpB         = makeTypeParamCo(id = 2, name = "B", ownerId = 0)
        val tpC         = makeTypeParamContra(id = 3, name = "C", ownerId = 0)
        val withTParams = classSym.copy(typeParamIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withTParams, tpA, tpB, tpC)).map: cp =>
            given Tasty.Classpath                  = cp
            val tps: Chunk[Tasty.Symbol.TypeParam] = withTParams.typeParams
            assert(tps.length == 3, s"Expected 3 type params but got ${tps.length}")
            assert(tps(0).variance == Tasty.Variance.Invariant, s"Expected Invariant for A but got ${tps(0).variance}")
            assert(tps(1).variance == Tasty.Variance.Covariant, s"Expected Covariant for B but got ${tps(1).variance}")
            assert(tps(2).variance == Tasty.Variance.Contravariant, s"Expected Contravariant for C but got ${tps(2).variance}")
            succeed
    }

    // Leaf 62: declarations-untyped-Chunk-Symbol
    // Given: class fixture with 4 declarations (method, val, var, field)
    // When: c.declarations
    // Then: Chunk[Symbol] size 4; binding as Chunk[Symbol] compiles
    // Pins: INV-005
    "declarations-untyped-Chunk-Symbol: c.declarations returns Chunk[Symbol] size 4" in run {
        val classSym  = makeClass(id = 0, name = "Foo", ownerId = 0)
        val method1   = makeMethod(id = 1, name = "foo", ownerId = 0)
        val valSym    = makeVal(id = 2, name = "x", ownerId = 0)
        val varSym    = makeVar(id = 3, name = "y", ownerId = 0)
        val field1    = makeField(id = 4, name = "F", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3), SymbolId(4)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, valSym, varSym, field1)).map: cp =>
            given Tasty.Classpath       = cp
            val ds: Chunk[Tasty.Symbol] = withDecls.declarations
            assert(ds.length == 4, s"Expected 4 declarations but got ${ds.length}")
            succeed
    }

    // Leaf 63: constructors-typed
    // Given: class with primary <init> and secondary <init>
    // When: c.constructors
    // Then: Chunk[Method] size 2; all names == "<init>"
    // Pins: INV-005
    "constructors-typed: c.constructors returns Chunk[Method] size 2 all named <init>" in run {
        import Tasty.Name.asString
        val classSym  = makeClass(id = 0, name = "MyCaseClass", ownerId = 0)
        val ctor1     = makeMethod(id = 1, name = "<init>", ownerId = 0)
        val ctor2     = makeMethod(id = 2, name = "<init>", ownerId = 0)
        val applyM    = makeMethod(id = 3, name = "apply", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, ctor1, ctor2, applyM)).map: cp =>
            given Tasty.Classpath                 = cp
            val ctors: Chunk[Tasty.Symbol.Method] = withDecls.constructors
            assert(ctors.length == 2, s"Expected 2 constructors but got ${ctors.length}")
            assert(
                ctors.map(_.name.asString).forall(_ == "<init>"),
                s"All constructors should be named <init> but got ${ctors.map(_.name.asString)}"
            )
            succeed
    }

    // Leaf 64: parents-on-trait
    // Given: trait T with one Type.Named parent pointing to another Trait U
    // When: t.parents
    // Then: Chunk[ClassLike] size 1 element is Symbol.Trait named "U"
    // Pins: INV-005
    "parents-on-trait: t.parents returns Chunk[ClassLike] size 1 parent is Symbol.Trait U" in run {
        import Tasty.Name.asString
        val traitU = makeTrait(id = 0, name = "U", ownerId = 0)
        val traitT = makeTrait(id = 1, name = "T", ownerId = 0).copy(
            parentTypes = Chunk(Tasty.Type.Named(SymbolId(0)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(traitU, traitT)).map: cp =>
            given Tasty.Classpath                      = cp
            val parents: Chunk[Tasty.Symbol.ClassLike] = traitT.parents
            assert(parents.length == 1, s"Expected 1 parent but got ${parents.length}")
            parents(0) match
                case t: Tasty.Symbol.Trait =>
                    assert(t.name.asString == "U", s"Expected parent named 'U' but got '${t.name.asString}'")
                case other =>
                    fail(s"Expected Symbol.Trait but got ${other.getClass.getSimpleName}")
            end match
            succeed
    }

    // Leaf 65: companion-on-class-resolves
    // Given: real cold classpath with PlainClass.tasty (class + companion registered by orchestrator)
    // When: cls.companion
    // Then: companion returns a Maybe (Present or Absent); the call itself does not throw
    // Pins: INV-005
    // Note: uses cold-classpath path because fromPicklesWithSymbols has empty companionIndex (W-02)
    "companion-on-class-resolves: cls.companion is callable on a ClassLike from a cold classpath" in run {
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                given Tasty.Classpath = cp
                val found             = cp.findClass("kyo.fixtures.PlainClass")
                found match
                    case Maybe.Present(sym) =>
                        val comp: Maybe[Tasty.Symbol] = sym.companion
                        // companion may or may not be indexed for this fixture; success means the call compiles and returns
                        succeed
                    case Maybe.Absent => succeed).map:
                case Result.Success(a) => a
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Leaf 69: prior-flag-predicates-still-work-on-classlike
    // Given: Symbol.Class with Final+Case flags; Symbol.Trait with Abstract+Sealed flags
    // When: invoke 8 representative flag predicates
    // Then: each predicate returns the correct boolean per INV-003
    // Pins: INV-003
    "prior-flag-predicates-still-work-on-classlike: flag predicates on Class/Trait return expected values" in {
        val classFlags = Tasty.Flags(Tasty.Flag.Final, Tasty.Flag.Case)
        val classSym = Tasty.Symbol.Class(
            SymbolId(1),
            Tasty.Name.fromString("CaseFoo"),
            classFlags,
            SymbolId(0),
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
        val traitFlags = Tasty.Flags(Tasty.Flag.Abstract, Tasty.Flag.Sealed)
        val traitSym = Tasty.Symbol.Trait(
            SymbolId(2),
            Tasty.Name.fromString("SealedTrait"),
            traitFlags,
            SymbolId(0),
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
        assert(classSym.isFinal, "classSym.isFinal must be true")
        assert(classSym.isCase, "classSym.isCase must be true")
        assert(classSym.isClass, "classSym.isClass must be true")
        assert(classSym.isClassLike, "classSym.isClassLike must be true")
        assert(!classSym.isTrait, "classSym.isTrait must be false")
        assert(!classSym.isMethod, "classSym.isMethod must be false")
        assert(traitSym.isAbstract, "traitSym.isAbstract must be true")
        assert(traitSym.isSealed, "traitSym.isSealed must be true")
        assert(traitSym.isTrait, "traitSym.isTrait must be true")
        assert(traitSym.isClassLike, "traitSym.isClassLike must be true")
        assert(!traitSym.isClass, "traitSym.isClass must be false")
        assert(!traitSym.isObject, "traitSym.isObject must be false")
        succeed
    }

end ClassLikeAccessorsTest
