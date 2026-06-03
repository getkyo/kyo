package kyo

abstract class BaseCalibanTest extends kyo.test.Test[Any]:
    // Run the body THROUGH the ZIO runtime (preserving the kyo<->ZIO/caliban interop these tests cover),
    // bridging the resulting Future back into a kyo computation so it can be a kyo-test leaf body.
    def runZIO[A](v: zio.ZIO[Any, Throwable, A]): A < Async =
        Async.fromFuture(zio.Unsafe.unsafely(zio.Runtime.default.unsafe.runToFuture(v)))
end BaseCalibanTest
