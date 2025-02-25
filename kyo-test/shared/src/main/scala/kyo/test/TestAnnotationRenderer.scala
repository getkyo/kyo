package kyo.test

import kyo.*
import kyo.test.TestAnnotationRenderer.*

/** A `TestAnnotationRenderer` knows how to render test annotations.
  */
sealed abstract class TestAnnotationRenderer:
    self =>

    def run(ancestors: List[TestAnnotationMap], child: TestAnnotationMap): List[String]

    /** A symbolic alias for `combine`.
      */
    final def <>(that: TestAnnotationRenderer): TestAnnotationRenderer =
        self.combine(that)

    /** Combines this test annotation renderer with the specified test annotation renderer to produce a new test annotation renderer that
      * renders both sets of test annotations.
      */
    final def combine(that: TestAnnotationRenderer): TestAnnotationRenderer =
        (self, that) match
            case (CompositeRenderer(left), CompositeRenderer(right)) => CompositeRenderer(left ++ right)
            case (CompositeRenderer(left), leaf)                     => CompositeRenderer(left ++ Vector(leaf))
            case (leaf, CompositeRenderer(right))                    => CompositeRenderer(Vector(leaf) ++ right)
            case (left, right)                                       => CompositeRenderer(Vector(left, right))
end TestAnnotationRenderer

object TestAnnotationRenderer:

    /** A test annotation renderer that renders a single test annotation.
      */
    sealed abstract class LeafRenderer extends TestAnnotationRenderer

    object LeafRenderer:
        def apply[V](annotation: TestAnnotation[V])(render: ::[V] => Option[String]): TestAnnotationRenderer =
            new LeafRenderer:
                def run(ancestors: List[TestAnnotationMap], child: TestAnnotationMap): List[String] =
                    render(::(child.get(annotation), ancestors.map(_.get(annotation)))).toList
    end LeafRenderer

    /** A test annotation renderer that combines multiple other test annotation renderers.
      */
    final case class CompositeRenderer(renderers: Vector[TestAnnotationRenderer]) extends TestAnnotationRenderer:
        def run(ancestors: List[TestAnnotationMap], child: TestAnnotationMap): List[String] =
            renderers.toList.flatMap(_.run(ancestors, child))

    /** The default test annotation renderer used by the `DefaultTestReporter`.
      */
    lazy val default: TestAnnotationRenderer =
        CompositeRenderer(Vector(ignored, repeated, retried, tagged, timed))

    /** A test annotation renderer that renders the number of ignored tests.
      */
    val ignored: TestAnnotationRenderer =
        LeafRenderer(TestAnnotation.ignored) { case (child :: _) =>
            if child == 0 then None
            else Some(s"ignored: $child")
        }

    /** A test annotation renderer that renders how many times a test was repeated.
      */
    val repeated: TestAnnotationRenderer =
        LeafRenderer(TestAnnotation.repeated) { case (child :: _) =>
            if child == 0 then None
            else Some(s"repeated: $child")
        }

    /** A test annotation renderer that renders how many times a test had to be retried before it succeeded.
      */
    val retried: TestAnnotationRenderer =
        LeafRenderer(TestAnnotation.retried) { case (child :: _) =>
            if child == 0 then None
            else Some(s"retried: $child")
        }

    /** A test annotation renderer that renders string tags.
      */
    val tagged: TestAnnotationRenderer =
        LeafRenderer(TestAnnotation.tagged) { case (child :: _) =>
            if child.isEmpty then None
            else Some(s"tagged: ${child.map("\"" + _ + "\"").mkString(", ")}")
        }

    /** A test annotation renderer that does not render any test annotations.
      */
    val silent: TestAnnotationRenderer =
        new TestAnnotationRenderer:
            def run(ancestors: List[TestAnnotationMap], child: TestAnnotationMap): List[String] =
                List.empty

    /** A test annotation renderer that renders the time taken to execute each test or suite both in absolute duration and as a percentage
      * of total execution time.
      */
    val timed: TestAnnotationRenderer =
        LeafRenderer(TestAnnotation.timing) { case (child :: _) =>
            if child == Duration.Zero then None
            else Some(f"${child.toString()}") // TODO: Used to be TestDuration and render
        }
end TestAnnotationRenderer
