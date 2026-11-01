package kyo.lang

import scala.annotation.StaticAnnotation

/** Metadata attached to every compiled `.kyo` definition.
  *
  * The `kyo-lang` compiler plugin adds this annotation to each object, method, and value it prepares from a `.kyo` source, after the
  * definition's body has been validated and lowered. A later compilation, of another `.kyo` file or of the same file against a
  * published artifact, reads the annotation from the symbol and admits the definition without re-reading its source: the metadata is
  * the record that the definition went through the language's checks.
  *
  * The annotation is generated, never written. The plugin rejects a `.scala` source that declares it by hand, because the admission
  * it grants would otherwise extend to code that no check has seen. It is a `StaticAnnotation`, so it persists in TASTy and classfiles
  * and is available across separate compilation and across artifacts.
  */
final class Checked extends StaticAnnotation
