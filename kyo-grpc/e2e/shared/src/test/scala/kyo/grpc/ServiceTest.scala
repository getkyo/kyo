package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.test.*
import org.scalactic.Equality
import org.scalactic.TripleEquals.*
import org.scalatest.EitherValues.*
import org.scalatest.Inspectors.*

import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class ServiceTest extends KyoTest:

    "unary" - {
        "echo" in run {
            for
                client <- createClientAndServer
                message = "Hello"
                request = Echo(message)
                response <- Fibers.fromFuture(client.unary(request))
            yield assert(response == EchoEcho(message))
        }
        "abort" in {
            forEvery(Status.Code.values().filterNot(_ == Status.Code.OK)) { code =>
                run {
                    for
                        client <- createClientAndServer
                        status  = code.toStatus
                        request = Abort(status.getCode.value)
                        response <- IOs.catching(Fibers.fromFuture(client.unary(request))) {
                            case e: StatusRuntimeException => e
                        }
                    yield
                        val responseOrException = response match
                            case e: StatusRuntimeException => Right(e)
                            case other                     => Left(other)
                        assert(responseOrException.value.getStatus === status)
                }
            }
        }
        "fail" in run {
            for
                client <- createClientAndServer
                message = "Oh no!"
                request = Fail(message)
                response <- IOs.catching(Fibers.fromFuture(client.unary(request))) {
                    case e: StatusRuntimeException => e
                }
            yield
                val responseOrException = response match
                    case e: StatusRuntimeException => Right(e)
                    case other                     => Left(other)
                assert(responseOrException.value.getStatus === Status.INTERNAL.withDescription(message))
        }
    }

    private given CanEqual[Response, EchoEcho]       = CanEqual.derived
    private given CanEqual[Status.Code, Status.Code] = CanEqual.derived

    private given Equality[Status] with
        override def areEqual(status: Status, b: Any): Boolean =
            b match
                case other: Status =>
                    status.getCode == other.getCode &&
                        status.getDescription == other.getDescription &&
                        status.getCause == other.getCause
                case _ => false

    private def createClientAndServer =
        for
            port   <- findFreePort
            _      <- createServer(port)
            client <- createClient(port)
        yield client

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
