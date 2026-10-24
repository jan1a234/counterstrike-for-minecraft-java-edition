# Prüfung von Harbor 17 / Version 0.2.0

Die folgenden Prüfungen wurden am 24. Oktober 2026 tatsächlich mit Minecraft 26.2, Fabric API 0.161.0+26.2, JDK 25 und Gradle 9.7.0 ausgeführt. Keine simulierten Spielausgaben oder ersetzten Rendererbilder.

## Erfolgreiche Prüfungen

- `build`: erfolgreich; 44 JUnit-Tests, keine Fehler oder übersprungenen Tests.
- `runGameTest`: alle 8 erforderlichen Servertests erfolgreich.
- Serverseitig: 88.938 tatsächliche Blockprüfungen, sichere feste Spawns, Countdown, echte Botkämpfe und Punkte, Stop und erneuter Start.
- Custom-Modus: Botgefecht über mehrere Chunks ohne Zuschauer, einschließlich Friedlich.
- Inventarisolation: Kisten, Ender-/Pferdeinventare, veraltete Menüs, Drop-Pakete, Cursor-Drops, Bodenitems, Projektile, XP und Ausrüstungstransfers; Kontrollspieler bleiben uneingeschränkt.
- Bot-Sicherheit: Peaceful-Despawn mit Vanilla-Kontrolle, Speicher-Gates einschließlich Passenger-Save, Eigentumsmarker und Tod ohne Ausrüstung-/Loot-Export.
- `runClientGameTest`: echter Client mit integrierter Welt sowie separatem laufendem Server. Inventar und Spielerdaten nach Verlassen, Stop, Abmelden/Wiederverbinden und regulärem Neustart verglichen; Schutz gegen Angriffe Außenstehender getestet.
- Assetprüfung: fünf Modelle, 183 Quader, Referenzen, Transformationskontexte und PNG-Prüfsummen erfolgreich geprüft. Acht Screenshots vom echten Client erzeugt.
- Produktions-Jar geprüft: Version 0.2.0, Harbor-Dimension und Waffenassets enthalten; keine GameTest-Klassen oder Test-World-Presets enthalten.

## Wiederholbarkeit

`ci/run_runtime_tests.py` führt Server und Client nacheinander aus und verlangt Exitcode 0, konkrete Laufzeitmarker und frische PNGs. Sein abschließender Bericht unter `build/reports/counterstrike-runtime/results.json` ergab `passed: true`. Die Verzeichnisse sind absichtlich nicht eingecheckt. Ausführungsbefehle und Voraussetzungen stehen in `src/gametest/README.md`.

Kopien echter Aufnahmen befinden sich unter `docs/screenshots/harbor.png` und `docs/screenshots/rifle.png`.

## Nicht behauptet

Nicht vollständig getestet sind harte Prozess-/Stromausfälle, große Mehrspielerlast, beliebige Fremdmods und jede bestehende Welt. Geprüfte Speicher-Gates ersetzen keinen vollständigen Crash-/Stromausfalltest. Alte unmarkierte Zombie-Bots werden nicht automatisch entfernt. Die Mod bleibt Team-Deathmatch mit Minecraft-Figuren, keine vollständige Nachbildung von Counter-Strike.
