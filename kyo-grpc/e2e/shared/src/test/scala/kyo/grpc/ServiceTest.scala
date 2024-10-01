package kyo.grpc

import io.grpc.{Server as _, *}
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kgrpc.*
import kgrpc.test.*
import kyo.*
import org.scalactic.Equality
import org.scalactic.TripleEquals.*
import org.scalatest.EitherValues.*
import org.scalatest.Inspectors.*

class ServiceTest extends Test:

    "unary" - {
        "echo" in run {
            for
                client <- createClientAndServer
                message = "Hello"
                request = Say(message)
                response <- client.oneToOne(request)
            yield assert(response == Echo(message))
        }
        "abort" in {
            forEvery(Status.Code.values().filterNot(_ == Status.Code.OK)) { code =>
                run {
                    val status = code.toStatus
                    Abort.run[StatusRuntimeException] {
                        for
                            client <- createClientAndServer
                            request = Cancel(status.getCode.value)
                            _ <- Abort.catching[StatusRuntimeException](client.oneToOne(request))
                        yield ()
                    }.map { result =>
                        assert(result.swap.toEither.value.getStatus === status)
                    }
                }
            }
        }
        "fail" in run {
            val message = "Oh no!"
            Abort.run[StatusRuntimeException] {
                for
                    client <- createClientAndServer
                    request = Fail(message)
                    _ <- Abort.catching[StatusRuntimeException](client.oneToOne(request))
                yield ()
            }.map { result =>
                assert(result.swap.toEither.value.getStatus === Status.INTERNAL.withDescription(message))
            }
        }
    }

    "server streaming" - {
        "echo" in run {
            for
                client <- createClientAndServer
                message = "Hello"
                request = Say(message, count = 5)
                responses <- client.oneToMany(request).run
            yield assert(responses == Chunk.from((1 to 5).map(n => Echo(s"$message $n"))))
        }
        "abort" in {
            forEvery(Status.Code.values().filterNot(_ == Status.Code.OK)) { code =>
                run {
                    val status = code.toStatus
                    Abort.run[StatusRuntimeException] {
                        for
                            client <- createClientAndServer
                            request = Cancel(status.getCode.value)
                            _ <- Abort.catching[StatusRuntimeException](client.oneToMany(request))
                        yield ()
                    }.map { result =>
                        assert(result.swap.toEither.value.getStatus === status)
                    }
                }
            }
        }
        "fail" in run {
            val message = "Oh no!"
            Abort.run[StatusRuntimeException] {
                for
                    client <- createClientAndServer
                    request = Fail(message)
                    _ <- Abort.catching[StatusRuntimeException](client.oneToMany(request))
                yield ()
            }.map { result =>
                assert(result.swap.toEither.value.getStatus === Status.INTERNAL.withDescription(message))
            }
        }
    }

    private given CanEqual[Response, Echo]           = CanEqual.derived
    private given CanEqual[Status.Code, Status.Code] = CanEqual.derived

    private given Equality[Status] with
        override def areEqual(status: Status, b: Any): Boolean =
            b match
                case other: Status =>
                    status.getCode == other.getCode &&
                    status.getDescription == other.getDescription &&
                    status.getCause == other.getCause
                case _ => false
    end given

    private def createClientAndServer =
        for
            port   <- findFreePort
            _      <- createServer(port)
            client <- createClient(port)
        yield client

    private def createServer(port: Int) =
        Server.start(port)(_.addService(TestServiceImpl.definition))

    private def createClient(port: Int) =
        createChannel(port).map(TestService.client(_))

    private def createChannel(port: Int) =
        Resource.acquireRelease(
            IO(
                ManagedChannelBuilder
                    .forAddress("localhost", port)
                    .usePlaintext()
                    .build()
            )
        ) { channel =>
            IO(channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS)).unit
        }

    private def findFreePort =
        val socket = new ServerSocket(0)
        IO.ensure(IO(socket.close()))(socket.getLocalPort)

end ServiceTest
