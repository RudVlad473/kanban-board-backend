#!/bin/sh
# Client-side connect prober for the kanban nonprod connect-timeout investigation (2026-09-05).
# Pairs with /var/tmp/net-forensics/samples.csv on netcup-prod: this records whether a connection
# COULD be established from here; that records whether the host saw any handshake at the same
# instant. A row here with exit!=0 and a flat passive_opens there = the SYN never arrived.
OUT="$HOME/dev/kanban-net-forensics/probe.csv"
URL=https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health
[ -f "$OUT" ] || echo "utc,exitcode,http,time_connect,time_total" > "$OUT"
while :; do
  R=$(curl -s -o /dev/null --connect-timeout 25 --max-time 40 \
        -w '%{exitcode},%{http_code},%{time_connect},%{time_total}' "$URL" 2>/dev/null) \
        || R="${R:-99,000,0,0}"
  echo "$(date -u +%FT%TZ),$R" >> "$OUT"
  sleep 15
done
