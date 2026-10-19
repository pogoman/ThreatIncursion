#!/bin/bash
# colonies.sh <ti-log> - human colonies / bases summed over the LAST census block (the faction lines precede the threat-hives line)
awk '/^Census: [a-z_]+ colonies/{if(!inblk){blk=""; inblk=1} blk=blk $0 "\n"; next} {if(inblk){last=blk; inblk=0}} END{if(inblk) last=blk; printf "%s", last}' "$1" | sed -E 's/^Census: ([a-z_]+) colonies ([0-9]+) \(size ([0-9]+)\).*bases ([0-9]+).*/\1 \2 \3 \4/' | awk '{c+=$2; s+=$3; b+=$4; n++; d=d" "$1"="$2} END{print c" colonies (size "s"), bases "b", "n" factions:"d}'
