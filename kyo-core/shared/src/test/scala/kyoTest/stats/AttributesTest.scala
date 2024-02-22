package kyoTest.stats

import kyo.*
import kyo.stats.*
import kyo.stats.Attributes.*
import kyoTest.KyoTest

class AttributesTest extends KyoTest:

    "empty" in {
        val attrs = Attributes.empty
        assert(attrs.get.isEmpty)
    }

    "one" in {
        val attr = Attributes.add("test", true)
        assert(attr.get.size == 1)
        assert(attr.get.head.isInstanceOf[Attribute.BooleanAttribute])
    }

    "add" in {
        val attr1    = Attributes.add("test1", true)
        val attr2    = Attributes.add("test2", 123)
        val combined = attr1.add(attr2)
        assert(combined.get.size == 2)
    }

    "all" in {
        val attrList = List(Attributes.add("test1", true), Attributes.add("test2", 123.45))
        val combined = Attributes.all(attrList)
        assert(combined.get.size == attrList.size)
    }

    "primitives" - {

        "boolean" in {
            val booleanAttr = Attributes.add("bool", true)
            assert(booleanAttr.get.head.isInstanceOf[Attribute.BooleanAttribute])
        }

        "int" in {
            val booleanAttr = Attributes.add("int", 1)
            assert(booleanAttr.get.head.isInstanceOf[Attribute.LongAttribute])
        }

        "double" in {
            val doubleAttr = Attributes.add("double", 123.45)
            assert(doubleAttr.get.head.isInstanceOf[Attribute.DoubleAttribute])
        }

        "long" in {
            val longAttr = Attributes.add("long", 123L)
            assert(longAttr.get.head.isInstanceOf[Attribute.LongAttribute])
        }

        "string" in {
            val stringAttr = Attributes.add("string", "value")
            assert(stringAttr.get.head.isInstanceOf[Attribute.StringAttribute])
        }
    }

    "lists" - {
        "boolean list" in {
            val boolListAttr = Attributes.add("boolList", List(true, false, true))
            assert(boolListAttr.get.head.isInstanceOf[Attribute.BooleanListAttribute])
            assert(
                boolListAttr.get.head.asInstanceOf[Attribute.BooleanListAttribute].value.size == 3
            )
        }

        "integer list" in {
            val intListAttr = Attributes.add("intList", List(1, 2, 3))
            assert(intListAttr.get.head.isInstanceOf[Attribute.LongListAttribute])
            assert(intListAttr.get.head.asInstanceOf[Attribute.LongListAttribute].value.size == 3)
        }

        "double list" in {
            val doubleListAttr = Attributes.add("doubleList", List(1.1, 2.2, 3.3))
            assert(doubleListAttr.get.head.isInstanceOf[Attribute.DoubleListAttribute])
            assert(
                doubleListAttr.get.head.asInstanceOf[Attribute.DoubleListAttribute].value.size == 3
            )
        }

        "long list" in {
            val longListAttr = Attributes.add("longList", List(100L, 200L, 300L))
            assert(longListAttr.get.head.isInstanceOf[Attribute.LongListAttribute])
            assert(longListAttr.get.head.asInstanceOf[Attribute.LongListAttribute].value.size == 3)
        }

        "string list" in {
            val stringListAttr = Attributes.add("stringList", List("a", "b", "c"))
            assert(stringListAttr.get.head.isInstanceOf[Attribute.StringListAttribute])
            assert(
                stringListAttr.get.head.asInstanceOf[Attribute.StringListAttribute].value.size == 3
            )
        }

    }
end AttributesTest
