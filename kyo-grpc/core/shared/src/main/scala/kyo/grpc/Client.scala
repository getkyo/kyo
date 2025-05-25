package kyo.grpc

import io.grpc.*
import kyo.*

object Client:

    /** Shuts down the [[ManagedChannel]] with orderly shutdown and timeout.
      *
      * First attempts graceful shutdown by calling [[ManagedChannel.shutdown]] and waiting for termination.
      * If the channel doesn't terminate within the timeout, forces shutdown with [[ManagedChannel.shutdownNow]].
      *
      * @param channel
      *   The ManagedChannel to shut down
      * @param timeout
      *   The maximum duration to wait for graceful termination (default: 30 seconds)
      * @return
      *   `true` if the channel is terminated or `false` if the channel was forcibly shutdown, but it has not terminated
      *   yet
      */
    def shutdown(channel: ManagedChannel, timeout: Duration = 30.seconds)(using Frame): Boolean < IO =
        IO:
            val terminated =
                channel
                    .shutdown()
                    .awaitTermination(timeout.toJava.toNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
            if terminated then true
            else channel.shutdownNow().isTerminated

    def channel(host: String, port: Int, timeout: Duration = 30.seconds)(
        configure: ManagedChannelBuilder[?] => ManagedChannelBuilder[?],
        shutdown: (ManagedChannel, Duration) => Frame ?=> Any < IO = shutdown
    )(using Frame): ManagedChannel < (Resource & IO) =
        Resource.acquireRelease(
            IO(configure(ManagedChannelBuilder.forAddress(host, port)).build())
        )(shutdown(_, timeout))

end Client
