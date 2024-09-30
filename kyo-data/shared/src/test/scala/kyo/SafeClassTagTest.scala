package kyo

case class CustomInt(value: Int) extends AnyVal

class SafeClassTagTest extends Test:

    trait Animal
    trait Mammal  extends Animal
    trait Reptile extends Animal
    class Dog     extends Mammal
    class Cat     extends Mammal
    class Snake   extends Reptile

    trait Swimmer
    trait Flyer

    class Duck    extends Animal with Swimmer with Flyer
    class Penguin extends Animal with Swimmer

    opaque type UserId = String
    object UserId:
        def apply(id: String): UserId = id

    enum Color:
        case Red, Green, Blue

    case class Person(name: String, age: Int)

    "single types" - {
        "primitive types" - {
            "Int" in {
                val intTag = SafeClassTag[Int]
                assert(intTag.accepts(42))
                assert(!intTag.accepts("42"))
                assert(intTag.unapply(42).get == 42)
                assert(intTag.unapply("42").isEmpty)
            }

            "Double" in {
                val doubleTag = SafeClassTag[Double]
                assert(doubleTag.accepts(3.14))
                assert(!doubleTag.accepts(42))
            }

            "Boolean" in {
                val booleanTag = SafeClassTag[Boolean]
                assert(booleanTag.accepts(true))
                assert(!booleanTag.accepts(1))
            }

            "String" in {
                val stringTag = SafeClassTag[String]
                assert(stringTag.accepts("hello"))
                assert(!stringTag.accepts(42))
            }
        }

        "class hierarchy" - {
            "Animal" in {
                val animalTag = SafeClassTag[Animal]
                assert(animalTag.accepts(new Dog))
                assert(animalTag.accepts(new Snake))
                assert(!animalTag.accepts("not an animal"))
            }

            "Mammal" in {
                val mammalTag = SafeClassTag[Mammal]
                assert(mammalTag.accepts(new Dog))
                assert(!mammalTag.accepts(new Snake))
            }

            "Reptile" in {
                val reptileTag = SafeClassTag[Reptile]
                assert(reptileTag.accepts(new Snake))
                assert(!reptileTag.accepts(new Dog))
            }
        }

        "enum types" - {
            "Color" in {
                val colorTag = SafeClassTag[Color]
                assert(colorTag.accepts(Color.Red))
                assert(colorTag.accepts(Color.Green))
                assert(!colorTag.accepts("Red"))
            }
        }

        "case classes" - {
            "Person" in {
                val personTag = SafeClassTag[Person]
                assert(personTag.accepts(Person("Alice", 30)))
                assert(!personTag.accepts(("Alice", 30)))
            }
        }

        "opaque types" - {
            "UserId" in {
                val userIdTag = SafeClassTag[UserId]
                assert(userIdTag.accepts(UserId("user123")))
                assert(!userIdTag.accepts(123))
            }
        }
    }

    "union types" - {
        "simple union" in {
            val intOrStringTag = SafeClassTag[Int | String]
            assert(intOrStringTag.accepts(42))
            assert(intOrStringTag.accepts("hello"))
            assert(!intOrStringTag.accepts(true))
        }

        "complex class union" in {
            val complexUnionTag = SafeClassTag[Dog | Cat | Snake | Duck]
            assert(complexUnionTag.accepts(new Dog))
            assert(complexUnionTag.accepts(new Duck))
            assert(!complexUnionTag.accepts(new Penguin))
        }

        "mixed type union" in {
            val numberOrColorTag = SafeClassTag[Int | Double | Color]
            assert(numberOrColorTag.accepts(42))
            assert(numberOrColorTag.accepts(3.14))
            assert(numberOrColorTag.accepts(Color.Blue))
            assert(!numberOrColorTag.accepts("Blue"))
        }
    }

    "intersection types" - {
        "trait intersection" in {
            val animalAndSwimmerTag = SafeClassTag[Animal & Swimmer]
            assert(animalAndSwimmerTag.accepts(new Duck))
            assert(animalAndSwimmerTag.accepts(new Penguin))
            assert(!animalAndSwimmerTag.accepts(new Dog))
        }

        "multiple trait intersection" in {
            val animalAndSwimmerAndFlyerTag = SafeClassTag[Animal & Swimmer & Flyer]
            assert(animalAndSwimmerAndFlyerTag.accepts(new Duck))
            assert(!animalAndSwimmerAndFlyerTag.accepts(new Penguin))
            assert(!animalAndSwimmerAndFlyerTag.accepts(new Dog))
        }

        "class and trait intersection" in {
            val mammalAndSwimmerTag = SafeClassTag[Mammal & Swimmer]
            assert(!mammalAndSwimmerTag.accepts(new Dog))
            assert(!mammalAndSwimmerTag.accepts(new Duck))
        }
    }

    "complex combinations" - {
        "union and intersection" in {
            val complexTag = SafeClassTag[(Animal & Swimmer) | (Mammal & Flyer)]
            assert(complexTag.accepts(new Duck))
            assert(complexTag.accepts(new Penguin))
            assert(!complexTag.accepts(new Dog))
            assert(!complexTag.accepts(new Snake))
        }

        "nested unions and intersections" in {
            val nestedTag = SafeClassTag[((Animal & Swimmer) | Mammal) & (Reptile | Flyer)]
            assert(nestedTag.accepts(new Duck))
            assert(!nestedTag.accepts(new Penguin))
            assert(!nestedTag.accepts(new Dog))
            assert(!nestedTag.accepts(new Snake))
        }
        "nested intersections" in {
            trait A
            trait B
            trait C
            class ABC extends A with B with C
            val complexTag = SafeClassTag[A & (B & C)]
            assert(complexTag.accepts(new ABC))
            assert(SafeClassTag[ABC] <:< complexTag)
        }
        "redundant types in union" in {
            val redundantUnion = SafeClassTag[Int | Int | Double]
            assert(redundantUnion.accepts(42))
            assert(redundantUnion.accepts(3.14))
        }
        "deeply nested unions and intersections" in {
            trait A; trait B; trait C; trait D; trait E; trait F
            type ComplexType = (A & B) | ((C & D) | (E & F))
            val complexTag = SafeClassTag[ComplexType]
            assert(complexTag.accepts(new A with B {}))
            assert(complexTag.accepts(new C with D {}))
            assert(complexTag.accepts(new E with F {}))
            assert(!complexTag.accepts(new A {}))
        }
    }

    "edge cases" - {
        "null" in {
            val anyTag = SafeClassTag[Any]
            assert(!anyTag.accepts(null))
            assert(anyTag.unapply(null).isEmpty)

            val stringTag = SafeClassTag[String]
            assert(!stringTag.accepts(null))
            assert(stringTag.unapply(null).isEmpty)
        }

        "Any" in {
            val anyTag = SafeClassTag[Any]
            assert(anyTag.accepts(42))
            assert(anyTag.accepts("string"))
            assert(!anyTag.accepts(null))
            assert(anyTag.accepts(new Object))
        }

        "AnyVal" - {
            "regular" in {
                val value: AnyVal = 1
                val anyValTag     = SafeClassTag[AnyVal]
                assert(anyValTag.accepts(42))
                assert(anyValTag.accepts(42.1))
                assert(anyValTag.accepts(value))
                assert(!anyValTag.accepts(null))
                assert(!anyValTag.accepts(new Object))
            }
            "custom" in {
                val customIntTag = SafeClassTag[CustomInt]
                assert(customIntTag.accepts(CustomInt(42)))
                assert(!customIntTag.accepts(42))

                val anyValTag = SafeClassTag[AnyVal]
                assert(anyValTag.accepts(CustomInt(42)))
            }
            "Unit as AnyVal" in {
                val anyValTag = SafeClassTag[AnyVal]
                assert(anyValTag.accepts(()))

                val unitTag = SafeClassTag[Unit]
                assert(unitTag.accepts(()))
                assert(!unitTag.accepts(5))
                assert(!unitTag.accepts("string"))

                assert(SafeClassTag[Unit] <:< SafeClassTag[AnyVal])
                assert(!(SafeClassTag[Int] <:< SafeClassTag[Unit]))
                assert(!(SafeClassTag[Boolean] <:< SafeClassTag[Unit]))
            }
        }

        "Nothing" in {
            val nothingTag = SafeClassTag[Nothing]
            assert(!nothingTag.accepts(42))
            assert(!nothingTag.accepts(null))
            assert(!nothingTag.accepts(new Object))
        }

        "Unit" in {
            val unitTag = SafeClassTag[Unit]
            assert(unitTag.accepts(()))
            assert(!unitTag.accepts(null))
            assert(!unitTag.accepts(42))
            assert(!unitTag.accepts(""))

            assert(unitTag.unapply(()).get == ())
            assert(unitTag.unapply(42).isEmpty)
        }
        "sealed traits" in {
            sealed trait Fruit
            case object Apple  extends Fruit
            case object Banana extends Fruit

            val fruitTag = SafeClassTag[Fruit]
            assert(fruitTag.accepts(Apple))
            assert(fruitTag.accepts(Banana))
            assert(!fruitTag.accepts("Not a fruit"))
        }

        "abstract classes" in {
            abstract class Shape
            class Circle extends Shape
            class Square extends Shape

            val shapeTag = SafeClassTag[Shape]
            assert(shapeTag.accepts(new Circle))
            assert(shapeTag.accepts(new Square))
            assert(!shapeTag.accepts("Not a shape"))
        }

        "Java classes" in {
            val listTag = SafeClassTag[Thread]
            assert(listTag.accepts(Thread.currentThread()))
            assert(!listTag.accepts(new java.util.LinkedList[String]()))
        }

    }

    "unsupported" - {
        "generic types" - {
            "List[Int]" in {
                assertDoesNotCompile("SafeClassTag[List[Int]]")
            }

            "Option[String]" in {
                assertDoesNotCompile("SafeClassTag[Option[String]]")
            }

            "Map[String, Int]" in {
                assertDoesNotCompile("SafeClassTag[Map[String, Int]]")
            }

            "generic class" in {
                class Generic[T]
                assertDoesNotCompile("SafeClassTag[Generic[Int]]")
            }

            "generic trait" in {
                trait GenericTrait[T]
                assertDoesNotCompile("SafeClassTag[GenericTrait[String]]")
            }
        }

        "Null type" - {
            "Null" in {
                assertDoesNotCompile("SafeClassTag[Null]")
            }

            "Null in union" in {
                assertDoesNotCompile("SafeClassTag[String | Null]")
            }

            "Null in intersection" in {
                assertDoesNotCompile("SafeClassTag[Any & Null]")
            }

            "Complex type with Null" in {
                assertDoesNotCompile("SafeClassTag[(String | Int) & (Double | Null)]")
            }
        }
    }

    "<:<" - {
        "primitive types" - {
            "reflexivity" in {
                assert(SafeClassTag[Int] <:< SafeClassTag[Int])
                assert(SafeClassTag[Double] <:< SafeClassTag[Double])
                assert(SafeClassTag[Boolean] <:< SafeClassTag[Boolean])
            }

            "non-subclass relationships" in {
                assert(!(SafeClassTag[Int] <:< SafeClassTag[Long]))
                assert(!(SafeClassTag[Float] <:< SafeClassTag[Double]))
                assert(!(SafeClassTag[Byte] <:< SafeClassTag[Short]))
            }
        }

        "class hierarchy" - {
            "direct subclass" in {
                assert(SafeClassTag[Dog] <:< SafeClassTag[Mammal])
                assert(SafeClassTag[Cat] <:< SafeClassTag[Mammal])
                assert(SafeClassTag[Snake] <:< SafeClassTag[Reptile])
            }

            "indirect subclass" in {
                assert(SafeClassTag[Dog] <:< SafeClassTag[Animal])
                assert(SafeClassTag[Snake] <:< SafeClassTag[Animal])
            }

            "non-subclass relationships" in {
                assert(!(SafeClassTag[Dog] <:< SafeClassTag[Reptile]))
                assert(!(SafeClassTag[Cat] <:< SafeClassTag[Dog]))
                assert(!(SafeClassTag[Animal] <:< SafeClassTag[Mammal]))
            }
        }

        "union types" - {
            "subclass of union" in {
                assert(SafeClassTag[Dog] <:< SafeClassTag[Dog | Cat])
                assert(SafeClassTag[Cat] <:< SafeClassTag[Dog | Cat])
                assert(SafeClassTag[Snake] <:< SafeClassTag[Mammal | Reptile])
            }

            "union subclass" in {
                assert(SafeClassTag[Dog | Cat] <:< SafeClassTag[Animal])
                assert(SafeClassTag[Mammal | Reptile] <:< SafeClassTag[Animal])
            }

            "non-subclass relationships" in {
                assert(!(SafeClassTag[Dog | Cat] <:< SafeClassTag[Dog]))
                assert(!(SafeClassTag[Mammal | Reptile] <:< SafeClassTag[Mammal]))
            }
        }

        "intersection types" - {
            "subclass of intersection" in {
                assert(SafeClassTag[Duck] <:< SafeClassTag[Animal & Swimmer])
                assert(SafeClassTag[Duck] <:< SafeClassTag[Animal & Swimmer & Flyer])
            }

            "intersection subclass" in {
                assert(SafeClassTag[Animal & Swimmer] <:< SafeClassTag[Animal])
            }

            "non-subclass relationships" in {
                assert(!(SafeClassTag[Animal & Swimmer] <:< SafeClassTag[Flyer]))
                assert(!(SafeClassTag[Duck] <:< SafeClassTag[Mammal & Swimmer]))
            }
        }

        "complex combinations" - {
            "union and intersection" in {
                assert(SafeClassTag[Duck] <:< SafeClassTag[(Animal & Swimmer) | (Mammal & Flyer)])
                assert(SafeClassTag[Penguin] <:< SafeClassTag[(Animal & Swimmer) | (Mammal & Flyer)])
                assert(!(SafeClassTag[Dog] <:< SafeClassTag[(Animal & Swimmer) | (Mammal & Flyer)]))
            }

            "nested unions and intersections" in {
                val complexTag = SafeClassTag[((Animal & Swimmer) | Mammal) & (Reptile | Flyer)]
                assert(SafeClassTag[Duck] <:< complexTag)
                assert(!(SafeClassTag[Penguin] <:< complexTag))
                assert(!(SafeClassTag[Dog] <:< complexTag))
            }
        }

        "Nothing type" - {
            "Nothing is subclass of everything" in {
                assert(SafeClassTag[Nothing] <:< SafeClassTag[Any])
                assert(SafeClassTag[Nothing] <:< SafeClassTag[Int])
                assert(SafeClassTag[Nothing] <:< SafeClassTag[Animal])
                assert(SafeClassTag[Nothing] <:< SafeClassTag[Dog | Cat])
                assert(SafeClassTag[Nothing] <:< SafeClassTag[Animal & Swimmer])
            }

            "only Nothing is subclass of Nothing" in {
                assert(SafeClassTag[Nothing] <:< SafeClassTag[Nothing])
                assert(!(SafeClassTag[Any] <:< SafeClassTag[Nothing]))
                assert(!(SafeClassTag[Int] <:< SafeClassTag[Nothing]))
                assert(!(SafeClassTag[Animal] <:< SafeClassTag[Nothing]))
            }
        }

        "Unit type" - {
            "Unit is subclass of itself and anyval" in {
                assert(SafeClassTag[Unit] <:< SafeClassTag[Unit])
                assert(SafeClassTag[Unit] <:< SafeClassTag[AnyVal])
            }

            "Unit is not subclass of other types" in {
                assert(!(SafeClassTag[Unit] <:< SafeClassTag[Any]))
                assert(!(SafeClassTag[Unit] <:< SafeClassTag[Int]))
            }

            "Other types are not subclasses of Unit" in {
                assert(!(SafeClassTag[Any] <:< SafeClassTag[Unit]))
                assert(!(SafeClassTag[AnyVal] <:< SafeClassTag[Unit]))
                assert(!(SafeClassTag[Int] <:< SafeClassTag[Unit]))
            }
        }
    }

end SafeClassTagTest
