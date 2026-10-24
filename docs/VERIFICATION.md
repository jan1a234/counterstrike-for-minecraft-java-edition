# Prüfung von Harbor 17 / Version 0.2.1

Die folgenden Prüfungen wurden am 24. Oktober 2026 tatsächlich mit Minecraft 26.2, Fabric API 0.161.0+26.2, JDK 25 und Gradle 9.7.0 ausgeführt. Eingaben, Serverantworten und Bilder stammen aus echten Minecraft-Läufen.

## Erfolgreiche Prüfungen

- Build und 45 JUnit-Tests erfolgreich; keine Fehler oder übersprungenen Tests. Die Laufzeittasks wurden beim Paketbuild ausgelassen und anschließend separat durch den strengen Runtime-Runner ausgeführt.
- Alle 8 erforderlichen Server-GameTests erfolgreich, Prozess-Exitcode 0.
- Neuer Schießtest: alle fünf Waffen mit beiden Maustasten gegen Luft, Blöcke und nahe Gegner; serverseitiger Munitionsverbrauch und tatsächlicher Gesundheitsschaden geprüft. Die Tests betätigen Minecraft über echte Maus-/Tastaturereignisse statt direkt `tryFire` aufzurufen.
- Dauerfeuer, Loslassen, gleichzeitige Maustasten, neu belegte Angriffstaste, R, automatisches Nachladen und Nachladeabbruch beim Waffenwechsel erfolgreich geprüft.
- Waffenwechsel und Schuss im selben Clienttick: neue Waffe schießt, alte bleibt unverändert. Zuschauer-Kamera, GUI, Nichtteilnehmer-Sperre sowie gewöhnlicher Blockabbau und Kistennutzung bleiben erhalten.
- Harbor: 88.938 tatsächliche Blockprüfungen, feste sichere Spawns, drei verbundene Routen mit zusätzlichen Abknickungen und begrenzten Sichtlinien. Natürlicher Botkampf, Punkte, Countdown, Stop und Neustart erfolgreich.
- Kartenupgrade v1 → v2: 93.879 exakte Blockvergleiche, vier erhaltene eigene Änderungen und sechs Außenproben. Vollendete Migration bleibt unverändert; unterbrochene Migration wird fortgesetzt; beschädigte Spawns werden abgelehnt. Oberwelt und Custom-Karten werden nicht umgebaut.
- Custom-Botkampf über mehrere Chunks ohne Zuschauer, einschließlich Friedlich.
- Bestehende Inventarisolation erneut geprüft: externe Menüs, Drops, Bodenitems, Projektile, XP und Ausrüstungstransfers; Kontrollspieler bleiben uneingeschränkt. Bot-Speicher-Gates, Peaceful-Verhalten und unterdrückte Beute ebenfalls geprüft.
- Vollständiger Client-Lifecycle: Inventar und Spielerdaten nach Verlassen, Stop, Abmelden/Wiederverbinden und regulärem Serverneustart verglichen; Außenspielerschutz geprüft. Prozess-Exitcode 0.
- Assetprüfung: fünf Modelle, 183 Quader, Referenzen und PNG-Prüfsummen erfolgreich. Neun echte Rendereraufnahmen erzeugt; Waffenansicht, Harbor-Übersicht und Lagerhalle visuell geprüft.
- Produktions-Jar: Version 0.2.1, Dimension, Modelle und beide neuen Client-Mixins enthalten; keine GameTest-Klassen oder Test-World-Presets enthalten.
- Unabhängiges Abschlussreview: keine offenen konkreten Korrektheits- oder Sicherheitsbefunde.

## Reproduktion und Nachweise

`ci/run_runtime_tests.py --client` verlangt erfolgreiche Prozess-Exits, konkrete Server-/Clientmarker und frische Renderer-PNGs. Der finale Bericht unter `build/reports/counterstrike-runtime/results.json` lautet `passed: true`; beide Stufen haben `exit_code: 0`. Befehle und Voraussetzungen stehen in `src/gametest/README.md`.

Die roten Ausgangsläufe reproduzierten verlorene Luftschüsse, fehlendes Linksklickfeuer sowie falsche Waffenwahl im selben Tick und blockierte Zuschauer-Kamera. Die jeweiligen Grünläufe prüfen die korrigierte Wirkung über das Netzwerk. Botkämpfe allein gelten ausdrücklich nicht als Nachweis funktionierender Spielereingaben.

Auf Windows verzögerte sich das Beenden eines erfolgreichen Testservers; der finale Lauf endete ohne erzwungenen Abbruch regulär. Ein zuvor abgebrochener Diagnoselauf wird nicht als erfolgreicher Build gezählt.

Kopien echter Aufnahmen befinden sich unter `docs/screenshots/harbor.png`, `warehouse.png` und `rifle.png`. Rohlogs und wegwerfbare Testwelten sind nicht eingecheckt.

## Grenzen

Nicht vollständig getestet sind harte Prozess-/Stromausfälle, große Mehrspielerlast, beliebige Fremdmods und jede bestehende Welt. Bei der Kartenmigration sind eigene Blöcke, die exakt dem alten generierten Zustand gleichen, nicht unterscheidbar; vor dem Update Weltbackup anlegen. Alte unmarkierte Zombie-Bots werden nicht automatisch entfernt. Die Mod bleibt Team-Deathmatch mit Minecraft-Figuren, keine vollständige Counter-Strike-Simulation.
