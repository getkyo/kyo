package kyo.internal

import kyo.Schema

/** Shared DTOs for container inspect JSON — shapes that are byte-for-byte identical across both the HTTP (Docker/Podman compat API) and
  * Shell (docker/podman CLI JSON output) backends.
  */

/** Single mount entry in a container inspect response.
  *
  * Fields are identical between Docker and Podman (both daemon variants) for the 5-field projection we consume. Additional fields (`Mode`,
  * `Propagation`) present on the wire are silently discarded by zio-schema.
  */
final private[internal] case class InspectMountDto(
    Type: String = "",
    Name: String = "",
    Source: String = "",
    Destination: String = "",
    RW: Boolean = true
) derives Schema

/** Single-field health status DTO from a container inspect response.
  *
  * Both Docker and Podman return `Log` and `FailingStreak` in addition to `Status`, but neither backend needs those fields — they are
  * silently discarded by zio-schema.
  */
final private[internal] case class InspectHealthDto(
    Status: String = ""
) derives Schema
