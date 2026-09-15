# Contributing to kyo-sql

Module-specific guide for kyo-sql and its backend modules (`kyo-sql-postgres`, `kyo-sql-mysql`, and any engine added later). Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary, test patterns, and the unsafe-boundary tiers that apply across all of Kyo. This document records what is specific to kyo-sql: what the module promises across engines, how each promise is held, and the discipline that keeps an engine's quirks from leaking into a caller.

## The headline invariant

**A program written against kyo-sql behaves the same on every backend, or it fails.** What it must never do is behave *differently*.

That is the whole value proposition of the module, and it is stronger than it sounds. It is not only that the same query returns the same rows. It is that every observable a caller can reach agrees across engines:

| surface | what must agree |
|---|---|
| decoding | `decode[A]` answers the same `A` for the same stored value |
| rendering | `SqlRow.text` answers the same string for the same stored value |
| metadata | `columnKind` reports the same neutral kind for the same column type |
| errors | the same failure raises the same `SqlException` leaf |
| transactions | commit, rollback, nesting, and isolation mean the same thing |
| cancellation | an interrupted statement leaves the connection in the same state |
| streaming | a stream yields the same rows and releases the same resources |
| pooling | a lease, a warmup, and a discard behave the same |
| naming | a `SqlNaming` casing resolves the same way |
| writes | a generated key comes back the same way |

`kyo-sql-tests` carries a conformance battery per surface, and each one is a specification a new backend must satisfy. When you touch any of them, the question is not "does my engine still pass" but "do all of them still agree".

An engine that *cannot* do something is a separate matter from an engine that does it *differently*, and telling those apart is the discipline this guide is mostly about.

Note the scope of that table: it is what a caller observes **through a neutral type**. It is not a claim that the backends are alike, and most of a backend module has nothing to do with it.

## What legitimately lives in a backend module

Conformance governs observables reached through neutral types. It says nothing about how an engine is spoken to, and that is the bulk of the work:

- **The wire protocol.** Message framing, packet layout, the extended and simple query flows, pipelining. PostgreSQL and MySQL share nothing here.
- **Authentication.** SCRAM-SHA-256, `caching_sha2_password`, `sha256_password`, `mysql_native_password`, and each engine's TLS negotiation.
- **The type tables.** OIDs on one side, type bytes plus flags and collation on the other, and the mapping from each to the neutral vocabulary.
- **The dialect.** `SqlIdiom` generates genuinely different SQL per engine, and that is its job.
- **Connection lifecycle.** What a reset means, what a clean session is, how prepared statements are cached and released.
- **Engine-only types.** `inet`, `cidr`, `macaddr`, `tsvector`, ranges, arrays on one side; `GEOMETRY`, `SET`, `ENUM`, `BIT` on the other, with their wire layouts and decoders.
- **Engine-only public API.** `PostgresClient.copyIn`, `copyOut`, `notifications`, `parameters`; `MysqlClient.loadLocalInfile`.

That last one is worth stating plainly, because a rule about conformance can read as though engine-specific API were a smell. It is not. A caller who holds a `PostgresClient` rather than a `SqlClient` has **chosen** engine-specific behavior, and the type says so at every use site. Extending that surface is ordinary work, and its tests belong in the backend module.

So the discriminator is the type in the caller's hand:

| the caller holds | the rule |
|---|---|
| a neutral type (`SqlClient`, `SqlRow`, `decode[A]`, `SqlRow.text`) | must conform; a difference is a bug |
| an engine type (`PostgresClient`, `MysqlClient`) | engine-specific is the contract; tested in that module |

## Mechanism may diverge; observables may not

The two rules above look contradictory until the line is drawn in the right place, and drawing it wrongly is how a design goes astray.

`SqlIdiom` **is** an extension point, because SQL syntax legitimately differs: `LIMIT` against `FETCH FIRST`, backticks against double quotes, `RETURNING` against a generated-key round trip. Two backends emitting different SQL for one query is correct behavior.

Value rendering **is not** an extension point, because two engines holding one value must produce one string. Shaping it like an idiom would institutionalize exactly the divergence the module exists to remove.

The distinction is not "shared code good, per-engine code bad". It is:

