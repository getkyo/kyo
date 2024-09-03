package kyo.grpc

import io.grpc.*
import kyo.*

object Client:

    def shutdown(channel: ManagedChannel): Unit < IOs =
        IOs(channel.shutdown()).unit

    def channel(host: String, port: Int)(
        configure: ManagedChannelBuilder[?] => ManagedChannelBuilder[?],
        shutdown: ManagedChannel => Unit < IOs = shutdown
    ): ManagedChannel < Resources =
        Resources.acquireRelease(
            IOs(configure(ManagedChannelBuilder.forAddress(host, port)).build())
        )(shutdown)

end Client
