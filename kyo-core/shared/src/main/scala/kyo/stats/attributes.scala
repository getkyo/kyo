package kyo.stats

import scala.annotation.implicitNotFound

object attributes {

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

  case class Attributes(get: List[Attribute]) extends AnyVal {
    def add(a: Attributes): Attributes =
      Attributes(get ++ a.get)
  }

  object Attributes {
    val empty: Attributes = Attributes(Nil)

    def of[T](name: String, value: T)(implicit a: AsAttribute[T]) =
      Attributes(a.f(name, value) :: Nil)

    def all(l: List[Attributes]): Attributes =
      Attributes(l.flatMap(_.get))
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
    implicit val double: AsAttribute[Double] =
      AsAttribute(Attribute.DoubleAttribute(_, _))
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
