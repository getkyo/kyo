package kyo

import kyo.locals.Locals.State

import scala.runtime.AbstractFunction0
import scala.runtime.AbstractFunction1
import scala.util.control.NonFatal
import scala.language.higherKinds

import locals._

object core {

  import internal._

  abstract class Handler[M[_], E <: Effect[M, E]] {
    def pure[T](v: T): M[T]
    def handle[T](ex: Throwable): T > E = throw ex
    def apply[T, U, S](m: M[T], f: T => U > (E with S)): U > (E with S)
  }

  abstract class Effect[M[_], E <: Effect[M, E]] {
    self: E =>

    def accepts[M2[_], E2 <: Effect[M2, E2]](other: Effect[M2, E2]): Boolean = this eq other

    /*inline*/
    protected final def suspend[T, S](v: M[T] > S): T > (S with E) = {
      def suspendLoop(v: M[T] > S): T > (S with E) = {
        v match {
          case kyo: Kyo[MX, EX, Any, M[T], S] @unchecked =>
            new KyoCont[MX, EX, Any, T, S with E](kyo) {
              def apply(v: Any, s: Safepoint[MX, EX], l: Locals.State) =
                suspendLoop(kyo(v, s, l))
            }
          case _ =>
            new KyoRoot[M, E, T, S with E](v.asInstanceOf[M[T]], this) {}
        }
      }
      suspendLoop(v)
    }

    /*inline*/
    protected final def handle[T, S](v: T > (E with S))(implicit
        h: Handler[M, E],
        s: Safepoint[M, E]
    ): M[T] > S = {
      def handleLoop(
          v: T > (S with E)
      ): M[T] > S =
        v match {
          case kyo: Kyo[M, E, Any, T, S with E] @unchecked if (accepts(kyo.effect)) =>
            if (kyo.isRoot) {
              kyo.value.asInstanceOf[M[T] > S]
            } else {
              handleLoop(h[Any, T, S with E](
                  kyo.value,
                  kyo(_, s, Locals.State.empty)
              ))
            }
          case kyo: Kyo[MX, EX, Any, T, S with E] @unchecked =>
            new KyoCont[MX, EX, Any, M[T], S](kyo) {
              def apply(v: Any, s2: Safepoint[MX, EX], l: Locals.State): M[T] > S =
                handleLoop {
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
      handleLoop(v)
    }
  }

  /*inline*/
  def transform[T, S, U, S2](v: T > S)( /*inline*/ f: T => (U > S2)): U > (S with S2) = {
    def transformLoop(v: T > S): U > (S with S2) =
      v match {
        case kyo: Kyo[MX, EX, Any, T, S] @unchecked =>
          new KyoCont[MX, EX, Any, U, S with S2](kyo) {
            def apply(v: Any, s: Safepoint[MX, EX], l: Locals.State) = {
              val n = kyo(v, s, l)
              if (s()) {
                s[U, S with S2](transformLoop(n))
                  .asInstanceOf[U > (S with S2)]
              } else {
                transformLoop(n)
              }
            }
          }
        case _ =>
          f(v.asInstanceOf[T])
      }
    transformLoop(v)
  }

  trait Safepoint[M[_], E <: Effect[M, _]] {
    def apply(): Boolean
    def apply[T, S](v: => T > S): T > (S with E)
  }

  object Safepoint {
    private val _noop = new Safepoint[MX, EX] {
      def apply()                  = false
      def apply[T, S](v: => T > S) = v
    }
    implicit def noop[M[_], E <: Effect[M, E]]: Safepoint[M, E] =
      _noop.asInstanceOf[Safepoint[M, E]]
  }

  private[kyo] object internal {

    /*inline*/
    def deepHandle[M[_], E <: Effect[M, E], T](e: E)(v: T > E)(implicit
        h: DeepHandler[M, E],
        s: Safepoint[M, E]
    ): M[T] = {
      def deepHandleLoop(v: T > E): M[T] =
        v match {
          case kyo: Kyo[M, E, Any, T, E] @unchecked =>
            require(kyo.effect == e, "Unhandled effect: " + kyo.effect)
            h.apply(kyo.value, (v: Any) => deepHandleLoop(kyo(v, s, Locals.State.empty)))
          case _ =>
            h.pure(v.asInstanceOf[T])
        }
      deepHandleLoop(v)
    }

    abstract class Injection[M1[_], E1 <: Effect[M1, E1], M2[_], E2 <: Effect[M2, E2]] {
      def apply[T](m: M1[T]): M2[T]
    }

    def inject[T, M1[_], M2[_], E1 <: Effect[M1, E1], E2 <: Effect[M2, E2], S](
        from: E1,
        to: E2
    )(v: T > (E1 with E2 with S))(
        implicit i: Injection[M1, E1, M2, E2]
    ): T > (E2 with S) = {
      def injectLoop(v: T > (S with E1 with E2)): T > (E2 with S) = {
        v match {
          case kyo: Kyo[M1, E1, Any, T, S with E1 with E2] @unchecked if from.accepts(kyo.effect) =>
            new Kyo[M2, E2, Any, T, E2 with S] {
              val value: M2[Any] = i(kyo.value)
              def effect: E2     = to
              def apply(v: Any, s: Safepoint[M2, E2], l: State) =
                injectLoop(kyo(v, Safepoint.noop, l))
            }
          case kyo: Kyo[MX, EX, Any, T, S with E1 with E2] @unchecked =>
            new KyoCont[MX, EX, Any, T, E2 with S](kyo) {
              def apply(v: Any, s: Safepoint[MX, EX], l: Locals.State) =
                injectLoop(kyo(v, s, l))
            }
          case _ =>
            v.asInstanceOf[T > (E2 with S)]
        }
      }
      injectLoop(v)
    }

    type MX[_] = Any
    type EX    = Effect[MX, _]

    abstract class DeepHandler[M[_], E <: Effect[M, E]] {
      def pure[T](v: T): M[T]
      def apply[T, U](m: M[T], f: T => M[U]): M[U]
    }

    abstract class Kyo[M[_], E <: Effect[M, _], T, U, S] {
      def value: M[T]
      def effect: E
      def apply(v: T, s: Safepoint[M, E], l: Locals.State): U > S
      def isRoot: Boolean = false
    }

    abstract class KyoRoot[M[_], E <: Effect[M, _], T, S](v: M[T], e: E)
        extends Kyo[M, E, T, T, S] {
      final def value  = v
      final def effect = e
      final def apply(v: T, s: Safepoint[M, E], l: Locals.State) =
        v.asInstanceOf[T > S]
      override final def isRoot = true
    }

    abstract class KyoCont[M[_], E <: Effect[M, _], T, U, S](prev: Kyo[M, E, T, _, _])
        extends Kyo[M, E, T, U, S] {
      final val value: M[T] = prev.value
      final val effect: E   = prev.effect
    }

    implicit def fromKyo[M[_], E <: Effect[M, _], T, U, S](v: Kyo[M, E, T, U, S]): U > S =
      v.asInstanceOf[U > S]
  }
}
