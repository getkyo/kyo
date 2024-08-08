package kyo

import sttp.client3.*

object Requests:

    abstract class Backend:
        self =>

        def send[T](r: Request[T, Any]): Response[T] < (Async & Abort[Closed])

        def withMeter(m: Meter)(using Frame): Backend =
            new Backend:
                def send[T](r: Request[T, Any]) =
                    m.run(self.send(r))
    end Backend

    private val local = Local.init[Backend](PlatformBackend.default)

    def let[T, S](b: Backend)(v: T < S)(using Frame): T < (Async & S) =
        local.let(b)(v)

    type BasicRequest = RequestT[Empty, Either[String, String], Any]

    val basicRequest: BasicRequest = sttp.client3.basicRequest

    def apply[E, T](f: BasicRequest => Request[Either[E, T], Any])(using Frame): T < (Async & Abort[E | Closed]) =
        request(f(basicRequest))

    def request[E, T](req: Request[Either[E, T], Any])(using Frame): T < (Async & Abort[E | Closed]) =
        local.use(_.send(req)).map {
            _.body match
                case Left(ex) =>
                    Abort.fail(ex)
                case Right(value) =>
                    value
        }
end Requests
