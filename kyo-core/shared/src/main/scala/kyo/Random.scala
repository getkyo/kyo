package kyo

import scala.annotation.tailrec

/** A high-performance random number generator with functionality for generating various types of random values.
  *
  * Random provides a complete toolkit for random number generation, supporting various data types, ranges, and distribution patterns. It
  * offers both instance-based usage through object methods and context-based usage through companion object methods.
  *
  * Key features:
  *   - Generates primitive values with consistent distribution (`nextInt`, `nextLong`, `nextDouble`, `nextBoolean`, etc.)
  *   - Produces random collections with configurable constraints (`nextValues`, `nextStringAlphanumeric`, `nextBytes`)
  *   - Allows sampling from existing collections with uniform probability (`nextValue`)
  *   - Supports Fisher-Yates shuffling for randomizing sequence order (`shuffle`)
  *   - Provides Gaussian (normal) distribution sampling for statistical applications (`nextGaussian`)
  *
  * The companion object supports flexible context management with methods like `let` and `withSeed` for controlling randomness in specific
  * scopes. The `let` method allows temporary replacement of the current generator with a custom one, while `withSeed` creates reproducible
  * sequences by using a deterministic seed. These methods provide safe isolation for random generation within effect boundaries, making
  * them particularly valuable for testing scenarios where deterministic behavior is required.
  *
  * @see
  *   [[kyo.Random.live]] For immediate access to the default random generator
  * @see
  *   [[kyo.Random.withSeed]] For creating reproducible random sequences
  */
abstract class Random extends Serializable:
    def nextInt(using Frame): Int < Sync
    def nextInt(exclusiveBound: Int)(using Frame): Int < Sync
    def nextLong(using Frame): Long < Sync
    def nextDouble(using Frame): Double < Sync
    def nextBoolean(using Frame): Boolean < Sync
    def nextFloat(using Frame): Float < Sync
    def nextGaussian(using Frame): Double < Sync
    def nextValue[A](seq: Seq[A])(using Frame): A < Sync
    def nextValues[A](length: Int, seq: Seq[A])(using Frame): Seq[A] < Sync
    def nextStringAlphanumeric(length: Int)(using Frame): String < Sync
    def nextString(length: Int, chars: Seq[Char])(using Frame): String < Sync
    def nextBytes(length: Int)(using Frame): Seq[Byte] < Sync
    def shuffle[A](seq: Seq[A])(using Frame): Seq[A] < Sync
    def unsafe: Random.Unsafe
end Random

