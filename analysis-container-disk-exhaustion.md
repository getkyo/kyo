# Analysis: ContainerPredefItTest failures on x64 JS job (run 29646051890, Jul 18)

## Symptom

Main run `29646051890` (commit `27a0c19d2`), job `build (linux-x64, ubuntu-latest) / build (JS)`:
ContainerPredefItTest finished 5 passed, 4 failed after 66 minutes of otherwise green build.

- MySQL `create + insert + select round-trip`: exec returned 500
  `can only create exec sessions on running containers: container state improper` (failed in 968ms).
- All three MongoDB tests: `Pull failed for mongo:7 ... write /var/tmp/container_images_storage*/N:
  no space left on device`.
- Additionally, the two passing MySQL tests logged stop/remove 500s:
  `given PID did not die within timeout`, leaking both containers.

The other three x64 jobs (JVM, Native, Wasm) and all four arm64 jobs of the same run passed,
including the same kyo-pod suites.

## Evidence: the runner started with a defective disk

ci-mon samples `df -Pm .` (the workspace filesystem) every 20s. First sample per job, all
started within seconds of each other, all on runner image `ubuntu-24.04` version
`20260714.240.1`, provisioner `20260707.563`:

| Job | First ci-mon diskFreeMB |
|---|---|
| x64 JVM (passed) | 78467 |
| x64 Native (passed) | 78452 |
| x64 Wasm (passed) | 78451 |
| arm64 JS (passed) | 101711 |
| **x64 JS (failed)** | **4053** |

The failing job's runner was ~74.4GB short at 13:24:32, before sbt ran a single task. The gap
matches the workspace data volume being absent entirely, with `/home/runner/work` falling back
to the small OS partition. Identical image and provisioner versions rule out an image rollout;
this was one bad VM.

Disk trajectory on the failing job:

```
13:24:32  4053   job start
14:26:19  1864   after the full JS build/test run (~2.2GB of build outputs over 62 min)
14:27:19  1222   ContainerItTest finishing, ContainerOrchestrationItTest running
14:28:19   798   MySQL "SELECT 1" pulling mysql:8.0 (test took 43.3s, cold pull)
14:28:39   205   pull extraction + MySQL datadir init
14:29:19     3   second/third MySQL containers + leaked datadirs
14:29:39     3   mongo:7 pull fails with ENOSPC
```

Everything the job writes shares that one filesystem: sbt targets, coursier cache, the rootless
podman graphroot (`~/.local/share/containers/storage`), and the pull temp dir
(`/var/tmp/container_images_storage*`).

## Root cause chain

1. GitHub provisioned one runner VM with ~4GB free on the workspace filesystem instead of ~78GB
   (infra fault; siblings on the identical image had the full volume).
2. The JS build consumed ~2.2GB over the run, leaving ~1.9GB when the kyo-pod IT suites started.
3. The DB predef suites need roughly 2-3GB cold: postgres:16 (cached earlier by ContainerItTest),
   mysql:8.0 (~600MB extracted) and mongo:7 (~700MB) pulls, plus a fresh MySQL datadir per
   container. Disk hit 0 mid-suite.
4. Cascade, all downstream of ENOSPC:
   - mysqld stalls in IO on the full disk (compounded by memory pressure: java 10-11.5GB RSS,
     chrome-headless 1GB, node 1.2GB on a 16GB box, ~1.5GB into swap), so it does not die within
     podman's kill window: stop and remove both return 500 `given PID did not die within
     timeout`, and the containers leak, pinning their datadir space.
   - The third MySQL container starts but mysqld exits immediately on the full disk; the exec
     that follows gets `can only create exec sessions on running containers`.
   - The mongo:7 pulls fail writing blobs to `/var/tmp`: the three MongoDB failures.

The earlier ContainerItTest warnings in the same log (14:16-14:23) are a different, benign
shape: stop on an already-exited container (`container state improper ... is stopped`), the
known cleanup race noise, unrelated to disk.

## Assessment

Infra flake on a single defective GitHub-hosted runner; no kyo-pod bug and nothing introduced
by `27a0c19d2` (the other three runs of the same commit, and all 8 sibling jobs, are green;
the 11 preceding main runs are green). A retry of the job is the remedy.

## Optional hardening (proposal, not applied)

1. **Preflight disk guard in `scripts/ci-test.sh`**: before launching sbt (next to the
   ci-monitor startup at `scripts/ci-test.sh:444`), fail fast when the workspace filesystem has
   less free space than a floor (e.g. 20GB). This run spent 66 minutes to surface a misleading
   container-test failure; a preflight turns the same fault into a seconds-long failure that
   names the real cause.
2. **One mount line in `scripts/ci-monitor.sh` startup**: log the workspace filesystem device
   and total size once, so a short-disk runner is a one-glance diagnosis in future logs.
