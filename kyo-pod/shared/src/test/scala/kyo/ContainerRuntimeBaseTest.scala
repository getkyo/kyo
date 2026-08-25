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

    "available" - {

        "never reports a runtime whose installed CLI cannot reach its daemon" in {
            // Host-independent form of the rule: whatever this host has, a runtime that is enumerated must
            // either have a healthy CLI or no CLI at all.
            ContainerRuntime.available.foreach { rt =>
                assert(
                    !ContainerRuntime.cliPresent(rt) || ContainerRuntime.cliExists(rt),
                    s"$rt is enumerated as available while its own CLI reports it is not"
                )
            }
        }
    }

end ContainerRuntimeBaseTest
