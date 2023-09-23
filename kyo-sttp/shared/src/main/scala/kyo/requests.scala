package kyo

import kyo._
import kyo.concurrent.fibers._
import kyo.consoles._
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

  abstract class Backend {
    def send[T](r: Request[T, Any]): Fiber[Response[T]] > IOs
  }

  object Backend {
    implicit val default: Backend =
      new Backend {
        val backend = PlatformBackend.instance
        def send[T](r: Request[T, Any]): Fiber[Response[T]] > IOs =
          Fibers.joinFiber(r.send(backend))
      }
  }

  type Requests = Envs[Backend] with Fibers with IOs

  object Requests {

    private val envs = Envs[Backend]

    def run[T, S](b: Backend)(v: T > (Requests with S)): T > (Fibers with IOs with S) =
      envs.run[T, Fibers with IOs with S](b)(v)

    def run[T, S](v: T > (Requests with S))(implicit b: Backend): T > (Fibers with IOs with S) =
      run[T, S](b)(v)

    def apply[T, S](req: Request[T, Any] > S): Response[T] > (Requests with S) =
      fiber(req).map(_.get)

    def fiber[T, S](req: Request[T, Any] > S): Fiber[Response[T]] > (Requests with S) =
      envs.get.map(b => req.map(b.send))

    def apply[T, S](f: BasicRequest => Request[T, Any] > S): Response[T] > (Requests with S) =
      fiber(f).map(_.get)

    def fiber[T, S](f: BasicRequest => Request[T, Any] > S)
        : Fiber[Response[T]] > (Requests with S) =
      fiber(f(basicRequest))

    def parallel[T1, T2](
        v1: => T1 > Requests,
        v2: => T2 > Requests
    ): (T1, T2) > Requests =
      parallel(List(IOs(v1), IOs(v2))).map(s => (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2]))

    def parallel[T1, T2, T3](
        v1: => T1 > Requests,
        v2: => T2 > Requests,
        v3: => T3 > Requests
    ): (T1, T2, T3) > Requests =
      parallel(List(IOs(v1), IOs(v2), IOs(v3))).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3])
      )

    def parallel[T1, T2, T3, T4](
        v1: => T1 > Requests,
        v2: => T2 > Requests,
        v3: => T3 > Requests,
        v4: => T4 > Requests
    ): (T1, T2, T3, T4) > Requests =
      parallel(List(IOs(v1), IOs(v2), IOs(v3), IOs(v4))).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3], s(3).asInstanceOf[T4])
      )

    def parallel[T](l: List[T > Requests]): Seq[T] > Requests =
      Fibers.get(parallelFiber(l))

    def parallelFiber[T](l: List[T > Requests]): Fiber[Seq[T]] > Requests =
      envs.get.map { b =>
        Fibers.parallelFiber(l.map(envs.run(b)))
      }
  }
}
