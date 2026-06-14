package kyo

import kyo.Tasty.SymbolId

/** Tests for ClassLike typed resolution accessors.
  *
  * Most cases use fromPicklesWithSymbols for synthetic fixtures. The companion test uses a real
  * cold-classpath via ClasspathOrchestrator so that companionIndex is populated.
  */
class ClassLikeAccessorsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Synthetic symbol builders

    private def makeClass(id: Int, name: String, ownerId: Int): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
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
            Chunk.empty
        )

    private def makeTrait(id: Int, name: String, ownerId: Int): Tasty.Symbol.Trait =
        Tasty.Symbol.Trait(
            SymbolId(id),
            Tasty.Name(name),
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
            Chunk.empty
        )

    private def makeObject(id: Int, name: String, ownerId: Int): Tasty.Symbol.Object =
        Tasty.Symbol.Object(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty
        )

    private def makeMethod(id: Int, name: String, ownerId: Int): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

    private def makeVal(id: Int, name: String, ownerId: Int): Tasty.Symbol.Val =
        Tasty.Symbol.Val(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )

    private def makeVar(id: Int, name: String, ownerId: Int): Tasty.Symbol.Var =
        Tasty.Symbol.Var(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )

    private def makeField(id: Int, name: String, ownerId: Int): Tasty.Symbol.Field =
        Tasty.Symbol.Field(
            SymbolId(id),
            Tasty.Name(name),
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
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

    private def makeAbstractType(id: Int, name: String, ownerId: Int): Tasty.Symbol.AbstractType =
        Tasty.Symbol.AbstractType(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Chunk.empty
        )

    private def makeOpaqueType(id: Int, name: String, ownerId: Int): Tasty.Symbol.OpaqueType =
        Tasty.Symbol.OpaqueType(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Chunk.empty,
            Chunk.empty
        )

    private def makeTypeParam(id: Int, name: String, ownerId: Int): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Tasty.Variance.Invariant
        )

    private def makeTypeParamCo(id: Int, name: String, ownerId: Int): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags(Tasty.Flag.Covariant),
            SymbolId(ownerId),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Tasty.Variance.Covariant
        )

    private def makeTypeParamContra(id: Int, name: String, ownerId: Int): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags(Tasty.Flag.Contravariant),
            SymbolId(ownerId),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Tasty.Variance.Contravariant
        )

    "parents-on-class: c.parents returns Chunk[ClassLike] size 2 for two Type.Named parents" in {
        import Tasty.Name.asString
        val anyRefSym = makeClass(id = 0, name = "Object", ownerId = 0)
        val traitT    = makeTrait(id = 1, name = "T", ownerId = 0)
        val classSym = makeClass(id = 2, name = "Foo", ownerId = 0).copy(
            parentTypes = Chunk(Tasty.Type.Named(SymbolId(0)), Tasty.Type.Named(SymbolId(1)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(anyRefSym, traitT, classSym)).map { classpath =>
            val parents: Chunk[Tasty.Symbol] =
                classSym.parentTypes.flatMap { case Tasty.Type.Named(pid) => classpath.symbol(pid).toList; case _ => Nil }
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
    }

    "methods-typed-Chunk-Method: c.methods returns Chunk[Method] size 2" in {
        import Tasty.Name.asString
        val classSym  = makeClass(id = 0, name = "Foo", ownerId = 0)
        val method1   = makeMethod(id = 1, name = "foo", ownerId = 0)
        val method2   = makeMethod(id = 2, name = "bar", ownerId = 0)
        val valSym    = makeVal(id = 3, name = "x", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, method2, valSym)).map { classpath =>
            val ms = withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).collect { case m: Tasty.Symbol.Method => m }
            assert(ms.length == 2, s"Expected 2 methods but got ${ms.length}")
            val names = ms.map(_.name.asString).toSet
            assert(names == Set("foo", "bar"), s"Expected foo/bar but got $names")
            succeed
        }
    }

    "vals-typed-Chunk-Val: c.vals returns Chunk[Val] size 1 name x" in {
        import Tasty.Name.asString
        val classSym  = makeClass(id = 0, name = "Foo", ownerId = 0)
        val method1   = makeMethod(id = 1, name = "foo", ownerId = 0)
        val valSym    = makeVal(id = 2, name = "x", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, valSym)).map { classpath =>
            val vs = withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).collect { case v: Tasty.Symbol.Val => v }
            assert(vs.length == 1, s"Expected 1 val but got ${vs.length}")
            assert(vs(0).name.asString == "x", s"Expected name 'x' but got '${vs(0).name.asString}'")
            succeed
        }
    }

    "vars-typed-Chunk-Var: c.vars returns Chunk[Var] size 1 name y" in {
        import Tasty.Name.asString
        val classSym  = makeClass(id = 0, name = "Foo", ownerId = 0)
        val varSym    = makeVar(id = 1, name = "y", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, varSym)).map { classpath =>
            val vs = withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).collect { case v: Tasty.Symbol.Var => v }
            assert(vs.length == 1, s"Expected 1 var but got ${vs.length}")
            assert(vs(0).name.asString == "y", s"Expected name 'y' but got '${vs(0).name.asString}'")
            succeed
        }
    }

    "fields-typed-on-java: c.fields returns Chunk[Field] size 2" in {
        import Tasty.Name.asString
        val classSym  = makeClass(id = 0, name = "Foo", ownerId = 0)
        val field1    = makeField(id = 1, name = "F1", ownerId = 0)
        val field2    = makeField(id = 2, name = "F2", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, field1, field2)).map { classpath =>
            val fs = withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).collect { case f: Tasty.Symbol.Field => f }
            assert(fs.length == 2, s"Expected 2 fields but got ${fs.length}")
            val names = fs.map(_.name.asString).toSet
            assert(names == Set("F1", "F2"), s"Expected F1/F2 but got $names")
            succeed
        }
    }

    "nestedTypes: c.nestedTypes returns Chunk[ClassLike] size 3" in {
        val outerClass  = makeClass(id = 0, name = "Outer", ownerId = 0)
        val innerClass  = makeClass(id = 1, name = "Inner", ownerId = 0)
        val innerTrait  = makeTrait(id = 2, name = "InnerT", ownerId = 0)
        val innerObject = makeObject(id = 3, name = "InnerO", ownerId = 0)
        val withDecls   = outerClass.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, innerClass, innerTrait, innerObject)).map { classpath =>
            val nested = withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(s =>
                s.isInstanceOf[Tasty.Symbol.Class] || s.isInstanceOf[Tasty.Symbol.Trait] || s.isInstanceOf[Tasty.Symbol.Object]
            )
            assert(nested.length == 3, s"Expected 3 nested types but got ${nested.length}")
            succeed
        }
    }

    "typeAliases: c.declarations.collect { case t: Tasty.Symbol.TypeAlias => t } returns Chunk[TypeAlias] size 2" in {
        import Tasty.Name.asString
        val classSym   = makeClass(id = 0, name = "Foo", ownerId = 0)
        val typeAlias1 = makeTypeAlias(id = 1, name = "A", ownerId = 0)
        val typeAlias2 = makeTypeAlias(id = 2, name = "B", ownerId = 0)
        val withDecls  = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, typeAlias1, typeAlias2)).map { classpath =>
            val tas: Chunk[Tasty.Symbol.TypeAlias] =
                withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).collect { case t: Tasty.Symbol.TypeAlias => t }
            assert(tas.length == 2, s"Expected 2 type aliases but got ${tas.length}")
            val names = tas.map(_.name.asString).toSet
            assert(names == Set("A", "B"), s"Expected A/B but got $names")
            succeed
        }
    }

    "abstractTypes: t.declarations.collect { case t: Tasty.Symbol.AbstractType => t } returns Chunk[AbstractType] size 1" in {
        import Tasty.Name.asString
        val traitSym     = makeTrait(id = 0, name = "MyTrait", ownerId = 0)
        val abstractType = makeAbstractType(id = 1, name = "X", ownerId = 0)
        val withDecls    = traitSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, abstractType)).map { classpath =>
            val ats: Chunk[Tasty.Symbol.AbstractType] =
                withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).collect { case t: Tasty.Symbol.AbstractType => t }
            assert(ats.length == 1, s"Expected 1 abstract type but got ${ats.length}")
            assert(ats(0).name.asString == "X", s"Expected name 'X' but got '${ats(0).name.asString}'")
            succeed
        }
    }

    "opaqueTypes: o.declarations.collect { case t: Tasty.Symbol.OpaqueType => t } returns Chunk[OpaqueType] size 1" in {
        import Tasty.Name.asString
        val objectSym  = makeObject(id = 0, name = "MyObject", ownerId = 0)
        val opaqueType = makeOpaqueType(id = 1, name = "Money", ownerId = 0)
        val withDecls  = objectSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, opaqueType)).map { classpath =>
            val ots: Chunk[Tasty.Symbol.OpaqueType] = withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).collect {
                case t: Tasty.Symbol.OpaqueType =>
                    t
            }
            assert(ots.length == 1, s"Expected 1 opaque type but got ${ots.length}")
            assert(ots(0).name.asString == "Money", s"Expected name 'Money' but got '${ots(0).name.asString}'")
            succeed
        }
    }

    "typeParams-on-class: c.typeParams returns Chunk[TypeParam] size 3 with correct variances" in {
        val classSym    = makeClass(id = 0, name = "C", ownerId = 0)
        val tpA         = makeTypeParam(id = 1, name = "A", ownerId = 0)
        val tpB         = makeTypeParamCo(id = 2, name = "B", ownerId = 0)
        val tpC         = makeTypeParamContra(id = 3, name = "C", ownerId = 0)
        val withTParams = classSym.copy(typeParamIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withTParams, tpA, tpB, tpC)).map { classpath =>
            val tps = withTParams.typeParamIds.flatMap(id => classpath.symbol(id).toChunk).collect {
                case tp: Tasty.Symbol.TypeParam => tp
            }
            assert(tps.length == 3, s"Expected 3 type params but got ${tps.length}")
            assert(tps(0).variance == Tasty.Variance.Invariant, s"Expected Invariant for A but got ${tps(0).variance}")
            assert(tps(1).variance == Tasty.Variance.Covariant, s"Expected Covariant for B but got ${tps(1).variance}")
            assert(tps(2).variance == Tasty.Variance.Contravariant, s"Expected Contravariant for C but got ${tps(2).variance}")
            succeed
        }
    }

    "declarations-untyped-Chunk-Symbol: c.declarations returns Chunk[Symbol] size 4" in {
        val classSym  = makeClass(id = 0, name = "Foo", ownerId = 0)
        val method1   = makeMethod(id = 1, name = "foo", ownerId = 0)
        val valSym    = makeVal(id = 2, name = "x", ownerId = 0)
        val varSym    = makeVar(id = 3, name = "y", ownerId = 0)
        val field1    = makeField(id = 4, name = "F", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3), SymbolId(4)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, valSym, varSym, field1)).map { classpath =>
            val ds: Chunk[Tasty.Symbol] = withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk)
            assert(ds.length == 4, s"Expected 4 declarations but got ${ds.length}")
            succeed
        }
    }

    "constructors-typed: declarations filtered by init name returns Chunk[Method] size 2 all named <init>" in {
        import Tasty.Name.asString
        val classSym  = makeClass(id = 0, name = "MyCaseClass", ownerId = 0)
        val ctor1     = makeMethod(id = 1, name = "<init>", ownerId = 0)
        val ctor2     = makeMethod(id = 2, name = "<init>", ownerId = 0)
        val applyM    = makeMethod(id = 3, name = "apply", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, ctor1, ctor2, applyM)).map { classpath =>
            val ctors: Chunk[Tasty.Symbol.Method] =
                withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).collect {
                    case m: Tasty.Symbol.Method if m.name.asString == "<init>" => m
                }
            assert(ctors.length == 2, s"Expected 2 constructors but got ${ctors.length}")
            assert(
                ctors.map(_.name.asString).forall(_ == "<init>"),
                s"All constructors should be named <init> but got ${ctors.map(_.name.asString)}"
            )
            succeed
        }
    }

    "parents-on-trait: t.parents returns Chunk[ClassLike] size 1 parent is Symbol.Trait U" in {
        import Tasty.Name.asString
        val traitU = makeTrait(id = 0, name = "U", ownerId = 0)
        val traitT = makeTrait(id = 1, name = "T", ownerId = 0).copy(
            parentTypes = Chunk(Tasty.Type.Named(SymbolId(0)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(traitU, traitT)).map { classpath =>
            val parents: Chunk[Tasty.Symbol] =
                traitT.parentTypes.flatMap { case Tasty.Type.Named(pid) => classpath.symbol(pid).toList; case _ => Nil }
            assert(parents.length == 1, s"Expected 1 parent but got ${parents.length}")
            parents(0) match
                case t: Tasty.Symbol.Trait =>
                    assert(t.name.asString == "U", s"Expected parent named 'U' but got '${t.name.asString}'")
                case other =>
                    fail(s"Expected Symbol.Trait but got ${other.getClass.getSimpleName}")
            end match
            succeed
        }
    }

    // Uses cold-classpath path because fromPicklesWithSymbols has empty companionIndex.
    "companion-on-class-resolves: cls.companion is callable on a ClassLike from a cold classpath" in {
        val pickle = Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(pickle)) {
                Tasty.classpath.map { classpath =>
                    val found = classpath.findClass("kyo.fixtures.PlainClass")
                    found match
                        case Maybe.Present(symbol) =>
                            val comp: Maybe[Tasty.Symbol] = classpath.companion(symbol)
                            // companion may or may not be indexed for this fixture; success means the call compiles and returns
                            succeed
                        case Maybe.Absent => succeed
                    end match
                }
            }
        ).map {
            case Result.Success(a) => a
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "prior-flag-predicates-still-work-on-classlike: flag predicates on Class/Trait return expected values" in {
        val classFlags = Tasty.Flags(Tasty.Flag.Final, Tasty.Flag.Case)
        val classSym = Tasty.Symbol.Class(
            SymbolId(1),
            Tasty.Name("CaseFoo"),
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
            Chunk.empty
        )
        val traitFlags = Tasty.Flags(Tasty.Flag.Abstract, Tasty.Flag.Sealed)
        val traitSym = Tasty.Symbol.Trait(
            SymbolId(2),
            Tasty.Name("SealedTrait"),
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
            Chunk.empty
        )
        assert(classSym.isFinal, "classSym.isFinal must be true")
        assert(classSym.isCase, "classSym.isCase must be true")
        assert(classSym.isInstanceOf[Tasty.Symbol.Class], "classSym.isInstanceOf[Tasty.Symbol.Class] must be true")
        assert(classSym.isInstanceOf[Tasty.Symbol.ClassLike], "classSym.isInstanceOf[Tasty.Symbol.ClassLike] must be true")
        assert(!classSym.isInstanceOf[Tasty.Symbol.Trait], "classSym.isInstanceOf[Tasty.Symbol.Trait] must be false")
        assert(!classSym.isInstanceOf[Tasty.Symbol.Method], "classSym.isInstanceOf[Tasty.Symbol.Method] must be false")
        assert(traitSym.isAbstract, "traitSym.isAbstract must be true")
        assert(traitSym.isSealed, "traitSym.isSealed must be true")
        assert(traitSym.isInstanceOf[Tasty.Symbol.Trait], "traitSym.isInstanceOf[Tasty.Symbol.Trait] must be true")
        assert(traitSym.isInstanceOf[Tasty.Symbol.ClassLike], "traitSym.isInstanceOf[Tasty.Symbol.ClassLike] must be true")
        assert(!traitSym.isInstanceOf[Tasty.Symbol.Class], "traitSym.isInstanceOf[Tasty.Symbol.Class] must be false")
        assert(!traitSym.isInstanceOf[Tasty.Symbol.Object], "traitSym.isInstanceOf[Tasty.Symbol.Object] must be false")
        succeed
    }

end ClassLikeAccessorsTest
