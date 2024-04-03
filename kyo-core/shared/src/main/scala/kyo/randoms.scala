package kyo

trait Random:
    def nextInt: Int < IOs
    def nextInt(exclusiveBound: Int): Int < IOs
    def nextLong: Long < IOs
    def nextDouble: Double < IOs
    def nextBoolean: Boolean < IOs
    def nextFloat: Float < IOs
    def nextGaussian: Double < IOs
    def nextValue[T](seq: Seq[T]): T < IOs
    def nextValues[T](length: Int, seq: Seq[T]): Seq[T] < IOs
    def nextStringAlphanumeric(length: Int): String < IOs
    def nextString(length: Int, chars: Seq[Char]): String < IOs
    def nextBytes(length: Int): Seq[Byte] < IOs
    def shuffle[T](seq: Seq[T]): Seq[T] < IOs
    def unsafe: Random.Unsafe
end Random

object Random:

    trait Unsafe:
        def nextInt: Int
        def nextInt(exclusiveBound: Int): Int
        def nextLong: Long
        def nextDouble: Double
        def nextBoolean: Boolean
        def nextFloat: Float
        def nextGaussian: Double
        def nextValue[T](seq: Seq[T]): T
        def nextValues[T](length: Int, seq: Seq[T]): Seq[T]
        def nextStringAlphanumeric(length: Int): String
        def nextString(length: Int, seq: Seq[Char]): String
        def nextBytes(length: Int): Seq[Byte]
        def shuffle[T](seq: Seq[T]): Seq[T]
    end Unsafe

    object Unsafe:
        def apply(random: java.util.Random): Unsafe =
            new Unsafe:
                def nextInt: Int                      = random.nextInt()
                def nextInt(exclusiveBound: Int): Int = random.nextInt(exclusiveBound)
                def nextLong: Long                    = random.nextLong()
                def nextDouble: Double                = random.nextDouble()
                def nextBoolean: Boolean              = random.nextBoolean()
                def nextFloat: Float                  = random.nextFloat()
                def nextGaussian: Double              = random.nextGaussian()
                def nextValue[T](seq: Seq[T]): T      = seq(random.nextInt(seq.size))
                def nextValues[T](length: Int, seq: Seq[T]): Seq[T] =
                    Seq.fill(length)(nextValue(seq))

                val alphanumeric = (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).toIndexedSeq
                def nextStringAlphanumeric(length: Int): String =
                    nextString(length, alphanumeric)

                def nextString(length: Int, seq: Seq[Char]): String =
                    val b = new StringBuilder
                    var i = 0
                    while i < length do
                        b.addOne(nextValue(seq))
                        i += 1
                    b.result()
                end nextString

                val bytes = Seq(0.toByte, 1.toByte).toIndexedSeq
                def nextBytes(length: Int): Seq[Byte] =
                    nextValues(length, bytes)

                def shuffle[T](seq: Seq[T]): Seq[T] =
                    val buffer = scala.collection.mutable.ArrayBuffer.from(seq)
                    var i      = buffer.size - 1
                    while i > 0 do
                        val j    = nextInt(i + 1)
                        val temp = buffer(i)
                        buffer(i) = buffer(j)
                        buffer(j) = temp
                        i -= 1
                    end while
                    buffer.toSeq
                end shuffle
    end Unsafe

    val default = apply(Unsafe(new java.util.Random))

    def apply(u: Unsafe): Random =
        new Random:
            val nextInt: Int < IOs                      = IOs(u.nextInt)
            def nextInt(exclusiveBound: Int): Int < IOs = IOs(u.nextInt(exclusiveBound))
            val nextLong: Long < IOs                    = IOs(u.nextLong)
            val nextDouble: Double < IOs                = IOs(u.nextDouble)
            val nextBoolean: Boolean < IOs              = IOs(u.nextBoolean)
            val nextFloat: Float < IOs                  = IOs(u.nextFloat)
            val nextGaussian: Double < IOs              = IOs(u.nextGaussian)
            def nextValue[T](seq: Seq[T]): T < IOs      = IOs(u.nextValue(seq))
            def nextValues[T](length: Int, seq: Seq[T]): Seq[T] < IOs =
                IOs(u.nextValues(length, seq))
            def nextStringAlphanumeric(length: Int): String < IOs =
                IOs(u.nextStringAlphanumeric(length))
            def nextString(length: Int, chars: Seq[Char]): String < IOs =
                IOs(u.nextString(length, chars))
            def nextBytes(length: Int): Seq[Byte] < IOs =
                IOs(u.nextBytes(length))
            def shuffle[T](seq: Seq[T]): Seq[T] < IOs =
                IOs(u.shuffle(seq))
            def unsafe: Unsafe = u
end Random

object Randoms:

    private val local = Locals.init(Random.default)

    def let[T, S](r: Random)(v: T < S): T < (S & IOs) =
        local.let(r)(v)

    val nextInt: Int < IOs =
        local.use(_.nextInt)

    def nextInt(exclusiveBound: Int): Int < IOs =
        local.use(_.nextInt(exclusiveBound))

    val nextLong: Long < IOs =
        local.use(_.nextLong)

    val nextDouble: Double < IOs =
        local.use(_.nextDouble)

    val nextBoolean: Boolean < IOs =
        local.use(_.nextBoolean)

    val nextFloat: Float < IOs =
        local.use(_.nextFloat)

    val nextGaussian: Double < IOs =
        local.use(_.nextGaussian)

    def nextValue[T](seq: Seq[T]): T < IOs =
        local.use(_.nextValue(seq))

    def nextValues[T](length: Int, seq: Seq[T]): Seq[T] < IOs =
        local.use(_.nextValues(length, seq))

    def nextStringAlphanumeric(length: Int): String < IOs =
        local.use(_.nextStringAlphanumeric(length))

    def nextString(length: Int, chars: Seq[Char]): String < IOs =
        local.use(_.nextString(length, chars))

    def nextBytes(length: Int): Seq[Byte] < IOs =
        local.use(_.nextBytes(length))

    def shuffle[T](seq: Seq[T]): Seq[T] < IOs =
        local.use(_.shuffle(seq))
end Randoms
