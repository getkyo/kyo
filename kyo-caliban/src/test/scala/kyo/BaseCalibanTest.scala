package kyo

abstract class BaseCalibanTest extends kyo.test.Test[Any]:

    // ResolversTest's WebSocket subscription leaves are timing-sensitive: under kyo-test's concurrent leaf pool,
    // overlapping leaves starve WebSocket message delivery past the collectMessages deadline (these pass reliably on
    // main, where ScalaTest ran the suite's tests sequentially). Run each suite's leaves sequentially to restore that.
    //
    // Only the socket category is disabled. The GraphQL server in these suites is Scope-managed (closed on scope exit),
    // but the NIO transport defers a listening socket's real fd close to its idle selector's next select(), which nothing
    // wakes, so the listener fd outlives the run. That fix belongs to the transport (frozen for the kyo-net rewrite); the
    // socket is an opaque socket:[inode] on an ephemeral port that no allowlist can match. File-descriptor, thread, and
    // fiber detection stay on.
    override def config = super.config.sequential.leakCheckSockets(false)

    // Run the body THROUGH the ZIO runtime (preserving the kyo<->ZIO/caliban interop these tests cover),
    // bridging the resulting Future back into a kyo computation so it can be a kyo-test leaf body.
    def runZIO[A](v: zio.ZIO[Any, Throwable, A]): A < Async =
        Async.fromFuture(zio.Unsafe.unsafely(zio.Runtime.default.unsafe.runToFuture(v)))
end BaseCalibanTest
