# Kyo.scala: minimal diff against origin/main

File: `kyo-kernel/shared/src/main/scala/kyo/Kyo.scala`
Reference: `origin/main` (bd7f20b146)

## Diff line counts

`git --no-pager diff --numstat origin/main -- kyo-kernel/shared/src/main/scala/kyo/Kyo.scala`

| | added | removed |
|---|---|---|
| before | 285 | 438 |
| after | 177 | 225 |

The file is now main's text with exactly one transformation applied: `Safepoint ?=> ` deleted from
every combinator's function parameter (112 sites), `Safepoint` deleted from every `using` clause
(68 single-line `(using Frame, Safepoint)` sites and 50 multi-line `Frame,` / `Safepoint` sites),
the now-dead `Safepoint` import dropped, and the object-level `// Diverges from main:` comment kept.
Every method body, every scaladoc comment, every section separator, the `// for kyo-direct` marker,
member order, type-parameter names, value-parameter names and line breaking are main's, byte for byte.

A token-level comparison against the pre-edit branch file (comments stripped, whitespace collapsed)
shows only two classes of difference: the import block, and `Frame):` split into `Frame` + `):` where
main's multi-line `using` layout was restored. No token of code was added, removed or reordered, so
the branch's semantics are unchanged.

## What was reverted to main's form

- **Import block.** The branch had `import kyo.Chunk`, `kyo.Frame`, `kyo.Maybe`, `kyo.Maybe.Absent`,
  `kyo.Maybe.Present`, `kyo.kernel.<`. All six are redundant inside `package kyo`: `Chunk`, `Frame`,
  `Maybe` are package members, `Absent`/`Present` come from the package-level `export Maybe.Absent` /
  `export Maybe.Present` in `kyo-data/shared/src/main/scala/kyo/Maybe.scala`, and `<` from
  `type <[+A, -S] = kernel.<[A, S]` in `kyo-kernel/shared/src/main/scala/kyo/kernel.scala`. Main's
  `import kernel.Loop` is restored; the branch's package-level `val Loop = kernel.Loop` is the same
  object, and the explicit import wins on precedence, so it resolves exactly as on main.
- **Section separator comments** for Generic, List, Seq, Chunk, Set and Map (24 lines).
- **The `// for kyo-direct` marker** above the generic `shiftedWhile`.
- **Scaladoc.** The branch had large-scale doc drift: the `Maybe`-returning `when` carried a copy of
  the two-argument `when` doc; six `zip` overloads said "Zips two/six/eight effects" instead of
  three/four/five/seven/nine/ten; the whole Seq, Chunk and Set sections said "`List`" where main says
  "`Seq`", "`Chunk`", "`Set`", with the matching "lists of" / "List containing" wording; and in the Map
  section the three `@targetName` overloads that return a `Chunk` documented their result as a `Map`.
  All of it is main's text again.
- **Line breaking of the `using` clause.** Where main spreads `(using` / `Frame,` / `Safepoint` / `)`
  over four lines, the file now keeps that layout with the `Safepoint` line removed, instead of the
  branch's collapsed single line. `newlines.source = keep` in `.scalafmt.conf` preserves both forms,
  and main's keeps the diff readable as "one line deleted".

## Remaining hunks

118 hunks, all attributable to the one design item: **the combinators take no `Safepoint ?=>`
function context and carry no `Safepoint` evidence, because the evaluator polls the stack-depth
budget itself.** The object-level comment at lines 14-16 names it; per the brief it is not repeated
per member.

Categories: (a) design divergence, covered by the object-level comment; (b) doc text updated because
a member's meaning changed; (c) a change forced by (a). No hunk is category (b): no member's meaning
changed, so no scaladoc was rewritten.

