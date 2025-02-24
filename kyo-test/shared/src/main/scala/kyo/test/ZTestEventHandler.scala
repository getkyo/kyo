package kyo.test

import kyo.Abort
import kyo.Env
import kyo.IO
import kyo.debugln

// Assuming ExecutionEvent is defined elsewhere in the test framework
trait ZTestEventHandler:
    def handle(event: ExecutionEvent): Unit < (Env[Any] & IO & Abort[Nothing])

object ZTestEventHandler:
    val silent: ZTestEventHandler = new ZTestEventHandler:
        def handle(event: ExecutionEvent): Unit < (Env[Any] & IO & Abort[Nothing]) = ()
