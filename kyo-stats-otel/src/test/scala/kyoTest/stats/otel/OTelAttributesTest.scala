package kyoTest.stats.otel

import kyoTest._
import kyo.stats.otel._
import kyo.stats._
import scala.jdk.CollectionConverters._
import io.opentelemetry.api.common.AttributeKey

class OTelAttributesTest extends KyoTest {

  "test" in {
    val kyoAttrs = Attributes.all(List(
        Attributes.of("boolAttr", true),
        Attributes.of("doubleAttr", 2.0),
        Attributes.of("intAttr", 42),
        Attributes.of("longAttr", 123L),
        Attributes.of("stringAttr", "test"),
        Attributes.of("boolListAttr", List(true, false)),
        Attributes.of("doubleListAttr", List(1.1, 2.2)),
        Attributes.of("longListAttr", List(100L, 200L)),
        Attributes.of("stringListAttr", List("a", "b"))
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
