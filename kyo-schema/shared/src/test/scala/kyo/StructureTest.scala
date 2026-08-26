package kyo

class StructureTest extends kyo.test.Test[Any]:

    "Value.primitive" - {

        "dispatches on the static type" - {
            "String" in assert(Structure.Value.primitive("a") == Structure.Value.Str("a"))
            "Int" in assert(Structure.Value.primitive(1) == Structure.Value.Integer(1L))
            "Long" in assert(Structure.Value.primitive(1L) == Structure.Value.Integer(1L))
            "Boolean" in assert(Structure.Value.primitive(true) == Structure.Value.Bool(true))
            "Char" in assert(Structure.Value.primitive('a') == Structure.Value.Str("a"))
        }

        "Span[Byte] is the only span the bytes branch claims" - {
            "a Span[Byte] is bytes" in {
                Structure.Value.primitive(Span(1.toByte, 2.toByte)) match
                    case Structure.Value.Bytes(bs) => assert(bs.toArray.toList == List(1.toByte, 2.toByte))
                    case other                     => fail(s"expected Bytes, got $other")
            }
            // A Span of any other element type used to reach this branch too, because a
            // parameterized opaque type's tag carried no arguments and so every Span tag was
            // the same tag. The branch then cast it to Span[Byte] and wrote its contents as
            // raw bytes.
            "a Span[Int] is not" in {
                assert(!Structure.Value.primitive(Span(1, 2, 3)).isInstanceOf[Structure.Value.Bytes])
            }
            "a Span[String] is not" in {
                assert(!Structure.Value.primitive(Span("a", "b")).isInstanceOf[Structure.Value.Bytes])
            }
        }
    }
end StructureTest
