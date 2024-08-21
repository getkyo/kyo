package kyo

private[kyo] inline def isNull[A](v: A): Boolean =
    v.asInstanceOf[AnyRef] eq null

private[kyo] inline def discard[A](v: A): Unit =
    val _ = v
    ()
