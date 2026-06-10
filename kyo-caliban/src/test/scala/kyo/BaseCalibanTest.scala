package kyo

abstract class BaseCalibanTest extends kyo.test.Test[Any]:

    // ResolversTest's WebSocket subscription leaves are timing-sensitive: under kyo-test's concurrent leaf pool,
    // overlapping leaves starve WebSocket message delivery past the collectMessages deadline (these pass reliably on
    // main, where ScalaTest ran the suite's tests sequentially). Run each suite's leaves sequentially to restore that.
    override def config = super.config.sequential

    // Run the body THROUGH the ZIO runtime (preserving the kyo<->ZIO/caliban interop these tests cover),
    // bridging the resulting Future back into a kyo computation so it can be a kyo-test leaf body.
    def runZIO[A](v: zio.ZIO[Any, Throwable, A]): A < Async =
        Async.fromFuture(zio.Unsafe.unsafely(zio.Runtime.default.unsafe.runToFuture(v)))
end BaseCalibanTest
