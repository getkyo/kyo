#!/usr/bin/env bash
# readme-check.sh — mechanical red-flag detector for the /readme skill
#
# Usage: readme-check.sh <path-to-README.md> [--chunks] [--intro-order] [--callouts] [--with-doctest]
#
# Default: emits a structured findings report covering every red-flag category
# from Stage 7 of the /readme skill. Each finding includes the category, line
# numbers, and the matched content.
#
# --chunks: emit chunk boundaries for Stage 2a, one per output line, formatted
#   "STARTLINE-ENDLINE TYPE FIRST50CHARS".
# --intro-order: emit the introduction-order list, one identifier per line,
#   with the line number where it first appears in a backticked context.
# --callouts: scan source files alongside the README for callout signals
#   (Note:, Unlike X, Caution:, Be aware, WARNING in scaladoc; API names with
#   qualifiers like Unsafe/Lazy/Default). Emit candidate callouts with their
#   source location. The agent decides which surface as README callouts.
# --with-doctest: after the structural findings report, detect whether the
#   module is wired to kyo-doctest (via build.sbt) and, if so, run
#   sbt <module>/doctest. A non-zero sbt exit increments STRONG and emits a
#   [STRONG-doctest] finding. If the module is not wired, the flag is a no-op
#   (prints a skip notice and exits with the structural exit code).
#
# Exit code: 0 if no STRONG findings; 1 if any STRONG findings (Prerequisites
# heading, Audience disclaimer, em-dashes, doctest failure, etc.).

if [ $# -lt 1 ]; then
  echo "Usage: $0 <path-to-README.md> [--chunks] [--intro-order] [--callouts] [--with-doctest]" >&2
  exit 2
fi

FILE="$1"
MODE="report"
WITH_DOCTEST=0
shift || true
for arg in "$@"; do
  case "$arg" in
    --chunks)       MODE="chunks" ;;
    --intro-order)  MODE="intro-order" ;;
    --callouts)     MODE="callouts" ;;
    --with-doctest) WITH_DOCTEST=1 ;;
    *) echo "Unknown flag: $arg" >&2; exit 2 ;;
  esac
done

if [ ! -f "$FILE" ]; then
  echo "File not found: $FILE" >&2
  exit 2
fi

STRONG=0

emit() { printf "  [%s] L%s  %s\n" "$1" "$2" "$3"; }
emit_strong() { STRONG=$((STRONG + 1)); emit "$@"; }

