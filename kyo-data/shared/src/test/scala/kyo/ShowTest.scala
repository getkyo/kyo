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

    case class ShowCase(value: Wr[Int])

    sealed trait ShowSealed
    object ShowSealed:
        case object Obj                             extends ShowSealed
        case class Nested(value: Wr[Int])           extends ShowSealed
        case class DoubleNested(showCase: ShowCase) extends ShowSealed
    end ShowSealed

    enum ShowADT:
        case Obj
        case Nested(value: Wr[Int])
        case DoubleNested(showCase: ShowCase)
    end ShowADT

    enum ShowNestedADT:
        case InnerSealed(value: ShowSealed)
        case InnerADT(value: ShowADT)

    "derivation" - {
        "should derive for a case class correctly" in {
            assert(Show[ShowCase].show(ShowCase(Wr(23))) == "ShowCase(Yep(23))")
            assert(Show.show(ShowCase(Wr(23))) == "ShowCase(Yep(23))")
            assert(Show[ShowCase].show(ShowCase(Wr(null))) == "ShowCase(Nope)")
            assert(Show.show(ShowCase(Wr(null))) == "ShowCase(Nope)")
        }

        "should derive show for sealed hierarchy correctly" in {
            assert(Show[ShowSealed.Obj.type].show(ShowSealed.Obj) == "Obj")
            assert(Show.show(ShowSealed.Obj) == "Obj")
            assert(Show[ShowSealed].show(ShowSealed.Obj) == "Obj")
            assert(Show.show(ShowSealed.Obj) == "Obj")
            val wr: Wr[Int] = Wr(23)
            assert(Show[ShowSealed.Nested].show(ShowSealed.Nested(wr)) == "Nested(Yep(23))")
            assert(Show.show(ShowSealed.Nested(wr)) == "Nested(Yep(23))")
            assert(Show[ShowSealed].show(ShowSealed.Nested(wr)) == "Nested(Yep(23))")
            assert(Show.show(ShowSealed.Nested(wr)) == "Nested(Yep(23))")
            assert(Show[ShowSealed.DoubleNested].show(ShowSealed.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Show.show(ShowSealed.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Show[ShowSealed].show(ShowSealed.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Show.show(ShowSealed.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
        }

        "should derive show for enum correctly" in {
            assert(Show[ShowADT.Obj.type].show(ShowADT.Obj) == "Obj")
            assert(Show.show(ShowADT.Obj) == "Obj")
            assert(Show[ShowADT].show(ShowADT.Obj) == "Obj")
            assert(Show.show(ShowADT.Obj) == "Obj")
            val wr: Wr[Int] = Wr(23)
            assert(Show[ShowADT.Nested].show(ShowADT.Nested(wr)) == "Nested(Yep(23))")
            assert(Show.show(ShowADT.Nested(wr)) == "Nested(Yep(23))")
            assert(Show[ShowADT].show(ShowADT.Nested(wr)) == "Nested(Yep(23))")
            assert(Show.show(ShowADT.Nested(wr)) == "Nested(Yep(23))")
            assert(Show[ShowADT.DoubleNested].show(ShowADT.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Show.show(ShowADT.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Show[ShowADT].show(ShowADT.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Show.show(ShowADT.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
        }

        "should derive show for highly nested type" in {
            assert(Show[ShowNestedADT].show(
                ShowNestedADT.InnerADT(ShowADT.DoubleNested(ShowCase(Wr(23))))
            ) == "InnerADT(DoubleNested(ShowCase(Yep(23))))")
            assert(Show.show(
                ShowNestedADT.InnerADT(ShowADT.DoubleNested(ShowCase(Wr(23))))
            ) == "InnerADT(DoubleNested(ShowCase(Yep(23))))")
            assert(Show[ShowNestedADT].show(
                ShowNestedADT.InnerSealed(ShowSealed.DoubleNested(ShowCase(Wr(23))))
            ) == "InnerSealed(DoubleNested(ShowCase(Yep(23))))")
            assert(Show.show(
                ShowNestedADT.InnerSealed(ShowSealed.DoubleNested(ShowCase(Wr(23))))
            ) == "InnerSealed(DoubleNested(ShowCase(Yep(23))))")
        }

        "should derive tuple correctly" in {
            assert(Show[EmptyTuple].show(EmptyTuple) == "EmptyTuple")
            assert(Show.show(EmptyTuple) == "EmptyTuple")
            assert(Show[Tuple1[Wr[String]]].show(Tuple1(Wr("hello"))) == "(Yep(hello))")
            assert(Show.show(Tuple1(Wr("hello"))) == "(Yep(hello))")
            assert(Show[(Int, Wr[String])].show((23, Wr("hello"))) == "(23,Yep(hello))")
            assert(Show.show((23, Wr("hello"))) == "(23,Yep(hello))")
            assert(Show[(Int, Wr[String], Wr[Nothing])].show((23, Wr("hello"), Wr(null))) == "(23,Yep(hello),Nope)")
            assert(Show.show((23, Wr("hello"), Wr(null))) == "(23,Yep(hello),Nope)")
        }

        "should support custom show" in {
            given Show[ShowCase] with
                def show(value: ShowCase): String =
                    "My Custom ShowCase: " + Show.show(value.value)

            assert(Show[ShowCase].show(ShowCase(Wr(23))) == "My Custom ShowCase: Yep(23)")
            assert(Show.show(ShowCase(Wr(23))) == "My Custom ShowCase: Yep(23)")
            assert(Show[ShowCase].show(ShowCase(Wr(null))) == "My Custom ShowCase: Nope")
            assert(Show.show(ShowCase(Wr(null))) == "My Custom ShowCase: Nope")
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
