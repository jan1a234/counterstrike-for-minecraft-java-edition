#!/bin/bash
# Startet einen echten Dedicated Server mit der Mod, laesst 3 gegen 3 Bots kaempfen
# und prueft, dass keine Fehler auftreten und Kills gezaehlt werden.
set -u
mkdir -p run/world
echo "eula=true" > run/eula.txt
cp ci/smoke/server.properties run/server.properties
cp ci/smoke/counterstrike.json run/world/counterstrike.json

./gradlew runServer --no-daemon > server.log 2>&1 &
SERVER=$!

for i in $(seq 1 300); do
	grep -q "Done (" server.log && break
	if ! kill -0 $SERVER 2>/dev/null; then echo "Server ist abgestuerzt"; cat server.log; exit 1; fi
	sleep 1
done
grep -q "Done (" server.log || { echo "Server startet nicht"; tail -100 server.log; exit 1; }

RCON="python3 ci/smoke/rcon.py"
$RCON "forceload add -16 -16 64 32" "time set day" "gamerule spawn_mobs false" "gamerule doMobSpawning false" "cs status" "cs start"
sleep 100
$RCON "cs status"
$RCON "cs stop" "stop"
wait $SERVER

echo "===== Mod-Ausgaben ====="
grep -E "\[CHAT\]|counterstrike|Counter-Strike" server.log | tail -60

FAIL=0
if grep -nE "Exception|Caused by|\[Server thread/ERROR\]" server.log; then
	echo "FEHLER: Exceptions im Server-Log"; FAIL=1
fi
if ! grep -qE "\[Bot\] [A-Za-z]+ \[[^]]+\] \[Bot\]" server.log; then
	echo "FEHLER: kein einziger Bot-Kill im Kill-Feed"; FAIL=1
fi
exit $FAIL
