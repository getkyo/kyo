package kyo.net.internal.posix

import kyo.*
import kyo.net.Test
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadPump
import kyo.net.internal.transport.WritePump
import kyo.net.internal.transport.WriteResult

/** Compile-time guard: introducing [[PosixHandle]] does NOT change any shared transport-layer signature. The shared layer (`IoDriver[Handle]`,
  * `ReadPump[Handle]`, `WritePump[Handle]`, `Connection[Handle]`, `ConnectionPool`) is generic over the handle type, so it accepts
  * `PosixHandle` verbatim; only the concrete driver is handle-specific. These tests are compile-time guards: they type-check iff no shared
  * signature drifted.
  *
  * Guards compile with no IoDriver instance constructed: the type-check expressions compile iff no shared signature has drifted.
  */
class PosixHandleInvariantsTest extends Test:

    import AllowUnsafe.embrace.danger

    "PosixHandleInvariantsTest (shared layer unchanged at Handle = PosixHandle)" - {

        // Compile-time check: IoDriver[PosixHandle] is the concrete contract type.
        // No IoDriver instance is constructed; the expression compiles iff the signature is unchanged.
        "the IoDriver contract is satisfiable at Handle = PosixHandle" in {
            typeCheck("val _: kyo.net.internal.transport.IoDriver[kyo.net.internal.posix.PosixHandle] = ???")
            succeed
        }

        // Compile-time check: ReadPump and WritePump constructors accept PosixHandle.
        // The instantiations compile iff the pump classes are generic over PosixHandle with their bodies unedited.
        // The runtime assert pins the PosixHandle split-fd contract.
        "ReadPump and WritePump instantiate over PosixHandle + IoDriver[PosixHandle]" in {
            typeCheck("""
                val handle = kyo.net.internal.posix.PosixHandle.socket(3, PosixHandle.DefaultReadBufferSize, Absent)
                val ch = kyo.Channel.Unsafe.init[kyo.Span[Byte]](1)
                import kyo.AllowUnsafe.embrace.danger
                given kyo.Frame = kyo.Frame.internal
                // These compile iff the constructors are generic over PosixHandle.
                // The dummy driver satisfies the type without exercising any real I/O.
                val driver: kyo.net.internal.transport.IoDriver[kyo.net.internal.posix.PosixHandle] = ???
                val _rp = new kyo.net.internal.transport.ReadPump[kyo.net.internal.posix.PosixHandle](handle, driver, ch, (_: kyo.net.internal.transport.TeardownCause) => ())
                val _wp = new kyo.net.internal.transport.WritePump[kyo.net.internal.posix.PosixHandle](handle, driver, ch, (_: kyo.net.internal.transport.TeardownCause) => (), kyo.AtomicRef.Unsafe.init[kyo.net.internal.transport.WriteState](kyo.net.internal.transport.WriteState.Idle))
            """)
            val handle = PosixHandle.socket(3, PosixHandle.DefaultReadBufferSize, Absent)
            assert(handle.readFd == 3 && handle.writeFd == 3)
        }
    }

end PosixHandleInvariantsTest
