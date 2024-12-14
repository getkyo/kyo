package kyo
import scala.language.implicitConversions

class RenderTest extends Test:
    type Wr[A] = Wr.Type[A]
    object Wr:
        opaque type Type[A] = A | Null
        def apply[A](a: A | Null): Type[A] = a

        given [A](using ra: Render[A]): Render[Wr[A]] with
            given CanEqual[A | Null, Null]   = CanEqual.derived
            def asText(value: Wr[A]): String = if value == null then "Nope" else s"Yep(${ra.asText(value.asInstanceOf[A])})"
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
            assert(Render[ShowCase].asText(ShowCase(Wr(23))).show == "ShowCase(Yep(23))")
            assert(Render.asText(ShowCase(Wr(23))).show == "ShowCase(Yep(23))")
            assert(Render[ShowCase].asText(ShowCase(Wr(null))).show == "ShowCase(Nope)")
            assert(Render.asText(ShowCase(Wr(null))).show == "ShowCase(Nope)")
        }

        "should derive show for sealed hierarchy correctly" in {
            assert(Render[ShowSealed.Obj.type].asText(ShowSealed.Obj).show == "Obj")
            assert(Render.asText(ShowSealed.Obj).show == "Obj")
            assert(Render[ShowSealed].asText(ShowSealed.Obj).show == "Obj")
            assert(Render.asText(ShowSealed.Obj).show == "Obj")
            val wr: Wr[Int] = Wr(23)
            assert(Render[ShowSealed.Nested].asText(ShowSealed.Nested(wr)).show == "Nested(Yep(23))")
            assert(Render.asText(ShowSealed.Nested(wr)).show == "Nested(Yep(23))")
            assert(Render[ShowSealed].asText(ShowSealed.Nested(wr)).show == "Nested(Yep(23))")
            assert(Render.asText(ShowSealed.Nested(wr)).show == "Nested(Yep(23))")
            assert(Render[ShowSealed.DoubleNested].asText(ShowSealed.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render.asText(ShowSealed.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render[ShowSealed].asText(ShowSealed.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render.asText(ShowSealed.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
        }

        "should derive show for enum correctly" in {
            assert(Render[ShowADT.Obj.type].asText(ShowADT.Obj).show == "Obj")
            assert(Render.asText(ShowADT.Obj).show == "Obj")
            assert(Render[ShowADT].asText(ShowADT.Obj).show == "Obj")
            assert(Render.asText(ShowADT.Obj).show == "Obj")
            val wr: Wr[Int] = Wr(23)
            assert(Render[ShowADT.Nested].asText(ShowADT.Nested(wr)).show == "Nested(Yep(23))")
            assert(Render.asText(ShowADT.Nested(wr)).show == "Nested(Yep(23))")
            assert(Render[ShowADT].asText(ShowADT.Nested(wr)).show == "Nested(Yep(23))")
            assert(Render.asText(ShowADT.Nested(wr)).show == "Nested(Yep(23))")
            assert(Render[ShowADT.DoubleNested].asText(ShowADT.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render.asText(ShowADT.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render[ShowADT].asText(ShowADT.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(Render.asText(ShowADT.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
        }

        "should derive show for highly nested type" in {
            assert(Render[ShowNestedADT].asText(
                ShowNestedADT.InnerADT(ShowADT.DoubleNested(ShowCase(Wr(23))))
            ).show == "InnerADT(DoubleNested(ShowCase(Yep(23))))")
            assert(Render.asText(
                ShowNestedADT.InnerADT(ShowADT.DoubleNested(ShowCase(Wr(23))))
            ).show == "InnerADT(DoubleNested(ShowCase(Yep(23))))")
            assert(Render[ShowNestedADT].asText(
                ShowNestedADT.InnerSealed(ShowSealed.DoubleNested(ShowCase(Wr(23))))
            ).show == "InnerSealed(DoubleNested(ShowCase(Yep(23))))")
            assert(Render.asText(
                ShowNestedADT.InnerSealed(ShowSealed.DoubleNested(ShowCase(Wr(23))))
            ).show == "InnerSealed(DoubleNested(ShowCase(Yep(23))))")
        }

        "should derive tuple correctly" in {
            assert(Render[EmptyTuple].asText(EmptyTuple).show == "EmptyTuple")
            assert(Render.asText(EmptyTuple).show == "EmptyTuple")
            assert(Render[Tuple1[Wr[String]]].asText(Tuple1(Wr("hello"))).show == "(Yep(hello))")
            assert(Render.asText(Tuple1(Wr("hello"))).show == "(Yep(hello))")
            assert(Render[(Int, Wr[String])].asText((23, Wr("hello"))).show == "(23,Yep(hello))")
            assert(Render.asText((23, Wr("hello"))).show == "(23,Yep(hello))")
            assert(Render[(Int, Wr[String], Wr[Nothing])].asText((23, Wr("hello"), Wr(null))).show == "(23,Yep(hello),Nope)")
            assert(Render.asText((23, Wr("hello"), Wr(null))).show == "(23,Yep(hello),Nope)")
        }

        "should support custom show" in {
            given Render[ShowCase] with
                def asText(value: ShowCase): String =
                    "My Custom ShowCase: " + Render.asText(value.value)

            assert(Render[ShowCase].asText(ShowCase(Wr(23))).show == "My Custom ShowCase: Yep(23)")
            assert(Render.asText(ShowCase(Wr(23))).show == "My Custom ShowCase: Yep(23)")
            assert(Render[ShowCase].asText(ShowCase(Wr(null))).show == "My Custom ShowCase: Nope")
            assert(Render.asText(ShowCase(Wr(null))).show == "My Custom ShowCase: Nope")
        }
    }

    "interpolation" - {
        "should interpolate" in {
            assert(t"${23}".show == "23")
            assert(t"prefix ${Wr(23)} suffix".show == "prefix Yep(23) suffix")
            assert(t"prefix ${Wr(null)} suffix".show == "prefix Nope suffix")
            assert(t"prefix ${ShowADT.Obj} suffix".show == "prefix Obj suffix")
            assert(t"prefix ${ShowADT.Nested(Wr(null))} suffix".show == "prefix Nested(Nope) suffix")
            assert(t"prefix ${ShowADT.Nested(Wr(23))} suffix".show == "prefix Nested(Yep(23)) suffix")
        }

        "should handle empty string" in {
            assert(t"".show == "")
        }
    }

end RenderTest
