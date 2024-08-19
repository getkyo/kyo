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

        "nextInt" in {
            val v = IO.run(Random.let(testRandom)(Random.nextInt)).eval
            assert(v == 10)
        }

        "nextInt(n)" in {
            val v = IO.run(Random.let(testRandom)(Random.nextInt(42))).eval
            assert(v == 41)
        }

        "nextLong" in {
            val v = IO.run(Random.let(testRandom)(Random.nextLong)).eval
            assert(v == 20L)
        }

        "nextBoolean" in {
            val v = IO.run(Random.let(testRandom)(Random.nextBoolean)).eval
            assert(v == true)
        }

        "nextDouble" in {
            val v = IO.run(Random.let(testRandom)(Random.nextDouble)).eval
            assert(v == 30d)
        }

        "nextFloat" in {
            val v = IO.run(Random.let(testRandom)(Random.nextFloat)).eval
            assert(v == 40f)
        }

        "nextGaussian" in {
            val v = IO.run(Random.let(testRandom)(Random.nextGaussian)).eval
            assert(v == 50d)
        }

        "nextValue" in {
            val v = IO.run(Random.let(testRandom)(Random.nextValue(List(1, 2)))).eval
            assert(v == 2)
        }

        "nextValues" in {
            val v = IO.run(Random.let(testRandom)(Random.nextValues(3, List(1, 2)))).eval
            assert(v == List(2, 2, 2))
        }

        "nextString" in {
            val v = IO.run(Random.let(testRandom)(Random.nextStringAlphanumeric(5))).eval
            assert(v == "aaaaa")
        }

        "nextString with chars" in {
            val v = IO.run(Random.let(testRandom)(Random.nextString(3, List('x', 'y', 'z')))).eval
            assert(v == "zzz")
        }

        "nextBytes" in {
            val v = IO.run(Random.let(testRandom)(Random.nextBytes(4))).eval
            assert(v == Seq(1.toByte, 1.toByte, 1.toByte, 1.toByte))
        }

        "shuffle" in {
            val v = IO.run(Random.let(testRandom)(Random.shuffle(Seq(1, 2, 3)))).eval
            assert(v == Seq(3, 2, 1))
        }
    }

    "live" - {
        "nextInt" in {
            val v = IO.run(Random.nextInt).eval
            assert(v >= Int.MinValue && v <= Int.MaxValue)
        }

        "nextInt(n)" in {
            val n = 10
            val v = IO.run(Random.nextInt(n)).eval
            assert(v >= 0 && v < n)
        }

        "nextLong" in {
            val v = IO.run(Random.nextLong).eval
            assert(v >= Long.MinValue && v <= Long.MaxValue)
        }

        "nextBoolean" in {
            val v = IO.run(Random.nextBoolean).eval
            assert(v == true || v == false)
        }

        "nextDouble" in {
            val v = IO.run(Random.nextDouble).eval
            assert(v >= 0.0 && v < 1.0)
        }

        "nextFloat" in {
            val v = IO.run(Random.nextFloat).eval
            assert(v >= 0.0f && v < 1.0f)
        }

        "nextGaussian" in {
            val v = IO.run(Random.nextGaussian).eval
            assert(v >= Double.MinValue && v <= Double.MaxValue)
        }

        "nextValue" in {
            val seq = List(1, 2, 3, 4, 5)
            val v   = IO.run(Random.nextValue(seq)).eval
            assert(seq.contains(v))
        }

        "nextValues" in {
            val seq    = List(1, 2, 3, 4, 5)
            val length = 3
            val v      = IO.run(Random.nextValues(length, seq)).eval
            assert(v.length == length)
            assert(v.forall(seq.contains))
        }

        "nextString" in {
            val length = 5
            val v      = IO.run(Random.nextStringAlphanumeric(length)).eval
            assert(v.length == length)
            assert(v.forall(c =>
                c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
            ))
        }

        "nextString with chars" in {
            val length = 3
            val chars  = List('x', 'y', 'z')
            val v      = IO.run(Random.nextString(length, chars)).eval
            assert(v.length == length)
            assert(v.forall(chars.contains))
        }

        "nextBytes" in {
            val length = 4
            val v      = IO.run(Random.nextBytes(length)).eval
            assert(v.length == length)
            assert(v.forall(b => b == 0.toByte || b == 1.toByte))
        }

        "shuffle" in {
            val v = IO.run(Random.shuffle(Seq(1, 2, 3))).eval
            assert(v.length == 3)
            assert(v.toSet == Set(1, 2, 3))
        }
    }
end RandomTest