# ----- Chunks mode -----
if [ "$MODE" = "chunks" ]; then
  awk '
    BEGIN { chunk_start = 1; in_setup_html = 0; in_code = 0 }
    /^<!--/ { in_setup_html = 1 }
    /-->/ {
      if (in_setup_html) {
        printf "%d-%d setup-html  (HTML-hidden setup block)\n", chunk_start, NR
        chunk_start = NR + 1
        in_setup_html = 0
        next
      }
    }
    in_setup_html { next }
    /^```/ { in_code = !in_code; next }
    in_code { next }
    /^# / && NR > chunk_start {
      if (NR - 1 >= chunk_start) {
        printf "%d-%d prior  ...\n", chunk_start, NR - 1
      }
      printf "%d-%d title  %s\n", NR, NR, substr($0, 1, 60)
      chunk_start = NR + 1
      next
    }
    /^## / {
      if (NR - 1 >= chunk_start) {
        printf "%d-%d chunk  ...\n", chunk_start, NR - 1
      }
      printf "%d-%d h2  %s\n", NR, NR, substr($0, 1, 60)
      chunk_start = NR + 1
      next
    }
    /^### / {
      if (NR - 1 >= chunk_start) {
        printf "%d-%d chunk  ...\n", chunk_start, NR - 1
      }
      printf "%d-%d h3  %s\n", NR, NR, substr($0, 1, 60)
      chunk_start = NR + 1
      next
    }
    END {
      if (NR >= chunk_start) {
        printf "%d-%d chunk  ...\n", chunk_start, NR
      }
    }
  ' "$FILE"
  exit 0
fi

# ----- Callouts mode -----
if [ "$MODE" = "callouts" ]; then
  # Find source files in the module sibling to the README
  module_dir=$(dirname "$FILE")
  search_paths=(
    "$module_dir/shared/src/main/scala"
    "$module_dir/jvm/src/main/scala"
    "$module_dir/js/src/main/scala"
    "$module_dir/native/src/main/scala"
    "$module_dir/src/main/scala"
  )

  echo "== callout signals in source under $module_dir =="
  echo

  any_path_exists=0
  for p in "${search_paths[@]}"; do
    [ -d "$p" ] || continue
    any_path_exists=1

    echo "-- $p --"

    # Scaladoc signals: lines in /** ... */ blocks that contain key phrases
    grep -rnEH '(Note:|Unlike [A-Z]|Caution:|Be aware|WARNING|Common mistake|Surprising|Counter-intuitive)' "$p" 2>/dev/null | head -50 || true
    echo

    # API qualifiers in declarations
    echo "  API names with qualifiers (Unsafe/Lazy/Default/Async/Forced) suggest a non-qualified default with different behavior:"
    grep -rnEH '(def|val|object|class|trait|case class)[[:space:]]+(Unsafe|Lazy|Default|Async|Forced)[A-Z][a-zA-Z]*' "$p" 2>/dev/null | head -20 || true
    echo

  done

  if [ "$any_path_exists" = "0" ]; then
    echo "  (no source directories found alongside $FILE)"
    echo "  Expected one of: shared/src/main/scala, jvm/src/main/scala, src/main/scala under $module_dir"
  fi

  echo
  echo "These are CANDIDATE callouts. For each, decide:"
  echo "  - Does a naive reader's expectation differ from the actual behavior here?"
  echo "  - Is the surprise already obvious from the API name + type?"
  echo "  - Where in the README should the callout land (inline at first use)?"
  echo "  - Or is this a design-rationale paragraph that does NOT belong in the README?"
  exit 0
fi

# ----- Intro-order mode -----
if [ "$MODE" = "intro-order" ]; then
  awk '
    {
      s = $0
      while (match(s, /`[A-Za-z][A-Za-z0-9_.]*`/)) {
        ident = substr(s, RSTART + 1, RLENGTH - 2)
        if (!(ident in seen)) {
          seen[ident] = NR
        }
        s = substr(s, RSTART + RLENGTH)
      }
    }
    END {
      for (ident in seen) printf "L%d  %s\n", seen[ident], ident
    }
  ' "$FILE" | sort -t L -k 2 -n
  exit 0
fi

# ----- Default: structured findings -----

echo "== red-flag detector: $FILE =="
echo

run_grep() {
  # run_grep CATEGORY STRENGTH PATTERN
  local cat="$1" strength="$2" pat="$3"
  local matches
  matches=$(grep -nE "$pat" "$FILE" 2>/dev/null || true)
  if [ -z "$matches" ]; then
    echo "  (none)"
    return
  fi
  while IFS=: read -r ln rest; do
    [ -z "$ln" ] && continue
    if [ "$strength" = "strong" ]; then
      emit_strong "$cat" "$ln" "$rest"
    else
      emit "$cat" "$ln" "$rest"
    fi
  done <<< "$matches"
}

echo "-- banned headings (Prerequisites/Background/Capabilities) --"
run_grep "STRONG-heading" strong '^#+ (Prerequisites|Background|Prerequisite|Capabilities)\b'
echo

echo "-- audience disclaimer prefix --"
run_grep "STRONG-audience" strong '^\*\*Audience:'
echo

echo "-- first-time-kyo preamble --"
run_grep "STRONG-preamble" strong 'First-time Kyo readers'
echo

echo "-- 'Why not' / 'Comparison with' opener patterns --"
run_grep "FLAG-defense" flag '^\*\*Why not |^## Comparison with'
echo

echo "-- competitor suggestions ('Reach for X', 'Pick X if') --"
run_grep "FLAG-competitor" flag 'Reach for [A-Z][a-zA-Z]+|Pick [A-Z][a-zA-Z]+ if '
echo

echo "-- em-dashes --"
em_count=$(grep -c '—' "$FILE" 2>/dev/null | tr -d '[:space:]')
em_count=${em_count:-0}
if [ "$em_count" -gt 0 ]; then
  while IFS=: read -r ln rest; do
    [ -z "$ln" ] && continue
    emit_strong "STRONG-emdash" "$ln" "$rest"
  done <<< "$(grep -n '—' "$FILE" | head -10)"
  [ "$em_count" -gt 10 ] && echo "  ... and $((em_count - 10)) more"
else
  echo "  (none)"
fi
echo

echo "-- build-process noise (CrossType, mimaCheck, crossProject, etc.) --"
run_grep "FLAG-build" flag 'CrossType\.|mimaCheck|mimaPreviousArtifacts|mimaFailOnProblem|crossProject\(|projectMatrix|binary compatibility is not enforced|-Xsource:3'
echo

echo "-- kyo basics definitional re-explanation (pending type / A < S / effect row) --"
run_grep "FLAG-funnel" flag 'pending type|A Kyo computation has type|effect row.*intersection|effect row.*union'
echo

echo "-- capability list density --"
cap_line=$(grep -nE '^## Capabilities' "$FILE" 2>/dev/null | head -1 | cut -d: -f1 || true)
if [ -n "$cap_line" ]; then
  next_h=$(awk -v start="$cap_line" 'NR > start && /^## / { print NR; exit }' "$FILE")
  bullets=$(awk -v start="$cap_line" -v end="${next_h:-99999}" 'NR > start && NR < end && /^- / { c++ } END { print c+0 }' "$FILE")
  if [ "$bullets" -gt 5 ]; then
    emit "FLAG-capability-list" "$cap_line" "## Capabilities with $bullets bullets (threshold 5); apply value-per-item test"
  else
    echo "  (## Capabilities has $bullets bullets, under threshold)"
  fi
else
  echo "  (no ## Capabilities heading; OK)"
fi
echo

echo "-- first-code-line latency --"
first_scala=$(awk '
  /^<!--/ { in_html = 1 }
  /-->/ { in_html = 0; next }
  in_html { next }
  /^```scala/ { print NR; exit }
