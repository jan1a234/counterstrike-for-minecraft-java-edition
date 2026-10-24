# Original tactical voxel assets

Five original cuboid models: `rifle`, `mg`, `sniper`, `shotgun`, `pistol`.
No commercial game models, textures, logos, or sound assets are included.
All geometry and the 64x64 RGBA pixel atlas were authored for this project.
The shared palette uses dark gunmetal, brown grips, olive furniture, brass
accents and cyan scope glass. The weapons have deliberately different
silhouettes: curved magazine, ammunition box/carry handle, long scoped barrel,
ribbed pump/twin tubes, and compact pistol slide.

## Integration

Set the existing weapon stack's component to its exact WeaponType ID:

```java
stack.set(DataComponents.ITEM_MODEL,
    Identifier.fromNamespaceAndPath("counterstrike", type.id));
```

Each `items/<id>.json` selects `counterstrike:item/<id>`, whose cuboid model
is in `models/item/<id>.json`. The shared texture is
`textures/item/tactical_atlas.png`. These are common resources so the client
receives them from the mod jar; no replacement of vanilla item models is used.

Models provide explicit GUI, first-person (both hands), third-person (both
hands), ground, fixed/item-frame, and head transforms. Their barrel points
along model +X. GUI transforms show a slightly tilted side profile, with a
separate scale for the compact pistol and long sniper rifle.

## Validation

From the repository root:

```text
python ci/validate_weapon_assets.py
```

The validator needs only Python's standard library. It discovers the IDs in
WeaponType.java, resolves item/model/PNG references, validates nondegenerate
cuboids in [-16,32], checks six textured faces, UV bounds and permitted element
rotations, checks all eight display contexts, and verifies PNG CRCs and decoded
byte count. Models were also inspected in an offline geometry contact sheet.
That offline preview is not runtime evidence. The integrated client test now also
renders all five equipped weapons and saves real Minecraft screenshots;
`docs/screenshots/rifle.png` is one of those captures.
