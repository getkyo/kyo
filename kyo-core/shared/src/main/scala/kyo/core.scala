package kyo

import kyo.locals.Locals.State

import scala.annotation.targetName
import scala.runtime.AbstractFunction0
import scala.runtime.AbstractFunction1
import scala.util.NotGiven
import scala.util.control.NonFatal

import frames._
import locals._

object core {

  trait Effect[+M[_]] {
    def accepts(other: Effect[_]): Boolean = this eq other
  }

  infix opaque type >[+T, +S] = T | AKyo[T, S]

  abstract class Handler[M[_], E <: Effect[M]] {
    def pure[T](v: T): M[T]
    def handle[T](ex: Throwable): T > E = throw ex
    def apply[T, U, S](m: M[T], f: T => U > (S | E)): U > (S | E)
  }

  private[kyo] abstract class Injection[M1[_], E1 <: Effect[M1], M2[_], E2 <: Effect[M2], S] {
    def apply[T](m: M1[T]): M2[T] > S
  }

  private[kyo] abstract class DeepHandler[M[_], E <: Effect[M]] {
    def pure[T](v: T): M[T]
    def apply[T, U](m: M[T], f: T => M[U]): M[U]
  }

  trait Safepoint[E <: Effect[_]] {
    def apply(): Boolean
    def apply[T, S](v: => T > (S | E)): T > (S | E)
  }
  object Safepoint {
    private val _noop = new Safepoint[Effect[_]] {
      def apply()                                = false
      def apply[T, S](v: => T > (S | Effect[_])) = v
    }
    /*inline(3)*/
    given noop[E <: Effect[_]]: Safepoint[E] =
      _noop.asInstanceOf[Safepoint[E]]
  }

  private[kyo] sealed trait AKyo[+T, +S]
  private[kyo] abstract class Kyo[M[_], E <: Effect[M], T, U, S]
      extends AKyo[U, S] {
    def value: M[T]
    def effect: E
    def frame: Frame[String]
    def apply(v: T, s: Safepoint[E], l: Locals.State): U > S
  }

  private[kyo] abstract class KyoRoot[M[_], E <: Effect[M], T, S](fr: Frame[String], v: M[T], e: E)
      extends Kyo[M, E, T, T, S] {
    def frame  = fr
    def value  = v
    def effect = e
    def apply(v: T, s: Safepoint[E], l: Locals.State) =
      v
  }

  private[kyo] abstract class KyoCont[M[_], E <: Effect[M], T, U, S](prev: Kyo[M, E, T, _, _])
      extends Kyo[M, E, T, U, S] {
    final val value: M[T] = prev.value
    final val effect: E   = prev.effect
  }

  private type MX[_]
  private type EX <: Effect[MX]

  extension [M[_], T, S](v: M[T] > S) {
    @targetName("suspend")
    /*inline(3)*/
    def >[E <: Effect[M]]( /*inline(3)*/ e: E)(using /*inline(3)*/ fr: Frame[">"]): T > (S | E) =
      def suspendLoop(v: M[T] > S): T > (S | E) =
        v match
          case kyo: Kyo[MX, EX, Any, M[T], S] @unchecked =>
            new KyoCont[MX, EX, Any, T, S | E](kyo) {
              def frame = fr
              def apply(v: Any, s: Safepoint[EX], l: Locals.State) =
                suspendLoop(kyo(v, s, l))
            }
          case _ =>
            new KyoRoot(fr, v.asInstanceOf[M[T]], e) {}
      suspendLoop(v)
  }

  extension [T, S, S2](v: T > S > S2) {
    private[kyo] def flatten: T > (S | S2) =
      v.map(v => v)
  }

  /*inline(3)*/
  private[kyo] def zip[T1, T2, S](v1: T1 > S, v2: T2 > S): (T1, T2) > S =
    v1.map(t1 => v2.map(t2 => (t1, t2)))

  /*inline(3)*/
  private[kyo] def zip[T1, T2, T3, S](v1: T1 > S, v2: T2 > S, v3: T3 > S): (T1, T2, T3) > S =
    v1.map(t1 => v2.map(t2 => v3.map(t3 => (t1, t2, t3))))

