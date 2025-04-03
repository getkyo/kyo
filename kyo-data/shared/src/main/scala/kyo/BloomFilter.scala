package kyo

import scala.annotation.tailrec
import scala.util.hashing.MurmurHash3

final class BloomFilter private (bitArray: BitArray, _size: Int, hashFunctionCount: Int):

    def mightContain(element: Int): Boolean =

        @tailrec
        def checkAllBits(i: Int): Boolean =
            if i >= hashFunctionCount then true
            else
                val hash = MurmurHash3.mix(element, i)
                val idx  = (hash & Int.MaxValue) % bitArray.size
                if !bitArray.get(idx) then false
                else checkAllBits(i + 1)

        checkAllBits(0)
    end mightContain

    def doesNotContain(element: Int): Boolean =
        !mightContain(element)

    def size: Int = _size

end BloomFilter

object BloomFilter:

    private val LOG_2         = Math.log(2)
    private val LOG_2_SQUARED = LOG_2 * LOG_2

    final class Builder private[BloomFilter] (bitArrayBuilder: BitArray.Builder, size: Int, hashFunctionCount: Int):

        def add(element: Int): this.type =

            @tailrec
            def setAllBits(i: Int): Unit =
                if i < hashFunctionCount then
                    val hash = MurmurHash3.mix(element, i)
                    val idx  = (hash & Int.MaxValue) % bitArrayBuilder.size
                    bitArrayBuilder.set(idx, true)
                    setAllBits(i + 1)

            setAllBits(0)
            this
        end add

        def result(): BloomFilter =
            BloomFilter(bitArrayBuilder.result(), size, hashFunctionCount)

    end Builder

    def builder(elementsSize: Int, falsePositiveRate: Double = 0.01): Builder =

        if elementsSize <= 0 then
            throw new IllegalArgumentException("Expected size must be positive")

        if falsePositiveRate <= 0.0 || falsePositiveRate >= 1.0 then
            throw new IllegalArgumentException("False positive rate must be between 0 and 1")

        val bitSize = Math.ceil(-elementsSize * Math.log(falsePositiveRate) / LOG_2_SQUARED).toInt

        val hashFunctionCount = Math.max(1, Math.ceil(bitSize * LOG_2 / elementsSize).toInt)

        new Builder(BitArray.builder(bitSize), elementsSize, hashFunctionCount)
    end builder

end BloomFilter
