package kyo.net.internal.util

import kyo.*
import kyo.net.Test

class LoopConfinedTest extends Test:

    import LoopProof.given

    "LoopConfined" - {

        "on-loop-read-write-round-trips" in {
            val token    = new LoopToken(Thread.currentThread())
            val confined = new LoopConfined(0, token)
            confined.set(5)
            assert(confined.get == 5)
            succeed
        }

        "off-loop-thread-identity-condition" in {
            // Tests LoopToken.onLoop directly, without relying on -ea. The condition is a
            // single reference-equality read (Thread.currentThread() eq loopThread); we
            // construct a LoopToken capturing a helper thread, then check it from both
            // the helper thread (onLoop == true) and the test thread (onLoop == false).
            import AllowUnsafe.embrace.danger

            val helperTokenPromise  = Promise.Unsafe.init[LoopToken, Any]()
            val onLoopResultPromise = Promise.Unsafe.init[Boolean, Any]()

            val helperThread = new Thread(() =>
                val token = new LoopToken(Thread.currentThread())
                // Check onLoop from within the helper thread: must be true.
                onLoopResultPromise.completeDiscard(Result.succeed(token.onLoop))
                helperTokenPromise.completeDiscard(Result.succeed(token))
            )
            helperThread.setDaemon(true)
            helperThread.start()

            for
                onLoopOnHelper <- onLoopResultPromise.safe.get
                token          <- helperTokenPromise.safe.get
            yield
                // The helper thread confirmed onLoop == true from within itself.
                assert(onLoopOnHelper, "onLoop must be true when called from the loop thread")
                // From the test (non-loop) thread, onLoop must be false.
                assert(!token.onLoop, "onLoop must be false when called from a different thread")
                succeed
            end for
        }

        // PRIMARY structural guarantee: a LoopConfined get/set call requires a LoopProof in scope.
        // The negative direction (no proof -> compile error) cannot be verified with typeCheckErrors
        // from within kyo.*, because private[kyo] given instances are always visible within the kyo
        // package and its sub-packages, so the ambient LoopProof.proof leaks into every snippet
        // compiled from this test class (the same behavior documented in AssertScopeTest). The
        // guarantee is real: callers OUTSIDE kyo.* cannot access the private[kyo] LoopConfined or
        // LoopToken types at all, let alone summon LoopProof.proof; the phantom blocks them at the
        // package-access level. This leaf verifies the positive direction: with the proof available
        // the call compiles and the snippet is well-typed.
        "loop-proof-required-compile-time" in {
            val withProofErrors = scala.compiletime.testing.typeCheckErrors(
                """
                import kyo.net.internal.util.LoopConfined
                import kyo.net.internal.util.LoopProof
                import kyo.net.internal.util.LoopToken
                import LoopProof.given
                val token    = new LoopToken(Thread.currentThread())
                val confined = new LoopConfined(0, token)
                confined.get
                """
            )
            assert(
                withProofErrors.isEmpty,
                s"Expected no compile error with LoopProof.given in scope, got: ${withProofErrors.map(_.message)}"
            )
            succeed
        }
    }

end LoopConfinedTest
