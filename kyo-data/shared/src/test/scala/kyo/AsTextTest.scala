package kyo
import scala.language.implicitConversions

class AsTextTest extends Test:
    type Wr[A] = Wr.Type[A]
    object Wr:
        opaque type Type[A] = A | Null
        def apply[A](a: A | Null): Type[A] = a

        given [A](using ata: AsText[A]): AsText[Wr[A]] with
            given CanEqual[A | Null, Null]   = CanEqual.derived
            def asText(value: Wr[A]): String = if value == null then "Nope" else s"Yep(${ata.asText(value.asInstanceOf[A])})"
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
            assert(AsText[ShowCase].asText(ShowCase(Wr(23))).show == "ShowCase(Yep(23))")
            assert(AsText.asText(ShowCase(Wr(23))).show == "ShowCase(Yep(23))")
            assert(AsText[ShowCase].asText(ShowCase(Wr(null))).show == "ShowCase(Nope)")
            assert(AsText.asText(ShowCase(Wr(null))).show == "ShowCase(Nope)")
        }

        "should derive show for sealed hierarchy correctly" in {
            assert(AsText[ShowSealed.Obj.type].asText(ShowSealed.Obj).show == "Obj")
            assert(AsText.asText(ShowSealed.Obj).show == "Obj")
            assert(AsText[ShowSealed].asText(ShowSealed.Obj).show == "Obj")
            assert(AsText.asText(ShowSealed.Obj).show == "Obj")
            val wr: Wr[Int] = Wr(23)
            assert(AsText[ShowSealed.Nested].asText(ShowSealed.Nested(wr)).show == "Nested(Yep(23))")
            assert(AsText.asText(ShowSealed.Nested(wr)).show == "Nested(Yep(23))")
            assert(AsText[ShowSealed].asText(ShowSealed.Nested(wr)).show == "Nested(Yep(23))")
            assert(AsText.asText(ShowSealed.Nested(wr)).show == "Nested(Yep(23))")
            assert(AsText[ShowSealed.DoubleNested].asText(ShowSealed.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(AsText.asText(ShowSealed.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(AsText[ShowSealed].asText(ShowSealed.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(AsText.asText(ShowSealed.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
        }

        "should derive show for enum correctly" in {
            assert(AsText[ShowADT.Obj.type].asText(ShowADT.Obj).show == "Obj")
            assert(AsText.asText(ShowADT.Obj).show == "Obj")
            assert(AsText[ShowADT].asText(ShowADT.Obj).show == "Obj")
            assert(AsText.asText(ShowADT.Obj).show == "Obj")
            val wr: Wr[Int] = Wr(23)
            assert(AsText[ShowADT.Nested].asText(ShowADT.Nested(wr)).show == "Nested(Yep(23))")
            assert(AsText.asText(ShowADT.Nested(wr)).show == "Nested(Yep(23))")
            assert(AsText[ShowADT].asText(ShowADT.Nested(wr)).show == "Nested(Yep(23))")
            assert(AsText.asText(ShowADT.Nested(wr)).show == "Nested(Yep(23))")
            assert(AsText[ShowADT.DoubleNested].asText(ShowADT.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(AsText.asText(ShowADT.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(AsText[ShowADT].asText(ShowADT.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
            assert(AsText.asText(ShowADT.DoubleNested(ShowCase(wr))).show == "DoubleNested(ShowCase(Yep(23)))")
        }

        "should derive show for highly nested type" in {
            assert(AsText[ShowNestedADT].asText(
                ShowNestedADT.InnerADT(ShowADT.DoubleNested(ShowCase(Wr(23))))
            ).show == "InnerADT(DoubleNested(ShowCase(Yep(23))))")
            assert(AsText.asText(
                ShowNestedADT.InnerADT(ShowADT.DoubleNested(ShowCase(Wr(23))))
            ).show == "InnerADT(DoubleNested(ShowCase(Yep(23))))")
            assert(AsText[ShowNestedADT].asText(
                ShowNestedADT.InnerSealed(ShowSealed.DoubleNested(ShowCase(Wr(23))))
            ).show == "InnerSealed(DoubleNested(ShowCase(Yep(23))))")
            assert(AsText.asText(
                ShowNestedADT.InnerSealed(ShowSealed.DoubleNested(ShowCase(Wr(23))))
            ).show == "InnerSealed(DoubleNested(ShowCase(Yep(23))))")
        }

        "should derive tuple correctly" in {
            assert(AsText[EmptyTuple].asText(EmptyTuple).show == "EmptyTuple")
            assert(AsText.asText(EmptyTuple).show == "EmptyTuple")
            assert(AsText[Tuple1[Wr[String]]].asText(Tuple1(Wr("hello"))).show == "(Yep(hello))")
            assert(AsText.asText(Tuple1(Wr("hello"))).show == "(Yep(hello))")
            assert(AsText[(Int, Wr[String])].asText((23, Wr("hello"))).show == "(23,Yep(hello))")
            assert(AsText.asText((23, Wr("hello"))).show == "(23,Yep(hello))")
            assert(AsText[(Int, Wr[String], Wr[Nothing])].asText((23, Wr("hello"), Wr(null))).show == "(23,Yep(hello),Nope)")
            assert(AsText.asText((23, Wr("hello"), Wr(null))).show == "(23,Yep(hello),Nope)")
        }

        "should support custom show" in {
            given AsText[ShowCase] with
                def asText(value: ShowCase): String =
                    "My Custom ShowCase: " + AsText.asText(value.value)

            assert(AsText[ShowCase].asText(ShowCase(Wr(23))).show == "My Custom ShowCase: Yep(23)")
            assert(AsText.asText(ShowCase(Wr(23))).show == "My Custom ShowCase: Yep(23)")
            assert(AsText[ShowCase].asText(ShowCase(Wr(null))).show == "My Custom ShowCase: Nope")
            assert(AsText.asText(ShowCase(Wr(null))).show == "My Custom ShowCase: Nope")
        }
    }

    "interpolation" - {
        "should interpolate" in {
            assert(txt"${23}".show == "23")
            assert(txt"prefix ${Wr(23)} suffix".show == "prefix Yep(23) suffix")
            assert(txt"prefix ${Wr(null)} suffix".show == "prefix Nope suffix")
            assert(txt"prefix ${ShowADT.Obj} suffix".show == "prefix Obj suffix")
            assert(txt"prefix ${ShowADT.Nested(Wr(null))} suffix".show == "prefix Nested(Nope) suffix")
            assert(txt"prefix ${ShowADT.Nested(Wr(23))} suffix".show == "prefix Nested(Yep(23)) suffix")
        }
    }

end AsTextTest
