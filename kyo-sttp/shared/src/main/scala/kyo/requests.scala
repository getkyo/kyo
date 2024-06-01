package kyo

import kyo.*
import kyo.internal.Trace
import sttp.client3.*

opaque type Requests <: Fibers = Fibers

object Requests:

    abstract class Backend:
        self =>

        def send[T](r: Request[T, Any]): Response[T] < Fibers

        def withMeter(m: Meter)(using Trace): Backend =
            new Backend:
                def send[T](r: Request[T, Any]) =
                    m.run(self.send(r))
    end Backend

    private val local = Locals.init[Backend](PlatformBackend.default)

    def run[T, S](v: T < (Requests & S))(using Trace): T < (Fibers & S) =
        v

    def run[T, S](b: Backend)(v: T < (Requests & S))(using Trace): T < (Fibers & S) =
        local.let(b)(v)

    type BasicRequest = sttp.client3.RequestT[Empty, Either[?, String], Any]

    val basicRequest: BasicRequest =
        sttp.client3.basicRequest.mapResponse {
            case Left(s) =>
                Left(new Exception(s))
            case Right(v) =>
                Right(v)
        }

    def apply[T](f: BasicRequest => Request[Either[?, T], Any])(using Trace): T < Requests =
        request(f(basicRequest))

    def request[T](req: Request[Either[?, T], Any])(using Trace): T < Requests =
        local.use(_.send(req)).map {
            _.body match
                case Left(ex: Throwable) =>
                    IOs.fail[T](ex)
                case Left(ex) =>
                    IOs.fail[T](new Exception("" + ex))
                case Right(value) =>
                    value
        }
end Requests
