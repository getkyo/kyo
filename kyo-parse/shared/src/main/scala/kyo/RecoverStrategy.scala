package kyo

@FunctionalInterface
trait RecoverStrategy[In, Out]:
    def apply(failedParser: Out < Parse[In]): Out < Parse[In]

object RecoverStrategy:

    def viaParser[In, Out](parser: Out < Parse[In]): RecoverStrategy[In, Out] =
        _ => parser

    def skipThenRetryUntil[In, Out](skip: Any < Parse[In], until: Any < Parse[In])(using Tag[Parse[In]], Frame): RecoverStrategy[In, Out] =
        failedParser =>
            Parse.firstOf(
                until.andThen(Parse.fail("Until reached")),
                skip.andThen(Parse.firstOf(failedParser, skipThenRetryUntil(skip, until)(failedParser)))
            )

    def nestedDelimiters[In, Out](
        left: In,
        right: In,
        others: Seq[(In, In)],
        fallback: Out
    )(using CanEqual[In, In], Tag[Parse[In]], Frame): RecoverStrategy[In, Out] =
        viaParser:
            val allDelimiters = (left, right) +: others

            def manyBlocks: Unit < Parse[In] =
                val anyBlock =
                    Parse.firstOf(
                        others.map((l, r) =>
                            () =>
                                Parse.between(
                                    Parse.literal(l),
                                    manyBlocks,
                                    Parse.literal(r)
                                )
                        )
                    )

                val skip =
                    Parse.andIs(
                        Parse.any.andThen(()),
                        Parse.not(Parse.firstOf(
                            allDelimiters.flatMap((l, r) => Seq(() => Parse.literal(l), () => Parse.literal(r)))
                        ))
                    )

                Parse.repeat(Parse.firstOf(
                    anyBlock,
                    skip
                )).andThen(())
            end manyBlocks

            Parse.between(
                Parse.literal(left),
                manyBlocks,
                Parse.literal(right)
            ).andThen(fallback)
end RecoverStrategy
