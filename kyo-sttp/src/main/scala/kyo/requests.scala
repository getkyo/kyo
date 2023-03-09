package kyo

import kyo.KyoApp
import kyo.concurrent.fibers._
import kyo.consoles._
import kyo.core._
import kyo.envs._
import kyo.ios._
import sttp.client3
import sttp.client3.Empty
import sttp.client3.HttpClientFutureBackend
import sttp.client3.Request
import sttp.client3.RequestT
import sttp.client3.Response
import sttp.client3.UriContext
import sttp.client3.basicRequest

import scala.concurrent.Future

object requests {

  type BasicRequest = RequestT[Empty, Either[String, String], Any]

  trait Backend {
    def send[T](r: Request[T, Any]): Response[T] > (IOs | Fibers)
  }

  object Backend {
    given default: Backend = new Backend {
      val backend = HttpClientFutureBackend()
      def send[T](r: Request[T, Any]): Response[T] > (IOs | Fibers) =
        Fibers.join(r.send(backend))
    }
  }

  type Requests = Envs[Backend] | Fibers | IOs

  object Requests {

    def run[T, S](b: Backend)(v: T > (S | Requests)): T > (S | IOs | Fibers) =
      Envs.let(b)(v)

    def run[T, S](v: T > (S | Requests))(using b: Backend): T > (S | IOs | Fibers) =
      run(b)(v)

    def apply[T](req: Request[T, Any]): Response[T] > Requests =
      Envs[Backend](_.send(req))

    def apply[T](f: BasicRequest => Request[T, Any]): Response[T] > Requests =
      apply(f(basicRequest))
  }
}
