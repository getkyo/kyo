package kyo

import kyo.debug.Debug

case class Container(config: Container.Config, id: String):

    def status(using Frame): Container.Status < (Async & Abort[ContainerException]) =
        Container.Service.use(_.status(id))

    def stop(using Frame): Unit < (Async & Abort[ContainerException]) =
        Container.Service.use(_.stop(id, config.stopTimeout))

    def execute(command: String)(using Frame): String < (Async & Abort[ContainerException]) =
        Container.Service.use(_.execute(id, command))

    def logs(using Frame): String < (Async & Abort[ContainerException]) =
        Container.Service.use(_.logs(id))

end Container

case class ContainerException(message: Text, cause: Text | Throwable = "")(using Frame) extends KyoException(message, cause)

object Container:
    enum Status derives CanEqual:
        case Running, Stopped, Failed, Created, Paused
        case Other(rawStatus: String)

    case class Config(
        image: String,
        name: Maybe[String] = Absent,
        ports: List[Config.Port] = Nil,
        env: Map[String, String] = Map.empty,
        volumes: List[Config.Volume] = Nil,
        waitFor: Config.WaitFor = Config.WaitFor.default,
        keepAlive: Boolean = true,
        stopTimeout: Duration = 5.seconds
    ) derives CanEqual

    object Config:
        case class Port(host: Int, container: Int)
        case class Volume(host: Path, container: Path)

        sealed trait WaitFor:
            def apply(container: Container)(using Frame): Unit < (Async & Abort[ContainerException])

        object WaitFor:
            val default = HealthCheck()

            case class HealthCheck(
                command: String = "echo ok",
                expectedResult: String = "ok",
                retryPolicy: Schedule = Schedule.fixed(1.second).take(10)
            ) extends WaitFor:
                def apply(container: Container)(using frame: Frame) =
                    Retry[ContainerException](retryPolicy) {
                        container.execute(command).map { res =>
                            Abort.when(res != expectedResult)(
                                Abort.fail(ContainerException(s"Invalid health check return. Expected '$expectedResult' but got '$res"))
                            )
                        }
                    }
            end HealthCheck

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
                _  <- Resource.ensure(service.stop(id, config.stopTimeout))
                container = new Container(config, id)
                _ <- config.waitFor(container)
            yield container
        }

    def init(
        image: String,
        name: Maybe[String] = Absent,
        ports: List[(Int, Int)] = Nil,
        env: Map[String, String] = Map.empty,
        volumes: List[(Path, Path)] = Nil,
        waitFor: Config.WaitFor = Config.WaitFor.default,
        keepAlive: Boolean = true,
        stopTimeout: Duration = 5.seconds
    )(using Frame): Container < (Async & Abort[ContainerException] & Resource) =
        init(Config(
            image,
            name,
            ports.map((h, c) => Config.Port(h, c)),
            env,
            volumes.map((h, c) => Config.Volume(h, c)),
            waitFor,
            keepAlive,
            stopTimeout
        ))

    abstract class Service:
        def create(config: Config)(using Frame): String < (Async & Abort[ContainerException])
        def start(id: String)(using Frame): Unit < (Async & Abort[ContainerException])
        def stop(id: String, timeout: Duration)(using Frame): Unit < (Async & Abort[ContainerException])
        def status(id: String)(using Frame): Status < (Async & Abort[ContainerException])
        def execute(id: String, command: String)(using Frame): String < (Async & Abort[ContainerException])
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
                val command = commandName +: args
                Process.Command(command*).spawn.map { process =>
                    process.waitFor.map {
                        case 0 =>
                            new String(process.stdout.readAllBytes()).trim
                        case code =>
                            Abort.fail(ContainerException(s"Command failed with error code $code: ${command.mkString(" ")}"))
                    }
                }
            end runCommand

            def create(config: Config)(using Frame) =
                val createArgs =
                    Seq("create") ++
                        config.name.map(n => Seq("--name", n)).getOrElse(Seq.empty) ++
                        config.ports.flatMap(p => Seq("-p", s"${p.host}:${p.container}")) ++
                        config.env.flatMap((k, v) => Seq("-e", s"$k=$v")) ++
                        config.volumes.flatMap(v => Seq("-v", s"${v.host}:${v.container}")) ++
                        Seq("-t") ++
                        Seq(config.image) ++
                        (if config.keepAlive then Seq("tail", "-f", "/dev/null") else Seq.empty)
                runCommand(createArgs*)
            end create

            def start(id: String)(using Frame) =
                runCommand("start", id).unit

            def stop(id: String, timeout: Duration)(using Frame) =
                runCommand("stop", s"--time=${timeout.toSeconds}", id).unit

            def status(id: String)(using Frame) =
                runCommand("inspect", "-f", "{{.State.Status}}", id).map(_.toLowerCase).map {
                    case "running"    => Status.Running
                    case "exited"     => Status.Stopped
                    case "stopped"    => Status.Stopped
                    case "created"    => Status.Created
                    case "configured" => Status.Created
                    case "paused"     => Status.Paused
                    case other        => Status.Other(other)
                }

            def execute(id: String, command: String)(using Frame) =
                runCommand("exec", id, "sh", "-c", command)

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
