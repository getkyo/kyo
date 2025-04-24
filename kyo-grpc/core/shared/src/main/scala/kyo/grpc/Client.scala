package kyo.grpc

import io.grpc.*
import kyo.*

object Client:

    def shutdown(channel: ManagedChannel)(using Frame): Unit < IO =
        IO(channel.shutdown()).unit

    def channel(host: String, port: Int)(
        configure: ManagedChannelBuilder[?] => ManagedChannelBuilder[?],
        shutdown: ManagedChannel => Frame ?=> Unit < IO = shutdown
    )(using Frame): ManagedChannel < (Resource & IO) =
        Resource.acquireRelease(
            IO(configure(ManagedChannelBuilder.forAddress(host, port)).build())
        )(channel => {
            scala.Console.err.println("Shutting down channel")
            shutdown(channel)
        })

end Client
