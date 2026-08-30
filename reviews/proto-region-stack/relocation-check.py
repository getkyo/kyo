#!/usr/bin/env python3
"""What actually changed inside the two blocks edit 5 relocates.

Most of edit 5's bulk is the baseline's own code, re-indented and re-typed. Saying so is a claim;
this prints the evidence. Each block is compared against the baseline with indentation, reflow and
the type rename normalized away, and whatever survives is a real difference.

Round 4 stopped on a version of `review.md` that said `flags.md` named five differences. It named
none, and structurally could not: a relocation with a signature change produces no flag row. This
is what replaced that sentence.

  relocation-check.py <path-to-tip-Eval.scala>

Run from the review directory; the baseline comes from git.
"""

import difflib
import os
import re
import subprocess
import sys

BASE = "31a7b4bde9"
EVAL = "kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala"
REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")


def baseline():
    return subprocess.check_output(["git", "show", "%s:%s" % (BASE, EVAL)], cwd=REPO).decode("utf-8").split("\n")


def span(lines, start, end):
    """The block between the first line equal to `start` and the first `end` at or after it."""
    i = next(n for n, ln in enumerate(lines) if ln.rstrip() == start)
    j = next(n for n, ln in enumerate(lines[i:], i) if ln.rstrip() == end)
    return lines[i:j + 1]


def normalize(lines, substitutions):
    text = "\n".join(lines)
    for old, new in substitutions:
        text = text.replace(old, new)
    # indentation and reflow are not differences
    return [re.sub(r"\s+", " ", ln).strip() for ln in text.split("\n") if ln.strip()]


def report(title, base_block, tip_block, substitutions, undone):
    a = normalize(base_block, substitutions)
    b = normalize(tip_block, [])
    diff = [ln for ln in difflib.unified_diff(a, b, lineterm="", n=0)
            if ln[:1] in "+-" and not ln.startswith(("---", "+++"))]
    print("%s, after undoing %s: %d differences" % (title, undone, len(diff)))
    for ln in diff:
        print("   " + ln)
    print()
    return len(diff)


if __name__ == "__main__":
    tip = open(sys.argv[1], encoding="utf-8").read().split("\n") if len(sys.argv) > 1 else \
        subprocess.check_output(["git", "show", "HEAD:%s" % EVAL], cwd=REPO).decode("utf-8").split("\n")
    base = baseline()

    report(
        "The absorb block",
        span(base, "                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then kyo.asInstanceOf[C < S]", "                    end if"),
        span(tip, "                        if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then kyo", "                            end match"),
        [("S2", "S3"),
         ("[IX, OX, EX, VX, A, S]", "[IX, OX, EX, VX, T, S2]"),
         ("[VX, CX, A, S]", "[VX, CX, T, S2]"),
         ("C, S] with", "C, S2] with"),
         ("[OX[VX], C, S]", "[OX[VX], C, S2]"),
         ("[VX, C, S]", "[VX, C, S2]")],
        "indentation, reflow and the type rename",
    )

    report(
        "The rebuild block",
        span(base, "                                res match", "                                end match"),
        span(tip, "                                susp match", "                                end match"),
        [("res match", "susp match"),
         ("S2", "S3"),
         ("EX & S]", "EX]"),
         ("Y, S] with", "Y, Any] with"),
         (", Y, S]:", ", Y, Any]:"),
         ("[OY[VY], Y, S]", "[OY[VY], Y, Any]"),
         ("[VX, Y, S]", "[VX, Y, Any]"),
         ("kyo.handler", "handler"),
         ("(st, ex)", "(state, ex)")],
        "indentation, the type rename, and the handler and state being read from the entry",
    )
