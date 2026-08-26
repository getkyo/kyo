package kyo.kernel.internal

import kyo.kernel.*

/** `Safepoint.stop` against a thread that was constructed but never started, and so
  * never claimed a slot. Only expressible where a second, distinct thread handle can
  * exist: JS and Wasm are single-threaded by construction, so this case moves here from
  * the shared `SafepointTest`, JVM and Native only.
  */
class SafepointUnstartedThreadTest extends kyo.Test:

    "stop misses a thread that never evaluated" in {
        assert(!Safepoint.stop(new Thread()))
    }

end SafepointUnstartedThreadTest
