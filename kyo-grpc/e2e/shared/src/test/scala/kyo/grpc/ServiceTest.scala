package kyo.grpc

import io.grpc.{Server as _, *}
import io.grpc.internal.GrpcUtil
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kgrpc.*
import kgrpc.test.*
import kyo.*
import org.scalactic.Equality
import org.scalactic.TripleEquals.*
import org.scalatest.EitherValues.*
import org.scalatest.Inspectors.*
import org.scalatest.Inspectors.*
import scala.util.chaining.scalaUtilChainingOps
import scala.jdk.CollectionConverters.*

class ServiceTest extends Test:

    private val trailers = Metadata().tap(_.put(GrpcUtil.CONTENT_TYPE_KEY, GrpcUtil.CONTENT_TYPE_GRPC))

    private val notOKStatusCodes = Status.Code.values().filterNot(_ == Status.Code.OK)

    "unary" - {
        "echo" in run {
            for
                client <- createClientAndServer
                message = "Hello"
                request = Say(message)
                response <- client.oneToOne(request)
            yield assert(response == Echo(message))
        }

        "cancel" in {
            forEvery(notOKStatusCodes) { code =>
                run {
                    val status = code.toStatus
                    // TODO: No StatusRuntimeExceptions
                    val expected = status.asRuntimeException(trailers)
                    for
                        client <- createClientAndServer
                        request = Cancel(status.getCode.value)
                        result <- Abort.run[StatusRuntimeException](client.oneToOne(request))
                    yield assertStatusRuntimeException(result, expected)
                }
            }
        }

        "fail" in run {
            val message = "Oh no!"
            // TODO: No StatusRuntimeExceptions
            val expected = Status.INTERNAL.withDescription(message).asRuntimeException(trailers)
            for
                client <- createClientAndServer
                request = Fail(message)
                result <- Abort.run[StatusRuntimeException](client.oneToOne(request))
            yield assertStatusRuntimeException(result, expected)
        }
    }

    "server streaming" - {
        "echo" in run {
            for
                client <- createClientAndServer
                message = "Hello"
                request = Say(message, count = 5)
                responses <- client.oneToMany(request).run
                // FIXME: There is a race condition here. Sometimes it has fewer messages.
            yield assert(responses == Chunk.from((1 to 5).map(n => Echo(s"$message $n"))))
        }

        "cancel" - {
            "FOO - producing stream" in {
//                forEvery(notOKStatusCodes) { code =>
//                    run {
//                        val status = code.toStatus
//                        // TODO: Why no trailers here?
//                        val expected = status.asException // (trailers)
//                        Abort.run[StatusException] {
//                            for
//                                client <- createClientAndServer
//                                request = Cancel(status.getCode.value, outside = true)
//                                response <- client.oneToMany(request).run
//                            yield response
//                        }.map(assertStatusException(_, expected))
//                    }
//                }
                run {
                    val status = notOKStatusCodes.head.toStatus
                    // TODO: Why no trailers here?
                    val expected = status.asException // (trailers)
                    for
                        client <- createClientAndServer
                        request = Cancel(status.getCode.value, outside = true)
                        result <- Abort.run[StatusException](client.oneToMany(request).run)
                        _ <- Console.printLineErr(s"Result = ${result}")
                    yield assertStatusException(result, expected)
                }
            }

            "first element" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val status = code.toStatus
                        // TODO: Why no trailers here?
                        val expected = status.asException // (trailers)
                        for
                            client <- createClientAndServer
                            request = Cancel(status.getCode.value)
                            result <- Abort.run[StatusException](client.oneToMany(request).take(1).run)
                        yield assertStatusException(result, expected)
                    }
                }
            }

            "after some elements" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val status = code.toStatus
                        val after  = 5
                        // TODO: Why no trailers here?
                        val expected = status.asException // (trailers)
                        for
                            client <- createClientAndServer
                            request = Cancel(status.getCode.value, after)
                            // TODO: How to we run as many as we can before failing?
                            stream = client.oneToMany(request)
                            responses      <- stream.take(after).run
                            failedResponse <- Abort.run[StatusException](stream.drop(after).take(1).run)
                        yield
                            assert(responses == Chunk.from((after to 1 by -1).map(n => Echo(s"Cancelling in $n"))))
                            assertStatusException(failedResponse, expected)
                        end for
                    }
                }
            }
        }

        "fail" - {
            "producing stream" in run {
                val message  = "Oh no!"
                val expected = Status.INTERNAL.withDescription(message).asException
                for
                    client <- createClientAndServer
                    request = Fail(message)
                    result <- Abort.run[StatusException](client.oneToMany(request).run)
                yield assertStatusException(result, expected)
            }

            "first element" in run {
                val message  = "Oh no!"
                val expected = Status.INTERNAL.withDescription(message).asException
                for
                    client <- createClientAndServer
                    request = Fail(message)
                    result <- Abort.run[StatusException](client.oneToMany(request).run)
                yield assertStatusException(result, expected)
            }

            "after some elements" in run {
                val message  = "Oh no!"
                val expected = Status.INTERNAL.withDescription(message).asException
                for
                    client <- createClientAndServer
                    request = Fail(message)
                    result <- Abort.run[StatusException](client.oneToMany(request).run)
                yield assertStatusException(result, expected)
            }
        }
    }

    // TODO: For client streaming test sending nothing.

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

    private given Equality[Metadata] with
        override def areEqual(metadata: Metadata, b: Any): Boolean =
            Maybe(b) match
                case Present(other: Metadata) => metadata.toString == other.toString
                case _ => false
        end areEqual
    end given

    private def assertStatusException(result: Result[StatusException, Any], expected: StatusException) =
        val actual = result.failure.get
        // We can't compare the exception here because if it fails we run into https://github.com/scalatest/scalatest/issues/427.
        assert(actual.getStatus === expected.getStatus)
        assert((actual.getTrailers == null && expected.getTrailers == null) || actual.getTrailers === expected.getTrailers)
        assert(actual.getMessage === expected.getMessage)
    end assertStatusException

    private def assertStatusRuntimeException(result: Result[StatusRuntimeException, Any], expected: StatusRuntimeException) =
        val actual = result.failure.get
        // We can't compare the exception here because if it fails we run into https://github.com/scalatest/scalatest/issues/427.
        assert(actual.getStatus === expected.getStatus)
        assert((actual.getTrailers == null && expected.getTrailers == null) || actual.getTrailers === expected.getTrailers)
        assert(actual.getMessage === expected.getMessage)
    end assertStatusRuntimeException

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
