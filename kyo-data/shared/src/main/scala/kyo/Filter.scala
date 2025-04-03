package kyo

import scala.annotation.tailrec
import scala.util.hashing.MurmurHash3

// Generic Bloom filter implementation
final case class Filter[T] private (
    private val bitArray: Array[Boolean],
    private val hashFunctionCount: Int,
    private val maxFillRatio: Double
):
    // Add a value to the filter
    def add(value: T): Filter[T] =
        val currentCount     = approximateElementCount()
        val maxCapacityValue = maxCapacity()

        if currentCount >= maxCapacityValue * maxFillRatio then
            // Create a new filter with increased capacity
            val newSize      = math.max(bitArray.length * 2, 1000)
            val newHashCount = Filter.calculateOptimalHashCount(newSize, currentCount + 1)

            val newFilter = Filter[T](new Array[Boolean](newSize), newHashCount, maxFillRatio)
            newFilter.add(value)
        else
            val newBitArray = bitArray.clone()

            @tailrec
            def addHashes(index: Int): Unit =
                if index < hashFunctionCount then
                    newBitArray(computeHashAt(value, index)) = true
                    addHashes(index + 1)

            addHashes(0)
            Filter(newBitArray, hashFunctionCount, maxFillRatio)
        end if
    end add

    // Check if a value might be in the filter
    def mightContain(value: T): Boolean =
        @tailrec
        def checkHashes(index: Int): Boolean =
            if index >= hashFunctionCount then true
            else if !bitArray(computeHashAt(value, index)) then false
            else checkHashes(index + 1)

        checkHashes(0)
    end mightContain

    // Check if a value is definitely not in the filter
    def definitelyNotContains(value: T): Boolean =
        !mightContain(value)

    // Convenience method for conditional execution
    def doIfNotPresent[R](value: T, fn: => R): (Filter[T], Option[R]) =
        if mightContain(value) then
            (this, None) // Already in filter (or false positive)
        else
            val result = fn
            (add(value), Some(result))

    // Compute the hash for a value at the given index
    private def computeHashAt(value: T, i: Int): Int =
        val seed = value.hashCode() + i
        val hash = MurmurHash3.stringHash(value.toString, seed)
        Math.abs(hash) % bitArray.length
    end computeHashAt

    // Estimate the number of elements in the filter
    def approximateElementCount(): Int =
        val setBits = countSetBits()
        if setBits == 0 then 0
        else
            ((-bitArray.length.toDouble / hashFunctionCount) *
                math.log(1 - setBits.toDouble / bitArray.length))
                    .toInt
        end if
    end approximateElementCount

    // Calculate the max capacity before performance degradation
    private def maxCapacity(): Int =
        val m = bitArray.length.toDouble
        val k = hashFunctionCount.toDouble
        (m * math.log(2) / k).toInt
    end maxCapacity

    // Check if the filter is empty
    def isEmpty: Boolean = countSetBits() == 0

    // Count the number of bits set to true
    private def countSetBits(): Int =
        @tailrec
        def count(index: Int, acc: Int): Int =
            if index >= bitArray.length then acc
            else count(index + 1, if bitArray(index) then acc + 1 else acc)

        count(0, 0)
    end countSetBits
end Filter

object Filter:
    // Create an empty filter
    def empty[T](maxFillRatio: Double = 0.75): Filter[T] =
        val initialSize      = 1000
        val initialHashCount = 6 // Higher count reduces false negatives
        new Filter[T](new Array[Boolean](initialSize), initialHashCount, maxFillRatio)
    end empty

    // Create a filter from existing values
    def from[T](values: Iterable[T], maxFillRatio: Double = 0.75): Filter[T] =
        values.foldLeft(empty[T](maxFillRatio))((filter, value) => filter.add(value))

    // Calculate the optimal hash count for a given size and element count
    private def calculateOptimalHashCount(size: Int, elemCount: Int): Int =
        if elemCount <= 0 then 6 // Default for empty filter
        else math.max(1, math.round((size.toDouble / elemCount) * math.log(2)).toInt)
end Filter
