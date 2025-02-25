package kyo.test

import java.util.UUID
import kyo.*
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.math.log
import scala.math.sqrt

/** TestRandom allows for deterministically testing effects involving randomness.
  *
  * TestRandom operates in two modes. In the first mode, TestRandom is a purely functional pseudo-random number generator. It generates
  * pseudo-random values without mutating internal state. The random seed can be set to guarantee a reproducible sequence, which is useful
  * for property based testing. In the second mode, TestRandom maintains an internal buffer that can be pre-fed with values for
  * deterministic testing.
  */
trait TestRandom extends Random with Restorable:
    def clearBooleans(implicit trace: Trace): Unit < IO
    def clearBytes(implicit trace: Trace): Unit < IO
    def clearChars(implicit trace: Trace): Unit < IO
    def clearDoubles(implicit trace: Trace): Unit < IO
    def clearFloats(implicit trace: Trace): Unit < IO
    def clearInts(implicit trace: Trace): Unit < IO
    def clearLongs(implicit trace: Trace): Unit < IO
    def clearStrings(implicit trace: Trace): Unit < IO
    def clearUUIDs(implicit trace: Trace): Unit < IO
    def feedBooleans(booleans: Boolean*)(implicit trace: Trace): Unit < IO
    def feedBytes(bytes: Chunk[Byte]*)(implicit trace: Trace): Unit < IO
    def feedChars(chars: Char*)(implicit trace: Trace): Unit < IO
    def feedDoubles(doubles: Double*)(implicit trace: Trace): Unit < IO
    def feedFloats(floats: Float*)(implicit trace: Trace): Unit < IO
    def feedInts(ints: Int*)(implicit trace: Trace): Unit < IO
    def feedLongs(longs: Long*)(implicit trace: Trace): Unit < IO
    def feedStrings(strings: String*)(implicit trace: Trace): Unit < IO
    def feedUUIDs(uuids: UUID*)(implicit trace: Trace): Unit < IO
    def getSeed(implicit trace: Trace): Long < IO
    def setSeed(seed: => Long)(implicit trace: Trace): Unit < IO
    def nextBoolean(implicit trace: Trace): Boolean < IO
    def nextBytes(length: => Int)(implicit trace: Trace): Chunk[Byte] < IO
    def nextDouble(implicit trace: Trace): Double < IO
    def nextDoubleBetween(minInclusive: => Double, maxExclusive: => Double)(implicit trace: Trace): Double < IO
    def nextFloat(implicit trace: Trace): Float < IO
    def nextFloatBetween(minInclusive: => Float, maxExclusive: => Float)(implicit trace: Trace): Float < IO
    def nextGaussian(implicit trace: Trace): Double < IO
    def nextInt(implicit trace: Trace): Int < IO
    def nextIntBetween(minInclusive: => Int, maxExclusive: => Int)(implicit trace: Trace): Int < IO
    def nextIntBounded(n: => Int)(implicit trace: Trace): Int < IO
    def nextLong(implicit trace: Trace): Long < IO
    def nextLongBetween(minInclusive: => Long, maxExclusive: => Long)(implicit trace: Trace): Long < IO
    def nextLongBounded(n: => Long)(implicit trace: Trace): Long < IO
    def nextPrintableChar(implicit trace: Trace): Char < IO
end TestRandom

