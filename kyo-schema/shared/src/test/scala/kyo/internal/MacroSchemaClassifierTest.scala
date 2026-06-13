package kyo.internal

import kyo.*

/** Tests for [[MacroUtils.MacroSchemaClassifier]].
  *
  * MacroSchemaClassifier.fieldKindFor runs inside a macro context (requires `using Quotes`).
  * The helper inline macro `MacroSchemaClassifierBridge.classifyField[T]` bridges compile-time
  * classification to a runtime string, enabling ordinary assertions in the test suite.
  */
class MacroSchemaClassifierTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // Helper type for ProductOrSum classification
    case class ClassifierTestCase(x: Int) derives CanEqual

    "MacroSchemaClassifier" - {

        "Int is classified as Primitive(Int)" in {
            val result = MacroSchemaClassifierBridge.classifyField[Int]
            assert(result == "Primitive(Int)")
        }

        "String is classified as Primitive(String)" in {
            val result = MacroSchemaClassifierBridge.classifyField[String]
            assert(result == "Primitive(String)")
        }

        "List[Int] is classified as Collection" in {
            val result = MacroSchemaClassifierBridge.classifyField[List[Int]]
            assert(result == "Collection")
        }

        "Option[Int] is classified as Optional" in {
            val result = MacroSchemaClassifierBridge.classifyField[Option[Int]]
            assert(result == "Optional")
        }

        "Map[String, Int] is classified as Mapping" in {
            val result = MacroSchemaClassifierBridge.classifyField[Map[String, Int]]
            assert(result == "Mapping")
        }

        "case class is classified as ProductOrSum" in {
            val result = MacroSchemaClassifierBridge.classifyField[ClassifierTestCase]
            assert(result == "ProductOrSum")
        }

    }

end MacroSchemaClassifierTest
