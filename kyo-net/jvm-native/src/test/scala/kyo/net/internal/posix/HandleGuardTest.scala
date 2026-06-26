package kyo.net.internal.posix

import kyo.*
import kyo.net.Test

class HandleGuardTest extends Test:

    import AllowUnsafe.embrace.danger

    "HandleGuard" - {

        "read-and-write-acquire-concurrently" in {
            val guard    = HandleGuard.init()
            val gotRead  = guard.acquireRead()
            val gotWrite = guard.acquireWrite()
            assert(gotRead)
            assert(gotWrite)
            succeed
        }

        "close-deferred-until-last-holder-releases" in {
            val guard = HandleGuard.init()
            assert(guard.acquireRead())
            assert(guard.acquireWrite())
            // requestClose while two holders are in flight: deferred, returns false
            assert(!guard.requestClose())
            // release the read holder: write holder still active, returns false
            assert(!guard.release(read = true))
            // release the write holder: now the last holder while closing, returns true
            assert(guard.release(read = false))
            succeed
        }

        "acquire-after-close-fails" in {
            val guard = HandleGuard.init()
            // requestClose on an idle guard (no holders): returns true (release now)
            assert(guard.requestClose())
            // subsequent acquires must both fail
            assert(!guard.acquireRead())
            assert(!guard.acquireWrite())
            succeed
        }
    }

end HandleGuardTest
