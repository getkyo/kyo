package kyo.internal.tasty.binary

/** Thrown when a varint continuation run exceeds the allowed byte count for the target type. */
class MalformedVarintException(val byteOffset: Long, msg: String) extends RuntimeException(msg)
