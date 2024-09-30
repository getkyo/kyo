package kyo

import kyo.Maybe
import kyo.internal.SafeClassTagMacro
import scala.annotation.tailrec
import scala.quoted.*

opaque type SafeClassTag[A] >: SafeClassTag.Element = Class[?] | SafeClassTag.Element

object SafeClassTag:
    sealed trait Element
    case class Union(elements: List[SafeClassTag[Any]])        extends Element
    case class Intersection(elements: List[SafeClassTag[Any]]) extends Element

    sealed trait Primitive extends Element

    case object IntTag     extends Primitive
    case object LongTag    extends Primitive
    case object DoubleTag  extends Primitive
    case object FloatTag   extends Primitive
    case object ByteTag    extends Primitive
    case object ShortTag   extends Primitive
    case object CharTag    extends Primitive
    case object BooleanTag extends Primitive
    case object UnitTag    extends Element
    case object AnyValTag  extends Element
    case object NothingTag extends Element

    inline given apply[A]: SafeClassTag[A] = ${ SafeClassTagMacro.derive[A] }

    extension [A](self: SafeClassTag[A])

        def accepts(value: Any): Boolean =
            !isNull(value) && {
                given CanEqual[Any, Any] = CanEqual.derived
                self match
                    case Union(elements)        => elements.exists(_.accepts(value))
                    case Intersection(elements) => elements.forall(_.accepts(value))
                    case NothingTag             => false
                    case UnitTag                => value.isInstanceOf[Unit]
                    case AnyValTag =>
                        value match
                            case (_: Int | _: Long | _: Double | _: Float | _: Byte | _: Short | _: Char | _: Boolean) =>
                                true
                            case _ =>
                                val cls = value.getClass
                                classOf[AnyVal].isAssignableFrom(cls) && (cls ne classOf[AnyVal])
                    case _: Primitive =>
                        value match
                            case _: Int     => self eq IntTag
                            case _: Long    => self eq LongTag
                            case _: Double  => self eq DoubleTag
                            case _: Float   => self eq FloatTag
                            case _: Byte    => self eq ByteTag
                            case _: Short   => self eq ShortTag
                            case _: Char    => self eq CharTag
                            case _: Boolean => self eq BooleanTag
                            case _          => false
                    case self: Class[?] => self.isInstance(value)
                end match
            }
        end accepts

        def unapply(value: Any): Maybe.Ops[A] =
            if accepts(value) then Maybe(value.asInstanceOf[A]) else Maybe.empty

        infix def &[B](that: SafeClassTag[B]): SafeClassTag[A & B] =
            self match
                case Intersection(e1) => that match
                        case Intersection(e2) => Intersection(e1 ++ e2)
                        case _                => Intersection(e1 :+ that)
                case _ => that match
                        case Intersection(e2) => Intersection(self +: e2)
                        case _                => Intersection(List(self, that))

        infix def |[B](that: SafeClassTag[B]): SafeClassTag[A | B] =
            self match
                case Union(e1) => that match
                        case Union(e2) => Union(e1 ++ e2)
                        case _         => Union(e1 :+ that)
                case _ => that match
                        case Union(e2) => Union(self +: e2)
                        case _         => Union(List(self, that))

        infix def <:<[B](that: SafeClassTag[B]): Boolean =
            given CanEqual[Any, Any] = CanEqual.derived
            self match
                case NothingTag             => true
                case Union(elements)        => elements.forall(_ <:< that)
                case Intersection(elements) => elements.exists(_ <:< that)
                case _ =>
                    that match
                        case NothingTag             => false
                        case Union(elements)        => elements.exists(self <:< _)
                        case Intersection(elements) => elements.forall(self <:< _)
                        case AnyValTag =>
                            self match
                                case AnyValTag | IntTag | LongTag | DoubleTag | FloatTag | ByteTag | ShortTag | CharTag | BooleanTag | UnitTag =>
                                    true
                                case _ => false
                        case UnitTag      => self == UnitTag
                        case _: Primitive => self == that
                        case cls: Class[?] =>
                            self match
                                case self: Class[?] => cls.isAssignableFrom(self)
                                case _              => false
            end match
        end <:<
    end extension

end SafeClassTag
