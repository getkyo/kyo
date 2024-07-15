package kyo.grpc

import io.grpc.*
import kyo.*
import sun.misc.Signal

abstract class KyoGrpServerApp extends KyoApp {

    protected def buildServer(port: Int, services: Seq[ServerServiceDefinition]): Server =
        services.foldLeft(ServerBuilder.forPort(port))(_.addService(_)).build()

    // This is required until https://github.com/getkyo/kyo/issues/491 is done.
    // Put it here so that it can be converted to a no-op without breaking compatibility or behaviour.
    protected def waitForInterrupt: Unit < Fibers =
        for {
            promise <- Fibers.initPromise[Unit]
            _ <- IOs(Signal.handle(new Signal("INT"),  _ => IOs.run(promise.complete(()).unit))).unit
            _ <- IOs(Signal.handle(new Signal("TERM"), _ => IOs.run(promise.complete(()).unit))).unit
            _ <- promise.get
        } yield ()
}
