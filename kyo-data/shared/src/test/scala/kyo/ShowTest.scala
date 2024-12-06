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
            assert(Show[(Int, Wr[String])].show((23, Wr("hello"))) == "(23,Yep(hello))")
        }
    }

    "interpolation" - {
        "should interpolate" in {
            assert(k"hello ${Wr(23)}" == "hello Yep(23)")
            assert(k"hello ${Wr(null)}" == "hello Nope")
        }
    }

end ShowTest
