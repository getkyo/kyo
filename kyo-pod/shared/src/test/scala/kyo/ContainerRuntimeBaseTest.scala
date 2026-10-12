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

    "assignment" - {

        def reasonFor(assigned: Seq[(String, Maybe[String])], rt: String): Maybe[String] =
            Maybe.fromOption(assigned.collectFirst { case (`rt`, reason) => reason }).flatten

        // The build forks these suites once per runtime. A fork that registers nothing for its runtime runs 0 leaves under a filter that
        // matches only container leaves, and the runner fails the whole selection for it.
        "a fork pinned to a runtime that is not reachable answers for it with the reason" in {
            val assigned = ContainerRuntime.assignment(
                windows = false,
                reachable = Seq("podman" -> true, "docker" -> false),
                distinct = Seq("podman"),
                pin = Present("docker")
            )
            assert(assigned.map(_._1) == Seq("docker"))
            assert(reasonFor(assigned, "docker") == Present("docker is not reachable on this host"))
        }

        "unpinned, every runtime is answered for, runnable or with its reason" in {
            val assigned = ContainerRuntime.assignment(
                windows = false,
                reachable = Seq("podman" -> true, "docker" -> false),
                distinct = Seq("podman"),
                pin = Absent
            )
            assert(assigned == Seq("podman" -> Absent, "docker" -> Present("docker is not reachable on this host")))
        }

        "a runtime that reaches the daemon of one kept before it is answered for with that reason" in {
            val assigned = ContainerRuntime.assignment(
                windows = false,
                reachable = Seq("podman" -> true, "docker" -> true),
                distinct = Seq("podman"),
                pin = Present("docker")
            )
            assert(reasonFor(assigned, "docker") ==
                Present("docker reaches the same daemon as another runtime here, which runs these leaves"))
        }

        "on Windows every runtime is answered for with the reason" in {
            val assigned = ContainerRuntime.assignment(
                windows = true,
                reachable = Seq("podman" -> true, "docker" -> true),
                distinct = Seq("podman", "docker"),
                pin = Absent
            )
            assert(assigned.map(_._1) == Seq("podman", "docker"))
            assert(assigned.forall(_._2 == Present("the host is Windows and these are Linux-container tests")))
        }

        "a runnable pinned runtime is answered for alone" in {
            val assigned = ContainerRuntime.assignment(
                windows = false,
                reachable = Seq("podman" -> true, "docker" -> true),
                distinct = Seq("podman", "docker"),
                pin = Present("podman")
            )
            assert(assigned == Seq("podman" -> Absent))
        }
    }

    "singleLegOwner" - {

        val both = Seq("podman" -> Absent, "docker" -> Absent)
        def owner(fork: Maybe[String], unpinned: Seq[(String, Maybe[String])] = both, sockets: Set[String] = Set("podman", "docker")) =
            ContainerRuntime.singleLegOwner(unpinned, fork.fold(unpinned)(p => unpinned.filter(_._1 == p)), fork, sockets.contains)

        "with both runtimes runnable, exactly one of the two per-runtime forks runs the leaf" in {
            // The build puts every daemon-touching suite in a podman fork and a docker fork, so a leaf each fork
            // ran against its own runtime would run twice per host.
            val runs = Seq("podman", "docker").filter(rt => owner(Present(rt)).isRight)
            assert(runs == Seq("podman"), s"expected only the podman fork to run it, got $runs")
            assert(owner(Present("docker")) == Left("a single-leg leaf runs once per host, in the podman fork"))
        }

        "the leaf follows the runtime that can run here" in {
            val dockerOnly = Seq("podman" -> Present("podman is not reachable on this host"), "docker" -> Absent)
            assert(owner(Present("docker"), dockerOnly) == Right("docker"))
            assert(owner(Present("podman"), dockerOnly) == Left("a single-leg leaf runs once per host, in the docker fork"))
        }

        "a runtime with no socket does not own the leaf" in {
            assert(owner(Present("docker"), sockets = Set("docker")) == Right("docker"))
            assert(owner(Absent, sockets = Set.empty) == Left("no runtime that can run here exposes a socket for the http backend"))
        }

        "unpinned, the process runs the leaf on the owner" in {
            assert(owner(Absent) == Right("podman"))
        }

        "outside the per-runtime forks, a process pinned to a runtime runs the leaf on that runtime" in {
            // KYO_POD_RUNTIME is also set by hand and by CI for the whole job, so a pin alone does not mean the build split the suite.
            assert(ContainerRuntime.singleLegOwner(both, Seq("docker" -> Absent), Absent, Set("podman", "docker").contains) ==
                Right("docker"))
        }

        "outside the per-runtime forks, a process pinned to a runtime that cannot run here carries the pin's reason" in {
            val pinnedToNone = Seq("none" -> Present("none is not reachable on this host"))
            assert(ContainerRuntime.singleLegOwner(both, pinnedToNone, Absent, Set("podman", "docker").contains) ==
                Left("none is not reachable on this host"))
        }

        "with no runtime runnable the leaf carries this process's reasons" in {
            val none = Seq(
                "podman" -> Present("podman is not reachable on this host"),
                "docker" -> Present("docker is not reachable on this host")
            )
            assert(owner(Absent, none) == Left("podman is not reachable on this host; docker is not reachable on this host"))
        }
    }

    "hostLeavesHere" - {

        "with both runtimes runnable, exactly one of the two per-runtime forks runs the host's leaves" in {
            val runs = Seq("podman", "docker").filter(rt => ContainerRuntime.hostLeavesHere(Present("podman"), Present(rt)))
            assert(runs == Seq("podman"), s"expected only the podman fork, got $runs")
        }

        "they follow the owner when only docker can run" in {
            assert(ContainerRuntime.hostLeavesHere(Present("docker"), Present("docker")))
            assert(!ContainerRuntime.hostLeavesHere(Present("docker"), Present("podman")))
        }

        "with no runtime runnable, the podman fork runs them, so they still run once" in {
            val runs = Seq("podman", "docker").filter(rt => ContainerRuntime.hostLeavesHere(Absent, Present(rt)))
            assert(runs == Seq("podman"), s"expected only the podman fork, got $runs")
        }

        "a process outside the per-runtime forks runs every leaf" in {
            assert(ContainerRuntime.hostLeavesHere(Present("docker"), Absent))
            assert(ContainerRuntime.hostLeavesHere(Absent, Absent))
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
