package kyo.net.internal.tls

import kyo.*
import kyo.ffi.Buffer
import kyo.internal.Diagnostics
import kyo.net.Test

/** [[NativeSslEngine]]'s freed-gate contract: once [[TlsEngine.free]] has run, every other op refuses the native call and reports a named
  * [[Diagnostics]] violation instead of dereferencing the already-released `ssl` pointer.
  *
  * This is the regression guard for a native use-after-free class root-caused via two `hs_err` crash dumps (a test-construction bug freed a
  * driver-attached engine while the driver's own queued teardown still owed it an `SSL_shutdown`; see `PollerIoDriverTlsHalfCloseEtTest`'s
  * `awaitCondition` fix and `TlsRealEngines.withEngines`'s ownership-rule doc for the actual hazard this closes off). Before this gate, that
  * class of bug was silent 1-in-N native corruption (an unmapped-page SIGSEGV or a wild write into a live allocation, surfacing later at an
  * unrelated leaf); after it, ANY post-free call is an attributed, in-process failure naming the exact op, with no native call at all.
  */
class NativeSslEngineTest extends Test:

    import AllowUnsafe.embrace.danger

    private val gatedOps = List(
        "handshakeStep",
        "feedCiphertext",
        "drainCiphertext",
        "readPlain",
        "writePlain",
        "hasBufferedPlaintext",
        "readBuffered",
        "certSha256",
        "shutdownStep"
    )

    "NativeSslEngine freed-gate hardening" - {
        "every op after free() returns its documented sentinel and reports a violation naming the op, without touching the native session" in {
            TlsRealEngines.assumeTlsReady()
            val engine = TlsRealEngines.singleEngine(isServer = true)
            engine.free()
            // Flush whatever the process-global violation list already held (a genuinely unrelated component's own report) so this leaf's own
            // drain below sees ONLY the 9 violations the calls below produce; restored at the end so kyo-test's authoritative end-of-run
            // TeardownViolationCheck still sees anything that was pending before this leaf ran.
            val preExisting = Diagnostics.drainViolations()
            val buf         = Buffer.alloc[Byte](1)
            try
                assert(engine.handshakeStep() == -2, "handshakeStep after free() must return -2 (fatal), not call the native shim")
                assert(engine.feedCiphertext(buf, 1) == -1, "feedCiphertext after free() must return -1 (error)")
                assert(engine.drainCiphertext(buf, 1) == 0, "drainCiphertext after free() must return 0 (nothing pending)")
                assert(engine.readPlain(buf, 1) == 0, "readPlain after free() must return 0 (want-read / clean-close sentinel)")
                assert(engine.writePlain(buf, 1) == 0, "writePlain after free() must return 0 (want-read sentinel)")
                assert(!engine.hasBufferedPlaintext, "hasBufferedPlaintext after free() must return false")
                assert(engine.readBuffered().isEmpty, "readBuffered after free() must return an empty span")
                assert(engine.certSha256() == Absent, "certSha256 after free() must return Absent")
                assert(engine.shutdownStep() == -2, "shutdownStep after free() must return -2 (fatal), not call the native shim")
            finally buf.close()
            end try
            val reported = Diagnostics.drainViolations()
            preExisting.foreach(Diagnostics.reportViolation)
            assert(
                reported.size == gatedOps.size,
                s"expected exactly ${gatedOps.size} violations, one per gated op, got: $reported"
            )
            gatedOps.foreach { op =>
                assert(
                    reported.exists(_.contains(s"NativeSslEngine.$op")),
                    s"missing a violation naming $op, got: $reported"
                )
            }
            // free() itself is exempt: it is the CAS-guarded exactly-once chokepoint (idempotent by design, mirroring PosixHandle.guard /
            // Buffer.close), never a misuse, so a second call must report nothing.
            engine.free()
            assert(Diagnostics.drainViolations().isEmpty, "a second free() call must not report a violation (it is idempotent by design)")
        }
    }

end NativeSslEngineTest
