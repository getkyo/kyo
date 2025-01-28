package kyo.offheap

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

trait Layout[A]:
    def get(ptr: Ptr[Byte], offset: Long): A
    def set(ptr: Ptr[Byte], offset: Long, value: A): Unit
    def size: Long
    def alignment: Int
end Layout

object Layout:
    given Layout[Byte] with
        def get(ptr: Ptr[Byte], offset: Long): Byte =
            !(ptr + offset)
        def set(ptr: Ptr[Byte], offset: Long, value: Byte): Unit =
            !(ptr + offset) = value
        def size: Long     = 1L
        def alignment: Int = 1
    end given

    given Layout[Short] with
        def get(ptr: Ptr[Byte], offset: Long): Short =
            !(ptr.asInstanceOf[Ptr[Short]] + (offset >> 1))
        def set(ptr: Ptr[Byte], offset: Long, value: Short): Unit =
            !(ptr.asInstanceOf[Ptr[Short]] + (offset >> 1)) = value
        def size: Long     = 2L
        def alignment: Int = 2
    end given

    given Layout[Int] with
        def get(ptr: Ptr[Byte], offset: Long): Int =
            !(ptr.asInstanceOf[Ptr[Int]] + (offset >> 2))
        def set(ptr: Ptr[Byte], offset: Long, value: Int): Unit =
            !(ptr.asInstanceOf[Ptr[Int]] + (offset >> 2)) = value
        def size: Long     = 4L
        def alignment: Int = 4
    end given

    given Layout[Long] with
        def get(ptr: Ptr[Byte], offset: Long): Long =
            !(ptr.asInstanceOf[Ptr[Long]] + (offset >> 3))
        def set(ptr: Ptr[Byte], offset: Long, value: Long): Unit =
            !(ptr.asInstanceOf[Ptr[Long]] + (offset >> 3)) = value
        def size: Long     = 8L
        def alignment: Int = 8
    end given

    given Layout[Float] with
        def get(ptr: Ptr[Byte], offset: Long): Float =
            !(ptr.asInstanceOf[Ptr[Float]] + (offset >> 2))
        def set(ptr: Ptr[Byte], offset: Long, value: Float): Unit =
            !(ptr.asInstanceOf[Ptr[Float]] + (offset >> 2)) = value
        def size: Long     = 4L
        def alignment: Int = 4
    end given

    given Layout[Double] with
        def get(ptr: Ptr[Byte], offset: Long): Double =
            !(ptr.asInstanceOf[Ptr[Double]] + (offset >> 3))
        def set(ptr: Ptr[Byte], offset: Long, value: Double): Unit =
            !(ptr.asInstanceOf[Ptr[Double]] + (offset >> 3)) = value
        def size: Long     = 8L
        def alignment: Int = 8
    end given
end Layout