- **Mechanism** ; how the result is produced. Free to differ, and usually must.
- **Observable** ; what a caller sees through a neutral type. Must not differ.

When you are unsure which side something belongs on, ask what a caller could write a test against. If they can observe it holding only neutral types, it is an observable.

## Conformance is the default for neutral behavior, and leaving it is a gate

**Every behavior both engines can exhibit through a neutral type belongs in the shared battery, with one pinned expectation.**

This is a gate on one specific move: taking something a caller reaches through a neutral type and making it per-engine. It is not a gate on writing engine-specific code, which is most of a backend module and needs no justification at all.

The gate exists because that move is the same one as an exemption inside the battery, and it is easy to make in good faith. This shipped for months:

```scala
if backend.id != "postgres" then succeed(s"${backend.label} renders its own types")
```

The stated reason was false. That backend had no rendering of its own and was answering the JDK's spelling for every column. The exemption did not describe an absent capability, it hid a live divergence, and its plausible wording is exactly why nobody re-read it.

**Relocating a test into a backend module is that identical laundering at file granularity, and it attracts less scrutiny for looking structural.** Before anything leaves the shared battery, apply one question:

> **Can the other engine exhibit this behavior at all?**

- **No** ; a *capability* difference. PostgreSQL has no column type that stores `838:59:59`; MySQL has no `RETURNING`. Genuinely not conformable.
- **Yes** ; a *behavior* difference. Both engines can, and the drivers disagree. **This is a conformance failure wearing a costume. Fix the driver.**

The trap is a wire or protocol asymmetry that presents as a capability difference. MySQL's `TIMESTAMP` arrives converted to the session zone with no offset beside it, while PostgreSQL's `timestamptz` text carries one. That is not licence to diverge: both engines store instants perfectly well, so recovering the instant is a driver obligation (pin the session at connect), not a fact about what the engine can do.

Ask it of every surface, not just the one you are editing. "This engine reports a different SQLSTATE" is a mapping obligation. "This engine's pool warms differently" is usually a bug. "This engine has no `RETURNING`" is real.

**Ask it in both directions, because it often answers only one.** Division by zero is the worked example. One engine raises `division_by_zero`; the other answers an absent value and carries on. Asking "can the second engine raise?" answers no, and no SQL a dialect can render changes that, since its error-on-division-by-zero mode governs data-change statements and never a `SELECT`. Asking the reverse, "can the first engine answer absent?", answers yes, with `NULLIF(divisor, 0)`. One direction was reachable, so the divergence was conformable and the direction was not a matter of taste. The dialect now lowers it, and every dividing arm plus `%` carries the guard.

Two things follow. A behavior is conformable if EITHER engine can be brought to the other's answer, so one "no" does not settle it. And when only one direction is reachable, it is the answer, even when it is the weaker one: a standard error became an absent value here, which is a real loss, and it is still better than the same expression meaning different things on two deployments.

**When both engines fail and only the typed leaf differs, the fix is a marker, not a behavior change.** Overflowing `Int + Int` raises on one engine and is caught by the codec on the other, because that engine widens the expression and answers a value the declared type cannot hold. Neither returns a silently wrong number, so there is nothing to lower; what broke a caller was that the two failures were unrelated typed leaves, so a handler written against one missed the other. `SqlValueOutOfRange` is carried by both, and the conformance leaf asserts the marker. This is the same move `SqlIntegrityViolation` already makes for a constraint violation whose SQLSTATE differs per engine: **the recoverable failure class is the contract, and which layer noticed is not part of it.**

## How a divergence is expressed

A conformance body must not branch on which engine it is running against. It branches on a **named capability** declared on the backend descriptor, or not at all.

```scala
// Wrong: nothing says why, and a third backend silently takes one of the two paths.
if backend.id == "postgres" then ... else ...

// Right: the divergence is named, documented once, and every backend answers it.
if backend.supportsReturning then ...
```

The wrong form appeared in the battery in fifteen places across three files, and converting them is worth reading as a record of what the gate is for, because applying it one branch at a time changed the answer three times:

