# kyo-parse

A parser in kyo-parse is a value of type `A < Parse[In]`: a Kyo computation that consumes elements of type `In` from a position-tracked input and either produces an `A` (advancing the position) or drops the current parse branch so an enclosing alternative can try something else. You compose parsers with the usual combinators (`firstOf`, `inOrder`, `repeat`, `separatedBy`, `between`, `attempt`, `peek`, `require`) and discharge the effect by running a parser against a `String`, `Chunk[In]`, or `Stream[String, S]`. `In` is almost always `Char`, but the effect is parametric: you can parse any token stream (`Parse[Int]`, `Parse[Token]`) with the same combinators.

Two behaviors drive the rest of the library. Backtracking: `firstOf` tries alternatives in order, rewinding the input on each failed attempt, and `attempt` exposes that backtracking as `Maybe[A]` so you can branch on it explicitly. The cut: `require` commits to a parser, so a failure inside it is propagated as a fatal failure that `firstOf` will not swallow. Fatal failures stop normal alternation, but can still be intercepted by `recoverWith` plus a `RecoverStrategy` (skip-and-retry, balanced-delimiter resync, or a custom replacement parser), which is how error recovery and partial-AST reporting are built. Works across JVM, JavaScript, and Scala Native.

```scala
val greeting: String < Abort[ParseError] =
    Parse.runOrAbort("hello")(Parse.literal("hello"))

val n: Int < Abort[ParseError] =
    Parse.runOrAbort("42")(Parse.entireInput(Parse.int))
```

## Writing parsers

A parser is just a Kyo computation in the `Parse[In]` effect row. The smallest building block is `Parse.read`: it sees a `ParseInput[In]` (a position-tracked token buffer), inspects `remaining`, and returns either a successful `(newInput, Out)` or a failure chunk that gets recorded in the parse state.

### The Parse effect

`Parse[In]` is parametric in the token type. Two parsers with different `In` can co-exist in the same effect row: a `Parse[Int] & Parse[Char]` computation handles each input stream with its own `runResult` call.

```scala
val intCharZip: Chunk[(Char, Int)] < (Parse[Int] & Parse[Char]) =
    for
        chars <- Parse.repeat(Parse.any[Char])
        ints  <- Parse.repeat(Parse.any[Int])
    yield chars.zip(ints)

val result: Chunk[(Char, Int)] < Abort[ParseError] =
    intCharZip.handle(
        Parse.runOrAbort("abc"),
        Parse.runOrAbort(Chunk(1, 2, 3))
    )
```

`Tag[Parse[In]]` disambiguates the two effects at the handler site. Most readers never need to think about this: a single `Parse[Char]` row is the common case, and the `Tag` is resolved by the compiler.

### `read`, `readOne`, `readWhile`

`Parse.read` is the fundamental hook. Pass it a function from `ParseInput[In]` to `Result[Chunk[ParseFailure], (ParseInput[In], Out)]`: `Result.Success` advances the input, `Result.Failure` drops the branch.

```scala
// A custom hex-digit parser, built directly on read.
val hexDigit: Int < Parse[Char] =
    Parse.read: in =>
        if in.done then
            Result.fail(Chunk(ParseFailure("EOF", in.position)))
        else
            val c = in.remaining.head
            val v =
                if c.isDigit then c - '0'
                else if c >= 'a' && c <= 'f' then c - 'a' + 10
                else if c >= 'A' && c <= 'F' then c - 'A' + 10
                else -1
            if v >= 0 then Result.succeed((in.advance(1), v))
            else Result.fail(Chunk(ParseFailure(s"Expected hex digit, got $c", in.position)))

val hex: Int < Abort[ParseError] =
    Parse.runOrAbort("a")(hexDigit) // 10
```

`readOne[A](f: In => Result[Chunk[String], A])` is the convenience form for "look at exactly one token." `readWhile(f: A => Boolean)` consumes a run of matching tokens into a `Chunk[A]` without dropping the branch even if the run is empty.

### Dropping a branch

`Parse.fail(message)` appends a `ParseFailure` to the accumulating state and drops the current branch. It is what every `read`-derived combinator does on a mismatch.

```scala
val onlyZero: Int < Parse[Char] =
    for
        n <- Parse.int
        v <-
            if n == 0 then (0: Int < Parse[Char])
            else Parse.fail(s"Expected 0, got $n")
    yield v

val ok: ParseResult[Int] < Any = Parse.runResult("0")(onlyZero)
val no: ParseResult[Int] < Any = Parse.runResult("7")(onlyZero)
```

