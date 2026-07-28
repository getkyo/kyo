package kyo.net.internal.transport

import kyo.*
import kyo.net.NetConnectException
import kyo.net.NetException
import kyo.net.Test

/** ReadOutcome type-level contract.
  *
  * Each case carries distinct semantics: Bytes delivers data, WouldBlock signals no data ready
  * (not EOF), PeerFin is an orderly peer EOF, LocalShutdown distinguishes a self-inflicted half-
  * close from a peer FIN, CleanClose is a TLS close_notify, and Failed carries a hard error.
  * The tests here prove the structural match is exhaustive and each case is distinguishable.
  */
class ReadOutcomeTest extends Test:

    "ReadOutcome.Bytes carries the delivered span" in {
        val bytes                  = Array[Byte](1, 2, 3)
        val span                   = Span.fromUnsafe(bytes)
        val r                      = ReadOutcome.Bytes(span)
        val ReadOutcome.Bytes(got) = r: @unchecked
        assert(got.toArray.toList == bytes.toList)
        succeed
    }

    "ReadOutcome.WouldBlock is distinct from PeerFin" in {
        val wb  = ReadOutcome.WouldBlock
        val fin = ReadOutcome.PeerFin
        assert(wb != fin)
        succeed
    }

    "ReadOutcome.WouldBlock is not a terminal outcome (re-arm, not EOF)" in {
        val r = ReadOutcome.WouldBlock
        val isTerminal = r match
            case ReadOutcome.WouldBlock => false
            case ReadOutcome.PeerFin | ReadOutcome.LocalShutdown |
                ReadOutcome.CleanClose | ReadOutcome.Failed(_) => true
            case ReadOutcome.Bytes(_) => false
        assert(!isTerminal, "WouldBlock must not be treated as a terminal/EOF outcome")
        succeed
    }

    "ReadOutcome.PeerFin is a terminal outcome" in {
        val r = ReadOutcome.PeerFin
        val isTerminal = r match
            case ReadOutcome.WouldBlock | ReadOutcome.Bytes(_) => false
            case _                                             => true
        assert(isTerminal)
        succeed
    }

    "ReadOutcome.LocalShutdown is distinct from PeerFin" in {
        assert(ReadOutcome.LocalShutdown != ReadOutcome.PeerFin)
        succeed
    }

    "ReadOutcome.CleanClose is distinct from PeerFin and LocalShutdown" in {
        assert(ReadOutcome.CleanClose != ReadOutcome.PeerFin)
        assert(ReadOutcome.CleanClose != ReadOutcome.LocalShutdown)
        succeed
    }

    "ReadOutcome.Failed carries the exception" in {
        val cause                   = NetConnectException("localhost", 8080, "refused")
        val r                       = ReadOutcome.Failed(cause)
        val ReadOutcome.Failed(got) = r: @unchecked
        assert(got eq cause)
        succeed
    }

    "ReadOutcome match is exhaustive over all six cases" in {
        def classify(r: ReadOutcome): String = r match
            case ReadOutcome.Bytes(_)      => "bytes"
            case ReadOutcome.WouldBlock    => "would-block"
            case ReadOutcome.PeerFin       => "peer-fin"
            case ReadOutcome.LocalShutdown => "local-shutdown"
            case ReadOutcome.CleanClose    => "clean-close"
            case ReadOutcome.Failed(_)     => "failed"
        val cases = List(
            ReadOutcome.Bytes(Span.empty[Byte]),
            ReadOutcome.WouldBlock,
            ReadOutcome.PeerFin,
            ReadOutcome.LocalShutdown,
            ReadOutcome.CleanClose,
            ReadOutcome.Failed(NetConnectException("h", 0, "e"))
        )
        val labels = cases.map(classify)
        assert(labels == List("bytes", "would-block", "peer-fin", "local-shutdown", "clean-close", "failed"))
        succeed
    }

end ReadOutcomeTest
