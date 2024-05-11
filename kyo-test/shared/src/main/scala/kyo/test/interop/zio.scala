package kyo.test.interop

import kyo.*
import zio.Trace
import zio.ZIO

extension (capture: ZIO.type)
    private[kyo] def fromKyoFiber[A: Flat](fiber: Fiber[A])(using Trace): ZIO[Any, Throwable, A] =
        ZIO.asyncInterrupt[Any, Throwable, A] { cb =>
            val async: ZIO[Any, Throwable, A] = ZIO.fromFuture(_ => IOs.run(fiber.toFuture))
            cb(async)

            val interrupt: ZIO[Any, Nothing, Any] = ZIO.attempt(IOs.run(fiber.interrupt)).ignore
            Left(interrupt)
        }
