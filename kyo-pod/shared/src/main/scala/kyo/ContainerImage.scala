package kyo

/** Structured representation of a container image reference with registry, namespace, name, tag, and digest.
  *
  * Use predefined constants for common images (Alpine, Nginx, Ubuntu, etc.) or factory methods for custom images. The `parse` method
  * converts string references like "docker.io/library/alpine:3.19" into structured form, and `reference` produces the canonical string.
  *
  * Tag and digest are mutually exclusive: `withDigest` clears the tag, and `withTag` clears the digest. The `apply("name")` factory
  * defaults to tag "latest".
  *
  * @see
  *   [[ContainerImage.pull]] Pull an image from a registry
  * @see
  *   [[ContainerImage.ensure]] Pull only if not already present locally
  * @see
  *   [[Container.Config]] Container configuration referencing an image
  */
case class ContainerImage(
    name: String,
    namespace: Maybe[String] = Absent,
    registry: Maybe[ContainerImage.Registry] = Absent,
    tag: Maybe[String] = Absent,
    digest: Maybe[ContainerImage.Digest] = Absent
) derives CanEqual:

    /** Compute the full image reference string (e.g. "docker.io/library/alpine:latest" or "myapp@sha256:abc"). */
    def reference: String =
        val base = (registry, namespace) match
            case (Present(r), Present(ns)) => s"${r.value}/$ns/$name"
            case (Present(r), _)           => s"${r.value}/$name"
            case (_, Present(ns))          => s"$ns/$name"
            case _                         => name

        (tag, digest) match
            case (_, Present(d)) => s"$base@${d.value}"
            case (Present(t), _) => s"$base:$t"
            case _               => base
        end match
    end reference

    /** Set the image tag. IMPORTANT: clears digest, since tag and digest are mutually exclusive. */
    def withTag(t: String): ContainerImage = copy(tag = Present(t), digest = Absent)

    /** Set the image digest. IMPORTANT: clears tag, since tag and digest are mutually exclusive. */
    def withDigest(d: ContainerImage.Digest): ContainerImage     = copy(digest = Present(d), tag = Absent)
    def withRegistry(r: ContainerImage.Registry): ContainerImage = copy(registry = Present(r))
    def withNamespace(n: String): ContainerImage                 = copy(namespace = Present(n))
    def onDockerHub: ContainerImage                              = withRegistry(ContainerImage.Registry.DockerHub)
    def onGitHub: ContainerImage                                 = withRegistry(ContainerImage.Registry.GitHub)

end ContainerImage

