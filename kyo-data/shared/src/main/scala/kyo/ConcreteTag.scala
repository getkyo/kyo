package kyo

import kyo.Maybe
import kyo.internal.ConcreteTagMacro
import scala.quoted.*

/** An alternative to ClassTag that supports union and intersection types.
  *
  * ConcreteTag provides runtime type information and type checking capabilities for both simple and complex types, including unions and
  * intersections. Unlike ClassTag, ConcreteTag can represent and check against union and intersection types, making it safer for complex
  * type scenarios. It also properly handles special types like AnyVal and Nothing instead of falling back to java.lang.Object like
  * ClassTag.
  *
  * Limitations:
  *   - Does not support generic types (types with type parameters)
  *   - Cannot represent Null type
  *
  * @tparam A
  *   The type for which this ConcreteTag is defined
  */
opaque type ConcreteTag[A] >: ConcreteTag.Element = Class[?] | ConcreteTag.Element

object ConcreteTag:
    inline given [A, B]: CanEqual[ConcreteTag[A], ConcreteTag[B]] = CanEqual.derived

    sealed trait Element

    case class Union(elements: Set[ConcreteTag[Any]])        extends Element
    case class Intersection(elements: Set[ConcreteTag[Any]]) extends Element
    case class LiteralTag(value: Any)                        extends Element

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

    inline given apply[A]: ConcreteTag[A] = ${ ConcreteTagMacro.derive[A] }

    /** Creates a ConcreteTag from a Java class, mapping primitive classes to their corresponding Primitive tags.
      *
      * @param cls
      *   the Java class
      * @return
      *   a ConcreteTag corresponding to the class
      */
    def fromClass[A](cls: Class[?]): ConcreteTag[A] =
        (if cls eq java.lang.Integer.TYPE then IntTag
         else if cls eq java.lang.Long.TYPE then LongTag
         else if cls eq java.lang.Double.TYPE then DoubleTag
         else if cls eq java.lang.Float.TYPE then FloatTag
         else if cls eq java.lang.Byte.TYPE then ByteTag
         else if cls eq java.lang.Short.TYPE then ShortTag
         else if cls eq java.lang.Character.TYPE then CharTag
         else if cls eq java.lang.Boolean.TYPE then BooleanTag
         else cls) .asInstanceOf[ConcreteTag[A]]
    end fromClass

    /** Derives a ConcreteTag from an existing array's component type.
      *
      * @param array
      *   the array to inspect
      * @return
      *   a ConcreteTag matching the array's primitive or reference component type
      */
    def fromArray[A](array: Array[A]): ConcreteTag[A] =
        fromClass(array.getClass.getComponentType)

    /** Creates a new array with the correct primitive or reference type.
      *
      * @param len
      *   the length of the array to create
      * @param ct
      *   the ConcreteTag determining the array's component type
      * @return
      *   a new array of the appropriate type
      */
    def newArray[A](len: Int)(using ct: ConcreteTag[A]): Array[A] =
        ((ct: @unchecked) match
            case IntTag        => new Array[Int](len)
            case LongTag       => new Array[Long](len)
            case DoubleTag     => new Array[Double](len)
            case FloatTag      => new Array[Float](len)
            case ByteTag       => new Array[Byte](len)
            case ShortTag      => new Array[Short](len)
            case CharTag       => new Array[Char](len)
            case BooleanTag    => new Array[Boolean](len)
            case cls: Class[?] => java.lang.reflect.Array.newInstance(cls, len)
            case _             => new Array[AnyRef](len)
        ).asInstanceOf[Array[A]]
    end newArray

    /** Copies an array to a new length, preserving the component type.
      *
      * @param array
      *   the source array
      * @param newLen
      *   the length of the new array
      * @return
      *   a new array with elements copied from the source
      */
    def copyOf[A](array: Array[A], newLen: Int): Array[A] =
        val copy = java.lang.reflect.Array.newInstance(
            array.getClass.getComponentType,
            newLen
        ).asInstanceOf[Array[A]]
        System.arraycopy(array, 0, copy, 0, Math.min(array.length, newLen))
        copy
    end copyOf

    extension [A](self: ConcreteTag[A])

        /** Returns the Java class corresponding to this ConcreteTag.
          *
          * For primitive tags, returns the primitive class (e.g. `java.lang.Integer.TYPE` for `Int`). For reference types, returns the
          * class directly. For complex types (Union, Intersection, Literal, AnyVal, Nothing), returns `classOf[AnyRef]`.
          *
          * @return
          *   the Java class for this tag
          */
        def toClass: Class[?] =
            self match
                case IntTag        => java.lang.Integer.TYPE
                case LongTag       => java.lang.Long.TYPE
                case DoubleTag     => java.lang.Double.TYPE
                case FloatTag      => java.lang.Float.TYPE
                case ByteTag       => java.lang.Byte.TYPE
                case ShortTag      => java.lang.Short.TYPE
                case CharTag       => java.lang.Character.TYPE
                case BooleanTag    => java.lang.Boolean.TYPE
                case UnitTag       => java.lang.Void.TYPE
                case cls: Class[?] => cls
                case _             => classOf[AnyRef]
            end match
        end toClass

        /** Checks if the given value is accepted by this ConcreteTag
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

        /** Combines this ConcreteTag with another to form an intersection type
          *
          * @param that
          *   The ConcreteTag to intersect with
          * @return
          *   A new ConcreteTag representing the intersection of this and that
          */
        infix def &[B](that: ConcreteTag[B]): ConcreteTag[A & B] =
            self match
                case Intersection(e1) => that match
                        case Intersection(e2) => Intersection(e1 ++ e2)
                        case _                => Intersection(e1 + that)
                case _ => that match
                        case Intersection(e2) => Intersection(e2 + self)
                        case _                => Intersection(Set(self, that))

        /** Combines this ConcreteTag with another to form a union type
          *
          * @param that
          *   The ConcreteTag to union with
          * @return
          *   A new ConcreteTag representing the union of this and that
          */
        infix def |[B](that: ConcreteTag[B]): ConcreteTag[A | B] =
            self match
                case Union(e1) => that match
                        case Union(e2) => Union(e1 ++ e2)
                        case _         => Union(e1 + that)
                case _ => that match
                        case Union(e2) => Union(e2 + self)
                        case _         => Union(Set(self, that))

        /** Checks if this ConcreteTag is a subtype of another ConcreteTag
          *
          * @param that
          *   The ConcreteTag to check against
          * @return
          *   true if this is a subtype of that, false otherwise
          */
        infix def <:<[B](that: ConcreteTag[B]): Boolean =
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

        /** Returns a string representation of this ConcreteTag
          *
          * @return
          *   A string describing the structure of this ConcreteTag
          */
        def show: String =
            def showInner(tag: ConcreteTag[Any]): String =
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

            s"ConcreteTag[${showInner(self)}]"
        end show
    end extension

end ConcreteTag
