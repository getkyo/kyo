package kyo.ffi.internal

import kyo.Chunk
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** Runtime reader for the per-module native manifests the build plugin emits under
  * `META-INF/kyo-ffi/native-manifest/<module>.manifest`.
  *
  * The manifest is DATA, read here before any generated `<T>Impl` class initializer runs, so a missing bundled
  * native fails a `Ffi.load[T]` with a precise, catchable [[kyo.ffi.FfiLoadError.LibraryNotFound]] instead of
  * poisoning the impl companion. The reflection-free trait->id index is what makes that possible: an earlier
  * attempt keyed the check on a field reflected off the generated companion, but the Scala 3 backend elides that
  * field, so the manifest carries the id explicitly.
  *
  * Line format, one block per bundled library id plus a trait index:
  * {{{
  * <id>.platforms=darwin-x86_64,darwin-aarch64,linux-x86_64,...
  * <id>.version=<semver>
  * <id>.minRuntime=<semver>
  * trait.<binding-trait-FQN>=<id>
  * }}}
  *
  * `platforms` is the `<os>-<arch>` set the native is bundled for on this build; `version` / `minRuntime` are the
  * kyo release the native ships with (single-source, lockstep). A library id present in the trait index but with
  * no block (an OS system library such as `c`) has no [[Entry]]: its absence from the native layout is expected.
  *
  * The raw manifest texts come from [[NativeManifestPlatform.manifestTexts]]: the JVM enumerates the classpath, JS
  * and Native supply none (JS resolves presence through its loader at load time; Native links at build time).
  */
object NativeManifest:

    /** One bundled library id's manifest block. `platforms` is the `<os>-<arch>` set this build bundles the native
      * for; `version` is the native's own release version; `minRuntime` is the minimum kyo-ffi runtime it requires.
      */
    final case class Entry(id: String, platforms: Set[String], version: String, minRuntime: String)

    private val TraitPrefix      = "trait."
    private val PlatformsSuffix  = ".platforms"
    private val VersionSuffix    = ".version"
    private val MinRuntimeSuffix = ".minRuntime"

    /** Parsed shape of the merged manifests: `traitToId` maps a binding-trait FQN to its library id;
      * `idToEntry` maps a bundled library id to its block.
      */
    final private[internal] case class Index(traitToId: Map[String, String], idToEntry: Map[String, Entry])

    /** Parse and merge `texts` into an [[Index]]. Pure: every input is manifest text, no resource access here.
      *
      * Blank lines and lines with no `=` are skipped. A `trait.<FQN>=<id>` line contributes the trait index; an
      * `<id>.platforms` / `.version` / `.minRuntime` line contributes that id's block. When several modules
      * declare the same id (uncommon; ids are module-specific), platforms are unioned and the first non-empty
      * version / minRuntime wins.
      */
    private[internal] def parse(texts: Chunk[String]): Index =
        val traitToId  = scala.collection.mutable.Map.empty[String, String]
        val platforms  = scala.collection.mutable.Map.empty[String, Set[String]]
        val version    = scala.collection.mutable.Map.empty[String, String]
        val minRuntime = scala.collection.mutable.Map.empty[String, String]
        val ids        = scala.collection.mutable.LinkedHashSet.empty[String]

        texts.foreach { text =>
            text.linesIterator.foreach { raw =>
                val line = raw.trim
                val eq   = line.indexOf('=')
                if line.nonEmpty && eq > 0 then
                    val key   = line.substring(0, eq).trim
                    val value = line.substring(eq + 1).trim
                    if key.startsWith(TraitPrefix) then
                        val fqn = key.substring(TraitPrefix.length)
                        if fqn.nonEmpty then traitToId.update(fqn, value)
                    else if key.endsWith(PlatformsSuffix) then
                        val id = key.substring(0, key.length - PlatformsSuffix.length)
                        ids += id
                        val parsed = value.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet
                        platforms.update(id, platforms.getOrElse(id, Set.empty) ++ parsed)
                    else if key.endsWith(VersionSuffix) then
                        val id = key.substring(0, key.length - VersionSuffix.length)
                        ids += id
                        if value.nonEmpty && !version.contains(id) then version.update(id, value)
                    else if key.endsWith(MinRuntimeSuffix) then
                        val id = key.substring(0, key.length - MinRuntimeSuffix.length)
                        ids += id
                        if value.nonEmpty && !minRuntime.contains(id) then minRuntime.update(id, value)
                    end if
                end if
            }
        }

        val entries = ids.iterator.map { id =>
            id -> Entry(id, platforms.getOrElse(id, Set.empty), version.getOrElse(id, ""), minRuntime.getOrElse(id, ""))
        }.toMap
        Index(traitToId.toMap, entries)
    end parse

    // Built once from the platform-provided manifest texts. `lazy` so a runtime with no manifests pays nothing.
    private lazy val index: Index = parse(NativeManifestPlatform.manifestTexts)

    /** The library id a binding trait declares, from the manifest trait index, or [[Absent]] when no manifest on
      * the classpath names this trait (an unmigrated module, a test binding, or a runtime with no manifests).
      */
    def libraryIdFor(traitFqn: String): Maybe[String] =
        index.traitToId.get(traitFqn) match
            case Some(id) => Present(id)
            case None     => Absent

    /** The manifest block for a bundled library id, or [[Absent]] when the id has no block (a system library, or an
      * id no manifest declares).
      */
    def entryFor(id: String): Maybe[Entry] =
        index.idToEntry.get(id) match
            case Some(e) => Present(e)
            case None    => Absent
end NativeManifest
