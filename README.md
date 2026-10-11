# Counter-Strike für Minecraft

Fabric-Mod für Minecraft Java 26.2: Team Deathmatch zwischen Counter-Terroristen (CT) und Terroristen (T).
Das Team mit den meisten Kills bei Ablauf der Zeit gewinnt.

## Installation

1. Fabric Loader 0.19.5 oder neuer und Fabric API für 26.2 installieren (Server und Client).
2. Die Jar aus `build/libs` (oder aus dem Artefakt des GitHub-Actions-Builds) in den `mods`-Ordner legen.

## Spiel einrichten

```
/cs spawn add ct      Spawnpunkt für CT an deiner Position (mehrere möglich)
/cs spawn add t       Spawnpunkt für T
/cs time 15|30|120    Laufzeit in Minuten
/cs bots ct|t <n>     Anzahl Bots pro Team (Bots folgen in Schritt 3)
/cs join ct|t|auto    Team wählen
/cs start             Spiel starten (10 Sekunden Countdown)
/cs stop              Spiel abbrechen
/cs status            Spielstand und Einstellungen
```

Die Einstellungen landen in `counterstrike.json` im Weltordner und gehören damit zur Map.

## Bauen

```
./gradlew build
```

Benötigt JDK 25.
