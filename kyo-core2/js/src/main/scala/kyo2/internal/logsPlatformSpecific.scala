package kyo2.internal

import kyo2.Log

trait logsPlatformSpecific:
    val unsafe: Log.Unsafe = Log.Unsafe.ConsoleLogger("kyo.logs")
