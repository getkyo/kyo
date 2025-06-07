package org.agrona

import java.nio.ByteBuffer

// org.agrona.DirectBuffer is an interface in Java.
// We'll stub it as a trait in Scala.
trait DirectBuffer:
    protected[agrona] def buffer: Array[Byte]
    def capacity: Int = buffer.length
    def getBytes(index: Int, dst: Array[Byte], dstOffset: Int, length: Int): Unit =
        System.arraycopy(buffer, index, dst, dstOffset, length)
    def getBytes(index: Int, dst: Array[Byte]): Unit = getBytes(index, dst, 0, dst.length)

    // Method used in Topic.scala: buffer.putBytes(offset, bytes)
    // This likely maps to putBytes(int index, byte[] src, int srcOffset, int length)
    // where srcOffset is 0 and length is src.length.
    def putBytes(index: Int, src: Array[Byte], srcOffset: Int, length: Int): Unit = ???

    // Overload to match usage: buffer.putBytes(offset, bytes)
    def putBytes(index: Int, src: Array[Byte]): Unit = putBytes(index, src, 0, src.length)

    // Other methods from DirectBuffer are not directly called in Topic.scala on DirectBuffer instances,
    // but might be relevant for a complete stub or if other parts of Aeron API rely on them.
    // For now, these cover the direct usage.
end DirectBuffer

private[agrona] class BackingBuffer(protected[agrona] val buffer: Array[Byte]) extends MutableDirectBuffer:
    def this(capacity: Int) = this(new Array[Byte](capacity))
    override def putBytes(index: Int, src: Array[Byte], srcOffset: Int, length: Int): Unit =
        System.arraycopy(src, srcOffset, buffer, index, length)
end BackingBuffer

object DirectBuffer:
    def apply(bytes: Array[Byte]): DirectBuffer   = new BackingBuffer(bytes)
    def apply(capacity: Int): MutableDirectBuffer = new BackingBuffer(capacity)
