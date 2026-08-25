#!/usr/bin/env bash
# Ground truth for the parsers, derived from the captured artifacts by means the
# parsers do not share.
#
# The harness's QA once asserted shape ("entries parsed", nonEmpty, bytes > 0) and passed
# while the morphism verdict was inverted, the deopt count measured compiler-planted guards
# rather than runtime events, and the allocation parser discarded 109,799 of the lines in
# the file it was reading. Shape assertions cannot fail that way.
#
# So every parser is tested against an independent derivation of the same fact. This script
# is that derivation: it prints `name=value` lines the test compares its typed output to.
# Deriving rather than hardcoding is the point, since a constant copied into a test rots
# silently when the capture is replaced.
#
# Usage: oracles.sh [artifact-dir]

set -euo pipefail
dir="${1:-$(dirname "$0")/../../../../../qa-artifacts}"
logc="$dir/qa-logc.xml"
alloc="$dir/qa-alloc.txt"

[ -f "$logc" ] || { echo "no compilation log at $logc" >&2; exit 1; }

# --- compilation log: call sites -------------------------------------------------------
# A missing `receiver` attribute means the site is not a profiled virtual call, never that
# it is megamorphic. The gap between these two numbers is why: only a handful of sites in
# the whole log carry a receiver profile at all.
echo "calls_total=$(grep -o '<call ' "$logc" | wc -l | tr -d ' ')"
echo "calls_with_receiver=$(grep -o "<call [^>]*receiver='" "$logc" | wc -l | tr -d ' ')"
echo "calls_with_count=$(grep -o "<call method='[0-9]*' count='" "$logc" | wc -l | tr -d ' ')"

# --- compilation log: deoptimization ---------------------------------------------------
# `thread=` marks a real runtime deopt. `bci=` marks a guard the compiler planted while
# compiling, which is a property of the code shape and not an event that happened.
echo "traps_total=$(grep -o '<uncommon_trap' "$logc" | wc -l | tr -d ' ')"
echo "traps_runtime=$(grep -o "<uncommon_trap thread='" "$logc" | wc -l | tr -d ' ')"
# derived, never subtracted: the planted population leads with `bci=` in 512 cases and with
# `method=` in 121 more, so taking it as total-minus-runtime hides whichever form a parser misses.
echo "traps_planted=$(grep -o '<uncommon_trap [a-z_]*=' "$logc" | grep -vc "thread=" | tr -d ' ')"
echo "make_not_entrant=$(grep -o '<make_not_entrant' "$logc" | wc -l | tr -d ' ')"

# --- compilation log: method declarations ----------------------------------------------
# The unloaded form carries no `bytes`/`iicount`, so a regex demanding both silently drops
# it and leaves unresolvable `method#N` ids downstream.
echo "methods_total=$(grep -o '<method id=' "$logc" | wc -l | tr -d ' ')"
echo "methods_unloaded=$(grep -o "<method [^>]*unloaded='1'" "$logc" | wc -l | tr -d ' ')"

# --- compilation log: tasks ------------------------------------------------------------
# HotSpot omits `level` for the top tier, so an absent attribute means C2, not unknown.
# OSR tasks compile a loop already running; the JMH stub loop is one, and it is where the
# measured code actually lives, so folding them into the standard counts hides them.
echo "tasks_total=$(grep -o '<task compile_id=' "$logc" | wc -l | tr -d ' ')"
# count the tasks, not every element mentioning osr: `compile_kind='osr'` also appears on
# task_queued and nmethod elements, so the occurrence count overstates the task count.
echo "tasks_osr=$(grep -o '<task [^>]*osr_bci=' "$logc" | wc -l | tr -d ' ')"
echo "osr_mentions=$(grep -o "compile_kind='osr'" "$logc" | wc -l | tr -d ' ')"
# a task with no level attribute is C2. Both stored production runs reported 0 of these for a fork
# that performed 88, because the level was read off the whole line and a <method> element sharing
# it carries level='3'.
echo "tasks_c2=$(grep -o '<task compile_id=[^>]*>' "$logc" | grep -vc "level='" | tr -d ' ')"

# --- call sites worth distinguishing ------------------------------------------------------
# `count='-1'` is HotSpot's no-profile marker rather than a count, and bimorphic sites (carrying a
# second receiver) are the only real polymorphism evidence the log contains.
echo "calls_no_profile=$(grep -o "<call [^>]*count='-1'" "$logc" | wc -l | tr -d ' ')"
echo "calls_bimorphic=$(grep -o '<call [^>]*receiver2=' "$logc" | wc -l | tr -d ' ')"

