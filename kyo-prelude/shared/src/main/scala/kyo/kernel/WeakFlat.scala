package kyo.kernel

import scala.annotation.implicitNotFound
import scala.util.NotGiven

@implicitNotFound("Detected nested Kyo computation '${A}', please call '.flatten' first.")
case class WeakFlat[A](dummy: Null) extends AnyVal

trait WeakFlatLowPriority:
    inline given WeakFlat[Nothing] = WeakFlat.unsafe.bypass

object WeakFlat extends WeakFlatLowPriority:
    inline given WeakFlat[Unit] = unsafe.bypass

    inline given [A](using inline ng: NotGiven[A <:< (Any < Nothing)]): WeakFlat[A] = unsafe.bypass

    object unsafe:
        inline given bypass[A]: WeakFlat[A] = WeakFlat(null)
end WeakFlat
