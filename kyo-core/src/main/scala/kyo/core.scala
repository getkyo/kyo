package kyo

import scala.annotation.targetName
import scala.runtime.AbstractFunction1
import scala.util.control.NonFatal
import scala.runtime.AbstractFunction0
import frames._

object core {

  trait Effect[+M[_]] {
    def accepts(other: Effect[_]): Boolean = this eq other
  }

  infix opaque type >[+T, +S] = T | AKyo[T, S]

  sealed abstract class Handler[M[_], E <: Effect[M]] {
    def pure[T](v: T): M[T]
  }

  abstract class ShallowHandler[M[_], E <: Effect[M]] extends Handler[M, E] {
    def handle[T](ex: Throwable): T > E = throw ex
    def apply[T, U, S](m: M[T], f: T => U > (S | E)): U > (S | E)
  }

  abstract class DeepHandler[M[_], E <: Effect[M]] extends Handler[M, E] {
    def map[T, U](m: M[T], f: T => U): M[U] = flatMap(m, v => pure(f(v)))
    def flatten[T](v: M[M[T]]): M[T]        = flatMap(v, identity[M[T]])
    def flatMap[T, U](m: M[T], f: T => M[U]): M[U]
  }

  trait Safepoint[E <: Effect[_]] { self =>
    def apply(): Boolean
    def apply[T, S](v: => T > (S | E)): T > (S | E)
  }
  object Safepoint {
    given noop[E <: Effect[_]]: Safepoint[E] =
      new Safepoint[E] {
        def apply()                        = false
        def apply[T, S](v: => T > (S | E)) = v
      }
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
    def value: M[T] = prev.value
    def effect: E   = prev.effect
  }

  private type M2[_]
  private type E2 <: Effect[M2]

  extension [M[_], T, S](v: M[T] > S) {
    @targetName("suspend")
    inline def >[E <: Effect[M]](inline e: E)(using inline fr: Frame[">"]): T > (S | E) =
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

  extension [T, S](v: T > S) {

    inline def map[U, S2](inline f: T => U): U > (S | S2) = apply(f)

    inline def flatMap[U, S2](inline f: T => (U > S2)): U > (S | S2) = apply(f)