object Random:

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe extends Serializable:
        def nextInt()(using AllowUnsafe): Int
        def nextInt(exclusiveBound: Int)(using AllowUnsafe): Int
        def nextLong()(using AllowUnsafe): Long
        def nextDouble()(using AllowUnsafe): Double
        def nextBoolean()(using AllowUnsafe): Boolean
        def nextFloat()(using AllowUnsafe): Float
        def nextGaussian()(using AllowUnsafe): Double
        def nextValue[A](seq: Seq[A])(using AllowUnsafe): A
        def nextValues[A](length: Int, seq: Seq[A])(using AllowUnsafe): Seq[A]
        def nextStringAlphanumeric(length: Int)(using AllowUnsafe): String
        def nextString(length: Int, seq: Seq[Char])(using AllowUnsafe): String
        def nextBytes(length: Int)(using AllowUnsafe): Seq[Byte]
        def shuffle[A](seq: Seq[A])(using AllowUnsafe): Seq[A]
        def safe: Random = Random(this)
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def apply(random: java.util.Random): Unsafe =
            new Unsafe:
                def nextInt()(using AllowUnsafe)                    = random.nextInt()
                def nextInt(exclusiveBound: Int)(using AllowUnsafe) = random.nextInt(exclusiveBound)
                def nextLong()(using AllowUnsafe)                   = random.nextLong()
                def nextDouble()(using AllowUnsafe)                 = random.nextDouble()
                def nextBoolean()(using AllowUnsafe)                = random.nextBoolean()
                def nextFloat()(using AllowUnsafe)                  = random.nextFloat()
                def nextGaussian()(using AllowUnsafe)               = random.nextGaussian()
                def nextValue[A](seq: Seq[A])(using AllowUnsafe)    = seq(random.nextInt(seq.size))
                def nextValues[A](length: Int, seq: Seq[A])(using AllowUnsafe) =
                    Seq.fill(length)(nextValue(seq))

                val alphanumeric = (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).toIndexedSeq

                def nextStringAlphanumeric(length: Int)(using AllowUnsafe) =
                    nextString(length, alphanumeric)

                def nextString(length: Int, seq: Seq[Char])(using AllowUnsafe) =
                    val b = new StringBuilder
                    @tailrec def loop(i: Int): Unit =
                        if i < length then
                            b.addOne(nextValue(seq))
                            loop(i + 1)
                    loop(0)
                    b.result()
                end nextString

                val bytes = Seq(0.toByte, 1.toByte).toIndexedSeq
                def nextBytes(length: Int)(using AllowUnsafe) =
                    nextValues(length, bytes)

                def shuffle[A](seq: Seq[A])(using AllowUnsafe) =
                    val buffer = scala.collection.mutable.ArrayBuffer.from(seq)
                    @tailrec def shuffleLoop(i: Int): Unit =
                        if i > 0 then
                            val j    = nextInt(i + 1)
                            val temp = buffer(i)
                            buffer(i) = buffer(j)
                            buffer(j) = temp
                            shuffleLoop(i - 1)
                    shuffleLoop(buffer.size - 1)
                    buffer.toSeq
                end shuffle
    end Unsafe

    /** Creates a new Random instance from an Unsafe implementation.
      *
      * @param u
      *   The Unsafe implementation to use.
      * @return
      *   A new Random instance.
      */
    def apply(u: Unsafe): Random =
        new Random:
            def nextInt(using Frame)                      = Sync.Unsafe(u.nextInt())
            def nextInt(exclusiveBound: Int)(using Frame) = Sync.Unsafe(u.nextInt(exclusiveBound))
            def nextLong(using Frame)                     = Sync.Unsafe(u.nextLong())
            def nextDouble(using Frame)                   = Sync.Unsafe(u.nextDouble())
            def nextBoolean(using Frame)                  = Sync.Unsafe(u.nextBoolean())
            def nextFloat(using Frame)                    = Sync.Unsafe(u.nextFloat())
            def nextGaussian(using Frame)                 = Sync.Unsafe(u.nextGaussian())
            def nextValue[A](seq: Seq[A])(using Frame)    = Sync.Unsafe(u.nextValue[A](seq))
            def nextValues[A](length: Int, seq: Seq[A])(using Frame) =
                Sync.Unsafe(u.nextValues(length, seq))
            def nextStringAlphanumeric(length: Int)(using Frame) =
                Sync.Unsafe(u.nextStringAlphanumeric(length))
            def nextString(length: Int, chars: Seq[Char])(using Frame) =
                Sync.Unsafe(u.nextString(length, chars))
            def nextBytes(length: Int)(using Frame) =
                Sync.Unsafe(u.nextBytes(length))
            def shuffle[A](seq: Seq[A])(using Frame) =
                Sync.Unsafe(u.shuffle(seq))
            def unsafe: Unsafe = u

    /** A live instance of Random using the default java.util.Random. */
    val live = Random(Random.Unsafe(new java.util.Random))

    private val local = Local.init(live)

    /** Executes the given effect with a specific Random instance.
      *
      * @param r
      *   The Random instance to use.
      * @param v
      *   The effect to execute.
      * @tparam A
      *   The return type of the effect.
      * @tparam S
      *   The effect type.
      * @return
      *   The result of the effect execution.
      */
    def let[A, S](r: Random)(v: A < S)(using Frame): A < (S & Sync) =
        local.let(r)(v)

    /** Executes the given effect with a new Random instance initialized with the specified seed.
      *
      * @param seed
      *   The seed value to initialize the Random instance.
      * @param v
      *   The effect to execute.
      * @tparam A
      *   The return type of the effect.
      * @tparam S
      *   The effect type.
      * @return
      *   The result of the effect execution with the seeded Random instance.
      */
    def withSeed[A, S](seed: Int)(v: A < S)(using Frame): A < (S & Sync) =
        Sync(Random(Random.Unsafe(new java.util.Random(seed)))).map(let(_)(v))

    /** Gets the current Random instance from the local context.
      *
      * @return
      *   The current Random instance.
      */
    def get(using Frame): Random < Any = local.get

    /** Executes a function that requires a Random instance using the current Random from the local context.
      *
      * @param f
      *   A function that takes a Random instance and returns an effect.
      * @tparam A
      *   The return type of the effect.
      * @tparam S
      *   The effect type.
      * @return
      *   The result of executing the function with the current Random instance.
      */
    def use[A, S](f: Random => A < S)(using Frame): A < (S & Sync) =
        local.use(f)

    /** Generates a random integer.
      *
      * @return
      *   A random Int value.
      */
    def nextInt(using Frame): Int < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextInt())

    /** Generates a random integer between 0 (inclusive) and the specified bound (exclusive).
      *
      * @param exclusiveBound
      *   The upper bound (exclusive) for the random integer.
      * @return
      *   A random Int value within the specified range.
      */
    def nextInt(exclusiveBound: Int)(using Frame): Int < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextInt(exclusiveBound))

    /** Generates a random long integer.
      *
      * @return
      *   A random Long value.
      */
    def nextLong(using Frame): Long < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextLong())

    /** Generates a random double between 0.0 (inclusive) and 1.0 (exclusive).
      *
      * @return
      *   A random Double value between 0.0 and 1.0.
      */
    def nextDouble(using Frame): Double < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextDouble())

    /** Generates a random boolean value.
      *
      * @return
      *   A random Boolean value.
      */
    def nextBoolean(using Frame): Boolean < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextBoolean())

    /** Generates a random float between 0.0 (inclusive) and 1.0 (exclusive).
      *
      * @return
      *   A random Float value between 0.0 and 1.0.
      */
    def nextFloat(using Frame): Float < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextFloat())

    /** Generates a random double from a Gaussian distribution with mean 0.0 and standard deviation 1.0.
      *
      * @return
      *   A random Double value from a Gaussian distribution.
      */
    def nextGaussian(using Frame): Double < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextGaussian())

    /** Selects a random element from the given sequence.
      *
      * @param seq
      *   The sequence to choose from.
      * @tparam A
      *   The type of elements in the sequence.
      * @return
      *   A randomly selected element from the sequence.
      */
    def nextValue[A](seq: Seq[A])(using Frame): A < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextValue(seq): A)

    /** Generates a sequence of random elements from the given sequence.
      *
      * @param length
      *   The number of elements to generate.
      * @param seq
      *   The sequence to choose from.
      * @tparam A
      *   The type of elements in the sequence.
      * @return
      *   A new sequence of randomly selected elements.
      */
    def nextValues[A](length: Int, seq: Seq[A])(using Frame): Seq[A] < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextValues(length, seq))

    /** Generates a random alphanumeric string of the specified length.
      *
      * @param length
      *   The length of the string to generate.
      * @return
      *   A random alphanumeric String of the specified length.
      */
    def nextStringAlphanumeric(length: Int)(using Frame): String < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextStringAlphanumeric(length))

    /** Generates a random string of the specified length using the given character sequence and the current Random instance.
      *
      * @param length
      *   The length of the string to generate.
      * @param chars
      *   The sequence of characters to choose from.
      * @return
      *   A random String of the specified length.
      */
    def nextString(length: Int, chars: Seq[Char])(using Frame): String < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextString(length, chars))

    /** Generates a sequence of random bytes.
      *
      * @param length
      *   The number of bytes to generate.
      * @return
      *   A Seq[Byte] of random bytes.
      */
    def nextBytes(length: Int)(using Frame): Seq[Byte] < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextBytes(length))

    /** Shuffles the elements of the given sequence randomly.
      *
      * @param seq
      *   The sequence to shuffle.
      * @tparam A
      *   The type of elements in the sequence.
      * @return
      *   A new sequence with the elements shuffled randomly.
      */
    def shuffle[A](seq: Seq[A])(using Frame): Seq[A] < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.shuffle(seq))

end Random
