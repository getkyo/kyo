package kyo.grpc

import io.grpc.{Server as _, *}
import io.grpc.internal.GrpcUtil
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kgrpc.*
import kgrpc.test.*
import kyo.*
import kyo.grpc.*
import org.scalactic.Equality
import org.scalactic.TripleEquals.*
import org.scalatest.Inspectors.*
import scala.concurrent.Future
import scala.util.chaining.scalaUtilChainingOps

class ServiceTest extends Test:

    private given CanEqual[Response, Echo]           = CanEqual.derived
    private given CanEqual[Status.Code, Status.Code] = CanEqual.derived

    private val emptyTrailers = Metadata()
    private val trailers      = Metadata().tap(_.put(GrpcUtil.CONTENT_TYPE_KEY, GrpcUtil.CONTENT_TYPE_GRPC))

    private val notOKStatusCodes = Status.Code.values().filterNot(_ === Status.Code.OK)

    "unary" - {
        "success" in run {
            val message = "Hello"
            val request = Success(message)
            for
                client   <- createClientAndServer
                response <- client.oneToOne(request)
            yield assert(response === Echo(message))
            end for
        }

        "fail" in {
            forEvery(notOKStatusCodes) { code =>
                run {
                    val message  = "Yeah nah bro"
                    val status   = code.toStatus.withDescription(message)
                    val request  = Fail(message, status.getCode.value)
                    val expected = status.asException(trailers)
                    for
                        client <- createClientAndServer
                        result <- Abort.run[StatusException](client.oneToOne(request))
                    yield assertStatusException(result, expected)
                    end for
                }
            }
        }

        "panic" in run {
            val message  = "Oh no!"
            val request  = Panic(message)
            val expected = Status.UNKNOWN.asException(trailers)
            for
                client <- createClientAndServer
                result <- Abort.run[StatusException](client.oneToOne(request))
            yield
                assertStatusException(result, expected)
                // Do not expose the internal error message
                val actual = result.failure.get
                assert(actual.getMessage === "UNKNOWN")
                assert(actual.getCause === null)
                assert(actual.getStatus().getCause === null)
            end for
        }
    }

    "server streaming" - {
        "success" in run {
            val message = "Hello"
            val request = Success(message, count = 5)
            for
                client    <- createClientAndServer
                responses <- client.oneToMany(request).run
            yield assert(responses == Chunk.from((1 to 5).map(n => Echo(s"$message $n"))))
            end for
        }

        "fail" - {
            "producing stream" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val message  = "Yeah nah bro"
                        val status   = code.toStatus.withDescription(message)
                        val request  = Fail(message, status.getCode.value, outside = true)
                        val expected = status.asException(trailers)
                        for
                            client   <- createClientAndServer
                            response <- Abort.run[StatusException](client.oneToMany(request).take(1).run)
                        yield assertStatusException(response, expected)
                        end for
                    }
                }
            }

            "first element" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val message  = "Yeah nah bro"
                        val status   = code.toStatus.withDescription(message)
                        val request  = Fail(message, status.getCode.value)
                        val expected = status.asException(trailers)
                        for
                            client <- createClientAndServer
                            result <- Abort.run[StatusException](client.oneToMany(request).take(1).run)
                        yield assertStatusException(result, expected)
                        end for
                    }
                }
            }

            "after some elements" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val message = "Yeah nah bro"
                        val status  = code.toStatus.withDescription(message)
                        val after   = 5
                        val request = Fail(message, status.getCode.value, after)
                        // For some reason, the trailers are empty here.
                        val expected = status.asException(emptyTrailers)
                        for
                            client            <- createClientAndServer
                            (responses, tail) <- client.oneToMany(request).splitAt(5)
                            failedResponse    <- Abort.run[StatusException](tail.run)
                        yield
                            assert(responses == Chunk.from((after to 1 by -1).map(n => Echo(s"Failing in $n"))))
                            assertStatusException(failedResponse, expected)
                        end for
                    }
                }
            }
        }

        "panic" - {
            "producing stream" in run {
                val message  = "Oh no!"
                val request  = Panic(message)
                val expected = Status.UNKNOWN.asException(trailers)
                for
                    client <- createClientAndServer
                    result <- Abort.run[StatusException](client.oneToMany(request).take(1).run)
                yield
                    assertStatusException(result, expected)
                    // Do not expose the internal error message
                    val actual = result.failure.get
                    assert(actual.getMessage === "UNKNOWN")
                    assert(actual.getCause === null)
                    assert(actual.getStatus().getCause === null)
                end for
            }

            "first element" in run {
                val message  = "Oh no!"
                val request  = Panic(message)
                val expected = Status.UNKNOWN.asException(trailers)
                for
                    client <- createClientAndServer
                    result <- Abort.run[StatusException](client.oneToMany(request).take(1).run)
                yield
                    assertStatusException(result, expected)
                    // Do not expose the internal error message
                    val actual = result.failure.get
                    assert(actual.getMessage === "UNKNOWN")
                    assert(actual.getCause === null)
                    assert(actual.getStatus().getCause === null)
                end for
            }

            "after some elements" in run {
                val message = "Oh no!"
                val after   = 5
                val request = Panic(message, after)
                // For some reason, the trailers are empty here.
                val expected = Status.UNKNOWN.asException(emptyTrailers)
                for
                    client            <- createClientAndServer
                    (responses, tail) <- client.oneToMany(request).splitAt(5)
                    failedResponse    <- Abort.run[StatusException](tail.run)
                yield
                    assert(responses == Chunk.from((after to 1 by -1).map(n => Echo(s"Panicing in $n"))))
                    assertStatusException(failedResponse, expected)
                    // Do not expose the internal error message
                    val actual = failedResponse.failure.get
                    assert(actual.getMessage === "UNKNOWN")
                    assert(actual.getCause === null)
                    assert(actual.getStatus().getCause === null)
                end for
            }
        }
    }

    "client streaming" - {
        "empty" in run {
            val successes = Chunk.empty[Request]
            val requests  = Stream(Emit.value(successes))
            for
                client   <- createClientAndServer
                response <- client.manyToOne(requests)
            yield assert(response === Echo())
            end for
        }

        "success" in run {
            val successes = Chunk.from((1 to 5).map(n => Success(n.toString): Request))
            val requests  = Stream(Emit.value(successes))
            for
                client   <- createClientAndServer
                response <- client.manyToOne(requests)
            yield assert(response === Echo((1 to 5).mkString(" ")))
            end for
        }

        "fail" - {
            "first element" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val message   = "Yeah nah bro"
                        val status    = code.toStatus.withDescription(message)
                        val fail      = Fail(message, status.getCode.value)
                        val successes = Chunk.from((1 to 5).map(n => Success(n.toString): Request))
                        val requests  = Stream(Emit.value(Chunk(fail).concat(successes)))
                        val expected  = status.asException(trailers)
                        for
                            client <- createClientAndServer
                            result <- Abort.run[StatusException](client.manyToOne(requests))
                        yield assertStatusException(result, expected)
                        end for
                    }
                }
            }

            "after some elements" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val message   = "Yeah nah bro"
                        val status    = code.toStatus.withDescription(message)
                        val after     = 5
                        val successes = Chunk.from((1 to after).map(n => Success(n.toString): Request))
                        val fail      = Fail(message, status.getCode.value)
                        val requests  = Stream(Emit.value(successes.append(fail)))
                        val expected  = status.asException(trailers)
                        for
                            client <- createClientAndServer
                            result <- Abort.run[StatusException](client.manyToOne(requests))
                        yield assertStatusException(result, expected)
                        end for
                    }
                }
            }
        }

        "panic" - {
            "first element" in run {
                val message   = "Oh no!"
                val panic     = Panic(message)
                val successes = Chunk.from((1 to 5).map(n => Success(n.toString): Request))
                val requests  = Stream(Emit.value(Chunk(panic).concat(successes)))
                val expected  = Status.UNKNOWN.asException(trailers)
                for
                    client <- createClientAndServer
                    result <- Abort.run[StatusException](client.manyToOne(requests))
                yield
                    assertStatusException(result, expected)
                    // Do not expose the internal error message
                    val actual = result.failure.get
                    assert(actual.getMessage === "UNKNOWN")
                    assert(actual.getCause === null)
                    assert(actual.getStatus().getCause === null)
                end for
            }

            "after some elements" in run {
                val message   = "Oh no!"
                val after     = 5
                val panic     = Panic(message)
                val successes = Chunk.from((1 to after).map(n => Success(n.toString): Request))
                val requests  = Stream(Emit.value(successes.append(panic)))
                val expected  = Status.UNKNOWN.asException(trailers)
                for
                    client <- createClientAndServer
                    result <- Abort.run[StatusException](client.manyToOne(requests))
                yield
                    assertStatusException(result, expected)
                    // Do not expose the internal error message
                    val actual = result.failure.get
                    assert(actual.getMessage === "UNKNOWN")
                    assert(actual.getCause === null)
                    assert(actual.getStatus().getCause === null)
                end for
            }
        }
    }

    "bidirectional streaming" - {
        "empty" in run {
            val successes = Chunk.empty[Request]
            val requests  = Stream(Emit.value(successes))
            for
                client    <- createClientAndServer
                responses <- client.manyToMany(requests).run
            yield assert(responses == Chunk.empty)
            end for
        }

        "success" in run {
            val successes = Chunk.from((1 to 5).map(n => Success(n.toString, count = n - 2): Request))
            val expected  = Chunk.from((3 to 5).flatMap(n => Chunk.from((1 to (n - 2)).map(m => Echo(s"$n $m")))))
            val requests  = Stream(Emit.value(successes))
            for
                client    <- createClientAndServer
                responses <- client.manyToMany(requests).run
            yield assert(responses == expected)
            end for
        }

        "fail" - {
            "producing stream on first element" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val message   = "Yeah nah bro"
                        val status    = code.toStatus.withDescription(message)
                        val fail      = Fail(message, status.getCode.value, outside = true)
                        val successes = Chunk.from((1 to 5).map(n => Success(n.toString, count = 1): Request))
                        val requests  = Stream(Emit.value(Chunk(fail).concat(successes)))
                        val expected  = status.asException(trailers)
                        for
                            client   <- createClientAndServer
                            response <- Abort.run[StatusException](client.manyToMany(requests).take(1).run)
                        yield assertStatusException(response, expected)
                        end for
                    }
                }
            }

            "producing stream after some elements" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val message   = "Yeah nah bro"
                        val status    = code.toStatus.withDescription(message)
                        val after     = 5
                        val successes = Chunk.from((1 to after).map(n => Success(n.toString, count = 1): Request))
                        val fail      = Fail(message, status.getCode.value, outside = true)
                        val requests  = Stream(Emit.value(successes.append(fail)))
                        // For some reason, the trailers are empty here.
                        val expected = status.asException(emptyTrailers)
                        for
                            client            <- createClientAndServer
                            (responses, tail) <- client.manyToMany(requests).splitAt(5)
                            failedResponse    <- Abort.run[StatusException](tail.run)
                        yield
                            assert(responses == Chunk.from((1 to after).map(n => Echo(s"$n 1"))))
                            assertStatusException(failedResponse, expected)
                        end for
                    }
                }
            }

            "first element" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val message   = "Yeah nah bro"
                        val status    = code.toStatus.withDescription(message)
                        val fail      = Fail(message, status.getCode.value)
                        val successes = Chunk.from((1 to 5).map(n => Success(n.toString, count = 1): Request))
                        val requests  = Stream(Emit.value(Chunk(fail).concat(successes)))
                        val expected  = status.asException(trailers)
                        for
                            client <- createClientAndServer
                            result <- Abort.run[StatusException](client.manyToMany(requests).take(1).run)
                        yield assertStatusException(result, expected)
                        end for
                    }
                }
            }

            "after some elements" in {
                forEvery(notOKStatusCodes) { code =>
                    run {
                        val message   = "Yeah nah bro"
                        val status    = code.toStatus.withDescription(message)
                        val after     = 5
                        val successes = Chunk.from((1 to after).map(n => Success(n.toString, count = 1): Request))
                        val fail      = Fail(message, status.getCode.value)
                        val requests  = Stream(Emit.value(successes.append(fail)))
                        // For some reason, the trailers are empty here.
                        val expected = status.asException(emptyTrailers)
                        for
                            client            <- createClientAndServer
                            (responses, tail) <- client.manyToMany(requests).splitAt(5)
                            failedResponse    <- Abort.run[StatusException](tail.run)
                        yield
                            assert(responses == Chunk.from((1 to after).map(n => Echo(s"$n 1"))))
                            assertStatusException(failedResponse, expected)
                        end for
                    }
                }
            }
        }

        "panic" - {
            "producing stream on first element" in {
                run {
                    val message   = "Oh no!"
                    val panic     = Panic(message)
                    val successes = Chunk.from((1 to 5).map(n => Success(n.toString, count = 1): Request))
                    val requests  = Stream(Emit.value(Chunk(panic).concat(successes)))
                    val expected  = Status.UNKNOWN.asException(trailers)
                    for
                        client   <- createClientAndServer
                        response <- Abort.run[StatusException](client.manyToMany(requests).take(1).run)
                    yield
                        assertStatusException(response, expected)
                        // Do not expose the internal error message
                        val actual = response.failure.get
                        assert(actual.getMessage === "UNKNOWN")
                        assert(actual.getCause === null)
                        assert(actual.getStatus().getCause === null)
                    end for
                }
            }

            "producing stream after some elements" in {
                run {
                    val after     = 5
                    val successes = Chunk.from((1 to after).map(n => Success(n.toString, count = 1): Request))
                    val message   = "Oh no!"
                    val panic     = Panic(message)
                    val requests  = Stream(Emit.value(successes.append(panic)))
                    // For some reason, the trailers are empty here?
                    val expected = Status.UNKNOWN.asException(emptyTrailers)
                    for
                        client            <- createClientAndServer
                        (responses, tail) <- client.manyToMany(requests).splitAt(after)
                        failedResponse    <- Abort.run[StatusException](tail.run)
                    yield
                        assert(responses == Chunk.from((1 to after).map(n => Echo(s"$n 1"))))
                        assertStatusException(failedResponse, expected)
                        // Do not expose the internal error message
                        val actual = failedResponse.failure.get
                        assert(actual.getMessage === "UNKNOWN")
                        assert(actual.getCause === null)
                        assert(actual.getStatus().getCause === null)
                    end for
                }
            }

            "first element" in {
                run {
                    val message   = "Oh no!"
                    val panic     = Panic(message)
                    val successes = Chunk.from((1 to 5).map(n => Success(n.toString, count = 1): Request))
                    val requests  = Stream(Emit.value(Chunk(panic).concat(successes)))
                    val expected  = Status.UNKNOWN.asException(trailers)
                    for
                        client <- createClientAndServer
                        result <- Abort.run[StatusException](client.manyToMany(requests).take(1).run)
                    yield
                        assertStatusException(result, expected)
                        // Do not expose the internal error message
                        val actual = result.failure.get
                        assert(actual.getMessage === "UNKNOWN")
                        assert(actual.getCause === null)
                        assert(actual.getStatus().getCause === null)
                    end for
                }
            }

            "after some elements" in {
                run {
                    val after     = 5
                    val successes = Chunk.from((1 to after).map(n => Success(n.toString, count = 1): Request))
                    val message   = "Oh no!"
                    val panic     = Panic(message)
                    val requests  = Stream(Emit.value(successes.append(panic)))
                    // For some reason, the trailers are empty here?
                    val expected = Status.UNKNOWN.asException(emptyTrailers)
                    for
                        client            <- createClientAndServer
                        (responses, tail) <- client.manyToMany(requests).splitAt(5)
                        failedResponse    <- Abort.run[StatusException](tail.run)
                    yield
                        assert(responses == Chunk.from((1 to after).map(n => Echo(s"$n 1"))))
                        assertStatusException(failedResponse, expected)
                        // Do not expose the internal error message
                        val actual = failedResponse.failure.get
                        assert(actual.getMessage === "UNKNOWN")
                        assert(actual.getCause === null)
                        assert(actual.getStatus().getCause === null)
                    end for
                }
            }
        }
    }

    private def assertStatusException(result: Result[StatusException, Any], expected: StatusException) =
        assert(result.isError)
        assert(result.isFailure)
        val actual = result.failure.get
        assert(actual === expected)
    end assertStatusException

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
            Sync.defer(
                ManagedChannelBuilder
                    .forAddress("localhost", port)
                    .usePlaintext()
                    .build()
            )
        ) { channel =>
            Sync.defer(channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS)).unit
        }

    private def findFreePort =
        for
            socket <- Sync.defer(new ServerSocket(0))
            port   <- Sync.ensure(Sync.defer(socket.close()))(socket.getLocalPort)
        yield port

end ServiceTest
