#!/bin/bash
# Startet den Client unter einem virtuellen Bildschirm und prueft, dass er mit der Mod
# (inklusive Mixin) bis ins Hauptmenue kommt.
set -u
./gradlew downloadAssets --no-daemon -q || true
export LIBGL_ALWAYS_SOFTWARE=1
timeout 420 xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient --no-daemon > client.log 2>&1 &
CLIENT=$!
OK=0
for i in $(seq 1 400); do
	if grep -qE "Sound engine started|Created: .*minecraft:textures/atlas" client.log; then OK=1; break; fi
	if ! kill -0 $CLIENT 2>/dev/null; then break; fi
	sleep 1
done
sleep 10
pkill -f "net.fabricmc.devlaunchinjector" || pkill -f KnotClient || true
kill $CLIENT 2>/dev/null
wait $CLIENT 2>/dev/null

grep -nE "Mixin|mixin|counterstrike" client.log | grep -viE "^\S*\s*$" | head -40
if grep -nE "MixinApplyError|InvalidInjectionException|InvalidMixinException|Mixin apply .* failed|---- Minecraft Crash Report" client.log; then
	echo "FEHLER: Mixin- oder Crash-Meldung"; exit 1
fi
if [ $OK -ne 1 ]; then
	echo "FEHLER: Client ist nicht bis zum Laden der Texturen gekommen"; tail -80 client.log; exit 1
fi
echo "Client startet sauber"