' "$FILE")
if [ -n "$first_scala" ]; then
  if [ "$first_scala" -gt 40 ]; then
    emit "FLAG-code-latency" "$first_scala" "first visible scala block at L$first_scala (threshold 40); lift a small example earlier"
  else
    echo "  (first visible scala at L$first_scala, under threshold 40)"
  fi
else
  echo "  (no scala code block found)"
fi
echo

echo "-- semicolon overuse (em-dash substitution smell) --"
semi=$(grep -c '; ' "$FILE" 2>/dev/null | tr -d '[:space:]')
semi=${semi:-0}
total=$(wc -l < "$FILE" | tr -d '[:space:]')
total=${total:-0}
if [ "$total" -gt 0 ]; then
  ratio=$((semi * 100 / total))
  if [ "$ratio" -gt 30 ]; then
    echo "  FLAG: $semi '; ' occurrences in $total lines (${ratio}%); review for mechanical em-dash substitution"
  else
    echo "  ($semi '; ' in $total lines, ${ratio}%)"
  fi
fi
echo

echo "-- heading shape (flat naming vs content-specific) --"
flat=$(grep -cE '^## [A-Z][a-zA-Z]+ *$' "$FILE" 2>/dev/null | tr -d '[:space:]')
flat=${flat:-0}
total_h2=$(grep -cE '^## ' "$FILE" 2>/dev/null | tr -d '[:space:]')
total_h2=${total_h2:-0}
if [ "$flat" -gt 0 ]; then
  echo "  ($flat of $total_h2 H2 headings are single-word like '## Client', '## Server'; consider content-specific headings)"
  grep -nE '^## [A-Z][a-zA-Z]+ *$' "$FILE" | while IFS=: read -r ln rest; do
    [ -z "$ln" ] && continue
    emit "FLAG-heading-shape" "$ln" "$rest"
  done
else
  echo "  (all H2 headings are content-specific)"
fi
echo

echo "-- mental-model spine in opening --"
# Heuristic: check the first ~12 lines after the title for prose that introduces
# a load-bearing concept by NAME with definitional language. If the opening is
# only one short paragraph followed immediately by a bullet list or Getting
# Started heading, the spine is likely missing.
title_line=$(grep -nE '^# ' "$FILE" 2>/dev/null | head -1 | cut -d: -f1)
if [ -n "$title_line" ]; then
  # Find next ## heading
  next_h2=$(awk -v start="$title_line" 'NR > start && /^## / { print NR; exit }' "$FILE")
  opening_lines=$((${next_h2:-0} - title_line))
  if [ "$opening_lines" -lt 6 ]; then
    echo "  FLAG: opening is only $opening_lines lines before first ## heading; consider whether a mental-model spine paragraph would help"
  else
    echo "  (opening is $opening_lines lines before first ## heading; presence of spine prose still requires human judgment)"
  fi
fi
echo

# Summary
echo "== summary =="
echo "STRONG findings: $STRONG"
if [ "$STRONG" -gt 0 ]; then
  echo "Exit 1: strong findings present (fix before commit)"
  structural_exit=1
else
  echo "Exit 0: no strong findings"
  structural_exit=0
fi

# ----- Optional doctest pass -----
if [ "$WITH_DOCTEST" = "1" ]; then
  module_dir=$(dirname "$FILE")
  module_name=$(basename "$module_dir")

  # Walk up from module_dir looking for build.sbt
  repo_root=""
  search_dir="$module_dir"
  while [ "$search_dir" != "/" ]; do
    if [ -f "$search_dir/build.sbt" ]; then
      repo_root="$search_dir"
      break
    fi
    search_dir=$(dirname "$search_dir")
  done

  if [ -z "$repo_root" ]; then
    echo "doctest skipped: no build.sbt found upward from $module_dir"
    exit "$structural_exit"
  fi

  # Detect whether the module is wired to kyo-doctest
  if ! grep -qE "${module_name}/doctest|enablePlugins\(KyoDoctestPlugin\).*${module_name}|${module_name}.*KyoDoctestPlugin" "$repo_root/build.sbt"; then
    echo "doctest skipped: $module_name not wired to kyo-doctest in $repo_root/build.sbt"
    exit "$structural_exit"
  fi

  echo
  echo "== running sbt ${module_name}/doctest =="
  cd "$repo_root" && sbt "${module_name}/doctest" 2>&1 | tail -30
  sbt_exit="${PIPESTATUS[0]}"

  if [ "$sbt_exit" -ne 0 ]; then
    STRONG=$((STRONG + 1))
    echo "  [STRONG-doctest] sbt ${module_name}/doctest failed (exit $sbt_exit)"
    echo "  Re-run: cd $repo_root && sbt ${module_name}/doctest"
    exit 1
  fi
fi

exit "$structural_exit"
