package kyo.internal

import scala.quoted.*

/** JVM-specific primitive type symbols for the `isSerializableType` gate.
  *
  * Cross-build shadow (mirrors `AsciiStringFactory`): the same `object PlatformSymbols`
  * is defined in `kyo-schema/{jvm,js,native}/src/main/scala/kyo/internal/PlatformSymbols.scala`;
  * the sbt cross-project picks the right one per platform. JS and Native return `Set.empty`;
  * JVM registers the seven types backing the JVM-only string-transform `Schema` givens defined
  * in `PlatformSchemas.scala` so they pass the `metaApply` `isSerializableType` check.
  */
private[internal] object PlatformSymbols:

    def primitiveSymbols(using Quotes): Set[quotes.reflect.Symbol] =
        import quotes.reflect.*
        Set(
            TypeRepr.of[java.net.URI].typeSymbol,
            TypeRepr.of[java.net.URL].typeSymbol,
            TypeRepr.of[java.net.InetAddress].typeSymbol,
            TypeRepr.of[java.nio.file.Path].typeSymbol,
            TypeRepr.of[java.io.File].typeSymbol,
            TypeRepr.of[java.util.Locale].typeSymbol,
            TypeRepr.of[java.util.Currency].typeSymbol
        )
    end primitiveSymbols
end PlatformSymbols