> **Note:** `fail` records the message even on branches that `firstOf` later discards. The recorded failures from earlier branches stay in `ParseResult.errors`, so a successful parse can still report non-fatal collected failures.

### Position and rewind

`Parse.position` reads the current cursor; `Parse.rewind(p)` jumps it back to a saved position. These are the primitives behind `peek` and `andIs`; user code rarely calls them directly.

```scala
val saveAndRestore: Int < Parse[Char] =
    for
        start <- Parse.position
        n     <- Parse.int
        _     <- Parse.rewind(start) // back to where we started
    yield n
```

### `ParseInput[In]`

The token buffer parsers see. `remaining: Chunk[In]` is the unconsumed tail; `advance(n)` moves the cursor; `advanceWhile(pred)` skips matching tokens; `done` is the EOF check. You only handle a `ParseInput` directly when calling `Parse.read`.

## Sequencing and choice

Combine parsers in order or as alternatives. These are the day-one vocabulary you use to express grammars.

### `inOrder`

`inOrder` runs two through eight parsers in sequence and returns the results as a tuple. If any sub-parser drops, the whole sequence drops.

```scala
val assignment: (String, Char, Int) < Parse[Char] =
    Parse.inOrder(
        Parse.identifier,
        Parse.literal('='),
        Parse.int
    )

val parsed: (String, Char, Int) < Abort[ParseError] =
    Parse.runOrAbort("x=42")(assignment)
```

For sequences longer than eight or for variadic shapes, build the same effect with a `for` comprehension; `inOrder` is just sugar for the common arities.

### `firstOf`

`firstOf` tries alternatives left-to-right, rewinding the input on each failed attempt and returning the first successful result. If every branch fails, the whole `firstOf` drops.

```scala
val signed: Int < Parse[Char] =
    Parse.firstOf(
        Parse.literal('-').andThen(Parse.int).map(-_),
        Parse.int
    )

val a: Int < Abort[ParseError] = Parse.runOrAbort("-7")(signed) // -7
val b: Int < Abort[ParseError] = Parse.runOrAbort("12")(signed) // 12
```

`firstOf` is PEG-style ordered choice. Earlier parsers take precedence; there is no ambiguity detection and no "try every branch" combinator. Each branch is implicitly wrapped in `attempt`, so wrapping again is a no-op.

