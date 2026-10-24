# Echte Minecraft-Laufzeittests

Die Tests verwenden Fabric GameTest für Minecraft 26.2 und ausschließlich temporäre Welten unter `build/run`. Java 25 ist erforderlich; Clienttests brauchen eine OpenGL-Anzeige. Die Konfiguration ist bereits in `build.gradle` enthalten.

## Ausführen

Die Laufzeittests nacheinander starten, nicht in parallelen Gradle-Prozessen:

```sh
./gradlew test
./gradlew runGameTest
./gradlew runClientGameTest
python ci/validate_weapon_assets.py
```

Ein zusätzlicher Runner erzeugt einen maschinenlesbaren Bericht; die Pfade anpassen:

```sh
python ci/run_runtime_tests.py --java-home /path/to/jdk-25 --gradle /path/to/gradle/bin/gradle --client
```

Er prüft Exitcodes, echte Laufzeitmarker und frische PNGs. Logs und `results.json` liegen in `build/reports/counterstrike-runtime`. Fehlende Belege bedeuten einen fehlgeschlagenen Test.

## Prüfumfang

Die Servertests erzeugen Harbor und prüfen reale Blöcke, Kollisionen, sichere feste Spawns, wiederholten Aufbau und Ablehnung beschädigter Spawns. Es folgen Countdown, tatsächliche Bot-Ticks, natürliche Gefechte mit Punkten, Stop und erneuter Start. Produktionscode verwaltet seine Chunk-Tickets selbst; die Tests verdecken fehlende Tick-Bereitschaft nicht durch Forceloads.

Der Clienttest führt `/cs play` mit einem angemeldeten Spieler aus. Er prüft Inventar samt Komponenten, Position, Spielmodus, Gesundheit, Nahrung und Erfahrung nach Verlassen, Stop, regulärem Serverneustart und Abmelden/Wiederverbinden bei weiterlaufendem Server. Eine Lobby-Regression schützt gegen Inventar-Rollback und Verlust des ursprünglichen Teams.

Echte Renderer-Screenshots zeigen Spielsituation, fünf Waffen, Kartenübersicht und Wiederherstellung. Sie liegen unter `build/run/clientGameTest/screenshots`; neue Läufe ersetzen sie. Persistierte Item-Encodings statt alter Registry-Holder-Identitäten erlauben den korrekten Vergleich über Serverneustarts.

Zusätzliche Serverregressionen prüfen Container-/Drop-/Pickup-/XP-Isolation mit unveränderten Außenstehenden, ephemere Bot-Speicherung und Gefechte im Custom-Modus auf Friedlich. Die Inventarprüfung läuft synchron vor dem Harbor-Szenario, damit beide Tests nicht gleichzeitig den Match-Singleton verändern.

## Schussregression und Kartenupgrade

`WeaponInputClientTest` und `WeaponInteractionScenarios` verwenden Fabric-TestInput: echte Maus- und Tastaturereignisse laufen durch Minecraft, Netzwerk und Server. Kein Aufruf von `onUse` oder `tryFire` ersetzt die Eingabe. Geprüft werden alle fünf Waffen mit beiden Maustasten in Luft, auf Blöcke und nahe Gegner, tatsächlicher Gesundheitsschaden, Dauerfeuer und Loslassen, doppelte Tasten, neu belegte Angriffstaste, Waffenwechsel und Schuss im selben Tick, unveränderte Zuschauer-Kamera, R/Auto-Nachladen, Waffenwechsel, GUI und normale Minecraft-Interaktionen.

`HarborUpgradeGameTest` läuft synchron vor dem Botkampf. Eine installierte v1-Geometrie mit eigenen Änderungen wird auf v2 angehoben; exakte Blöcke, eigene Änderungen, Außenbereich, Oberwelt, unterbrochene Upgrades und beschädigte Spawns werden geprüft. Die eingefrorene v1-Geometrie ist durch einen Digest gegen die veröffentlichte Fassung abgesichert.

## Grenzen

Nicht vollständig abgedeckt sind beliebige Modkombinationen, große Mehrspielerlast und harte Prozess-/Stromausfälle. Separate Snapshot-Dateitests prüfen beschädigte Daten und atomare Dateivorgänge. Der Headless-World-Preset ist in `HARBOR_DIMENSION.md` erklärt und ausschließlich im Test-Source-Set enthalten.
