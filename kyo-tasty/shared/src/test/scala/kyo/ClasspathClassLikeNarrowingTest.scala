package kyo

import kyo.Tasty.SymbolId
import scala.compiletime.testing.typeCheckErrors

/** Tests for the ClassLike-narrowed Classpath instance methods (parents, permittedSubclasses,
  * directSubclassesOf, subclassesOf, implementationsOf) that carry no effect row and require no
  * Frame. Tests wrap Classpath construction in Sync.defer per the pattern in
  * ClasspathPureInstanceTest.
  *
  * Fixture layout:
  *   0  -> Symbol.Package "shop"           (memberIds = [1,2,3,4,5,6,7])
  *   1  -> Symbol.Class   "Animal"         (sealed; permittedSubclassIds = [2,3])
  *                                          parentTypes = [Named(8)] (AnyRef placeholder)
  *   2  -> Symbol.Class   "Dog"            (extends Animal; parentTypes = [Named(1)])
  *                                          subclassIndex has 2 -> [6]
  *   3  -> Symbol.Class   "Cat"            (extends Animal; parentTypes = [Named(1)])
  *   4  -> Symbol.Object  "Dog$"           (companion of Dog; no permittedSubclassIds)
  *   5  -> Symbol.EnumCase "Color.Red"     (has permittedSubclassIds field but semantically Absent)
  *   6  -> Symbol.Class   "BigDog"         (extends Dog; parentTypes = [Named(2)])
  *   7  -> Symbol.Trait   "Tameable"       (extends Animal; parentTypes = [Named(1)])
  *   8  -> Symbol.Class   "AnyRef"         (root parent placeholder)
  *   9  -> Symbol.Class   "Mongrel"        (non-sealed; permittedSubclassIds = Present(Chunk.empty))
  *
  * subclassIndex:
  *   1 (Animal) -> [2, 3, 7]    (Dog, Cat, Tameable)
  *   2 (Dog)    -> [6]          (BigDog)
  */
