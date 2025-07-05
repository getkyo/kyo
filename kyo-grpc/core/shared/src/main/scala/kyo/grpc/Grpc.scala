package kyo.grpc

import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kyo.*
import scala.concurrent.Future

/** Effect of sending or receiving a gRPC message.
  *
  * Service method implementations will be [[Async]] effects that either succeed with some `Response` or terminate early with a
  * [[GrpcFailure]]. For example:
  * {{{
  *    Abort.fail(Status.INVALID_ARGUMENT.withDescription("Id cannot be empty.").asException)
  * }}}
  *
  * Clients will typically handle the effect of calling a gRPC method using functions such as [[Abort.run]].
  */
type Grpc = Async & Abort[GrpcFailure]

object Grpc:

    /** Creates a computation pending the [[Grpc]] effect from a [[Future]].
      *
      * If the `Future` fails with an exception, it will be converted to a [[GrpcFailure]] using [[GrpcFailure.fromThrowable]] and the
      * computation will abort.
      *
      * @param f
      *   The `Future` that produces the computation result
      * @tparam A
      *   The type of the successful result
      * @return
      *   A computation that completes with the result of the Future
      */
    def fromFuture[A](f: Future[A])(using Frame): A < Grpc =
        Fiber.fromFuture(f)
            .map(_.getResult)
            .map(_.mapError(e => GrpcFailure.fromThrowable(e.failureOrPanic)))
            .map(Abort.get)

end Grpc
