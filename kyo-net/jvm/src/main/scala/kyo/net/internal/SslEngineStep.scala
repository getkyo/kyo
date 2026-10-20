package kyo.net.internal

import javax.net.ssl.SSLEngineResult

/** What one `SSLEngine.wrap` or `unwrap` call did: the one classification every JDK engine loop ([[NioTlsState]], [[JdkSslEngine]]) stops
  * on.
  *
  * A JDK engine reports a call that can never move again as an ordinary result, so a loop that retries on the status alone spins. A wrap
  * on a closed outbound side returns `CLOSED` with nothing consumed or produced on every call, and an unwrap that waits on a delegated task
  * returns `OK` with nothing consumed or produced on every call. A loop calls the engine again only on [[Progress]].
  */
private[net] enum SslEngineStep derives CanEqual:

    /** The call consumed or produced bytes, and the engine may move again. */
    case Progress

    /** The unwrap needs more ciphertext than is buffered: the ordinary want-read. */
    case Underflow

    /** The call consumed and produced nothing, and calling again cannot change that. */
    case NoProgress

    /** The engine's side is closed: the unwrap consumed the peer's close_notify, or the wrap found this side's outbound closed. */
    case Closed

    /** The engine waits on a delegated task. After the handshake that is a renegotiation, which the transport does not run. */
    case NeedTask
end SslEngineStep

private[net] object SslEngineStep:

    // Java enums have no Scala CanEqual, so the statuses are compared by reference.
    def of(result: SSLEngineResult): SslEngineStep =
        val status = result.getStatus
        if status eq SSLEngineResult.Status.CLOSED then Closed
        else if result.getHandshakeStatus eq SSLEngineResult.HandshakeStatus.NEED_TASK then NeedTask
        else if status eq SSLEngineResult.Status.BUFFER_UNDERFLOW then Underflow
        else if result.bytesConsumed() > 0 || result.bytesProduced() > 0 then Progress
        else NoProgress
        end if
    end of

end SslEngineStep
