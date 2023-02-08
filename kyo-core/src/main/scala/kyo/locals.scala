package kyo

import core._
import frames._
import ios._

object locals {

  trait Local[T] {

    import Locals._

    def default: T

    /*inline(3)*/
    def get(using /*inline(3)*/ fr: Frame["apply"]): T > Locals =
      new Kyo[Local, Locals, T, T, Locals] {
        def frame  = fr
        def value  = Local.this
        def effect = Locals
        def apply(v: T, s: Safepoint[Locals]): T > Locals =
          v
      }

    /*inline(3)*/
    def let[U, S](v: T)(f: U > S)(using /*inline(3)*/ fr: Frame["let"]): U > (S | Locals) =
      def loop(f: U > S): U > (S | Locals) =
        f match {
          case kyo: Kyo[Local, Locals, State, U, S] @unchecked if (kyo.value eq saveState) =>
            new Kyo[Local, Locals, State, U, S | Locals] {
              def frame  = fr
              def value  = saveState
              def effect = Locals
              def apply(st: State, s: Safepoint[Locals]): U > (S | Locals) =
                loop(kyo(Locals.add(st, Local.this, v), s))
            }
          case kyo: Kyo[Local, Locals, T, U, S] @unchecked if (kyo.value eq this) =>
            loop(kyo(v, Safepoint.noop))
          case kyo: Kyo[M2, E2, Any, U, S] @unchecked =>
            new KyoCont[M2, E2, Any, U, S | Locals](kyo) {
              def frame = fr
              def apply(v2: Any, s: Safepoint[E2]) =
                loop(kyo(v2, s))
            }
          case _ =>
            f
        }
      loop(f)
  }

  object Local {
    /*inline(3)*/
    def apply[T](defaultValue: T)(using /*inline(3)*/ fr: Frame["drop"]): Local[T] =
      new Local[T] {
        def default           = defaultValue
        override def toString = s"Local($fr)"
      }
  }

  private type M2[_]
  private type E2 <: Effect[M2]

  final class Locals private[locals] extends Effect[Local] {

    opaque type State = Map[Local[_], Any]

    private[locals] val saveState = Local[State](Map.empty)

    private[locals] def add[T](s: State, l: Local[T], v: T): State =
      s.updated(l, v)

    /*inline(3)*/
    def save: State > Locals =
      saveState.get

    /*inline(3)*/
    def restore[T, S](st: State)(f: T > S)(using /*inline(3)*/ fr: Frame["restore"])
        : T > (S | Locals) =
      def loop(f: T > S): T > (S | Locals) =
        f match {
          case kyo: Kyo[Local, Locals, State, T, S] @unchecked if (kyo.value eq saveState) =>
            new Kyo[Local, Locals, State, T, S | Locals] {
              def frame  = fr
              def value  = saveState
              def effect = Locals
              def apply(st2: State, s: Safepoint[Locals]): T > (S | Locals) =
                loop(kyo(st2 ++ st, s))
            }
          case kyo: Kyo[Local, Locals, Any, T, S] @unchecked if (st.contains(kyo.value)) =>
            loop(kyo(st(kyo.value), Safepoint.noop))
          case kyo: Kyo[M2, E2, Any, T, S] @unchecked =>
            new KyoCont[M2, E2, Any, T, S | Locals](kyo) {
              def frame = fr
              def apply(v2: Any, s: Safepoint[E2]) =
                loop(kyo(v2, s))
            }
          case _ =>
            f
        }
      loop(f)

    /*inline(3)*/
    def drop[T, S](v: T > (S | Locals))(using /*inline(3)*/ fr: Frame["drop"]): T > S =
      def loop(v: T > (S | Locals)): T > S =
        v match {
          case kyo: Kyo[Local, Locals, State, T, S | Locals] @unchecked
              if (kyo.value eq saveState) =>
            loop(kyo(Map.empty, Safepoint.noop))
          case kyo: Kyo[Local, Locals, Any, T, S] @unchecked if (kyo.effect eq Locals) =>
            loop(kyo(kyo.value.default, Safepoint.noop))
          case kyo: Kyo[M2, E2, Any, T, S | Locals] @unchecked =>
            new KyoCont[M2, E2, Any, T, S](kyo) {
              def frame = fr
              def apply(v2: Any, s: Safepoint[E2]) =
                loop {
                  kyo(v2, s)
                }
            }
          case _ =>
            v.asInstanceOf[T]
        }
      loop(v)
  }
  val Locals = new Locals
}
