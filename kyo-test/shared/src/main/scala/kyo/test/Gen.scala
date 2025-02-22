package kyo.test

import java.nio.charset.StandardCharsets
import java.util.UUID
import kyo.Abort
// Replacing ZIO imports with Kyo equivalents
import kyo.Chunk
import kyo.Env
import kyo.Frame
import kyo.Kyo
import kyo.Random
import kyo.Zippable
import kyo.stream.Stream
import kyo.test.Sample
import scala.collection.immutable.SortedMap
import scala.math.Numeric.DoubleIsFractional

/** A `Gen[R, A]` represents a generator of values of type `A`, which requires an environment `R`. Generators may be random or
  * deterministic.
  */
final case class Gen[-R, +A](sample: Stream[Env[R], Sample[R, A]]):
    self =>

    /** A symbolic alias for `concat`. */
    def ++[R1 <: R, A1 >: A](that: Gen[R1, A1])(implicit trace: Frame): Gen[R1, A1] =
        self.concat(that)

    /** A symbolic alias for `zip`. */
    def <*>[R1 <: R, B](that: Gen[R1, B])(implicit zippable: Zippable[A, B], trace: Trace): Gen[R1, zippable.Out] =
        self.zip(that)

    /** Concatenates the specified deterministic generator with this deterministic generator, resulting in a generator that generates the
      * values from this generator and then the values from the specified generator.
      */
    def concat[R1 <: R, A1 >: A](that: Gen[R1, A1])(implicit trace: Frame): Gen[R1, A1] =
        Gen(self.sample ++ that.sample)

    /** Maps the values produced by this generator with the specified partial function, discarding any values the partial function is not
      * defined at.
      */
    def collect[B](pf: PartialFunction[A, B])(implicit trace: Frame): Gen[R, B] =
        self.flatMap { a =>
            pf.andThen(Gen.const(_)).applyOrElse[A, Gen[Any, B]](a, _ => Gen.empty)
        }

    /** Filters the values produced by this generator, discarding any values that do not meet the specified predicate.
      */
    def filter(f: A => Boolean)(implicit trace: Frame): Gen[R, A] =
        self.flatMap(a => if f(a) then Gen.const(a) else Gen.empty)

    /** Filters the values produced by this generator, discarding any values that do not meet the specified effectual predicate.
      */
    def filterZIO[R1 <: R](f: A => Boolean < Env[R1])(implicit trace: Frame): Gen[R1, A] =
        self.flatMap(a => Gen.fromKyo(f(a)).flatMap(p => if p then Gen.const(a) else Gen.empty))

    /** Filters the values produced by this generator, discarding any values that meet the specified predicate.
      */
    def filterNot(f: A => Boolean)(implicit trace: Frame): Gen[R, A] =
        filter(a => !f(a))

    def withFilter(f: A => Boolean)(implicit trace: Frame): Gen[R, A] = filter(f)

    def flatMap[R1 <: R, B](f: A => Gen[R1, B])(implicit trace: Frame): Gen[R1, B] =
        Gen {
            self.sample.flatMap { sample =>
                val values  = f(sample.value).sample
                val shrinks = Gen(sample.shrink).flatMap(f).sample
                values.map(_.flatMap(s => Sample(s.value, shrinks)))
            }
        }

    def flatten[R1 <: R, B](implicit ev: A <:< Gen[R1, B], trace: Trace): Gen[R1, B] =
        flatMap(ev)

    def map[B](f: A => B)(implicit trace: Frame): Gen[R, B] =
        Gen(sample.map(_.map(f)))

    /** Maps an effectual function over a generator.
      */
    def mapZIO[R1 <: R, B](f: A => B < Env[R1])(implicit trace: Frame): Gen[R1, B] =
        Gen(sample.mapZIO(_.foreach(f)))

    /** Discards the shrinker for this generator.
      */
    def noShrink(implicit trace: Frame): Gen[R, A] =
        reshrink(Sample.noShrink)

    /** Discards the shrinker for this generator and applies a new shrinker by mapping each value to a sample using the specified function.
      */
    def reshrink[R1 <: R, B](f: A => Sample[R1, B])(implicit trace: Frame): Gen[R1, B] =
        Gen(sample.map(sample => f(sample.value)))

    /** Sets the size parameter for this generator to the specified value.
      */
    def resize(size: Int)(implicit trace: Frame): Gen[R, A] =
        Sized.withSizeGen(size)(self)

    /** Runs the generator and collects all of its values in a list.
      */
    def runCollect(implicit trace: Frame): List[A] < Env[R] =
        sample.map(_.value).runCollect.map(_.toList)

    /** Repeatedly runs the generator and collects the specified number of values in a list.
      */
    def runCollectN(n: Int)(implicit trace: Frame): List[A] < Env[R] =
        sample.map(_.value).forever.take(n.toLong).runCollect.map(_.toList)

    /** Runs the generator returning the first value of the generator.
      */
    def runHead(implicit trace: Frame): Option[A] < Env[R] =
        sample.map(_.value).runHead

    /** Composes this generator with the specified generator to create a cartesian product of elements.
      */
    def zip[R1 <: R, B](that: Gen[R1, B])(implicit zippable: Zippable[A, B], trace: Trace): Gen[R1, zippable.Out] =
        self.zipWith(that)(zippable.zip(_, _))

    /** Composes this generator with the specified generator to create a cartesian product of elements with the specified function.
      */
    def zipWith[R1 <: R, B, C](that: Gen[R1, B])(f: (A, B) => C)(implicit trace: Frame): Gen[R1, C] =
        self.flatMap(a => that.map(b => f(a, b)))
