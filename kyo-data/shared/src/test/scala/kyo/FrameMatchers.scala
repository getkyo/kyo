package kyo

import org.scalatest.matchers.{MatchResult, Matcher}

object FrameMatchers {
    class ProceedingFrame(right: Frame, offset: Int) extends Matcher[Frame]:
        def apply(left: Frame): MatchResult =
            val proceeds = left.className == right.className &&
                left.methodName == right.methodName &&
                left.position.lineNumber == right.position.lineNumber - offset

            val failureMessageSuffix =
                "frame " + left.position.show + " did not proceed " + right.position.show + " by " + offset + " lines"

            val negatedFailureMessageSuffix =
                "frame " + left.position.show + " proceeded " + right.position.show + " by " + offset + " lines"

            MatchResult(
                proceeds,
                "The " + failureMessageSuffix,
                "The " + negatedFailureMessageSuffix,
                "the " + failureMessageSuffix,
                "the " + negatedFailureMessageSuffix
            )
    end ProceedingFrame

    def proceed(right: Frame, offset: Int = 1): Matcher[Frame] =
        new ProceedingFrame(right, offset)
}
