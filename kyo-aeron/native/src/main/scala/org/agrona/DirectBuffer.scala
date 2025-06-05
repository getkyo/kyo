package org.agrona

// org.agrona.DirectBuffer is an interface in Java.
// We'll stub it as a trait in Scala.
trait DirectBuffer:
    // Method used in Topic.scala: buffer.getBytes(offset, bytes)
    // This likely maps to getBytes(int index, byte[] dst, int dstOffset, int length)
    // where dstOffset is 0 and length is dst.length.
    def getBytes(index: Int, dst: Array[Byte], dstOffset: Int, length: Int): Unit = ???

    // Overload to match usage: buffer.getBytes(offset, bytes)
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
