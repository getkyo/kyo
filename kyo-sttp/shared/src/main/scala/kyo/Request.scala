package kyo

import sttp.client3.*

object Request:

    case class Failed(cause: String) extends Exception

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

    type BasicRequest = RequestT[Empty, Result[Failed, String], Any]

    val basicRequest: BasicRequest = sttp.client3.basicRequest.mapResponse {
        case Left(value)  => Result.fail(Failed(value))
        case Right(value) => Result.success(value)
    }

    def apply[E, T](f: BasicRequest => Request[Result[E, T], Any])(using Frame): T < (Async & Abort[E]) =
        request(f(basicRequest))

    def request[E, T](req: Request[Result[E, T], Any])(using Frame): T < (Async & Abort[E]) =
        local.use(_.send(req)).map { r =>
            Abort.get(r.body)
        }
end Request
