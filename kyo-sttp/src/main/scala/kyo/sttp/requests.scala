package kyo.sttp

import kyo.concurrent.fibers._
import kyo.core._
import kyo.ios._
import kyo.consoles._
import sttp.client3
import sttp.client3.Empty
import sttp.client3.HttpClientFutureBackend
import sttp.client3.Request
import sttp.client3.RequestT
import sttp.client3.Response
import sttp.client3.basicRequest
import sttp.client3.UriContext
import scala.concurrent.Future
import kyo.KyoApp

object requests {

  // TODO fiber-based backend
  private val backend = HttpClientFutureBackend()

  type BasicRequest = RequestT[Empty, Either[String, String], Any]

  def apply[T](f: BasicRequest => Request[T, Any]): Response[T] > (IOs | Fibers) =
    Fibers.join(f(basicRequest).send(backend))
}

object test extends KyoApp {
  import kyo._
  import kyo.concurrent._

  def run(args: List[String]) =
    for {
      _        <- Consoles.println("Enter your name:")
      name     <- Consoles.readln
      _        <- Consoles.println(s"Hello, $name! What's your URL?")
      url      <- Consoles.readln
      response <- requests(_.get(uri"$url"))
      _        <- Consoles.println(response.body.toString())
    } yield ()
}