- **Eight became named capabilities.** `instantWireCarriesOffset` turned out to serve two leaves that looked unrelated, the mirror halves of one wire difference. `timeColumnIsSignedSpan`, `hasNativeArrayColumns`, `booleanColumnKind`, `hasCalendarIntervalColumn`, `hasNetworkAddressColumn`, `hasTimeWithOffsetColumn` and `hasNonFiniteSpecialValues` name the rest.
- **Three were spellings, not capabilities,** and became descriptor hooks: `typeNameFor(kind)`, `bytesLiteral(hex)`, and a literal-spelling function on the rendering table that retired its `postgresLiteral`/`mysqlLiteral` pair.
- **Two needed no branch at all,** which only applying the gate could reveal. The end-of-day leaf was restricted on the stated grounds that the other engine "has no end-of-day time value", and that is false: its time column is a signed SPAN, so `24:00:00` is nowhere near a boundary. The session-settings leaf skipped the engine with no such settings, where an empty list and a universal leaf assert something real for it.

**A skip reason nobody re-reads is wrong for as long as it exists.** Both of those had been sitting in the battery reading as considered judgements. That is the failure this rule exists to prevent, and it is why the gate is applied per branch rather than to the group.

A source scan enforces this now: `SqlBackendNeutralitySourceTest` fails on any engine name reaching a conformance body, with the banned list derived from the registered descriptors so a new backend extends it by existing.

This matters more than which file the test lives in. An inline `if` on an engine name is written in a second and read by nobody. A capability is declared in one place, carries a scaladoc saying **why this is a capability difference**, and every existing backend has to answer it when it is added. That review is the gate.

Rules for capabilities:

- A capability describes what an engine **can or cannot do**. "The driver does it differently" is never a justification; that is a bug report.
- Add one only when a conformance body actually branches on it.
- Its scaladoc says what the difference *is*, not which engine has it.
- DDL differences go through `SqlTestBackend.columnType(kind)` and friends rather than inline literals. If a kind you need is missing, add it; reaching around it is how the engine name gets back in.

