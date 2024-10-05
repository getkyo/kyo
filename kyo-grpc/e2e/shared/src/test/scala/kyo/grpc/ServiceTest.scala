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
import scala.util.chaining.scalaUtilChainingOps

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
            forEvery(notOKStatusCodes) { code =>
                run {
                    val status = code.toStatus
                    // TODO: No StatusRuntimeExceptions
                    val expected = status.asRuntimeException(trailers)
                    Abort.run[StatusRuntimeException] {
                        for
                            client <- createClientAndServer
                            request = Cancel(status.getCode.value)
                            _ <- client.oneToOne(request)
                        yield ()
                    }.map(assertStatusRuntimeException(_, expected))
                }
            }
        }

        "fail" in run {
            val message = "Oh no!"
            // TODO: No StatusRuntimeExceptions
            val expected = Status.INTERNAL.withDescription(message).asRuntimeException(trailers)
            Abort.run[StatusRuntimeException] {
                for
                    client <- createClientAndServer
                    request = Fail(message)
                    _ <- client.oneToOne(request)
                yield ()
            }.map(assertStatusRuntimeException(_, expected))
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

        // "abort" - {
        //     "producing stream" in {
        //         forEvery(notOKStatusCodes) { code =>
        //             run {
        //                 val status = code.toStatus
        //                 Abort.run[StatusRuntimeException] {
        //                     for
        //                         client <- createClientAndServer
        //                         request = Cancel(status.getCode.value)
        //                         _ <- client.oneToMany(request).run
        //                     yield ()
        //                 }.map { result =>
        //                     assert(result.swap.toEither.value.getStatus === status)
        //                 }
        //             }
        //         }
        //     }

        //     "first element" in {
        //         forEvery(notOKStatusCodes) { code =>
        //             run {
        //                 val status = code.toStatus
        //                 Abort.run[StatusRuntimeException] {
        //                     for
        //                         client <- createClientAndServer
        //                         request = Cancel(status.getCode.value)
        //                         _ <- client.oneToMany(request).run
        //                     yield ()
        //                 }.map { result =>
        //                     assert(result.swap.toEither.value.getStatus === status)
        //                 }
        //             }
        //         }
        //     }

        //     "after some elements" in {
        //         forEvery(notOKStatusCodes) { code =>
        //             run {
        //                 val status = code.toStatus
        //                 Abort.run[StatusRuntimeException] {
        //                     for
        //                         client <- createClientAndServer
        //                         request = Cancel(status.getCode.value)
        //                         _ <- client.oneToMany(request).run
        //                     yield ()
        //                 }.map { result =>
        //                     assert(result.swap.toEither.value.getStatus === status)
        //                 }
        //             }
        //         }
        //     }
        // }

        "fail" - {
            "producing stream" in run {
                val message  = "Oh no!"
                val expected = Status.INTERNAL.withDescription(message).asException
                Abort.run[StatusException] {
                    for
                        client <- createClientAndServer
                        request = Fail(message)
                        _ <- client.oneToMany(request).runDiscard
                    yield ()
                }.map(assertStatusException(_, expected))
            }

            "first element" in run {
                val message  = "Oh no!"
                val expected = Status.INTERNAL.withDescription(message).asException
                Abort.run[StatusException] {
                    for
                        client <- createClientAndServer
                        request = Fail(message)
                        _ <- client.oneToMany(request).runDiscard
                    yield ()
                }.map(assertStatusException(_, expected))
            }

            "after some elements" in run {
                val message  = "Oh no!"
                val expected = Status.INTERNAL.withDescription(message).asException
                Abort.run[StatusException] {
                    for
                        client <- createClientAndServer
                        request = Fail(message)
                        _ <- client.oneToMany(request).runDiscard
                    yield ()
                }.map(assertStatusException(_, expected))
            }
        }
    }

    private val trailers = Metadata().tap(_.put(GrpcUtil.CONTENT_TYPE_KEY, GrpcUtil.CONTENT_TYPE_GRPC))

    private val notOKStatusCodes = Status.Code.values().filterNot(_ == Status.Code.OK)

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
            b match
                case other: Metadata => metadata.toString == other.toString
                case _               => false
    end given

    private def assertStatusException(result: Result[StatusException, Any], expected: StatusException) =
        val actual = result.swap.value.get
        // We can't compare the exception here because if it fails we run into https://github.com/scalatest/scalatest/issues/427.
        assert(actual.getStatus === expected.getStatus)
        assert((actual.getTrailers == null && expected.getTrailers == null) || actual.getTrailers === expected.getTrailers)
        assert(actual.getMessage === expected.getMessage)
    end assertStatusException

    private def assertStatusRuntimeException(result: Result[StatusRuntimeException, Any], expected: StatusRuntimeException) =
        val actual = result.swap.value.get
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
