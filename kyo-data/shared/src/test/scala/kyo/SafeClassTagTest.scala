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

    "literal types" - {
        "integer literals" - {
            "zero" in {
                val zeroTag = SafeClassTag[0]
                assert(zeroTag.accepts(0))
                assert(!zeroTag.accepts(1))
                assert(zeroTag.accepts(-0))
                assert(zeroTag <:< SafeClassTag[Int])
                assert(!(SafeClassTag[Int] <:< zeroTag))
            }

            "positive" in {
                val positiveTag = SafeClassTag[42]
                assert(positiveTag.accepts(42))
                assert(!positiveTag.accepts(43))
                assert(!positiveTag.accepts(-42))
                assert(positiveTag <:< SafeClassTag[Int])
                assert(!(SafeClassTag[Int] <:< positiveTag))
            }

            "negative" in {
                val negativeTag = SafeClassTag[-1]
                assert(negativeTag.accepts(-1))
                assert(!negativeTag.accepts(1))
                assert(!negativeTag.accepts(0))
                assert(negativeTag <:< SafeClassTag[Int])
                assert(!(SafeClassTag[Int] <:< negativeTag))
            }
        }

        "string literals" - {
            "empty string" in {
                val emptyStringTag = SafeClassTag[""]
                assert(emptyStringTag.accepts(""))
                assert(!emptyStringTag.accepts(" "))
                assert(emptyStringTag <:< SafeClassTag[String])
                assert(!(SafeClassTag[String] <:< emptyStringTag))
            }

            "non-empty string" in {
                val helloTag = SafeClassTag["hello"]
                assert(helloTag.accepts("hello"))
                assert(!helloTag.accepts("Hello"))
                assert(!helloTag.accepts(""))
                assert(helloTag <:< SafeClassTag[String])
                assert(!(SafeClassTag[String] <:< helloTag))
            }
        }

        "boolean literals" - {
            "true" in {
                val trueTag = SafeClassTag[true]
                assert(trueTag.accepts(true))
                assert(!trueTag.accepts(false))
                assert(trueTag <:< SafeClassTag[Boolean])
                assert(!(SafeClassTag[Boolean] <:< trueTag))
            }

            "false" in {
                val falseTag = SafeClassTag[false]
                assert(falseTag.accepts(false))
                assert(!falseTag.accepts(true))
                assert(falseTag <:< SafeClassTag[Boolean])
                assert(!(SafeClassTag[Boolean] <:< falseTag))
            }
        }

        "char literals" in {
            val charTag = SafeClassTag['a']
            assert(charTag.accepts('a'))
            assert(!charTag.accepts('A'))
            assert(charTag.accepts('\u0061')) // Unicode for 'a'
            assert(charTag <:< SafeClassTag[Char])
            assert(!(SafeClassTag[Char] <:< charTag))
        }

        "float literals" in {
            val floatTag = SafeClassTag[3.14f]
            assert(floatTag.accepts(3.14f))
            assert(!floatTag.accepts(3.14))
            assert(!floatTag.accepts(3.140001f))
            assert(!(SafeClassTag[Float] <:< floatTag))
        }

        "double literals" in {
            val doubleTag = SafeClassTag[3.14]
            assert(doubleTag.accepts(3.14))
            assert(!doubleTag.accepts(3.14f))
            assert(!doubleTag.accepts(3.140000000000001))
            assert(doubleTag <:< SafeClassTag[Double])
            assert(!(SafeClassTag[Double] <:< doubleTag))
        }

        "union with literals" in {
            val unionTag = SafeClassTag[42 | "hello" | true]
            assert(unionTag.accepts(42))
            assert(unionTag.accepts("hello"))
            assert(unionTag.accepts(true))
            assert(!unionTag.accepts(43))
            assert(!unionTag.accepts("Hello"))
            assert(!unionTag.accepts(false))
            assert(unionTag <:< SafeClassTag[Int | String | Boolean])
            assert(!(SafeClassTag[Int | String | Boolean] <:< unionTag))
        }

        "intersection with literal" in {
            val intersectionTag = SafeClassTag[Int & 42]
            assert(intersectionTag.accepts(42))
            assert(!intersectionTag.accepts(43))
            assert(intersectionTag <:< SafeClassTag[Int])
            assert(intersectionTag <:< SafeClassTag[42])
            assert(!(SafeClassTag[Int] <:< intersectionTag))
        }

        "long literals" in {
            val longTag = SafeClassTag[1000000000000L]
            assert(longTag.accepts(1000000000000L))
            assert(!longTag.accepts(1000000000001L))
            assert(longTag <:< SafeClassTag[Long])
        }

        "extreme numeric literals" in {
            val maxIntTag = SafeClassTag[2147483647]
            assert(maxIntTag.accepts(Int.MaxValue))
            assert(!maxIntTag.accepts(Int.MaxValue - 1))

            val minLongTag = SafeClassTag[-9223372036854775808L]
            assert(minLongTag.accepts(Long.MinValue))
            assert(!minLongTag.accepts(Long.MinValue + 1))
        }

        "unicode character literals" in {
            val unicodeCharTag = SafeClassTag['π']
            assert(unicodeCharTag.accepts('π'))
            assert(!unicodeCharTag.accepts('p'))
            assert(unicodeCharTag <:< SafeClassTag[Char])
        }

        "complex literal combinations" in {
            val complexTag = SafeClassTag[42 | "hello" | 3.14 | 'a']
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
                val tag = SafeClassTag[(Int & 42) | (String & "hello")]
                assert(tag.accepts(42))
                assert(tag.accepts("hello"))
                assert(!tag.accepts(43))
                assert(!tag.accepts("world"))
                assert(tag <:< SafeClassTag[Int | String])
                assert(!(SafeClassTag[Int | String] <:< tag))
            }

            "intersection of unions" in {
                val tag = SafeClassTag[(Int | String) & (42 | "hello")]
                assert(tag.accepts(42))
                assert(tag.accepts("hello"))
                assert(!tag.accepts(43))
                assert(!tag.accepts("world"))
                assert(tag <:< SafeClassTag[Int | String])
                assert(!(SafeClassTag[Int | String] <:< tag))
            }

            "nested unions and intersections" in {
                val tag = SafeClassTag[((Int & 42) | (String & "hello")) & (Double | Boolean)]
                assert(!tag.accepts(42))
                assert(!tag.accepts("hello"))
                assert(!tag.accepts(3.14))
                assert(!tag.accepts(true))
            }

            "union of literal types and regular types" in {
                val tag = SafeClassTag[42 | String | true | Double]
                assert(tag.accepts(42))
                assert(tag.accepts("hello"))
                assert(tag.accepts(true))
                assert(tag.accepts(3.14))
                assert(!tag.accepts(43))
                assert(!tag.accepts(false))
                assert(tag <:< SafeClassTag[Int | String | Boolean | Double])
            }

            "complex union and intersection with traits" in {
                trait X
                trait Y
                trait Z
                class XY extends X with Y
                class YZ extends Y with Z
                val tag = SafeClassTag[(X & Y) | (Y & Z) | 42]
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

                val tag = SafeClassTag[Int | Double]
                assert(tag.accepts(42))
                assert(tag.accepts(3.14))
                assert(!tag.accepts("not a number"))

                // This test checks if we can use the tag with a type bound
                def acceptsNumeric[T: Numeric](value: T)(using tag: SafeClassTag[T]): Boolean =
                    tag.accepts(value)

                assert(acceptsNumeric(42))
                assert(acceptsNumeric(3.14))
            }
        }
    }

    "equality" - {
        "simple types" in {
            assert(SafeClassTag[Int] == SafeClassTag[Int])
            assert(SafeClassTag[String] == SafeClassTag[String])
            assert(SafeClassTag[Int] != SafeClassTag[String])
        }

        "class hierarchy" in {
            assert(SafeClassTag[Dog] == SafeClassTag[Dog])
            assert(SafeClassTag[Animal] == SafeClassTag[Animal])
            assert(SafeClassTag[Dog] != SafeClassTag[Cat])
        }

        "union types" - {
            "simple union" in {
                assert(SafeClassTag[Int | String] == SafeClassTag[Int | String])
                assert(SafeClassTag[String | Int] == SafeClassTag[Int | String])
                assert(SafeClassTag[Int | String] != SafeClassTag[Int | Double])
            }

            "complex union" in {
                assert(SafeClassTag[Dog | Cat | Snake] == SafeClassTag[Cat | Snake | Dog])
                assert(SafeClassTag[Dog | (Cat | Snake)] == SafeClassTag[(Dog | Cat) | Snake])
                assert(SafeClassTag[Dog | Cat | Snake] != SafeClassTag[Dog | Cat | Duck])
            }
        }

        "intersection types" - {
            "simple intersection" in {
                assert(SafeClassTag[Animal & Swimmer] == SafeClassTag[Animal & Swimmer])
                assert(SafeClassTag[Swimmer & Animal] == SafeClassTag[Animal & Swimmer])
                assert(SafeClassTag[Animal & Swimmer] != SafeClassTag[Animal & Flyer])
            }

            "complex intersection" in {
                assert(SafeClassTag[Animal & Swimmer & Flyer] == SafeClassTag[Flyer & Animal & Swimmer])
                assert(SafeClassTag[Animal & (Swimmer & Flyer)] == SafeClassTag[(Animal & Swimmer) & Flyer])
                assert(SafeClassTag[Animal & Swimmer & Flyer] != SafeClassTag[Animal & Swimmer & Reptile])
            }
        }

        "mixed union and intersection" in {
            assert(SafeClassTag[(Animal & Swimmer) | (Mammal & Flyer)] == SafeClassTag[(Mammal & Flyer) | (Animal & Swimmer)])
            assert(SafeClassTag[(Animal & Swimmer) | (Mammal & Flyer)] != SafeClassTag[(Animal & Flyer) | (Mammal & Swimmer)])
        }

        "with literal types" in {
            assert(SafeClassTag[42 | "hello" | true] == SafeClassTag[true | 42 | "hello"])
            assert(SafeClassTag[42 & Int] == SafeClassTag[Int & 42])
            assert(SafeClassTag[42 | "hello" | true] != SafeClassTag[42 | "hello" | false])
        }

        "complex nested types" in {
            val type1 = SafeClassTag[((Animal & Swimmer) | Mammal) & (Reptile | Flyer)]
            val type2 = SafeClassTag[(Mammal | (Swimmer & Animal)) & (Flyer | Reptile)]
            val type3 = SafeClassTag[((Animal & Swimmer) | Mammal) & (Flyer | Snake)]

            assert(type1 == type2)
            assert(type1 != type3)
        }
    }

    "dynamic creation" - {
        "& (intersection) method" - {
            "simple types" in {
                val intAndString = SafeClassTag[Int] & SafeClassTag[String]
                assert(!intAndString.accepts(42))
                assert(!intAndString.accepts("hello"))
            }

            "class hierarchy" in {
                val mammalAndSwimmer = SafeClassTag[Mammal] & SafeClassTag[Swimmer]
                assert(!mammalAndSwimmer.accepts(new Dog))
                assert(!mammalAndSwimmer.accepts(new Duck))
            }

            "with existing intersection" in {
                val animalAndSwimmer   = SafeClassTag[Animal & Swimmer]
                val tripleIntersection = animalAndSwimmer & SafeClassTag[Flyer]
                assert(tripleIntersection.accepts(new Duck))
                assert(!tripleIntersection.accepts(new Penguin))
            }
        }

        "| (union) method" - {
            "simple types" in {
                val intOrString = SafeClassTag[Int] | SafeClassTag[String]
                assert(intOrString.accepts(42))
                assert(intOrString.accepts("hello"))
                assert(!intOrString.accepts(true))
            }

            "class hierarchy" in {
                val mammalOrReptile = SafeClassTag[Mammal] | SafeClassTag[Reptile]
                assert(mammalOrReptile.accepts(new Dog))
                assert(mammalOrReptile.accepts(new Snake))
                assert(!mammalOrReptile.accepts(new Duck))
            }

            "with existing union" in {
                val dogOrCat    = SafeClassTag[Dog | Cat]
                val animalUnion = dogOrCat | SafeClassTag[Snake]
                assert(animalUnion.accepts(new Dog))
                assert(animalUnion.accepts(new Cat))
                assert(animalUnion.accepts(new Snake))
                assert(!animalUnion.accepts(new Duck))
            }
        }

        "combining & and |" in {
            val complexTag = (SafeClassTag[Int] & SafeClassTag[AnyVal]) | (SafeClassTag[String] & SafeClassTag[Any])
            assert(complexTag.accepts(42))
            assert(complexTag.accepts("hello"))
            assert(!complexTag.accepts(true))
        }
    }

end SafeClassTagTest
