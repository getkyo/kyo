# kyo-natives-plugin

The sbt plugin an application enables to get everything kyo's published artifacts need from the build that consumes
them.

On the JVM that is almost nothing: a library rides inside a jar, kyo's loader extracts it on first use, and
`ServiceLoader` finds a provider at run time. The other two platforms resolve at build time what the JVM resolves at
run time, so the build has to answer for them.

Three answers, and a module can need any combination:

- **A delivered library.** Scala Native has no runtime loader, and on Node koffi opens a file by path and never sees
  a classpath, so a library has to be put somewhere before anything asks for it.
- **A system library.** A shim over OpenSSL or liburing needs the library on the machine doing the linking, and only
  that machine can answer. The artifact carries what to look for.
- **A service provider.** Scala Native resolves `ServiceLoader` when it links and drops any class nothing references,
  so a provider also has to be named in the link. This is the one with no symptom: an un-enlisted provider links
  clean and never registers.

It is not `kyo-ffi-plugin`. That one is for a module *building* a binding, and brings codegen and packaging with it.
Enabling this one brings neither. The system-library probe does compile and link a small C program, with the clang
Scala Native already requires, and only on the Native leg.

## Setup

```
// project/plugins.sbt
addSbtPlugin("io.getkyo" % "kyo-natives-plugin" % kyoVersion)
```
```
// the application project; on a crossProject, `.enablePlugins` covers every leg and
// `.nativeConfigure(_.enablePlugins(KyoNativesPlugin))` covers only one
.enablePlugins(KyoNativesPlugin)
```

The plugin is per-project, not per-build: a root aggregate has no classpath of its own and delivers nothing. It
brings sbt-scalajs and sbt-scala-native with it, so a build that has neither takes both into its meta-build by adding
it.

It reads the dependencies already on the project's classpath. Each kyo artifact declares which of its libraries a
consumer needs and which artifact carries them, so there is no list of kyo modules to keep in step here.

Production and tests are read separately. A kyo module that is a `% Test` dependency gets its libraries into the
test binary, the test classpath and the Node test run, and nowhere in what the application ships.

## What it does per platform

**Scala Native.** All three answers, folded into one `nativeConfig`.

Links the binary against each delivered library and stages the library beside the linked binary, for `nativeLink`,
`nativeLinkReleaseFast` and `nativeLinkReleaseFull` alike. A deployment carries the two files together, the way a JVM
application carries its jars. The directory a link writes into holds
build output besides them, so copy the binary and its `lib<id>.<ext>` rather than the directory. A binary deployed
without its libraries fails in the dynamic loader naming the file it wanted. On Linux the delivered libraries need the
C++ runtime (`libstdc++`, for BoringSSL) and `libuuid` and `libatomic` (for Aeron) on the machine that runs the binary,
which a minimal image has to install.

Probes this machine for each system library the dependencies declare, and adds the ones that link. A library that
does not link is not an error: its shim compiles stubs and the capability reports itself unavailable at run time,
which is what every platform does for a library that is not there. `sbt show kyoNativesSystemLibraries` lists what
was found, and a `[kyo-natives]` line names each one that was not.

Enlists every service provider the dependency jars declare, read from the `META-INF/services` entries the JVM reads
at run time, so the two platforms discover the same set. `kyoNativesEnlistServices := false` hands that back to the
build.

**Node.** Writes each library under `target/node_modules/@kyo/ffi-native/native/<os>-<arch>/`, which is where the
loader asks `require.resolve` for it, and installs koffi beside it. Both live under `target/`, where `sbt run` and
`sbt test` resolve them. A deployed application resolves them the way Node resolves any package, by walking up from
the linked output, so `node_modules/@kyo/ffi-native` and `node_modules/koffi` travel with `main.js`. That is
`target/node_modules`, which holds only what production code delivers; a bundler or packager reads it. The test run
gets its own copy, with the test-only libraries, under `target/kyo-natives-test-node/node_modules`.

