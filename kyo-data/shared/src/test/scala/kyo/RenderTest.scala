package kyo
import scala.language.implicitConversions

class RenderTest extends Test:
    type Wr[A] = Wr.Type[A]
    object Wr:
        opaque type Type[A] = A | Null
        def apply[A](a: A | Null): Type[A] = a

        given [A](using ra: Render[A]): Render[Wr[A]] with
            given CanEqual[A | Null, Null]     = CanEqual.derived
            def asString(value: Wr[A]): String = if value == null then "Nope" else s"Yep(${ra.asString(value.asInstanceOf[A])})"
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
            assert(Render[ShowCase].asString(ShowCase(Wr(23))) == "ShowCase(Yep(23))")
            assert(Render.asString(ShowCase(Wr(23))) == "ShowCase(Yep(23))")
            assert(Render[ShowCase].asString(ShowCase(Wr(null))) == "ShowCase(Nope)")
            assert(Render.asString(ShowCase(Wr(null))) == "ShowCase(Nope)")
        }

        "should derive show for sealed hierarchy correctly" in {
            assert(Render[ShowSealed.Obj.type].asString(ShowSealed.Obj) == "Obj")
            assert(Render.asString(ShowSealed.Obj) == "Obj")
            assert(Render[ShowSealed].asString(ShowSealed.Obj) == "Obj")
            assert(Render.asString(ShowSealed.Obj) == "Obj")
            val wr: Wr[Int] = Wr(23)
            assert(Render[ShowSealed.Nested].asString(ShowSealed.Nested(wr)) == "Nested(Yep(23))")
            assert(Render.asString(ShowSealed.Nested(wr)) == "Nested(Yep(23))")
            assert(Render[ShowSealed].asString(ShowSealed.Nested(wr)) == "Nested(Yep(23))")
            assert(Render.asString(ShowSealed.Nested(wr)) == "Nested(Yep(23))")
            assert(Render[ShowSealed.DoubleNested].asString(ShowSealed.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render.asString(ShowSealed.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render[ShowSealed].asString(ShowSealed.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render.asString(ShowSealed.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
        }

        "should derive show for enum correctly" in {
            assert(Render[ShowADT.Obj.type].asString(ShowADT.Obj) == "Obj")
            assert(Render.asString(ShowADT.Obj) == "Obj")
            assert(Render[ShowADT].asString(ShowADT.Obj) == "Obj")
            assert(Render.asString(ShowADT.Obj) == "Obj")
            val wr: Wr[Int] = Wr(23)
            assert(Render[ShowADT.Nested].asString(ShowADT.Nested(wr)) == "Nested(Yep(23))")
            assert(Render.asString(ShowADT.Nested(wr)) == "Nested(Yep(23))")
            assert(Render[ShowADT].asString(ShowADT.Nested(wr)) == "Nested(Yep(23))")
            assert(Render.asString(ShowADT.Nested(wr)) == "Nested(Yep(23))")
            assert(Render[ShowADT.DoubleNested].asString(ShowADT.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render.asString(ShowADT.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render[ShowADT].asString(ShowADT.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render.asString(ShowADT.DoubleNested(ShowCase(wr))) == "DoubleNested(ShowCase(Yep(23)))")
        }

        "should derive show for highly nested type" in {
            assert(Render[ShowNestedADT].asString(
                ShowNestedADT.InnerADT(ShowADT.DoubleNested(ShowCase(Wr(23))))
            ) == "InnerADT(DoubleNested(ShowCase(Yep(23))))")
            assert(Render.asString(
                ShowNestedADT.InnerADT(ShowADT.DoubleNested(ShowCase(Wr(23))))
            ) == "InnerADT(DoubleNested(ShowCase(Yep(23))))")
            assert(Render[ShowNestedADT].asString(
                ShowNestedADT.InnerSealed(ShowSealed.DoubleNested(ShowCase(Wr(23))))
            ) == "InnerSealed(DoubleNested(ShowCase(Yep(23))))")
            assert(Render.asString(
                ShowNestedADT.InnerSealed(ShowSealed.DoubleNested(ShowCase(Wr(23))))
            ) == "InnerSealed(DoubleNested(ShowCase(Yep(23))))")
        }

        "should derive tuple correctly" in {
            assert(Render[EmptyTuple].asString(EmptyTuple) == "EmptyTuple")
            assert(Render.asString(EmptyTuple) == "EmptyTuple")
            assert(Render[Tuple1[Wr[String]]].asString(Tuple1(Wr("hello"))) == "(Yep(hello))")
            assert(Render.asString(Tuple1(Wr("hello"))) == "(Yep(hello))")
            assert(Render[(Int, Wr[String])].asString((23, Wr("hello"))) == "(23,Yep(hello))")
            assert(Render.asString((23, Wr("hello"))) == "(23,Yep(hello))")
            assert(Render[(Int, Wr[String], Wr[Nothing])].asString((23, Wr("hello"), Wr(null))) == "(23,Yep(hello),Nope)")
            assert(Render.asString((23, Wr("hello"), Wr(null))) == "(23,Yep(hello),Nope)")
        }

        "should support custom show" in {
            given Render[ShowCase] with
                def asString(value: ShowCase): String =
                    "My Custom ShowCase: " + Render.asString(value.value)

            assert(Render[ShowCase].asString(ShowCase(Wr(23))) == "My Custom ShowCase: Yep(23)")
            assert(Render.asString(ShowCase(Wr(23))) == "My Custom ShowCase: Yep(23)")
            assert(Render[ShowCase].asString(ShowCase(Wr(null))) == "My Custom ShowCase: Nope")
            assert(Render.asString(ShowCase(Wr(null))) == "My Custom ShowCase: Nope")
        }
    }

    "interpolation" - {
        "should interpolate" in {
            assert(t"${23}" == "23")
            assert(t"prefix ${Wr(23)} suffix" == "prefix Yep(23) suffix")
            assert(t"prefix ${Wr(null)} suffix" == "prefix Nope suffix")
            assert(t"prefix ${ShowADT.Obj} suffix" == "prefix Obj suffix")
            assert(t"prefix ${ShowADT.Nested(Wr(null))} suffix" == "prefix Nested(Nope) suffix")
            assert(t"prefix ${ShowADT.Nested(Wr(23))} suffix" == "prefix Nested(Yep(23)) suffix")
        }

        "should handle empty string" in {
            assert(t"" == "")
        }
    }

end RenderTest
