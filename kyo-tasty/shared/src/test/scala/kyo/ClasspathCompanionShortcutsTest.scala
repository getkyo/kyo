package kyo

import kyo.Tasty.SymbolId

/** Tests for the companion-shortcut delegation from `object Tasty` to `Classpath` instance methods.
  *
  * Each test verifies that the companion shortcut (`Tasty.findX`, `Tasty.requireX`, `Tasty.allX`)
  * delegates correctly to the underlying pure `Classpath` instance method and returns an equal result.
  *
  * Fixture symbol layout (id == index in `symbols` Chunk):
  *   0  -> Class      "Dog"       owner=7 (shop)
  *   1  -> Trait      "Pettable"  owner=7 (shop)
  *   2  -> Object     "Singleton" owner=7 (shop)
  *   3  -> Method     "bark"      owner=0 (Dog)
  *   4  -> Val        "sound"     owner=0 (Dog)
  *   5  -> Var        "counter"   owner=0 (Dog)
  *   6  -> TypeAlias  "DogAlias"  owner=7 (shop)
  *   7  -> Package    "shop"      owner=-1
  */
class ClasspathCompanionShortcutsTest extends kyo.test.Test[Any]:

    import Tasty.Name.asString

    // ── fixture builders ────────────────────────────────────────────────────

    private val dogId       = SymbolId(0)
    private val pettableId  = SymbolId(1)
    private val singletonId = SymbolId(2)
    private val barkId      = SymbolId(3)
    private val soundId     = SymbolId(4)
    private val counterId   = SymbolId(5)
    private val aliasId     = SymbolId(6)
    private val shopId      = SymbolId(7)

    // Negative SymbolId represents an external symbol not in the fixture's symbols Chunk.
    // Classpath.findAnnotation resolves these via unresolvedFullNameByNegId.
    private val deprecatedExtId   = SymbolId(-1)
    private val deprecatedAnnType = Tasty.Type.Named(deprecatedExtId)
    private val deprecatedAnn     = Tasty.Annotation(deprecatedAnnType, Chunk.empty, Tasty.Name("scala.deprecated"))

    private val dogSym = Tasty.Symbol.Class(
        dogId,
        Tasty.Name("Dog"),
        Tasty.Flags.empty,
        shopId,
        Maybe.Absent,
        Maybe.Absent,
        Maybe.Absent,
        Chunk.empty,
        Chunk.empty,
        Chunk(barkId, soundId, counterId),
        Maybe.Absent,
        Chunk(deprecatedAnn),
        Chunk.empty
    )

    private val pettableSym = Tasty.Symbol.Trait(
        pettableId,
        Tasty.Name("Pettable"),
        Tasty.Flags.empty,
        shopId,
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

    private val singletonSym = Tasty.Symbol.Object(
        singletonId,
        Tasty.Name("Singleton"),
        Tasty.Flags.empty,
        shopId,
        Maybe.Absent,
        Maybe.Absent,
        Maybe.Absent,
        Chunk.empty,
        Chunk.empty,
        Chunk.empty,
        Chunk.empty,
        Chunk.empty
    )

    private val barkSym = Tasty.Symbol.Method(
        barkId,
        Tasty.Name("bark"),
        Tasty.Flags.empty,
        dogId,
        Maybe.Absent,
        Maybe.Absent,
        Maybe.Absent,
        Chunk.empty,
        Chunk.empty,
        Chunk.empty,
        Maybe.Absent
    )

    private val soundSym = Tasty.Symbol.Val(
        soundId,
        Tasty.Name("sound"),
        Tasty.Flags.empty,
        dogId,
        Maybe.Absent,
        Maybe.Absent,
        Maybe.Absent,
        Chunk.empty
    )

    private val counterSym = Tasty.Symbol.Var(
        counterId,
        Tasty.Name("counter"),
        Tasty.Flags.empty,
        dogId,
        Maybe.Absent,
        Maybe.Absent,
        Maybe.Absent,
        Chunk.empty
    )

    private val aliasSym = Tasty.Symbol.TypeAlias(
        aliasId,
        Tasty.Name("DogAlias"),
        Tasty.Flags.empty,
        shopId,
        Maybe.Absent,
        Maybe.Absent,
        Maybe.Absent,
        Chunk.empty,
        Chunk.empty
    )

    private val shopPkg = Tasty.Symbol.Package(
        shopId,
        Tasty.Name("shop"),
        Tasty.Flags.empty,
        SymbolId(-1),
        Chunk(dogId, pettableId, singletonId, aliasId)
    )

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            Tasty.Classpath.make(
                symbols = Chunk(dogSym, pettableSym, singletonSym, barkSym, soundSym, counterSym, aliasSym, shopPkg),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(dogId, pettableId, singletonId),
                packageIds = Chunk(shopId),
                fullNameIndex = Dict(
                    "shop.Dog"       -> dogId,
                    "shop.Pettable"  -> pettableId,
                    "shop.Singleton" -> singletonId,
                    "shop.DogAlias"  -> aliasId
                ),
                packageIndex = Dict("shop" -> shopId, "shop.Dog" -> dogId),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                unresolvedFullNameByNegId = Dict(deprecatedExtId -> "scala.deprecated"),
                errors = Chunk.empty
            )
        }

    // ── helpers ─────────────────────────────────────────────────────────────

    private def withFixture[A](body: Tasty.Classpath => A < Sync)(using Frame): A < Sync =
        buildFixture.map { classpath =>
            Tasty.withClasspath(classpath) {
                body(classpath)
            }
        }

    // ── find* shortcut tests (leaves 1-9) ───────────────────────────────────

    "findClass shortcut delegates to canonical" in {
        withFixture { classpath =>
            Tasty.findClass("shop.Dog").map { shortcutResult =>
                val directResult = classpath.findClass("shop.Dog")
                assert(
                    shortcutResult == directResult,
                    s"findClass shortcut must equal classpath.findClass; got $shortcutResult vs $directResult"
                )
                assert(shortcutResult.isDefined, "findClass must return Present for shop.Dog")
                succeed
            }
        }
    }

    "findTrait shortcut delegates to canonical" in {
        withFixture { classpath =>
            Tasty.findTrait("shop.Pettable").map { shortcutResult =>
                val directResult = classpath.findTrait("shop.Pettable")
                assert(
                    shortcutResult == directResult,
                    s"findTrait shortcut must equal classpath.findTrait; got $shortcutResult vs $directResult"
                )
                assert(shortcutResult.isDefined, "findTrait must return Present for shop.Pettable")
                succeed
            }
        }
    }

    "findObject shortcut delegates to canonical" in {
        withFixture { classpath =>
            Tasty.findObject("shop.Singleton").map { shortcutResult =>
                val directResult = classpath.findObject("shop.Singleton")
                assert(
                    shortcutResult == directResult,
                    s"findObject shortcut must equal classpath.findObject; got $shortcutResult vs $directResult"
                )
                assert(shortcutResult.isDefined, "findObject must return Present for shop.Singleton")
                succeed
            }
        }
    }

    "findClassLike shortcut delegates to canonical" in {
        withFixture { classpath =>
            Tasty.findClassLike("shop.Dog").map { shortcutResult =>
                val directResult = classpath.findClassLike("shop.Dog")
                assert(
                    shortcutResult == directResult,
                    s"findClassLike shortcut must equal classpath.findClassLike; got $shortcutResult vs $directResult"
                )
                assert(shortcutResult.isDefined, "findClassLike must return Present for shop.Dog")
                succeed
            }
        }
    }

    "findPackage shortcut delegates to canonical" in {
        withFixture { classpath =>
            Tasty.findPackage("shop").map { shortcutResult =>
                val directResult = classpath.findPackage("shop")
                assert(
                    shortcutResult == directResult,
                    s"findPackage shortcut must equal classpath.findPackage; got $shortcutResult vs $directResult"
                )
                assert(shortcutResult.isDefined, "findPackage must return Present for shop")
                succeed
            }
        }
    }

    "findSymbol shortcut delegates to canonical" in {
        withFixture { classpath =>
            Tasty.findSymbol("shop.Dog").map { shortcutResult =>
                val directResult = classpath.findSymbol("shop.Dog")
                assert(
                    shortcutResult == directResult,
                    s"findSymbol shortcut must equal classpath.findSymbol; got $shortcutResult vs $directResult"
                )
                assert(shortcutResult.isDefined, "findSymbol must return Present for shop.Dog")
                succeed
            }
        }
    }

    "findMember is a pure Classpath instance method (no companion shortcut)" in {
        withFixture { classpath =>
            val result = classpath.findMember(dogSym, "bark")
            assert(result.isDefined, "findMember must return Present for bark on Dog")
            succeed
        }
    }

    "findDeclaredMember is a pure Classpath instance method (no companion shortcut)" in {
        withFixture { classpath =>
            val result = classpath.findDeclaredMember(dogSym, "bark")
            assert(result.isDefined, "findDeclaredMember must return Present for bark on Dog")
            succeed
        }
    }

    "findAnnotation is a pure Classpath instance method (no companion shortcut)" in {
        withFixture { classpath =>
            val result = classpath.findAnnotation(dogSym, "scala.deprecated")
            assert(result.isDefined, "findAnnotation must return Present for @scala.deprecated on Dog")
            succeed
        }
    }

    // ── require* shortcut tests (leaves 10-15) ──────────────────────────────

    "requireClass shortcut success and absent paths" in {
        withFixture { _ =>
            Abort.run[TastyError](Tasty.requireClass("shop.Dog")).map {
                case Result.Success(cls) =>
                    assert(cls.name.asString == "Dog", s"requireClass returned wrong name: ${cls.name.asString}")
                    Abort.run[TastyError](Tasty.requireClass("shop.Missing")).map {
                        case Result.Failure(TastyError.NotFound(_)) => succeed
                        case other                                  => fail(s"Expected NotFound but got $other")
                    }
                case other => fail(s"Expected Success for shop.Dog but got $other")
            }
        }
    }

    "requireTrait shortcut success and absent paths" in {
        withFixture { _ =>
            Abort.run[TastyError](Tasty.requireTrait("shop.Pettable")).map {
                case Result.Success(trt) =>
                    assert(trt.name.asString == "Pettable", s"requireTrait returned wrong name: ${trt.name.asString}")
                    Abort.run[TastyError](Tasty.requireTrait("shop.Missing")).map {
                        case Result.Failure(TastyError.NotFound(_)) => succeed
                        case other                                  => fail(s"Expected NotFound but got $other")
                    }
                case other => fail(s"Expected Success for shop.Pettable but got $other")
            }
        }
    }

    "requireObject shortcut success and absent paths" in {
        withFixture { _ =>
            Abort.run[TastyError](Tasty.requireObject("shop.Singleton")).map {
                case Result.Success(obj) =>
                    assert(obj.name.asString == "Singleton", s"requireObject returned wrong name: ${obj.name.asString}")
                    Abort.run[TastyError](Tasty.requireObject("shop.Missing")).map {
                        case Result.Failure(TastyError.NotFound(_)) => succeed
                        case other                                  => fail(s"Expected NotFound but got $other")
                    }
                case other => fail(s"Expected Success for shop.Singleton but got $other")
            }
        }
    }

    "requireClassLike shortcut success and absent paths" in {
        withFixture { _ =>
            Abort.run[TastyError](Tasty.requireClassLike("shop.Dog")).map {
                case Result.Success(cls) =>
                    assert(cls.name.asString == "Dog", s"requireClassLike returned wrong name: ${cls.name.asString}")
                    Abort.run[TastyError](Tasty.requireClassLike("shop.Missing")).map {
                        case Result.Failure(TastyError.NotFound(_)) => succeed
                        case other                                  => fail(s"Expected NotFound but got $other")
                    }
                case other => fail(s"Expected Success for shop.Dog but got $other")
            }
        }
    }

    "requirePackage shortcut success and absent paths" in {
        withFixture { _ =>
            Abort.run[TastyError](Tasty.requirePackage("shop")).map {
                case Result.Success(pkg) =>
                    assert(pkg.name.asString == "shop", s"requirePackage returned wrong name: ${pkg.name.asString}")
                    Abort.run[TastyError](Tasty.requirePackage("shop.Missing")).map {
                        case Result.Failure(TastyError.NotFound(_)) => succeed
                        case other                                  => fail(s"Expected NotFound but got $other")
                    }
                case other => fail(s"Expected Success for shop but got $other")
            }
        }
    }

    "requireSymbol shortcut success and absent paths" in {
        withFixture { _ =>
            Abort.run[TastyError](Tasty.requireSymbol("shop.Dog")).map {
                case Result.Success(symbol) =>
                    assert(symbol.name.asString == "Dog", s"requireSymbol returned wrong name: ${symbol.name.asString}")
                    Abort.run[TastyError](Tasty.requireSymbol("shop.Missing")).map {
                        case Result.Failure(TastyError.NotFound(_)) => succeed
                        case other                                  => fail(s"Expected NotFound but got $other")
                    }
                case other => fail(s"Expected Success for shop.Dog but got $other")
            }
        }
    }

    // ── all* shortcut tests (leaves 16-25) ──────────────────────────────────

    "allClasses shortcut equals canonical" in {
        withFixture { classpath =>
            Tasty.allClasses.map { shortcutResult =>
                val directResult = classpath.allClasses
                assert(shortcutResult == directResult, s"allClasses shortcut must equal classpath.allClasses")
                assert(shortcutResult.nonEmpty, "allClasses must include shop.Dog")
                succeed
            }
        }
    }

    "allTraits shortcut equals canonical" in {
        withFixture { classpath =>
            Tasty.allTraits.map { shortcutResult =>
                val directResult = classpath.allTraits
                assert(shortcutResult == directResult, s"allTraits shortcut must equal classpath.allTraits")
                assert(shortcutResult.nonEmpty, "allTraits must include shop.Pettable")
                succeed
            }
        }
    }

    "allObjects shortcut equals canonical" in {
        withFixture { classpath =>
            Tasty.allObjects.map { shortcutResult =>
                val directResult = classpath.allObjects
                assert(shortcutResult == directResult, s"allObjects shortcut must equal classpath.allObjects")
                assert(shortcutResult.nonEmpty, "allObjects must include shop.Singleton")
                succeed
            }
        }
    }

    "allClassLikes shortcut equals canonical" in {
        withFixture { classpath =>
            Tasty.allClassLike.map { shortcutResult =>
                val directResult = classpath.allClassLike
                assert(shortcutResult == directResult, s"allClassLike shortcut must equal classpath.allClassLike")
                assert(shortcutResult.nonEmpty, "allClassLike must include shop.Dog")
                succeed
            }
        }
    }

    "allPackages shortcut equals canonical" in {
        withFixture { classpath =>
            Tasty.allPackages.map { shortcutResult =>
                val directResult = classpath.allPackages
                assert(shortcutResult == directResult, s"allPackages shortcut must equal classpath.allPackages")
                assert(shortcutResult.nonEmpty, "allPackages must include shop")
                succeed
            }
        }
    }

    "allSymbols shortcut equals canonical" in {
        withFixture { classpath =>
            Tasty.allSymbols.map { shortcutResult =>
                val directResult = classpath.allSymbols
                assert(shortcutResult == directResult, s"allSymbols shortcut must equal classpath.allSymbols")
                assert(shortcutResult.nonEmpty, "allSymbols must include shop.Dog")
                succeed
            }
        }
    }

    "allMethods shortcut equals canonical" in {
        withFixture { classpath =>
            Tasty.allMethods.map { shortcutResult =>
                val directResult = classpath.allMethods
                assert(shortcutResult == directResult, s"allMethods shortcut must equal classpath.allMethods")
                assert(shortcutResult.nonEmpty, "allMethods must include bark")
                succeed
            }
        }
    }

    "allVals shortcut equals canonical" in {
        withFixture { classpath =>
            Tasty.allVals.map { shortcutResult =>
                val directResult = classpath.allVals
                assert(shortcutResult == directResult, s"allVals shortcut must equal classpath.allVals")
                assert(shortcutResult.nonEmpty, "allVals must include sound")
                succeed
            }
        }
    }

    "allVars shortcut equals canonical" in {
        withFixture { classpath =>
            Tasty.allVars.map { shortcutResult =>
                val directResult = classpath.allVars
                assert(shortcutResult == directResult, s"allVars shortcut must equal classpath.allVars")
                assert(shortcutResult.nonEmpty, "allVars must include counter")
                succeed
            }
        }
    }

    "allTypeAliases shortcut equals canonical" in {
        withFixture { classpath =>
            Tasty.allTypeAliases.map { shortcutResult =>
                val directResult = classpath.allTypeAliases
                assert(shortcutResult == directResult, s"allTypeAliases shortcut must equal classpath.allTypeAliases")
                assert(shortcutResult.nonEmpty, "allTypeAliases must include DogAlias")
                succeed
            }
        }
    }

    // ── 14-leaf enumeration tests (leaves 26-27) ────────────────────────────

    "14-leaf enumeration on Classpath.findClass; fixture resolving to Symbol.Trait" in {
        withFixture { classpath =>
            // "shop.Pettable" is a Trait, not a Class; findClass must return Absent, not swallow
            val result = classpath.findClass("shop.Pettable")
            assert(result == Maybe.Absent, s"findClass must return Absent for a Trait fully-qualified name; got $result")
            succeed
        }
    }

    "14-leaf enumeration on Classpath.findPackage; fixture resolving to Symbol.Class" in {
        withFixture { classpath =>
            // "shop.Dog" is registered in packageIndex with dogId (a Symbol.Class, not a Package).
            // findPackage reaches symbol(dogId) and hits the _: Symbol.Class => Maybe.Absent arm,
            // exercising the 14-leaf enumeration and proving the discrimination is correct.
            val result = classpath.findPackage("shop.Dog")
            assert(result == Maybe.Absent, s"findPackage must return Absent for a Class fully-qualified name; got $result")
            succeed
        }
    }

    // ── findMethod guard-failure regression (leaf 28) ───────────────────────

    "findMethod returns Absent without MatchError when method name does not match any declaration" in {
        withFixture { _ =>
            // Dog declares "bark" but not "woof". The inner match in findMethod iterates over
            // declarationIds; when it encounters barkId (a Symbol.Method whose name != "woof"),
            // the guarded arm fails. Without the unguarded `_: Symbol.Method => Chunk.empty` arm
            // this would throw a MatchError. After the fix it must return Absent.
            Tasty.findMethod("shop.Dog", "woof").map { result =>
                assert(result == Maybe.Absent, s"findMethod must return Absent for non-existent method; got $result")
                succeed
            }
        }
    }

    // ── equals carve-out tests (leaves 29-31) ───────────────────────────────

    "equals on Symbol; Symbol vs Symbol equal" in {
        val s1: Tasty.Symbol = dogSym
        val s2: Tasty.Symbol = dogSym
        assert(s1 == s2, "Two references to the same Symbol must be equal")
        succeed
    }

    "equals on Symbol; Symbol vs String returns false" in {
        val symbol: Tasty.Symbol = dogSym
        assert(!symbol.equals("foo"), "Symbol.equals must return false when compared to a String")
        succeed
    }

    "Indices.equals returns false for non-Indices" in {
        val indices = Tasty.Classpath.Indices.empty
        assert(!indices.equals("other"), "Indices.equals must return false for non-Indices values")
        succeed
    }

    // Every sealed-ADT match in kyo-tasty/shared/src/main enumerates all cases explicitly.
    // The only surviving `case _ =>` arms match open types (Any receiver) or opaque types,
    // each preceded by a // Carve-out: comment. The two open-type cases verified below
    // return false for non-matching values, which is the correct behavior for equals overrides.

    "Symbol.equals and Indices.equals return false for non-matching types" in {
        val symbol: Tasty.Symbol = dogSym
        val nonSym: Any          = 42
        assert(!(symbol.equals(nonSym)), "Symbol.equals(Any) must return false for a non-Symbol value")

        val indices: Tasty.Classpath.Indices = Tasty.Classpath.Indices.empty
        val nonIndices: Any                  = "not indices"
        assert(!(indices.equals(nonIndices)), "Indices.equals(Any) must return false for a non-Indices value")

        succeed
    }

end ClasspathCompanionShortcutsTest
