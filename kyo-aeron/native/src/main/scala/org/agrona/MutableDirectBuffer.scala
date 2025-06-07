package org.agrona

trait MutableDirectBuffer extends DirectBuffer:
    override def putBytes(index: Int, src: Array[Byte], srcOffset: Int, length: Int): Unit
    override def putBytes(index: Int, src: Array[Byte]): Unit = putBytes(index, src, 0, src.length)
end MutableDirectBuffer
