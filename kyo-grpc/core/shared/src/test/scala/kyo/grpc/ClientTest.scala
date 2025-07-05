package kyo.grpc

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.ManagedChannelProvider
import io.grpc.ManagedChannelProvider.ProviderNotFoundException
import io.grpc.ManagedChannelRegistry
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import kyo.*
import org.scalamock.scalatest.AsyncMockFactory

class ClientTest extends Test with AsyncMockFactory:

    private val host = "localhost"
    private val port = 50051

    "shutdown shuts down the channel gracefully" in run {
        val channel = mock[ManagedChannel]

        (() => channel.shutdown())
            .expects()
            .returns(channel)
            .once()

        channel.awaitTermination
            .expects(30000000000L, TimeUnit.NANOSECONDS)
            .returns(true)
            .once()

        Client.shutdown(channel).map(_ => succeed)
    }

    "shutdown shuts down the channel forcefully" in run {
        val channel = mock[ManagedChannel]

        (() => channel.shutdown())
            .expects()
            .returns(channel)
            .once()

        channel.awaitTermination
            .expects(30000000000L, TimeUnit.NANOSECONDS)
            .returns(false)
            .once()

        (() => channel.shutdownNow())
            .expects()
            .returns(channel)
            .once()

        channel.awaitTermination
            .expects(1, TimeUnit.MINUTES)
            .returns(true)
            .once()

        Client.shutdown(channel).map(_ => succeed)
    }

    "configures channel" in run {
        val channel = mock[ManagedChannel]
        (() => channel.shutdown())
            .expects()
            .once()

        val unconfiguredBuilder = mock[Builder]

        val configuredBuilder = mock[Builder]
        (() => configuredBuilder.build())
            .expects()
            .returns(channel)
            .once()

        val provider = StubProvider(unconfiguredBuilder)

        var configured = false

        def configure(
            actualBuilder: ManagedChannelBuilder[?]
        ): ManagedChannelBuilder[?] =
            configured = true
            assert(actualBuilder eq unconfiguredBuilder)
            configuredBuilder
        end configure

        for
            _ <- replaceProviders(provider)
            _ <- Client.channel(host, port)(configure)
        yield
            assert(provider.builderAddressName == host)
            assert(provider.builderAddressPort == port)
            assert(configured)
        end for
    }

    "shuts down channel" in run {
        val channel = mock[ManagedChannel]

        // Be careful here. Unexpected calls will fail when shutdown is called which gets swallowed by Resource and so
        // the test will not fail. See https://github.com/ScalaMock/ScalaMock/issues/633.
        var shutdownCount = 0
        (() => channel.shutdown())
            .expects()
            .onCall(() =>
                shutdownCount += 1
                channel
            )
            .once()

        val builder = mock[Builder]
        (() => builder.build())
            .expects()
            .returns(channel)
            .once()

        val provider = StubProvider(builder)

        val result = Resource.run:
            for
                _ <- replaceProviders(provider)
                _ <- Client.channel(host, port)(identity)
            yield assert(shutdownCount == 0)

        result.map(_ => assert(shutdownCount == 1))
    }

    private def replaceProviders(provider: ManagedChannelProvider): Unit < Sync =
        for
            registry <- Sync.defer(ManagedChannelRegistry.getDefaultRegistry())
            _        <- removeProviders(registry)
            _        <- Sync.defer(registry.register(provider))
        yield ()

    private def removeProviders(registry: ManagedChannelRegistry): Unit < Sync =
        Loop(registry): registry =>
            Abort.recover[ProviderNotFoundException](_ => Loop.done):
                for
                    provider <- Abort.catching[ProviderNotFoundException](ManagedChannelProvider.provider())
                    _        <- Sync.defer(registry.deregister(provider))
                yield Loop.continue(registry)

    abstract private class Builder extends ManagedChannelBuilder[Builder]

    private class StubProvider(builder: ManagedChannelBuilder[?]) extends ManagedChannelProvider:
        var builderAddressName: String = ""
        var builderAddressPort: Int    = -1

        override protected def isAvailable(): Boolean = true

        override protected def priority(): Int = 0

        override protected def builderForAddress(name: String, port: Int): ManagedChannelBuilder[?] =
            builderAddressName = name
            builderAddressPort = port
            builder
        end builderForAddress

        override protected def builderForTarget(target: String): ManagedChannelBuilder[?] = builder

        override protected def getSupportedSocketAddressTypes: java.util.Collection[Class[? <: SocketAddress]] =
            java.util.Collections.emptyList()
    end StubProvider

end ClientTest
