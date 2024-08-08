package kyo

import kyo.Tag
import kyo.kernel.Safepoint

private[kyo] type Frame = kernel.Frame
private[kyo] inline def Frame = kernel.Frame

type Flat[A] = kernel.Flat[A]
val Flat = kernel.Flat

type <[+A, -S] = kernel.<[A, S]

val Loop = kernel.Loop

private[kyo] inline def isNull[A](v: A): Boolean =
    v.asInstanceOf[AnyRef] eq null

private[kyo] inline def discard[A](v: A): Unit =
    val _ = v
    ()

private[kyo] object bug:

    case class KyoBugException(msg: String) extends Exception(msg)

    def failTag[A, B, S](
        kyo: A < S,
        expected: Tag.Full[B]
    ): Nothing =
        bug(s"Unexpected pending effect while handling ${expected.show}: " + kyo)

    def apply(msg: String): Nothing =
        throw KyoBugException(msg + " Please open an issue 🥹  https://github.com/getkyo/kyo/issues")
end bug
