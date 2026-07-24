#!/usr/bin/env bash
set -uo pipefail
#
# ci-reclaim-disk.sh - delete the big preinstalled runner toolchains this build never uses.
#
# Native rows write roughly 40GB of link workspace (per-module native-test trees) and GitHub
# runner images vary in starting free disk; a runner starting under ~42GB exhausts it mid-row
# and dies without uploading its log. Reclaiming the unused toolchains gives every runner
# comfortable headroom regardless of which image instance it got. Best effort throughout: a
# missing path or unavailable docker is fine, and nothing here can fail the job.

free_mb() { df -Pm / 2>/dev/null | awk 'NR==2{print $4}'; }

before=$(free_mb)
sudo rm -rf \
    /usr/local/lib/android \
    /usr/share/dotnet \
    /opt/ghc \
    /usr/local/.ghcup \
    /opt/hostedtoolcache/CodeQL \
    /usr/local/share/powershell \
    /usr/share/swift \
    2>/dev/null || true
sudo docker image prune --all --force >/dev/null 2>&1 || true
after=$(free_mb)
echo "ci-reclaim-disk: freeMB ${before:-?} -> ${after:-?} (reclaimed $((${after:-0} - ${before:-0}))MB)"
exit 0