# --- allocation ------------------------------------------------------------------------
# The flat table is an independent aggregation of the same data the stack traces carry, so
# per-method totals summed by class must reproduce it. That conservation check is what
# fails loudly when a parser truncates, rots, or meets a changed format.
if [ -f "$alloc" ]; then
    # a trace section opens with `--- <bytes> bytes (<pct>%), <n> samples`. This capture is
    # sbt console output, so every line carries an `[info] ` prefix and ANSI codes, which is
    # itself the reason to read the file async-profiler writes instead of this.
    echo "alloc_trace_sections=$(grep -cE '^\[info\] --- [0-9]+ bytes' "$alloc" || true)"
    echo "alloc_frame_lines=$(grep -c '\[ *[0-9]*\]' "$alloc" || true)"
    echo "alloc_total_samples=$(grep -oE 'Total samples *: *[0-9]+' "$alloc" | grep -oE '[0-9]+$' || true)"
    # bytes covered by the traces, against the flat table's total. The traces are truncated
    # to a fixed number of sections, so this fraction is the ceiling on what per-method
    # attribution derived from them can ever explain.
    echo "alloc_traced_bytes=$(grep -oE '^\[info\] --- [0-9]+ bytes' "$alloc" | grep -oE '[0-9]+' | paste -sd+ - | bc || true)"

    # The flat table is a complete per-class attribution: every allocated byte is accounted
    # for, by class. What it cannot say is which method allocated them. That is the whole
    # reason to read the traces, and it is also the independent total the conservation check
    # reconciles per-method sums against.
    echo "alloc_flat_classes=$(grep -cE '^\[info\] +[0-9]+ +[0-9.]+% +[0-9]+ +[a-zA-Z]' "$alloc" || true)"
    # take the bytes column positionally; extracting digits would split "50.01%" into two numbers
    echo "alloc_flat_bytes=$(awk '/^\[info\] +[0-9]+ +[0-9.]+% +[0-9]+ +[a-zA-Z]/ {s+=$2} END {print s+0}' "$alloc")"
fi

# --- inlining decisions that can affect a measured score -----------------------------------
# C1 refusals are warmup-tier decisions: C1 runs for roughly two warmup iterations and has only a
# 35-byte gate with no frequency tier, so it refuses callees C2 inlines hot. Of 1969 refusals in this
# capture 1929 are C1 and 40 are C2, and 'callee is too large' is 1166 C1 against 0 C2. Reporting the
# C1 set as a method's inlining behaviour points optimization at code the score never executes.
if command -v python3 >/dev/null; then
python3 - "$logc" <<'PY'
import re, sys
raw = open(sys.argv[1], errors='replace').read()
c2 = c1 = c2_too_large = 0
for t in re.split(r"(?=<task )", raw):
    if not t.startswith('<task '):
        continue
    head = t[:t.find('>') + 1]
    is_c2 = re.search(r"level='(\d+)'", head) is None
    for m in re.finditer(r"<inline_fail reason='([^']+)'", t):
        if is_c2:
            c2 += 1
            if 'too large' in m.group(1):
                c2_too_large += 1
        else:
            c1 += 1
print(f"refusals_c2={c2}")
print(f"refusals_c1={c1}")
print(f"refusals_c2_too_large={c2_too_large}")
PY
fi

# --- recompilation that is not tier escalation --------------------------------------------
# A method compiled at level 3 and then at level 4 was promoted, which tiered compilation does to
# every hot method. Counting those as recompilations reported 84 where 10 methods were actually
# recompiled at the same tier.
if command -v python3 >/dev/null; then
python3 - "$logc" <<'PY'
import re, sys
from collections import defaultdict
raw = open(sys.argv[1], errors='replace').read()
per = defaultdict(list)
for m in re.finditer(r"<task [^>]*>", raw):
    el = m.group(0)
    name = re.search(r"method='([^']+)'", el)
    lvl = re.search(r"level='(\d+)'", el)
    if name:
        per[name.group(1)].append(lvl.group(1) if lvl else '4')
print(f"methods_multi_task={sum(1 for v in per.values() if len(v) > 1)}")
print(f"methods_recompiled_same_tier={sum(1 for v in per.values() if len(v) != len(set(v)))}")
PY
fi
