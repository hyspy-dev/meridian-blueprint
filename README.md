# meridian-blueprint

A prefab or a selection shown in the world as a projection, with a diff overlay: cyan where a block
is missing, orange where another block is, yellow where the right block faces the wrong way. The
game's own paste tool places, turns and flips it.

## Building it

The **Build** section of the module's settings builds the blueprint placed in the world, with the
player's own character or a headless one. It needs core's `Worker` (core moves the player).

1. **What is in the way** is broken first, top down: another block where the blueprint has one, or
   the right block turned the wrong way.
2. **The blueprint's blocks** go in bottom up, the nearest first in each layer, each where it has
   something to lean on - a block below, beside or above. Each carries the rotation the blueprint
   gives it: the server keeps a placed block's rotation as it is sent.
3. **Scaffolding**, of a cheap block the player carries (*Scaffolding of* names one):
   - a column under a block that has nothing to lean on yet;
   - a pillar to stand on, built under the body, when a block is out of reach from anywhere the body
     gets to.

   A column or pillar is 6 blocks at most: the top of a pillar is still a drop the body survives.
   Both are taken down at the end.
4. **The drops** of what was broken are picked up.

**Blocks** come from the player's inventory: the storage and the backpack into the hotbar as needed.
When the player runs out, from *Blocks the player runs out of*:

- **None**: what is carried; a block of an item the player has none of is left, and named at the end.
- **The nearest chests that hold them** (the default): chests within 32 blocks, those core saw holding
  the item first.
- **The chosen chest**: open it in the game, then press **Choose the chest I opened last**.

**Build** and **Stop building**; the **Building** line says how it goes. The block being worked on is
outlined, through walls.

A block tried 3 times is left, and counted as still to build.

### On the command channel

```
blueprint <conn> build                                     -> {status, placed, broken, scaffolds, left, lacking}
blueprint <conn> build-box <x y z> <w h d> <block> [rot]   -> builds a box of one block there
blueprint <conn> stop
blueprint <conn> set chests off|nearest|chest <x y z>
blueprint <conn> set scaffold <item>
```

### Not yet

- **Air in a prefab.** A prefab's empty cells are not kept, so a block where the prefab has air is
  not broken.
- **Tall work.** A block more than 6 above anything the body stands on is left.
- **Bridges along an axis, filling and clearing water and lava.**
