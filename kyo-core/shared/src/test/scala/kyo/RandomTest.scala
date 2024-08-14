package kyoTest

import kyo.*
import kyo.internal.Trace

class randomsTest extends KyoTest:

    "mocked" - {
        val testRandom: Random = new Random:
            def nextInt(using Trace)                                   = 10
            def nextInt(n: Int)(using Trace)                           = Math.min(55, n - 1)
            def nextLong(using Trace)                                  = 20L
            def nextBoolean(using Trace)                               = true
            def nextDouble(using Trace)                                = 30d
            def nextFloat(using Trace)                                 = 40f
            def nextGaussian(using Trace)                              = 50d
            def nextValue[T](seq: Seq[T])(using Trace)                 = seq.last
            def nextValues[T](length: Int, seq: Seq[T])(using Trace)   = Seq.fill(length)(seq.last)
            def nextStringAlphanumeric(length: Int)(using Trace)       = "a" * length
            def nextString(length: Int, chars: Seq[Char])(using Trace) = chars.last.toString * length
            def nextBytes(length: Int)(using Trace)                    = Seq.fill(length)(1.toByte)
            def shuffle[T](seq: Seq[T])(using Trace)                   = seq.reverse
            def unsafe                                                 = ???

        "nextInt" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextInt))
            assert(v == 10)
        }

        "nextInt(n)" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextInt(42)))
            assert(v == 41)
        }

        "nextLong" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextLong))
            assert(v == 20L)
        }

        "nextBoolean" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextBoolean))
            assert(v == true)
        }

        "nextDouble" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextDouble))
            assert(v == 30d)
        }

        "nextFloat" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextFloat))
            assert(v == 40f)
        }

        "nextGaussian" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextGaussian))
            assert(v == 50d)
        }

        "nextValue" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextValue(List(1, 2))))
            assert(v == 2)
        }

        "nextValues" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextValues(3, List(1, 2))))
            assert(v == List(2, 2, 2))
        }

        "nextString" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextStringAlphanumeric(5)))
            assert(v == "aaaaa")
        }

        "nextString with chars" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextString(3, List('x', 'y', 'z'))))
            assert(v == "zzz")
        }

        "nextBytes" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.nextBytes(4)))
            assert(v == Seq(1.toByte, 1.toByte, 1.toByte, 1.toByte))
        }

        "shuffle" in {
            val v = IOs.run(Randoms.let(testRandom)(Randoms.shuffle(Seq(1, 2, 3))))
            assert(v == Seq(3, 2, 1))
        }
    }

    "live" - {
        "nextInt" in {
            val v = IOs.run(Randoms.nextInt)
            assert(v >= Int.MinValue && v <= Int.MaxValue)
        }

        "nextInt(n)" in {
            val n = 10
            val v = IOs.run(Randoms.nextInt(n))
            assert(v >= 0 && v < n)
        }

        "nextLong" in {
            val v = IOs.run(Randoms.nextLong)
            assert(v >= Long.MinValue && v <= Long.MaxValue)
        }

        "nextBoolean" in {
            val v = IOs.run(Randoms.nextBoolean)
            assert(v == true || v == false)
        }

        "nextDouble" in {
            val v = IOs.run(Randoms.nextDouble)
            assert(v >= 0.0 && v < 1.0)
        }

        "nextFloat" in {
            val v = IOs.run(Randoms.nextFloat)
            assert(v >= 0.0f && v < 1.0f)
        }

        "nextGaussian" in {
            val v = IOs.run(Randoms.nextGaussian)
            assert(v >= Double.MinValue && v <= Double.MaxValue)
        }

        "nextValue" in {
            val seq = List(1, 2, 3, 4, 5)
            val v   = IOs.run(Randoms.nextValue(seq))
            assert(seq.contains(v))
        }

        "nextValues" in {
            val seq    = List(1, 2, 3, 4, 5)
            val length = 3
            val v      = IOs.run(Randoms.nextValues(length, seq))
            assert(v.length == length)
            assert(v.forall(seq.contains))
        }

        "nextString" in {
            val length = 5
            val v      = IOs.run(Randoms.nextStringAlphanumeric(length))
            assert(v.length == length)
            assert(v.forall(c =>
                c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
            ))
        }

        "nextString with chars" in {
            val length = 3
            val chars  = List('x', 'y', 'z')
            val v      = IOs.run(Randoms.nextString(length, chars))
            assert(v.length == length)
            assert(v.forall(chars.contains))
        }

        "nextBytes" in {
            val length = 4
            val v      = IOs.run(Randoms.nextBytes(length))
            assert(v.length == length)
            assert(v.forall(b => b == 0.toByte || b == 1.toByte))
        }

        "shuffle" in {
            val v = IOs.run(Randoms.shuffle(Seq(1, 2, 3)))
            assert(v.length == 3)
            assert(v.toSet == Set(1, 2, 3))
        }
    }
end randomsTest
