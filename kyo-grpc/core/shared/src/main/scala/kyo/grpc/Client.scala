package kyo.grpc

import io.grpc.*
import java.util.concurrent.TimeUnit
import kyo.*

object Client:

    /** Attempts an orderly shut down of the [[io.grpc.ManagedChannel]] within a timeout.
      *
      * First attempts graceful shutdown by calling [[io.grpc.ManagedChannel.shutdown]] and waits up to `timeout` for termination. If the
      * server doesn't terminate within the timeout, forces shutdown with [[io.grpc.ManagedChannel.shutdownNow]] and then waits up to 1 hour
      * for it to terminate (there is no indefinite wait).
      *
      * @param channel
      *   The channel to shut down
      * @param timeout
      *   The maximum duration to wait for graceful termination (default: 30 seconds)
      */
    def shutdown(channel: ManagedChannel, timeout: Duration = 30.seconds)(using Frame): Unit < IO =
        IO:
            val terminated =
                channel
                    .shutdown()
                    .awaitTermination(timeout.toJava.toNanos, TimeUnit.NANOSECONDS)
            if terminated then () else discard(channel.shutdownNow().awaitTermination(1, TimeUnit.HOURS))

    def channel(host: String, port: Int, timeout: Duration = 30.seconds)(
        configure: ManagedChannelBuilder[?] => ManagedChannelBuilder[?],
        shutdown: (ManagedChannel, Duration) => Frame ?=> Any < IO = shutdown
    )(using Frame): ManagedChannel < (Resource & IO) =
        Resource.acquireRelease(
            IO(configure(ManagedChannelBuilder.forAddress(host, port)).build())
        )(shutdown(_, timeout))

end Client
