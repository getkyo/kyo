package kyo.grpc

import io.grpc.ServerBuilder
import java.util.concurrent.TimeUnit
import kyo.*
import sun.misc.Signal

object Server:

    /** Attempts an orderly shut down of the [[io.grpc.Server]] within a timeout.
      *
      * First attempts graceful shutdown by calling [[io.grpc.Server.shutdown]] and waits up to `timeout` for termination. If the server
      * doesn't terminate within the timeout, forces shutdown with [[io.grpc.Server.shutdownNow]] and then waits indefinitely for it to
      * terminate.
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

    /** Starts an [[io.grpc.Server]] on the specified port with the provided configuration and shutdown logic.
      *
      * @param port
      *   the port on which the server will listen
      * @param timeout
      *   the maximum duration to wait for graceful termination (default: 30 seconds)
      * @param configure
      *   a function to configure the [[ServerBuilder]] such as adding services
      * @param shutdown
      *   A function to handle the shutdown of the server, which takes the server instance and a timeout duration. Defaults to
      *   [[Server.shutdown]]
      * @return
      *   the running server pending [[Resource]] and [[IO]]
      */
    def start(port: Int, timeout: Duration = 30.seconds)(
        configure: ServerBuilder[?] => ServerBuilder[?],
        shutdown: (io.grpc.Server, Duration) => Frame ?=> Any < IO = shutdown
    )(using Frame): io.grpc.Server < (Resource & IO) =
        Resource.acquireRelease(
            IO(configure(ServerBuilder.forPort(port)).build().start())
        )(shutdown(_, timeout))

    // This is required until https://github.com/getkyo/kyo/issues/491 is done.
    /** Waits indefinitely for an interrupt signal (SIGINT or SIGTERM).
      *
      * Use this to keep the server running until an interrupt signal is received. For example:
      * {{{
      *   run {
      *     for
      *       _ <- Console.println(s"Server is running on port $port. Press Ctrl-C to stop.")
      *       server <- Server.start(port)(_.addService(GreeterService), { server =>
      *         for
      *           _ <- Console.print("Shutting down...")
      *           _ <- Server.shutdown(server)
      *           _ <- Console.println("Done.")
      *         yield ()
      *       })
      *       _ <- Server.waitForInterrupt
      *     yield ()
      *   }
      * }}}
      *
      * @return
      *   [[Unit]] pending [[Async]] that completes when an interrupt signal is received
      */
    def waitForInterrupt(using Frame, AllowUnsafe): Unit < Async =
        for
            promise <- Promise.init[Nothing, Unit]
            complete = promise.complete(Result.succeed(())).unit
            _ <- IO(Signal.handle(new Signal("INT"), _ => Abort.run(IO.Unsafe.run(complete)).eval.getOrThrow))
            _ <- IO(Signal.handle(new Signal("TERM"), _ => Abort.run(IO.Unsafe.run(complete)).eval.getOrThrow))
            _ <- promise.get
        yield ()

end Server
