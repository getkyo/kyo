package kyo.net

import kyo.*
import kyo.net.NetConnectionClosedException.Operation

/** [[NetException]]'s re-rooted hierarchy: disjoint from [[Closed]], organized into three recoverable subcategories, and rendering each
  * leaf's structured fields into its message exactly once.
  */
class NetExceptionTest extends Test:

    "disjointness: NetException is not a Closed, and Closed is final" in {
        val netEx: NetException = NetConnectException("h", 1)
        val closed: Closed      = Closed("r", summon[Frame], "")

        // Erased to Any first: the receiver's static type would otherwise let the compiler prove the disjoint type test always
        // false and reject it under -Werror, but the point of this assertion is exactly to observe that runtime fact.
        assert(!(netEx: Any).isInstanceOf[Closed], "a NetException must not be a Closed")
        assert(!(closed: Any).isInstanceOf[NetException], "a Closed must not be a NetException")

        typeCheckFailure("""
            final class ProbeClosed(using kyo.Frame) extends
                kyo.Closed("resource", summon[kyo.Frame], "")
        """)
    }

    "subcategory family recovery" in {
        val connect: NetException   = NetConnectException("h", 1)
        val handshake: NetException = NetTlsHandshakeException("h", 1)
        val backend: NetException   = NetBackendUnavailableException(Absent)

        assert(connect.isInstanceOf[NetConnectionException])
        assert(!connect.isInstanceOf[NetTlsException])
        assert(!connect.isInstanceOf[NetCapabilityException])

        assert(handshake.isInstanceOf[NetTlsException])
        assert(!handshake.isInstanceOf[NetConnectionException])
        assert(!handshake.isInstanceOf[NetCapabilityException])

        assert(backend.isInstanceOf[NetCapabilityException])
        assert(!backend.isInstanceOf[NetConnectionException])
        assert(!backend.isInstanceOf[NetTlsException])

        assert(connect.isInstanceOf[NetException])
        assert(handshake.isInstanceOf[NetException])
        assert(backend.isInstanceOf[NetException])
    }

    "timeout leaf stays distinct from connect leaf" in {
        // Ascribed to the shared NetException supertype: both leaves are final and otherwise sibling-disjoint, so a check against their
        // own precise inferred types would be a statically provable always-false test the compiler rejects under -Werror.
        val timeout: NetException = NetConnectTimeoutException("h", 5432, 3.seconds)
        val connect: NetException = NetConnectException("h", 5432)

        assert(timeout.isInstanceOf[NetConnectTimeoutException])
        assert(!timeout.isInstanceOf[NetConnectException])
        assert(connect.isInstanceOf[NetConnectException])
        assert(!connect.isInstanceOf[NetConnectTimeoutException])
    }

    "stdio leaves stay concrete" in {
        // Ascribed to the shared NetCapabilityException supertype for the same reason as the timeout/connect check above.
        val alreadyOpen: NetCapabilityException = NetStdioAlreadyOpenException()
        val unsupported: NetCapabilityException = NetStdioUnsupportedException()

        assert(alreadyOpen.isInstanceOf[NetStdioAlreadyOpenException])
        assert(!alreadyOpen.isInstanceOf[NetStdioUnsupportedException])
        assert(unsupported.isInstanceOf[NetStdioUnsupportedException])
        assert(!unsupported.isInstanceOf[NetStdioAlreadyOpenException])

        assert(alreadyOpen.isInstanceOf[NetCapabilityException])
        assert(unsupported.isInstanceOf[NetCapabilityException])
    }

    "NetConnectionClosedException carries a typed operation (handshake vs upgrade)" in {
        val handshake = NetConnectionClosedException(Operation.Handshake)
        val upgrade   = NetConnectionClosedException(Operation.Upgrade)

        assert(handshake.operation == Operation.Handshake)
        assert(upgrade.operation == Operation.Upgrade)
        assert(
            handshake.getMessage.contains("transport closed during handshake"),
            s"message must render the handshake operation, got ${handshake.getMessage}"
        )
        assert(
            upgrade.getMessage.contains("transport closed during upgrade"),
            s"message must render the upgrade operation, got ${upgrade.getMessage}"
        )
    }

    "NetConnectionClosedException carries the start and close operations the transports also construct" in {
        val start = NetConnectionClosedException(Operation.Start)
        val close = NetConnectionClosedException(Operation.Close)

        assert(start.operation == Operation.Start)
        assert(close.operation == Operation.Close)
        assert(
            start.getMessage.contains("transport closed during start"),
            s"message must render the start operation, got ${start.getMessage}"
        )
        assert(
            close.getMessage.contains("transport closed during close"),
            s"message must render the close operation, got ${close.getMessage}"
        )
    }

    "NetBackendUnavailableException folds two cases into one typed field" in {
        val named = NetBackendUnavailableException(Present("io_uring"))
        val none  = NetBackendUnavailableException(Absent)

        assert(named.backend == Present("io_uring"))
        assert(none.backend == Absent)
        assert(
            named.getMessage.contains("I/O backend 'io_uring' is unavailable"),
            s"message must name the backend, got ${named.getMessage}"
        )
        assert(
            none.getMessage.contains("no I/O backend is available"),
            s"message must state no backend is available, got ${none.getMessage}"
        )
    }

    "every leaf renders its cause suffix once" in {
        val config   = NetTlsConfigException(new java.io.IOException("bad pem"))
        val provider = NetTlsProviderUnavailableException("boringssl")

        assert(
            config.getMessage.contains(": bad pem"),
            s"message must end with the cause suffix, got ${config.getMessage}"
        )
        assert(
            provider.getMessage.contains("TLS provider 'boringssl' is not available"),
            s"message must name the provider, got ${provider.getMessage}"
        )
        assert(
            !provider.getMessage.contains("is not available: "),
            s"a leaf with no cause must not render a trailing cause suffix, got ${provider.getMessage}"
        )
    }

    "getCause forwards the constructor cause for every cause-carrying leaf" in {
        val cause = new java.io.IOException("boom")

        assert(
            NetConnectException("h", 1, cause).getCause == cause,
            "NetConnectException.getCause must return the constructor cause"
        )
        assert(
            NetDnsResolutionException("h", cause).getCause == cause,
            "NetDnsResolutionException.getCause must return the constructor cause"
        )
        assert(
            NetUnixConnectException("/tmp/sock", cause).getCause == cause,
            "NetUnixConnectException.getCause must return the constructor cause"
        )
        assert(
            NetBindException("h", 1, cause).getCause == cause,
            "NetBindException.getCause must return the constructor cause"
        )
        assert(
            NetConnectionClosedException(Operation.Read, cause).getCause == cause,
            "NetConnectionClosedException.getCause must return the constructor cause"
        )
        assert(
            NetTlsHandshakeException("h", 1, cause).getCause == cause,
            "NetTlsHandshakeException.getCause must return the constructor cause"
        )
        assert(
            NetTlsProviderUnavailableException("boringssl", cause).getCause == cause,
            "NetTlsProviderUnavailableException.getCause must return the constructor cause"
        )
        assert(
            NetTlsConfigException(cause).getCause == cause,
            "NetTlsConfigException.getCause must return the constructor cause"
        )
        assert(
            NetBackendUnavailableException(Present("io_uring"), cause).getCause == cause,
            "NetBackendUnavailableException.getCause must return the constructor cause"
        )
    }

    "a Closed handler does not catch a NetException (the intended correction)" in {
        val widened: Unit < Abort[NetException | Closed]                   = Abort.fail(NetConnectException("h", 1))
        val afterClosedHandler: Result[Closed, Unit] < Abort[NetException] = Abort.run[Closed](widened)
        Abort.run[NetException](afterClosedHandler).map { outer =>
            val ok = outer match
                case Result.Failure(_: NetConnectException) => true
                case _                                      => false
            assert(ok, s"Abort.run[Closed] must not catch a NetException, got $outer")
        }
    }

end NetExceptionTest
