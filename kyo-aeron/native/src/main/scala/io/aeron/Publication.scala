package io.aeron

import io.aeron.logbuffer.BufferClaim

object Publication:
    val BACK_PRESSURED = -1L
    val NOT_CONNECTED  = -2L
    val ADMIN_ACTION   = -3L
    val CLOSED         = -4L
end Publication

class Publication(uri: String, streamId: Int):
    private[aeron] def publish(bytes: Array[Byte]): Unit =
        Aeron.subscriptions
            .getOrElse((uri, streamId), Nil)
            .foreach(_.receive(bytes))

    def isConnected(): Boolean =
        Aeron.subscriptions.get((uri, streamId)).exists(_.nonEmpty)

    def tryClaim(length: Int, bufferClaim: BufferClaim): Long =
        bufferClaim.setPublication(this, length)
        length.toLong

    def close(): Unit = {}
end Publication
