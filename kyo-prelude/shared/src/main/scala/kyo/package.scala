package kyo2

import kyo.Tag
import kyo2.kernel.Safepoint

private[kyo2] type Frame = kernel.Frame
private[kyo2] inline def Frame = kernel.Frame

type Flat[A] = kernel.Flat[A]
val Flat = kernel.Flat

type <[+A, -S] = kernel.<[A, S]

val Loop = kernel.Loop

private[kyo2] inline def isNull[A](v: A): Boolean =
    v.asInstanceOf[AnyRef] eq null

private[kyo2] inline def discard[A](v: A): Unit =
    val _ = v
    ()

private[kyo2] object bug:

    case class KyoBugException(msg: String) extends Exception(msg)

    def failTag[A, B, S](
        kyo: A < S,
        expected: Tag.Full[B]
    ): Nothing =
        bug(s"Unexpected pending effect while handling ${expected.show}: " + kyo)

    def apply(msg: String): Nothing =
        throw KyoBugException(msg + " Please open an issue 🥹  https://github.com/getkyo/kyo/issues")
end bug
