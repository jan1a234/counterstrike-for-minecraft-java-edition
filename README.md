# Counter-Strike für Minecraft

Fabric-Mod für Minecraft Java 26.2: Team Deathmatch zwischen Counter-Terroristen (CT) und Terroristen (T),
mit vier Klassen, Schusswaffen und Bots. Das Team mit den meisten Kills bei Ablauf der Zeit gewinnt.

## Installation

1. Fabric Loader 0.19.5 oder neuer und Fabric API für 26.2 installieren, auf dem Server und bei jedem Spieler.
2. Die Mod-Jar in den `mods`-Ordner legen (Server und Client). Die Jar gibt es im Artefakt
   `counterstrike-mod` des letzten GitHub-Actions-Builds auf `main` (Tab „Actions“).

## Map einrichten (einmalig, als OP)

```
/cs spawn add ct      Spawnpunkt für CT an deiner Position (mehrere möglich)
/cs spawn add t       Spawnpunkt für T
/cs spawn list        Spawnpunkte anzeigen
/cs spawn clear ct|t  Spawnpunkte eines Teams löschen
```

Die Einstellungen landen in `counterstrike.json` im Weltordner und gehören damit zur Map.

## Spiel starten

```
/cs time 15|30|120             Laufzeit in Minuten
/cs bots ct|t <anzahl>         Bots pro Team (0 bis 16)
/cs botlevel leicht|normal|schwer
/cs join ct|t|auto             Team wählen (jeder Spieler)
/cs class sturm|mg|sniper|pionier   Klasse wählen (oder im Chat anklicken)
/cs start                      Spiel starten (10 Sekunden Countdown)
/cs stop                       Spiel abbrechen
/cs status                     Spielstand und Einstellungen
```

Während des Spiels sind alle im Abenteuermodus, nach dem Spiel bekommt jeder seinen alten Spielmodus zurück.
Oben zeigt eine Leiste den Stand und die Restzeit, rechts stehen die Kills jedes Spielers.
Wer ausgeschaltet wird, spawnt sofort an einem Team-Spawn neu und hat 3 Sekunden Spawnschutz.

## Klassen

| Klasse | Hauptwaffe | Extras |
|---|---|---|
| Sturmtruppler | Sturmgewehr, 30 Schuss | 1 Granate |
| MG-Schütze | Maschinengewehr, 100 Schuss, Salven | Eisenbrust, +3 Herzen, 15 % langsamer |
| Sniper | Scharfschützengewehr, 5 Schuss | Schleichen = Zielfernrohr (4-fach Zoom, volle Präzision) |
| Pionier | Schrotflinte, 8 Kugeln pro Schuss | 3 Granaten, 10 % schneller |

Alle haben zusätzlich Pistole und Messer.

## Steuerung

- Rechtsklick: schießen (gedrückt halten für Dauerfeuer)
- Schleichen + Rechtsklick: nachladen (der Sniper lädt automatisch, wenn das Magazin leer ist)
- Schleichen: genauer zielen
- Granate: Rechtsklick wirft, sie explodiert nach 2,5 Sekunden ohne Blockschaden
- Kopftreffer machen mehr Schaden, Munition steht über der Schnellleiste

## Bauen

```
./gradlew build
```

Benötigt JDK 25.
