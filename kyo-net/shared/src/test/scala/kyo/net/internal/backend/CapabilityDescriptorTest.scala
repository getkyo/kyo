package kyo.net.internal.backend

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.net.Test

/** The two guarantees the shared identity makes to every registry that reads it: a probe runs at most once per candidate, and it never
  * throws at the caller however badly the probe body fails.
  */
class CapabilityDescriptorTest extends Test:

    import AllowUnsafe.embrace.danger

    private def descriptor(body: AllowUnsafe ?=> CapabilityOutcome): CapabilityDescriptor =
        new CapabilityDescriptor:
            def name: String                                               = "stub"
            def priority: Int                                              = 10
            def libraryIds: Chunk[String]                                  = Chunk.empty
            private[net] def doProbe(using AllowUnsafe): CapabilityOutcome = body

    "the probe body runs once and every later call reads the memo" in {
        val runs = new AtomicInteger(0)
        val subject = descriptor {
            discard(runs.getAndIncrement())
            CapabilityOutcome.Available
        }
        assert(subject.probe == CapabilityOutcome.Available)
        assert(subject.probe == CapabilityOutcome.Available)
        assert(subject.probe == CapabilityOutcome.Available)
        assert(runs.get() == 1, s"expected one probe body run, got ${runs.get()}")
    }

    "a failing probe body is memoized too, so a broken dependency is not re-attempted per question" in {
        val runs = new AtomicInteger(0)
        val subject = descriptor {
            discard(runs.getAndIncrement())
            CapabilityOutcome.NotBundled("kyonet_posix_uring", "linux-x86_64")
        }
        assert(subject.probe == CapabilityOutcome.NotBundled("kyonet_posix_uring", "linux-x86_64"))
        assert(subject.probe == CapabilityOutcome.NotBundled("kyonet_posix_uring", "linux-x86_64"))
        assert(runs.get() == 1, s"expected one probe body run, got ${runs.get()}")
    }

    "a probe body that throws is reported as ProbeFailed carrying the throwable, never raised at the caller" in {
        val boom    = new RuntimeException("probe blew up")
        val subject = descriptor(throw boom)
        subject.probe match
            case CapabilityOutcome.ProbeFailed(cause) => assert(cause eq boom, s"the raised throwable must be carried, got $cause")
            case other                                => fail(s"expected ProbeFailed, got $other")
    }

end CapabilityDescriptorTest
