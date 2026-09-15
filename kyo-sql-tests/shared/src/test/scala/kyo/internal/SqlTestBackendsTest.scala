package kyo.internal

import kyo.*
import kyo.test.Test

/** The guard on the conformance battery itself: every engine this repository ships a descriptor for is actually discovered.
  *
  * Without it the battery has a silent failure mode that looks exactly like success. `forEachBackend` runs its body once per DISCOVERED
  * descriptor and fails only when none is found at all, so a run that discovered one engine executes every cross-engine leaf against that one
  * engine, agrees with itself, and reports green. Conformance is a claim about two engines answering alike; asserted against one, every leaf
  * in the battery is vacuous, and nothing in the output says so.
  *
  * Discovery goes through a service file in a test artifact, which is the kind of thing that survives a refactor by not being noticed. This
  * leaf turns "the battery quietly stopped comparing" into a named failure.
  *
  * Reads [[SqlTestBackends.registered]], not `available`: whether the descriptors were DISCOVERED is a classpath fact, and `available`
  * intersects that with whether a container runtime can start one. Reading `available` here fails on a host with no container runtime, which
  * is not what this asserts.
  */
class SqlTestBackendsTest extends Test:

    "every shipped backend descriptor is discovered" in {
        val found = SqlTestBackends.registered.map(_.id).toSet
        assert(
            found == Set("postgres", "mysql"),
            s"the conformance battery compares only what it discovers, and it discovered $found. " +
                "A missing descriptor does not fail any leaf, it makes every cross-engine leaf agree with itself."
        )
    }

    "each discovered descriptor has a distinct id" in {
        val ids = SqlTestBackends.registered.map(_.id)
        assert(
            ids.distinct.size == ids.size,
            s"two descriptors sharing an id would collapse onto one shared container and one leaf, got $ids"
        )
    }

end SqlTestBackendsTest