> **Note:** `firstOf` rewinds only on a non-fatal failure. A `require` failure inside one branch propagates and aborts the whole `firstOf`. That is intentional, see [Commitment and recovery](#commitment-and-recovery).

### `between`

Wrap content in two discardable delimiters and return the content.

```scala
val parens: Int < Parse[Char] =
    Parse.between(Parse.literal('('), Parse.int, Parse.literal(')'))

val n: Int < Abort[ParseError] = Parse.runOrAbort("(42)")(parens)
```

### `separatedBy`

Parse a list of elements separated by a delimiter, returning a `Chunk[Out]`. The `allowTrailing` flag controls whether a dangling separator is tolerated.

```scala
val csv: Chunk[Int] < Parse[Char] =
    Parse.separatedBy(Parse.int, Parse.literal(','))

val xs: Chunk[Int] < Abort[ParseError] =
    Parse.runOrAbort("1,2,3")(csv) // Chunk(1, 2, 3)
```

If no first element matches, `separatedBy` succeeds with an empty chunk; once at least one element is parsed, missing-element-after-separator drops unless `allowTrailing = true`.

> **Note:** By default a trailing separator is rejected (the parse fails with "Trailing separator not allowed"); pass `allowTrailing = true` to tolerate one.

## Repetition: zero-or-more, exactly-n, and until-marker

Zero-or-more, exactly-n, and parse-until-marker shapes.

### `repeat`

Two overloads. The open form runs the parser until it fails and returns every successful result; the bounded form requires exactly `n` successes.

```scala
val many: Chunk[Int] < Parse[Char] =
    Parse.repeat(Parse.between(Parse.literal('['), Parse.int, Parse.literal(']')))

val exact: Chunk[Int] < Parse[Char] =
    Parse.repeat(3)(Parse.between(Parse.literal('['), Parse.int, Parse.literal(']')))

val a: Chunk[Int] < Abort[ParseError] = Parse.runOrAbort("[1][2][3]")(many)
val b: Chunk[Int] < Abort[ParseError] = Parse.runOrAbort("[1][2][3]")(exact)
```

`repeat(n)` drops the branch if it cannot find `n` elements; `repeat` (unbounded) always succeeds, possibly with an empty `Chunk`.

### `repeatUntil`

Run the element parser repeatedly until the terminator succeeds. The terminator is consumed; its result is discarded.

```scala
val sentence: Chunk[String] < Parse[Char] =
    Parse.repeatUntil(
        Parse.identifier,
        Parse.literal('.')
    )

val words: Chunk[String] < Abort[ParseError] =
    Parse.runOrAbort("alpha beta gamma.")(
        Parse.spaced(sentence)
    )
```

### `skipUntil`

Discard tokens one at a time until the target parser succeeds, then return the target's result. Useful when you want to recover by jumping forward to a known anchor.

```scala
val nextHeader: String < Parse[Char] =
    Parse.skipUntil(Parse.literal("##"))

val found: String < Abort[ParseError] =
    Parse.runOrAbort("junk and noise ## here")(nextHeader)
```

## Lookahead and backtracking

Inspect input without consuming, or turn the implicit `firstOf` backtrack into a value you can branch on.

### `attempt`

Wrap a parser to expose its drop as `Maybe[A]`. A successful parse returns `Present(a)` and consumes input; a drop returns `Absent` and rewinds.

```scala
val maybeSigned: Maybe[Char] < Parse[Char] =
    Parse.attempt(Parse.literal('-'))

val withSign: Int < Parse[Char] =
    for
        sign  <- Parse.attempt(Parse.literal('-'))
        digit <- Parse.int
    yield if sign.isDefined then -digit else digit

val n: Int < Abort[ParseError] = Parse.runOrAbort("-42")(withSign) // -42
val m: Int < Abort[ParseError] = Parse.runOrAbort("42")(withSign)  // 42
```

> **Note:** Each branch of `firstOf` is already wrapped in `attempt` internally. Wrapping a `firstOf` branch in `attempt` again has no effect; reach for `attempt` when you want the `Maybe[A]` value, not because you suspect the branch might consume on failure.

### `peek`

Try a parser, rewind on success too. Use `peek` to test what comes next without committing to it.

```scala
val looksLikeNumber: Boolean < Parse[Char] =
    Parse.peek(Parse.int).map(_.isDefined)

val b: Boolean < Abort[ParseError] = Parse.runOrAbort("42abc")(looksLikeNumber) // true; "42abc" still unconsumed
```

`peek` is `attempt` plus a save-position / rewind. It never consumes input, even on success. That is the difference from `attempt`, which consumes on success.

### `not`

Succeed (consuming nothing) iff the parser fails. The dual of `peek` for negative lookahead.

```scala
// An identifier that is not the reserved word "let".
val nonLet: String < Parse[Char] =
    for
        _    <- Parse.not(Parse.literal("let"))
        name <- Parse.identifier
    yield name
```

### `andIs`

Parse the first parser, then verify a second parser also matches at the produced position. The cursor rewinds to immediately after the first parser's result.

```scala
// Match an identifier only if a '(' follows (function call syntax).
val funcCall: String < Parse[Char] =
    Parse.andIs(Parse.identifier, Parse.literal('('))

val name: String < Abort[ParseError] =
    Parse.runOrAbort("foo(")(funcCall) // "foo", cursor stops before '('
```

## Commitment and recovery

Sometimes you want backtracking, and sometimes you want to commit. `require` switches off backtracking on purpose, and `recoverWith` lets you intercept the resulting fatal failure.

### `require`

Wrap a parser in `Parse.require` to convert any failure inside it into a fatal failure. A surrounding `firstOf` will not try other branches; the whole alternation aborts.

```scala
// Once we see '=', a value MUST follow. A missing value is a fatal failure,
// not a signal to try the next alternative.
val entry: (String, Int) < Parse[Char] =
    for
        key <- Parse.identifier
        _   <- Parse.literal('=')
        v   <- Parse.require(Parse.int)
    yield (key, v)

val ok: (String, Int) < Abort[ParseError] = Parse.runOrAbort("x=42")(entry)
```

This is the PEG "cut": a deliberate refusal to backtrack. It makes error messages precise (the parser stops at the exact failing position) and turns garden-path ambiguity into a hard error.

### `recoverWith`

Install a `RecoverStrategy` around a parser. If the parser fails (including via `require`), the strategy runs in a cleared failure state; its successes are merged back into the outer state.

```scala
val recovered: Int < Parse[Char] =
    Parse.recoverWith(
        Parse.between(Parse.literal('('), Parse.int, Parse.literal(')')),
        RecoverStrategy.viaParser[Char, Int](0)
    )

// "(7)" parses normally. "(garbage)" fails the int parser, then the recovery
// strategy produces 0 and the outer parse continues.
val a: ParseResult[Int] < Any = Parse.runResult("(7)")(recovered)
val b: ParseResult[Int] < Any = Parse.runResult("(garbage)")(recovered)
```

When to reach for `attempt` vs `require` vs `recoverWith`. `attempt` exposes a non-fatal drop as `Maybe`, so callers can branch on it. `require` makes a parser commit: a failure is fatal and ends alternation. `recoverWith` is the only way to keep parsing after a fatal failure; it pairs naturally with `require` to produce partial ASTs that include error nodes.

### `RecoverStrategy`

A `RecoverStrategy[In, Out]` is `failedParser => recoveryParser`. The trait is `@FunctionalInterface`, so a SAM literal is allowed; the companion ships three ready-made strategies.

`viaParser(p)` returns `p` regardless of the failed parser. Use it for a constant fallback value.

`skipThenRetryUntil(skip, until)` consumes one `skip` token at a time, retrying the original parser after each, and gives up when `until` matches. Use it for "advance to the next statement-like boundary."

`nestedDelimiters(left, right, others, fallback)` walks a balanced run of `(left, right)` pairs (counting any other delimiter pairs in `others` so nesting stays balanced), then returns `fallback` for the failed parser's slot. This is the standard "syntax error inside braces" recovery.

```scala
val recoverBlock: Int < Parse[Char] =
    Parse.recoverWith(
        Parse.between(Parse.literal('('), Parse.int, Parse.literal(')')),
        RecoverStrategy.nestedDelimiters(
            left = '(',
            right = ')',
            others = Seq('[' -> ']', '{' -> '}'),
            fallback = -1
        )
    )

val result: ParseResult[Int] < Any =
    Parse.runResult("(broken {nested} stuff)")(recoverBlock)
// out = Present(-1), errors records the nested-delimiter recovery path
```

## Token-level combinators

One-token matchers that work for any `In`, not just `Char`.

### `any`, `anyIf`, `anyMatch`

`any` consumes one token unconditionally. `anyIf(pred)(errMsg)` consumes one matching token. `anyMatch(pf)` consumes one token in the partial function's domain and returns the mapped value.

```scala
val nextToken: Char < Parse[Char] = Parse.any[Char]

val vowel: Char < Parse[Char] =
    Parse.anyIf[Char](c => "aeiou".contains(c))(c => s"Expected vowel, got $c")

val digit: Int < Parse[Char] =
    Parse.anyMatch { case c if c.isDigit => c - '0' }
```

### `anyIn`, `anyNotIn`

One-token-in-set and its complement. Both have overloads for `Seq[A]`, `String` (for `In = Char`), and varargs.

```scala
val op: Char < Parse[Char]    = Parse.anyIn("+-*/")
val notOp: Char < Parse[Char] = Parse.anyNotIn("+-*/")
```

### `literal`

Two overloads with different shapes. `literal[A](value)` matches a single token via `CanEqual` for any token type. `literal(text: String)` matches a whole `String` against a `Parse[Char]` input.

```scala
val openParen: Char < Parse[Char] = Parse.literal('(')   // single Char
val keyword: String < Parse[Char] = Parse.literal("def") // whole String
```

> **Caution:** The overloads have the same name but different shapes; calling `Parse.literal("def")` matches the four-character substring, while `Parse.literal('d')` matches a single `Char`. Reaching for the wrong one silently changes what is matched.

### `end`

Succeeds only at EOF; drops the branch with an "Expected: EOF, Got: ..." failure otherwise. Pair it with `entireInput` if you want "parse must consume everything"; see [Running parsers](#running-parsers).

```scala
val justOne: Int < Parse[Char] =
    for
        n <- Parse.int
        _ <- Parse.end
    yield n
```

## Character parsers

Ready-made character-level parsers for the common shapes. All of these have `In = Char`.

### `int` and `decimal`

`int` consumes any run of digits or `'-'` characters, then parses the run via `toIntOption`. `decimal` does the same with digits, `'.'`, and `'-'`, parsing via `toDoubleOption`.

```scala
val n: Int < Abort[ParseError]    = Parse.runOrAbort("42")(Parse.int)
val d: Double < Abort[ParseError] = Parse.runOrAbort("3.14")(Parse.decimal)
```

> **Caution:** `int` and `decimal` are greedy and non-overlapping. Parsing `"3.14"` with `Parse.int` consumes `"3"` and stops at `.`, leaving `.14` unconsumed (and the parse succeeds with `3`). Use `decimal` when the input might have a fractional part. Both validators run on the consumed run via `toIntOption` / `toDoubleOption`, so malformed inputs like `--5` or `1.2.3` fail with a generic "Invalid int" / "Invalid decimal" message, not a position pointing at the bad character.

### `boolean`

Matches the literal text `"true"` or `"false"` and returns the corresponding `Boolean`.

```scala
val b: Boolean < Abort[ParseError] = Parse.runOrAbort("true")(Parse.boolean)
```

### `identifier`

A letter or underscore followed by letters, digits, or underscores. Returns a `String`.

```scala
val name: String < Abort[ParseError] = Parse.runOrAbort("user_42")(Parse.identifier)
```

### `whitespaces`

Consumes a run of whitespace characters and returns the consumed `String`. The "ambient" form below is usually what you want; call `whitespaces` directly only when whitespace is part of the grammar.

```scala
val ws: String < Parse[Char] = Parse.whitespaces
```

### `regex`

Two overloads, one taking `scala.util.matching.Regex` and one taking a `String` (compiled to `Regex` once). Both match the regex against a prefix of `remaining` and return the matched text.

```scala
val word: String < Abort[ParseError] =
    Parse.runOrAbort("hello world")(Parse.regex("[a-z]+"))
```

## Ambient whitespace handling

A grammar that ignores whitespace at every step does not want to weave `whitespaces` calls between every combinator. `Parse.spaced(parser, isWhitespace)` installs a discard predicate that applies to every parser inside the sub-computation.

```scala
val tokens: Chunk[Int] < Parse[Char] =
    Parse.spaced(Parse.repeat(Parse.int))

val xs: Chunk[Int] < Abort[ParseError] =
    Parse.runOrAbort("  1   2   3  ")(tokens) // Chunk(1, 2, 3)
```

> **Note:** `spaced` reaches into the entire sub-computation, not just the immediate parser. Every read inside `tokens` consults the active discard predicate before consuming, so a deeply nested `int` inside a `firstOf` inside a `repeat` still skips leading whitespace. The scaladoc calls this an Aspect-like override. The predicate defaults to `(_: Char).isWhitespace`; pass a custom one to discard comments, indent markers, or other syntactic noise.

## Running parsers

Discharge `Parse[In]` against an input source. The full result is a `ParseResult[Out]`; helpers collapse it to `Abort[ParseError]` or stream successful results.

### `runResult`

Returns a `ParseResult[Out]` carrying every collected failure plus the optional `Out`. Overloads cover `String`, `Chunk[In]`, `ParseInput[In]`, and `ParseState[In]`.

```scala
val r: ParseResult[Int] < Any =
    Parse.runResult("42")(Parse.int)
// ParseResult(errors = Chunk.empty, out = Present(42), fatal = false)
```

A `ParseResult` is the success-with-warnings shape. `out` can be `Present` even when `errors` is non-empty: branches discarded by `firstOf` still leave their failure messages behind, and a recovery strategy merges its post-recovery errors into the result. `isFailure` is `true` when either `out` is `Absent` or `errors` is non-empty.

### `runState`

Like `runResult`, plus the final `ParseState[In]`. Useful when you need to know how much input was consumed, or to chain a second parser starting from where the first left off.

```scala
val st: (ParseState[Char], ParseResult[Int]) < Any =
    Parse.runState(ParseState(ParseInput(Chunk.from("42rest"), 0), Chunk.empty))(Parse.int)
```

### `runOrAbort`

Collapse the `ParseResult` to a value or an `Abort[ParseError]`. Partial-error chunks are discarded on success; failures become `ParseError` (a `KyoException` carrying `Chunk[ParseFailure]`). Overloads cover `String` and `Chunk[In]`.

```scala
val n: Int < Abort[ParseError] = Parse.runOrAbort("42")(Parse.int)
```

When to reach for `runResult` vs `runOrAbort`. Use `runResult` when you want to inspect non-fatal collected failures alongside a successful value (typical for IDE-style partial-AST reporting). Use `runOrAbort` for the common case where any failure should abort and any non-failure should yield the value cleanly.

### `entireInput`

Wrap a parser so that EOF must immediately follow. Without it, `runResult("12abc")(Parse.int)` succeeds with `12` and leaves `"abc"` unconsumed; with it, the same call fails with "Incomplete parse - remaining input not consumed."

> **Caution:** This is the common footgun behind "why did my malformed input parse?": a bare `Parse.repeat(p)` succeeds on a partial match and silently leaves the rest of the input unconsumed unless you wrap it in `entireInput`.

```scala
val all: Int < Abort[ParseError] =
    Parse.runOrAbort("42")(Parse.entireInput(Parse.int))
```

### `runStream`

Run a character parser against a `Stream[String, S]`, emitting one successful result per match. The runner accumulates text across chunks, re-runs the parser on the growing buffer, and continues with the unconsumed tail after each success.

```scala
val numbers: Stream[Int, Abort[ParseError]] =
    Parse.runStream(Stream.init(Seq[String]("1 ", "2 ", "3 ")))(
        for
            n <- Parse.int
            _ <- Parse.attempt(Parse.literal(' '))
        yield n
    )

val collected: Chunk[Int] < Abort[ParseError] = numbers.run
// Chunk(1, 2, 3)
```

> **Caution:** `runStream` buffers text across chunks and re-runs the parser on every accumulated buffer. The parser must be idempotent over the buffer prefix: each successful parse emits, then the runner continues with the unconsumed tail. A parser that consumes input only conditionally on EOF will not behave as the reader expects under streaming.

### Result shapes

`ParseFailure(message: String, position: Int)` is a single recorded error. `ParseResult[Out](errors: Chunk[ParseFailure], out: Maybe[Out], fatal: Boolean)` is the run output, and `orAbort` collapses it to `Abort[ParseError]`. `ParseError(failures: Chunk[ParseFailure])` is the `KyoException` subtype carried by `Abort` after `runOrAbort`.

```scala
val fail: ParseResult[Int] < Any =
    Parse.runResult("abc")(Parse.int)
// errors = Chunk(ParseFailure("Invalid int", 0)), out = Absent, fatal = false
```

## Putting it together

The combinators from the preceding sections compose naturally. This arithmetic-expression parser builds a grammar bottom-up using `firstOf`, `inOrder`, `between`, `spaced`, `entireInput`, and the three run modes, all against the same `Parse[Char]` effect row.

```scala
// term ::= '(' expr ')' | int        expr ::= term op expr | term

def term: Int < Parse[Char] =
    Parse.firstOf(
        Parse.between(Parse.literal('('), expr, Parse.literal(')')),
        Parse.int
    )

def expr: Int < Parse[Char] =
    Parse.firstOf(
        for
            left  <- term
            op    <- Parse.anyIn("+-*/")
            right <- expr
        yield op match
            case '+' => left + right
            case '-' => left - right
            case '*' => left * right
            case '/' => left / right,
        term
    )

// Abort on failure; whitespace skipped at every step.
val program: Int < Abort[ParseError] =
    Parse.runOrAbort("(1 + 2) * 3")(Parse.spaced(Parse.entireInput(expr)))

// Collect non-fatal failures alongside the result instead of aborting.
val partial: ParseResult[Int] < Any =
    Parse.runResult("(1 + 2) * 3")(Parse.spaced(Parse.entireInput(expr)))

// Emit one Int per successful match from a stream of text chunks.
val live: Stream[Int, Abort[ParseError]] =
    Parse.runStream(Stream.init(Seq[String]("1 ", "2 ", "3 ")))(
        Parse.spaced(Parse.int)
    )
```

## Low-level state access

Escape hatches for library authors writing custom handler shapes or new combinators that the high-level surface cannot express. End-user grammars do not reach for these.

### `modifyState`

Suspend the effect with a custom `ParseState[In] => (ParseState[In], Maybe[Out])` transition. Every higher-level combinator (`read`, `fail`, `position`, `rewind`) is defined in terms of `modifyState`.

```scala
// A combinator that returns the current failure count without changing state.
val errorCount: Int < Parse[Char] =
    Parse.modifyState[Int]: state =>
        (state, Present(state.failures.length))
```

### `ParseState[In]` and `Parse.Op`

`ParseState[In]` is the handler-visible state: `input: ParseInput[In]`, `failures: Chunk[ParseFailure]`, `isDiscarded: In => Boolean`. `Parse.Op` is the algebraic-effect enum (`ModifyState`, `Attempt`, `Require`, `RecoverWith`, `Discard`) that custom handlers pattern-match on. Both are load-bearing for handler implementers; ordinary parser authors never see them.
