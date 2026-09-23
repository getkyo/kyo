package kyo

import kyo.test.AllocationProbe

class SpanAllocationJvmTest extends kyo.test.Test[Any]:

    import SpanAllocationJvmTest.*

    "empty spans are served from the cache without allocating" - {
        "Span.empty of a reference type" in {
            assertNoAllocation(Span.empty[String])
        }

        "Span.empty of a primitive type" in {
            assertNoAllocation(Span.empty[Int])
        }

        "Span.empty of a generic type" in {
            assertNoAllocation(Span.empty[Maybe[Int]])
        }

        "an element type taken from the expected type" in {
            def empty(): Span[String] = Span.empty
            assertNoAllocation(empty())
        }

        "an empty slice" in {
            val strings = Span("a", "b")
            assertNoAllocation(strings.slice(1, 1))
        }

        "a drop past the end" in {
            val strings = Span("a", "b")
            assertNoAllocation(strings.drop(5))
        }

        "ShallowTag.newArray of length zero" in {
            assertNoAllocation(ShallowTag[String].newArray(0))
        }
    }

end SpanAllocationJvmTest

object SpanAllocationJvmTest:

    // Every result is stored, so escape analysis cannot scalar-replace an allocation this should detect.
    private val sink = new Array[AnyRef](1)

    inline def assertNoAllocation(inline body: Any)(using Frame, kyo.test.AssertScope): Unit =
        AllocationProbe.assertBoundedPerOp(warmupIters = 20000, measuredIters = 20000, maxBytesPerOp = 0.0) {
            sink(0) = body.asInstanceOf[AnyRef]
        }

end SpanAllocationJvmTest
