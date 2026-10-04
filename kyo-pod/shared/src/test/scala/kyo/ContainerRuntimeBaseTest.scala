package kyo

/** Tests for the runtime-availability decision the suites register their leaves from.
  *
  * A socket FILE is not a daemon, and a stale one outlives the daemon that made it. On a Mac where Docker
  * Desktop is not running, `~/.docker/run/docker.sock` still exists and answers `_ping` with a 500, so
  * treating its presence as availability registered every `[docker]` leaf in the module and failed all 425 of
  * them on a host condition that says nothing about the code.
  */
class ContainerRuntimeBaseTest extends BasePodTest:

    "runtimeAvailable" - {

        "an installed CLI is the authority, whatever the socket file says" in {
            // The reported host: the docker binary is installed, `docker version` exits 1 because the daemon
            // is unreachable, and both socket files are still on disk.
            assert(!ContainerRuntime.runtimeAvailable(cliInstalled = true, cliHealthy = false, socketPresent = true))
            assert(ContainerRuntime.runtimeAvailable(cliInstalled = true, cliHealthy = true, socketPresent = false))
            assert(ContainerRuntime.runtimeAvailable(cliInstalled = true, cliHealthy = true, socketPresent = true))
        }

        "with no CLI installed the socket decides, so a mounted-socket container keeps its runtime" in {
            assert(ContainerRuntime.runtimeAvailable(cliInstalled = false, cliHealthy = false, socketPresent = true))
            assert(!ContainerRuntime.runtimeAvailable(cliInstalled = false, cliHealthy = false, socketPresent = false))
        }
    }

    "cliPresent" - {

        "answers for a binary that exists but says nothing about its exit code" in {
            // `ls` is on PATH on every host these suites run on, and `ls version` fails (exit 1, no such
            // file), so this pins the distinction the decision rests on: present, not healthy.
            //
            // Not `sh`: `sh version` asks the shell to RUN a script called `version`, which exits 127, the
            // same code a shell uses for a binary it cannot find. A real caller never hits that, because
            // `docker version` and `podman version` are valid invocations, but it makes `sh` a probe subject
            // that cannot distinguish the two cases and so cannot test them.
            assert(ContainerRuntime.cliPresent("ls"))
            assert(!ContainerRuntime.cliExists("ls"))
        }

        "is false for a binary that is not installed, on every platform's way of saying so" in {
            // The platforms disagree on the mechanism and this leaf is why the implementation does not pick
            // one: the JVM raises from the spawn, Scala Native runs through /bin/sh so the spawn succeeds and
            // the shell exits 127, and JS asks the shell directly. All three have to answer absent.
            assert(!ContainerRuntime.cliPresent("kyo-pod-no-such-binary-9d4f1a"))
            assert(!ContainerRuntime.cliExists("kyo-pod-no-such-binary-9d4f1a"))
        }
    }

    "envSocketFrom" - {

        "podman takes CONTAINER_HOST as given, whatever its path looks like" in {
            // A forwarded socket under a name that says nothing about its daemon: classifying by path sent the podman leaves to the
            // default rootless socket while the docker leaves took this one, with no error anywhere.
            assert(ContainerRuntime.envSocketFrom("podman", Present("unix:///tmp/kyo-pod-root.sock"), Absent) ==
                Present("/tmp/kyo-pod-root.sock"))
            assert(ContainerRuntime.envSocketFrom("docker", Present("unix:///tmp/kyo-pod-root.sock"), Absent) == Absent)
        }

        "docker takes DOCKER_HOST, the variable its CLI reads" in {
            assert(ContainerRuntime.envSocketFrom("docker", Absent, Present("unix:///var/run/docker.sock")) ==
                Present("/var/run/docker.sock"))
            assert(ContainerRuntime.envSocketFrom("podman", Absent, Present("unix:///var/run/docker.sock")) == Absent)
        }

        "an empty socket path names nothing" in {
            assert(ContainerRuntime.envSocketFrom("podman", Present("unix://"), Absent) == Absent)
        }
    }

    "available" - {

        "never reports a runtime whose installed CLI cannot reach its daemon" in {
            // Host-independent form of the rule: whatever this host has, a runtime that is enumerated must
            // either have a healthy CLI or no CLI at all. Asserted over the whole list rather than from inside
            // a loop over it, because a host with no runtime at all reaches no assertion that way, and the run
            // then reports the leaf as having checked nothing. Every Windows runner is such a host: `available`
            // is empty there by construction, since its Docker daemon runs Windows containers.
            val enumerated = ContainerRuntime.available
            assert(
                enumerated.forall(rt => !ContainerRuntime.cliPresent(rt) || ContainerRuntime.cliExists(rt)),
                s"enumerated $enumerated while a runtime's own CLI reports it is not available"
            )
        }
    }

end ContainerRuntimeBaseTest
