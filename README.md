# meridian-blueprint

A prefab or a selection shown in the world as a projection, with a diff overlay: cyan where a block
is missing, orange where another block is (or a block or a fluid where the blueprint has air),
yellow where the right block faces the wrong way. The game's own paste tool places, turns and flips
it.

A prefab's empty cells are kept: they are air in the blueprint, turned and flipped with the rest, and
never drawn as a ghost.

## Building it

The **Build** section of the module's settings builds the blueprint placed in the world, with the
player's own character or a headless one. It needs core's `Worker` (core moves the player).

1. **What is in the way**, top down: another block where the blueprint has one, the right block
   turned the wrong way, or a block where the blueprint has air - broken. A fluid where the
   blueprint has air is shut out with scaffolding: a block put in a fluid's cell takes its place, and
   comes down at the end.
2. **The blueprint's blocks** go in bottom up, the nearest first in each layer, each where it has
   something to lean on - a block below, beside or above. Each carries the rotation the blueprint
   gives it: the server keeps a placed block's rotation as it is sent.
3. **Its water** is poured in, a bucket at a time; the bucket is filled at the nearest water source
   outside the blueprint (within 24 blocks). The player needs a `Container_Bucket`. Lava has no
   bucket in the game: a blueprint's lava is left, and said so at the end.
4. **The scaffolding** is taken down, and **the drops** of what was broken are picked up.

**Scaffolding**, of a cheap block the player carries (*Scaffolding of* names one):

- a column under a block that has nothing to lean on yet, 6 blocks at most, its blocks put in from
  below;
- a pillar to stand on, built under the body, when a block is out of reach from anywhere the body
  gets to - 24 blocks at most. From its top every block within reach is placed; then it is taken
  down under the feet, a block at a time.

**Blocks** come from the player's inventory: the storage and the backpack into the hotbar as needed.
When the player runs out, from *Blocks the player runs out of*:

- **None**: what is carried; a block of an item the player has none of is left, and named at the end.
- **The nearest chests that hold them** (the default): chests within 32 blocks, those core saw holding
  the item first.
- **The chosen chest**: open it in the game, then press **Choose the chest I opened last**.

**Build along where I look** builds the blueprint, then again its own length further along the axis
the player looks along, and again: *how many copies*, or with -1 until stopped or a copy is left
unfinished (the blocks ran out). Each copy leans on the one before: a bridge over a gap, a road, a
wall. A bridge's next block is placed leaning over the edge, as a player bridges.

**Build**, **Build along where I look** and **Stop building**; the **Building** line says how it goes.
The block being worked on is outlined, through walls. A block tried 3 times is left, and counted as
still to build.

### On the command channel

```
blueprint <conn> build                                     -> {status, placed, broken, scaffolds, left, lacking, poured, copies}
blueprint <conn> build-along <copies> [x+|x-|z+|z-]        -> the placed blueprint along an axis (default: where the player looks)
blueprint <conn> build-box <x y z> <w h d> <block> [rot]   -> a box of one block there; Empty: air; Water_Source: water
blueprint <conn> build-box-along <x y z> <w h d> <block> <copies> <x+|x-|z+|z->
blueprint <conn> stop
blueprint <conn> set chests off|nearest|chest <x y z>
blueprint <conn> set scaffold <item>
```

### Not yet

- **Lava**: no bucket carries it, so a blueprint's lava is not poured. Lava where the blueprint has
  air or a block is shut out with blocks like water.
- **A column taller than 6** under a block with nothing to lean on: the block is left.
- **Water flowing back** into a cleared cell from a source outside the blueprint.
