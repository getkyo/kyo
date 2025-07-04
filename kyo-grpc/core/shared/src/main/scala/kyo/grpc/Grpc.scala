package kyo.grpc

import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kyo.*
import scala.concurrent.Future

/**
 * Effect of sending or receiving a gRPC message.
 * 
 * Service method implementations will be [[Async]] effects that either succeed with some `Response` or terminate early with a [[GrpcFailure]]. For example:
 * {{{
 *    Abort.fail(Status.INVALID_ARGUMENT.withDescription("Id cannot be empty.").asException)
 * }}}
 * 
 * Clients will typically handle the effect of calling a gRPC method using functions such as [[Abort.run]].
 */
type Grpc = Async & Abort[GrpcFailure]

/**
 * A failure that occurred while sending or receiving a gRPC message.
 * 
 * These are typically created from a [[io.grpc.Status]] via `asException`.
 * 
 * @see [[StatusException]]
 */
type GrpcFailure = StatusException

object Grpc:

    /*
    * Creates a computation pending the [[Grpc]] effect from a [[Future]].
    * 
    * If the `Future` fails with a [[StatusException]] then the computation will fail with a [[StatusException]]. A [[StatusRuntimeException]] will be converted to a [[StatusException]].
    * 
    * If the `Future`` fails with some other `Throwable` then the computation will panic with that `Throwable`.
    * 
      * @param f
      *   The `Future` that produces the computation result
      * @tparam A
      *   The type of the successful result
      * @return
      *   A computation that completes with the result of the Future
    */
    def fromFuture[A](f: Future[A])(using Frame): A < Grpc =
        Fiber.fromFuture(f).map(_.getResult).map:
            case Result.Success(a: A) => a
            case Result.Error(ex: StatusException) => Abort.fail(ex)
            case Result.Error(ex: StatusRuntimeException) => Abort.fail(StreamNotifier.throwableToStatusException(ex))
            case Result.Error(t) => Abort.panic(t)

end Grpc
