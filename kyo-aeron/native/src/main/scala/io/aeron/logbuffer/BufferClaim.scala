package io.aeron.logbuffer

import io.aeron.Publication
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer

class BufferClaim:
    private val _buffer: MutableDirectBuffer = DirectBuffer(1500)
    private var publication: Publication     = null
    private var length: Int                  = 0

    private[aeron] def setPublication(pub: Publication, length: Int): Unit =
        this.publication = pub
        this.length = length

    def buffer(): MutableDirectBuffer = this._buffer

    def offset(): Int = 0

    def commit(): Unit =
        if publication != null then
            val bytes = new Array[Byte](length)
            _buffer.getBytes(0, bytes)
            publication.publish(bytes)
end BufferClaim
