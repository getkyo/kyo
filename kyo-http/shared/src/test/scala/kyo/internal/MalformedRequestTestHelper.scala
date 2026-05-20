package kyo.internal

import kyo.*
import kyo.internal.transport.*

/** Helper that sends garbage bytes to a server port using Transport. */
private[kyo] object MalformedRequestTestHelper:

    /** Connect to host:port, send garbage bytes, then close. */
    def sendGarbage(host: String, port: Int)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            val transport = HttpPlatformTransport.transport
            val fiber     = transport.connect(host, port)
            Abort.run[Closed](fiber.safe.get).map {
                case Result.Success(conn3) =>
                    val garbage = Span.fromUnsafe("GARBAGE\r\nNOT HTTP\r\n\r\n".getBytes("UTF-8"))
                    Abort.run[Closed](conn3.outbound.safe.put(garbage)).unit
                        .andThen(Async.sleep(100.millis))
                        .andThen(Sync.Unsafe.defer(conn3.close()))
                case _ => Kyo.unit // connection failed, nothing to do
            }
        }

end MalformedRequestTestHelper
