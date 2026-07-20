package kyo.test

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kyo.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Self-tests for [[AllocationProbe]].
  *
  * The probe is the primitive every zero-allocation claim in the codebase rests on, so its own contract is
  * driven through a real leaf: a violation must fail the CALLING leaf through that leaf's `AssertScope`
  * (an `AssertionFailed`, not a `java.lang.AssertionError` from `Predef.assert`), a passing probe must
  * count as one evaluated assertion, and an op that genuinely allocates must never pass at a bound of 0.
  * The real HotSpot bean always reports its counter supported, so the unsupported-counter safety branch
  * is exercised through the injectable [[AllocationCounter]] test seam instead.
  *
  * ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (the runner depends on the api), so
  * the suite class is plain ScalaTest and the leaves under test are driven through [[LeafHarness]], the
  * convention every kyo-test-api self-test follows.
  */
class AllocationProbeTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    private val warmupIters   = 20000
    private val measuredIters = 2000

    // The probe checks the best of several independent post-warmup measurement windows (see
    // AllocationProbe's `trials`), so the op runs warmupIters + trials * measuredIters times total.
    private val trials = 5

    private val allocBean: ThreadMXBean =
        ManagementFactory.getThreadMXBean.asInstanceOf[ThreadMXBean]

    "a genuinely non-allocating op passes at maxBytesPerOp = 0" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "non-allocating" in {
                    // Sums into a preallocated primitive slot: no heap allocation, and not constant-foldable.
                    val acc = new Array[Long](1)
                    AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                        acc(0) = acc(0) + 1L
                    }
                    assert(acc(0) == (warmupIters + trials * measuredIters).toLong)
                }
        }.map {
            case _: TestResult.Passed => succeed
            case other                => fail(s"Expected Passed for a non-allocating op at bound 0, got $other")
        }
    }

    "an allocating op fails the calling leaf through its AssertScope, naming the measured per-op bytes" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "allocating" in {
                    AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                        // 1 KiB per call, on every call: the best-of-trials minimum cannot filter a
                        // systematic per-op allocation, since it is identical (and far above the bound) in
                        // every one of the trials, not just a spike confined to one of them.
                        val fresh = new Array[Byte](1024)
                        fresh(0) = 1
                        fresh
                    }
                }
        }.map {
            case f: TestResult.Failed =>
                // The diagram is the AssertionFailed's, which is what proves the probe reported through the
                // leaf's AssertScope. Predef.assert would have thrown a java.lang.AssertionError, whose
                // diagram is its toString and carries none of this text.
                assert(
                    f.diagram.contains("per-op allocation") && f.diagram.contains("exceeds the bound 0.0"),
                    s"expected the probe's per-op violation diagram, got: ${f.diagram}"
                )
                assert(
                    f.diagram.contains(s"measured over $measuredIters ops"),
                    s"expected the measured-op count in the diagram, got: ${f.diagram}"
                )
                succeed
            case other => fail(s"Expected Failed for a 1 KiB-per-op allocation at bound 0, got $other")
        }
    }

    "a spike confined to a single trial is filtered by the best-of-trials minimum" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "one-off-spike" in {
                    val calls = new Array[Long](1)
                    AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                        calls(0) += 1
                        // Allocates only during the first trial's measured window (right after warmup),
                        // simulating a one-off JIT background-compile spike; warmup and the remaining four
                        // trials are genuinely non-allocating. If the probe checked a single window, or the
                        // MAXIMUM across trials, this op would fail; the minimum across trials filters it.
                        if calls(0) > warmupIters && calls(0) <= warmupIters + measuredIters then
                            discard(new Array[Byte](1024))
                    }
                }
        }.map {
            case _: TestResult.Passed => succeed
            case other =>
                fail(s"Expected Passed: a spike confined to one trial must be filtered by the minimum, got $other")
        }
    }

    "a passing probe is the leaf's only assertion and still satisfies failOnNoAssertion" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "probe-only" in {
                    val acc = new Array[Long](1)
                    AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                        acc(0) = acc(0) + 1L
                    }
                }
        }.map {
            case _: TestResult.Passed => succeed
            case other                => fail(s"Expected Passed: the probe records one evaluation on entry, got $other")
        }
    }

    "a disabled allocation counter never yields a false pass: the probe enables it and still measures" in {
        // The counter is a process-global JVM switch. Disable it, then require that an op allocating 1 KiB
        // per call STILL fails at bound 0: a probe that read a disabled counter would measure 0 bytes per op
        // and pass, which is exactly the silent false pass this primitive must never produce.
        val wasEnabled = allocBean.isThreadAllocatedMemoryEnabled
        allocBean.setThreadAllocatedMemoryEnabled(false)
        val run =
            LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
                new kyo.test.Test[Any]:
                    "counter-disabled" in {
                        AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                            val fresh = new Array[Byte](1024)
                            fresh(0) = 1
                            fresh
                        }
                    }
            }
        run.map { result =>
            allocBean.setThreadAllocatedMemoryEnabled(wasEnabled)
            result match
                case f: TestResult.Failed =>
                    assert(
                        f.diagram.contains("per-op allocation"),
                        s"expected the probe to enable the counter and report the real allocation, got: ${f.diagram}"
                    )
                    assert(
                        allocBean.isThreadAllocatedMemorySupported,
                        "the probe requires a supported per-thread allocation counter"
                    )
                    succeed
                case other =>
                    fail(s"Expected Failed: a disabled counter must not turn a 1 KiB-per-op allocation into a pass, got $other")
            end match
        }
    }

    "an unsupported allocation counter fails loud rather than passing silently" in {
        // The real HotSpot bean always reports its counter supported, so this branch is unreachable
        // through the public overload; inject a counter that reports unsupported instead. A probe that
        // treated an unsupported counter as an implicit pass would be exactly the silent false pass this
        // primitive exists to prevent, even for a genuinely non-allocating op.
        val unsupported = new AllocationCounter:
            def isSupported: Boolean                = false
            def isEnabled: Boolean                  = false
            def enable(): Unit                      = ()
            def currentThreadAllocatedBytes(): Long = 0L
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "counter-unsupported" in {
                    val acc = new Array[Long](1)
                    AllocationProbe.assertBoundedPerOp(unsupported, warmupIters, measuredIters, 0.0) {
                        acc(0) = acc(0) + 1L
                    }
                }
        }.map {
            case f: TestResult.Failed =>
                assert(
                    f.diagram.contains("per-thread allocation measurement is unsupported"),
                    s"expected the probe's unsupported-counter violation diagram, got: ${f.diagram}"
                )
                succeed
            case other =>
                fail(s"Expected Failed: an unsupported counter must never pass, even for a non-allocating op, got $other")
        }
    }

end AllocationProbeTest
