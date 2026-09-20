# kyo-natives-plugin

The sbt plugin an application enables to get the shared libraries kyo's published artifacts carry.

On the JVM nothing is needed: the library rides inside a jar and kyo's loader extracts it on first use. Scala Native
has no runtime loader, and on Node koffi opens a file by path and never sees a classpath, so on those two platforms a
library has to be put somewhere before anything asks for it. That is this plugin's whole job.

It is not `kyo-ffi-plugin`. That one is for a module *building* a binding, and brings a C toolchain, codegen and
packaging with it. Enabling this one requires none of that.

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

## What it does per platform

**Scala Native.** Links the binary against each library and stages the library beside the linked binary. A deployment
carries the two files together, the way a JVM application carries its jars. The directory `nativeLink` writes into
holds build output besides them, so copy the binary and its `lib<id>.<ext>` rather than the directory. A binary
deployed without its libraries fails in the dynamic loader naming the file it wanted.

**Node.** Writes each library under `target/node_modules/@kyo/ffi-native/native/<os>-<arch>/`, which is where the
loader asks `require.resolve` for it, and installs koffi beside it. Both live under `target/`, where `sbt run` and
`sbt test` resolve them. A deployed application resolves them the way Node resolves any package, by walking up from
the linked output, so `node_modules/@kyo/ffi-native` and `node_modules/koffi` travel with `main.js`.

**JVM.** Adds the per-os-arch classifier jars to the classpath, so an application gets them without naming a
`classifier` dependency per library. The classpath is host-shaped by default: an image built on one OS or
architecture and run on another has to name its target.

## koffi, the one thing from npm

Opening a library on Node needs koffi, a native Node addon rather than a Scala.js dependency, so it cannot travel in
a jar. The plugin writes `target/package.json` pinning `koffi` to a supported range and runs `npm install` into
`target/node_modules`, once per clean and again whenever that range changes. It is the only thing in this path your
build fetches from npm rather than from Maven, and the thing to point your own registry, lockfile or audit at.
`kyoNativesKoffi := false` turns the install off for a build that provides koffi itself.

## Settings

| Key | Meaning |
|-----|---------|
| `kyoNativesTargets` | The `<os>-<arch>` targets to deliver for. Empty, the default, means the one this build is for. A JVM classpath and a Node bundle resolve their library at runtime, so naming more than one there is how an artifact built on one machine runs on another. A linked binary is built for exactly one target, so more than one is an error there. |
| `kyoNativesSource` | `Auto` (default) delivers what the artifacts carry and leaves the build alone for anything they do not; `Jar` fails the build when a library is missing for the target; `Disabled` contributes nothing. |
| `kyoNativesKoffi` | Whether to install koffi into `target/node_modules`. On by default. |
| `kyoNativesNodeEnv` | The environment a Node process needs to resolve the delivered package. A project that sets its own `jsEnv` keeps it and folds this in. |
| `kyoNativesDirectory` | Where the unpacked libraries are written, one subdirectory per target. |
| `kyoNativesReport` | Prints each library, the artifact it came from, the directory it was staged in, and what it is wired into. |

## When something is missing

Under `Auto` a library the release does not carry for your target is not a build failure: the application still
builds, and the capability reports itself unavailable at run time. That is the right default for a machine kyo
publishes no artifact for, and the wrong one if you expected the capability, which is what `kyoNativesSource := Jar`
is for.

A Native build whose target triple and `kyoNativesTargets` disagree fails at the link rather than producing a binary
linked against another pole's libraries. Those fail in the linker with a file-format error at best, and load and
crash at worst where the poles share an architecture, as glibc and musl do.

`sbt kyoNativesReport` is the first thing to run when a library is not where you expected.