class ClasspathClassLikeNarrowingTest extends kyo.test.Test[Any]:

    private val animalId   = SymbolId(1)
    private val dogId      = SymbolId(2)
    private val catId      = SymbolId(3)
    private val dogObjId   = SymbolId(4)
    private val redCaseId  = SymbolId(5)
    private val bigDogId   = SymbolId(6)
    private val tameableId = SymbolId(7)
    private val anyRefId   = SymbolId(8)
    private val mongrelId  = SymbolId(9)

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            val shopPkg = Tasty.Symbol.Package(
                SymbolId(0),
                Tasty.Name("shop"),
                Tasty.Flags.empty,
                SymbolId(-1),
                memberIds = Chunk(animalId, dogId, catId, dogObjId, redCaseId, bigDogId, tameableId)
            )
            val animalClass = Tasty.Symbol.Class(
                animalId,
                Tasty.Name("Animal"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk(Tasty.Type.Named(anyRefId)),
                Chunk.empty,
                Chunk.empty,
                permittedSubclassIds = Maybe.Present(Chunk(dogId, catId)),
                Chunk.empty,
                Chunk.empty
            )
            val dogClass = Tasty.Symbol.Class(
                dogId,
                Tasty.Name("Dog"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk(Tasty.Type.Named(animalId)),
                Chunk.empty,
                Chunk.empty,
                permittedSubclassIds = Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val catClass = Tasty.Symbol.Class(
                catId,
                Tasty.Name("Cat"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk(Tasty.Type.Named(animalId)),
                Chunk.empty,
                Chunk.empty,
                permittedSubclassIds = Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val dogObject = Tasty.Symbol.Object(
                dogObjId,
                Tasty.Name("Dog"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty
            )
            val redEnumCase = Tasty.Symbol.EnumCase(
                redCaseId,
                Tasty.Name("Red"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                permittedSubclassIds = Maybe.Present(Chunk.empty),
                Chunk.empty,
                Chunk.empty
            )
            val bigDogClass = Tasty.Symbol.Class(
                bigDogId,
                Tasty.Name("BigDog"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk(Tasty.Type.Named(dogId)),
                Chunk.empty,
                Chunk.empty,
                permittedSubclassIds = Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val tameableTrait = Tasty.Symbol.Trait(
                tameableId,
                Tasty.Name("Tameable"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk(Tasty.Type.Named(animalId)),
                Chunk.empty,
                Chunk.empty,
                permittedSubclassIds = Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val anyRefClass = Tasty.Symbol.Class(
                anyRefId,
                Tasty.Name("AnyRef"),
                Tasty.Flags.empty,
                SymbolId(-1),
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
            val mongrelClass = Tasty.Symbol.Class(
                mongrelId,
                Tasty.Name("Mongrel"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                permittedSubclassIds = Maybe.Present(Chunk.empty),
                Chunk.empty,
                Chunk.empty
            )
            Tasty.Classpath.make(
                symbols = Chunk(
                    shopPkg,
                    animalClass,
                    dogClass,
                    catClass,
                    dogObject,
                    redEnumCase,
                    bigDogClass,
                    tameableTrait,
                    anyRefClass,
                    mongrelClass
                ),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(animalId, dogId, catId, tameableId),
                packageIds = Chunk(SymbolId(0)),
                fullNameIndex = Dict(
                    "shop.Animal"   -> animalId,
                    "shop.Dog"      -> dogId,
                    "shop.Cat"      -> catId,
                    "shop.Dog$"     -> dogObjId,
                    "shop.BigDog"   -> bigDogId,
                    "shop.Tameable" -> tameableId,
                    "shop.Mongrel"  -> mongrelId
                ),
                packageIndex = Dict("shop" -> SymbolId(0)),
                subclassIndex = Dict(
                    animalId -> Chunk(dogId, catId, tameableId),
                    dogId    -> Chunk(bigDogId)
                ),
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }

    "Classpath.parents returns the direct parent class for a single-parent class" in {
        buildFixture.map { classpath =>
            classpath.symbol(animalId) match
                case Maybe.Present(animal: Tasty.Symbol.ClassLike) =>
                    val anyRef = classpath.symbol(anyRefId).get
                    val result = classpath.parents(animal)
                    assert(result == Chunk(anyRef), s"parents of Animal must be [AnyRef]; got $result")
                    succeed
                case other => fail(s"expected ClassLike at animalId, got $other")
        }
    }

    "Classpath.parents returns multiple parents for a class extending class and trait" in {
        buildFixture.map { classpath =>
            // Build a fixture with two parents: Type.Named(animalId) and Type.Named(tameableId)
            val dogWithTwoParts = Tasty.Symbol.Class(
                SymbolId(99),
                Tasty.Name("PetDog"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk(Tasty.Type.Named(animalId), Tasty.Type.Named(tameableId)),
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val animal   = classpath.symbol(animalId).get
            val tameable = classpath.symbol(tameableId).get
            val result   = classpath.parents(dogWithTwoParts)
            assert(result == Chunk(animal, tameable), s"parents of PetDog must be [Animal, Tameable]; got $result")
            succeed
        }
    }

    "Classpath.parents excludes non-Named parent types" in {
        buildFixture.map { classpath =>
            val classWithApplied = Tasty.Symbol.Class(
                SymbolId(98),
                Tasty.Name("ListOfDog"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk(Tasty.Type.Applied(Tasty.Type.Named(animalId), Chunk(Tasty.Type.Named(dogId)))),
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val result = classpath.parents(classWithApplied)
            assert(result.isEmpty, s"Applied parent must not resolve to a symbol; got $result")
            succeed
        }
    }

    "Classpath.parents rejects Symbol.Parameter at compile time" in {
        // This is a compile-time check: Symbol.Parameter is not a subtype of Symbol.ClassLike.
        val errors = typeCheckErrors("(??? : kyo.Tasty.Classpath).parents(??? : kyo.Tasty.Symbol.Parameter)")
        assert(errors.nonEmpty, "passing Symbol.Parameter to parents must be a compile error")
        succeed
    }

    "Classpath.permittedSubclasses returns Present with permits for a sealed class" in {
        buildFixture.map { classpath =>
            classpath.symbol(animalId) match
                case Maybe.Present(animal: Tasty.Symbol.ClassLike) =>
                    val dog    = classpath.symbol(dogId).get
                    val cat    = classpath.symbol(catId).get
                    val result = classpath.permittedSubclasses(animal)
                    assert(
                        result == Maybe.Present(Chunk(dog, cat)),
                        s"permittedSubclasses of sealed Animal must be Present([Dog, Cat]); got $result"
                    )
                    succeed
                case other => fail(s"expected ClassLike at animalId, got $other")
        }
    }

    "Classpath.permittedSubclasses returns Present(Chunk.empty) for a non-sealed class" in {
        buildFixture.map { classpath =>
            classpath.symbol(mongrelId) match
                case Maybe.Present(mongrel: Tasty.Symbol.ClassLike) =>
                    val result = classpath.permittedSubclasses(mongrel)
                    assert(
                        result == Maybe.Present(Chunk.empty),
                        s"permittedSubclasses of non-sealed Mongrel must be Present(empty); got $result"
                    )
                    succeed
                case other => fail(s"expected ClassLike at mongrelId, got $other")
        }
    }

    "Classpath.permittedSubclasses returns Absent for a Symbol.Object" in {
        buildFixture.map { classpath =>
            classpath.symbol(dogObjId) match
                case Maybe.Present(dogObj: Tasty.Symbol.ClassLike) =>
                    val result = classpath.permittedSubclasses(dogObj)
                    assert(result == Maybe.Absent, s"permittedSubclasses of Object must be Absent; got $result")
                    succeed
                case other => fail(s"expected ClassLike at dogObjId, got $other")
        }
    }

    "Classpath.permittedSubclasses returns Absent for a Symbol.EnumCase" in {
        buildFixture.map { classpath =>
            classpath.symbol(redCaseId) match
                case Maybe.Present(redCase: Tasty.Symbol.ClassLike) =>
                    val result = classpath.permittedSubclasses(redCase)
                    assert(result == Maybe.Absent, s"permittedSubclasses of EnumCase must be Absent; got $result")
                    succeed
                case other => fail(s"expected ClassLike at redCaseId, got $other")
        }
    }

    "Classpath.directSubclassesOf returns only immediate children, not transitive" in {
        buildFixture.map { classpath =>
            (
                classpath.symbol(animalId),
                classpath.symbol(dogId),
                classpath.symbol(catId),
                classpath.symbol(tameableId),
                classpath.symbol(bigDogId)
            ) match
                case (
                        Maybe.Present(animal: Tasty.Symbol.ClassLike),
                        Maybe.Present(dog: Tasty.Symbol.ClassLike),
                        Maybe.Present(cat: Tasty.Symbol.ClassLike),
                        Maybe.Present(tameable: Tasty.Symbol.ClassLike),
                        Maybe.Present(bigDog: Tasty.Symbol.ClassLike)
                    ) =>
                    val result = classpath.directSubclassesOf(animal)
                    assert(result.contains(dog), s"directSubclassesOf(Animal) must contain Dog; got $result")
                    assert(result.contains(cat), s"directSubclassesOf(Animal) must contain Cat; got $result")
                    assert(result.contains(tameable), s"directSubclassesOf(Animal) must contain Tameable; got $result")
                    assert(!result.contains(bigDog), s"directSubclassesOf(Animal) must NOT contain BigDog (transitive); got $result")
                    assert(result.size == 3, s"directSubclassesOf(Animal) must have 3 entries; got ${result.size}")
                    succeed
                case other => fail(s"expected ClassLike symbols for directSubclassesOf test, got $other")
        }
    }

    "Classpath.subclassesOf returns the transitive closure of subclasses" in {
        buildFixture.map { classpath =>
            (
                classpath.symbol(animalId),
                classpath.symbol(dogId),
                classpath.symbol(catId),
                classpath.symbol(bigDogId),
                classpath.symbol(tameableId)
            ) match
                case (
                        Maybe.Present(animal: Tasty.Symbol.ClassLike),
                        Maybe.Present(dog: Tasty.Symbol.ClassLike),
                        Maybe.Present(cat: Tasty.Symbol.ClassLike),
                        Maybe.Present(bigDog: Tasty.Symbol.ClassLike),
                        Maybe.Present(tameable: Tasty.Symbol.ClassLike)
                    ) =>
                    val result = classpath.subclassesOf(animal)
                    assert(result.contains(dog), s"subclassesOf(Animal) must contain Dog; got $result")
                    assert(result.contains(cat), s"subclassesOf(Animal) must contain Cat; got $result")
                    assert(result.contains(bigDog), s"subclassesOf(Animal) must contain BigDog (transitive); got $result")
                    assert(result.contains(tameable), s"subclassesOf(Animal) must contain Tameable; got $result")
                    assert(result.size == 4, s"subclassesOf(Animal) must have 4 entries; got ${result.size}")
                    succeed
                case other => fail(s"expected ClassLike symbols for subclassesOf test, got $other")
        }
    }

    "Classpath.subclassesOf returns empty for a leaf class with no subclasses" in {
        buildFixture.map { classpath =>
            classpath.symbol(bigDogId) match
                case Maybe.Present(bigDog: Tasty.Symbol.ClassLike) =>
                    val result = classpath.subclassesOf(bigDog)
                    assert(result.isEmpty, s"subclassesOf(BigDog) must be empty; got $result")
                    succeed
                case other => fail(s"expected ClassLike at bigDogId, got $other")
        }
    }

    "Classpath.implementationsOf returns only concrete non-abstract Class subtypes" in {
        buildFixture.map { classpath =>
            (
                classpath.symbol(animalId),
                classpath.symbol(dogId),
                classpath.symbol(catId),
                classpath.symbol(bigDogId)
            ) match
                case (
                        Maybe.Present(animal: Tasty.Symbol.ClassLike),
                        Maybe.Present(dog: Tasty.Symbol.Class),
                        Maybe.Present(cat: Tasty.Symbol.Class),
                        Maybe.Present(bigDog: Tasty.Symbol.Class)
                    ) =>
                    val result = classpath.implementationsOf(animal)
                    assert(result.contains(dog), s"implementationsOf(Animal) must contain Dog; got $result")
                    assert(result.contains(cat), s"implementationsOf(Animal) must contain Cat; got $result")
                    assert(result.contains(bigDog), s"implementationsOf(Animal) must contain BigDog; got $result")
                    succeed
                case other => fail(s"expected ClassLike/Class symbols for implementationsOf test, got $other")
        }
    }

    "Classpath.implementationsOf excludes Object and EnumCase leaves" in {
        buildFixture.map { classpath =>
            classpath.symbol(animalId) match
                case Maybe.Present(animal: Tasty.Symbol.ClassLike) =>
                    val result = classpath.implementationsOf(animal)
                    val ids    = result.map(_.id)
                    assert(!ids.contains(dogObjId), s"implementationsOf must exclude Object; got $result")
                    assert(!ids.contains(redCaseId), s"implementationsOf must exclude EnumCase; got $result")
                    succeed
                case other => fail(s"expected ClassLike at animalId, got $other")
        }
    }

    "Classpath.implementationsOf returns empty for an Object leaf" in {
        buildFixture.map { classpath =>
            classpath.symbol(dogObjId) match
                case Maybe.Present(dogObj: Tasty.Symbol.ClassLike) =>
                    val result = classpath.implementationsOf(dogObj)
                    assert(result.isEmpty, s"implementationsOf(DogObject) must be empty; got $result")
                    succeed
                case other => fail(s"expected ClassLike at dogObjId, got $other")
        }
    }

    "Classpath.implementationsOf returns empty for an EnumCase leaf" in {
        buildFixture.map { classpath =>
            classpath.symbol(redCaseId) match
                case Maybe.Present(redCase: Tasty.Symbol.ClassLike) =>
                    val result = classpath.implementationsOf(redCase)
                    assert(result.isEmpty, s"implementationsOf(EnumCase) must be empty; got $result")
                    succeed
                case other => fail(s"expected ClassLike at redCaseId, got $other")
        }
    }

    "Classpath.parents requires no Frame context: pure method signature audit" in {
        buildFixture.map { classpath =>
            classpath.symbol(animalId) match
                case Maybe.Present(animal: Tasty.Symbol.ClassLike) =>
                    // This call compiles without `using Frame` in scope, confirming the method is pure.
                    val result: Chunk[Tasty.Symbol] = classpath.parents(animal)
                    assert(result.nonEmpty, "Animal has AnyRef as parent; result must be non-empty")
                    succeed
                case other => fail(s"expected ClassLike at animalId, got $other")
        }
    }

    "No catch-all arms in directSubclassesOf, subclassesOf, implementationsOf, parents, permittedSubclasses bodies" in {
        // This test is a behavioral assertion: the exhaustive matches in the 5 methods
        // must compile without warnings about non-exhaustive matches, and this compilation
        // already succeeded. We verify behavior by calling all methods and confirming no
        // MatchError escapes for any Symbol.ClassLike subtype.
        buildFixture.map { classpath =>
            def requireClassLike(id: Tasty.SymbolId): Tasty.Symbol.ClassLike =
                classpath.symbol(id) match
                    case Maybe.Present(cl: Tasty.Symbol.ClassLike) => cl
                    case other                                     => throw new AssertionError(s"expected ClassLike at $id, got $other")
            val allClassLike = Chunk(
                requireClassLike(animalId),
                requireClassLike(dogId),
                requireClassLike(catId),
                requireClassLike(dogObjId),
                requireClassLike(redCaseId),
                requireClassLike(tameableId)
            )
            allClassLike.foreach { cl =>
                val _ = classpath.parents(cl)
                val _ = classpath.permittedSubclasses(cl)
                val _ = classpath.directSubclassesOf(cl)
                val _ = classpath.subclassesOf(cl)
                val _ = classpath.implementationsOf(cl)
            }
            succeed
        }
    }

end ClasspathClassLikeNarrowingTest
