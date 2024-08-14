package kyo

import sttp.client3.*

case class FailedRequest(cause: String) extends Exception

object Request:

    abstract class Backend:
        self =>

        def send[A](r: Request[A, Any]): Response[A] < Async

        def withMeter(m: Meter)(using Frame): Backend =
            new Backend:
                def send[A](r: Request[A, Any]) =
                    m.run(self.send(r))
    end Backend

    private val local = Local.init[Backend](PlatformBackend.default)

    def let[A, S](b: Backend)(v: A < S)(using Frame): A < (Async & S) =
        local.let(b)(v)

    type BasicRequest = RequestT[Empty, Either[FailedRequest, String], Any]

    val basicRequest: BasicRequest = sttp.client3.basicRequest.mapResponse {
        case Left(value)  => Left(FailedRequest(value))
        case Right(value) => Right(value)
    }

    def apply[E, A](f: BasicRequest => Request[Either[E, A], Any])(using Frame): A < (Async & Abort[E]) =
        request(f(basicRequest))

    def request[E, A](req: Request[Either[E, A], Any])(using Frame): A < (Async & Abort[E]) =
        local.use(_.send(req)).map { r =>
            Abort.get(r.body)
        }
end Request
