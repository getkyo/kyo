package kyo

import io.grpc.stub.{ServerCalls, StreamObserver}
import io.grpc.*
import io.grpc.examples.helloworld.helloworld.GreeterGrpc.{METHOD_SAY_HELLO, SERVICE}
// TODO: Why does it double up on the helloworld package name?
import io.grpc.examples.helloworld.helloworld.{HelloReply, HelloRequest}

import scala.util.Try

type Grpcs >: Grpcs.Effects <: Grpcs.Effects

object Grpcs:
  type Effects = Fibers & Aborts[StatusException]

  // TODO: Is there any kind of backpressure or could these closures keep building up?
  def init[T: Flat](t: => T < Grpcs): Fiber[T] =
    def pendingFibers: Try[T] < Fibers = Aborts.run[StatusException].apply[StatusException, T, Fibers, StatusException, Any](t).map(_.toTry)

    val pendingIOs: Fiber[Try[T]] < IOs = Fibers.init(pendingFibers)
    val pendingIOs2: Fiber[T] < IOs = pendingIOs.map(_.transform(_.fold(Fiber.fail, Fiber.value)))
    IOs.run(pendingIOs2)

trait KyoGreeter:
  def sayHello(request: HelloRequest): HelloReply < Grpcs

object GreeterService extends KyoGreeter:
  override def sayHello(request: HelloRequest): HelloReply < Grpcs =
    for {
      _ <- Consoles.run(Consoles.println(s"Got request: $request"))
    } yield HelloReply(s"Hello, ${request.name}")

object HelloWorldServer extends KyoApp:

  private def buildGreeterService(serviceImpl: KyoGreeter): ServerServiceDefinition =
    ServerServiceDefinition.builder(SERVICE)
      .addMethod(
        METHOD_SAY_HELLO,
        // TODO: When to use a different type of call?
        ServerCalls.asyncUnaryCall((request: HelloRequest, observer: StreamObserver[HelloReply]) => {
          val fiber = Grpcs.init(serviceImpl.sayHello(request))
          val pendingIOs = fiber.onComplete { reply =>
            IOs.attempt(reply).map(scalapb.grpc.Grpc.completeObserver(observer))
          }
          IOs.run(pendingIOs)
        }))
      .build()

  private def buildServer(port: Int, services: Seq[ServerServiceDefinition]): Server =
    services.foldLeft(ServerBuilder.forPort(port))(_.addService(_)).build()

  private val port: Int = 9001

  private val services = Seq(
    buildGreeterService(GreeterService)
  )

  run {
    for {
      // TODO: Get the shutdown working properly.
      _ <- Consoles.println(s"Server is running on port $port. Press Ctrl-C to stop.")
      server <- Resources.acquireRelease(IOs(buildServer(port, services).start())) { (server: Server) =>
        IOs.run(Consoles.run(Consoles.print("Shutting down...")))
        // TODO: Add a timeout.
        server.shutdown().awaitTermination()
        IOs.run(Consoles.run(Consoles.println("Done.")))
      }
      _ <- Fibers.sleep(Duration.Infinity)
    } yield {
      "Goodbye!"
    }
  }
