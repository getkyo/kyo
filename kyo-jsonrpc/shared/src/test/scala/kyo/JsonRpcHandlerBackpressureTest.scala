package kyo

/** Regression for silent response drop under writer backpressure.
  *
  * The bounded outbound `writerChannel` can fill (a progress flood outpacing the drain); a required response must
  * backpressure onto it, not drop, or the caller waits forever under the default `requestTimeout = Infinity`.
  */
class JsonRpcHandlerBackpressureTest extends JsonRpcTest:

    case class EchoReq(n: Int) derives Schema, CanEqual
    case class EchoResp(n: Int) derives Schema, CanEqual

    /** Blocks every send on a gate until the test opens it, stalling the endpoint's writer loop so its bounded
      * writerChannel fills behind the first in-flight send.
      */
    private class GatedTransport(inner: JsonRpcTransport, gate: Latch) extends JsonRpcTransport:
        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            gate.await.andThen(inner.send(env))
        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] = inner.incoming
        def close(using Frame): Unit < Async                                      = inner.close
    end GatedTransport

    "responses backpressure onto a full writer channel instead of being dropped" in {
        val n = 100 // > the 64-slot writerChannel, so responses overflow while the writer is gated
        // Finite caller timeout so a dropped response surfaces as a typed abort instead of an infinite hang.
        val callerConfig = JsonRpcHandler.Config().requestTimeout(15.seconds)
        for
            ran  <- Latch.init(n) // counts down as each handler runs; all-ran => all responses attempted
            gate <- Latch.init(1) // holds B's outbound send shut until we open it
            echo = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
                (req, _) => ran.release.andThen(EchoResp(req.n))
            }
            result <-
                JsonRpcTransport.inMemory.map { (ta, tb) =>
                    val gatedTb = new GatedTransport(tb, gate)
                    JsonRpcHandler.init(ta, Seq.empty, callerConfig).map { a =>
                        JsonRpcHandler.init(gatedTb, Seq(echo)).map { _ =>
                            val calls =
                                Async.foreach(1 to n, n) { i =>
                                    Abort.run[JsonRpcError | Closed](a.call[EchoReq, EchoResp]("echo", EchoReq(i)))
                                }
                            Fiber.initUnscoped(calls).map { callsFib =>
                                // Every handler ran => the writer channel has filled and overflowed behind the
                                // gated send; only then open the gate so the writer can drain and deliver.
                                ran.await.andThen(gate.release).andThen(callsFib.get)
                            }
                        }
                    }
                }
        yield
            val delivered = result.collect { case Result.Success(v) => v }
            assert(
                delivered.size == n,
                s"expected all $n responses delivered, got ${delivered.size} (dropped ${n - delivered.size})"
            )
            assert(delivered.toSet == (1 to n).map(EchoResp(_)).toSet)
        end for
    }
end JsonRpcHandlerBackpressureTest
