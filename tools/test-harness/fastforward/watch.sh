#!/bin/bash
# watch.sh <hwNN> [maxMinutes] [ignore,list] - polls a running batch every 3 minutes and exits EARLY
# on the first fail signal, so a background run of it notifies as soon as a batch has told what it will
# (the user, 2026-10-07: runs reach evident fail conditions within minutes; hw52 ran 25 minutes past
# an exception visible at minute 19). Prints only new signals plus a one-line progress mark a game.
#
# Signals (the third argument lists the ones to ignore, e.g. "ref,hoard" to read on past known ones):
#   exc      a non-loading exception (vanilla's ShipHullSpreadsheetLoader noise filtered)
#   ref      a held prong refused on its day ("cannot sail today")
#   wipe     the swarm wiped: 0 hives after war day 400
#   collapse the swarm collapsing: hives under half its peak of 8 or more (hw47 0 / 10 / 0, hw49 b 0)
#   starved  swarms lost to the navy charge ("of navy upkeep unpaid"; hw49 8.8k-15.8k FP)
#   hoard    the fund hoarded: 20k+ FP and no launch for 500 war days, or none ever by day 2600 (hw41-49 30-43k unspent; ck2 opens the war at 2231, so 1500 fired on every batch from it at load)
#   broke    the swarm cannot sail: supplies stock under 5% of a month's output in the last three censuses with the
#            fund at 20k+ (hw53-54: the stock peaks 45-75k at 4-7 hives and falls to ~0 as 17-24 are founded)
#   humans   the humans collapsing: forward bases lost twice those founded, 10+ founded (hw51a 93 of 48)
#   starve   the humans cannot pay their plays: 50+ STARVE lines (expeditions the pools cannot pay)
#   hulls    a faction out of hulls with no hull convoy: a Hulls ledger line with 1000+ FP lost and 0 free (Luddic Path,
#            hostile to every donor, excepted) while another faction has hulls built and free
#   nowar    no war by day 2600: no strike launched (hw50-53 opened between 1466 and 1953; ck2 opens at 2231)
# The thresholds are a first cut (2026-10-07), the user's to tune.
TAG=$1; MAX=${2:-130}; IGN=",${3:-},"; T="$(cygpath -u "$LOCALAPPDATA")/Temp/threatinc-tests"
declare -A seen; start=$(date +%s)
ign() { case "$IGN" in *",$1,"*) return 0;; esac; return 1; }
flag() { # flag <game> <signal> <text...>: print once a game, mark the pass failed
  local g=$1 s=$2; shift 2
  ign "$s" && return
  [ -n "${seen[$s$g]}" ] && return
  seen[$s$g]=1; fail=1; echo "  SIGNAL $s: $*"
}
while :; do
  fail=0
  for g in a b c; do
    F=$T/ti-$TAG$g.txt; E=$T/exc-$TAG$g.txt
    [ -f "$F" ] || continue
    war=$(grep -o 'war [0-9]*' $F | tail -1 | cut -d' ' -f2); war=${war:-0}
    ex=$(grep -v "ShipHullSpreadsheetLoader" $E 2>/dev/null | grep -c "Exception\|Error")
    ref=$(grep -c 'cannot sail today' $F); la=$(grep -c '^Offensive launched' $F); hs=$(grep -c 'sails on its day' $F)
    hull=$(grep -c 'ship hulls from' $F); land=$(grep -c '^Hulls: .* lands' $F)
    hives=$(grep -o '^Census: threat hives [0-9]*' $F | tail -1 | grep -o '[0-9]*$'); hives=${hives:-0}
    peak=$(grep -o '^Census: threat hives [0-9]*' $F | grep -o '[0-9]*$' | sort -n | tail -1); peak=${peak:-0}
    starved=$(grep -c 'of navy upkeep unpaid' $F)
    fbf=$(grep -c 'Forward Base.*founded\|founded.*Forward Base' $F); fbl=$(grep -ci 'forward base.*\(lost\|destroyed\|falls\)' $F)
    starve=$(grep -c 'STARVE:' $F); strikes=$(grep -c '^Strike launched' $F)
    echo "$(date +%T) $TAG$g war $war | hives $hives (peak $peak) | launches $la held-sailed $hs refused $ref | exc $ex | fb $fbf/$fbl lost | STARVE $starve | hull convoys $hull landed $land"
    [ "$ex" -gt 0 ] && flag $g exc "$(grep -v "ShipHullSpreadsheetLoader" $E | grep -m1 -A6 "Exception\|Error" | grep "Exception\|Error\|threatinc" | head -3 | cut -c1-180 | tr '\n' ' ')"
    [ "$ref" -gt 0 ] && flag $g ref "$(grep -m1 -B1 'cannot sail today' $F | head -1 | cut -c1-220)"
    [ "$war" -gt 400 ] && [ "$hives" -eq 0 ] && [ "$peak" -gt 0 ] && flag $g wipe "0 hives at war day $war (peak $peak)"
    [ "$war" -gt 600 ] && [ "$peak" -ge 8 ] && [ $((hives * 2)) -lt "$peak" ] && flag $g collapse "$hives hives of a $peak peak at war day $war"
    [ "$starved" -gt 0 ] && flag $g starved "$(grep -m1 'of navy upkeep unpaid' $F | cut -c1-200)"
    fund=$(grep '^Offensive: ' $F | grep -o 'fund [0-9]* FP' | tail -1 | grep -o '[0-9]*'); fund=${fund:-0}
    if [ "$la" -gt 0 ]; then
      lastla=$(awk '/^Clock: day/{w=$5} /^Offensive launched/{l=w} END{print l+0}' $F)
      [ $((war - lastla)) -gt 500 ] && [ "$fund" -ge 20000 ] && flag $g hoard "no launch since war day $lastla, saving with $fund FP"
    elif [ "$war" -gt 2600 ] && [ "$fund" -ge 20000 ]; then
      flag $g hoard "no launch ever by war day $war, fund $fund FP: $(grep '^Offensive: ' $F | tail -1 | cut -c1-160)"
    fi
    # the last three censuses' supplies stock against a month's output
    lowsup=$(grep -o '^Census: .*supplies [0-9]* (+[0-9]*' $F | tail -3 | sed 's/.*supplies ([0-9]*) (+([0-9]*)/ /' | awk '$2>0 && $1*20<$2{n++} END{print n+0}')
    [ "$lowsup" -ge 3 ] && [ "$fund" -ge 20000 ] && flag $g broke "supplies stock under 5% of a month's output three censuses running, fund $fund FP: $(grep '^Census' $F | tail -1 | grep -o 'supplies [0-9]* ([^)]*)')"
    [ "$fbf" -ge 10 ] && [ "$fbl" -ge $((fbf * 2)) ] && flag $g humans "forward bases founded $fbf, lost $fbl"
    [ "$starve" -ge 50 ] && flag $g starve "$starve STARVE lines: $(grep 'STARVE:' $F | sed 's/#[0-9]*//; s/([0-9]* FP needed)//' | sort | uniq -c | sort -rn | head -2 | tr '\n' ';' | cut -c1-200)"
    short=$(grep '^Hulls: ' $F | grep -Ev 'Your faction|Luddic Path' | grep -Ec '[0-9]{4,} lost, 0 free')
    rich=$(grep '^Hulls: ' $F | grep -v 'Your faction' | grep -Ec '\([1-9][0-9]* built\): .* [1-9][0-9]* free')
    [ "$short" -gt 0 ] && [ "$rich" -gt 0 ] && [ "$hull" -eq 0 ] && flag $g hulls "a faction out of hulls, another with hulls free, no hull convoy: $(grep '^Hulls: ' $F | grep -Ev 'Your faction|Luddic Path' | grep -Em1 '[0-9]{4,} lost, 0 free' | cut -c1-160)"
    [ "$war" -gt 2600 ] && [ "$strikes" -eq 0 ] && flag $g nowar "no strike launched by war day $war"
  done
  [ $fail = 1 ] && { echo "FAIL SIGNAL - stop the batch, or read on with the signal in the ignore list"; exit 1; }
  [ $(( ($(date +%s)-start)/60 )) -ge $MAX ] && exit 0
  # sequential runs (the user, 2026-10-07: one game at a time) relaunch between games: two quiet polls end the watch
  if tasklist 2>/dev/null | grep -qi java.exe; then nojava=0; else nojava=$((nojava + 1)); [ "$nojava" -ge 2 ] && { echo "batch ended"; exit 0; }; fi
  sleep 180
done
