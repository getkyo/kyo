package kyo.internal

import kyo.AllowUnsafe

/** Struct-level composite marshalling for off-heap buffers.
  *
  * Primitive instances delegate to [[UnsafeBuffer]]'s primitive get/set methods. Composite instances (e.g. for case classes) are generated
  * by codegen or derived from schemas.
  */
trait UnsafeLayout[A]:
    def size: Int
    def alignment: Int
    def read(buf: UnsafeBuffer, offset: Long)(using AllowUnsafe): A
    def write(buf: UnsafeBuffer, offset: Long, value: A)(using AllowUnsafe): Unit
end UnsafeLayout

object UnsafeLayout:

    given UnsafeLayout[Byte] with
        val size                                                                         = 1
        val alignment                                                                    = 1
        def read(buf: UnsafeBuffer, offset: Long)(using AllowUnsafe): Byte               = buf.getByte(offset)
        def write(buf: UnsafeBuffer, offset: Long, value: Byte)(using AllowUnsafe): Unit = buf.setByte(offset, value)
    end given

    given UnsafeLayout[Short] with
        val size                                                                          = 2
        val alignment                                                                     = 2
        def read(buf: UnsafeBuffer, offset: Long)(using AllowUnsafe): Short               = buf.getShort(offset)
        def write(buf: UnsafeBuffer, offset: Long, value: Short)(using AllowUnsafe): Unit = buf.setShort(offset, value)
    end given

    given UnsafeLayout[Int] with
        val size                                                                        = 4
        val alignment                                                                   = 4
        def read(buf: UnsafeBuffer, offset: Long)(using AllowUnsafe): Int               = buf.getInt(offset)
        def write(buf: UnsafeBuffer, offset: Long, value: Int)(using AllowUnsafe): Unit = buf.setInt(offset, value)
    end given

    given UnsafeLayout[Long] with
        val size                                                                         = 8
        val alignment                                                                    = 8
        def read(buf: UnsafeBuffer, offset: Long)(using AllowUnsafe): Long               = buf.getLong(offset)
        def write(buf: UnsafeBuffer, offset: Long, value: Long)(using AllowUnsafe): Unit = buf.setLong(offset, value)
    end given

    given UnsafeLayout[Float] with
        val size                                                                          = 4
        val alignment                                                                     = 4
        def read(buf: UnsafeBuffer, offset: Long)(using AllowUnsafe): Float               = buf.getFloat(offset)
        def write(buf: UnsafeBuffer, offset: Long, value: Float)(using AllowUnsafe): Unit = buf.setFloat(offset, value)
    end given

    given UnsafeLayout[Double] with
        val size                                                                           = 8
        val alignment                                                                      = 8
        def read(buf: UnsafeBuffer, offset: Long)(using AllowUnsafe): Double               = buf.getDouble(offset)
        def write(buf: UnsafeBuffer, offset: Long, value: Double)(using AllowUnsafe): Unit = buf.setDouble(offset, value)
    end given

end UnsafeLayout