  /*inline(3)*/
  private[kyo] def zip[T1, T2, T3, T4, S](
      v1: T1 > S,
      v2: T2 > S,
      v3: T3 > S,
      v4: T4 > S
  ): (T1, T2, T3, T4) > S =
    v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => (t1, t2, t3, t4)))))

  extension [S](v: Unit > S) {
    private[kyo] def andThen[T, S2](f: => T > S2): T > (S | S2) =
      v.map(_ => f)
    private[kyo] def repeat(i: Int): Unit > S =
      if i <= 0 then () else v.andThen(repeat(i - 1))
    private[kyo] def forever: Unit > S =
      v.andThen(forever)
  }

  extension [T, S](v: T > S) {

    /*inline(3)*/
    private[kyo] def unit: Unit > S = map(_ => ())

    /*inline(3)*/
    private[kyo] def map[U, S2]( /*inline(3)*/ f: T => (U > S2)): U > (S | S2) =
      v.flatMap(f)

    /*inline(3)*/
    private[kyo] def flatMap[U, S2]( /*inline(3)*/ f: T => (U > S2)): U > (S | S2) =
      v[U, S2](f)

    /*inline(3)*/
    private[kyo] def withFilter(p: T => Boolean): T > S =
      v.map(v => if (!p(v)) throw new MatchError(v) else v)

    /*inline(3)*/
    private[kyo] def apply[U, S2]( /*inline(3)*/ f: T => (U > S2))(using
        /*inline(3)*/ fr: Frame["apply"]
    ): U > (S | S2) =
      def transformLoop(v: T > S): U > (S | S2) =
        v match {
          case kyo: Kyo[MX, EX, Any, T, S] @unchecked =>
            new KyoCont[MX, EX, Any, U, S | S2](kyo) {
              def frame = fr
              def apply(v: Any, s: Safepoint[EX], l: Locals.State) =
                val n = kyo(v, s, l)
                if (s()) {
                  s(transformLoop(n)).asInstanceOf[U > (S | S2)]
                } else {
                  transformLoop(n)
                }
            }
          case _ =>
            f(v.asInstanceOf[T])
        }
      transformLoop(v)

    @targetName("inject")
    /*inline(3)*/
    private[kyo] def >>[M1[_], M2[_], E1 <: Effect[M1], E2 <: Effect[M2], S2, S3](tup: (E1, E2))(
        using
        /*inline(3)*/ i: Injection[M1, E1, M2, E2, S3],
        /*inline(3)*/ fr: Frame["<<"]
    )(using
        S => (S2 | E1 | E2)
    ): T > (S2 | S3 | E2) =
      def injectLoop(v: T > (S | E1 | E2)): T > (S2 | S3 | E2) =
        v match {
          case kyo: Kyo[M1, E1, Any, T, S | E1 | E2] @unchecked if tup._1.accepts(kyo.effect) =>
            i(kyo.value) { v =>
              new Kyo[M2, E2, Any, T, S2 | S3 | E2] {
                def value: M2[Any]       = v
                def effect: E2           = tup._2
                def frame: Frame[String] = fr
                def apply(v: Any, s: Safepoint[E2], l: State) =
                  injectLoop(kyo(v, Safepoint.noop, l))
              }
            }
          case kyo: Kyo[MX, EX, Any, T, S | E1 | E2] @unchecked =>
            new KyoCont[MX, EX, Any, T, S2 | S3 | E2](kyo) {
              def frame = fr
              def apply(v: Any, s: Safepoint[EX], l: Locals.State) =
                injectLoop(kyo(v, s, l))
            }
          case _ =>
            v.asInstanceOf[T > (S2 | S3 | E2)]
        }
      injectLoop(v)

    @targetName("shallowHandle")
    /*inline(3)*/
    def <[M[_], E <: Effect[M], S2 <: S](
        e: E
    )(using S => S2 | E)(using
        h: Handler[M, E],
        s: Safepoint[E],
        /*inline(3)*/ fr: Frame["<"]
    ): M[T] > S2 =
      def shallowHandleLoop(
          v: T > (S2 | E)
      ): M[T] > S2 =
        v match {
          case kyo: Kyo[M, E, Any, T, S2 | E] @unchecked if (e.accepts(kyo.effect)) =>
            if (kyo.isInstanceOf[KyoRoot[_, _, _, _]]) {
              kyo.value.asInstanceOf[M[T] > S2]
            } else {
              shallowHandleLoop(h(kyo.value, kyo(_, s, Locals.State.empty)))
            }
          case kyo: Kyo[MX, EX, Any, T, S2 | E] @unchecked =>
            val e = kyo.effect
            new Kyo[MX, EX, Any, M[T], S2] {
              def frame  = fr
              def value  = kyo.value
              def effect = e
              def apply(v: Any, s2: Safepoint[EX], l: Locals.State): M[T] > S2 =
                shallowHandleLoop {
                  try kyo(v, s2, l)
                  catch {
                    case ex if (NonFatal(ex)) =>
                      h.handle(ex)
                  }
                }
            }
          case _ =>
            h.pure(v.asInstanceOf[T])
        }
      shallowHandleLoop(v.asInstanceOf[T > (S2 | E)])

    @targetName("deepHandle")
    /*inline(3)*/
    private[kyo] def <<[U](
        f: T > S => U
    ): U = f(v)
  }

  extension [M[_], E <: Effect[M]](e: E) {

    /*inline(3)*/
    private[kyo] def apply[T]()(using
        h: DeepHandler[M, E],
        s: Safepoint[E]
    ): T > E => M[T] =
      (v: T > E) =>
        def deepHandleLoop(v: T > E): M[T] =
          v match {
            case kyo: Kyo[M, E, Any, T, E] @unchecked =>
              h.apply(kyo.value, (v) => deepHandleLoop(kyo(v, s, Locals.State.empty)))
            case _ =>
              h.pure(v.asInstanceOf[T])
          }
        deepHandleLoop(v)
  }

  given [M[_], E <: Effect[M], T](using
      DeepHandler[M, E]
  ): Conversion[E, T > E => M[T] > Nothing] with
    /*inline(3)*/
    def apply(v: E) = v()

  private val identityConversion = new Conversion[Any, Any] {
    def apply(v: Any) = v
  }

  /*inline(3)*/
  given [T, S](using /*inline(3)*/ ng: NotGiven[T <:< (Any > Any)])
      : Conversion[Kyo[_, _, _, T, S], T > S] =
    identityConversion.asInstanceOf[Conversion[Kyo[_, _, _, T, S], T > S]]

  /*inline(3)*/
  given [T](using /*inline(3)*/ ng: NotGiven[T <:< (Any > Any)]): Conversion[T > Nothing, T] =
    identityConversion.asInstanceOf[Conversion[T > Nothing, T]]

  /*inline(3)*/
  given [T](using /*inline(3)*/ ng: NotGiven[T <:< (Any > Any)]): Conversion[T, T > Nothing] =
    identityConversion.asInstanceOf[Conversion[T, T > Nothing]]
}
