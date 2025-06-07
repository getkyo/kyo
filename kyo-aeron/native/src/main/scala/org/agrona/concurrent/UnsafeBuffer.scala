package org.agrona.concurrent

import org.agrona.DirectBuffer

class UnsafeBuffer(protected[agrona] var buffer: Array[Byte]) extends DirectBuffer:
    def this(capacity: Int) = this(new Array[Byte](capacity))

    override def getBytes(index: Int, dst: Array[Byte]): Unit =
        getBytes(index, dst, 0, dst.length)

    override def putBytes(index: Int, src: Array[Byte]): Unit =
        putBytes(index, src, 0, src.length)

    override def putBytes(index: Int, src: Array[Byte], srcOffset: Int, length: Int): Unit =
        val requiredCapacity = index + length
        if buffer.length < requiredCapacity then
            val newBuffer = new Array[Byte](requiredCapacity)
            System.arraycopy(buffer, 0, newBuffer, 0, buffer.length)
            buffer = newBuffer
        end if
        System.arraycopy(src, srcOffset, buffer, index, length)
    end putBytes
end UnsafeBuffer
