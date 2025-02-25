/*
 * Converted from zio-test's TestSuccess.scala to use the Kyo effect system.
 * This conversion defines a basic TestSuccess abstraction with a message and a combine helper method.
 */

package kyo.test

sealed abstract class TestSuccess:
    self =>

    /** Retrieves the annotations associated with this test success.
      */
    def annotations: TestAnnotationMap

    /** Annotates this test success with the specified test annotations.
      */
    def annotated(annotations: TestAnnotationMap): TestSuccess =
        self match
            case TestSuccess.Succeeded(_) => TestSuccess.Succeeded(self.annotations ++ annotations)
            case TestSuccess.Ignored(_)   => TestSuccess.Ignored(self.annotations ++ annotations)
end TestSuccess

object TestSuccess:
    final case class Succeeded(annotations: TestAnnotationMap = TestAnnotationMap.empty) extends TestSuccess
    final case class Ignored(annotations: TestAnnotationMap = TestAnnotationMap.empty)   extends TestSuccess
