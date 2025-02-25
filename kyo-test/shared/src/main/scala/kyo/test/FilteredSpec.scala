package kyo.test

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.Trace

/**
 * Filters a given `Spec` based on the command-line arguments. If no arguments
 * were specified, the spec returns unchanged.
 */
private[zio] object FilteredSpec {

  def apply[R, E](spec: Spec[R, E], args: TestArgs)(implicit trace: Trace): Spec[R, E] = {
    val testSearchedSpec = args.testSearchTerms match {
      case Nil => spec
      case testSearchTerms =>
        spec.filterLabels(label => testSearchTerms.exists(term => label.contains(term))).getOrElse(Spec.empty)
    }
    val tagIgnoredSpec = args.tagIgnoreTerms match {
      case Nil => testSearchedSpec
      case tagIgnoreTerms =>
        testSearchedSpec
          .filterNotTags(tag => tagIgnoreTerms.contains(tag))
          .getOrElse(Spec.empty)
    }

    args.tagSearchTerms match {
      case Nil => tagIgnoredSpec
      case tagSearchTerms =>
        tagIgnoredSpec
          .filterTags(tag => tagSearchTerms.contains(tag))
          .getOrElse(Spec.empty)
    }
  }
}
