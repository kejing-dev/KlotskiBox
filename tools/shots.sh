#!/usr/bin/env bash
# QA state captures: tools/shots.sh OUTDIR LANG STATE... (each: reset to a clean classic game, goto, wait, capture)
set -u
OUT="$1"; LANG_="$2"; shift 2
K="$(dirname "$0")/kb.sh"
mkdir -p "$OUT"
"$K" cmd lang "$LANG_"; "$K" cmd welcome
for S in "$@"; do
  "$K" cmd hold off; "$K" cmd page play
  case "$S" in
    S11) "$K" cmd page settings ;;
    S12) "$K" cmd page rules ;;
    S13exit) "$K" cmd exitask ;;
    S10) "$K" cmd goto S02; sleep 3; "$K" cmd closepanel ;;
    S10result) "$K" cmd goto S09; sleep 4; "$K" cmd closepanel ;;
    *) "$K" cmd goto "$S" ;;
  esac
  sleep "${WAIT:-9}"
  for try in 1 2 3; do "$K" shot "$OUT/$S-$LANG_.png" 2>/dev/null && [ -s "$OUT/$S-$LANG_.png" ] && break; sleep 3; done
  case "$S" in S10*) "$K" cmd tap "cell:1:4"; sleep 4 ;; esac
  echo "$S $LANG_"
done