    @targetName("transform")
    inline def apply[U, S2](inline f: T => (U > S2))(using
        inline fr: Frame["apply"]
    ): U > (S | S2) =
      def transformLoop(v: T > S): U > (S | S2) =
        v match {
          case kyo: Kyo[M2, E2, Any, T, S] @unchecked =>
            new KyoCont[M2, E2, Any, U, S | S2](kyo) {
              def frame = fr
              def apply(v: Any, s: Safepoint[E2]) =
                val n = kyo(v, s)
                if (frame.preemptable && s()) {
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
    inline def <[M[_], E <: Effect[M], S2 <: S](
        e: E
    )(using S => S2 | E)(using
        h: ShallowHandler[M, E],
        s: Safepoint[E],
        inline fr: Frame["<"]
    ): M[T] > S2 =
      def shallowHandleLoop(
          v: T > (S2 | E)
      ): M[T] > S2 =
        v match {
          case kyo: Kyo[M, E, Any, T, S2 | E] @unchecked if (e.accepts(kyo.effect)) =>
            if (kyo.isRoot) {
              kyo.value.asInstanceOf[M[T] > S2]
            } else {
              shallowHandleLoop(h(
                  kyo.value,
                  v =>
                    try kyo(v, s)
                    catch {
                      case ex if (NonFatal(ex)) =>
                        h.handle(ex)
                    }
              ))
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
    inline def <<[U](
        f: T > S => U
    ): U = f(v)
  }

  extension [M[_], E <: Effect[M]](e: E) {

    inline def apply[T]()(using
        h: DeepHandler[M, E],
        s: Safepoint[E]
    ): T > E => M[T] > Nothing =
      (v: T > E) =>
        def deepHandleLoop(v: T > E): M[T] =
          v match {
            case kyo: Kyo[M, E, Any, T, E] @unchecked =>
              h.flatMap(kyo.value, (v) => deepHandleLoop(kyo(v, s)))
            case _ =>
              h.pure(v.asInstanceOf[T])
          }
        deepHandleLoop(v)

    inline def apply[M1[_], E1 <: Effect[M1], T](
        tup1: (E1, [U] => M1[M[U]] => M[M1[U]])
    )(using
        h: DeepHandler[M, E],
        h1: DeepHandler[M1, E1],
        s: Safepoint[E],
        s1: Safepoint[E1]
    ): T > (E | E1) => M[M1[T]] =
      (v: T > (E | E1)) =>
        def deepHandleLoop(v: T > (E | E1)): M[M1[T]] =
          v match {
            case kyo: Kyo[M, E, Any, T, E | E1] @unchecked if (kyo.effect eq e) =>
              h.flatMap(kyo.value, (v) => deepHandleLoop(kyo(v, s)))
            case kyo: Kyo[M1, E1, Any, T, E | E1] @unchecked if (kyo.effect eq tup1._1) =>
              val m1 = h1.map(kyo.value, v => deepHandleLoop(kyo(v, s1)))
              h.map(tup1._2(m1), h1.flatten)
            case _ =>
              h.pure(h1.pure(v.asInstanceOf[T]))
          }
        deepHandleLoop(v)

    inline def apply[M1[_], E1 <: Effect[M1], M2[_], E2 <: Effect[M2], T](
        tup1: (E1, [U] => M1[M[U]] => M[M1[U]]),
        tup2: (
            E2,
            [U] => M2[M[U]] => M[M2[U]],
            [U] => M2[M1[U]] => M1[M2[U]]
        )
    )(using
        h: DeepHandler[M, E],
        h1: DeepHandler[M1, E1],
        h2: DeepHandler[M2, E2],
        s: Safepoint[E],
        s1: Safepoint[E1],
        s2: Safepoint[E2]
    ): T > (E | E1 | E2) => M[M1[M2[T]]] =
      (v: T > (E | E1 | E2)) =>
        def deepHandleLoop(v: T > (E | E1 | E2)): M[M1[M2[T]]] =
          v match {
            case kyo: Kyo[M, E, Any, T, E | E1 | E2] @unchecked if (kyo.effect eq e) =>
              h.flatMap(kyo.value, (v) => deepHandleLoop(kyo(v, s)))
            case kyo: Kyo[M1, E1, Any, T, E | E1 | E2] @unchecked if (kyo.effect eq tup1._1) =>
              val m1 = h1.map(kyo.value, v => deepHandleLoop(kyo(v, s1)))
              h.map(tup1._2(m1), h1.flatten)
            case kyo: Kyo[M2, E2, Any, T, E | E1 | E2] @unchecked if (kyo.effect eq tup2._1) =>
              val m2: M2[M[M1[M2[T]]]] =
                h2.map(kyo.value, v => deepHandleLoop(kyo(v, s2)))
              val b: M[M2[M1[M2[T]]]] = tup2._2(m2)
              val c: M[M1[M2[T]]]     = h.map(b, v => h1.map(tup2._3(v), h2.flatten))
              c
            case _ =>
              h.pure(h1.pure(h2.pure(v.asInstanceOf[T])))
          }
        deepHandleLoop(v)
  }

  private final val _identity: Any => Any = v => v

  private final def identity[T] =
    _identity.asInstanceOf[T => T]

  abstract class InlineConversion[T, U] extends Conversion[T, U] {
    inline def apply(v: T): U
  }

  given [M[_], E <: Effect[M], T](using
      DeepHandler[M, E]
  ): InlineConversion[E, T > E => M[T] > Nothing] with
    inline def apply(v: E) = v()

  given [T, S]: InlineConversion[Kyo[_, _, _, T, S], T > S] with
    inline def apply(v: Kyo[_, _, _, T, S]) = v

  given [T]: InlineConversion[T > Nothing, T] with
    inline def apply(v: T > Nothing) = v.asInstanceOf[T]

  given [T]: InlineConversion[T, T > Nothing] with
    inline def apply(v: T) = v.asInstanceOf[T > Nothing]
}
