package kyo.net.internal

/** Named state for one step of a TLS handshake, replacing raw integer codes in the posix engine
  * driver so call sites carry a typed value instead of a literal integer.
  *
  *   - [[WantRead]]: engine code `0`, more ciphertext needed from the peer before the next step.
  *   - [[WantWrite]]: engine code `-1`, ciphertext has been queued; drain and send it, then re-step.
  *   - [[Done]]: engine code `1`, attach the engine and start the pumps.
  *   - [[Failed]]: engine code `-2`, the engine returned a fatal-error status.
  */
private[kyo] enum HandshakeState derives CanEqual:
    case WantRead
    case WantWrite
    case Done
    case Failed(reason: HandshakeFailure)
end HandshakeState

private[kyo] object HandshakeState:
    /** Map a raw integer code from [[kyo.net.internal.TlsEngine.handshakeStep]] to a typed state.
      *
      * Code contract (matches `TlsEngine.handshakeStep` scaladoc): `1` done, `0` want-read, `-1` want-write, `-2` fatal error. Any other
      * value is treated as [[WantRead]] (safe: the engine will emit a definite terminal code on the next step).
      */
    def fromCode(code: Int): HandshakeState = code match
        case 1  => Done
        case -2 => Failed(HandshakeFailure.EngineError)
        case -1 => WantWrite
        case _  => WantRead
    end fromCode
end HandshakeState

/** Why a handshake failed, carried in [[HandshakeState.Failed]] so the failure is a typed value the
  * driver surfaces to the connection's pending promise rather than a swallowed throw.
  *
  *   - [[EngineError]]: the engine returned `-2` (a fatal-error status, normal alert-record outcome).
  *   - [[DeadlineReaped]]: the handshake deadline fired before completion (slowloris guard, R-028).
  *   - [[EngineThrew]]: the engine threw an unexpected exception during a handshake step.
  */
private[kyo] enum HandshakeFailure derives CanEqual:
    case EngineError
    case DeadlineReaped
    case EngineThrew(cause: Throwable)
end HandshakeFailure
