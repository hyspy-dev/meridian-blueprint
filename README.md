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
2. **The blueprint's blocks** go in strictly layer by layer, bottom up: a layer is begun only when
   the one under it is done (or its cells left as impossible), the nearest first in it, each where it
   has something to lean on - a block below, beside or above. Each carries the rotation the blueprint
   gives it: the server keeps a placed block's rotation as it is sent.
3. **Its water** is poured in, a bucket at a time; the bucket is filled at the nearest water source
   outside the blueprint (within 24 blocks). The player needs a `Container_Bucket`. Lava has no
   bucket in the game: a blueprint's lava is left, and said so at the end.
4. **The scaffolding** is taken down, and **the drops** of what was broken are picked up.

**Scaffolding is for the character only**, never under the building: a pillar of a cheap block the
player carries (*Scaffolding of* names one), built under the body as a player towers up, 24 blocks at
most.

- **Blocks that hang in the air** (the lowest of them, nothing to lean on): the pillar goes up beside
  one of them, outside the building, its top level with it - the block leans on the pillar's side.
  From up there every block within reach goes in; the character then builds on, standing on what it
  has built, and comes down its pillar at the end, the pillar taken down under the feet.
- **A block out of reach** from anywhere the body gets to: a pillar beside it; from its top every
  block within reach is placed, then it is taken down under the feet.
- Blocks with no place beside them to climb to are left, and the log says which and why.

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

### The prefab put down is kept

The prefab put down is kept in the module's settings as one line, *Put down*: the prefab's name, the
paste tool's turns and flips before the paste, where it was pasted, then the panel's moves and turns
of it - `house | Y90 FX @120,64,-30 rY s1,0,0`. A proxy opened again, a headless one too, puts it down
again from that: the prefab read from the list, turned as the paste tool turns (core's own turn), set
where it was, moved and turned as the panel did. **Build** with no blueprint shown builds it, and a game
turning blueprinting on sees it again. Nothing of the world is kept: building reads the world anew,
and what is right already - built before, or by someone helping - is left alone.

The line is replaced by the next prefab put down (a selection pasted is no prefab: it clears it), and
gone with **Forget the prefab put down**. Editing or removing the prefab's file changes what is put
down again. One per server folder, as the miner's box.

### What it takes

While a blueprint is shown, a panel at the right side of the screen shows the prefab's name and
`Built N of M`, and under it a strip for each block it takes: the item's icon and
`carried / still to place`, red while less is carried than is still to place; the most still to
place first (10 strips, the rest counted). It counts anew every 2 seconds, so it follows the building - the bot's and anyone's.
A cell is still to place where the world does not have the blueprint's block, turned as drawn. Air
is no block. The panel goes with the projection.

### On the command channel

```
blueprint <conn> prefabs                                   -> {names, dir}: the prefabs, and the folder they are in
blueprint <conn> put <prefab> <x y z>                      -> {placed, cells, needed}: put down unturned, its anchor there, and kept
blueprint <conn> turn|mirror X|Y|Z                         -> the panel's turn / mirror of the one put down
blueprint <conn> move <dx dy dz>                           -> the panel's move of it
blueprint <conn> placed                                    -> {placed, at, cells, needed[, blocks]}: the one put down, or the kept one put down again
blueprint <conn> forget                                    -> the kept one forgotten
blueprint <conn> build                                     -> the one put down (or kept): {status, placed, broken, scaffolds, left, lacking, poured, copies}
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
- **Water flowing back** into a cleared cell from a source outside the blueprint.
