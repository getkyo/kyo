package kyo.grpc

import io.grpc.ServerBuilder
import java.util.concurrent.TimeUnit
import kyo.*
import sun.misc.Signal

/** Server utilities for managing gRPC servers in Kyo.
  *
  * Provides functionality to start and gracefully shutdown gRPC servers with proper resource management.
  *
  * @example
  *   {{{
  *   for
  *       _ <- Console.printLine(s"Server is running on port $port. Press Ctrl-C to stop.")
  *       server <- Server.start(port)(
  *           _.addService(GreeterService),
  *           { (server, duration) =>
  *               for
  *                   _ <- Console.print("Shutting down...")
  *                   _ <- Server.shutdown(server, duration)
  *                   _ <- Console.printLine("Done.")
  *               yield ()
  *           }
  *       )
  *       _ <- Async.never
  *   yield ()
  *   }}}
  */
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
    def shutdown(server: io.grpc.Server, timeout: Duration = 30.seconds)(using Frame): Unit < Sync =
        Sync.defer:
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
      *   the running server pending [[Resource]] and [[Sync]]
      */
    def start(port: Int, timeout: Duration = 30.seconds)(
        configure: ServerBuilder[?] => ServerBuilder[?],
        shutdown: (io.grpc.Server, Duration) => Frame ?=> Any < Sync = shutdown
    )(using Frame): io.grpc.Server < (Resource & Sync) =
        Resource.acquireRelease(
            Sync.defer(configure(ServerBuilder.forPort(port)).build().start())
        )(shutdown(_, timeout))

end Server
