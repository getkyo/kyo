package kyo.grpc

import io.grpc.ServerBuilder
import kyo.*
import sun.misc.Signal

object Server:

    def shutdown(server: io.grpc.Server): Unit < IOs =
        IOs(server.shutdown().awaitTermination())

    def start(port: Int)(
        configure: ServerBuilder[?] => ServerBuilder[?],
        shutdown: io.grpc.Server => Unit < IOs = shutdown
    ): io.grpc.Server < Resources =
        Resources.acquireRelease(
            IOs(configure(ServerBuilder.forPort(port)).build().start())
        )(shutdown)

    // This is required until https://github.com/getkyo/kyo/issues/491 is done.
    // Put it here so that it can be converted to a no-op without breaking compatibility or behaviour.
    def waitForInterrupt: Unit < Fibers =
        for {
            promise <- Fibers.initPromise[Unit]
            _ <- IOs(Signal.handle(new Signal("INT"),  _ => IOs.run(promise.complete(()).unit))).unit
            _ <- IOs(Signal.handle(new Signal("TERM"), _ => IOs.run(promise.complete(()).unit))).unit
            _ <- promise.get
        } yield ()

end Server
