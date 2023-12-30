package kyoTest.stats.otel

import kyoTest._
import kyo.stats.otel._
import kyo.stats._
import scala.jdk.CollectionConverters._
import io.opentelemetry.api.common.AttributeKey

class OTelAttributesTest extends KyoTest {

  "test" in {
    val kyoAttrs = Attributes.all(List(
        Attributes.add("boolAttr", true),
        Attributes.add("doubleAttr", 2.0),
        Attributes.add("intAttr", 42),
        Attributes.add("longAttr", 123L),
        Attributes.add("stringAttr", "test"),
        Attributes.add("boolListAttr", List(true, false)),
        Attributes.add("doubleListAttr", List(1.1, 2.2)),
        Attributes.add("longListAttr", List(100L, 200L)),
        Attributes.add("stringListAttr", List("a", "b"))
    ))

    val oTelAttrs = OTelAttributes(kyoAttrs)

    assert(oTelAttrs.get(AttributeKey.booleanKey("boolAttr")).booleanValue())
    assert(oTelAttrs.get(AttributeKey.doubleKey("doubleAttr")).doubleValue() == 2.0)
    assert(oTelAttrs.get(AttributeKey.longKey("intAttr")).longValue() == 42)
    assert(oTelAttrs.get(AttributeKey.longKey("longAttr")).longValue() == 123L)
    assert(oTelAttrs.get(AttributeKey.stringKey("stringAttr")) == "test")

    assert(oTelAttrs.get(AttributeKey.booleanArrayKey("boolListAttr")).asScala.toList ==
      List(true, false))
    assert(oTelAttrs.get(AttributeKey.doubleArrayKey("doubleListAttr")).asScala.toList ==
      List(1.1, 2.2))
    assert(oTelAttrs.get(AttributeKey.longArrayKey("longListAttr")).asScala.toList ==
      List(100L, 200L))
    assert(oTelAttrs.get(AttributeKey.stringArrayKey("stringListAttr")).asScala.toList ==
      List("a", "b"))
  }
}