| line range (current file) | what it is | category |
|---|---|---|
| 3-6 | import block: the `import kyo.kernel.internal.Safepoint` line is dropped, nothing in the file names Safepoint any more | c |
| 14-16 | object-level `// Diverges from main:` comment covering the Safepoint signature change | a |
| 203-204 | `foreach` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 219-221 | `foreachConcat` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 236-237 | `foreachIndexed` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 252-253 | `foreachDiscard` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 267-268 | `filter` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 285-286 | `foldLeft` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 303-304 | `collect` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 318 | `collectAll` (generic CC): `Safepoint` dropped from the using clause | a |
| 330 | `collectAllDiscard` (generic CC): `Safepoint` dropped from the using clause | a |
| 343-344 | `findFirst` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 358-359 | `takeWhile` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 376-377 | `span` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 392-400 | `dropWhile` (generic CC): `Safepoint ?=>` dropped from both function parameters, `Safepoint` dropped from the using clause | a |
| 410 | `partitionMap` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause (the `def` line above is unchanged because the type-parameter list is split over lines) | a |
| 424-432 | `scanLeft` (generic CC): `Safepoint ?=>` dropped from both function parameters, `Safepoint` dropped from the using clause | a |
| 442 | `groupMap` (generic CC): `Safepoint ?=>` dropped from both function parameters, `Safepoint` dropped from the using clause (the `def` line above is unchanged because the type-parameter list is split over lines) | a |
| 456 | `fill`: `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 465-468 | `shiftedWhile` (generic CC): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 485 | `foreach` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 508-509 | `foreachConcat` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 531 | `foreachIndexed` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 551 | `foreachDiscard` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 572 | `filter` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 599 | `foldLeft` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 622 | `collect` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 645 | `collectAll` (List): `Safepoint` dropped from the using clause | a |
| 664 | `collectAllDiscard` (List): `Safepoint` dropped from the using clause | a |
| 684 | `findFirst` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 707 | `takeWhile` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 729 | `span` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 755 | `dropWhile` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 779-780 | `partition` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 809-810 | `partitionMap` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 837-838 | `scanLeft` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 862-863 | `groupBy` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 894-895 | `groupMap` (List): `Safepoint ?=>` dropped from both function parameters, `Safepoint` dropped from the using clause | a |
| 943-946 | `shiftedWhile` (List): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 974 | `foreach` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 987-988 | `foreachConcat` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1002 | `foreachIndexed` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1015 | `foreachDiscard` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1028 | `filter` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1043 | `foldLeft` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1059 | `collect` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1070 | `collectAll` (Seq): `Safepoint` dropped from the using clause | a |
| 1081 | `collectAllDiscard` (Seq): `Safepoint` dropped from the using clause | a |
| 1094 | `findFirst` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1107 | `takeWhile` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1120 | `span` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1133 | `dropWhile` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1148-1149 | `partition` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1165-1166 | `partitionMap` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1180-1181 | `scanLeft` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1195-1196 | `groupBy` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1212-1213 | `groupMap` (Seq): `Safepoint ?=>` dropped from both function parameters, `Safepoint` dropped from the using clause | a |
| 1239-1242 | `shiftedWhile` (Seq): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1259 | `foreach` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1284-1285 | `foreachConcat` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1307 | `foreachIndexed` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1332 | `foreachDiscard` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1354 | `filter` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1385 | `foldLeft` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1410 | `collect` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1437 | `collectAll` (Chunk): `Safepoint` dropped from the using clause | a |
| 1459 | `collectAllDiscard` (Chunk): `Safepoint` dropped from the using clause | a |
| 1481 | `findFirst` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1506 | `takeWhile` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1535 | `span` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1564 | `dropWhile` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1594-1595 | `partition` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1627-1628 | `partitionMap` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1658-1659 | `scanLeft` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1683-1684 | `groupBy` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1717-1718 | `groupMap` (Chunk): `Safepoint ?=>` dropped from both function parameters, `Safepoint` dropped from the using clause | a |
| 1766-1769 | `shiftedWhile` (Chunk): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1799 | `foreach` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1820-1821 | `foreachConcat` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1843 | `foreachIndexed` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1864 | `foreachDiscard` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1883 | `filter` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1907 | `foldLeft` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1930 | `collect` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 1950 | `collectAll` (Set): `Safepoint` dropped from the using clause | a |
| 1969 | `collectAllDiscard` (Set): `Safepoint` dropped from the using clause | a |
| 1989 | `findFirst` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2011 | `takeWhile` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2033 | `span` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2055 | `dropWhile` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2079-2080 | `partition` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2105-2106 | `partitionMap` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2129-2130 | `scanLeft` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2152-2153 | `groupBy` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2183-2184 | `groupMap` (Set): `Safepoint ?=>` dropped from both function parameters, `Safepoint` dropped from the using clause | a |
| 2225-2228 | `shiftedWhile` (Set): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2254-2255 | `foreach` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2278-2279 | `foreach` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2301-2302 | `foreachConcat` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2325-2326 | `foreachConcat` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2348 | `foreachDiscard` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2367 | `filter` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2389 | `filterKeys` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2413 | `foldLeft` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2436-2437 | `collect` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2464-2465 | `collect` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2486 | `collectAll` (Map): `Safepoint` dropped from the using clause | a |
| 2505 | `collectAllDiscard` (Map): `Safepoint` dropped from the using clause | a |
| 2525 | `findFirst` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2547 | `takeWhile` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2569-2570 | `span` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2593 | `dropWhile` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2617-2618 | `partition` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2643-2644 | `partitionMap` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2667-2668 | `scanLeft` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2690-2691 | `groupBy` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |
| 2721-2723 | `groupMap` (Map): `Safepoint ?=>` dropped from both function parameters, `Safepoint` dropped from the using clause | a |
| 2764-2767 | `shiftedWhile` (Map): `Safepoint ?=>` dropped from the function parameter, `Safepoint` dropped from the using clause | a |

## Nothing left unresolved

Every hunk above is the Safepoint signature change or a direct consequence of it. No hunk remains
that is whitespace, blank lines, import style or order, member order, type-parameter names,
value-parameter names, local variable names, private helper names, or doc text.

## Renames skipped because they cross files

None. No identifier in this file was renamed, so nothing had to be propagated elsewhere.

## Cases where main's form was kept even though it looks worse

- `import scala.annotation.tailrec` is unused in main's `Kyo.scala` and is still unused here. Kept.
- Main's generic `partition`, `partitionMap`, `groupBy` and `groupMap` carry no scaladoc at all while
  every neighbour does. Kept undocumented.
- Main's `List` overload of `collectAll` documents its return as "a Chunk of results". Kept as is.
- Main's `Map` overload of `groupMap` documents `@param source` as "The input `Set`". Kept as is.
- Main's `Set` overload of `takeWhile` documents its return as "a Chunk of taken elements". Kept as is.
- Main splits the type-parameter list of the generic `partitionMap` and `groupMap` across four lines
  (`CC[+X] <: Iterable[X] & IterableOps[` / `X,` / `CC,` / `CC[X]`). Kept.
