package kyo.stats

import kyo.stats.Attributes.AsAttribute
import scala.annotation.implicitNotFound

case class Attributes(get: List[Attributes.Attribute]) extends AnyVal {
    def add(a: Attributes): Attributes =
        Attributes(get ++ a.get)
    def add[T](name: String, value: T)(implicit a: AsAttribute[T]): Attributes =
        add(Attributes.add(name, value))
}

object Attributes {
    val empty: Attributes = Attributes(Nil)

    def add[T](name: String, value: T)(implicit a: AsAttribute[T]) =
        Attributes(a.f(name, value) :: Nil)

    def all(l: List[Attributes]): Attributes =
        Attributes(l.flatMap(_.get))

    sealed trait Attribute
    object Attribute {
        case class BooleanListAttribute(name: String, value: List[Boolean]) extends Attribute
        case class BooleanAttribute(name: String, value: Boolean)           extends Attribute
        case class DoubleListAttribute(name: String, value: List[Double])   extends Attribute
        case class DoubleAttribute(name: String, value: Double)             extends Attribute
        case class LongListAttribute(name: String, value: List[Long])       extends Attribute
        case class LongAttribute(name: String, value: Long)                 extends Attribute
        case class StringListAttribute(name: String, value: List[String])   extends Attribute
        case class StringAttribute(name: String, value: String)             extends Attribute
    }

    @implicitNotFound(
        "Invalid attribute type: '${T}'. Supported: 'Boolean', " +
            "'Double', 'Long', 'String', and 'List's of these types."
    )
    case class AsAttribute[T](f: (String, T) => Attribute)

    object AsAttribute {
        implicit val booleanList: AsAttribute[List[Boolean]] =
            AsAttribute(Attribute.BooleanListAttribute(_, _))
        implicit val boolean: AsAttribute[Boolean] =
            AsAttribute(Attribute.BooleanAttribute(_, _))
        implicit val doubleList: AsAttribute[List[Double]] =
            AsAttribute(Attribute.DoubleListAttribute(_, _))
        implicit val int: AsAttribute[Int] =
            AsAttribute(Attribute.LongAttribute(_, _))
        implicit val double: AsAttribute[Double] =
            AsAttribute(Attribute.DoubleAttribute(_, _))
        implicit val intList: AsAttribute[List[Int]] =
            AsAttribute((k, v) => Attribute.LongListAttribute(k, v.map(_.toLong)))
        implicit val longList: AsAttribute[List[Long]] =
            AsAttribute(Attribute.LongListAttribute(_, _))
        implicit val long: AsAttribute[Long] =
            AsAttribute(Attribute.LongAttribute(_, _))
        implicit val stringList: AsAttribute[List[String]] =
            AsAttribute(Attribute.StringListAttribute(_, _))
        implicit val string: AsAttribute[String] =
            AsAttribute(Attribute.StringAttribute(_, _))
    }
}
