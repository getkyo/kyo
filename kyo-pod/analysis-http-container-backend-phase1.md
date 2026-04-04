# Phase 1: HttpContainerBackend — Analysis

## Goal
Create `HttpContainerBackend` class that implements `ContainerBackend` via Docker/Podman HTTP API over Unix domain sockets, replacing `ShellBackend` CLI-based approach.

## Changes

### 1. New file: `kyo-pod/shared/src/main/scala/kyo/internal/HttpContainerBackend.scala`
- `HttpContainerBackend(socketPath: String, apiVersion: String = "v1.43")` extending `ContainerBackend`
- `url(path, params*)` helper to build `http+unix://` URLs
- All trait methods stubbed with `???`
- Companion object with `detect()` method that:
  1. Checks `DOCKER_HOST` env var (parse `unix://` prefix)
  2. Checks `$XDG_RUNTIME_DIR/podman/podman.sock`
  3. Checks `/var/run/docker.sock`
  4. Checks `$HOME/.docker/run/docker.sock`
  5. Pings `_ping` on each, returns first that responds

### 2. Update `Container.scala` — `resolveBackend`
- Wire `BackendConfig.UnixSocket(path)` to create `HttpContainerBackend` instead of failing
- Update `ContainerBackend.detect()` to try HTTP detection first, fall back to Shell

## Key design decisions
- Use `HttpClient.getText(url)` for `_ping` — returns `String < (Async & Abort[HttpException])`
- Use `kyo.System.env[String]("DOCKER_HOST")` for env var access (no AllowUnsafe)
- Use `Path.exists` to check socket paths before trying ping
- URL encoding: `socketPath.replace("/", "%2F")` for the authority portion
- `HttpUrl` already handles `http+unix://` scheme natively
