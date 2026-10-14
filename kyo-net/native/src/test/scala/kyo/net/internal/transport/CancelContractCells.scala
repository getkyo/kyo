package kyo.net.internal.transport

import kyo.net.internal.posix.PosixCancelCells

/** Scala Native's [[CancelCell]]s: the posix drivers (no NIO floor on Native). */
object CancelContractCells:
    val all: Seq[CancelCell] = PosixCancelCells.all
end CancelContractCells
