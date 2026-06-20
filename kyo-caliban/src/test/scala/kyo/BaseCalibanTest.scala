package kyo

abstract class BaseCalibanTest extends kyo.test.Test[Any]:

    // ResolversTest's WebSocket subscription leaves are timing-sensitive: under kyo-test's concurrent leaf pool,
    // overlapping leaves starve WebSocket message delivery past the collectMessages deadline (these pass reliably on
    // main, where ScalaTest ran the suite's tests sequentially). Run each suite's leaves sequentially to restore that.
    //
    // leakCheck is disabled because the kyo-http NIO transport can leave a server's listening socket open: closing the
    // ServerSocketChannel only cancels its selector key, and on JDK 11+ the real fd close (kill()) is deferred until the
    // driver selector runs another select(); nothing wakes the selector on close, so when the fork goes idle after its
    // last server closes the LISTEN fd lingers. It surfaces here flakily (last-server-biased) because these suites bind
    // many ephemeral HttpServers. The leaked target is an opaque socket:[inode] with a dynamic inode and port, so the
    // allowlist (which matches a substring of the raw symlink target) cannot cover it. The transport is being replaced
    // by kyo-net; the bug and its fix are documented for that module in HANDOFF-listener-fd-leak.md.
    override def config = super.config.sequential.leakCheck(false)

    // Run the body THROUGH the ZIO runtime (preserving the kyo<->ZIO/caliban interop these tests cover),
    // bridging the resulting Future back into a kyo computation so it can be a kyo-test leaf body.
    def runZIO[A](v: zio.ZIO[Any, Throwable, A]): A < Async =
        Async.fromFuture(zio.Unsafe.unsafely(zio.Runtime.default.unsafe.runToFuture(v)))
end BaseCalibanTest