end Gen

object Gen extends GenKyo with FunctionVariants with TimeVariants:

    /** A generator of alpha characters.
      */
    def alphaChar(implicit trace: Frame): Gen[Any, Char] =
        weighted(char(65, 90) -> 26, char(97, 122) -> 26)

    /** A generator of alphanumeric characters. Shrinks toward '0'.
      */
    def alphaNumericChar(implicit trace: Frame): Gen[Any, Char] =
        weighted(char(48, 57) -> 10, char(65, 90) -> 26, char(97, 122) -> 26)

    /** A generator of alphanumeric strings. Shrinks towards the empty string.
      */
    def alphaNumericString(implicit trace: Frame): Gen[Any, String] =
        Gen.string(alphaNumericChar)

    /** A generator of alphanumeric strings whose size falls within the specified bounds.
      */
    def alphaNumericStringBounded(min: Int, max: Int)(implicit trace: Frame): Gen[Any, String] =
        Gen.stringBounded(min, max)(alphaNumericChar)

    /** A generator of US-ASCII characters. Shrinks toward '0'.
      */
    def asciiChar(implicit trace: Frame): Gen[Any, Char] =
        Gen.oneOf(Gen.char('\u0000', '\u007F'))

    /** A generator US-ASCII strings. Shrinks towards the empty string.
      */
    def asciiString(implicit trace: Frame): Gen[Any, String] =
        Gen.string(Gen.asciiChar)

    /** A generator of big decimals inside the specified range: [min, max]. The shrinker will shrink toward the lower end of the range.
      */
    def bigDecimal(min: BigDecimal, max: BigDecimal)(implicit trace: Frame): Gen[Any, BigDecimal] =
        if min > max then
            Abort.die(new IllegalArgumentException("invalid bounds"))
        else
            val difference = max - min
            val decimals   = difference.scale max 0
            val bigInt     = (difference * BigDecimal(10).pow(decimals)).toBigInt
            Gen.bigInt(0, bigInt).map(bigInt => min + BigDecimal(bigInt) / BigDecimal(10).pow(decimals))

    /** A generator of java.math.BigDecimal inside the specified range: [min, max].
      */
    def bigDecimalJava(min: BigDecimal, max: BigDecimal)(implicit trace: Frame): Gen[Any, java.math.BigDecimal] =
        Gen.bigDecimal(min, max).map(_.underlying)

    /** A generator of big integers inside the specified range: [min, max]. The shrinker will shrink toward the lower end of the range.
      */
    def bigInt(min: BigInt, max: BigInt)(implicit trace: Frame): Gen[Any, BigInt] =
        Gen.fromKyoSample {
            if min > max then Abort.die(new IllegalArgumentException("invalid bounds"))
            else if min == max then Kyo.succeed(Sample.noShrink(min))
            else
                val bitLength  = (max - min).bitLength
                val byteLength = ((bitLength.toLong + 7) / 8).toInt
                val excessBits = byteLength * 8 - bitLength
                val mask       = (1 << (8 - excessBits)) - 1
                val effect = nextBytes(byteLength).map { bytes =>
                    val arr = bytes.toArray
                    arr(0) = (arr(0) & mask).toByte
                    min + BigInt(arr)
                }.repeatUntil(n => min <= n && n <= max)
                effect.map(Sample.shrinkIntegral(min))
        }

    /** A generator of java.math.BigInteger inside the specified range: [min, max].
      */
    def bigIntegerJava(min: BigInt, max: BigInt)(implicit trace: Frame): Gen[Any, java.math.BigInteger] =
        Gen.bigInt(min, max).map(_.underlying)

    /** A generator of booleans. Shrinks toward 'false'.
      */
    def boolean(implicit trace: Frame): Gen[Any, Boolean] =
        elements(false, true)
end Gen
