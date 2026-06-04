#!/usr/bin/env bash
# contributing-check.sh - mechanical checks for the /contributing skill.
#
# Usage:
#   contributing-check.sh <doc.md> [<module-dir>] [--citations]
#
# Default (structural): em/en-dashes, leftover TODO/FIXME/placeholder markers,
#   a visible pointer back to the root CONTRIBUTING.md, and at least one section.
# --citations: additionally extract every `File.ext:line(-line)` citation in the
#   doc and resolve it against <module-dir>: the file must exist (uniquely) and
#   contain the cited line. Unresolved or out-of-range citations are STRONG;
#   ambiguous (multi-match) ones are WEAK.
#
# Exit code: 0 if no STRONG findings, 1 if any STRONG, 2 on usage error.
#
# Portability: targets bash 3.2 and BSD userland (macOS). Uses perl for the
# unicode dash scan (BSD grep has no -P) and avoids mapfile.

set -u

if [ $# -lt 1 ]; then
  echo "Usage: $0 <doc.md> [<module-dir>] [--citations]" >&2
  exit 2
fi

DOC="$1"; shift || true
MODULE_DIR=""
DO_CITATIONS=0
for arg in "$@"; do
  case "$arg" in
    --citations) DO_CITATIONS=1 ;;
    *)           MODULE_DIR="$arg" ;;
  esac
done

if [ ! -f "$DOC" ]; then
  echo "[STRONG-missing-file] doc not found: $DOC"
  exit 1
fi

STRONG=0
WEAK=0
strong() { echo "[STRONG-$1] $2"; STRONG=$((STRONG + 1)); }
weak()   { echo "[WEAK-$1] $2"; WEAK=$((WEAK + 1)); }

# --- 1. em-dash / en-dash (perl: portable unicode match) ---
DASHHITS="$(perl -ne 'print "$.: $_" if /[\x{2014}\x{2013}]/' "$DOC" 2>/dev/null)"
if [ -n "$DASHHITS" ]; then
  while IFS= read -r ln; do
    [ -n "$ln" ] && strong "dash" "em/en-dash -> $ln"
  done <<EOF
$DASHHITS
EOF
fi

# --- 2. leftover TODO / placeholder markers ---
MARKHITS="$(grep -nE 'TODO|FIXME|XXX|TBD|LOREM' "$DOC" 2>/dev/null || true)"
if [ -n "$MARKHITS" ]; then
  while IFS= read -r ln; do
    [ -n "$ln" ] && strong "marker" "leftover marker -> $ln"
  done <<EOF
$MARKHITS
EOF
fi

# --- 3. defer-to-root pointer present ---
if ! grep -qiE 'root .*CONTRIBUTING|\.\./CONTRIBUTING\.md|CONTRIBUTING\.md\)' "$DOC"; then
  weak "no-root-pointer" "no visible pointer to the root CONTRIBUTING.md"
fi

# --- 4. at least one section heading ---
if ! grep -qE '^## ' "$DOC"; then
  weak "no-sections" "no '## ' section headings found"
fi

# --- 5. citation resolution (optional) ---
if [ "$DO_CITATIONS" = "1" ]; then
  if [ -z "$MODULE_DIR" ] || [ ! -d "$MODULE_DIR" ]; then
    strong "citations-no-module" "--citations needs a valid <module-dir>; got '$MODULE_DIR'"
  else
    # Repo root, for resolving root-level citations (build.sbt and friends) that
    # are valid but live outside the module dir.
    REPO_ROOT="$(cd "$MODULE_DIR" 2>/dev/null && git rev-parse --show-toplevel 2>/dev/null || true)"
    [ -z "$REPO_ROOT" ] && REPO_ROOT="$(dirname "$MODULE_DIR")"
    CITES="$(grep -oE '[A-Za-z0-9_./-]+\.(scala|sbt|java|md|json):[0-9]+(-[0-9]+)?' "$DOC" 2>/dev/null | sort -u || true)"
    while IFS= read -r cite; do
      [ -z "$cite" ] && continue
      ref="${cite%%:*}"
      lines="${cite##*:}"
      endline="${lines##*-}"
      # Resolve bare names, partial paths (a slash but not a full path, e.g.
      # internal/BrowserTab.scala), and full paths uniformly.
      base="${ref##*/}"
      if   [ -f "$MODULE_DIR/$ref" ]; then FOUND="$MODULE_DIR/$ref"
      elif [ -f "$ref" ];             then FOUND="$ref"
      else
        FOUND="$(find "$MODULE_DIR" -type d \( -name target -o -name .git -o -name node_modules \) -prune -o -type f -name "$base" -print 2>/dev/null)"
        # fall back to shallow repo-root files (build.sbt, root configs) when not in the module
        if [ -z "$FOUND" ] && [ -n "$REPO_ROOT" ]; then
          FOUND="$(find "$REPO_ROOT" -maxdepth 2 -type d \( -name target -o -name .git -o -name node_modules \) -prune -o -type f -name "$base" -print 2>/dev/null)"
        fi
        # a partial-path citation (has a slash) keeps only matches whose path ends with it
        if [ "$ref" != "$base" ] && [ -n "$FOUND" ]; then
          FOUND="$(printf '%s\n' "$FOUND" | grep -E "/${ref}\$" || true)"
        fi
      fi
      if [ -z "$FOUND" ]; then count=0; f=""
      else count="$(printf '%s\n' "$FOUND" | grep -c .)"; f="$(printf '%s\n' "$FOUND" | head -1)"; fi
      if [ "$count" -eq 0 ]; then
        strong "cite-unresolved" "$cite -> no such file under $MODULE_DIR"
      elif [ "$count" -gt 1 ]; then
        weak "cite-ambiguous" "$cite -> $count files match '$base'; line not checked"
      else
        # awk NR counts a final line that lacks a trailing newline; wc -l would undercount it
        total="$(awk 'END{print NR}' "$f")"
        if [ "$endline" -gt "$total" ] 2>/dev/null; then
          strong "cite-out-of-range" "$cite -> $f has only $total lines"
        fi
      fi
    done <<EOF
$CITES
EOF
  fi
fi

echo "---"
echo "STRONG=$STRONG WEAK=$WEAK"
[ "$STRONG" -eq 0 ]
