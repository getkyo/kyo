package kyo.internal.tasty.binary

/** Thrown when a varint continuation run exceeds the allowed byte count for the target type.
  *
  * Internal sentinel: thrown from a hot decode loop and caught by the adjacent reader, which converts it
  * into a structured `TastyError.CorruptedFile` on the `Abort[TastyError]` row. The exception never crosses
  * a public API boundary, so it deliberately bypasses `KyoException` (which would impose a `Frame` argument
  * and dev-aware message formatting) and uses the `enableSuppression=false, writableStackTrace=false` form to
  * skip stack-trace materialisation on the throw path.
  */
class MalformedVarintException(val byteOffset: Long, msg: String)
    extends RuntimeException(msg, null, false, false)
