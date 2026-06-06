package kyo.test.prop

import kyo.Chunk

/** Shrink algorithms for primitive types, exposed as static methods on the companion object.
  *
  * These functions are the canonical shrink implementations used by the built-in Gen instances (Gen.int, Gen.long, Gen.double, Gen.string,
  * Gen.list). They are also available directly for users building custom generators.
  *
  * @see
  *   [[kyo.test.prop.Gen]] the primary generator type whose shrink method delegates here for built-in instances
  * @see
  *   [[kyo.test.prop.PropertyTest]] uses the shrink loop to minimize failing counterexamples
  * @see
  *   [[kyo.test.prop.PropertyFailedException]] carries the shrunk counterexample after the shrink loop completes
  */
object Shrink:

    /** Shrink an Int toward zero.
      *
      * For v = 100: 50, 25, 12, 6, 3, 1, 0. For v = -42: 42, -21, -10, -5, -2, -1, 0. For v = 0: empty.
      */
    def int(v: Int): Chunk[Int] = Gen.shrinkInt(v)

    /** Shrink a Long toward zero. Same halving strategy as Int but for Long values. */
    def long(v: Long): Chunk[Long] = Gen.shrinkLong(v)

    /** Shrink a Double toward 0.0 and integral values.
      *
      * For a finite non-zero v: a negative value tries its positive mirror first; then the integral neighbor (v.toLong.toDouble, e.g.
      * 2.7 -> 2.0) when it differs from v and from 0.0; then sign-preserved halving down to 0.0. There is no 0.001 floor; it shrinks all
      * the way to 0.0. NaN shrinks to Chunk(0.0); +Infinity shrinks to 0.0 then Double.MaxValue then halving; -Infinity mirrors with
      * -Double.MaxValue. Every emitted candidate is finite. For v == 0.0 (including -0.0): empty.
      */
    def double(v: Double): Chunk[Double] = Gen.shrinkDouble(v)

    /** Shrink a String toward empty by removing one character at a time from the end. Always yields "" as the last candidate. */
    def string(v: String): Chunk[String] = Gen.shrinkString(v)

    /** Shrink a Chunk[A] in two phases:
      *   1. Drop one element at a time (from the end first).
      *   2. Shrink each element using the provided element-shrink function (e.g. `Shrink.int`).
      */
    def list[A](chunk: Chunk[A], elemShrink: A => Chunk[A]): Chunk[Chunk[A]] = Gen.shrinkList(chunk, elemShrink)

end Shrink
