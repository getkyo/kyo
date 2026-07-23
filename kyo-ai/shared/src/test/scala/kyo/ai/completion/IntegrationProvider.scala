package kyo.ai.completion

import kyo.StaticFlag

/** Narrows the live integration matrix to one provider, e.g. `-Dkyo.ai.completion.provider=openai`.
  *
  * Declared in this package rather than in `kyo`, where it would shadow the module's own
  * `kyo.ai.provider` flag for any test importing `kyo.*` and silently answer questions asked of that
  * one instead.
  */
private[kyo] object provider extends StaticFlag[String]("")
