package meridian.blueprint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import meridian.core.api.BlockView;
import meridian.core.api.Containers;
import meridian.core.api.PlayerState;
import meridian.core.api.WorldState;

/**
 * What a blueprint takes to build: each block as the item placed for it - the block's family, as
 * building places it - and how many, against what the player carries.
 */
final class Materials {

    /** Where what the player carries is: as building takes blocks from. */
    private static final List<String> CARRIED = List.of("Hotbar", "Storage", "Backpack");

    private Materials() {
    }

    /** Each item the blueprint's blocks take, and how many - the most first. Air is no item. */
    static Map<String, Integer> needed(Blueprint bp, WorldState worldState) {
        Map<Integer, BlockView> types = new HashMap<>();
        for (BlockView t : worldState.allBlockTypes()) {
            types.put(t.id(), t);
        }
        Map<String, Integer> count = new HashMap<>();
        for (int i = 0; i < bp.size; i++) {
            if (bp.blockId[i] == 0) {
                continue;
            }
            BlockView t = types.get(bp.blockId[i]);
            if (t == null) {
                continue;
            }
            BlockView fam = types.getOrDefault(t.family(), t);
            count.merge(fam.name(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(count.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : sorted) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** How many of each item the player carries; empty where nothing tells. */
    static Map<String, Integer> carried(PlayerState state) {
        Map<String, Integer> out = new HashMap<>();
        if (state == null) {
            return out;
        }
        for (Map.Entry<String, PlayerState.Section> e : state.inventories().entrySet()) {
            if (!CARRIED.contains(e.getKey())) {
                continue;
            }
            for (Containers.Item it : e.getValue().items().values()) {
                if (it != null && it.id() != null && it.quantity() > 0) {
                    out.merge(it.id(), it.quantity(), Integer::sum);
                }
            }
        }
        return out;
    }
}
