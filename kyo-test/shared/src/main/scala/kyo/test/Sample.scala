package kyo.test

import kyo.*

type SampleStream[-S, +A] = Stream[Sample[S, A], S]

/** A sample is a single observation from a random variable, together with a tree of "shrinkings" used for minimization of "large" failures.
  */
final case class Sample[-S, +A](value: A, shrink: SampleStream[S, A])(using trace: Trace):
    self =>

    /** A symbolic alias for `zip`. */
    def <*>[S1, B](that: Sample[S1, B])(using zippable: Zippable[A, B], trace: Trace): Sample[S & S1, zippable.Out] =
        self.zip(that)

    /** Filters this sample by replacing it with its shrink tree if the value does not meet the specified predicate and recursively
      * filtering the shrink tree.
      */
    def filter(f: A => Boolean)(using trace: Trace): SampleStream[S, A] =
        if f(value) then Stream(Emit.value(Chunk(Sample(value, shrink.flatMap(_.filter(f))))))
        else shrink.flatMap(_.filter(f))

    def flatMap[S1, B](f: A => Sample[S1, B])(using trace: Trace): Sample[S & S1, B] =
        val sample = f(value)
        Sample(sample.value, sample.shrink.concat(shrink.map(_.flatMap(f)).asInstanceOf[SampleStream[S1, B]]))

    def foreach[S1, B](f: A => B < S1)(using trace: Trace): Sample[S & S1, B] < S1 =
        f(value).map(Sample(_, shrink.map(_.foreach(f))))

    def map[B](f: A => B)(using trace: Trace): Sample[S, B] =
        Sample(f(value), shrink.map(_.map(f)))

    /** Converts the shrink tree into a stream of shrinkings by recursively searching the shrink tree, using the specified function to
      * determine whether a value is a failure. The resulting stream will contain all values explored, regardless of whether they are
      * successes or failures.
      */
    def shrinkSearch(f: A => Boolean)(using trace: Trace): SampleStream[S, A] =
        if !f(value) then Stream(Emit.value(Chunk(this)))
        else Stream(Emit.value(Chunk(this))).concat(shrink.takeWhile(v => !f(v.value)).flatMap(_.shrinkSearch(f)))

    /** Composes this sample with the specified sample to create a cartesian product of values and shrinkings. */
    def zip[S1, B](that: Sample[S1, B])(using zippable: Zippable[A, B], trace: Trace): Sample[S & S1, zippable.Out] =
        self.zipWith(that)(zippable.zip(_, _))

    /** Composes this sample with the specified sample to create a cartesian product of values and shrinkings with the specified function.
      */
    def zipWith[S1, B, C](that: Sample[S1, B])(f: (A, B) => C)(using trace: Trace): Sample[S & S1, C] =
        self.flatMap(a => that.map(b => f(a, b)))
end Sample

object Sample:

    /** A sample without shrinking. */
    def noShrink[A](a: A)(using trace: Trace): Sample[Any, A] =
        Sample(a, Stream.empty)

    def shrinkFractional[A](smallest: A)(a: A)(using F: Fractional[A], trace: Trace): Sample[Any, A] =
        Sample.unfold(a) { max =>
            (
                max,
                Stream.unfold[A, A, Any](smallest) { min =>
                    val mid = F.plus(min, F.div(F.minus(max, min), F.fromInt(2)))
                    if mid == max then None
                    else if F.toDouble(F.abs(F.minus(max, mid))) < 0.001 then Some((min, max))
                    else Some((mid, mid))
                }
            )
        }

    def shrinkIntegral[A](smallest: A)(a: A)(using I: Integral[A], trace: Trace): Sample[Any, A] =
        Sample.unfold(a) { max =>
            (
                max,
                Stream.unfold(smallest) { min =>
                    val mid = I.plus(min, I.quot(I.minus(max, min), I.fromInt(2)))
                    if mid == max then None
                    else if I.equiv(I.abs(I.minus(max, mid)), I.one) then Some((mid, max))
                    else Some((mid, mid))
                }
            )
        }
//  def unfold[R, A, S](s: S)(f: S => (A, ZStream[R, Nothing, S]))(implicit trace: Trace): Sample[R, A] = {
//    val (value, shrink) = f(s)
//    Sample(value, shrink.map(unfold(_)(f)))
//  }
    def unfold[S, A, T](s: T)(f: T => (A, Stream[T, S]))(using trace: Trace): Sample[S, A] =
        val (value, shrink) = f(s)
        Sample[S, A](value, shrink.map(unfold(_)(f)))
end Sample
