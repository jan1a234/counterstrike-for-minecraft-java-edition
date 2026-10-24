# Harbor dimension in the headless GameTest server

`resources/data/minecraft/worldgen/world_preset/flat.json` is a **test-only** fixture.
It retains Minecraft 26.2's three vanilla flat-preset dimension definitions and adds
`counterstrike:harbor`, identically to the production dimension JSON. Keep this
entry synchronized with `src/main/resources/data/counterstrike/dimension/harbor.json`.
The dimension type continues to come from production resources.

## Why this is necessary

Inspection of the actual 26.2 `minecraft-common.jar` with `javap -c -p` showed:

* Fabric's `FabricGameTestRunner.runHeadlessServer` calls vanilla `GameTestServer.create`.
* `GameTestServer.lambda$create$1` constructs a new empty `MappedRegistry` for
  `Registries.LEVEL_STEM` and freezes it.
* It selects `WorldPresets.FLAT` from the loaded worldgen registry, calls
  `createWorldDimensions()`, then `bake(emptyRegistry)`.
* Consequently, standalone datapack `dimension/*.json` registrations are not merged
  into this headless test world's dimension set. Only dimensions embedded in the
  flat world preset become server levels.

This is not an `ArenaManager` lookup failure or invalid production dimension data.
Do not add an overworld fallback, runtime dimension registration, or a production
vanilla-preset override. Real integrated/dedicated world loading is a separate path.
The fixture is confined to the gametest source set, not the distributed mod.

Fabric's official 26.2 Automated Testing documentation describes the separate
`src/gametest/resources` test mod/source set; the empty-registry behavior above was
verified in the installed Minecraft bytecode, not inferred from that general guide.

## Verification

The original real `runGameTest` failed at `ArenaManager.level` with
`Harbor-Dimension fehlt`. With only this fixture added, the same JDK25/Gradle command:

* created and saved `counterstrike:harbor` as a fourth server dimension;
* completed 88,938 real generated-block checks;
* passed fixed-spawn, idempotence, damaged-spawn rejection, countdown, and damage-protection assertions;
* reached the later live bot-combat assertion, which timed out on tick 2402.

Thus dimension loading is fixed, but that execution is **not a green entire suite**.
The bot-only ticking/combat issue must be diagnosed separately; this fixture neither
skips assertions nor adds ticking tickets on production's behalf.

Do not run `runGameTest` and `runClientGameTest` concurrently: their configured
cleanup/run-directory behavior can interfere with an active test world. Use isolated
run directories or serialize them. Never use a player's saves for these tests.
