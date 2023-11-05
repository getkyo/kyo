package kyo

import kyo._
import kyo.concurrent.fibers._
import kyo.consoles._
import kyo.envs._
import kyo.ios._
import sttp.client3._
import sttp.client3.Empty
import sttp.client3.Request
import sttp.client3.RequestT
import sttp.client3.Response
import sttp.client3.UriContext
import sttp.client3.basicRequest

import scala.concurrent.Future
import kyo.tries.Tries
import sttp.client3.SttpBackend
import kyo.internal.KyoSttpMonad

object requests {

  abstract class Backend {
    def send[T](r: Request[T, Any]): Response[T] > (Fibers with IOs)
  }

  object Backend {
    implicit val default: Backend = PlatformBackend.default
  }

  type Requests >: Requests.Effects <: Requests.Effects

  object Requests {

    type Effects = Envs[Backend] with Fibers with IOs with Tries

    private val envs = Envs[Backend]

    def run[T, S](b: Backend)(v: T > (Requests with S)): T > (Fibers with IOs with Tries with S) =
      Tries(envs.run[T, Fibers with IOs with Tries with S](b)(v))

    def run[T, S](v: T > (Requests with S))(implicit
        b: Backend
    ): T > (Fibers with IOs with Tries with S) =
      run[T, S](b)(v)

    type SeedRequest = RequestT[Empty, Either[_, String], Any]

    private val SeedRequest: SeedRequest =
      basicRequest.mapResponse {
        case Left(s) =>
          Left(new Exception(s))
        case Right(v) =>
          Right(v)
      }

    // def apply[T, S](req: Request[T, Any] > S): Response[T] > (Requests with S) =
    //   envs.get.map(b => req.map(b.send))

    def apply[T](f: SeedRequest => Request[Either[_, T], Any]): T > Requests =
      envs.get.map(b => b.send(f(SeedRequest))).map {
        _.body match {
          case Left(ex: Throwable) =>
            IOs.fail[T](ex)
          case Left(ex) =>
            IOs.fail[T](new Exception("" + ex))
          case Right(value) =>
            value
        }
      }
  }
}
