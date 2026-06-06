package kyo

class RandomTest extends kyo.test.Test[Any]:

    "mocked" - {
        val testRandom = Random(
            new Random.Unsafe:
                def nextInt()(using AllowUnsafe)                                 = 10
                def nextInt(n: Int)(using AllowUnsafe)                           = Math.min(55, n - 1)
                def nextLong()(using AllowUnsafe)                                = 20L
                def nextBoolean()(using AllowUnsafe)                             = true
                def nextDouble()(using AllowUnsafe)                              = 30d
                def nextFloat()(using AllowUnsafe)                               = 40f
                def nextGaussian()(using AllowUnsafe)                            = 50d
                def nextValue[A](seq: Seq[A])(using AllowUnsafe)                 = seq.last
                def nextValues[A](length: Int, seq: Seq[A])(using AllowUnsafe)   = Seq.fill(length)(seq.last)
                def nextStringAlphanumeric(length: Int)(using AllowUnsafe)       = "a" * length
                def nextString(length: Int, chars: Seq[Char])(using AllowUnsafe) = chars.last.toString * length
                def nextBytes(length: Int)(using AllowUnsafe)                    = Seq.fill(length)(1.toByte)
                def shuffle[A](seq: Seq[A])(using AllowUnsafe)                   = seq.reverse
                override def uuid()(using AllowUnsafe)                           = "mocked-uuid"
        )

        "nextInt" in {
            Random.let(testRandom)(Random.nextInt).map { v =>
                assert(v == 10)
            }
        }

        "nextInt(n)" in {
            Random.let(testRandom)(Random.nextInt(42)).map { v =>
                assert(v == 41)
            }
        }

        "nextLong" in {
            Random.let(testRandom)(Random.nextLong).map { v =>
                assert(v == 20L)
            }
        }

        "nextBoolean" in {
            Random.let(testRandom)(Random.nextBoolean).map { v =>
                assert(v == true)
            }
        }

        "nextDouble" in {
            Random.let(testRandom)(Random.nextDouble).map { v =>
                assert(v == 30d)
            }
        }

        "nextFloat" in {
            Random.let(testRandom)(Random.nextFloat).map { v =>
                assert(v == 40f)
            }
        }

        "nextGaussian" in {
            Random.let(testRandom)(Random.nextGaussian).map { v =>
                assert(v == 50d)
            }
        }

        "nextValue" in {
            Random.let(testRandom)(Random.nextValue(List(1, 2))).map { v =>
                assert(v == 2)
            }
        }

        "nextValues" in {
            Random.let(testRandom)(Random.nextValues(3, List(1, 2))).map { v =>
                assert(v == List(2, 2, 2))
            }
        }

        "nextString" in {
            Random.let(testRandom)(Random.nextStringAlphanumeric(5)).map { v =>
                assert(v == "aaaaa")
            }
        }

        "nextString with chars" in {
            Random.let(testRandom)(Random.nextString(3, List('x', 'y', 'z'))).map { v =>
                assert(v == "zzz")
            }
        }

        "nextBytes" in {
            Random.let(testRandom)(Random.nextBytes(4)).map { v =>
                assert(v == Seq(1.toByte, 1.toByte, 1.toByte, 1.toByte))
            }
        }

        "shuffle" in {
            Random.let(testRandom)(Random.shuffle(Seq(1, 2, 3))).map { v =>
                assert(v == Seq(3, 2, 1))
            }
        }

        "uuid" in {
            Random.let(testRandom)(Random.uuid).map { v =>
                assert(v == "mocked-uuid")
            }
        }
    }

    "live" - {
        "nextInt" in {
            Random.nextInt.map { _ =>
                succeed("nextInt draws an unbounded Int; every Int value is valid so there is no tighter bound to assert")
            }
        }

        "nextInt(n)" in {
            val n = 10
            Random.nextInt(n).map { v =>
                assert(v >= 0 && v < n)
            }
        }

        "nextLong" in {
            Random.nextLong.map { _ =>
                succeed("nextLong draws an unbounded Long; every Long value is valid so there is no tighter bound to assert")
            }
        }

        "nextBoolean" in {
            Random.nextBoolean.map { _ =>
                succeed("nextBoolean draws a Boolean; both true and false are valid so there is no tighter value to assert")
            }
        }

        "nextDouble" in {
            Random.nextDouble.map { v =>
                assert(v >= 0.0 && v < 1.0)
            }
        }

        "nextFloat" in {
            Random.nextFloat.map { v =>
                assert(v >= 0.0f && v < 1.0f)
            }
        }

        "nextGaussian" in {
            Random.nextGaussian.map { v =>
                assert(v >= Double.MinValue && v <= Double.MaxValue)
            }
        }

        "nextValue" in {
            val seq = List(1, 2, 3, 4, 5)
            Random.nextValue(seq).map { v =>
                assert(seq.contains(v))
            }
        }

        "nextValues" in {
            val seq    = List(1, 2, 3, 4, 5)
            val length = 3
            Random.nextValues(length, seq).map { v =>
                assert(v.length == length)
                assert(v.forall(seq.contains))
            }
        }

        "nextString" in {
            val length = 5
            Random.nextStringAlphanumeric(length).map { v =>
                assert(v.length == length)
                assert(v.forall(c =>
                    c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                ))
            }
        }

        "nextString with chars" in {
            val length = 3
            val chars  = List('x', 'y', 'z')
            Random.nextString(length, chars).map { v =>
                assert(v.length == length)
                assert(v.forall(chars.contains))
            }
        }

        "nextBytes" in {
            val length = 4
            Random.nextBytes(length).map { v =>
                assert(v.length == length)
                assert(v.forall(b => b == 0.toByte || b == 1.toByte))
            }
        }

        "shuffle" in {
            Random.shuffle(Seq(1, 2, 3)).map { v =>
                assert(v.length == 3)
                assert(v.toSet == Set(1, 2, 3))
            }
        }

        "uuid" in {
            Random.uuid.map { v =>
                assert(v.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            }
        }

        "uuid uniqueness" in {
            Random.uuid.map { a =>
                Random.uuid.map { b =>
                    assert(a != b)
                }
            }
        }
    }

    "unsafe" - {
        import AllowUnsafe.embrace.danger

        val testUnsafe = new TestUnsafe()

        "should generate nextInt correctly" in {
            discard(testUnsafe.nextInt())
            succeed("unsafe nextInt draws an unbounded Int; every Int value is valid so there is no tighter bound to assert")
        }

        "should generate nextInt with bound correctly" in {
            val bound  = 10
            val result = testUnsafe.nextInt(bound)
            assert(result >= 0 && result < bound)
        }

        "should generate nextLong correctly" in {
            discard(testUnsafe.nextLong())
            succeed("unsafe nextLong draws an unbounded Long; every Long value is valid so there is no tighter bound to assert")
        }

        "should generate nextDouble correctly" in {
            val result = testUnsafe.nextDouble()
            assert(result >= 0.0 && result < 1.0)
        }

        "should generate nextBoolean correctly" in {
            discard(testUnsafe.nextBoolean())
            succeed("unsafe nextBoolean draws a Boolean; both true and false are valid so there is no tighter value to assert")
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

        "should generate uuid correctly" in {
            val result = testUnsafe.uuid()
            assert(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        }

        "should convert to safe Random" in {
            val safeRandom: Random = testUnsafe.safe
            discard(safeRandom)
            succeed("Unsafe.safe returns a safe Random wrapper (verified by the Random ascription)")
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
            def uuid(using Frame)                                      = "context-uuid"
            def unsafe                                                 = ???

        "get should return current Random instance" in {
            Random.let(testRandom) {
                Random.get.map { random =>
                    assert(random.equals(testRandom))
                }
            }
        }

        "use should execute function with current Random" in {
            Random.let(testRandom) {
                Random.use(_.nextInt).map { result =>
                    assert(result == 42)
                }
            }
        }

        "withSeed should create deterministic Random" in {
            val seed = 12345
            for
                result1 <- Random.withSeed(seed)(Random.nextInt)
                result2 <- Random.withSeed(seed)(Random.nextInt)
            yield assert(result1 == result2)
            end for
        }

        "withSeed should produce different results with different seeds" in {
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
