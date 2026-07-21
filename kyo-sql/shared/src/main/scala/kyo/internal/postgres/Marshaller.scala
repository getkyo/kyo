package kyo.internal.postgres

/** Encodes a typed Frontend message into a [[PostgresBufferWriter]].
  *
  * One instance per Frontend message type. Implementations are pure functions: they do not perform I/O and carry no mutable state.
  *
  * The [[write]] method is called once per message; callers are responsible for flushing the buffer afterward. No state is accumulated
  * between calls, each invocation is independent.
  *
  * @tparam T
  *   the Frontend message type this marshaller handles
  */
trait Marshaller[T]:
    def write(msg: T, buf: PostgresBufferWriter): Unit
end Marshaller
