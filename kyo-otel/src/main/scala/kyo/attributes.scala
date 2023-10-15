package kyo

import io.opentelemetry.api.common.{Attributes => OAttributes, AttributeKey}
import scala.jdk.CollectionConverters._

object attributes {

  class Attributes private[attributes] (private[kyo] val o: OAttributes) extends AnyVal {

    def add(a: Attributes) =
      Attributes(OAttributes.builder().putAll(o).putAll(a.o).build())

    def add[T](name: String, v: T)(implicit a: AsAttribute[T]): Attributes =
      add(Attributes(a.f(name, v)))
  }

  object Attributes {

    def empty: Attributes =
      new Attributes(OAttributes.empty())

    def of[T](name: String, v: T)(implicit a: AsAttribute[T]): Attributes =
      new Attributes(a.f(name, v))
  }

  class AsAttribute[T](private[attributes] val f: (String, T) => OAttributes)

  object AsAttribute {
    implicit val booleanList: AsAttribute[List[Boolean]] =
      new AsAttribute((name, value) =>
        OAttributes.of(
            AttributeKey.booleanArrayKey(name),
            value.asJava.asInstanceOf[java.util.List[java.lang.Boolean]]
        )
      )
    implicit val boolean: AsAttribute[Boolean] =
      new AsAttribute((name, value) =>
        OAttributes.of(
            AttributeKey.booleanKey(name),
            value
        )
      )
    implicit val doubleList: AsAttribute[List[Double]] =
      new AsAttribute((name, value) =>
        OAttributes.of(
            AttributeKey.doubleArrayKey(name),
            value.asJava.asInstanceOf[java.util.List[java.lang.Double]]
        )
      )
    implicit val double: AsAttribute[Double] =
      new AsAttribute((name, value) =>
        OAttributes.of(
            AttributeKey.doubleKey(name),
            value
        )
      )
    implicit val longList: AsAttribute[List[Long]] =
      new AsAttribute((name, value) =>
        OAttributes.of(
            AttributeKey.longArrayKey(name),
            value.asJava.asInstanceOf[java.util.List[java.lang.Long]]
        )
      )
    implicit val long: AsAttribute[Long] =
      new AsAttribute((name, value) =>
        OAttributes.of(
            AttributeKey.longKey(name),
            value
        )
      )
    implicit val stringList: AsAttribute[List[String]] =
      new AsAttribute((name, value) =>
        OAttributes.of(
            AttributeKey.stringArrayKey(name),
            value.asJava
        )
      )
    implicit val string: AsAttribute[String] =
      new AsAttribute((name, value) =>
        OAttributes.of(
            AttributeKey.stringKey(name),
            value
        )
      )
  }
}
