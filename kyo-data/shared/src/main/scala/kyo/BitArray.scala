package kyo

import scala.annotation.tailrec

opaque type BitArray = IArray[Long]

object BitArray:

    private inline def LongBits       = 64
    private inline def LongIndexShift = 6
    private inline def BitIndexMask   = 63

    final case class Builder(size: Int):
        private val array: Array[Long] = new Array[Long]((size + LongBits - 1) / LongBits)

        def set(idx: Int, value: Boolean): this.type =
            if idx < 0 || idx >= size then throw new IndexOutOfBoundsException(s"Index $idx out of bounds for size $size")
            val longIndex = idx >>> LongIndexShift
            val bitIndex  = idx & BitIndexMask
            val mask      = 1L << bitIndex

            if value then
                array(longIndex) = array(longIndex) | mask
            else
                array(longIndex) = array(longIndex) & ~mask
            end if

            this
        end set

        def result(): BitArray = IArray.unsafeFromArray(array)
    end Builder

    def builder(size: Int): Builder =
        if size < 0 then throw new IllegalArgumentException("size cannot be negative")
        new Builder(size)

    def empty: BitArray = IArray.empty

    extension (self: BitArray)

        def size: Int = self.length * LongBits

        def get(idx: Int): Boolean =
            if idx < 0 || idx >= size then
                throw new IndexOutOfBoundsException(s"Index $idx out of bounds for size ${size}")
            else
                val longIndex = idx >>> LongIndexShift
                val bitIndex  = idx & BitIndexMask
                val mask      = 1L << bitIndex
                (self(longIndex) & mask) != 0L

    end extension

end BitArray
