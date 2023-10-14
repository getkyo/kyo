package kyo.server

import kyo._
import kyo.ios._
import kyo.routes._
import kyo.tries._
import kyo.concurrent.fibers._
import kyo.server.internal._
import kyo.server.internal.KyoMonadError._
import sttp.monad.MonadError
import sttp.monad.syntax._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.RequestResult
import sttp.tapir.server.interceptor.reject.RejectInterceptor
import sttp.tapir.server.interpreter.{BodyListener, FilterServerEndpoints, ServerInterpreter}
import sttp.tapir.server.netty.internal.{NettyBodyListener, RunAsync, _}
import sttp.tapir.server.netty.{NettyResponse, NettyServerRequest, Route}

trait NettyKyoServerInterpreter {
  def nettyServerOptions: NettyKyoServerOptions

  def toRoute(ses: List[ServerEndpoint[Any, kyo.routes.internal.M]])
      : Route[kyo.routes.internal.M] = {

    val runAsync = new RunAsync[kyo.routes.internal.M] {
      override def apply[T](f: => T > (Fibers with IOs)): Unit =
        App.run(Fibers.forkFiber {
          App.run(f)
          ()
        })
    }
    implicit val bodyListener: BodyListener[internal.M, NettyResponse] = {
      new NettyBodyListener(runAsync)
    }

    val interceptors = nettyServerOptions.interceptors
    val createFile   = nettyServerOptions.createFile
    val deleteFile   = nettyServerOptions.deleteFile

    val serverInterpreter = new ServerInterpreter[Any, kyo.routes.internal.M, NettyResponse, Any](
        FilterServerEndpoints(ses),
        new NettyKyoRequestBody(createFile),
        new NettyKyoToResponseBody(
            delegate = new NettyToResponseBody
        ),
        RejectInterceptor.disableWhenSingleEndpoint(interceptors, ses),
        deleteFile
    )

    val handler: Route[kyo.routes.internal.M] = { (request: NettyServerRequest) =>
      serverInterpreter(request)
        .map {
          case RequestResult.Response(response) => Some(response)
          case RequestResult.Failure(_)         => None
        }
    }

    handler
  }
}

object NettyKyoServerInterpreter {
  def apply(): NettyKyoServerInterpreter = {
    new NettyKyoServerInterpreter {
      override def nettyServerOptions: NettyKyoServerOptions =
        NettyKyoServerOptions.default()
    }
  }
  def apply(options: NettyKyoServerOptions): NettyKyoServerInterpreter = {
    new NettyKyoServerInterpreter {
      override def nettyServerOptions: NettyKyoServerOptions = options
    }
  }
}
