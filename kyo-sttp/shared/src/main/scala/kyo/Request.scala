package kyo

import sttp.client3.*

case class FailedRequest(cause: String) extends Exception

object Request:

    abstract class Backend:
        self =>

        def send[T](r: Request[T, Any]): Response[T] < Async

        def withMeter(m: Meter)(using Frame): Backend =
            new Backend:
                def send[T](r: Request[T, Any]) =
                    m.run(self.send(r))
    end Backend

    private val local = Local.init[Backend](PlatformBackend.default)

    def let[T, S](b: Backend)(v: T < S)(using Frame): T < (Async & S) =
        local.let(b)(v)

    type BasicRequest = RequestT[Empty, Either[FailedRequest, String], Any]

    val basicRequest: BasicRequest = sttp.client3.basicRequest.mapResponse {
        case Left(value)  => Left(FailedRequest(value))
        case Right(value) => Right(value)
    }

    def apply[E, T](f: BasicRequest => Request[Either[E, T], Any])(using Frame): T < (Async & Abort[E]) =
        request(f(basicRequest))

    def request[E, T](req: Request[Either[E, T], Any])(using Frame): T < (Async & Abort[E]) =
        local.use(_.send(req)).map { r =>
            Abort.get(r.body)
        }
end Request
