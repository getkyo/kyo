package kyo.bench

import kyo.Tag
import kyo.internal.TagHash
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Setup

/** Candidate bodies for `Tag`'s fast-path equality, over the only inputs that reach it.
  *
  * Equal encodings are answered by the `eq` short circuit in front of the body, so every pair here is unequal and the question is which
  * shape rejects one for less. `eq` alone is measured as the unreachable floor: it cannot serve as the body, because `=:=` treats a false
  * from the fast path as final for a concrete tag and would then report two identical types as different.
  *
  * The three inputs separate the cases the JVM costs differently: a short pair, a pair whose encodings share a nine-thousand character
  * prefix, and a pair whose hashes agree so the hash rejects nothing.
  *
  * JDK 25 on arm64 macOS, four comparisons per op: short 228.6M ops/s for hash-then-equals against 175.6M for equals-only, deep 235.7M
  * against 0.671M, colliding 1.82M against 1.85M. The deep row decides the shape. Rejecting on the memoized hash costs two field reads
  * where equals scans nine thousand characters, and tags reach that size routinely: `Tag[List[Aa]]` for an empty local class already
  * encodes to 10,351 characters.
  *
  * The short row needs three forks and ten iterations to mean anything. At roughly a nanosecond per comparison a single five-iteration
  * fork reports errors near the score itself, wide enough to invent a regression that three forks show is not there.
  */
class TagFastPathBench extends BaseBench:

    class Aa
    class BB

    private def literal[A](tag: Tag[A]): String =
        tag match
            case value: String => value
            case _             => throw new AssertionError("Benchmark input must be a derived static Tag")

    private val shortLefts = Array(
        literal(Tag[Int]),
        literal(Tag[Long]),
        literal(Tag[Boolean]),
        literal(Tag[Double])
    )
    private val shortRights = Array(
        literal(Tag[Long]),
        literal(Tag[Boolean]),
        literal(Tag[Double]),
        literal(Tag[Int])
    )

    private val deepLefts = Array(
        literal(Tag[Map[String, List[Either[(Int, Long, Char), Vector[Option[Set[Char]]]]]]]),
        literal(Tag[Map[String, List[Either[(Int, Long, Byte), Vector[Option[Set[Char]]]]]]]),
        literal(Tag[Map[String, List[Either[(Int, Long, Char), Vector[Option[Set[Byte]]]]]]]),
        literal(Tag[Map[String, List[Either[(Int, Long, Byte), Vector[Option[Set[Byte]]]]]]])
    )
    private val deepRights = Array(
        literal(Tag[Map[String, List[Either[(Int, Long, Byte), Vector[Option[Set[Byte]]]]]]]),
        literal(Tag[Map[String, List[Either[(Int, Long, Char), Vector[Option[Set[Byte]]]]]]]),
        literal(Tag[Map[String, List[Either[(Int, Long, Byte), Vector[Option[Set[Char]]]]]]]),
        literal(Tag[Map[String, List[Either[(Int, Long, Char), Vector[Option[Set[Char]]]]]]])
    )

    private val collidingLefts = Array(
        literal(Tag[Aa]),
        literal(Tag[List[Aa]]),
        literal(Tag[Vector[Aa]]),
        literal(Tag[Option[Aa]])
    )
    private val collidingRights = Array(
        literal(Tag[BB]),
        literal(Tag[List[BB]]),
        literal(Tag[Vector[BB]]),
        literal(Tag[Option[BB]])
    )

    private def firstDiff(a: String, b: String): Int =
        val limit = math.min(a.length, b.length)
        var index = 0
        while index < limit && a.charAt(index) == b.charAt(index) do index += 1
        index
    end firstDiff

    /** Fails the trial rather than reporting timings for inputs that do not have the shape each case is named for. */
    @Setup(Level.Trial)
    def describe(): Unit =
        def report(name: String, lefts: Array[String], rights: Array[String]): Unit =
            lefts.indices.foreach { index =>
                val left  = lefts(index)
                val right = rights(index)
                require(left != right, s"$name pair $index is equal, so it would never reach the body")
                println(
                    s"TAG_FASTPATH_INPUT input=$name pair=$index left_length=${left.length} " +
                        s"right_length=${right.length} first_diff=${firstDiff(left, right)} " +
                        s"hash_collides=${TagHash.of(left) == TagHash.of(right)}"
                )
            }
        report("short", shortLefts, shortRights)
        report("deep", deepLefts, deepRights)
        report("colliding", collidingLefts, collidingRights)
        require(
            collidingLefts.indices.forall(index => TagHash.of(collidingLefts(index)) == TagHash.of(collidingRights(index))),
            "The colliding case requires every pair's hashes to agree"
        )
    end describe

    // Separate loops keep the benchmark from adding a function-value call to every comparison.
    private def eqOnly(lefts: Array[String], rights: Array[String]): Int =
        var index    = 0
        var checksum = 0
        while index < lefts.length do
            if lefts(index) eq rights(index) then checksum += 1
            index += 1
        checksum
    end eqOnly

    private def hashThenEquals(lefts: Array[String], rights: Array[String]): Int =
        var index    = 0
        var checksum = 0
        while index < lefts.length do
            val left  = lefts(index)
            val right = rights(index)
            if TagHash.of(left) == TagHash.of(right) && left.equals(right) then checksum += 1
            index += 1
        end while
        checksum
    end hashThenEquals

    private def equalsOnly(lefts: Array[String], rights: Array[String]): Int =
        var index    = 0
        var checksum = 0
        while index < lefts.length do
            if lefts(index).equals(rights(index)) then checksum += 1
            index += 1
        checksum
    end equalsOnly

    /** The predicate `Tag` actually calls. It should cost what this platform's chosen candidate costs; a gap is the trait call failing to
      * inline, which would be a regression the candidate columns cannot show.
      */
    private def production(lefts: Array[String], rights: Array[String]): Int =
        var index    = 0
        var checksum = 0
        while index < lefts.length do
            if Tag.equalEncodings(lefts(index), rights(index)) then checksum += 1
            index += 1
        checksum
    end production

    @Benchmark def shortEq: Int             = eqOnly(shortLefts, shortRights)
    @Benchmark def shortHashThenEquals: Int = hashThenEquals(shortLefts, shortRights)
    @Benchmark def shortEqualsOnly: Int     = equalsOnly(shortLefts, shortRights)
    @Benchmark def shortProduction: Int     = production(shortLefts, shortRights)

    @Benchmark def deepEq: Int             = eqOnly(deepLefts, deepRights)
    @Benchmark def deepHashThenEquals: Int = hashThenEquals(deepLefts, deepRights)
    @Benchmark def deepEqualsOnly: Int     = equalsOnly(deepLefts, deepRights)
    @Benchmark def deepProduction: Int     = production(deepLefts, deepRights)

    @Benchmark def collidingEq: Int             = eqOnly(collidingLefts, collidingRights)
    @Benchmark def collidingHashThenEquals: Int = hashThenEquals(collidingLefts, collidingRights)
    @Benchmark def collidingEqualsOnly: Int     = equalsOnly(collidingLefts, collidingRights)
    @Benchmark def collidingProduction: Int     = production(collidingLefts, collidingRights)

end TagFastPathBench
