package kyo.internal

import kyo.*

/** Shared error-classification primitives used by HttpContainerBackend and ShellBackend.
  *
  * Both backends need to map a [[ResourceContext]] to the correct [[ContainerException]] subtype when the daemon signals resource-not-found
  * (§1.7) or conflict (§1.8). This object is the single place where that dispatch lives.
  *
  * @see
  *   [[DaemonErrorPhrases]] for the shared phrase vocabulary used by both backends.
  */
private[internal] object ErrorClassification:

    /** Construct the appropriate "resource not found" exception for a given context.
      *
      * The `cause` parameter is only forwarded to the [[ContainerOperationException]] produced for the `Op` context — the four
      * resource-specific branches produce exceptions whose constructors do not accept a cause, preserving the `getCause == null` invariant
      * tested by ContainerTest.scala:308-315.
      */
    def missingFor(ctx: ResourceContext, cause: String | Throwable)(using Frame): ContainerException =
        ctx match
            case ResourceContext.Container(id) => ContainerMissingException(id)
            case ResourceContext.Image(ref) =>
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

    /** Phrases matching the "conflict / already in use" condition shared by both backends.
      *
      * HTTP: inferStatusFromMessage maps "already in use" and "name is reserved" to 409 (note: "name is reserved" is HTTP-only, stays
      * inline). Shell: ErrorPatterns.Conflict checks "already in use" (plus "conflict" and "name is already in use" which are Shell-only).
      */
    val AlreadyInUse: Seq[String] = Seq("already in use")

end DaemonErrorPhrases
