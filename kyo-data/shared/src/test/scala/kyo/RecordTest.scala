package kyo

class RecordTest extends Test:
    "empty record" in {
        val empty = Record.init
        assert(empty.toMap.isEmpty)
    }

    "type-safe access" in {
        val record: Record["name" ~> String | "age" ~> Int] = Record.init("name" ~> "John")("age" ~> 30)
        assert(record("name") == "John")
        assert(record("age") == 30)
    }

    "tt" in {
        val record: Record["name" ~> String] = Record.init("name" ~> "John")("name" ~> 30)
        val a                                = record("name")
        succeed
    }

    // "field dropping" in {
    //     val record                               = Record.init("name" ~> "John")("age" ~> 30)
    //     val withoutAge: Record["name" ~> String] = record
    //     assert(withoutAge("name") == "John")
    //     assertDoesNotCompile("""withoutAge("age")""")
    // }

//     "merging records" in {
//         val person  = Record.init("name" ~> "John")("age" ~> 30)
//         val contact = Record.init("email" ~> "john@example.com")("phone" ~> "123-456-7890")

//         val merged = person ++ contact
//         assert(merged("name") == "John")
//         assert(merged("email") == "john@example.com")
//         assert(merged("phone") == "123-456-7890")
//         assert(merged("age") == 30)
//     }

//     "nested records" in {
//         val address = Record.init("street" ~> "Main St")("city" ~> "Springfield")
//         val person  = Record.init("name" ~> "John")("address" ~> address)

//         assert(person("name") == "John")
//         assert(person("address")("street") == "Main St")
//     }

//     "complex types" in {
//         case class CustomId(value: String) derives CanEqual
//         trait Role derives CanEqual
//         case object Admin extends Role
//         case object User  extends Role

//         val record = Record.init("id" ~> CustomId("123"))("role" ~> Admin)
//         assert(record("id") == CustomId("123"))
//         assert(record("role") == Admin)
//     }

//     "type constraints" - {
//         "prevents accessing non-existent fields" in {
//             assertDoesNotCompile("""
//                 val record = Record.init("name" ~> "John")
//                 record("age")
//             """)
//         }

//         "prevents type mismatches" in {
//             assertDoesNotCompile("""
//                 val record = Record.init("age" ~> 30)
//                 val x: String = record("age")
//             """)
//         }

//         "prevents invalid field additions" in {
//             assertDoesNotCompile("""
//                 val x: Record["test" ~> String] = Record.init.add("test" ~> 42)
//             """)
//         }

//         "prevents assigning incorrect record types" in {
//             val record: Record["age" ~> Int | "name" ~> String] = Record.init("age" ~> 30)("name" ~> "John")
//             assertDoesNotCompile("""
//                 val invalid: Record["age" ~> Int | "invalid" ~> Thread] = record
//             """)
//             assertDoesNotCompile("""
//                 val invalid: Record["age" ~> Int | "named" ~> Int] = record
//             """)
//         }
//     }

//     // "equality and comparison" in {
//     //     given [A, B]: CanEqual[A, B] = CanEqual.derived
//     //     val record1                  = Record.init("a" ~> 1)("b" ~> "test")
//     //     val record2                  = Record.init("a" ~> 1)("b" ~> "test")
//     //     val record3                  = Record.init("a" ~> 2)("b" ~> "test")

//     //     assert(record1.toMap == record2.toMap)
//     //     assert(record1.toMap != record3.toMap)
//     // }

//     // "edge cases" - {

//     //     "nested structures" in {
//     //         val level3 = Record.init("data" ~> "deep")
//     //         val level2 = Record.init("next" ~> level3)
//     //         val level1 = Record.init("nested" ~> level2)

//     //         assert(level1("nested")("next")("data") == "deep")
//     //     }

//     //     "type hierarchy" in {
//     //         trait Animal
//     //         class Dog extends Animal
//     //         class Cat extends Animal

//     //         val record      = Record.init("pet" ~> new Dog)
//     //         val pet: Animal = record("pet")
//     //         assert(pet.isInstanceOf[Dog])
//     //     }
//     // }

//     // trait Base
//     // class Sub1 extends Base
//     // class Sub2 extends Base

