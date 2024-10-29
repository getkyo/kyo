package kyo

class RandomTest extends Test:

    "mocked" - {
        val testRandom: Random = new Random:
            def nextInt(using Frame)                                   = 10
            def nextInt(n: Int)(using Frame)                           = Math.min(55, n - 1)
            def nextLong(using Frame)                                  = 20L
            def nextBoolean(using Frame)                               = true
            def nextDouble(using Frame)                                = 30d
            def nextFloat(using Frame)                                 = 40f
            def nextGaussian(using Frame)                              = 50d
            def nextValue[A](seq: Seq[A])(using Frame)                 = seq.last
            def nextValues[A](length: Int, seq: Seq[A])(using Frame)   = Seq.fill(length)(seq.last)
            def nextStringAlphanumeric(length: Int)(using Frame)       = "a" * length
            def nextString(length: Int, chars: Seq[Char])(using Frame) = chars.last.toString * length
            def nextBytes(length: Int)(using Frame)                    = Seq.fill(length)(1.toByte)
            def shuffle[A](seq: Seq[A])(using Frame)                   = seq.reverse
            def unsafe                                                 = ???

        "nextInt" in run {
            Random.let(testRandom)(Random.nextInt).map { v =>
                assert(v == 10)
            }
        }

        "nextInt(n)" in run {
            Random.let(testRandom)(Random.nextInt(42)).map { v =>
                assert(v == 41)
            }
        }

        "nextLong" in run {
            Random.let(testRandom)(Random.nextLong).map { v =>
                assert(v == 20L)
            }
        }

        "nextBoolean" in run {
            Random.let(testRandom)(Random.nextBoolean).map { v =>
                assert(v == true)
            }
        }

        "nextDouble" in run {
            Random.let(testRandom)(Random.nextDouble).map { v =>
                assert(v == 30d)
            }
        }

        "nextFloat" in run {
            Random.let(testRandom)(Random.nextFloat).map { v =>
                assert(v == 40f)
            }
        }

        "nextGaussian" in run {
            Random.let(testRandom)(Random.nextGaussian).map { v =>
                assert(v == 50d)
            }
        }

        "nextValue" in run {
            Random.let(testRandom)(Random.nextValue(List(1, 2))).map { v =>
                assert(v == 2)
            }
        }

        "nextValues" in run {
            Random.let(testRandom)(Random.nextValues(3, List(1, 2))).map { v =>
                assert(v == List(2, 2, 2))
            }
        }

        "nextString" in run {
            Random.let(testRandom)(Random.nextStringAlphanumeric(5)).map { v =>
                assert(v == "aaaaa")
            }
        }

        "nextString with chars" in run {
            Random.let(testRandom)(Random.nextString(3, List('x', 'y', 'z'))).map { v =>
                assert(v == "zzz")
            }
        }

        "nextBytes" in run {
            Random.let(testRandom)(Random.nextBytes(4)).map { v =>
                assert(v == Seq(1.toByte, 1.toByte, 1.toByte, 1.toByte))
            }
        }

        "shuffle" in run {
            Random.let(testRandom)(Random.shuffle(Seq(1, 2, 3))).map { v =>
                assert(v == Seq(3, 2, 1))
            }
        }
    }

    "live" - {
        "nextInt" in run {
            Random.nextInt.map { v =>
                assert(v >= Int.MinValue && v <= Int.MaxValue)
            }
        }

        "nextInt(n)" in run {
            val n = 10
            Random.nextInt(n).map { v =>
                assert(v >= 0 && v < n)
            }
        }

        "nextLong" in run {
            Random.nextLong.map { v =>
                assert(v >= Long.MinValue && v <= Long.MaxValue)
            }
        }

        "nextBoolean" in run {
            Random.nextBoolean.map { v =>
                assert(v == true || v == false)
            }
        }

        "nextDouble" in run {
            Random.nextDouble.map { v =>
                assert(v >= 0.0 && v < 1.0)
            }
        }

        "nextFloat" in run {
            Random.nextFloat.map { v =>
                assert(v >= 0.0f && v < 1.0f)
            }
        }

        "nextGaussian" in run {
            Random.nextGaussian.map { v =>
                assert(v >= Double.MinValue && v <= Double.MaxValue)
            }
        }

        "nextValue" in run {
            val seq = List(1, 2, 3, 4, 5)
            Random.nextValue(seq).map { v =>
                assert(seq.contains(v))
            }
        }

        "nextValues" in run {
            val seq    = List(1, 2, 3, 4, 5)
            val length = 3
            Random.nextValues(length, seq).map { v =>
                assert(v.length == length)
                assert(v.forall(seq.contains))
            }
        }

        "nextString" in run {
            val length = 5
            Random.nextStringAlphanumeric(length).map { v =>
                assert(v.length == length)
                assert(v.forall(c =>
                    c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                ))
            }
        }

        "nextString with chars" in run {
            val length = 3
            val chars  = List('x', 'y', 'z')
            Random.nextString(length, chars).map { v =>
                assert(v.length == length)
                assert(v.forall(chars.contains))
            }
        }

        "nextBytes" in run {
            val length = 4
            Random.nextBytes(length).map { v =>
                assert(v.length == length)
                assert(v.forall(b => b == 0.toByte || b == 1.toByte))
            }
        }

        "shuffle" in run {
            Random.shuffle(Seq(1, 2, 3)).map { v =>
                assert(v.length == 3)
                assert(v.toSet == Set(1, 2, 3))
            }
        }
    }

    "unsafe" - {
        import AllowUnsafe.embrace.danger

        val testUnsafe = new TestUnsafe()

        "should generate nextInt correctly" in {
            val result = testUnsafe.nextInt()
            assert(result >= Int.MinValue && result <= Int.MaxValue)
        }

        "should generate nextInt with bound correctly" in {
            val bound  = 10
            val result = testUnsafe.nextInt(bound)
            assert(result >= 0 && result < bound)
        }

        "should generate nextLong correctly" in {
            val result = testUnsafe.nextLong()
            assert(result >= Long.MinValue && result <= Long.MaxValue)
        }

        "should generate nextDouble correctly" in {
            val result = testUnsafe.nextDouble()
            assert(result >= 0.0 && result < 1.0)
        }

        "should generate nextBoolean correctly" in {
            val result = testUnsafe.nextBoolean()
            assert(result == true || result == false)
        }

        "should generate nextFloat correctly" in {
            val result = testUnsafe.nextFloat()
            assert(result >= 0.0f && result < 1.0f)
        }

        "should generate nextGaussian correctly" in {
            val result = testUnsafe.nextGaussian()
            assert(!result.isNaN && !result.isInfinite)
        }

        "should select nextValue correctly" in {
            val seq    = Seq(1, 2, 3, 4, 5)
            val result = testUnsafe.nextValue(seq)
            assert(seq.contains(result))
        }

        "should generate nextValues correctly" in {
            val seq    = Seq(1, 2, 3, 4, 5)
            val length = 3
            val result = testUnsafe.nextValues(length, seq)
            assert(result.length == length)
            assert(result.forall(seq.contains))
        }

        "should generate nextStringAlphanumeric correctly" in {
            val length = 10
            val result = testUnsafe.nextStringAlphanumeric(length)
            assert(result.length == length)
            assert(result.forall(c => c.isLetterOrDigit))
        }

        "should generate nextString correctly" in {
            val length = 5
            val chars  = Seq('a', 'b', 'c')
            val result = testUnsafe.nextString(length, chars)
            assert(result.length == length)
            assert(result.forall(chars.contains))
        }

        "should generate nextBytes correctly" in {
            val length = 8
            val result = testUnsafe.nextBytes(length)
            assert(result.length == length)
            assert(result.forall(b => b >= Byte.MinValue && b <= Byte.MaxValue))
        }

        "should shuffle sequence correctly" in {
            val seq    = Seq(1, 2, 3, 4, 5)
            val result = testUnsafe.shuffle(seq)
            assert(result.length == seq.length)
            assert(result.toSet == seq.toSet)
        }

        "should convert to safe Random" in {
            val safeRandom = testUnsafe.safe
            assert(safeRandom.isInstanceOf[Random])
        }
    }

    "context operations" - {
        val testRandom: Random = new Random:
            def nextInt(using Frame)                                   = 42
            def nextInt(n: Int)(using Frame)                           = n - 1
            def nextLong(using Frame)                                  = 20L
            def nextBoolean(using Frame)                               = true
            def nextDouble(using Frame)                                = 30d
            def nextFloat(using Frame)                                 = 40f
            def nextGaussian(using Frame)                              = 50d
            def nextValue[A](seq: Seq[A])(using Frame)                 = seq.last
            def nextValues[A](length: Int, seq: Seq[A])(using Frame)   = Seq.fill(length)(seq.last)
            def nextStringAlphanumeric(length: Int)(using Frame)       = "a" * length
            def nextString(length: Int, chars: Seq[Char])(using Frame) = chars.last.toString * length
            def nextBytes(length: Int)(using Frame)                    = Seq.fill(length)(1.toByte)
            def shuffle[A](seq: Seq[A])(using Frame)                   = seq.reverse
            def unsafe                                                 = ???

        "get should return current Random instance" in run {
            Random.let(testRandom) {
                Random.get.map { random =>
                    assert(random.equals(testRandom))
                }
            }
        }

        "use should execute function with current Random" in run {
            Random.let(testRandom) {
                Random.use(_.nextInt).map { result =>
                    assert(result == 42)
                }
            }
        }

        "withSeed should create deterministic Random" in run {
            val seed = 12345
            for
                result1 <- Random.withSeed(seed)(Random.nextInt)
                result2 <- Random.withSeed(seed)(Random.nextInt)
            yield assert(result1 == result2)
            end for
        }

        "withSeed should produce different results with different seeds" in run {
            for
                result1 <- Random.withSeed(12345)(Random.nextInt)
                result2 <- Random.withSeed(54321)(Random.nextInt)
            yield assert(result1 != result2)
        }
    }

    class TestUnsafe extends Random.Unsafe:
        private val javaRandom = new java.util.Random()

        def nextInt()(using AllowUnsafe): Int                    = javaRandom.nextInt()
        def nextInt(exclusiveBound: Int)(using AllowUnsafe): Int = javaRandom.nextInt(exclusiveBound)
        def nextLong()(using AllowUnsafe): Long                  = javaRandom.nextLong()
        def nextDouble()(using AllowUnsafe): Double              = javaRandom.nextDouble()
        def nextBoolean()(using AllowUnsafe): Boolean            = javaRandom.nextBoolean()
        def nextFloat()(using AllowUnsafe): Float                = javaRandom.nextFloat()
        def nextGaussian()(using AllowUnsafe): Double            = javaRandom.nextGaussian()
        def nextValue[A](seq: Seq[A])(using AllowUnsafe): A      = seq(javaRandom.nextInt(seq.size))
        def nextValues[A](length: Int, seq: Seq[A])(using AllowUnsafe): Seq[A] =
            Seq.fill(length)(nextValue(seq))
        def nextStringAlphanumeric(length: Int)(using AllowUnsafe): String =
            nextString(length, ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9'))
        def nextString(length: Int, chars: Seq[Char])(using AllowUnsafe): String =
            Seq.fill(length)(nextValue(chars)).mkString
        def nextBytes(length: Int)(using AllowUnsafe): Seq[Byte] =
            val array = new Array[Byte](length)
            javaRandom.nextBytes(array)
            array.toSeq
        end nextBytes
        def shuffle[A](seq: Seq[A])(using AllowUnsafe): Seq[A] =
            scala.util.Random.shuffle(seq)
    end TestUnsafe

end RandomTest