object TestRandom extends Serializable:

    final case class Test(randomState: Var.Atomic[Data], bufferState: Var.Atomic[Buffer]) extends TestRandom:

        def clearBooleans(implicit trace: Trace): Unit < IO =
            bufferState.update(_.copy(booleans = List.empty))

        def clearBytes(implicit trace: Trace): Unit < IO =
            bufferState.update(_.copy(bytes = List.empty))

        def clearChars(implicit trace: Trace): Unit < IO =
            bufferState.update(_.copy(chars = List.empty))

        def clearDoubles(implicit trace: Trace): Unit < IO =
            bufferState.update(_.copy(doubles = List.empty))

        def clearFloats(implicit trace: Trace): Unit < IO =
            bufferState.update(_.copy(floats = List.empty))

        def clearInts(implicit trace: Trace): Unit < IO =
            bufferState.update(_.copy(integers = List.empty))

        def clearLongs(implicit trace: Trace): Unit < IO =
            bufferState.update(_.copy(longs = List.empty))

        def clearStrings(implicit trace: Trace): Unit < IO =
            bufferState.update(_.copy(strings = List.empty))

        def clearUUIDs(implicit trace: Trace): Unit < IO =
            bufferState.update(_.copy(uuids = List.empty))

        def feedBooleans(booleans: Boolean*)(implicit trace: Trace): Unit < IO =
            bufferState.update(data => data.copy(booleans = booleans.toList ::: data.booleans))

        def feedBytes(bytes: Chunk[Byte]*)(implicit trace: Trace): Unit < IO =
            bufferState.update(data => data.copy(bytes = bytes.toList.flatten ::: data.bytes))

        def feedChars(chars: Char*)(implicit trace: Trace): Unit < IO =
            bufferState.update(data => data.copy(chars = chars.toList ::: data.chars))

        def feedDoubles(doubles: Double*)(implicit trace: Trace): Unit < IO =
            bufferState.update(data => data.copy(doubles = doubles.toList ::: data.doubles))

        def feedFloats(floats: Float*)(implicit trace: Trace): Unit < IO =
            bufferState.update(data => data.copy(floats = floats.toList ::: data.floats))

        def feedInts(ints: Int*)(implicit trace: Trace): Unit < IO =
            bufferState.update(data => data.copy(integers = ints.toList ::: data.integers))

        def feedLongs(longs: Long*)(implicit trace: Trace): Unit < IO =
            bufferState.update(data => data.copy(longs = longs.toList ::: data.longs))

        def feedStrings(strings: String*)(implicit trace: Trace): Unit < IO =
            bufferState.update(data => data.copy(strings = strings.toList ::: data.strings))

        def feedUUIDs(uuids: UUID*)(implicit trace: Trace): Unit < IO =
            bufferState.update(data => data.copy(uuids = uuids.toList ::: data.uuids))

        def getSeed(implicit trace: Trace): Long < IO =
            randomState.get.map { case Data(seed1, seed2, _) =>
                ((seed1.toLong << 24) | seed2) ^ 0x5deece66dL
            }

        def setSeed(seed: => Long)(implicit trace: Trace): Unit < IO =
            randomState.update { case Data(_, _, other) =>
                // Placeholder: set new seed based on the provided seed
                Data((seed >> 24).toInt, (seed & 0xffffff).toInt, other)
            }

        def nextBoolean(implicit trace: Trace): Boolean < IO =
            unsafe.nextBoolean()(Unsafe.unsafe)

        def nextBytes(length: => Int)(implicit trace: Trace): Chunk[Byte] < IO =
            unsafe.nextBytes(length)(Unsafe.unsafe)

        def nextDouble(implicit trace: Trace): Double < IO =
            unsafe.nextDouble()(Unsafe.unsafe)

        def nextDoubleBetween(minInclusive: => Double, maxExclusive: => Double)(implicit trace: Trace): Double < IO =
            unsafe.nextDoubleBetween(minInclusive, maxExclusive)(Unsafe.unsafe)

        def nextFloat(implicit trace: Trace): Float < IO =
            unsafe.nextFloat()(Unsafe.unsafe)

        def nextFloatBetween(minInclusive: => Float, maxExclusive: => Float)(implicit trace: Trace): Float < IO =
            unsafe.nextFloatBetween(minInclusive, maxExclusive)(Unsafe.unsafe)

        def nextGaussian(implicit trace: Trace): Double < IO =
            unsafe.nextGaussian()(Unsafe.unsafe)

        def nextInt(implicit trace: Trace): Int < IO =
            unsafe.nextInt()(Unsafe.unsafe)

        def nextIntBetween(minInclusive: => Int, maxExclusive: => Int)(implicit trace: Trace): Int < IO =
            unsafe.nextIntBetween(minInclusive, maxExclusive)(Unsafe.unsafe)

        def nextIntBounded(n: => Int)(implicit trace: Trace): Int < IO =
            unsafe.nextIntBounded(n)(Unsafe.unsafe)

        def nextLong(implicit trace: Trace): Long < IO =
            unsafe.nextLong()(Unsafe.unsafe)

        def nextLongBetween(minInclusive: => Long, maxExclusive: => Long)(implicit trace: Trace): Long < IO =
            unsafe.nextLongBetween(minInclusive, maxExclusive)(Unsafe.unsafe)

        def nextLongBounded(n: => Long)(implicit trace: Trace): Long < IO =
            unsafe.nextLongBounded(n)(Unsafe.unsafe)

        def nextPrintableChar(implicit trace: Trace): Char < IO =
            unsafe.nextPrintableChar()(Unsafe.unsafe)
    end Test

end TestRandom
