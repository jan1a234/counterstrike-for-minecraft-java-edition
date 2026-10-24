# Counter-Strike für Minecraft — Harbor 17

Team-Deathmatch für **Minecraft Java 26.2**, mit eigener Hafenkarte, festen Team-Spawns, vier Klassen, fünf modellierten Schusswaffen und Bots. Das Team mit den meisten Kills bei Ablauf der Zeit gewinnt.

## Installieren und spielen

Fabric Loader **0.19.5+**, Fabric API **0.161.0+26.2** und die Mod-Jar gehören auf Server und Client. Im Einzelspieler genügt die Installation im Client. Java 25 wird benötigt.

1. `counterstrike-0.2.1.jar` in den `mods`-Ordner legen.
2. Welt öffnen und **`/cs play`** eingeben.
3. Nach dem Countdown spielen; **`/cs leave`** bringt dich zurück.

Keine Map-Datei, kein zusätzlicher Resourcepack-Download und kein manuelles Setzen von Spawns erforderlich. Beim ersten Start wird **Harbor 17** in einer eigenen Dimension gebaut; deine Oberwelt wird nicht überbaut. Die Karte hat Containerhof, Lagerhalle und Pier, gedeckte Team-Bereiche und **16 feste Spawnplätze pro Team**. Der Einstieg ergänzt Botgegner. Containerhof und Pier haben versetzte Deckungen; die Lagerhalle hat versetzte Tore und Innenwände. Drei durchgängige Wege mit Querverbindungen bleiben erhalten.

**Update von 0.2.0:** Alte Jar ersetzen, nicht zusätzlich installieren. Bereits erzeugte Harbor-Arenen erhalten beim nächsten Start die neue Geometrie. Nur die mod-eigene Harbor-Fläche wird aktualisiert; erkennbare eigene Blockänderungen bleiben erhalten. Selbst gesetzte Blöcke, die exakt dem alten generierten Zustand gleichen, sind nicht unterscheidbar: vorher Weltbackup anlegen. Custom-Karten und Oberwelt werden nicht umgebaut. Bei einer alten Custom-Konfiguration als OP einmal `/cs map harbor` wählen.

## Steuerung

Die Waffen reagieren serverseitig; Nachladen und Zielen sind getrennte Aktionen.

- **Linksklick oder Rechtsklick:** schießen; gedrückt halten für wiederholtes Feuern. Beide nutzen dieselbe serverseitige Feuerrate. Die zugehörigen Minecraft-Tastenbelegungen werden respektiert.
- **R** oder **`/cs reload`:** nachladen; leere Magazine laden beim Schussversuch automatisch nach.
- **Schleichen:** genauer zielen; beim Sniper zusätzlich Zielfernrohr.
- **Granate + Rechtsklick:** werfen; Explosion ohne Blockzerstörung.
- **`/cs class sturm|mg|sniper|pionier`:** Klasse wählen.
- **`/cs status`:** Spielstand und Einstellungen anzeigen.

Bewegung und Dauerfeuer erhöhen die Streuung. Ruhe und Zielen helfen; Schaden nimmt je nach Waffe mit der Entfernung ab. Spawnschutz endet beim eigenen Angriff. Bots benötigen Sichtkontakt und reagieren je nach Schwierigkeit verzögert.

## Klassen und Spielregeln

Alle Klassen erhalten zusätzlich Pistole und Messer; Teamfarben, Munitionsanzeige, Restzeit und Kill-Liste bleiben im Spiel sichtbar.

| Klasse | Hauptwaffe | Besonderheit |
|---|---|---|
| Sturm | Sturmgewehr | Allrounder, eine Granate |
| MG | Maschinengewehr | Schwerere Rüstung, mehr Leben, langsamer |
| Sniper | Scharfschützengewehr | Präzises Zielen und Zielfernrohr |
| Pionier | Schrotflinte | Nahkampf, drei Granaten, schneller |

Spieler werden nach einem Abschuss neu ausgerüstet und an einen festen Team-Spawn gesetzt. Bots kämpfen auch ohne anwesende Zuschauer weiter. Dies ist **Team-Deathmatch**, nicht der Bombenmodus von Counter-Strike. Bots nutzen weiterhin Minecraft-Zombiefiguren mit Teamausrüstung; Waffen besitzen eigene Modelle. Keine Nachladeanimationen oder echte ballistische Simulation.

## Einstellungen und eigene Karten

Administratoren können Spieldauer, Bots und Karte vor einem Match festlegen. Die Konfiguration liegt als `counterstrike.json` im Weltordner.

```text
/cs time 15|30|120
/cs bots ct|t <0..16>
/cs botlevel leicht|normal|schwer
/cs start
/cs stop
/cs map harbor|custom
/cs spawn add ct|t
/cs spawn list
/cs spawn clear ct|t
```

`harbor` verwendet die mitgelieferten Spawns. Im Custom-Modus wird das Rechteck zwischen den Spawns einschließlich eines Chunk-Rands aktiv gehalten; zum Schutz vor übergroßen Ladebereichen gilt insgesamt ein Limit von 256 Chunks. Für `custom` zuerst eigene Spawnpunkte für beide Teams setzen. Bestehende Konfigurationen mit eigenen Spawnpunkten bleiben im Custom-Modus erhalten. Spieler können mit `/cs join ct|t|auto` ein Team wählen.

## Schutz deiner Spielerdaten

Vor dem Betreten wird dein Zustand dauerhaft im Weltordner gesichert: Inventar einschließlich Item-Komponenten, ursprüngliche Position und Spielmodus sowie Gesundheit, Nahrung, Effekte, Erfahrung und Team. Verlassen, Abmelden und regulärer Serverneustart stellen ihn wieder her. Ein bloßer Lobbybeitritt setzt dein Survival-Inventar nicht zurück.

Während eines Matches sind externe Kisten-/Ender-/Pferdeinventare, Ausrüstungstransfers sowie Item- und XP-Aufnahme und das Wegwerfen von Ausrüstung serverseitig gesperrt. Normales Umsortieren im eigenen Inventar bleibt möglich. Bots funktionieren auch auf Friedlich und werden nicht als dauerhafte Zombies gespeichert. Bereits von älteren Versionen gespeicherte, unmarkierte Zombies werden nicht automatisch entfernt.

Fehlerhafte oder nicht wiederherstellbare Sicherungen werden nicht stillschweigend gelöscht; erneuter Einstieg wird blockiert. **`counterstrike-player-backups` nicht manuell löschen**, solange eine Wiederherstellung offen ist. Vor Mod-Updates weiterhin ein normales Weltbackup anlegen. Ein harter Stromausfall und die Kombination mit beliebigen anderen Mods sind nicht vollständig abgedeckt.

## Bauen und prüfen

Die Tests laufen in wegwerfbaren Welten unter `build/run`, nicht in deinen Spielständen.

```bash
./gradlew build
python ci/validate_weapon_assets.py
./gradlew runGameTest
./gradlew runClientGameTest
```

Clienttests brauchen eine funktionierende OpenGL-Anzeige. Server- und Clienttests nicht in getrennten parallelen Gradle-Prozessen starten. Die Prüfungen umfassen Kartengeometrie und Versionsupgrade, echte Botkämpfe, Countdown/Stop, Spielerwiederherstellung sowie echte Client-Screenshots. Zusätzlich betätigt der Clienttest echte Maus-/Tasteneingaben und prüft serverseitig Munition, Schaden, Dauerfeuer, Nachladen und normale Minecraft-Interaktionen. Einzelheiten stehen in `src/gametest/README.md`.
