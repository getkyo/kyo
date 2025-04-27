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
import scala.jdk.CollectionConverters.*
import scala.util.chaining.scalaUtilChainingOps

class ServiceTest extends Test:

    private val trailers = Metadata().tap(_.put(GrpcUtil.CONTENT_TYPE_KEY, GrpcUtil.CONTENT_TYPE_GRPC))

    private val notOKStatusCodes = Status.Code.values().filterNot(_ == Status.Code.OK)

    "unary" - {
        "echo" in run {
            val message = "Hello"
            val request = Success(message)
            for
                client   <- createClientAndServer
                response <- client.oneToOne(request)
            yield assert(response == Echo(message))
            end for
        }

        "cancel" in {
            forEvery(notOKStatusCodes) { code =>
                run {
                    val status  = code.toStatus
                    val request = Fail(status.getCode.value)
                    // TODO: No StatusRuntimeExceptions
                    val expected = status.asRuntimeException(trailers)
                    for
                        client <- createClientAndServer
                        result <- Abort.run[StatusRuntimeException](client.oneToOne(request))
                    yield assertStatusRuntimeException(result, expected)
                    end for
                }
            }
        }

        "fail" in run {
            val message = "Oh no!"
            val request = Panic(message)
            // TODO: No StatusRuntimeExceptions
            val expected = Status.INTERNAL.withDescription(message).asRuntimeException(trailers)
            for
                client <- createClientAndServer
                result <- Abort.run[StatusRuntimeException](client.oneToOne(request))
            yield assertStatusRuntimeException(result, expected)
            end for
        }
    }

    "server streaming" - {
        "echo" in run {
            val message = "Hello"
            val request = Success(message, count = 5)
            for
                client    <- createClientAndServer
                responses <- client.oneToMany(request).run
            yield assert(responses == Chunk.from((1 to 5).map(n => Echo(s"$message $n"))))
            end for
        }

        "cancel" - {
            "producing stream" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val status  = code.toStatus
                        val request = Fail(status.getCode.value, outside = true)
                        // TODO: Why no trailers here?
                        val expected = status.asException
                        for
                            client   <- createClientAndServer
                            response <- Abort.run[StatusException](client.oneToMany(request).run)
                        yield assertStatusException(response, expected)
                        end for
                    }
                }
            }

            "first element" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val status  = code.toStatus
                        val request = Fail(status.getCode.value)
                        // TODO: Why no trailers here?
                        val expected = status.asException
                        for
                            client <- createClientAndServer
                            result <- Abort.run[StatusException](client.oneToMany(request).take(1).run)
                        yield assertStatusException(result, expected)
                        end for
                    }
                }
            }

            "FOO after some elements" in {
//                forEvery(notOKStatusCodes) { code =>
//                    run {
//                        val status  = code.toStatus
//                        val after   = 5
//                        val request = Fail(status.getCode.value, after)
//                        // TODO: Why no trailers here?
//                        val expected = status.asException
//                        for
//                            client            <- createClientAndServer
//                            (responses, tail) <- client.oneToMany(request).splitAt(5)
//                            failedResponse    <- Abort.run[StatusException](tail.run)
//                        yield
//                            assert(responses == Chunk.from((after to 1 by -1).map(n => Echo(s"Cancelling in $n"))))
//                            assertStatusException(failedResponse, expected)
//                        end for
//                    }
//                }
                run {
                    val status  = notOKStatusCodes.head.toStatus
                    val after   = 5
                    val request = Fail(status.getCode.value, after)
                    // TODO: Why no trailers here?
                    val expected = status.asException
                    for
                        client            <- createClientAndServer
                        (responses, tail) <- client.oneToMany(request).splitAt(5)
                        failedResponse    <- Abort.run[StatusException](tail.run)
                    yield
                        assert(responses == Chunk.from((after to 1 by -1).map(n => Echo(s"Cancelling in $n"))))
                        assertStatusException(failedResponse, expected)
                    end for
                }
            }
        }

        "fail" - {
            "producing stream" in run {
                val message  = "Oh no!"
                val request  = Panic(message)
                val expected = Status.INTERNAL.withDescription(message).asException
                for
                    client <- createClientAndServer
                    result <- Abort.run[StatusException](client.oneToMany(request).run)
                yield assertStatusException(result, expected)
                end for
            }

            "first element" in run {
                val message  = "Oh no!"
                val request  = Panic(message)
                val expected = Status.INTERNAL.withDescription(message).asException
                for
                    client <- createClientAndServer
                    result <- Abort.run[StatusException](client.oneToMany(request).run)
                yield assertStatusException(result, expected)
                end for
            }

            "after some elements" in run {
                val message  = "Oh no!"
                val request  = Panic(message)
                val expected = Status.INTERNAL.withDescription(message).asException
                for
                    client <- createClientAndServer
                    // TODO: Check first elements.
                    result <- Abort.run[StatusException](client.oneToMany(request).run)
                yield assertStatusException(result, expected)
                end for
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
                case _                        => false
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
