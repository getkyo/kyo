package kyo

import kyo.debug.Debug

case class Container(id: String) extends AnyVal:

    def state(using Frame): Container.State < (Async & Abort[ContainerException]) =
        Container.Service.use(_.state(id))

    def stop(using Frame): Unit < (Async & Abort[ContainerException]) =
        Container.Service.use(_.stop(id))

    def execute(command: String)(using Frame): String < (Async & Abort[ContainerException]) =
        Container.Service.use(_.execute(id, command))

    def copyTo(source: Path, destination: Path)(using Frame): Unit < (Async & Abort[ContainerException]) =
        Container.Service.use(_.copyTo(id, source, destination))

    def copyFrom(source: Path, destination: Path)(using Frame): Unit < (Async & Abort[ContainerException]) =
        Container.Service.use(_.copyFrom(id, source, destination))

    def logs(using Frame): String < (Async & Abort[ContainerException]) =
        Container.Service.use(_.logs(id))

end Container

case class ContainerException(message: Text, cause: Text | Throwable = "")(using Frame) extends KyoException(message, cause)

object Container:
    enum State derives CanEqual:
        case Running, Stopped, Failed

    case class Config(
        image: String,
        name: Maybe[String] = Absent,
        ports: List[Config.Port] = Nil,
        env: Map[String, String] = Map.empty,
        volumes: List[Config.Volume] = Nil,
        waitFor: Config.WaitFor = Config.WaitFor.default
    ) derives CanEqual

    object Config:
        case class Port(host: Int, container: Int)
        case class Volume(host: Path, container: Path)

        sealed trait WaitFor:
            def apply(container: Container)(using Frame): Unit < (Async & Abort[ContainerException])

        object WaitFor:
            val default = healthCheck()

            def healthCheck(
                command: String = "echo 1",
                expectedResult: String = "1",
                retryPolicy: Schedule = Schedule.fixed(1.second).take(10)
            ): WaitFor =
                new WaitFor:
                    def apply(container: Container)(using frame: Frame) =
                        Retry[ContainerException](retryPolicy) {
                            container.execute(command).map { res =>
                                val x = res
                                println(x)
                                Abort.when(res != "1")(
                                    Abort.fail(ContainerException(s"Invalid health check return. Expected '$expectedResult' but got '$res"))
                                )
                            }
                        }

            case class LogMessage(
                message: String,
                retryPolicy: Schedule = Schedule.fixed(1.second).take(10)
            ) extends WaitFor:
                def apply(container: Container)(using frame: Frame) =
                    Retry[ContainerException](retryPolicy) {
                        container.logs.map { logs =>
                            if logs.contains(message) then ()
                            else Abort.fail(new ContainerException(s"Log message '$message' not found"))
                        }
                    }
            end LogMessage

            case class Port(
                port: Int,
                retryPolicy: Schedule = Schedule.fixed(1.second).take(10)
            ) extends WaitFor:
                def apply(container: Container)(using Frame) =
                    Retry[ContainerException](retryPolicy) {
                        container.execute(s"nc -z localhost $port").unit
                    }
            end Port
        end WaitFor
    end Config

    def init(config: Config)(using Frame): Container < (Async & Abort[ContainerException] & Resource) =
        Service.use { service =>
            for
                id <- service.create(config)
                _  <- service.start(id)
                _  <- Resource.ensure(service.stop(id))
                container = new Container(id)
                _ <- config.waitFor(container)
            yield container
        }

    def init(
        image: String,
        name: Maybe[String] = Absent,
        ports: List[(Int, Int)] = Nil,
        env: Map[String, String] = Map.empty,
        volumes: List[(Path, Path)] = Nil,
        waitFor: Config.WaitFor = Config.WaitFor.healthCheck()
    )(using Frame): Container < (Async & Abort[ContainerException] & Resource) =
        init(Config(
            image,
            name,
            ports.map((h, c) => Config.Port(h, c)),
            env,
            volumes.map((h, c) => Config.Volume(h, c)),
            waitFor
        ))

    abstract class Service:
        def create(config: Config)(using Frame): String < (Async & Abort[ContainerException])
        def start(id: String)(using Frame): Unit < (Async & Abort[ContainerException])
        def stop(id: String)(using Frame): Unit < (Async & Abort[ContainerException])
        def state(id: String)(using Frame): State < (Async & Abort[ContainerException])
        def execute(id: String, command: String)(using Frame): String < (Async & Abort[ContainerException])
        def copyTo(id: String, source: Path, destination: Path)(using Frame): Unit < (Async & Abort[ContainerException])
        def copyFrom(id: String, source: Path, destination: Path)(using Frame): Unit < (Async & Abort[ContainerException])
        def logs(id: String)(using Frame): String < (Async & Abort[ContainerException])
    end Service

    object Service:
        val docker: Service = ProcessService("docker")
        val podman: Service = ProcessService("podman")

        private val local = Local.init[Maybe[Service]](Maybe.empty)

        def let[A, S](service: Service)(v: => A < S)(using Frame): A < S =
            local.let(Maybe(service))(v)

        def use[A, S](f: Service => A < S)(using Frame): A < (Async & Abort[ContainerException] & S) =
            local.use {
                case Absent           => detectService.map(f)
                case Present(service) => f(service)
            }

        class ProcessService(commandName: String) extends Service:
            private def runCommand(args: String*)(using frame: Frame) =
                println(args.mkString(" "))
                val command = Process.Command((commandName +: args)*)
                Abort.run[Throwable](command.text)
                    .map {
                        case Result.Error(ex) =>
                            println(ex)
                            Abort.fail(ContainerException(s"Command failed: $command", ex))
                        case Result.Success(v) =>
                            println(v)
                            v
                    }
            end runCommand

            def create(config: Config)(using Frame) =
                val createArgs = Seq("create") ++
                    config.name.map(n => Seq("--name", n)).getOrElse(Seq.empty) ++
                    config.ports.flatMap(p => Seq("-p", s"${p.host}:${p.container}")) ++
                    config.env.flatMap((k, v) => Seq("-e", s"$k=$v")) ++
                    config.volumes.flatMap(v => Seq("-v", s"${v.host}:${v.container}")) ++
                    Seq(config.image)
                runCommand(createArgs*).map(_.trim)
            end create

            def start(id: String)(using Frame) = runCommand("start", id).unit
            def stop(id: String)(using Frame)  = runCommand("stop", id).unit
            def state(id: String)(using Frame) =
                runCommand("inspect", "-f", "{{.State.Status}}", id).map {
                    case "running" => State.Running
                    case "exited"  => State.Stopped
                    case _         => State.Failed
                }
            def execute(id: String, command: String)(using Frame) =
                runCommand("exec", id, s""""sh -c '$command'"""")
            def copyTo(id: String, source: Path, destination: Path)(using Frame) =
                runCommand("cp", source.toString, s"$id:${destination.toString}").unit
            def copyFrom(id: String, source: Path, destination: Path)(using Frame) =
                runCommand("cp", s"$id:${source.toString}", destination.toString).unit
            def logs(id: String)(using Frame) = runCommand("logs", id)
        end ProcessService

        private def detectService(using frame: Frame): Service < (IO & Abort[ContainerException]) =
            for
                podmanAvailable <- Process.Command("podman", "version").waitFor(1.second)
                dockerAvailable <- Process.Command("docker", "version").waitFor(1.second)
            yield
                if dockerAvailable then Service.docker
                else if podmanAvailable then Service.podman
                else Abort.fail(ContainerException("No supported container service found"))
    end Service

end Container
