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
                val intTag = ConcreteTag[Int]
                assert(intTag.accepts(42))
                assert(!intTag.accepts("42"))
                assert(intTag.unapply(42).get == 42)
                assert(intTag.unapply("42").isEmpty)
            }

            "Double" in {
                val doubleTag = ConcreteTag[Double]
                assert(doubleTag.accepts(3.14))
                assert(!doubleTag.accepts(42))
            }

            "Boolean" in {
                val booleanTag = ConcreteTag[Boolean]
                assert(booleanTag.accepts(true))
                assert(!booleanTag.accepts(1))
            }

            "String" in {
                val stringTag = ConcreteTag[String]
                assert(stringTag.accepts("hello"))
                assert(!stringTag.accepts(42))
            }
        }

        "class hierarchy" - {
            "Animal" in {
                val animalTag = ConcreteTag[Animal]
                assert(animalTag.accepts(new Dog))
                assert(animalTag.accepts(new Snake))
                assert(!animalTag.accepts("not an animal"))
            }

            "Mammal" in {
                val mammalTag = ConcreteTag[Mammal]
                assert(mammalTag.accepts(new Dog))
                assert(!mammalTag.accepts(new Snake))
            }

            "Reptile" in {
                val reptileTag = ConcreteTag[Reptile]
                assert(reptileTag.accepts(new Snake))
                assert(!reptileTag.accepts(new Dog))
            }
        }

        "enum types" - {
            "Color" in {
                val colorTag = ConcreteTag[Color]
                assert(colorTag.accepts(Color.Red))
                assert(colorTag.accepts(Color.Green))
                assert(!colorTag.accepts("Red"))
            }
        }

        "case classes" - {
            "Person" in {
                val personTag = ConcreteTag[Person]
                assert(personTag.accepts(Person("Alice", 30)))
                assert(!personTag.accepts(("Alice", 30)))
            }
        }

        "opaque types" - {
            "UserId" in {
                val userIdTag = ConcreteTag[UserId]
                assert(userIdTag.accepts(UserId("user123")))
                assert(!userIdTag.accepts(123))
            }
        }
    }

    "union types" - {
        "simple union" in {
            val intOrStringTag = ConcreteTag[Int | String]
            assert(intOrStringTag.accepts(42))
            assert(intOrStringTag.accepts("hello"))
            assert(!intOrStringTag.accepts(true))
        }

        "complex class union" in {
            val complexUnionTag = ConcreteTag[Dog | Cat | Snake | Duck]
            assert(complexUnionTag.accepts(new Dog))
            assert(complexUnionTag.accepts(new Duck))
            assert(!complexUnionTag.accepts(new Penguin))
        }

        "mixed type union" in {
            val numberOrColorTag = ConcreteTag[Int | Double | Color]
            assert(numberOrColorTag.accepts(42))
            assert(numberOrColorTag.accepts(3.14))
            assert(numberOrColorTag.accepts(Color.Blue))
            assert(!numberOrColorTag.accepts("Blue"))
        }
    }

    "intersection types" - {
        "trait intersection" in {
            val animalAndSwimmerTag = ConcreteTag[Animal & Swimmer]
            assert(animalAndSwimmerTag.accepts(new Duck))
            assert(animalAndSwimmerTag.accepts(new Penguin))
            assert(!animalAndSwimmerTag.accepts(new Dog))
        }

        "multiple trait intersection" in {
            val animalAndSwimmerAndFlyerTag = ConcreteTag[Animal & Swimmer & Flyer]
            assert(animalAndSwimmerAndFlyerTag.accepts(new Duck))
            assert(!animalAndSwimmerAndFlyerTag.accepts(new Penguin))
            assert(!animalAndSwimmerAndFlyerTag.accepts(new Dog))
        }

        "class and trait intersection" in {
            val mammalAndSwimmerTag = ConcreteTag[Mammal & Swimmer]
            assert(!mammalAndSwimmerTag.accepts(new Dog))
            assert(!mammalAndSwimmerTag.accepts(new Duck))
        }
    }

    "complex combinations" - {
        "union and intersection" in {
            val complexTag = ConcreteTag[(Animal & Swimmer) | (Mammal & Flyer)]
            assert(complexTag.accepts(new Duck))
            assert(complexTag.accepts(new Penguin))
            assert(!complexTag.accepts(new Dog))
            assert(!complexTag.accepts(new Snake))
        }

        "nested unions and intersections" in {
            val nestedTag = ConcreteTag[((Animal & Swimmer) | Mammal) & (Reptile | Flyer)]
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
            val complexTag = ConcreteTag[A & (B & C)]
            assert(complexTag.accepts(new ABC))
            assert(ConcreteTag[ABC] <:< complexTag)
        }
        "redundant types in union" in {
            val redundantUnion = ConcreteTag[Int | Int | Double]
            assert(redundantUnion.accepts(42))
            assert(redundantUnion.accepts(3.14))
        }
        "deeply nested unions and intersections" in {
            trait A; trait B; trait C; trait D; trait E; trait F
            type ComplexType = (A & B) | ((C & D) | (E & F))
            val complexTag = ConcreteTag[ComplexType]
            assert(complexTag.accepts(new A with B {}))
            assert(complexTag.accepts(new C with D {}))
            assert(complexTag.accepts(new E with F {}))
            assert(!complexTag.accepts(new A {}))
        }
    }

    "edge cases" - {
        "null" in {
            val anyTag = ConcreteTag[Any]
            assert(!anyTag.accepts(null))
            assert(anyTag.unapply(null).isEmpty)

            val stringTag = ConcreteTag[String]
            assert(!stringTag.accepts(null))
            assert(stringTag.unapply(null).isEmpty)
        }

        "Any" in {
            val anyTag = ConcreteTag[Any]
            assert(anyTag.accepts(42))
            assert(anyTag.accepts("string"))
            assert(!anyTag.accepts(null))
            assert(anyTag.accepts(new Object))
        }

        "AnyVal" - {
            "regular" in {
                val value: AnyVal = 1
                val anyValTag     = ConcreteTag[AnyVal]
                assert(anyValTag.accepts(42))
                assert(anyValTag.accepts(42.1))
                assert(anyValTag.accepts(value))
                assert(!anyValTag.accepts(null))
                assert(!anyValTag.accepts(new Object))
            }
            "custom" in {
                val customIntTag = ConcreteTag[CustomInt]
                assert(customIntTag.accepts(CustomInt(42)))
                assert(!customIntTag.accepts(42))

                val anyValTag = ConcreteTag[AnyVal]
                assert(anyValTag.accepts(CustomInt(42)))
            }
            "Unit as AnyVal" in {
                val anyValTag = ConcreteTag[AnyVal]
                assert(anyValTag.accepts(()))

                val unitTag = ConcreteTag[Unit]
                assert(unitTag.accepts(()))
                assert(!unitTag.accepts(5))
                assert(!unitTag.accepts("string"))

                assert(ConcreteTag[Unit] <:< ConcreteTag[AnyVal])
                assert(!(ConcreteTag[Int] <:< ConcreteTag[Unit]))
                assert(!(ConcreteTag[Boolean] <:< ConcreteTag[Unit]))
            }
        }

        "Nothing" in {
            val nothingTag = ConcreteTag[Nothing]
            assert(!nothingTag.accepts(42))
            assert(!nothingTag.accepts(null))
            assert(!nothingTag.accepts(new Object))
        }

        "Unit" in {
            val unitTag = ConcreteTag[Unit]
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

            val fruitTag = ConcreteTag[Fruit]
            assert(fruitTag.accepts(Apple))
            assert(fruitTag.accepts(Banana))
            assert(!fruitTag.accepts("Not a fruit"))
        }

        "abstract classes" in {
            abstract class Shape
            class Circle extends Shape
            class Square extends Shape

            val shapeTag = ConcreteTag[Shape]
            assert(shapeTag.accepts(new Circle))
            assert(shapeTag.accepts(new Square))
            assert(!shapeTag.accepts("Not a shape"))
        }

        "Java classes" in {
            val listTag = ConcreteTag[Thread]
            assert(listTag.accepts(Thread.currentThread()))
            assert(!listTag.accepts(new java.util.LinkedList[String]()))
        }

    }

    "unsupported" - {

        val error = "This method requires a ConcreteTag"

        "generic types" - {
            "List[Int]" in {
                typeCheckFailure("ConcreteTag[List[Int]]")(error)
            }

            "Option[String]" in {
                typeCheckFailure("ConcreteTag[Option[String]]")(error)
            }

            "Map[String, Int]" in {
                typeCheckFailure("ConcreteTag[Map[String, Int]]")(error)
            }

            "generic class" in {
                class Generic[T]
                typeCheckFailure("ConcreteTag[Generic[Int]]")(error)
            }

            "generic trait" in {
                trait GenericTrait[T]
                typeCheckFailure("ConcreteTag[GenericTrait[String]]")(error)
            }
        }

        "Null type" - {
            "Null" in {
                typeCheckFailure("ConcreteTag[Null]")(error)
            }

            "Null in union" in {
                typeCheckFailure("ConcreteTag[String | Null]")(error)
            }

            "Null in intersection" in {
                typeCheckFailure("ConcreteTag[Any & Null]")(error)
            }

            "Complex type with Null" in {
                typeCheckFailure("ConcreteTag[(String | Int) & (Double | Null)]")(error)
            }
        }
    }

    "<:<" - {
        "primitive types" - {
            "reflexivity" in {
                assert(ConcreteTag[Int] <:< ConcreteTag[Int])
                assert(ConcreteTag[Double] <:< ConcreteTag[Double])
                assert(ConcreteTag[Boolean] <:< ConcreteTag[Boolean])
            }

            "non-subclass relationships" in {
                assert(!(ConcreteTag[Int] <:< ConcreteTag[Long]))
                assert(!(ConcreteTag[Float] <:< ConcreteTag[Double]))
                assert(!(ConcreteTag[Byte] <:< ConcreteTag[Short]))
            }
        }

        "class hierarchy" - {
            "direct subclass" in {
                assert(ConcreteTag[Dog] <:< ConcreteTag[Mammal])
                assert(ConcreteTag[Cat] <:< ConcreteTag[Mammal])
                assert(ConcreteTag[Snake] <:< ConcreteTag[Reptile])
            }

            "indirect subclass" in {
                assert(ConcreteTag[Dog] <:< ConcreteTag[Animal])
                assert(ConcreteTag[Snake] <:< ConcreteTag[Animal])
            }

            "non-subclass relationships" in {
                assert(!(ConcreteTag[Dog] <:< ConcreteTag[Reptile]))
                assert(!(ConcreteTag[Cat] <:< ConcreteTag[Dog]))
                assert(!(ConcreteTag[Animal] <:< ConcreteTag[Mammal]))
            }
        }

        "union types" - {
            "subclass of union" in {
                assert(ConcreteTag[Dog] <:< ConcreteTag[Dog | Cat])
                assert(ConcreteTag[Cat] <:< ConcreteTag[Dog | Cat])
                assert(ConcreteTag[Snake] <:< ConcreteTag[Mammal | Reptile])
            }

            "union subclass" in {
                assert(ConcreteTag[Dog | Cat] <:< ConcreteTag[Animal])
                assert(ConcreteTag[Mammal | Reptile] <:< ConcreteTag[Animal])
            }

            "non-subclass relationships" in {
                assert(!(ConcreteTag[Dog | Cat] <:< ConcreteTag[Dog]))
                assert(!(ConcreteTag[Mammal | Reptile] <:< ConcreteTag[Mammal]))
            }
        }

        "intersection types" - {
            "subclass of intersection" in {
                assert(ConcreteTag[Duck] <:< ConcreteTag[Animal & Swimmer])
                assert(ConcreteTag[Duck] <:< ConcreteTag[Animal & Swimmer & Flyer])
            }

            "intersection subclass" in {
                assert(ConcreteTag[Animal & Swimmer] <:< ConcreteTag[Animal])
            }

            "non-subclass relationships" in {
                assert(!(ConcreteTag[Animal & Swimmer] <:< ConcreteTag[Flyer]))
                assert(!(ConcreteTag[Duck] <:< ConcreteTag[Mammal & Swimmer]))
            }
        }

        "complex combinations" - {
            "union and intersection" in {
                assert(ConcreteTag[Duck] <:< ConcreteTag[(Animal & Swimmer) | (Mammal & Flyer)])
                assert(ConcreteTag[Penguin] <:< ConcreteTag[(Animal & Swimmer) | (Mammal & Flyer)])
                assert(!(ConcreteTag[Dog] <:< ConcreteTag[(Animal & Swimmer) | (Mammal & Flyer)]))
            }

            "nested unions and intersections" in {
                val complexTag = ConcreteTag[((Animal & Swimmer) | Mammal) & (Reptile | Flyer)]
                assert(ConcreteTag[Duck] <:< complexTag)
                assert(!(ConcreteTag[Penguin] <:< complexTag))
                assert(!(ConcreteTag[Dog] <:< complexTag))
            }
        }

        "Nothing type" - {
            "Nothing is subclass of everything" in {
                assert(ConcreteTag[Nothing] <:< ConcreteTag[Any])
                assert(ConcreteTag[Nothing] <:< ConcreteTag[Int])
                assert(ConcreteTag[Nothing] <:< ConcreteTag[Animal])
                assert(ConcreteTag[Nothing] <:< ConcreteTag[Dog | Cat])
                assert(ConcreteTag[Nothing] <:< ConcreteTag[Animal & Swimmer])
            }

            "only Nothing is subclass of Nothing" in {
                assert(ConcreteTag[Nothing] <:< ConcreteTag[Nothing])
                assert(!(ConcreteTag[Any] <:< ConcreteTag[Nothing]))
                assert(!(ConcreteTag[Int] <:< ConcreteTag[Nothing]))
                assert(!(ConcreteTag[Animal] <:< ConcreteTag[Nothing]))
            }
        }

        "Unit type" - {
            "Unit is subclass of itself and anyval" in {
                assert(ConcreteTag[Unit] <:< ConcreteTag[Unit])
                assert(ConcreteTag[Unit] <:< ConcreteTag[AnyVal])
            }

            "Unit is not subclass of other types" in {
                assert(!(ConcreteTag[Unit] <:< ConcreteTag[Any]))
                assert(!(ConcreteTag[Unit] <:< ConcreteTag[Int]))
            }

            "Other types are not subclasses of Unit" in {
                assert(!(ConcreteTag[Any] <:< ConcreteTag[Unit]))
                assert(!(ConcreteTag[AnyVal] <:< ConcreteTag[Unit]))
                assert(!(ConcreteTag[Int] <:< ConcreteTag[Unit]))
            }
        }
    }

    "literal types" - {
        "integer literals" - {
            "zero" in {
                val zeroTag = ConcreteTag[0]
                assert(zeroTag.accepts(0))
                assert(!zeroTag.accepts(1))
                assert(zeroTag.accepts(-0))
                assert(zeroTag <:< ConcreteTag[Int])
                assert(!(ConcreteTag[Int] <:< zeroTag))
            }

            "positive" in {
                val positiveTag = ConcreteTag[42]
                assert(positiveTag.accepts(42))
                assert(!positiveTag.accepts(43))
                assert(!positiveTag.accepts(-42))
                assert(positiveTag <:< ConcreteTag[Int])
                assert(!(ConcreteTag[Int] <:< positiveTag))
            }

            "negative" in {
                val negativeTag = ConcreteTag[-1]
                assert(negativeTag.accepts(-1))
                assert(!negativeTag.accepts(1))
                assert(!negativeTag.accepts(0))
                assert(negativeTag <:< ConcreteTag[Int])
                assert(!(ConcreteTag[Int] <:< negativeTag))
            }
        }

        "string literals" - {
            "empty string" in {
                val emptyStringTag = ConcreteTag[""]
                assert(emptyStringTag.accepts(""))
                assert(!emptyStringTag.accepts(" "))
                assert(emptyStringTag <:< ConcreteTag[String])
                assert(!(ConcreteTag[String] <:< emptyStringTag))
            }

            "non-empty string" in {
                val helloTag = ConcreteTag["hello"]
                assert(helloTag.accepts("hello"))
                assert(!helloTag.accepts("Hello"))
                assert(!helloTag.accepts(""))
                assert(helloTag <:< ConcreteTag[String])
                assert(!(ConcreteTag[String] <:< helloTag))
            }
        }

        "boolean literals" - {
            "true" in {
                val trueTag = ConcreteTag[true]
                assert(trueTag.accepts(true))
                assert(!trueTag.accepts(false))
                assert(trueTag <:< ConcreteTag[Boolean])
                assert(!(ConcreteTag[Boolean] <:< trueTag))
            }

            "false" in {
                val falseTag = ConcreteTag[false]
                assert(falseTag.accepts(false))
                assert(!falseTag.accepts(true))
                assert(falseTag <:< ConcreteTag[Boolean])
                assert(!(ConcreteTag[Boolean] <:< falseTag))
            }
        }

        "char literals" in {
            val charTag = ConcreteTag['a']
            assert(charTag.accepts('a'))
            assert(!charTag.accepts('A'))
            assert(charTag.accepts('\u0061')) // Unicode for 'a'
            assert(charTag <:< ConcreteTag[Char])
            assert(!(ConcreteTag[Char] <:< charTag))
        }

        "float literals" in {
            val floatTag = ConcreteTag[3.14f]
            assert(floatTag.accepts(3.14f))
            assert(!floatTag.accepts(3.14))
            assert(!floatTag.accepts(3.140001f))
            assert(!(ConcreteTag[Float] <:< floatTag))
        }

        "double literals" in {
            val doubleTag = ConcreteTag[3.14]
            assert(doubleTag.accepts(3.14))
            assert(!doubleTag.accepts(3.14f))
            assert(!doubleTag.accepts(3.140000000000001))
            assert(doubleTag <:< ConcreteTag[Double])
            assert(!(ConcreteTag[Double] <:< doubleTag))
        }

        "union with literals" in {
            val unionTag = ConcreteTag[42 | "hello" | true]
            assert(unionTag.accepts(42))
            assert(unionTag.accepts("hello"))
            assert(unionTag.accepts(true))
            assert(!unionTag.accepts(43))
            assert(!unionTag.accepts("Hello"))
            assert(!unionTag.accepts(false))
            assert(unionTag <:< ConcreteTag[Int | String | Boolean])
            assert(!(ConcreteTag[Int | String | Boolean] <:< unionTag))
        }

        "intersection with literal" in {
            val intersectionTag = ConcreteTag[Int & 42]
            assert(intersectionTag.accepts(42))
            assert(!intersectionTag.accepts(43))
            assert(intersectionTag <:< ConcreteTag[Int])
            assert(intersectionTag <:< ConcreteTag[42])
            assert(!(ConcreteTag[Int] <:< intersectionTag))
        }

        "long literals" in {
            val longTag = ConcreteTag[1000000000000L]
            assert(longTag.accepts(1000000000000L))
            assert(!longTag.accepts(1000000000001L))
            assert(longTag <:< ConcreteTag[Long])
        }

        "extreme numeric literals" in {
            val maxIntTag = ConcreteTag[2147483647]
            assert(maxIntTag.accepts(Int.MaxValue))
            assert(!maxIntTag.accepts(Int.MaxValue - 1))

            val minLongTag = ConcreteTag[-9223372036854775808L]
            assert(minLongTag.accepts(Long.MinValue))
            assert(!minLongTag.accepts(Long.MinValue + 1))
        }

        "unicode character literals" in {
            val unicodeCharTag = ConcreteTag['π']
            assert(unicodeCharTag.accepts('π'))
            assert(!unicodeCharTag.accepts('p'))
            assert(unicodeCharTag <:< ConcreteTag[Char])
        }

        "complex literal combinations" in {
            val complexTag = ConcreteTag[42 | "hello" | 3.14 | 'a']
            assert(complexTag.accepts(42))
            assert(complexTag.accepts("hello"))
            assert(complexTag.accepts(3.14))
            assert(complexTag.accepts('a'))
            assert(!complexTag.accepts(43))
            assert(!complexTag.accepts("world"))
            assert(!complexTag.accepts(3.15))
            assert(!complexTag.accepts('b'))
        }

        "complex type combinations" - {
            "union of intersections" in {
                val tag = ConcreteTag[(Int & 42) | (String & "hello")]
                assert(tag.accepts(42))
                assert(tag.accepts("hello"))
                assert(!tag.accepts(43))
                assert(!tag.accepts("world"))
                assert(tag <:< ConcreteTag[Int | String])
                assert(!(ConcreteTag[Int | String] <:< tag))
            }

            "intersection of unions" in {
                val tag = ConcreteTag[(Int | String) & (42 | "hello")]
                assert(tag.accepts(42))
                assert(tag.accepts("hello"))
                assert(!tag.accepts(43))
                assert(!tag.accepts("world"))
                assert(tag <:< ConcreteTag[Int | String])
                assert(!(ConcreteTag[Int | String] <:< tag))
            }

            "nested unions and intersections" in {
                val tag = ConcreteTag[((Int & 42) | (String & "hello")) & (Double | Boolean)]
                assert(!tag.accepts(42))
                assert(!tag.accepts("hello"))
                assert(!tag.accepts(3.14))
                assert(!tag.accepts(true))
            }

            "union of literal types and regular types" in {
                val tag = ConcreteTag[42 | String | true | Double]
                assert(tag.accepts(42))
                assert(tag.accepts("hello"))
                assert(tag.accepts(true))
                assert(tag.accepts(3.14))
                assert(!tag.accepts(43))
                assert(!tag.accepts(false))
                assert(tag <:< ConcreteTag[Int | String | Boolean | Double])
            }

            "complex union and intersection with traits" in {
                trait X
                trait Y
                trait Z
                class XY extends X with Y
                class YZ extends Y with Z
                val tag = ConcreteTag[(X & Y) | (Y & Z) | 42]
                assert(tag.accepts(new XY))
                assert(tag.accepts(new YZ))
                assert(tag.accepts(42))
                assert(!tag.accepts(new X {}))
                assert(!tag.accepts(new Z {}))
                assert(!tag.accepts(43))
            }

            "complex type bounds" in {
                trait Numeric[T]
                given Numeric[Int]    = new Numeric[Int] {}
                given Numeric[Double] = new Numeric[Double] {}

                val tag = ConcreteTag[Int | Double]
                assert(tag.accepts(42))
                assert(tag.accepts(3.14))
                assert(!tag.accepts("not a number"))

                // This test checks if we can use the tag with a type bound
                def acceptsNumeric[T: Numeric](value: T)(using tag: ConcreteTag[T]): Boolean =
                    tag.accepts(value)

                assert(acceptsNumeric(42))
                assert(acceptsNumeric(3.14))
            }
        }
    }

    "equality" - {
        "simple types" in {
            assert(ConcreteTag[Int] == ConcreteTag[Int])
            assert(ConcreteTag[String] == ConcreteTag[String])
            assert(ConcreteTag[Int] != ConcreteTag[String])
        }

        "class hierarchy" in {
            assert(ConcreteTag[Dog] == ConcreteTag[Dog])
            assert(ConcreteTag[Animal] == ConcreteTag[Animal])
            assert(ConcreteTag[Dog] != ConcreteTag[Cat])
        }

        "union types" - {
            "simple union" in {
                assert(ConcreteTag[Int | String] == ConcreteTag[Int | String])
                assert(ConcreteTag[String | Int] == ConcreteTag[Int | String])
                assert(ConcreteTag[Int | String] != ConcreteTag[Int | Double])
            }

            "complex union" in {
                assert(ConcreteTag[Dog | Cat | Snake] == ConcreteTag[Cat | Snake | Dog])
                assert(ConcreteTag[Dog | (Cat | Snake)] == ConcreteTag[(Dog | Cat) | Snake])
                assert(ConcreteTag[Dog | Cat | Snake] != ConcreteTag[Dog | Cat | Duck])
            }
        }

        "intersection types" - {
            "simple intersection" in {
                assert(ConcreteTag[Animal & Swimmer] == ConcreteTag[Animal & Swimmer])
                assert(ConcreteTag[Swimmer & Animal] == ConcreteTag[Animal & Swimmer])
                assert(ConcreteTag[Animal & Swimmer] != ConcreteTag[Animal & Flyer])
            }

            "complex intersection" in {
                assert(ConcreteTag[Animal & Swimmer & Flyer] == ConcreteTag[Flyer & Animal & Swimmer])
                assert(ConcreteTag[Animal & (Swimmer & Flyer)] == ConcreteTag[(Animal & Swimmer) & Flyer])
                assert(ConcreteTag[Animal & Swimmer & Flyer] != ConcreteTag[Animal & Swimmer & Reptile])
            }
        }

        "mixed union and intersection" in {
            assert(ConcreteTag[(Animal & Swimmer) | (Mammal & Flyer)] == ConcreteTag[(Mammal & Flyer) | (Animal & Swimmer)])
            assert(ConcreteTag[(Animal & Swimmer) | (Mammal & Flyer)] != ConcreteTag[(Animal & Flyer) | (Mammal & Swimmer)])
        }

        "with literal types" in {
            assert(ConcreteTag[42 | "hello" | true] == ConcreteTag[true | 42 | "hello"])
            assert(ConcreteTag[42 & Int] == ConcreteTag[Int & 42])
            assert(ConcreteTag[42 | "hello" | true] != ConcreteTag[42 | "hello" | false])
        }

        "complex nested types" in {
            val type1 = ConcreteTag[((Animal & Swimmer) | Mammal) & (Reptile | Flyer)]
            val type2 = ConcreteTag[(Mammal | (Swimmer & Animal)) & (Flyer | Reptile)]
            val type3 = ConcreteTag[((Animal & Swimmer) | Mammal) & (Flyer | Snake)]

            assert(type1 == type2)
            assert(type1 != type3)
        }
    }

    "dynamic creation" - {
        "& (intersection) method" - {
            "simple types" in {
                val intAndString = ConcreteTag[Int] & ConcreteTag[String]
                assert(!intAndString.accepts(42))
                assert(!intAndString.accepts("hello"))
            }

            "class hierarchy" in {
                val mammalAndSwimmer = ConcreteTag[Mammal] & ConcreteTag[Swimmer]
                assert(!mammalAndSwimmer.accepts(new Dog))
                assert(!mammalAndSwimmer.accepts(new Duck))
            }

            "with existing intersection" in {
                val animalAndSwimmer   = ConcreteTag[Animal & Swimmer]
                val tripleIntersection = animalAndSwimmer & ConcreteTag[Flyer]
                assert(tripleIntersection.accepts(new Duck))
                assert(!tripleIntersection.accepts(new Penguin))
            }
        }

        "| (union) method" - {
            "simple types" in {
                val intOrString = ConcreteTag[Int] | ConcreteTag[String]
                assert(intOrString.accepts(42))
                assert(intOrString.accepts("hello"))
                assert(!intOrString.accepts(true))
            }

            "class hierarchy" in {
                val mammalOrReptile = ConcreteTag[Mammal] | ConcreteTag[Reptile]
                assert(mammalOrReptile.accepts(new Dog))
                assert(mammalOrReptile.accepts(new Snake))
                assert(!mammalOrReptile.accepts(new Duck))
            }

            "with existing union" in {
                val dogOrCat    = ConcreteTag[Dog | Cat]
                val animalUnion = dogOrCat | ConcreteTag[Snake]
                assert(animalUnion.accepts(new Dog))
                assert(animalUnion.accepts(new Cat))
                assert(animalUnion.accepts(new Snake))
                assert(!animalUnion.accepts(new Duck))
            }
        }

        "combining & and |" in {
            val complexTag = (ConcreteTag[Int] & ConcreteTag[AnyVal]) | (ConcreteTag[String] & ConcreteTag[Any])
            assert(complexTag.accepts(42))
            assert(complexTag.accepts("hello"))
            assert(!complexTag.accepts(true))
        }
    }

end SafeClassTagTest
