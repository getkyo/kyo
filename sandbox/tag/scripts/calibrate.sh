#!/usr/bin/env bash
# Proves the harness detects known bugs before the harness is trusted.
#
# Expected, recorded 2026-08-26 on Scala 3.8.4:
#   branch macro (core/.../TagMacro.scala as committed):  1 red  (S2)
#   pre-branch macro (mutants/prebranch-TagMacro.scala):  3 red  (S2, S6, genuine Tag[Long] in scope)
# The pre-branch run needs -Dwerror=false: that macro trips -Xcheck-macros position warnings.
set -euo pipefail
cd "$(dirname "$0")/.."
macro=core/src/main/scala/kyo/internal/TagMacro.scala
cp "$macro" mutants/.current.bak
trap 'cp mutants/.current.bak "$macro"; rm -f mutants/.current.bak' EXIT
echo "== pre-branch macro"
cp mutants/prebranch-TagMacro.scala "$macro"
sbt -Dwerror=false -batch clean tests/test 2>&1 | grep -E "==> X|Passed|Failed" | cut -c1-160 || true
cp mutants/.current.bak "$macro"
echo "== current macro"
sbt -batch clean tests/test 2>&1 | grep -E "==> X|Passed|Failed" | cut -c1-160 || true
