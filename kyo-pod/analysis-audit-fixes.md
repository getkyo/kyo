# Audit Fixes Analysis (CONTRIBUTING.md violations)

## Fix 1: Seq -> Chunk in public API signatures (CRITICAL)

Propagation order: ContainerBackend (abstract) -> Container.scala + ContainerImage.scala (public API) -> HttpContainerBackend + ShellBackend (implementations).

At JSON encoding boundaries in HttpContainerBackend, convert Chunk back to Seq using `.toSeq` since the Json codec works with `Seq`.

## Fix 2: Missing scaladocs (HIGH)

6 no-arg overloads in Container.scala need brief scaladocs.

## Fix 3: Option -> Maybe in non-DTO code (HIGH)

- HttpContainerBackend `exposedPorts: Option[...]` -- feeds DTO, keep as Option.
- ShellBackend `parseDockerPortEntry` returns `Option` -- change to `Maybe`.

## Fix 4: demuxStream var/while -> tail recursion (HIGH)

Replace mutable loop with recursive `parse` function.