**JVM.** Adds the per-os-arch classifier jars to the classpath, so an application gets them without naming a
`classifier` dependency per library. The classpath is host-shaped by default: an image built on one OS or
architecture and run on another has to name its target.

## koffi, the one thing from npm

Opening a library on Node needs koffi, a native Node addon rather than a Scala.js dependency, so it cannot travel in
a jar. The plugin writes `target/package.json` pinning `koffi` to a supported range and runs `npm install` into
`target/node_modules`, once per clean and again whenever that range changes. It is the only thing in this path your
build fetches from npm rather than from Maven, and the thing to point your own registry, lockfile or audit at.
`kyoNativesKoffi := false` turns the install off for a build that provides koffi itself.

## Settings and tasks

| Key | Meaning |
|-----|---------|
| `kyoNativesTargets` | The `<os>-<arch>` targets to deliver for. Empty, the default, means the one this build is for. A JVM classpath and a Node bundle resolve their library at runtime, so naming more than one there is how an artifact built on one machine runs on another. A linked binary is built for exactly one target, so more than one is an error there. |
| `kyoNativesSource` | `Auto` (default) delivers what the artifacts carry and leaves the build alone for anything they do not; `Jar` fails the build when a library is missing for the target; `Disabled` contributes nothing. |
| `kyoNativesKoffi` | Whether to install koffi into `target/node_modules`. On by default. |
| `kyoNativesNodeEnv` | The environment a Node process needs to resolve the delivered package. A project that sets its own `jsEnv` keeps it and folds this in. |
| `kyoNativesDirectory` | Where the unpacked libraries are written, one subdirectory per target. |
| `kyoNativesResolvedTargets` | The targets in effect, after deriving the ones `kyoNativesTargets` left open. `show` it when a build delivers for a target you did not expect. |
| `kyoNativesMaterialize` | Scala.js only: writes the libraries into `target/node_modules`. Runs as part of linking, so a build rarely calls it. |
| `kyoNativesSystemLibraries` | Scala Native only: the declared system libraries this machine turned out to have, and the flags each one adds. |
| `kyoNativesEnlistServices` | Scala Native only: whether to enlist the dependencies' declared service providers. On by default. |
| `kyoNativesServiceProviders` | Scala Native only: the providers enlisted for the link, as interface to implementations. |
| `kyoNativesReport` | Prints each library, the artifact it came from, the directory it was staged in, and what it is wired into. |

## When something is missing

Under `Auto` a library the release does not carry for your target is not a build failure: the application still
builds, and the capability reports itself unavailable at run time. That is the right default for a machine kyo
publishes no artifact for, and the wrong one if you expected the capability, which is what `kyoNativesSource := Jar`
is for.

A Native build whose target triple and `kyoNativesTargets` disagree fails at the link rather than producing a binary
linked against another pole's libraries. Those fail in the linker with a file-format error at best, and load and
crash at worst where the poles share an architecture, as glibc and musl do.

The system-library probe links against this machine, with the clang on the `PATH` and its default search paths, so it
answers for a binary built here, for here. A link with a target triple for another pole, a `kyoNativesTargets` naming
one, or a `withClang` pointing at another toolchain fails rather than carrying libraries the probe found on the host.
Cross-compiling with system libraries means `kyoNativesSource := NativesSource.Disabled` and their flags written into
`nativeConfig` by hand.

A build that replaces `nativeConfig` outright, rather than building on `nativeConfig.value`, discards this plugin's
contribution along with everything else in it, and the link fails naming what was lost. Build on the existing value
(`nativeConfig ~= (_.withBaseName(...))`), or set `kyoNativesSource := NativesSource.Disabled` to wire the libraries
by hand. The same holds for a `Test / nativeLink / nativeConfig :=`.

A capability that is silently absent on Native, with the build green and no error at run time, is usually an
un-enlisted service provider. `sbt show kyoNativesServiceProviders` names what the link will carry.

`sbt kyoNativesReport` is the first thing to run when a library is not where you expected.
