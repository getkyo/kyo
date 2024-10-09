package kyo

import kyo.Maybe
import kyo.internal.SafeClassTagMacro
import scala.annotation.tailrec
import scala.quoted.*

/** An alternative to ClassTag that supports union and intersection types.
  *
  * SafeClassTag provides runtime type information and type checking capabilities for both simple and complex types, including unions and
  * intersections. Unlike ClassTag, SafeClassTag can represent and check against union and intersection types, making it safer for complex
  * type scenarios. It also properly handles special types like AnyVal and Nothing instead of falling back to java.lang.Object like
  * ClassTag.
  *
  * Limitations:
  *   - Does not support generic types (types with type parameters)
  *   - Cannot represent Null type
  *
  * @tparam A
  *   The type for which this SafeClassTag is defined
  */
opaque type SafeClassTag[A] >: SafeClassTag.Element = Class[?] | SafeClassTag.Element

object SafeClassTag:
    sealed trait Element

    case class Union(elements: List[SafeClassTag[Any]])        extends Element
    case class Intersection(elements: List[SafeClassTag[Any]]) extends Element
    case class LiteralTag(value: Any)                          extends Element

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

        /** Checks if the given value is accepted by this SafeClassTag
          *
          * @param value
          *   The value to check
          * @return
          *   true if the value is accepted, false otherwise
          */
        def accepts(value: Any): Boolean =
            !isNull(value) && {
                given CanEqual[Any, Any] = CanEqual.derived
                self match
                    case Union(elements)        => elements.exists(_.accepts(value))
                    case Intersection(elements) => elements.forall(_.accepts(value))
                    case LiteralTag(literal)    => value == literal
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

        /** Attempts to extract a value of type A from the given value
          *
          * @param value
          *   The value to extract from
          * @return
          *   A Maybe containing the extracted value if successful, or empty if not
          */
        def unapply(value: Any): Maybe.Ops[A] =
            if accepts(value) then Maybe(value.asInstanceOf[A]) else Maybe.empty

        /** Combines this SafeClassTag with another to form an intersection type
          *
          * @param that
          *   The SafeClassTag to intersect with
          * @return
          *   A new SafeClassTag representing the intersection of this and that
          */
        infix def &[B](that: SafeClassTag[B]): SafeClassTag[A & B] =
            self match
                case Intersection(e1) => that match
                        case Intersection(e2) => Intersection(e1 ++ e2)
                        case _                => Intersection(that :: e1)
                case _ => that match
                        case Intersection(e2) => Intersection(self :: e2)
                        case _                => Intersection(List(self, that))

        /** Combines this SafeClassTag with another to form a union type
          *
          * @param that
          *   The SafeClassTag to union with
          * @return
          *   A new SafeClassTag representing the union of this and that
          */
        infix def |[B](that: SafeClassTag[B]): SafeClassTag[A | B] =
            self match
                case Union(e1) => that match
                        case Union(e2) => Union(e1 ++ e2)
                        case _         => Union(that :: e1)
                case _ => that match
                        case Union(e2) => Union(self :: e2)
                        case _         => Union(List(self, that))

        /** Checks if this SafeClassTag is a subtype of another SafeClassTag
          *
          * @param that
          *   The SafeClassTag to check against
          * @return
          *   true if this is a subtype of that, false otherwise
          */
        infix def <:<[B](that: SafeClassTag[B]): Boolean =
            given CanEqual[Any, Any] = CanEqual.derived
            self match
                case NothingTag             => true
                case Union(elements)        => elements.forall(_ <:< that)
                case Intersection(elements) => elements.exists(_ <:< that)
                case LiteralTag(value)      => that.accepts(value)
                case _ =>
                    that match
                        case NothingTag             => false
                        case Union(elements)        => elements.exists(self <:< _)
                        case Intersection(elements) => elements.forall(self <:< _)
                        case LiteralTag(value)      => false
                        case AnyValTag =>
                            self match
                                case AnyValTag | IntTag | LongTag | DoubleTag | FloatTag | ByteTag | ShortTag | CharTag | BooleanTag | UnitTag =>
                                    true
                                case _ => false
                        case UnitTag      => self eq UnitTag
                        case _: Primitive => self eq that
                        case cls: Class[?] =>
                            self match
                                case self: Class[?] => cls.isAssignableFrom(self)
                                case _              => false
            end match
        end <:<

        /** Returns a string representation of this SafeClassTag
          *
          * @return
          *   A string describing the structure of this SafeClassTag
          */
        def show: String =
            def showInner(tag: SafeClassTag[Any]): String =
                given CanEqual[Any, Any] = CanEqual.derived
                tag match
                    case Union(elements)        => s"(${elements.map(showInner).mkString(" | ")})"
                    case Intersection(elements) => s"(${elements.map(showInner).mkString(" & ")})"
                    case LiteralTag(value)      => s"$value"
                    case NothingTag             => "Nothing"
                    case UnitTag                => "Unit"
                    case AnyValTag              => "AnyVal"
                    case IntTag                 => "Int"
                    case LongTag                => "Long"
                    case DoubleTag              => "Double"
                    case FloatTag               => "Float"
                    case ByteTag                => "Byte"
                    case ShortTag               => "Short"
                    case CharTag                => "Char"
                    case BooleanTag             => "Boolean"
                    case cls: Class[?]          => cls.getSimpleName
                end match
            end showInner

            s"SafeClassTag[${showInner(self)}]"
        end show
    end extension

end SafeClassTag
