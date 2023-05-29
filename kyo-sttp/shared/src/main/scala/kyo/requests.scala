package kyo

import kyo.KyoApp
import kyo.concurrent.fibers._
import kyo.consoles._
import kyo._
import kyo.envs._
import kyo.ios._
import sttp.client3
import sttp.client3.Empty
import sttp.client3.Request
import sttp.client3.RequestT
import sttp.client3.Response
import sttp.client3.UriContext
import sttp.client3.basicRequest

import scala.concurrent.Future

object requests {

  type BasicRequest = RequestT[Empty, Either[String, String], Any]

  trait Backend {
    def send[T](r: Request[T, Any]): Fiber[Response[T]] > IOs
  }

  object Backend {
    given default: Backend = new Backend {
      val backend = PlatformBackend.instance
      def send[T](r: Request[T, Any]): Fiber[Response[T]] > IOs =
        Fibers.joinFiber(r.send(backend))
    }
  }

  type Requests = Envs[Backend] with Fibers with IOs

  object Requests {

    def run[T, S](b: Backend)(v: T > (Requests with S)): T > (S with IOs with Fibers) =
      Envs[Backend].run(b)(v)

    def run[T, S](v: T > (Requests with S))(implicit b: Backend): T > (S with IOs with Fibers) =
      run(b)(v)

    def apply[T, S](req: Request[T, Any] > S): Response[T] > (Requests with S) =
      fiber(req).map(_.join)

    def fiber[T, S](req: Request[T, Any] > S): Fiber[Response[T]] > (Requests with S) =
      Envs[Backend].get.map(b => req.map(b.send))

    def apply[T, S](f: BasicRequest => Request[T, Any] > S): Response[T] > (Requests with S) =
      fiber(f).map(_.join)

    def fiber[T, S](f: BasicRequest => Request[T, Any] > S)
        : Fiber[Response[T]] > (Requests with S) =
      fiber(f(basicRequest))
  }
}