//     // "field merging with same name" - {
//     //     "same type" in {
//     //         val r1 = Record.init("x" ~> 1)
//     //         val r2 = Record.init("x" ~> 2)
//     //         val r3 = r1 ++ r2
//     //         assert(r3("x") == 2)
//     //     }

//     //     "base and subtype" in {
//     //         val sub = new Sub1
//     //         val r1  = Record.init("x" ~> sub)
//     //         val r2  = Record.init("x" ~> (sub: Base))
//     //         assertCompiles("val r3 = r1 ++ r2")
//     //         assertCompiles("val r4 = r2 ++ r1")
//     //     }

//     //     "different subtypes" in {
//     //         val r1 = Record.init("x" ~> new Sub1)
//     //         val r2 = Record.init("x" ~> new Sub2)
//     //         assertCompiles("""
//     //             val r3 = r1 ++ r2
//     //             val x: Base = r3("x")
//     //         """)
//     //     }

//     //     "unrelated types" in {
//     //         val r1                                     = Record.init("x" ~> 1)
//     //         val r2                                     = Record.init("x" ~> "str")
//     //         val r3: Record["x" ~> Int | "x" ~> String] = r1 ++ r2
//     //         succeed
//     //     }
//     // }

//     // "type inference" - {
//     //     "field type is inferred from value" in {
//     //         val r = Record.init("x" ~> 1)
//     //         assertCompiles("val x: Int = r(\"x\")")
//     //         assertDoesNotCompile("val x: String = r(\"x\")")
//     //     }

//     //     "merged record type contains all fields" in {
//     //         val r1 = Record.init("x" ~> 1)("y" ~> "a")
//     //         val r2 = Record.init("z" ~> true)
//     //         val r3 = r1 ++ r2
//     //         assertCompiles("""
//     //             val x: Int = r3("x")
//     //             val y: String = r3("y")
//     //             val z: Boolean = r3("z")
//     //         """)
//     //     }

//     //     "field shadowing keeps last type" in {
//     //         val r1 = Record.init("x" ~> "str")
//     //         val r2 = Record.init("x" ~> 1)
//     //         val r3 = r1 ++ r2
//     //         assertCompiles("val x: Int = r3(\"x\")")
//     //         assertDoesNotCompile("val x: String = r3(\"x\")")
//     //     }
//     // }

//     // "subtyping" - {
//     //     "record with subset of fields is subtype" in {
//     //         val full: Record["x" ~> Int | "y" ~> String] =
//     //             Record.init("x" ~> 1)("y" ~> "a")
//     //         val subset: Record["x" ~> Int] = full
//     //         assert(subset("x") == 1)
//     //         assertDoesNotCompile("subset(\"y\")")
//     //     }

//     //     "record with field subtypes is subtype" in {
//     //         val r1 = Record.init("x" ~> new Sub1)
//     //         assertCompiles("""
//     //             val r2: Record["x" ~> Base] = r1
//     //         """)
//     //     }

//     //     "record with both subset and subtypes" in {
//     //         val r1: Record["x" ~> Sub1 | "y" ~> String] =
//     //             Record.init("x" ~> new Sub1)("y" ~> "a")
//     //         assertCompiles("""
//     //             val r2: Record["x" ~> Base] = r1
//     //         """)
//     //     }
//     // }

//     // "type bounds" - {
//     //     "upper bounds" in {
//     //         def takeBase[A <: Base](r: Record["x" ~> A]): A = r("x")
//     //         val r1                                          = Record.init("x" ~> new Sub1)
//     //         assertCompiles("takeBase(r1)")
//     //         assertDoesNotCompile("""
//     //             val r2 = Record.init("x" ~> "str")
//     //             takeBase(r2)
//     //         """)
//     //     }

//     //     "lower bounds" in {
//     //         def takeSuperOfSub[A >: Sub1](r: Record["x" ~> A]): A = r("x")
//     //         val r1                                                = Record.init("x" ~> (new Sub1: Base))
//     //         assertCompiles("takeSuperOfSub(r1)")
//     //         assertDoesNotCompile("""
//     //             val r2 = Record.init("x" ~> "str")
//     //             takeSuperOfSub(r2)
//     //         """)
//     //     }
//     // }
end RecordTest
