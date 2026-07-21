package kyo.internal.mysql

/** Encodes a typed Frontend message into a [[MysqlBufferWriter]].
  *
  * One instance per Frontend message type. Implementations are pure functions: they do not perform I/O and carry no mutable state.
  *
  * @tparam T
  *   the Frontend message type this marshaller handles
  */
trait Marshaller[T]:
    def write(msg: T, buf: MysqlBufferWriter): Unit
end Marshaller
