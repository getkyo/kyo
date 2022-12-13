package kyo

import scala.annotation.targetName
import scala.runtime.AbstractFunction1
import scala.util.control.NonFatal

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

  abstract class Safepoint[E <: Effect[_]] {
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
  private[kyo] abstract class Kyo[M[_], E <: Effect[M], T, U, S](
      val value: M[T],
      val effect: E
  ) extends AbstractFunction1[T, U > S]
      with AKyo[U, S] {
    def apply(v: T): U > S = apply(v, Safepoint.noop[E])
    def apply(v: T, s: Safepoint[E]): U > S
    def isRoot = false
  }

  extension [M[_], T, S](v: M[T] > S) {
    @targetName("suspend")
    inline def >[E <: Effect[M]](e: E): T > (S | E) =
      def suspendLoop(v: M[T] > S): T > (S | E) =
        v match
          case kyo: Kyo[M, E, Any, M[T], S] @unchecked =>
            new Kyo[M, E, Any, T, S | E](kyo.value, kyo.effect) {
              def apply(v: Any, s: Safepoint[E]) = suspendLoop(kyo(v, s))
            }
          case _ =>
            new Kyo[M, E, T, T, S | E](v.asInstanceOf[M[T]], e) {
              override def isRoot                           = true
              def apply(v: T, s: Safepoint[E]): T > (S | E) = v
            }
      suspendLoop(v)
  }

  extension [T, S, S2](v: T > S > S2) {
    @targetName("flatten")
    def apply(): T > (S | S2) =
      v(identity[T > S])
  }

  extension [M[_], T, S](v: M[T > S]) {
    @targetName("nestedEffect")
    inline def >>[E <: Effect[M]](e: E): T > (S | E) =
      new Kyo[M, E, T > S, T, S | E](v, e) {
        def apply(v: T > S, s: Safepoint[E]): T > (S | E) = v
      }
  }

  extension [T, S](v: T > S) {

    inline def map[U, S2](inline f: T => U): U > (S | S2) = apply(f)

    inline def flatMap[U, S2](inline f: T => (U > S2)): U > (S | S2) = apply(f)

    @targetName("transform")
    inline def apply[U, S2](inline f: T => (U > S2)): U > (S | S2) =
      def transformLoop(v: T > S): U > (S | S2) =
        type M[_]
        type E <: Effect[M]
        v match {
          case kyo: Kyo[M, E, Any, T, S] @unchecked =>
            new Kyo[M, E, Any, U, S | S2](kyo.value, kyo.effect) {
              def apply(v: Any, s: Safepoint[E]) =
                if (s()) {
                  s(transformLoop(kyo(v, s))).asInstanceOf[U > (S | S2)]
                } else {
                  transformLoop(kyo(v, s))
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
        s: Safepoint[E]
    ): M[T] > S2 =
      def shallowHandleLoop(
          v: T > (S2 | E)
      ): M[T] > S2 =
        v match {
          case kyo: Kyo[M, E, Any, T, S2 | E] @unchecked =>
            if (e.accepts(kyo.effect)) {
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
            } else {
              new Kyo[M, E, Any, M[T], S2](kyo.value, kyo.effect) {
                def apply(v: Any, s2: Safepoint[E]): M[T] > S2 =
                  shallowHandleLoop {
                    try kyo(v, s)
                    catch {
                      case ex if (NonFatal(ex)) =>
                        h.handle(ex)
                    }
                  }
              }
            }
          case _ =>
            h.pure(v.asInstanceOf[T])
        }
      val r: M[T] > S2 = shallowHandleLoop(v.asInstanceOf[T > (S2 | E)])
      r

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

  inline given effectToHandle[M[_], E <: Effect[M], T](using
      DeepHandler[M, E]
  ): Conversion[E, T > E => M[T] > Nothing] = _()

  private final val _identity: Any => Any = v => v

  private final def identity[T] =
    _identity.asInstanceOf[T => T]

  private val identityConversion = new Conversion[Any, Any] {
    inline def apply(v: Any) = v
  }

  private[kyo] given fromKyo[T, S]: Conversion[Kyo[_, _, _, T, S], T > S] =
    identityConversion.asInstanceOf[Conversion[Kyo[_, _, _, T, S], T > S]]

  inline given toPure[T]: Conversion[T > Nothing, T] =
    identityConversion.asInstanceOf[Conversion[T > Nothing, T]]
  inline given fromPure[T]: Conversion[T, T > Nothing] =
    identityConversion.asInstanceOf[Conversion[T > Nothing, T]]
}
