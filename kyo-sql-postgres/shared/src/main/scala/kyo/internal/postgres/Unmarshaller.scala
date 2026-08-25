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

object Unmarshaller:

    /** An [[Unmarshaller]] for an empty-body message: `read` never consults the reader, `value` is returned unconditionally.
      *
      * Every zero-payload Backend message (`BindComplete`, `CloseComplete`, `CopyDone`, `NoData`, `ParseComplete`, `PortalSuspended`, ...)
      * shares this shape: the framing (tag byte + `Int32(4)` length) already consumed the whole message before an unmarshaller ever sees
      * it, so there is nothing left to parse. [[kyo.internal.postgres.MessageReader]] inlines the same idea for `EmptyQueryResponse`,
      * which carries no dedicated unmarshaller at all.
      */
    class Const[T](value: T) extends Unmarshaller[T]:
        def read(buf: PostgresBufferReader)(using Frame): T < Abort[SqlDecodeException] = value

end Unmarshaller
