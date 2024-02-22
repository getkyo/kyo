package kyo

import kyo.*

import sttp.client3.*

object Requests:

    abstract class Backend:
        def send[T](r: Request[T, Any]): Response[T] < Fibers

    private val local = Locals.init[Backend](PlatformBackend.default)

    def let[T, S](b: Backend)(v: T < S): T < (IOs & S) =
        local.let(b)(v)

    type BasicRequest = sttp.client3.RequestT[Empty, Either[_, String], Any]

    val basicRequest: BasicRequest =
        sttp.client3.basicRequest.mapResponse {
            case Left(s) =>
                Left(new Exception(s))
            case Right(v) =>
                Right(v)
        }

    def apply[T](f: BasicRequest => Request[Either[_, T], Any]): T < Fibers =
        request(f(basicRequest))

    def request[T](req: Request[Either[_, T], Any]): T < Fibers =
        local.get.map(_.send(req)).map {
            _.body match
                case Left(ex: Throwable) =>
                    IOs.fail[T](ex)
                case Left(ex) =>
                    IOs.fail[T](new Exception("" + ex))
                case Right(value) =>
                    value
        }
end Requests
