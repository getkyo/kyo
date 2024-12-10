package kyo
import scala.language.implicitConversions

class ShowTest extends Test:

    type Wr[A] = Wr.Type[A]
    object Wr:
        opaque type Type[A] = A | Null
        def apply[A](a: A | Null): Type[A] = a

        given [A](using sha: Show[A]): Show[Wr[A]] with
            given CanEqual[A | Null, Null] = CanEqual.derived
            def show(value: Wr[A]): String = if value == null then "Nope" else s"Yep(${sha.show(value.asInstanceOf[A])})"
        end given
    end Wr

    enum ShowADT:
        case Obj
        case Nested(value: Wr[Int])

    "derivation" - {
        "should derive show for ADT correctly" in {
            assert(Show[ShowADT.Obj.type].show(ShowADT.Obj) == "Obj")
            assert(Show[ShowADT].show(ShowADT.Obj) == "Obj")
            val wr: Wr[Int] = Wr(23)
            assert(Show[ShowADT.Nested].show(ShowADT.Nested(wr)) == "Nested(Yep(23))")
            assert(Show[ShowADT].show(ShowADT.Nested(wr)) == "Nested(Yep(23))")
        }

        "should derive tuple correctly" in {
            assert(Show[EmptyTuple].show(EmptyTuple) == "EmptyTuple")
            assert(Show[Tuple1[Wr[String]]].show(Tuple1(Wr("hello"))) == "(Yep(hello))")
            assert(Show[(Int, Wr[String])].show((23, Wr("hello"))) == "(23,Yep(hello))")
            assert(Show[(Int, Wr[String], Wr[Nothing])].show((23, Wr("hello"), Wr(null))) == "(23,Yep(hello),Nope)")
        }
    }

    "interpolation" - {
        "should interpolate" in {
            assert(k"${23}" == "23")
            assert(k"prefix ${Wr(23)} suffix" == "prefix Yep(23) suffix")
            assert(k"prefix ${Wr(null)} suffix" == "prefix Nope suffix")
            assert(k"prefix ${ShowADT.Obj} suffix" == "prefix Obj suffix")
            assert(k"prefix ${ShowADT.Nested(Wr(null))} suffix" == "prefix Nested(Nope) suffix")
            assert(k"prefix ${ShowADT.Nested(Wr(23))} suffix" == "prefix Nested(Yep(23)) suffix")
        }
    }

end ShowTest
