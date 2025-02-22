package zio.test

import zio.UIO
import zio.ZIO

trait ZTestEventHandler:
    def handle(event: ExecutionEvent): UIO[Unit]
object ZTestEventHandler:
    val silent: ZTestEventHandler = _ => ZIO.unit
