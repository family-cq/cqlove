package com.cqgzs.cqlove.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.cqgzs.cqlove.magic.MagicElement;
import com.cqgzs.cqlove.magic.MagicRank;
import com.cqgzs.cqlove.magic.MagicTalent;
import com.cqgzs.cqlove.magic.SkillDefinition;
import com.cqgzs.cqlove.magic.SkillRegistry;
import com.cqgzs.cqlove.network.MagicDataPayload;

/**
 * 客户端魔法数据缓存（服务端每推一次 {@link MagicDataPayload} 就整体覆盖）。
 *
 * <p>冷却在这里按 tick 自行递减，不用服务端每 tick 同步；
 * 真正判定仍然以服务端为准（客户端只负责显示）。</p>
 */
public final class MagicClientData {

    private static int rank;
    private static double mana;
    private static double maxMana;
    private static int talentPoints;
    private static List<MagicElement> awakened = List.of();
    private static final Map<MagicElement, String> selected = new EnumMap<>(MagicElement.class);
    private static final Map<String, Integer> cooldowns = new HashMap<>();
    private static int[] talents = new int[MagicTalent.values().length];
    /** 已学习技能 id（P 界面技能树 / G 轮盘显示用） */
    private static final java.util.Set<String> learned = new java.util.HashSet<>();
    /**
     * ZXCV 两段式施法：当前已"选中（上膛）"的槽位，-1 = 未选中。
     * 第一次按某个槽位只是选中，再按同一槽位才释放（需求：先选后放）。
     * 纯客户端状态，不参与网络同步。
     */
    private static int armedSlot = -1;

    private MagicClientData() {
    }

    /** 收到服务端快照 */
    public static void accept(MagicDataPayload payload) {
        rank = payload.rank();
        mana = payload.mana();
        maxMana = payload.maxMana();
        talentPoints = payload.talentPoints();

        // 已觉醒系列
        List<MagicElement> elements = new ArrayList<>();
        if (!payload.awakened().isEmpty()) {
            for (String id : payload.awakened().split(",")) {
                MagicElement element = MagicElement.byId(id.trim());
                if (element != null) {
                    elements.add(element);
                }
            }
        }
        awakened = List.copyOf(elements);

        // 每个系列选中的技能
        selected.clear();
        if (!payload.selected().isEmpty()) {
            for (String pair : payload.selected().split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                MagicElement element = MagicElement.byId(pair.substring(0, eq).trim());
                if (element != null) {
                    selected.put(element, pair.substring(eq + 1).trim());
                }
            }
        }

        // 冷却
        cooldowns.clear();
        if (!payload.cooldowns().isEmpty()) {
            for (String pair : payload.cooldowns().split(";")) {
                int bar = pair.indexOf('|');
                if (bar <= 0) {
                    continue;
                }
                try {
                    cooldowns.put(pair.substring(0, bar).trim(), Integer.parseInt(pair.substring(bar + 1).trim()));
                } catch (NumberFormatException ignored) {
                    // 忽略坏数据
                }
            }
        }

        // 天赋
        talents = new int[MagicTalent.values().length];
        if (!payload.talents().isEmpty()) {
            String[] parts = payload.talents().split(",");
            for (int i = 0; i < parts.length && i < talents.length; i++) {
                try {
                    talents[i] = Integer.parseInt(parts[i].trim());
                } catch (NumberFormatException ignored) {
                    talents[i] = 0;
                }
            }
        }

        // 已学习技能
        learned.clear();
        if (!payload.learned().isEmpty()) {
            for (String id : payload.learned().split(",")) {
                if (!id.isBlank()) {
                    learned.add(id.trim());
                }
            }
        }
    }

    /** 每 client tick：冷却递减 */
    public static void tick() {
        if (cooldowns.isEmpty()) {
            return;
        }
        cooldowns.replaceAll((id, ticks) -> ticks - 1);
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= 0);
    }

    /** 退出世界时清空 */
    public static void clear() {
        rank = 0;
        mana = 0.0D;
        maxMana = 0.0D;
        talentPoints = 0;
        awakened = List.of();
        selected.clear();
        cooldowns.clear();
        talents = new int[MagicTalent.values().length];
        learned.clear();
        armedSlot = -1;
    }

    /** 当前已选中的槽位（-1 = 未选中） */
    public static int armedSlot() {
        return armedSlot;
    }

    /** 选中某个槽位（ZXCV 第一次按） */
    public static void armedSlot(int slot) {
        armedSlot = slot;
    }

    /** 当前选中槽位对应的系列，未选中或槽位无效时返回 null（G 轮盘按系列过滤用） */
    public static @Nullable MagicElement armedElement() {
        return elementAt(armedSlot);
    }

    public static MagicRank rank() {
        return MagicRank.byOrdinal(rank);
    }

    public static double mana() {
        return mana;
    }

    public static double maxMana() {
        return maxMana;
    }

    public static int talentPoints() {
        return talentPoints;
    }

    public static List<MagicElement> awakened() {
        return awakened;
    }

    public static int talentLevel(MagicTalent talent) {
        int index = talent.ordinal();
        return index < talents.length ? talents[index] : 0;
    }

    /** 某槽位绑定的系列，没有则返回 null */
    public static @Nullable MagicElement elementAt(int slot) {
        return slot >= 0 && slot < awakened.size() ? awakened.get(slot) : null;
    }

    /** 某系列当前选中的技能定义（未同步到则退回第一个已学技能不可知，返回 null） */
    public static @Nullable SkillDefinition selectedSkill(MagicElement element) {
        String id = selected.get(element);
        if (id == null) {
            return null;
        }
        return SkillRegistry.byId(id);
    }

    /** 某技能剩余冷却秒数 */
    public static double cooldownSeconds(@Nullable String skillId) {
        if (skillId == null) {
            return 0.0D;
        }
        Integer ticks = cooldowns.get(skillId);
        return ticks == null ? 0.0D : ticks / 20.0D;
    }

    /**
     * 本地先记下玩家刚选的技能（乐观更新），服务端同步包回来后会整体覆盖。
     * 这样 HUD / P 界面点击后能立刻看到变化，不用等服务端一个来回。
     */
    public static void applyLocalSelection(MagicElement element, String skillId) {
        selected.put(element, skillId);
    }

    /** 该技能是否已学习（P 界面 / G 轮盘用） */
    public static boolean isLearned(@Nullable String skillId) {
        return skillId != null && learned.contains(skillId);
    }

    /** 某系列已学习的技能（按技能树顺序） */
    public static List<SkillDefinition> learnedSkills(MagicElement element) {
        List<SkillDefinition> result = new ArrayList<>();
        for (SkillDefinition skill : SkillRegistry.of(element)) {
            if (learned.contains(skill.id())) {
                result.add(skill);
            }
        }
        return result;
    }

    /** 所有已觉醒系列的全部已学技能（按觉醒顺序排，G 轮盘用） */
    public static List<SkillDefinition> allLearnedSkills() {
        List<SkillDefinition> result = new ArrayList<>();
        for (MagicElement element : awakened) {
            result.addAll(learnedSkills(element));
        }
        return result;
    }
}
