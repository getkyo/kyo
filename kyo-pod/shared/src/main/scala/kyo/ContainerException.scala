package kyo

/** Sealed exception hierarchy for all container operation failures.
  *
  * All container operations (Container, ContainerImage) fail with `Abort[ContainerException]`. Pattern match on subtypes for granular error
  * handling: `NotFound` for missing containers, `ImageNotFound` for missing images, `BackendUnavailable` when neither Docker nor Podman is
  * installed, etc. Use `Abort.recover` to provide fallback behavior.
  *
  * `General` is the catch-all for unclassified backend errors that don't match a specific subtype.
  *
  * @see
  *   [[Container]] Container lifecycle and management
  * @see
  *   [[ContainerImage]] Image operations
  * @see
  *   [[kyo.Abort]] Effect for typed error handling
  */
sealed abstract class ContainerException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

object ContainerException:
    case class NotFound(id: Container.Id)(using Frame)
        extends ContainerException(s"Container not found: ${id.value}", id.value) derives CanEqual
    case class ImageNotFound(image: ContainerImage)(using Frame)
        extends ContainerException(s"Image not found: ${image.reference}", image.reference) derives CanEqual
    case class AlreadyExists(name: String)(using Frame)
        extends ContainerException(s"Container already exists: $name", name) derives CanEqual
    case class AlreadyRunning(id: Container.Id)(using Frame)
        extends ContainerException(s"Container already running: ${id.value}", id.value) derives CanEqual
    case class AlreadyStopped(id: Container.Id)(using Frame)
        extends ContainerException(s"Container already stopped: ${id.value}", id.value) derives CanEqual
    case class StartFailed(id: Container.Id, reason: String)(using Frame)
        extends ContainerException(s"Container start failed: $reason", reason) derives CanEqual
    case class ExecFailed(id: Container.Id, cmd: Chunk[String], exitCode: ExitCode, stderr: String)(using Frame)
        extends ContainerException(s"Exec failed: ${cmd.mkString(" ")}", stderr) derives CanEqual
    case class NetworkNotFound(id: Container.Network.Id)(using Frame)
        extends ContainerException(s"Network not found: ${id.value}", id.value) derives CanEqual
    case class VolumeNotFound(id: Container.Volume.Id)(using Frame)
        extends ContainerException(s"Volume not found: ${id.value}", id.value) derives CanEqual
    case class BackendUnavailable(backend: String, reason: String)(using Frame)
        extends ContainerException(s"Backend unavailable: $backend", reason) derives CanEqual
    case class Timeout(operation: String, duration: Duration)(using Frame)
        extends ContainerException(s"Operation timed out: $operation", operation) derives CanEqual
    case class AuthenticationError(target: String, detail: String)(using Frame)
        extends ContainerException(s"Authentication failed for $target", detail) derives CanEqual
    case class PortConflict(port: Int, detail: String)(using Frame)
        extends ContainerException(s"Port $port is already allocated", detail) derives CanEqual
    case class VolumeInUse(id: Container.Volume.Id, containers: String)(using Frame)
        extends ContainerException(s"Volume ${id.value} is in use", containers) derives CanEqual
    case class NotSupported(operation: String, detail: String)(using Frame)
        extends ContainerException(s"$operation is not supported", detail) derives CanEqual
    case class ParseError(context: String, detail: String | Throwable)(using Frame)
        extends ContainerException(s"Parse error in $context", detail) derives CanEqual
    case class General(msg: String, err: String | Throwable)(using Frame)
        extends ContainerException(msg, err) derives CanEqual
end ContainerException
