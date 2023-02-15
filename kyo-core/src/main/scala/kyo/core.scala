package kyo

import scala.annotation.targetName
import scala.runtime.AbstractFunction1
import scala.util.control.NonFatal
import scala.runtime.AbstractFunction0
import frames._
import scala.util.NotGiven

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

  private[kyo] abstract class DeepHandler[M[_], E <: Effect[M]] {
    def pure[T](v: T): M[T]
    def map[T, U](m: M[T], f: T => U): M[U] = flatMap(m, v => pure(f(v)))
    def flatten[T](v: M[M[T]]): M[T]        = flatMap(v, identity[M[T]])
    def flatMap[T, U](m: M[T], f: T => M[U]): M[U]
  }

  private[kyo] trait Safepoint[E <: Effect[_]] { self =>
    def apply(): Boolean
    def apply[T, S](v: => T > (S | E)): T > (S | E)
  }
  private[kyo] object Safepoint {
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
    def apply(v: T, s: Safepoint[E]): U > S
    def isRoot = false
  }

  private[kyo] class KyoRoot[M[_], E <: Effect[M], T, S](fr: Frame[String], v: M[T], e: E)
      extends Kyo[M, E, T, T, S] {
    def frame  = fr
    def value  = v.asInstanceOf[M[T]]
    def effect = e

    override def isRoot = true

    def apply(v: T, s: Safepoint[E]): T > S = v
  }

  private[kyo] abstract class KyoCont[M[_], E <: Effect[M], T, U, S](prev: Kyo[M, E, T, _, _])
      extends Kyo[M, E, T, U, S] {
    val value: M[T] = prev.value
    val effect: E   = prev.effect
  }

  private type M2[_]
  private type E2 <: Effect[M2]

  extension [M[_], T, S](v: M[T] > S) {
    @targetName("suspend")
    /*inline(3)*/
    def >[E <: Effect[M]]( /*inline(3)*/ e: E)(using /*inline(3)*/ fr: Frame[">"]): T > (S | E) =
      def suspendLoop(v: M[T] > S): T > (S | E) =
        v match
          case kyo: Kyo[M2, E2, Any, M[T], S] @unchecked =>
            new KyoCont[M2, E2, Any, T, S | E](kyo) {
              def frame = fr
              def apply(v: Any, s: Safepoint[E2]) =
                suspendLoop(kyo(v, s))
            }
          case _ =>
            KyoRoot(fr, v.asInstanceOf[M[T]], e)
      suspendLoop(v)
  }

  extension [T, S, S2](v: T > S > S2) {
    @targetName("flatten")
    def apply()(using Frame["apply"]): T > (S | S2) =
      v(identity[T > S])
  }

  extension [M[_], T, S](v: M[T > S]) {
    @targetName("nestedEffect")
    def >>[E <: Effect[M]](e: E)(using fr: Frame[">>"]): T > (S | E) =
      new Kyo[M, E, T > S, T, S | E] {
        def frame  = fr
        def value  = v
        def effect = e
        def apply(v: T > S, s: Safepoint[E]): T > (S | E) =
          v
      }
  }

  /*inline(3)*/
  def zip[T1, T2, S](v1: T1 > S, v2: T2 > S): (T1, T2) > S =
    v1(t1 => v2(t2 => (t1, t2)))

  /*inline(3)*/
  def zip[T1, T2, T3, S](v1: T1 > S, v2: T2 > S, v3: T3 > S): (T1, T2, T3) > S =
    v1(t1 => v2(t2 => v3(t3 => (t1, t2, t3))))

  /*inline(3)*/
  def zip[T1, T2, T3, T4, S](v1: T1 > S, v2: T2 > S, v3: T3 > S, v4: T4 > S): (T1, T2, T3, T4) > S =
    v1(t1 => v2(t2 => v3(t3 => v4(t4 => (t1, t2, t3, t4)))))

  extension [T, S](v: T > S) {

    /*inline(3)*/
    def unit: Unit > S = map(_ => ())

    /*inline(3)*/
    def map[U]( /*inline(3)*/ f: T => U): U > S = apply(f)

    /*inline(3)*/
    def flatMap[U, S2]( /*inline(3)*/ f: T => (U > S2)): U > (S | S2) = apply(f)

    /*inline(3)*/
    def withFilter(p: T => Boolean): T > S =
      v(v => if (!p(v)) throw new MatchError(v) else v)

    @targetName("transform")
    /*inline(3)*/
    def apply[U, S2]( /*inline(3)*/ f: T => (U > S2))(using
        /*inline(3)*/ fr: Frame["apply"]
    ): U > (S | S2) =
      def transformLoop(v: T > S): U > (S | S2) =
        v match {
          case kyo: Kyo[M2, E2, Any, T, S] @unchecked =>
            new KyoCont[M2, E2, Any, U, S | S2](kyo) {
              def frame = fr
              def apply(v: Any, s: Safepoint[E2]) =
                val n = kyo(v, s)
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
            if (kyo.isRoot) {
              kyo.value.asInstanceOf[M[T] > S2]
            } else {
              shallowHandleLoop(h(kyo.value, kyo(_, s)))
            }
          case kyo: Kyo[M2, E2, Any, T, S2 | E] @unchecked =>
            val e = kyo.effect
            new Kyo[M2, E2, Any, M[T], S2] {
              def frame  = fr
              def value  = kyo.value
              def effect = e
              def apply(v: Any, s2: Safepoint[E2]): M[T] > S2 =
                shallowHandleLoop {
                  try kyo(v, s2)
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
              h.flatMap(kyo.value, (v) => deepHandleLoop(kyo(v, s)))
            case _ =>
              h.pure(v.asInstanceOf[T])
          }
        deepHandleLoop(v)
  }

  private final val _identity: Any => Any = v => v

  private final def identity[T] =
    _identity.asInstanceOf[T => T]

  abstract class InlineConversion[T, U] extends Conversion[T, U] {
    /*inline(3)*/
    def apply(v: T): U
  }

  given [M[_], E <: Effect[M], T](using
      DeepHandler[M, E]
  ): InlineConversion[E, T > E => M[T] > Nothing] with
    /*inline(3)*/
    def apply(v: E) = v()

  given [T, S](using NotGiven[T <:< (Any > Any)]): InlineConversion[Kyo[_, _, _, T, S], T > S] with
    /*inline(3)*/
    def apply(v: Kyo[_, _, _, T, S]) = v

  given [T](using NotGiven[T <:< (Any > Any)]): InlineConversion[T > Nothing, T] with
    /*inline(3)*/
    def apply(v: T > Nothing) = v.asInstanceOf[T]

  given [T](using NotGiven[T <:< (Any > Any)]): InlineConversion[T, T > Nothing] with
    /*inline(3)*/
    def apply(v: T) = v.asInstanceOf[T > Nothing]
}
