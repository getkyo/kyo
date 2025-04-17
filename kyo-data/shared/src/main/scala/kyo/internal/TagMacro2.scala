package kyo.internal

import Tag2.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import scala.util.Using

// Tags can be a regular decoded object or a serialized via java serialization
opaque type Tag2[A] >: Concrete[A] | Generic[A] = String | Generic[A] | Concrete[A]

object Tag2:

    def apply[A](using tag: Tag2[A]): Tag2[A] = tag

    def derive[A] = ??? // macro can derive concrete or generic

    extension [A](tag: Tag2[A])

        private def decoded: Tag2[A] = tag match
            case s: String => Serializer.deserialize[Tag2[A]](s)
            case _         => tag

        infix def <:<[B](that: Tag2[B]): Boolean =
            false

        infix def =:=[B](that: Tag2[B]): Boolean =
            false

        infix def >:>[B](that: Tag2[B]): Boolean =
            that <:< self

        infix def |[B](that: Tag2[B]): Tag2[A | B] =
            Generic.UnionTag(Set(tag, that).asInstanceOf[Set[Tag2[Any]]])

        infix def &[B](that: Tag2[B]): Tag2[A & B] =
            Generic.IntersectionTag(Set(tag, that).asInstanceOf[Set[Tag2[Any]]])
    end extension

    opaque type Concrete[A] = String | Concrete.Decoded[A]

    object Concrete:
        def apply[A](using tag: Concrete[A]): Concrete[A] = tag

        def derive[A] = ??? // macro restricts to only concrete tags

        extension [A](tag: Concrete[A])
            private def decoded: Concrete.Decoded[A] = tag match
                case s: String     => Serializer.deserialize[Concrete.Decoded[A]](s)
                case d: Decoded[A] => d

            def accepts(value: Any): Boolean =
                given CanEqual[Any, Any] = CanEqual.derived
                if value == null then
                    tag.decoded == NullTag
                else
                    tag.decoded match
                        case NothingTag => false
                        case NullTag    => value == null
                        case AnyTag     => true
                        case AnyValTag =>
                            value match
                                case _: Int | _: Long | _: Double | _: Float | _: Byte | _: Short | _: Char | _: Boolean =>
                                    true
                                case _ =>
                                    val cls = value.getClass
                                    classOf[AnyVal].isAssignableFrom(cls) && (cls ne classOf[AnyVal])
                        case UnitTag                => value.isInstanceOf[Unit]
                        case BooleanTag             => value.isInstanceOf[Boolean]
                        case ByteTag                => value.isInstanceOf[Byte]
                        case ShortTag               => value.isInstanceOf[Short]
                        case IntTag                 => value.isInstanceOf[Int]
                        case LongTag                => value.isInstanceOf[Long]
                        case FloatTag               => value.isInstanceOf[Float]
                        case DoubleTag              => value.isInstanceOf[Double]
                        case LiteralTag(_, literal) => value == literal
                        case ClassTag(className) =>
                            try
                                val tagClass = Class.forName(className)
                                tagClass.isInstance(value)
                            catch
                                case _: ClassNotFoundException => false
                end if
            end accepts
        end extension

        sealed abstract class Decoded[A] extends Serializable

        case object NothingTag extends Decoded[Nothing]
        case object NullTag    extends Decoded[Null]
        case object AnyTag     extends Decoded[Any]
        case object AnyValTag  extends Decoded[AnyVal]
        case object UnitTag    extends Decoded[Unit]

        sealed abstract class Primitive[A] extends Decoded[A]

        case object BooleanTag extends Primitive[Boolean]
        case object ByteTag    extends Primitive[Byte]
        case object ShortTag   extends Primitive[Short]
        case object IntTag     extends Primitive[Int]
        case object LongTag    extends Primitive[Long]
        case object FloatTag   extends Primitive[Float]
        case object DoubleTag  extends Primitive[Double]

        case class LiteralTag[A](tag: Tag2[A], value: A) extends Decoded[A]
        case class ClassTag[A](clazz: Class[A])          extends Decoded[A]
    end Concrete

    sealed abstract class Generic[A] extends Serializable

    object Generic:

        case class IntersectionTag[A](tags: Set[Tag2[Any]]) extends Generic[A]
        case class UnionTag[A](tags: Set[Tag2[Any]])        extends Generic[A]

        enum Variance extends Serializable:
            case Invariant, Covariant, Contravariant

        case class ClassTag[A](
            className: String,
            params: IArray[(Variance, Tag2[Any])],
            baseClasses: IArray[Tag2[Any]]
        ) extends Generic[A]
    end Generic

    private object Serializer:
        private val cache = new ConcurrentHashMap[String, Any]()

        def serialize(obj: Any): String =
            Using.resource(new ByteArrayOutputStream()) { baos =>
                Using.resource(new ObjectOutputStream(baos)) { oos =>
                    oos.writeObject(obj)
                }
                Base64.getEncoder.encodeToString(baos.toByteArray)
            }

        def deserialize[A](encoded: String): A =
            cache.computeIfAbsent(
                encoded,
                s =>
                    val bytes = Base64.getDecoder.decode(s)
                    Using.resource(new ByteArrayInputStream(bytes)) { bais =>
                        Using.resource(new ObjectInputStream(bais)) { ois =>
                            ois.readObject()
                        }
                    }
            ).asInstanceOf[A]
    end Serializer
end Tag2
