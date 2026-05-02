package kyo

import kyo.*

/** Sealed exception hierarchy for all container operation failures, organized into five sealed subcategories by failure mode.
  *
  * Each subcategory corresponds to a distinct caller response:
  *   - [[kyo.ContainerBackendException]] — transport and infrastructure failures between client and daemon (daemon unreachable, HTTP
  *     transport error, concurrency meter failure, operation timeout, backend capability missing). Instantiated directly for unclassified
  *     transport issues; specific leaves cover common cases.
  *   - [[kyo.ContainerNotFoundException]] — a referenced resource (container, image, network, volume) does not exist. Always a
  *     resource-specific leaf; never instantiated directly.
  *   - [[kyo.ContainerConflictException]] — a resource exists but is in a state that prevents the requested operation (already running,
  *     already stopped, already exists, port allocated, volume in use). Instantiated directly for unclassified conflicts (e.g., network
  *     endpoint already attached); specific leaves cover common cases.
  *   - [[kyo.ContainerOperationException]] — the daemon received the request and rejected it (container failed to start, exec exited
  *     non-zero, registry authentication failed, build failed, health check failed). Instantiated directly for unclassified operation
  *     rejections; specific leaves cover common cases.
  *   - [[kyo.ContainerDecodeException]] — a response from the daemon could not be parsed. Instantiated directly with a context string and
  *     an underlying cause.
  *
  * Container and image operations fail with `Abort[ContainerException]`. Match on a subcategory to handle a whole class of failures
  * uniformly (`Abort.recover[ContainerConflictException]` to absorb state mismatches), or on a specific leaf for granular handling.
  *
  * @see
  *   [[Container]] Container lifecycle and management
  * @see
  *   [[ContainerImage]] Image operations
  * @see
  *   [[kyo.ContainerBackendException]] Transport-level failures
  * @see
  *   [[kyo.ContainerNotFoundException]] Missing resources
  * @see
  *   [[kyo.ContainerConflictException]] State conflicts
  * @see
  *   [[kyo.ContainerOperationException]] Rejected operations
  * @see
  *   [[kyo.ContainerDecodeException]] Parse failures
  * @see
  *   [[kyo.Abort]] Effect for typed error handling
  */
sealed abstract class ContainerException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

// --- Backend (transport / infrastructure / capability failures) ---

/** Transport, infrastructure, and capability failures between the client and the container daemon.
  *
  * Instantiate directly for unclassified transport issues: HTTP request failures, concurrency meter closures, unexpected panics during a
  * daemon request. Use the specific leaves below when the failure mode is known.
  *
  * @see
  *   [[kyo.ContainerBackendUnavailableException]] Daemon not reachable at all
  * @see
  *   [[kyo.ContainerTimeoutException]] Operation exceeded its configured timeout
  * @see
  *   [[kyo.ContainerNotSupportedException]] Backend does not implement this capability
  */
sealed class ContainerBackendException(message: String, cause: String | Throwable = "")(using Frame)
    extends ContainerException(message, cause) derives CanEqual

/** No Docker or Podman backend is reachable. */
final case class ContainerBackendUnavailableException(backend: String, reason: String)(using Frame)
    extends ContainerBackendException(s"Backend unavailable: $backend", reason) derives CanEqual

/** A daemon operation exceeded the configured timeout. */
final case class ContainerTimeoutException(operation: String, duration: Duration)(using Frame)
    extends ContainerBackendException(s"Operation timed out after ${duration}: $operation") derives CanEqual

/** The selected backend does not implement the requested operation (e.g., CRIU checkpoint on the Shell backend). */
final case class ContainerNotSupportedException(operation: String, detail: String)(using Frame)
    extends ContainerBackendException(s"$operation is not supported", detail) derives CanEqual

// --- NotFound (resource doesn't exist) ---

/** The referenced container, image, network, or volume does not exist. Always a resource-specific leaf; never instantiated directly.
  *
  * @see
  *   [[kyo.ContainerMissingException]] Container not found
  * @see
  *   [[kyo.ContainerImageMissingException]] Image not found
  * @see
  *   [[kyo.ContainerNetworkMissingException]] Network not found
  * @see
  *   [[kyo.ContainerVolumeMissingException]] Volume not found
  */
sealed abstract class ContainerNotFoundException(message: String, cause: String | Throwable = "")(using Frame)
    extends ContainerException(message, cause)

/** The referenced container does not exist. */
final case class ContainerMissingException(id: Container.Id)(using Frame)
    extends ContainerNotFoundException(s"Container not found: ${id.value}") derives CanEqual

/** The referenced image could not be pulled and is not local. */
final case class ContainerImageMissingException(image: ContainerImage)(using Frame)
    extends ContainerNotFoundException(s"Image not found: ${image.reference}") derives CanEqual

/** The referenced network does not exist. */
final case class ContainerNetworkMissingException(id: Container.Network.Id)(using Frame)
    extends ContainerNotFoundException(s"Network not found: ${id.value}") derives CanEqual

/** The referenced volume does not exist. */
final case class ContainerVolumeMissingException(id: Container.Volume.Id)(using Frame)
    extends ContainerNotFoundException(s"Volume not found: ${id.value}") derives CanEqual

// --- Conflict (state conflict) ---

/** A resource exists but is in a state that prevents the requested operation.
  *
  * Instantiate directly for unclassified conflicts (network endpoint already attached, etc.). Use specific leaves when the conflict mode is
  * known.
  *
  * @see
  *   [[kyo.ContainerAlreadyExistsException]] Resource name already taken
  * @see
  *   [[kyo.ContainerAlreadyRunningException]] Container already running
  * @see
  *   [[kyo.ContainerAlreadyStoppedException]] Container already stopped
  * @see
  *   [[kyo.ContainerPortConflictException]] Host port already allocated
  * @see
  *   [[kyo.ContainerVolumeInUseException]] Volume still in use
  */