object ContainerImage:

    // --- Operations ---

    /** Pull an image from a registry. Checks locally first. */
    def pull(
        image: ContainerImage,
        platform: Maybe[Container.Platform] = Absent,
        auth: Maybe[RegistryAuth] = Absent
    )(using Frame): Unit < (Async & Abort[ContainerException]) =
        currentBackend.map(_.imagePull(image, platform, auth))

    /** Pull an image only if it is not already present locally. */
    def ensure(
        image: ContainerImage,
        platform: Maybe[Container.Platform] = Absent,
        auth: Maybe[RegistryAuth] = Absent
    )(using Frame): Unit < (Async & Abort[ContainerException]) =
        currentBackend.map(_.imageEnsure(image, platform, auth))

    /** List local images (non-intermediate only). */
    def list(using Frame): Chunk[Summary] < (Async & Abort[ContainerException]) =
        list(all = false, filters = Map.empty)

    /** List local images. When `all` is true, includes intermediate layers. */
    def list(
        all: Boolean,
        filters: Map[String, Seq[String]] = Map.empty
    )(using Frame): Chunk[Summary] < (Async & Abort[ContainerException]) =
        currentBackend.map(_.imageList(all, filters))

    /** Full image metadata (ID, tags, digests, size, labels, architecture, OS). */
    def inspect(image: ContainerImage)(using Frame): Info < (Async & Abort[ContainerException]) =
        currentBackend.map(_.imageInspect(image))

    /** Remove a local image. If `force` is true, removes even if containers reference it. */
    def remove(
        image: ContainerImage,
        force: Boolean = false,
        noPrune: Boolean = false
    )(using Frame): Chunk[DeleteResponse] < (Async & Abort[ContainerException]) =
        currentBackend.map(_.imageRemove(image, force, noPrune))

    /** Add a tag to an existing image. */
    def tag(
        source: ContainerImage,
        repo: String,
        tag: String = "latest"
    )(using Frame): Unit < (Async & Abort[ContainerException]) =
        currentBackend.map(_.imageTag(source, repo, tag))

    /** Build an image from a tar-archived build context stream. Returns build progress events. */
    def build(
        context: Stream[Byte, Sync],
        dockerfile: String = "Dockerfile",
        tags: Seq[String] = Seq.empty,
        buildArgs: Map[String, String] = Map.empty,
        labels: Map[String, String] = Map.empty,
        noCache: Boolean = false,
        pull: Boolean = false,
        target: Maybe[String] = Absent,
        platform: Maybe[Container.Platform] = Absent,
        auth: Maybe[RegistryAuth] = Absent
    )(using Frame): Stream[BuildProgress, Async & Abort[ContainerException]] =
        Stream {
            currentBackend.map(_.imageBuild(context, dockerfile, tags, buildArgs, labels, noCache, pull, target, platform, auth).emit)
        }

    /** Build an image from a Dockerfile directory path. Returns build progress events. */
    def buildFromPath(
        path: Path,
        dockerfile: String = "Dockerfile",
        tags: Seq[String] = Seq.empty,
        buildArgs: Map[String, String] = Map.empty,
        labels: Map[String, String] = Map.empty,
        noCache: Boolean = false,
        pull: Boolean = false,
        target: Maybe[String] = Absent,
        platform: Maybe[Container.Platform] = Absent,
        auth: Maybe[RegistryAuth] = Absent
    )(using Frame): Stream[BuildProgress, Async & Abort[ContainerException]] =
        Stream {
            currentBackend.map(_.imageBuildFromPath(path, dockerfile, tags, buildArgs, labels, noCache, pull, target, platform, auth).emit)
        }

    /** Push an image to its registry. */
    def push(
        image: ContainerImage,
        auth: Maybe[RegistryAuth] = Absent
    )(using Frame): Unit < (Async & Abort[ContainerException]) =
        currentBackend.map(_.imagePush(image, auth))

    /** Search registries for images matching the given term. */
    def search(
        term: String,
        limit: Int = Int.MaxValue,
        filters: Map[String, Seq[String]] = Map.empty
    )(using Frame): Chunk[SearchResult] < (Async & Abort[ContainerException]) =
        currentBackend.map(_.imageSearch(term, limit, filters))

    /** Image layer history showing how each layer was created. */
    def history(image: ContainerImage)(using Frame): Chunk[HistoryEntry] < (Async & Abort[ContainerException]) =
        currentBackend.map(_.imageHistory(image))

    /** Remove all unused (dangling) images. */
    def prune(using Frame): Container.PruneResult < (Async & Abort[ContainerException]) =
        prune(filters = Map.empty)

    /** Remove unused images matching the given filters. */
    def prune(
        filters: Map[String, Seq[String]]
    )(using Frame): Container.PruneResult < (Async & Abort[ContainerException]) =
        currentBackend.map(_.imagePrune(filters))

    /** Create a new image from a container's current state. Returns the new image ID. */
    def commit(
        container: Container.Id,
        repo: String = "",
        tag: String = "",
        comment: String = "",
        author: String = "",
        pause: Boolean = true
    )(using Frame): String < (Async & Abort[ContainerException]) =
        currentBackend.map(_.imageCommit(container, repo, tag, comment, author, pause))

    /** Pull an image with a stream of progress events (layer downloads, extraction, etc.). */
    def pullWithProgress(
        image: ContainerImage,
        platform: Maybe[Container.Platform] = Absent,
        auth: Maybe[RegistryAuth] = Absent
    )(using Frame): Stream[PullProgress, Async & Abort[ContainerException]] =
        Stream {
            currentBackend.map(_.imagePullWithProgress(image, platform, auth).emit)
        }

    // --- Factory methods ---

    def apply(name: String): ContainerImage              = new ContainerImage(name, tag = Present("latest"))
    def apply(name: String, tag: String): ContainerImage = new ContainerImage(name, tag = Present(tag))

    /** Parse a Docker image reference string into a ContainerImage.
      *
      * Supports formats like:
      *   - "alpine" -> name=alpine, tag=latest
      *   - "alpine:3.19" -> name=alpine, tag=3.19
      *   - "myregistry.com:5000/myapp:v1" -> registry=myregistry.com:5000, name=myapp, tag=v1
      *   - "ghcr.io/owner/repo:v1.2.3" -> registry=ghcr.io, namespace=owner, name=repo, tag=v1.2.3
      *   - "myapp@sha256:abc123" -> name=myapp, digest=sha256:abc123
      */
    def parse(ref: String): Result[String, ContainerImage] =
        if ref.isEmpty then Result.fail("Empty image reference")
        else
            // Split off digest if present
            val (beforeDigest, digestPart) = ref.indexOf('@') match
                case -1  => (ref, Absent: Maybe[String])
                case idx => (ref.substring(0, idx), Present(ref.substring(idx + 1)))

            // Split off tag if present and no digest
            val (beforeTag, tagPart) =
                if digestPart.nonEmpty then (beforeDigest, Absent: Maybe[String])
                else
                    // Find last colon that's not part of a registry port
                    // Registry ports look like "host:port/" - colon followed by digits then /
                    val lastColon = beforeDigest.lastIndexOf(':')
                    if lastColon == -1 then (beforeDigest, Absent: Maybe[String])
                    else
                        val afterColon = beforeDigest.substring(lastColon + 1)
                        // If there's a / after the colon, it's a registry:port, not a tag
                        if afterColon.contains('/') then (beforeDigest, Absent: Maybe[String])
                        else (beforeDigest.substring(0, lastColon), Present(afterColon))
                    end if

            // Parse the path components: registry/namespace/name or namespace/name or name
            val parts = beforeTag.split("/").toList
            val (registryVal, namespaceVal, nameVal) = parts match
                case single :: Nil                                               => (Absent: Maybe[Registry], Absent: Maybe[String], single)
                case first :: rest if first.contains('.') || first.contains(':') =>
                    // First segment looks like a registry (has dots or port)
                    rest match
                        case name :: Nil       => (Present(Registry(first)), Absent: Maybe[String], name)
                        case ns :: name :: Nil => (Present(Registry(first)), Present(ns), name)
                        case ns :: more        => (Present(Registry(first)), Present(ns), more.mkString("/"))
                        case Nil               => (Absent: Maybe[Registry], Absent: Maybe[String], first)
                case first :: name :: Nil        => (Absent: Maybe[Registry], Present(first), name)
                case first :: ns :: name :: rest =>
                    // If first segment looks like a registry
                    if first.contains('.') || first.contains(':') then
                        (Present(Registry(first)), Present(ns), (name :: rest).mkString("/"))
                    else
                        (Absent: Maybe[Registry], Present(first), (ns :: name :: rest).mkString("/"))
                case _ => (Absent: Maybe[Registry], Absent: Maybe[String], beforeTag)

            val finalTag = (tagPart, digestPart) match
                case (Absent, Absent) => Present("latest")
                case _                => tagPart

            val finalDigest = digestPart.map(d => Digest(d))

            Result.succeed(new ContainerImage(nameVal, namespaceVal, registryVal, finalTag, finalDigest))
        end if
    end parse

    // --- Predefined images ---

    val Alpine   = new ContainerImage("alpine", Present("library"), Present(Registry.DockerHub), Present("latest"))
    val Ubuntu   = new ContainerImage("ubuntu", Present("library"), Present(Registry.DockerHub), Present("latest"))
    val BusyBox  = new ContainerImage("busybox", Present("library"), Present(Registry.DockerHub), Present("latest"))
    val Nginx    = new ContainerImage("nginx", Present("library"), Present(Registry.DockerHub), Present("latest"))
    val Postgres = new ContainerImage("postgres", Present("library"), Present(Registry.DockerHub), Present("latest"))
    val Redis    = new ContainerImage("redis", Present("library"), Present(Registry.DockerHub), Present("latest"))

    // --- Nested types ---

    opaque type Registry = String
    object Registry:
        given CanEqual[Registry, Registry]           = CanEqual.derived
        given Render[Registry]                       = Render.from(_.value)
        val DockerHub: Registry                      = "docker.io"
        val GitHub: Registry                         = "ghcr.io"
        val Quay: Registry                           = "quay.io"
        def apply(host: String): Registry            = host
        def apply(host: String, port: Int): Registry = s"$host:$port"
        extension (self: Registry) def value: String = self
    end Registry

    opaque type Digest = String
    object Digest:
        given CanEqual[Digest, Digest] = CanEqual.derived
        given Render[Digest]           = Render.from(_.value)
        def apply(sha256: String): Digest =
            if sha256.startsWith("sha256:") then sha256 else s"sha256:$sha256"
        extension (self: Digest) def value: String = self
    end Digest

    opaque type Id = String
    object Id:
        given CanEqual[Id, Id]                 = CanEqual.derived
        given Render[Id]                       = Render.from(_.value)
        def apply(hash: String): Id            = hash
        extension (self: Id) def value: String = self
    end Id

    given Render[ContainerImage] = Render.from(_.reference)

    // --- Data Types ---

    case class Info(
        id: ContainerImage.Id,
        repoTags: Chunk[ContainerImage],
        repoDigests: Chunk[ContainerImage],
        createdAt: Instant,
        size: Long,
        labels: Map[String, String],
        architecture: String,
        os: String
    ) derives CanEqual

    case class Summary(
        id: ContainerImage.Id,
        repoTags: Chunk[ContainerImage],
        repoDigests: Chunk[ContainerImage],
        createdAt: Instant,
        size: Long,
        labels: Map[String, String]
    ) derives CanEqual

    case class PullProgress(
        id: Maybe[String],
        status: String,
        progress: Maybe[String],
        error: Maybe[String]
    ) derives CanEqual

    case class DeleteResponse(untagged: Maybe[String], deleted: Maybe[String]) derives CanEqual

    case class HistoryEntry(
        id: String,
        createdAt: Instant,
        createdBy: String,
        size: Long,
        tags: Chunk[String],
        comment: String
    ) derives CanEqual

    case class SearchResult(
        name: String,
        description: String,
        stars: Int,
        isOfficial: Boolean,
        isAutomated: Boolean
    ) derives CanEqual

    case class BuildProgress(
        stream: Maybe[String],
        status: Maybe[String],
        progress: Maybe[String],
        error: Maybe[String],
        aux: Maybe[String]
    ) derives CanEqual

    case class RegistryAuth(auths: Map[ContainerImage.Registry, String]) derives CanEqual

    object RegistryAuth:
        /** Load registry credentials from the Docker config file (~/.docker/config.json). */
        def fromConfig(using Frame): RegistryAuth < (Async & Abort[ContainerException]) =
            currentBackend.map(_.registryAuthFromConfig)

        def apply(
            username: String,
            password: String,
            server: String = "https://index.docker.io/v1/"
        ): RegistryAuth =
            RegistryAuth(Map(Registry(server) -> java.util.Base64.getEncoder.encodeToString(s"$username:$password".getBytes)))
    end RegistryAuth

    // --- Private ---

    private def currentBackend(using Frame): kyo.internal.ContainerBackend < (Async & Abort[ContainerException]) =
        Container.currentBackendForImage

end ContainerImage
