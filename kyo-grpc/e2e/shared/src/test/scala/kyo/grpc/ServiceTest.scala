package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.test.*

import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class ServiceTest extends KyoTest:

    private given CanEqual[Response, EchoEcho] = CanEqual.derived

    "echo" in run {
        for
            port     <- findFreePort
            _        <- createServer(port)
            client   <- createClient(port)
            response <- Fibers.fromFuture(client.unary(Echo("Hello")))
        yield assert(response == EchoEcho("Hello"))
    }

    private def createServer(port: Int) =
        Resources.acquireRelease(IOs(ServerBuilder.forPort(port).addService(TestService.bindService(TestServiceImpl)).build().start())) {
            server =>
                IOs(server.shutdown().awaitTermination())
        }

    private def createClient(port: Int) =
        Resources.acquireRelease(IOs(ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build())) {
            channel =>
                IOs(channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS)).unit
        }.map(TestServiceGrpc.stub)

    private def findFreePort =
        val socket = new ServerSocket(0)
        IOs.ensure(IOs(socket.close()))(socket.getLocalPort)

end ServiceTest
