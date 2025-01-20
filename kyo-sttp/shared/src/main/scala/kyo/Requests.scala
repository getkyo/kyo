package kyo

import sttp.client3.*

/** Represents a failed HTTP request.
  *
  * This exception is thrown when an HTTP request fails for any reason.
  *
  * @param cause
  *   A string or throwable describing the reason for the failure
  */
class FailedRequest(cause: Text | Throwable)(using Frame) extends KyoException("Failed http request", cause)

object Requests:

    /** Abstract class representing a backend for sending HTTP requests */
    abstract class Backend:
        self =>

        /** Sends an HTTP request
          *
          * @tparam A
          *   The type of the response body
          * @param r
          *   The request to send
          * @return
          *   The response wrapped in an effect
          */
        def send[A: Flat](r: Request[A, Any]): Response[A] < (Async & Abort[FailedRequest])

        /** Wraps the Backend with a meter
          *
          * @param m
          *   The meter to use
          * @return
          *   A new Backend that uses the meter
          */
        def withMeter(m: Meter)(using Frame): Backend =
            new Backend:
                def send[A: Flat](r: Request[A, Any]) =
                    Abort.run(m.run(self.send(r))).map(r => Abort.get(r.mapFail(FailedRequest(_))))
    end Backend

    /** The default live backend implementation */
    val live: Backend = PlatformBackend.default

    private val local = Local.init[Backend](live)

    /** Executes an effect with a custom backend
      *
      * @param b
      *   The backend to use
      * @param v
      *   The effect to execute
      * @return
      *   The result of the effect
      */
    def let[A, S](b: Backend)(v: A < S)(using Frame): A < (Async & S) =
        local.let(b)(v)

    /** Type alias for a basic request */
    type BasicRequest = RequestT[Empty, Either[FailedRequest, String], Any]

    /** A basic request with error handling */
    val basicRequest: BasicRequest = sttp.client3.basicRequest.mapResponse {
        case Left(value)  => Left(FailedRequest(value)(using Frame.internal))
        case Right(value) => Right(value)
    }

    /** Sends an HTTP request using a function to modify the basic request
      *
      * @tparam E
      *   The error type
      * @tparam A
      *   The success type of the response
      * @param f
      *   A function that takes a BasicRequest and returns a Request
      * @return
      *   The response body wrapped in an effect
      */
    def apply[E, A](f: BasicRequest => Request[Either[E, A], Any])(using Frame): A < (Async & Abort[FailedRequest | E]) =
        request(f(basicRequest))

    /** Sends an HTTP request
      *
      * @tparam E
      *   The error type
      * @tparam A
      *   The success type of the response
      * @param req
      *   The request to send
      * @return
      *   The response body wrapped in an effect
      */
    def request[E, A](req: Request[Either[E, A], Any])(using Frame): A < (Async & Abort[FailedRequest | E]) =
        local.use(_.send(req)).map { r =>
            Abort.get(r.body)
        }
end Requests
