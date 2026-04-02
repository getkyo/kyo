package kyo.grpc

import io.grpc.*
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import io.grpc.stub.MetadataStub
import java.util.concurrent.TimeUnit
import kyo.*
import scala.concurrent.duration.*

/** Test utilities for kyo-grpc tests */
object Test:

    /** Default test server configuration */
    val testServerConfig = GrpcServerConfig(
        host = "localhost",
        port = 0  // 0 means random available port
    )

    /** Default test client configuration */
    val testClientConfig = GrpcClientConfig(
        host = "localhost",
        port = 50051,
        deadline = Some(30.seconds)
    )

    /** A helper to run a test with a server and client */
    def withServerAndClient[Req, Res](
        service: ServerServiceDefinition,
        clientConfig: GrpcClientConfig = testClientConfig
    )(
        test: (GrpcServer, GrpcClient) => Any < Async
    ): Any < Async =
        val server = GrpcServer(testServerConfig)
            .addService(service)
            .build()
        
        for
            binding <- server.start()
            client <-
                val actualConfig = clientConfig.copy(port = binding.port)
                GrpcClient(actualConfig).build()
            result <- test(server, client)
            _ <- server.stop()
            _ <- client.shutdown()
        yield result

    /** Create a test server with a random available port */
    def createTestServer(services: ServerServiceDefinition*): GrpcServer =
        GrpcServer(testServerConfig).addService(services.toList:_*).build()

    /** Wait for a condition with timeout */
    def awaitCondition[T](
        poll: => T,
        maxDuration: FiniteDuration = 10.seconds,
        interval: FiniteDuration = 100.millis
    )(predicate: T => Boolean)(using Frame): Unit < Async =
        val start = System.currentTimeMillis()
        
        def loop(): Unit < Async =
            if predicate(poll) then
                ().pure[Async]
            else if (System.currentTimeMillis() - start) > maxDuration.toMillis then
                throw new java.util.concurrent.TimeoutException(s"Condition not met within $maxDuration")
            else
                Async.sleep(interval).andThen(loop())
        
        loop()

end Test

/** Base test class for gRPC tests */
abstract class GrpcTest extends Test:

    /** Create a test server */
    protected def createServer(services: ServerServiceDefinition*): GrpcServer =
        Test.createTestServer(services:_*)

    /** Run a test with a server and client */
    protected def withServerAndClient[Req, Res](
        service: ServerServiceDefinition,
        clientConfig: GrpcClientConfig = Test.testClientConfig
    )(
        test: (GrpcServer, GrpcClient) => Any < Async
    ): Any < Async =
        Test.withServerAndClient(service, clientConfig)(test)

end GrpcTest
