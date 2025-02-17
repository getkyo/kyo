package kyo.grpc

import io.grpc.ServerBuilder
import kyo.*
import sun.misc.Signal

object Server:

    def shutdown(server: io.grpc.Server)(using Frame): Unit < Async =
        Async.run(server.shutdown().awaitTermination()).map(_.get)

    def start(port: Int)(
        configure: ServerBuilder[?] => ServerBuilder[?],
        shutdown: io.grpc.Server => Frame ?=> Unit < Async = shutdown
    )(using Frame): io.grpc.Server < (Resource & IO) =
        Resource.acquireRelease(
            IO(configure(ServerBuilder.forPort(port)).build().start())
        )(server => shutdown(server))

    // This is required until https://github.com/getkyo/kyo/issues/491 is done.
    // Put it here so that it can be converted to a no-op without breaking compatibility or behaviour.
    def waitForInterrupt(using Frame, AllowUnsafe): Unit < Async =
        for
            promise <- Promise.init[Nothing, Unit]
            complete = promise.complete(Result.succeed(())).unit
            _ <- IO(Signal.handle(new Signal("INT"), _ => Abort.run(IO.Unsafe.run(complete)).eval.getOrThrow))
            _ <- IO(Signal.handle(new Signal("TERM"), _ => Abort.run(IO.Unsafe.run(complete)).eval.getOrThrow))
            _ <- promise.get
        yield ()

end Server
