package kyo.grpc

import io.grpc.ServerBuilder
import kyo.*
import sun.misc.Signal

type Server = io.grpc.Server

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
      * First attempts graceful shutdown by calling [[io.grpc.Server.shutdown]] and suspending up to `timeout` for termination. If the
      * server does not terminate within the timeout, it forces shutdown with [[io.grpc.Server.shutdownNow]] and then suspends up to 1
      * minute for termination.
      *
      * @param server
      *   The server to shut down
      * @param timeout
      *   The maximum duration to wait for graceful termination (default: 30 seconds)
      */
    def shutdown(server: Server, timeout: Duration = 30.seconds)(using Frame): Unit < Async =
        def pollTerminated(timeout: Duration): Boolean < Async =
            def poll: Boolean < Async =
                Sync.defer(server.isTerminated).flatMap:
                    case true  => true
                    case false => Async.sleep(10.millis).andThen(poll)

            Async.race(poll, Async.sleep(timeout).andThen(Sync.defer(server.isTerminated)))
        end pollTerminated

        for
            _          <- Sync.defer(server.shutdown())
            terminated <- pollTerminated(timeout)
            _ <-
                if terminated then Kyo.unit
                else Sync.defer(server.shutdownNow()).andThen(pollTerminated(1.minute).unit)
        yield ()
        end for
    end shutdown

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
      *   the running server pending [[Scope]] and [[Async]]
      */
    def start(port: Int, timeout: Duration = 30.seconds)(
        configure: ServerBuilder[?] => ServerBuilder[?],
        shutdown: (Server, Duration) => Frame ?=> Any < Async = shutdown
    )(using Frame): Server < (Scope & Async) =
        Scope.acquireRelease(
            Sync.defer(configure(ServerBuilder.forPort(port)).build().start())
        )(shutdown(_, timeout))

end Server
