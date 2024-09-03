package kyo

import kyo.Tag

private[kyo] type Frame = kernel.Frame
private[kyo] inline def Frame = kernel.Frame

type Flat[A] = kernel.Flat[A]
val Flat = kernel.Flat

type <[+A, -S] = kernel.<[A, S]

val Loop = kernel.Loop

private[kyo] object bug:

    case class KyoBugException(msg: String) extends Exception(msg)

    def failTag[A, B, S](
        kyo: A < S,
        expected: Tag.Full[B]
    ): Nothing =
        bug(s"Unexpected pending effect while handling ${expected.show}: " + kyo)

    def check(cond: Boolean): Unit =
        if !cond then throw new KyoBugException("Required condition is false.")

    def apply(msg: String): Nothing =
        throw KyoBugException(msg + " Please open an issue 🥹  https://github.com/getkyo/kyo/issues")
end bug