sealed class ContainerConflictException(message: String, cause: String | Throwable = "")(using Frame)
    extends ContainerException(message, cause) derives CanEqual

/** A container with the requested name already exists. */
final case class ContainerAlreadyExistsException(name: String)(using Frame)
    extends ContainerConflictException(s"Container already exists: $name") derives CanEqual

/** The container is already in the running state. */
final case class ContainerAlreadyRunningException(id: Container.Id)(using Frame)
    extends ContainerConflictException(s"Container already running: ${id.value}") derives CanEqual

/** The container is already in the stopped state. */
final case class ContainerAlreadyStoppedException(id: Container.Id)(using Frame)
    extends ContainerConflictException(s"Container already stopped: ${id.value}") derives CanEqual

/** The requested port is already allocated on the host. */
final case class ContainerPortConflictException(port: Int, detail: String)(using Frame)
    extends ContainerConflictException(s"Port $port is already allocated", detail) derives CanEqual

/** The volume is still attached to one or more containers. */
final case class ContainerVolumeInUseException(id: Container.Volume.Id, containers: String)(using Frame)
    extends ContainerConflictException(s"Volume ${id.value} is in use", containers) derives CanEqual

// --- Operation (daemon received the request and rejected it) ---

/** The daemon received the request and rejected it.
  *
  * Instantiate directly for unclassified operation rejections (daemon returned an error that doesn't match a specific leaf, copy/stat/
  * export/commit operations that failed with an unrecognized reason). Use specific leaves when the failure mode is known.
  *
  * @see
  *   [[kyo.ContainerStartFailedException]] Container failed to start
  * @see
  *   [[kyo.ContainerExecFailedException]] Exec returned non-zero
  * @see
  *   [[kyo.ContainerAuthException]] Registry authentication failed
  * @see
  *   [[kyo.ContainerBuildFailedException]] Image build failed
  * @see
  *   [[kyo.ContainerHealthCheckException]] Health check failed
  */
sealed class ContainerOperationException(message: String, cause: String | Throwable = "")(using Frame)
    extends ContainerException(message, cause) derives CanEqual

/** The container failed to start.
  *
  * @param id
  *   the container that failed to start
  * @param reason
  *   the daemon-reported cause
  */
final case class ContainerStartFailedException(id: Container.Id, reason: String)(using Frame)
    extends ContainerOperationException(s"Container start failed for ${id.value}: $reason") derives CanEqual

/** Exec returned a non-zero exit code.
  *
  * @param id
  *   the container the command ran in
  * @param cmd
  *   the executed command's argv
  * @param exitCode
  *   the non-zero exit code
  * @param stderr
  *   raw stderr captured from the exec; truncated to 500 chars in the formatted message but preserved in full on the field
  */
final case class ContainerExecFailedException private (id: Container.Id, cmd: Chunk[String], exitCode: ExitCode, stderr: String)(using
    Frame
) extends ContainerOperationException(
        s"Exec failed in ${id.value} (exit=${exitCode.toInt}): ${cmd.mkString(" ")}",
        stderr
    ) derives CanEqual
object ContainerExecFailedException:
    private val MaxStderr = 500

    def apply(id: Container.Id, cmd: Chunk[String], exitCode: ExitCode, stderr: String)(using Frame): ContainerExecFailedException =
        val truncated = if stderr.length > MaxStderr then stderr.take(MaxStderr) + "..." else stderr
        new ContainerExecFailedException(id, cmd, exitCode, truncated)
end ContainerExecFailedException

/** Registry authentication failed for a pull or push.
  *
  * @param registry
  *   the target registry host
  * @param detail
  *   the daemon-reported reason
  */
final case class ContainerAuthException(registry: String, detail: String)(using Frame)
    extends ContainerOperationException(s"Authentication failed for $registry", detail) derives CanEqual

/** Image build failed.
  *
  * @param context
  *   the build source (filesystem path or URL)
  * @param detail
  *   the failure stage (e.g. "tar failed", "build command failed", "image tag verification failed after build")
  * @param cause
  *   underlying error, if any
  */
final case class ContainerBuildFailedException(context: String, detail: String, cause: String | Throwable = "")(using Frame)
    extends ContainerOperationException(s"Build failed for $context: $detail", cause) derives CanEqual

/** Container health check failed.
  *
  * @param id
  *   the container whose check failed
  * @param reason
  *   why the check failed (e.g. "exec exited 1", "port 8080 not reachable")
  * @param attempts
  *   number of attempts made against the retry schedule (1 for single-shot checks)
  * @param lastError
  *   raw output or cause from the most recent attempts; truncated per entry by [[Container.healthCheckErrorMessageMaxLength]]
  */
final case class ContainerHealthCheckException(id: Container.Id, reason: String, attempts: Int, lastError: String = "")(using Frame)
    extends ContainerOperationException(
        s"Health check failed for ${id.value} after $attempts attempt(s): $reason",
        lastError
    ) derives CanEqual

// --- Decode (response parse failures) ---

/** The daemon's response could not be parsed. Instantiated directly with a message describing the parse context and a cause (a parser
  * exception or detail string). Programmer-level or daemon version mismatch.
  */
sealed class ContainerDecodeException(message: String, cause: String | Throwable = "")(using Frame)
    extends ContainerException(message, cause) derives CanEqual
