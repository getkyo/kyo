package kyo

import sttp.client3.*

opaque type Requests <: Async = Async

object Requests:

    abstract class Backend:
        self =>

        def send[T](r: Request[T, Any]): Response[T] < Async

        def withMeter(m: Meter)(using Frame): Backend =
            new Backend:
                def send[T](r: Request[T, Any]) =
                    m.run(self.send(r))
    end Backend

    private val local = Local.init[Backend](PlatformBackend.default)

    def run[T, S](v: T < (Requests & S))(using Frame): T < (Async & S) =
        v

    def run[T, S](b: Backend)(v: T < (Requests & S))(using Frame): T < (Async & S) =
        local.let(b)(v)

    type BasicRequest = sttp.client3.RequestT[Empty, Either[?, String], Any]

    val basicRequest: BasicRequest =
        sttp.client3.basicRequest.mapResponse {
            case Left(s) =>
                Left(new Exception(s))
            case Right(v) =>
                Right(v)
        }

    def apply[T](f: BasicRequest => Request[Either[?, T], Any])(using Frame): T < Requests =
        request(f(basicRequest))

    def request[T](req: Request[Either[?, T], Any])(using Frame): T < Requests =
        local.use(_.send(req)).map {
            _.body match
                case Left(ex: Throwable) =>
                    IO.fail[T](ex)
                case Left(ex) =>
                    IO.fail[T](new Exception("" + ex))
                case Right(value) =>
                    value
        }
end Requests
