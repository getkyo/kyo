package kyo.net

import kyo.*

/** `Closed` is final and `NetException` roots at [[kyo.KyoException]] as a SIBLING of [[Closed]], never a subtype (`verbatim-intent:23-27`):
  * `Abort[NetException]` is a distinct error channel from `Abort[Closed]`. The cause is carried STRUCTURALLY (forwarded to `KyoException`),
  * so `getCause()` returns the passed value instead of null on every leaf.
  */
class NetExceptionTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "Closed is final: `class X extends Closed` fails to compile" in {
        // typeCheckErrors compiles the snippet in a synthetic file context, so Frame.derive works without Frame.internal.
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            final class Sub extends kyo.Closed("resource", kyo.Frame.internal)
            """
        )
        assert(errors.nonEmpty, "extending final Closed must fail to compile")
        assert(
            errors.exists(_.message.toLowerCase.contains("final")),
            s"the failure must cite Closed's final modifier, got ${errors.map(_.message)}"
        )
        // The corresponding positive half: a full kyo-net module compile (this suite included) succeeds only if no OTHER source subtypes
        // Closed, since a second illegal `extends Closed` would fail the SAME compiler check this probe exercises, module-wide. A green
        // `sbt kyo-net/compile` is therefore the repo-wide confirmation that NetException.scala (the sole prior subtyper) is the only one
        // that changed, and that nothing new was introduced.
    }

    "getCause returns the structural cause for every cause-carrying leaf (Throwable case AND NetErrno case), never null" in {
        val throwableCause = new RuntimeException("x")
        val errnoCause     = NetErrno(111)
        val leaves: List[NetException] = List(
            NetConnectException("h", 80, cause = throwableCause),
            NetDnsResolutionException("h", cause = errnoCause),
            NetTlsSetupException("SSL_new", cause = throwableCause),
            NetBackendInitException("io_uring", cause = errnoCause, recoverable = true)
        )
        assert(leaves(0).getCause eq throwableCause, s"NetConnectException.getCause must be the exact Throwable, got ${leaves(0).getCause}")
        assert(
            leaves(1).getCause eq errnoCause,
            s"NetDnsResolutionException.getCause must be the exact NetErrno, got ${leaves(1).getCause}"
        )
        assert(
            leaves(2).getCause eq throwableCause,
            s"NetTlsSetupException.getCause must be the exact Throwable, got ${leaves(2).getCause}"
        )
        assert(leaves(3).getCause eq errnoCause, s"NetBackendInitException.getCause must be the exact NetErrno, got ${leaves(3).getCause}")
    }

    "NetErrno.code is structural and getMessage derives errno=code; NetErrno roots at KyoException not RuntimeException" in {
        val errno = NetErrno(111)
        assert(errno.code == 111)
        assert(errno.getMessage.contains("errno=111"), s"getMessage must derive from code, got ${errno.getMessage}")
        assert(errno.isInstanceOf[KyoException], "NetErrno must root at KyoException")
        // NetErrno and Closed are both final and unrelated, so the type system already proves NetErrno is never a Closed: a runtime
        // isInstanceOf check here is statically always-false and rejected by the assert macro. The compile-time probe below asserts the
        // same sibling-hierarchy property at the type level instead.
        val siblingErrors = scala.compiletime.testing.typeCheckErrors("val x: kyo.Closed = kyo.net.NetErrno(111)")
        assert(siblingErrors.nonEmpty, "NetErrno must not be assignable to Closed (sibling hierarchy, never a subtype)")
    }

    "a leaf with no cause supplied returns getCause null and a message assembled from its typed fields only" in {
        val e = NetConnectException("h", 80)
        assert(e.getMessage.contains("connect to h:80 failed"), s"getMessage must assemble from host/port, got ${e.getMessage}")
        assert(e.getCause == null, s"an empty-string cause must render getCause null, got ${e.getCause}")
        // Same static-disjointness reasoning as the NetErrno leaf above: a runtime isInstanceOf[Closed] check on a NetException is
        // statically always-false (both are final, unrelated types), so the property is asserted at the type level instead.
        val siblingErrors = scala.compiletime.testing.typeCheckErrors("val x: kyo.Closed = kyo.net.NetConnectException(\"h\", 80)")
        assert(siblingErrors.nonEmpty, "NetConnectException must not be assignable to Closed (sibling hierarchy, never a subtype)")
    }

end NetExceptionTest
