package kyo.internal.postgres

import kyo.*

/** Decodes a typed Backend message from a [[PostgresBufferReader]].
  *
  * One instance per Backend message type (or per discriminated group, such as all `Authentication*` variants). Implementations are pure
  * functions: they do not perform I/O and carry no mutable state.
  *
  * A failed decode should `Abort.fail` with a [[SqlDecodeException]] leaf rather than throw.
  *
  * @tparam T
  *   the Backend message type this unmarshaller produces
  */
trait Unmarshaller[T]:
    def read(buf: PostgresBufferReader)(using Frame): T < Abort[SqlDecodeException]
end Unmarshaller
