package kyo.internal

import kyo.*

/** Shared error-classification primitives used by HttpContainerBackend and ShellBackend.
  *
  * Both backends need to map a [[ResourceContext]] to the correct [[ContainerException]] subtype when the daemon signals
  * resource-not-found or conflict. This object is the single place where that dispatch lives.
  *
  * @see
  *   [[DaemonErrorPhrases]] for the shared phrase vocabulary used by both backends.
  */
private[internal] object ErrorClassification:

    /** Construct the appropriate "resource not found" exception for a given context.
      *
      * The `cause` parameter is only forwarded to the [[ContainerOperationException]] produced for the `Op` context — the four
      * resource-specific branches produce exceptions whose constructors do not accept a cause, preserving a `getCause == null` invariant
      * for typed leaves.
      */
    def missingFor(ctx: ResourceContext, cause: String | Throwable)(using Frame): ContainerException =
        ctx match
            case ResourceContext.Container(id) => ContainerMissingException(id)
            case ResourceContext.Image(ref)    =>
                ContainerImageMissingException(ContainerImage.parse(ref).getOrElse(ContainerImage(ref)))
            case ResourceContext.Network(id) => ContainerNetworkMissingException(id)
            case ResourceContext.Volume(id)  => ContainerVolumeMissingException(id)
            case ResourceContext.Op(name)    => ContainerOperationException(s"Resource not found during $name", cause)

    /** Construct the appropriate "conflict" exception for a given context. */
    def conflictFor(ctx: ResourceContext)(using Frame): ContainerException =
        ctx match
            case ResourceContext.Container(id) => ContainerAlreadyExistsException(id.value)
            case ResourceContext.Op(name)      => ContainerAlreadyExistsException(name)
            case other                         => ContainerAlreadyExistsException(other.describe)

    /** Construct a generic operation-failure exception for a given context. */
    def operationFor(ctx: ResourceContext, cause: String | Throwable)(using Frame): ContainerException =
        ContainerOperationException(s"Operation failed for ${ctx.describe}", cause)

end ErrorClassification

/** Phrase vocabulary shared between HttpContainerBackend.inferStatusFromMessage and ShellBackend.ErrorPatterns.
  *
  * Only phrases that appear in BOTH backends belong here. Shell-only phrases (e.g. "no such object", "image not found", "requested access
  * to the resource is denied", "conflict", "name is already in use") remain in ShellBackend.ErrorPatterns. HTTP-only phrases (e.g. "name is
  * reserved") remain inline in inferStatusFromMessage.
  */
private[internal] object DaemonErrorPhrases:

    /** Phrases matching the "resource not found" condition shared by both backends.
      *
      * HTTP: inferStatusFromMessage maps these to 404. Shell: ErrorPatterns.NoSuchContainer checks these phrases.
      *
      * Note: "no such object" and "no container with name or id" are Shell-only and remain in ErrorPatterns.NoSuchContainer.
      */
    val NoSuchContainer: Seq[String] = Seq("no such container", "no such network", "no such volume")

    /** Phrases matching the "image not found" condition shared by both backends.
      *
      * HTTP: inferStatusFromMessage maps these to 404. Shell: ErrorPatterns.ImageNotFound checks these phrases.
      *
      * Note: "image not found", "requested access to the resource is denied", "repository does not exist", "name unknown" are Shell-only
      * and remain in ErrorPatterns.ImageNotFound.
      */
    val NoSuchImage: Seq[String] = Seq("no such image", "manifest unknown", "image not known")

    /** Phrases by which a daemon quotes a status the registry gave it, rather than answering about the resource itself.
      *
      * Both daemons relay the registry's wording under a status of their own choosing, so the quoted status is the only reliable signal
      * that the failure is the registry's and therefore transient. HTTP: `HttpContainerBackend.bodyNamesRegistryFault` reads the response
      * body. Shell: `ShellBackend.mapError` reads the command's output, where podman prints the same sentence.
      */
    val ServerError: Seq[String] = Seq(
        "500 internal server error",
        "502 bad gateway",
        "503 service unavailable",
        "504 gateway timeout"
    )

    /** Phrases by which a daemon reports that its connection to the registry failed, from Go's network errors.
      *
      * The registry never answered, so nothing was said about the image: the failure is as transient as a quoted server status. Podman
      * prefixes these with `initializing source`, the same opening it uses for an absent image, and the docker CLI prints them as the
      * daemon's message with no status at all, so without these phrases a dropped connection reads as a missing image on one and as an
      * unclassified failure on the other.
      *
      * "connection refused" stays out: the shell backend reads it as the daemon's own socket refusing (`ErrorPatterns.BackendUnavailable`),
      * and a registry that refused arrives over HTTP as the daemon's 5xx, which the status already classifies.
      */
    val RegistryUnreachable: Seq[String] = Seq(
        "connection reset by peer",
        "i/o timeout",
        "tls handshake timeout",
        "no such host"
    )

    /** Phrases matching the "conflict / already in use" condition shared by both backends.
      *
      * HTTP: inferStatusFromMessage maps "already in use" and "name is reserved" to 409 (note: "name is reserved" is HTTP-only, stays
      * inline). Shell: ErrorPatterns.Conflict checks "already in use" (plus "conflict" and "name is already in use" which are Shell-only).
      */
    val AlreadyInUse: Seq[String] = Seq("already in use")

    /** Phrase by which Docker's classic image store refuses a pull of a reference it already holds for another platform. */
    val PlatformCopyConflict: Seq[String] = Seq("cannot overwrite digest")

    /** Phrases matching the "container is not in the running state" condition shared by both backends.
      *
      * The daemon refuses a state-dependent operation (exec-create, top) on a non-running container with one of these. Podman's
      * docker-compat shim reports them as HTTP 500 `container state improper`, which the wire status and `response` field fail to pin
      * down, so matching the body text is the only reliable signal. The HTTP backend maps this family to
      * [[ContainerAlreadyStoppedException]] so callers can tell a not-running condition from an opaque failure. The shell backend
      * classifies the same strings via `ShellBackend.isDockerDaemonError` and `ErrorPatterns.AlreadyStopped`.
      */
    val NotRunning: Seq[String] = Seq(
        "container state improper",
        "can only create exec sessions on running containers",
        "top can only be used on running containers",
        "is not running",
        "is already stopped",
        "already stopped"
    )

end DaemonErrorPhrases
