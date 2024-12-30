package kyo

import kyo.Tag

type <[+A, -S] = kernel.<[A, S]

val Loop = kernel.Loop

/** Identity type constructor.
  *
  * Id is a simple type alias that returns its input type unchanged. It is commonly used with [[ArrowEffect]] when an effect needs to
  * preserve the exact type it operates on without modification.
  *
  * @tparam A
  *   The type to pass through unchanged
  */
type Id[A] = A

/** Constant type constructor.
  *
  * Const ignores its second type parameter and always returns the first type. It is commonly used with [[ArrowEffect]] when an effect only
  * needs to work with a fixed type regardless of what type it's applied to.
  *
  * @tparam A
  *   The constant type to return
  */
type Const[A] = [B] =>> A

private[kyo] object bug:

    case class KyoBugException(msg: String) extends Exception(msg)

    def failTag[A, B, S](
        kyo: A < S,
        expected: Tag[B]
    ): Nothing =
        bug(s"Unexpected pending effect while handling ${expected.show}: " + kyo)

    def check(cond: Boolean): Unit =
        if !cond then throw new KyoBugException("Required condition is false.")

    def apply(msg: String): Nothing =
        throw KyoBugException(msg + " Please open an issue 🥹  https://github.com/getkyo/kyo/issues")
end bug
