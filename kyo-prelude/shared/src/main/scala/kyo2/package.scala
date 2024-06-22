package kyo2

import kyo.Tag
import kyo2.kernel.Safepoint

private[kyo2] type Frame = kernel.Frame
private[kyo2] inline def Frame = kernel.Frame

export kernel.<
export kernel.Loop

private[kyo2] inline def isNull[T](v: T): Boolean =
    v.asInstanceOf[AnyRef] eq null

private[kyo2] inline def discard[T](v: T): Unit =
    val _ = v
    ()

private[kyo2] object bug:

    case class KyoBugException(msg: String) extends Exception(msg)

    def failTag[T, U, S](
        kyo: T < S,
        expected: Tag.Full[U]
    ): Nothing =
        bug(s"Unexpected pending effect while handling ${expected.show}: " + kyo)

    def apply(msg: String): Nothing =
        throw KyoBugException(msg + " Please open an issue 🥹  https://github.com/getkyo/kyo/issues")
end bug