Backend-module test trees carry everything listed under [what legitimately lives in a backend module](#what-legitimately-lives-in-a-backend-module), and that is a large and growing surface. The single thing they are not is a route around a neutral-API divergence.

## Prefer guarantees by construction

A rule nobody can violate beats a rule everyone must remember. When a divergence keeps recurring, change the shape so it cannot be written.

The clearest case is value rendering, and it used to be held by convention. The SPI handed the backend the final answer:

```scala
def text(row, idx): String        // a backend could return anything at all
```

Every guarantee was therefore convention plus test coverage, and a backend returning `value.toString` passed every test that lacked a case for that exact type. That is how the JDK's spelling reached one engine's rows unnoticed.

**Built.** The SPI is inverted and the possibility is gone:

```scala
def columnValue(row, idx): SqlValue           // the backend produces neutral data
final def text(row, idx) = SqlValueRender.render(columnValue(row, idx))
```

A backend that wants to spell a bool `t` has nowhere to do it. What makes this hold rather than merely look tidy is that **every `SqlValue` variant carries DATA, never text.** Where a rendering needs a decision, the decision belongs to the renderer and the variant carries the fields it decides from:

- `NonFiniteNumber(NaN | PositiveInfinity | NegativeInfinity)`, not the words.
- `TemporalInfinity(negative)`, not `"infinity"`.
- `NetworkAddress(family, maskBits, address)`, not the address text. This is the sharpest case: rendering an IPv6 address means choosing which run of zero groups collapses to `::` (RFC 5952: longest run, leftmost on a tie, never a run of one), and a backend handing back its own text would own that choice and two backends could disagree about it. The compression moved into the renderer.

One variant looks like an escape, so it is worth being exact about what it is. `ServerRendering(text)` is constructed in exactly one place: the default `SqlRow.Codec.columnValue` on the base trait, which serves a codec whose rows carry no type metadata at all and so has nothing but the bytes. No backend in this repository constructs it. A backend that cannot render a column reports `ColumnKind.Unknown` and the shared positional codec refuses under BOTH wire formats, rather than answering the server's text under one and mojibake under the other.

Be precise about how strong that is. It is not a guarantee by construction: `SqlValue` is public, so an out-of-tree backend could construct `ServerRendering`, and could equally answer `SqlValue.Text(anySpelling)` from its `columnValue`. What the shape buys is that spelling a value is no longer the ordinary path a backend takes, and that doing it is visible in review and caught by the cross-protocol conformance leaves. The guarantee is the battery's; the shape is what makes the wrong move conspicuous.

The inversion also removed a passthrough that had been an exception to the parse-and-re-render rule: `inet` under the text protocol used to hand the server's own text back, so the two protocols agreed only because the server and this module happened to compress an address identically. The text arm now parses into the same fields the binary struct supplies, and both protocols render through one function, so they agree by construction rather than by coincidence.

Two further mechanisms belong with it, both now built as well:

- **`SqlTestBackend.id` is out of every conformance body.** Fifteen such branches existed; each became a named capability or a descriptor hook, and two turned out to need no branch at all.
- **The shared battery is scanned for engine identifiers** by `SqlBackendNeutralitySourceTest`, with the banned list derived from the registered descriptors rather than hardcoded, so a new backend extends it automatically and the check cannot go stale.

Note what the second one concedes: construction cannot catch an inline DDL literal, so a scan covers the gap. Prefer construction, fall back to a check, and treat a check as a sign that the shape could be better.

## Neutral types must span the union of the engines' domains

Anywhere an engine's value crosses into a Scala or JDK type, ask whether that type holds **every** engine's range. Where it does not, the type silently truncates, refuses, or corrupts, and the failure is per-engine by construction.

Cases already found the hard way:

- A **time** is a signed span, not a `java.time.LocalTime`: MySQL's `TIME` runs from -838:59:59 to 838:59:59 and PostgreSQL's `time` reaches 24:00:00 inclusive. `LocalTime` holds neither endpoint, so decoding through it refused values the column legally stores, on both engines.
- A **date** carries its era and permits zero fields: `LocalDate` numbers 1 BC as year 0 and 44 BC as -43, prefixes a `+` to five-digit years, and cannot represent MySQL's `0000-00-00` at all.
- An **integer** needs `BigInt`: an unsigned 64-bit column reaches past what a signed `Long` holds.
- A **float** carries its width: reading a four-byte column at the wider type turns 0.1 into 0.10000000149011612.

The same question applies to error types, metadata vocabularies, and anything else neutral: is this a union of what the engines have, or one engine's shape imposed on the rest?

### Where the type is WIDER than a column: stored exactly, or refused

The rule above is about a neutral type too narrow for the engines. The opposite happens too, and it has a different answer. `Double` admits NaN and the infinities, `String` admits U+0000, `LocalDate` admits year 10000, `Duration` admits a thousand hours, and for each there is an engine whose column cannot hold it.

Which values a column holds is **not conformable**: no driver change makes a MySQL `DOUBLE` hold NaN or a PostgreSQL `text` hold a NUL byte. Do not narrow the driver to the intersection to make the engines match, either; refusing on one engine what the other stores perfectly well is the failure mode the union rule above exists to prevent.

What IS conformable is the one answer no engine may give:

> **A value is stored exactly as it was given, or it is refused. It is never stored as a DIFFERENT value.**

That property is universal, needs no per-engine branch, and is what the domain leaves in the battery assert. A refusal is recoverable and a stored value is correct; a silent substitution is neither, and it is the only one of the three that nothing downstream can detect, because the row is wrong for every later reader with nothing raised to say so.

One engine failed this, on a value nobody would have guessed: a `Duration` past its `TIME` column's span was replaced by the column's own ceiling, `838:59:59`, and the write reported as successful. **The guard for it existed and was aimed at the wrong bound**, the four-byte day count the binary wire struct carries, which no `Duration` reaching a real column could cross, while the comment beside it stated the real range. A guard has to be on the bound the VALUE crosses, not on the bound the encoding could express.

### A client-side refusal is a typed failure, never a throw that escapes

The encoders refuse by throwing, because the `SqlCodec.Writer` methods they implement return `Unit` and have nowhere to put an effect. That throw has to be converted where the writer is driven, and for a long time it was not, on either backend.

The symptom is worth recognising because it is invisible in a type signature: the method's row says `Abort[SqlException]`, the caller writes exactly the handler that row asks for, and the failure arrives as a **panic** and goes straight past it. Every client-side refusal reached callers that way, including the one guard of this kind that already existed. When you add a guard in an encoder, check that a caller `Abort.run`-ing the statement actually sees a `Result.Failure` and not a `Result.Panic`.

## The driver owns session-dependent state

Anything whose meaning depends on state the caller did not set is the driver's to pin or normalize, never to pass through. Otherwise the same stored value means different things on two connections to the same database, and no amount of care downstream recovers it.

The settings that chose a value's spelling are the worked example, and none of them is reported to the connection:

| setting | changes |
|---|---|
| `extra_float_digits` (PostgreSQL) | whether 1e23 arrives as `1e+23` or `9.999999999999999e+22` |
| `TimeZone` (PostgreSQL) | which offset a `timestamptz` carries |
| `DateStyle` (PostgreSQL) | the field order of a date |
| `bytea_output` (PostgreSQL) | which of two spellings a byte string takes |
| `IntervalStyle` (PostgreSQL) | which of four spellings an interval takes |
| `time_zone` (MySQL) | which wall-clock time a `TIMESTAMP` converts to |
| the default transaction isolation level (both) | what a transaction that names no level actually means |

The last row is the one that is not about spelling, and it is the widest: the engines default to DIFFERENT isolation levels, so a transaction naming no level read a row twice and saw the same value on one deployment and a changed value on the other, with nothing in the source saying which. Both engines implement all four levels, so this was never a capability difference. `READ COMMITTED` is now pinned at connect on both, as a startup parameter on one engine and folded into the other's existing pin statement so it still costs one round trip. That level rather than the stricter one because it is what a caller who names nothing means, and because the stricter level is snapshot isolation on one engine, which can abort a transaction with a serialization failure an unnamed transaction has no reason to expect. **The two pins must name the same level or the pin achieves nothing**, which is why the conformance leaf pins the answer rather than only comparing the engines.

Hence the rule that a text-protocol value is **parsed and re-rendered** rather than handed back. It looks redundant and is not: parsing is what makes the answer independent of how the connection happens to be configured. When you meet a new setting of this kind, the options are to pin it at connect or to normalize what it produced, and doing neither is the bug.

## What the driver cannot fix: the caller's own DDL

Some behavior is decided by how a column was declared, and kyo-sql generates no DDL. Those are real divergences that no driver change reaches, and the honest handling is to say so rather than let the conformance suite imply they are solved.

Two were measured, and both are silent:

- **String comparison follows the column's collation.** One engine's default is case AND accent insensitive, so `where(_.name == "alice")` matches a stored `Alice`, `resume` equals `résumé`, and a unique index rejects the pair as duplicates. The other engine matches none of that. Nothing errors; the query returns different rows.
- **An unqualified temporal column has a different default precision per engine**, six digits against zero, so the same declaration keeps microseconds on one and rounds them away on the other.

A third, measured later and also silent: **an identifier past 63 bytes is truncated by one engine and rejected by the other.** Truncation is the dangerous half, because two names differing only past the 63rd byte become one table, announced in a notice nobody reads. It is caller-owned for the same reason as the other two: the name a table is created under is the caller's own `CREATE TABLE` text. **63 bytes is the portable ceiling**, and a leaf in the battery pins that two names at exactly that length stay two tables on every engine.

The test descriptors pin the first two, which is what makes the battery's comparisons mean one thing. **That pins the suite, not the user.** A caller who writes their own `CREATE TABLE` gets their engine's defaults and the divergence with them.

One more is not DDL-decided but belongs with them, because it is equally unreachable: **a window `ORDER BY` under a `RANGE` frame with a numeric offset cannot carry the pinned absent placement on an engine that has no placement keyword.** That frame derives its bounds by arithmetic on the ordering value, so both engines demand exactly one ordering expression, and the flavor that lowers a placement into a second ordering term has nowhere to put it. There is no SQL it could render instead, which is what makes this a capability limit rather than a choice.

Measured, and the obvious guess about its blast radius is wrong. Rows with a present ordering key agree exactly, because the aggregates ignore absent values and so cannot see where the absent rows sit. What differs is the absent row's OWN window value: where it sorts last its frame holds every earlier row, and where it sorts first its frame holds only itself. A placement the caller named EXPLICITLY is refused rather than dropped, because silently ignoring an instruction is worse than not rendering; the unnamed default is the carve-out, gated in the battery on `windowRangeOffsetHonoursAbsentPlacement`.

So when a behavior turns out to be schema-decided: pin it in the descriptor so the battery is honest, and document it for callers. Do not describe it as fixed, and do not let a conformance leaf passing on a pinned fixture stand in for a guarantee the driver does not make.

## Testing

- **Compile-green is not runtime-green.** A change to codec, bind, decode, render, or protocol behavior is unvalidated until it has round-tripped through real servers. Run the container suites and read the result; see the root guide's rule on container-backed tests.
- **Assert pinned expectations, not the server's answer read back.** Asserting against the server makes the test agree with whatever the driver currently does, which is the thing under test. Pin the value *and* assert cross-engine and cross-protocol agreement, so a drift on every path at once still fails.
- **Choose values where things disagreed**, not where they happened to agree. The old rendering battery passed for years on values Java and the engines spelled identically.
- **Shared tests or it did not happen.** Anything in `shared/src/main` needs coverage in `shared/src/test` that runs on JVM, JS, and Native. A rendering that used `Float.toString` shipped answering `12345.599609375` on Scala.js because its only coverage was a JVM-only container suite.
- **Reproduce before fixing.** A per-engine bug gets a failing test on the engine that shows it, and the fix is judged by whether every engine still agrees afterwards.

## A test that branches per engine is a claim, and the claim is usually wrong

A leaf that expects one answer from one engine and a different answer from another is asserting that the
divergence is forced. That is a strong claim about both engines, it is rarely checked, and it is the most
comfortable place for a real bug to live, because the test is green and reads as thorough.

The test that pinned a multi-row insert's generated key branched on `supportsReturning` and said the engines
"disagree here by protocol, not by choice". The protocol difference was real: `RETURNING` yields every key
while an OK packet carries one field defined as the first row's id. But it constrains the answer in **one
direction only**, since the engine holding every key can report the first and the engine holding only the
first cannot report the last. The divergence was a choice nobody had made, and one written insert answered
`n` on one backend and `n + 2` on the other for years.

So when a branch is unavoidable, state which engine **cannot** do the other's answer, and check that it is
true. A capability difference has a direction: one engine lacks something. If both can produce a given answer
and simply produce different ones, that is a driver bug wearing a branch as a disguise. Related failures to
watch for, all found here: a fixture that spelled a column type differently per engine and so hid a codec
question, a skip whose stated reason was false, and a branch that was true of one column and exempted nine.

## A known divergence needs a leaf that spans both engines

A battery built only on `forEachBackend` cannot state a divergence. It gives each backend its own leaf, so a
behavior one engine has and the other lacks is a green leaf beside a red one, and no single node's outcome
says *these disagree*. `pendingUntilFixed` makes that concrete: it reports pending while its body fails and
**fails once its body passes**, which is what stops a marker outliving the defect, so applied per backend it
fails immediately on whichever engine already behaves.

`SqlBackendTest.agreeAcrossBackends` is the shape for it. The body answers a **string describing what it
observed**, a failing body answers the name of the class it failed with, and the leaf asserts the answers
match. A divergence is then one leaf naming both answers, and it can carry `pendingUntilFixed` honestly: it
goes green exactly when the engines converge, whichever way they converge. Fewer than two backends fails
rather than passing vacuously.

Three states, three shapes, and picking the wrong one loses the finding:

| what is true | shape | why |
|---|---|---|
| each engine must do this | `forEachBackend` | the claim is per engine; a failure names which one |
| the engines must agree, and they do not yet | `agreeAcrossBackends` + `pendingUntilFixed` | only a leaf spanning both can fail on disagreement alone |
| every engine is wrong the same way | `forEachBackend(pendingUntilFixed = ...)` | agreement is satisfied by two engines both wrong; the decorator must be on each generated leaf, since the enclosing group does not pass it down |

**Pin the answer whenever one is known.** `agreeAcrossBackends(expected = Present("..."))` asserts what they
answered, not merely that they matched. Agreement alone is satisfied by two engines that are both wrong in the
same way, and it cannot notice the engines moving together to a new answer. Leave `expected` absent only for a
leaf whose finding is an open divergence, where there is no agreed answer yet to pin.

**A divergence leaf is written to fail, and its value is in failing for the measured reason.** Write the
observation so the two answers cannot coincide by accident, and put enough in the answer string to diagnose
from the failure line alone: a count that could have come from an empty table, or an outcome that collapses
"refused" and "silently did nothing" into one word, reports the wrong finding. Whether an engine refused a
writer belongs in the answer beside the value, because it is the half a caller can act on.

**The answer string is a comparison, so anything it leaks becomes a divergence.** It is rendered text, and
rendering carries more than the value: a `BigDecimal` prints its scale, so a quotient that is 2.5 on both
engines reads as `2.5000000000000000` against `2.5000` and a leaf reports a truncation bug that is not there,
while Scala's own `BigDecimal` equality compares by value and sees no difference at all. Normalize anything
whose spelling is the engine's choice rather than the answer, and when a leaf reports a divergence, confirm a
caller could observe it before writing it down.

## Write conformance bodies through the typed API, and cover raw SQL as its own surface

A conformance body written as SQL text tests the driver's runtime and **skips the dialect entirely**. SQL generation is itself a place two engines diverge, so a hand-written string dodges half of what conformance means and the leaf passes on a statement no renderer ever produced.

**The typed API is the default lane.** Prefer `Sql.insert[T].values(...)`, `Sql.update[T].set(_.f := v).where(...)`, `Sql.from[T]("t").where(...).select(...)` over the text equivalent, and reach for the raw form only where no typed surface exists. Two things follow for free:

- **Rendering is under test alongside behavior.** A dialect that lowers a comparison, a `SET` clause, or a null placement wrongly fails the leaf, instead of passing because the test hand-wrote SQL the renderer would never have emitted.
- **The body names no engine by construction.** Portability stops depending on the author remembering to keep engine words out.

DDL is the standing exception, since there is no typed DDL. Spell its column types through the descriptor (`columnType`, `textColumnType`, `autoIncrementPrimaryKey`) so the body still names no engine, and quote identifiers through `quoteIdent`: a name reserved on one engine and ordinary on another kills a leaf in setup before it asserts anything, which is how a `RANK` fixture died in `CREATE TABLE` on one engine only.

**Raw SQL is a public surface and needs its own coverage, with a different contract.** `executeRaw` and `query` are how a caller writes their own SQL, and what they get back diverges in ways the typed lane cannot reach: result-column naming (one engine folds an unquoted alias, the other preserves it), identifier folding, metadata for computed columns, multi-statement handling. Cover those deliberately, and hold them to the narrower contract: **the SQL text is the caller's, what the driver does with the results is ours.** A leaf that pins how an engine PARSES the caller's text is codifying a rule kyo-sql does not own; a leaf that pins how a row comes back is conformance.

So the two lanes ask different questions. Typed: *does one written expression mean the same thing everywhere, through rendering and execution both?* Raw: *does one result come back the same way everywhere, whatever text produced it?*

- **A render assertion cannot see whether its SQL runs.** Comparing rendered text is worth having, but it is blind to a statement the server refuses: fifteen window render assertions passed while the SQL they pinned was rejected outright, because the one breaking combination was the one the file did not contain. Anything whose correctness depends on the server accepting it needs a leaf that executes and checks returned values.

## Pre-submission checklist (kyo-sql)

- [ ] Anything a caller observes through a neutral type behaves the same on every engine
- [ ] Engine-specific work is reached through an engine type, so a caller opts into it visibly
- [ ] Nothing left the shared battery without answering "can the other engine do this at all", with the answer written down
- [ ] No engine name in a shared conformance body: no `id` comparison, no DDL literal, no type-name string
- [ ] Any new divergence is a declared capability whose scaladoc justifies it as a capability difference
- [ ] A new neutral type spans the union of the engines' domains rather than one engine's shape
- [ ] Any session-dependent state the change touches is pinned or normalized by the driver
- [ ] Tests for shared code are in `shared/src/test` and pass on JVM, JS, and Native
- [ ] The container conformance suites were run against real servers, and the result was read
