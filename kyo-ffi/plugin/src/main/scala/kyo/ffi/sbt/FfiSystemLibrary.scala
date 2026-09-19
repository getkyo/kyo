package kyo.ffi.sbt

/** A library the machine that links a Scala Native binary may provide, declared so a consumer's build can use it.
  *
  * On Scala Native a module's C ships as source and compiles in the consumer's build, where the flags this build used
  * mean nothing: its include and library paths name directories the consumer has never seen, and whether liburing or
  * OpenSSL exist is a fact about the consumer's machine. So a published Native artifact carries this declaration instead
  * of flags, and kyo-ffi-plugin in the consumer's build resolves it there: it compiles and links a probe that includes
  * every header in `headers` against the declared libraries, first with the compiler's default paths and then under each
  * prefix in `prefixesByOs`. When a probe links, the library is enabled with the flags that made it link plus the
  * library's `FfiLibrary.linkedDefine`, so the shim compiles its real code exactly when the link can satisfy it. When
  * none links, nothing is emitted and the shim compiles its stubs.
  *
  * Only libraries the consumer's machine may install belong here (liburing, OpenSSL). A library this build vendors
  * (BoringSSL, Aeron) is not on a consumer's machine however it is probed.
  *
  * @param headers
  *   headers the shim's real branch includes, as written in its `#include <...>` (e.g. `openssl/ssl.h`).
  * @param linkLibs
  *   libraries to link on every OS, without the `lib` prefix or an extension.
  * @param linkLibsByOs
  *   libraries to link on one OS, keyed like `FfiLibrary.linkLibsByOs` (`linux` also covers `linux-musl`). An OS with
  *   no libraries at all has nothing to probe, so the library is never enabled there.
  * @param staticLink
  *   link the libraries statically (`-Wl,-Bstatic` on GNU ld), as the JVM shared library does. The probe links the same
  *   way, so a machine with only a shared copy leaves the library disabled rather than linking it another way.
  * @param prefixesByOs
  *   install prefixes to try after the compiler's defaults, each contributing `<prefix>/include` and `<prefix>/lib`,
  *   keyed like `linkLibsByOs`. Homebrew's keg-only OpenSSL is the case it exists for.
  */
final case class FfiSystemLibrary(
    headers: Seq[String],
    linkLibs: Seq[String] = Nil,
    linkLibsByOs: Map[String, Seq[String]] = Map.empty,
    staticLink: Boolean = false,
    prefixesByOs: Map[String, Seq[String]] = Map.empty
) {

    /** The libraries to link on `os`: the always-on ones, then `os`'s own, deduplicated. */
    def resolvedLinkLibs(os: String): Seq[String] =
        (linkLibs ++ linkLibsByOs.getOrElse(FfiSystemLibrary.osKey(os), Nil)).distinct

    /** The prefixes to try on `os` after the compiler's defaults. */
    def prefixes(os: String): Seq[String] = prefixesByOs.getOrElse(FfiSystemLibrary.osKey(os), Nil)
}

object FfiSystemLibrary {
    private[sbt] def osKey(os: String): String = if (os == "linux-musl") "linux" else os
}
