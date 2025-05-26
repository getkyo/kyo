package kyo.grpc

import io.grpc.ServerBuilder
import kyo.*
import sun.misc.Signal

import java.util.concurrent.TimeUnit

object Server:

    /** Attempts an orderly shut down of the [[io.grpc.Server]] within a timeout.
      *
      * First attempts graceful shutdown by calling [[io.grpc.Server.shutdown]] and waits up to `timeout` for
      * termination. If the server doesn't terminate within the timeout, forces shutdown with
      * [[io.grpc.Server.shutdownNow]] and then waits indefinitely for it to terminate.
      *
      * @param server
      *   The server to shut down
      * @param timeout
      *   The maximum duration to wait for graceful termination (default: 30 seconds)
      */
    def shutdown(server: io.grpc.Server, timeout: Duration = 30.seconds)(using Frame): Unit < IO =
        IO:
            val terminated =
                server
                    .shutdown()
                    .awaitTermination(timeout.toJava.toNanos, TimeUnit.NANOSECONDS)
            if terminated then () else server.shutdownNow().awaitTermination()

    def start(port: Int, timeout: Duration = 30.seconds)(
        configure: ServerBuilder[?] => ServerBuilder[?],
        shutdown: (io.grpc.Server, Duration) => Frame ?=> Any < IO = shutdown
    )(using Frame): io.grpc.Server < (Resource & IO) =
        Resource.acquireRelease(
            IO(configure(ServerBuilder.forPort(port)).build().start())
        )(shutdown(_, timeout))

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
