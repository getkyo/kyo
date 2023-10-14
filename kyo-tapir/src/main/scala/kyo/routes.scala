package kyo

import kyo._
import kyo.ios._
import kyo.sums._
import kyo.envs._
import kyo.tries._
import kyo.aborts._
import kyo.server._
import kyo.concurrent.fibers._
import kyo.concurrent.timers._

import sttp.tapir._
import sttp.tapir.server.ServerEndpoint
import kyo.App.Effects

object test extends App {
  import scala.concurrent.duration._
  import routes._

  def run(args: List[String]) = {
    Routes.run(NettyKyoServer())(Routes.add[String, String, Any](_.get
      .in("hello").in(query[String]("name"))
      .out(stringBody)) { name =>
      Timers.run(Fibers.delay(1.second)(s"Hello, $name!"))
    }).unit
  }
}

object routes {

  import internal._

  type Route[+T] = ServerEndpoint[Any, internal.M]

  type Routes = Sums[List[Route[Any]]] with Fibers with IOs

  object Routes {

    private val sums = Sums[List[Route[Any]]]

    def run[T, S](v: Unit > (Routes with S)): NettyKyoServerBinding > (Fibers with IOs with S) =
      run[T, S](NettyKyoServer())(v)

    def run[T, S](server: NettyKyoServer)(v: Unit > (Routes with S))
        : NettyKyoServerBinding > (Fibers with IOs with S) =
      sums.run[NettyKyoServerBinding, Fibers with IOs with S] {
        v.map { r =>
          sums.get.map(server.addEndpoints(_)).map(_.start())
        }
      }

    def add[T, U, E, S](e: Endpoint[Unit, T, Unit, U, Unit])(
        f: T => U > (Fibers with IOs)
    ): Unit > Routes =
      sums.add(List(
          e.serverLogic[internal.M](f(_).map(Right(_))).asInstanceOf[Route[Any]]
      )).unit

    def add[T, U, S](
        e: PublicEndpoint[Unit, Unit, Unit, Any] => Endpoint[Unit, T, Unit, U, Any]
    )(
        f: T => U > (Fibers with IOs)
    ): Unit > Routes =
      add(e(endpoint))(f)
  }

  object internal {
    type M[T] = T > Fibers with IOs
  }
}
