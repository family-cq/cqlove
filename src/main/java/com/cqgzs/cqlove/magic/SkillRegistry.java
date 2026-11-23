package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 技能注册表：18 个系列的完整技能树（需求文档 5.1.1）。
 *
 * <p>技能 id 规则：{@code 系列id_阶序_同阶序号}，例如雷系初阶第 1 个技能 = {@code thunder_0_1}。
 * 阶序 0/1/2/3 = 初阶/中阶/高阶/超阶。</p>
 *
 * <p>文档中只有部分技能写了具体效果，其余先按“占位技能”注册，玩家释放时会提示尚未实现，
 * 后续按系列逐个补 {@link #implement} 即可，不用改存档与界面。</p>
 */
public final class SkillRegistry {

    /** id -> 定义（保持注册顺序） */
    private static final Map<String, SkillDefinition> BY_ID = new LinkedHashMap<>();
    /** 系列 -> 该系列全部技能（按阶级与序号排序） */
    private static final Map<MagicElement, List<SkillDefinition>> BY_ELEMENT = new EnumMap<>(MagicElement.class);

    /** 未实现技能的默认行为 */
    private static final SkillAction PLACEHOLDER = ctx -> NoticeApi.send(ctx.player(), 
            Component.literal("技能【" + ctx.skill().name() + "】尚未实现, 敬请期待").withStyle(ChatFormatting.GRAY));

    private SkillRegistry() {
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public static @Nullable SkillDefinition byId(String id) {
        return BY_ID.get(id);
    }

    /** 某系列的全部技能（按阶、按序号） */
    public static List<SkillDefinition> of(MagicElement element) {
        return BY_ELEMENT.getOrDefault(element, List.of());
    }

    public static List<SkillDefinition> all() {
        return List.copyOf(BY_ID.values());
    }

    /** 某系列某阶的技能（用于界面按阶分组显示） */
    public static List<SkillDefinition> of(MagicElement element, SkillTier tier) {
        List<SkillDefinition> result = new ArrayList<>();
        for (SkillDefinition skill : of(element)) {
            if (skill.tier() == tier) {
                result.add(skill);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /**
     * 定义一个系列的技能树。
     *
     * @param tierNames  四阶的组名，例如“雷印/霹雳/寂雷死光/雷神降临”
     * @param skillNames 四阶各自的小技能名；某阶为 null 或空表示该阶只有一个同名技能
     */
    private static void series(MagicElement element, String[] tierNames, String[][] skillNames) {
        for (int tier = 0; tier < 4; tier++) {
            String[] names = skillNames[tier];
            List<String> list = new ArrayList<>();
            if (names == null || names.length == 0) {
                // 文档里没写小技能的（如“雷神降临”“复苏”），整阶就一个技能，名字用组名
                list.add(tierNames[tier]);
            } else {
                for (String name : names) {
                    if (name != null && !name.isEmpty()) {
                        list.add(name);
                    }
                }
            }
            for (int i = 0; i < list.size(); i++) {
                int index = i + 1;
                String id = element.id() + "_" + tier + "_" + index;
                String description = resolveDescription(id);
                SkillDefinition definition = new SkillDefinition(
                        id, element, SkillTier.byOrdinal(tier), index, tierNames[tier], list.get(i), description, PLACEHOLDER);
                BY_ID.put(id, definition);
                BY_ELEMENT.computeIfAbsent(element, key -> new ArrayList<>()).add(definition);
            }
        }
    }

    /** 查技能描述：按系列分表，找不到就用占位文案 */
    private static String resolveDescription(String id) {
        String description = DESCRIPTIONS.get(id);
        if (description == null) {
            description = WATER_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = LIGHT_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = EARTH_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = ICE_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = MIND_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = HEAL_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = POISON_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = BLESS_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = SOUND_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = CURSE_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = PLANT_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = UNDEAD_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = SUMMON_DESCRIPTIONS.get(id);
        }
        if (description == null) {
            description = SPACE_DESCRIPTIONS.get(id);
        }
        return description == null ? "技能效果待补充" : description;
    }

    /** 把某个技能替换成真实实现 */
    private static void implement(String id, SkillAction action) {
        SkillDefinition old = BY_ID.get(id);
        if (old == null) {
            throw new IllegalStateException("未定义的技能 id: " + id);
        }
        SkillDefinition replaced = new SkillDefinition(old.id(), old.element(), old.tier(), old.indexInTier(),
                old.tierName(), old.name(), old.description(), action);
        BY_ID.put(id, replaced);
        List<SkillDefinition> list = BY_ELEMENT.get(old.element());
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                list.set(i, replaced);
                break;
            }
        }
    }

    /** 18 个系列的技能树（名字全部取自需求文档，空技能节点用组名占位） */
    private static void defineAllSeries() {
        series(MagicElement.THUNDER,
                new String[]{"雷印", "霹雳", "寂雷死光", "雷神降临"},
                new String[][]{
                        {"蟒痕", "狂策", "怒击"},
                        {"轰顶", "夜叉", "狂舞"},
                        null,
                        null});

        series(MagicElement.FIRE,
                new String[]{"火滋", "烈拳", "天炎葬礼", "火神焚天"},
                new String[][]{
                        {"灼烧", "焚骨", "爆裂"},
                        {"轰天", "地煞", "九宫"},
                        null,
                        null});

        series(MagicElement.WIND,
                new String[]{"风轨", "风盘", "风之翼", "泯风千刃"},
                new String[][]{
                        {"疾行", "闪步", "飘影"},
                        {"龙卷", "天罗", "撕裂"},
                        null,
                        null});

        series(MagicElement.WATER,
                new String[]{"水御", "爆浪", "水花天幕", "水之猎刃"},
                new String[][]{
                        {"化解", "守护", "循环"},
                        {"驱逐", "惊涛", "洪卷"},
                        null,
                        null});

        series(MagicElement.LIGHT,
                new String[]{"光耀", "光佑", "光落漫丈", "圣光大剑"},
                new String[][]{
                        {"净化", "闪烁", "失明"},
                        {"圣盾", "画壁", "守护"},
                        null,
                        null});

        series(MagicElement.EARTH,
                new String[]{"地波", "岩障", "岩魔之瞳", "角斗场"},
                new String[][]{
                        {"挪移", "迟缓", "陷落"},
                        {"嶙石", "山屏", "石盾"},
                        null,
                        null});

        series(MagicElement.ICE,
                new String[]{"冰漫", "冰锁", "冰封灵柩", "云冰晶界"},
                new String[][]{
                        {"冻结", "凝结", "覆盖"},
                        {"碾骨", "庞莽", "围城"},
                        null,
                        null});

        series(MagicElement.MIND,
                new String[]{"心灵", "心灵之涟", "摄魄控心", "永恒幻想"},
                new String[][]{
                        {"安抚", "冲击", "心灵之墙"},
                        {"恐惧", "躁动", "无畏"},
                        null,
                        null});

        series(MagicElement.POISON,
                new String[]{"毒萃", "毒穴", "毒变", "万噬之风"},
                new String[][]{
                        {"毒牙", "吐纳", "炼化"},
                        {"自爆毒", "泛发感染", "寄生毒"},
                        null,
                        null});

        series(MagicElement.HEAL,
                new String[]{"不药而愈", "治愈精灵", "宝瓶星芒座", "复苏"},
                new String[][]{
                        {"愈合", "净化", "生机"},
                        {"幼芽", "环舞", "群愈"},
                        null,
                        null});

        series(MagicElement.SUMMON,
                new String[]{"召唤空间", "契约空间", "召唤之门", "上古魔门"},
                new String[][]{null, null, null, null});

        series(MagicElement.SOUND,
                new String[]{"音弦", "杀乐", "断葬曲", "曲终帝陨"},
                new String[][]{
                        {"杀", "扰", "探"},
                        {"弦", "弓", "爆"},
                        null,
                        null});

        series(MagicElement.SHADOW,
                new String[]{"潜行", "巨影钉", "司夜统治", "虚暗刑场"},
                new String[][]{
                        {"穿梭", "虚化", "飞影"},
                        {"禁锢", "双子", "梨刺"},
                        null,
                        null});

        series(MagicElement.PLANT,
                new String[]{"藤蔓", "坤之林", "鬼木手", "移花接木"},
                new String[][]{
                        {"生长", "追袭", "束缚"},
                        {"囚牢", "疯长", "绽放"},
                        null,
                        null});

        series(MagicElement.SPACE,
                new String[]{"念控", "念攻", "瞬移", "空间死轴"},
                new String[][]{
                        {"吸取", "移动", "破坏"},
                        {"切割", "空间斩", "空间风暴"},
                        null,
                        null});

        series(MagicElement.BLESS,
                new String[]{"祝福", "祝喃", "神印礼赞", "众星守护"},
                new String[][]{
                        {"强化", "圣音", "灵力"},
                        {"强化", "圣音", "灵力"},
                        null,
                        null});

        series(MagicElement.UNDEAD,
                new String[]{"亡骸", "亡灵", "亡界", "亡灵天灾"},
                new String[][]{
                        {"亡灵生成", "肉体构造", "亡灵空间"},
                        {"招魂术", "无体之灵", "亡灵杀手"},
                        null,
                        null});

        series(MagicElement.CURSE,
                new String[]{"恐惧之霾", "邪蛛之阱", "鬼刑", "拘灵遣将"},
                new String[][]{
                        {"寒", "压", "伤"},
                        {"魂蜘", "天涯", "灭魂"},
                        null,
                        null});
    }

    /** 已实现技能的描述（需求文档原文摘要） */
    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            // 雷系·初阶
            Map.entry("thunder_0_1", "从手上释放 1 条雷电到鼠标指向的位置，超出施法范围按最大范围计算；命中生物造成 3 点魔法伤害。"),
            Map.entry("thunder_0_2", "连续释放 3 条雷电，每条 3 点伤害；面前有多只生物时会分别命中不同目标。"),
            Map.entry("thunder_0_3", "从天空落下一道雷电砸向鼠标指向的位置，命中生物造成 9 点魔法伤害。"),
            // 雷系·中阶（霹雳）
            Map.entry("thunder_1_1", "从天空连续落下 5 条雷电砸向鼠标位置，每条 9 点；5 只以内生物分别命中，无生物则砸方块 5 次。"),
            Map.entry("thunder_1_2", "以鼠标位置及前后左右共 5 格同时落雷，每条 60 点伤害。"),
            Map.entry("thunder_1_3", "同上 5 格落雷，分 3 波、间隔 1 秒，共 15 条雷电，每条 50 点伤害。"),
            // 雷系·高阶/超阶
            Map.entry("thunder_2_1", "以鼠标位置为中心 3×3 共 9 格全部落雷，命中生物造成 300 点伤害。"),
            Map.entry("thunder_3_1", "获得 60 秒雷神 buff：攻击/魔力/蓝量/魔暴伤 +100，免疫雷电/爆炸/火焰/岩浆，攻击附带雷电；仅觉醒雷系时冷却 -50%，buff 结束后进入冷却。"),
            // 火系·初阶（火滋）
            Map.entry("fire_0_1", "从手上释放一个火苗到鼠标指向的位置；命中生物造成 3 点伤害。"),
            Map.entry("fire_0_2", "火苗命中生物造成 6 点伤害，并灼烧 3 秒（每秒 1 点）；水中或雨天灼烧不生效。"),
            Map.entry("fire_0_3", "火苗命中即爆炸，造成 9 点范围伤害并灼烧 3 秒；水中或雨天灼烧不生效。"),
            // 火系·中阶（烈拳）
            Map.entry("fire_1_1", "火焰拳头命中爆炸，造成 30 点范围伤害，并点燃爆炸范围内的方块；水中或雨天不点燃。"),
            Map.entry("fire_1_2", "落点生成 3×3 岩浆地表，持续 6 秒，范围内生物每秒受到 10 点灼烧；水中生成气泡柱，雨天蒸发。"),
            Map.entry("fire_1_3", "命中爆炸造成 90 点伤害；2 秒后以落点为中心 8 个方位同时二次爆炸，每次 90 点。"),
            // 火系·高阶/超阶
            Map.entry("fire_2_1", "从头顶 10 格落下 9 个火焰石头砸向鼠标位置，每个 300 点伤害；多个生物各自分摊，剩余落在目标点。"),
            Map.entry("fire_3_1", "获得 60 秒火神 buff：攻击/魔力/蓝量/魔暴伤 +100，免疫爆炸/岩浆/火焰，攻击附带爆炸，清除周围水。buff 结束后进入冷却。"),
            // 风系·初阶（风轨）
            Map.entry("wind_0_1", "玩家速度增加 3，持续 3 秒；身边青色粒子，移动时身后带青色长条光晕拖尾；使用期间技能锁定，结束后才进冷却。"),
            Map.entry("wind_0_2", "玩家速度增加 6，持续 3 秒；其余同疾行。"),
            Map.entry("wind_0_3", "玩家速度增加 9，持续 3 秒；其余同疾行。"),
            // 风系·中阶（风盘）
            Map.entry("wind_1_1", "从手上射出青色光晕到鼠标位置，落地生成 1×2×1 旋风，吸扯半径 2 内的生物与物品，持续 5 秒，每秒 9 点伤害。"),
            Map.entry("wind_1_2", "同上，生成 2×4×2 旋风，吸扯半径 4，持续 6 秒，每秒 10 点伤害。"),
            Map.entry("wind_1_3", "同上，生成 3×9×3 旋风，吸扯半径 8，持续 7 秒，每秒 15 点伤害；旋风期间随机露出撕裂空间。"),
            // 风系·高阶/超阶
            Map.entry("wind_2_1", "被动：背后生成六翼翅膀，获得创造模式飞行；主动：4 面青色风盾环绕旋转，护甲/魔抗 +50，持续 30 秒，期间技能锁定，结束后进冷却。"),
            Map.entry("wind_3_1", "身边生成 32 个迷你旋风；发现敌对生物或你攻击过的生物时每 0.25 秒飞出一个追踪，命中爆炸 500 点；目标死亡则飞回；32 个全部飞出后进冷却。"));

    /** 水系技能描述（需求文档原文摘要） */
    private static final Map<String, String> WATER_DESCRIPTIONS = Map.ofEntries(
            Map.entry("water_0_1", "对准生物释放（没有生物不释放）：解除该生物全部状态效果（牛奶效果）；"
                    + "按住 Ctrl 释放时改为解除自身的；蓝白色光晕拖尾到目标身上。"),
            Map.entry("water_0_2", "对准生物释放：该生物生成 1 面水盾（圆形盾牌，中心向外散发白色波纹），"
                    + "抵挡 1 次魔法攻击，挡不住物理攻击；盾可一直存在、可无限叠加。"),
            Map.entry("water_0_3", "对准生物释放：该生物生成 4 面环身水盾，持续 30 秒，抵挡任意魔法攻击（挡不住物理），"
                    + "期间技能锁定，30 秒结束后进入冷却。"),
            Map.entry("water_1_1", "朝面前生成 3×2 浪墙贴地推进，碰到生物向前推动并造成 30 点伤害；到施法极限范围消失。"),
            Map.entry("water_1_2", "朝面前生成 6×3 浪墙贴地推进，碰到生物向前推动并造成 60 点伤害；到施法极限范围消失。"),
            Map.entry("water_1_3", "朝面前生成 3×9 水龙卷推进，碰到生物向前推动并把生物吸向中心，每秒 30 点伤害；"
                    + "到施法极限范围停顿 1 秒后消失。"),
            Map.entry("water_2_1", "在身前 3 格立起 10×10 水墙，持续 30 秒：替施法者吸收魔法伤害、清掉附近 3 格内的弹射物，"
                    + "范围内其他生物每秒受到 100 点伤害；期间技能锁定，30 秒结束后进入冷却。"),
            Map.entry("water_3_1", "身边生成 8 把水弯刀绕身旋转；发现敌对生物或你攻击过的生物时 8 把同时飞出攻击（可分散也可集中同一目标），"
                    + "每次命中 500 点伤害，命中后飞回身边继续下一轮，直到周围没有可追击的目标；持续 1 分钟后消失并进入冷却。"));

    /** 光系技能描述 */
    private static final Map<String, String> LIGHT_DESCRIPTIONS = Map.ofEntries(
            Map.entry("light_0_1", "解除自身全部负面状态效果，并回复 6 点生命；脚下升起金色光尘。"),
            Map.entry("light_0_2", "朝面向方向扔出一颗光球（没有伤害）：光球撞到方块就停下，"
                    + "在落点悬停 60 秒持续照亮周围（相当于 15 级光源）；释放后立即进入冷却，无需锁定。"),
            Map.entry("light_0_3", "对准生物释放：造成 9 点光伤害，并使目标失明 5 秒（同时发光，便于集火）。"),
            Map.entry("light_1_1", "20 秒内受到的所有伤害 -40%，4 面金色光盾环绕身周；结束后进入冷却。"),
            Map.entry("light_1_2", "在身前 3 格立起 6×4 光之壁垒，持续 10 秒：推开靠近的生物（两侧都推），"
                    + "并销毁敌方弹射物；结束后进入冷却。"),
            Map.entry("light_1_3", "对准生物释放（按住 Ctrl 释放可给自己）：立即回复 60 点生命，并获得 10 秒再生 II。"),
            Map.entry("light_2_1", "在鼠标位置降下 6×12 巨型光柱，持续 5 秒：范围内敌对生物每秒受到 150 点光伤害并失明。"),
            Map.entry("light_3_1", "对准生物释放：1.5 秒前摇内金色粒子将其围住且无法移动，头顶降下超巨大圣剑砸向脚下地面，"
                    + "落点 5 格内所有生物受到 1000 点光系魔法伤害并失明 5 秒；圣剑消失后技能才开始冷却。"));

    /** 土系技能描述 */
    private static final Map<String, String> EARTH_DESCRIPTIONS = Map.ofEntries(
            Map.entry("earth_0_1", "朝面向方向在 1 秒内推进 8 格（不是瞬移，有连续滑行的画面）；"
                    + "途中碰到生物不停下，撞到方块才停。"),
            Map.entry("earth_0_2", "对准生物释放：附加 10 秒迟缓，并造成 6 点土系魔法伤害。"),
            Map.entry("earth_0_3", "对准生物释放：将其固定在原地 5 秒无法移动，并造成 9 点土系魔法伤害。"),
            Map.entry("earth_1_1", "前摇后地面升起 2×3×2 的小山，碰到生物造成 30 点伤害；"
                    + "3 秒后小山沉回地下，技能开始冷却。"),
            Map.entry("earth_1_2", "前摇后四根石柱向两侧展开，中间铺开半透明光屏；持续 10 秒，"
                    + "把范围内的生物向两侧推开并造成 60 点伤害；前摇结束即开始冷却。"),
            Map.entry("earth_1_3", "按住 Ctrl 释放 = 给自己加盾；对准生物释放 = 给该生物加盾。"
                    + "4 面灰色石盾环绕：物理伤害 -60%、魔法伤害 -40%，持续 10 秒，结束后进入冷却。"),
            Map.entry("earth_2_1", "前摇后石之眼从眼前推出 1 格：前方范围内所有敌对生物石化 30 秒 —— "
                    + "无法移动、失明，物理防御 -90%、魔法防御 -60%；30 秒后进入冷却。"),
            Map.entry("earth_3_1", "锁定生物：以你为中心砌出半径 9、高 4 的原版石头围墙（永久保留），"
                    + "其它生物全部被推出去；被锁定的生物攻击/魔力/物防/魔防 -50%，你攻/魔力/物防/魔防 +20%，"
                    + "持续 60 秒；锁定目标死亡则效果立即结束，然后进入冷却。"));

    /** 冰系 8 技能描述（2026-11-14 设计：冰漫 / 冰锁 / 冰封灵柩 / 云冰晶界） */
    private static final Map<String, String> ICE_DESCRIPTIONS = Map.ofEntries(
            Map.entry("ice_0_1", "对准生物释放：冰封定身 3 秒，附加缓慢 III，并造成 3 点冰系魔法伤害。"),
            Map.entry("ice_0_2", "射出一根冰锥：命中生物造成 6 点冰系魔法伤害，并缓慢 3 秒；撞到方块碎裂。"),
            Map.entry("ice_0_3", "在前方地面铺 5×3 冰面，持续 10 秒：踩上去打滑；敌对生物踏上时缓慢 5 秒并受到 9 点冰系伤害；"
                    + "到期自动融化还原。"),
            Map.entry("ice_1_1", "对准生物释放：冰锁缠身定身 5 秒，期间每秒 8 点冰系伤害；"
                    + "冰锁碎裂时再爆发 15 点，然后进入冷却。"),
            Map.entry("ice_1_2", "召出立体的冰之巨蟒（正面蛇头在前、三节冰鳞躯干拖后）沿视线冲出 8 格："
                    + "沿途每个生物 35 点冰系伤害、被顶开并缓慢 4 秒；撞墙即碎。"),
            Map.entry("ice_1_3", "在准星指向处强制升起 5 宽 × 3 高的冰墙（覆盖原有地形，到期还原），挡 15 秒："
                    + "墙周围 2 格的生物每秒受 10 点冰系伤害；前摇结束即开始冷却。"),
            Map.entry("ice_2_1", "锁定生物后左右两面冰墙向中间靠拢合拢成冰棺：定身 30 秒，期间每秒 15 点冰系伤害、"
                    + "受到的所有伤害 +30%；30 秒后封棺爆碎再造成 100 点。生物中途消失冰块仍保留到技能结束。"),
            Map.entry("ice_3_1", "以施法点为中心展开半径 = 施法范围（超阶 30 格）的冰晶领域，持续 60 秒：领域内持续落雪；"
                    + "敌对生物缓慢 60 秒、每秒 12 点冰系伤害，连续停留 3 秒冻结 2 秒。"
                    + "领域期间自身攻击/魔力/蓝量/魔暴击伤害各 +100，免疫爆炸 / 缓慢 / 冰冻 / 失明，"
                    + "所有技能施法时间与冷却时间 -50%。"));

    /** 心灵系 8 技能描述（2026-11-14 需求：心灵 / 心灵之涟 / 摄魄控心 / 永恒幻想） */
    private static final Map<String, String> MIND_DESCRIPTIONS = Map.ofEntries(
            Map.entry("mind_0_1", "脚下展开白色圆圈（直径 = 当前施法范围）：圈内生物（含敌对生物）全部变成友好生物，"
                    + "不再攻击任何人，持续 30 秒；30 秒后进入冷却。"),
            Map.entry("mind_0_2", "脚下展开蓝色圆圈（直径 = 当前施法范围）：圈内敌对生物物理防御、魔法防御各 -10%，"
                    + "持续 30 秒；30 秒后进入冷却。"),
            Map.entry("mind_0_3", "用粒子墙把准星生物围在原地：该生物无法释放魔法攻击（造成的伤害被吞掉），"
                    + "魔法防御 -20%，持续 30 秒；30 秒后进入冷却。"),
            Map.entry("mind_1_1", "脚下展开灰色圆圈（直径 = 当前施法范围）：圈内敌对生物缓慢 + 失明，"
                    + "每秒 8 点心灵系魔法伤害，持续 30 秒；30 秒后进入冷却。"),
            Map.entry("mind_1_2", "脚下展开红色圆圈（直径 = 当前施法范围）：圈内敌对生物每秒 10 点心灵系魔法伤害、"
                    + "物理防御 -30%、魔法防御 -50%，持续 30 秒；30 秒后进入冷却。"),
            Map.entry("mind_1_3", "脚下展开金色圆圈（直径 = 当前施法范围）：圈内友好生物与玩家获得速度、幸运，"
                    + "攻击力 +12、魔力值 +12、物理防御 +10%、魔法防御 +20%，持续 30 秒；30 秒后进入冷却。"),
            Map.entry("mind_2_1", "用一串红色符文连成线锁住敌对生物 40 秒：按住 Alt 时右键让它朝准星位置移动、"
                    + "左键让它攻击准星生物。生物中途死亡则技能立即进入冷却；40 秒到还活着则原地爆裂，"
                    + "对该生物造成 300 点爆炸伤害（周围敌对生物溅射 150 点，不破坏地形），然后进入冷却。"),
            Map.entry("mind_3_1", "展开专属白金色结界跟随自身 60 秒：物理防御 +50%、魔法防御 +100%，"
                    + "免疫火焰 / 爆炸 / 中毒 / 失明 / 岩浆 / 雷电，魔力值 +100、魔暴击 +80%、"
                    + "魔暴击伤害 +200%；60 秒后进入冷却。"));

    /** 治愈系 8 技能描述（2026-11-14 设计：不药而愈 / 治愈精灵 / 宝瓶星芒座 / 复苏） */
    private static final Map<String, String> HEAL_DESCRIPTIONS = Map.ofEntries(
            Map.entry("heal_0_1", "对准友方释放（按住 Ctrl 或没指到友方时作用于自己）：立即回复 8 点生命并附加 4 秒生命恢复 II；"
                    + "指向亡灵生物时改为造成 3 点治愈系魔法伤害。"),
            Map.entry("heal_0_2", "对准友方释放（按住 Ctrl 给自己）：清除全部负面状态、回复 4 点生命，"
                    + "并在 10 秒内持续压掉新挂上的负面效果。"),
            Map.entry("heal_0_3", "对准友方释放（按住 Ctrl 或没指到友方时作用于自己）：30 秒内每 2 秒回复 3 点生命、"
                    + "物理/魔法防御 +10%，立即获得 30 秒伤害吸收 I。"),
            Map.entry("heal_1_1", "召出 1 只治愈精灵跟随自身 20 秒，每秒治疗半径 6 格内血量最低的 1 名友方 10 点生命；"
                    + "期间技能锁定，结束后进入冷却。"),
            Map.entry("heal_1_2", "召出 2 只治愈精灵环绕自身 25 秒，每秒治疗半径 8 格内全部友方 15 点生命并驱散负面；"
                    + "期间技能锁定，结束后进入冷却。"),
            Map.entry("heal_1_3", "召出 3 只治愈精灵跟随自身 30 秒，每秒治疗半径 12 格内全部友方 20 点生命并附加再生 I，"
                    + "范围内友方物理/魔法防御 +20%；期间技能锁定，结束后进入冷却。"),
            Map.entry("heal_2_1", "展开跟随自身的宝瓶星芒座 20 秒，半径 = 当前施法范围：圈内友方每秒回复 100 点生命、"
                    + "驱散负面并获得再生 I，范围内亡灵每秒受到 100 点治愈系魔法伤害，友方物理/魔法防御 +20%；"
                    + "结束后进入冷却。"),
            Map.entry("heal_3_1", "展开跟随自身的复苏结界 60 秒（脚下超阶圆环一直亮到进入冷却）：圈内友方每秒回复 500 点生命、"
                    + "亡灵每秒受到 500 点治愈系魔法伤害，驱散全部负面、获得再生 II，物理/魔法防御 +50%，"
                    + "结界内其它来源的治疗量 +50%；"
                    + "每名友方拥有一次「受到致死伤害时取消该伤害并恢复到 50% 最大生命」。60 秒后进入冷却。"));

    /** 毒系 8 技能描述（2026-11-14 设计：毒萃 / 毒穴 / 毒变 / 万噬之风） */
    private static final Map<String, String> POISON_DESCRIPTIONS = Map.ofEntries(
            Map.entry("poison_0_1", "对准敌对生物释放（准星没命中时取落点附近最近的敌对）：立即造成 8 点毒系魔法伤害，"
                    + "并附加 8 秒腐蚀，每秒 4 点毒系伤害，期间目标物理/魔法防御 -10%。"),
            Map.entry("poison_0_2", "在准星落点呼出一团毒雾云持续 10 秒（半径随施法范围提升，3~6 格）："
                    + "范围内敌对每秒受到 6 点毒系伤害并获得中毒 I、缓慢 I；期间技能锁定，结束后进入冷却。"),
            Map.entry("poison_0_3", "30 秒内自身免疫中毒 / 凋零 / 饥饿 / 反胃 / 失明 / 缓慢 / 虚弱与毒系伤害，"
                    + "毒系伤害 +30%，每秒对身边 3~5 格内敌对造成 5 点毒系伤害；期间技能锁定，结束后进入冷却。"),
            Map.entry("poison_1_1", "对准星敌对生物植入毒爆种子 8 秒，每秒 5 点毒系伤害；"
                    + "到期或目标提前死亡时在原地爆开，3 格内敌对受到 30 点毒系伤害并中毒 II 6 秒（不破坏地形）。"),
            Map.entry("poison_1_2", "对准星敌对生物施加感染 12 秒，每秒 8 点毒系伤害并附加虚弱；"
                    + "每 2 秒向 4 格内最多 2 只未感染的敌对生物传播，最多 3 跳，每跳伤害 ×0.8。"),
            Map.entry("poison_1_3", "对准星敌对生物植入寄生体 20 秒，每秒 12 点毒系伤害并为施法者回复 6 点生命，"
                    + "目标获得缓慢 II 与虚弱 II；宿主死亡时施法者回复 20 点生命，并在原地留下 3 格 / 6 秒毒云。"),
            Map.entry("poison_2_1", "展开跟随自身的毒变领域 20 秒，半径 = 当前施法范围：圈内敌对每秒受到 40 点毒系伤害、"
                    + "获得中毒 III 与缓慢 I，物理/魔法防御 -30%；自身免疫负面且毒系伤害 +50%；结束后进入冷却。"),
            Map.entry("poison_3_1", "展开跟随自身的暗绿结界 60 秒（半径 14 格，脚下超阶圆环一直亮到进入冷却）："
                    + "圈内敌对每秒受到 150 点毒系伤害，每存活 1 秒叠加 1 层「噬毒」（最多 10 层，每层 +15 点，"
                    + "满层额外 100 点引爆后重置），物理/魔法防御 -50% 并缓慢 II；圈内友方每秒回复 30 点生命；"
                    + "自身免疫负面且毒系伤害 +100%。60 秒后进入冷却。"));

    /** 祝福系 8 技能描述（2026-11-16 设计：祝福 / 祝喃 / 神印礼赞 / 众星守护） */
    private static final Map<String, String> BLESS_DESCRIPTIONS = Map.ofEntries(
            Map.entry("bless_0_1", "对准友方释放（按住 Ctrl 或没指到友方时作用于自己）：60 秒内攻击力 +12 并获得力量 I。"),
            Map.entry("bless_0_2", "对准友方释放（按住 Ctrl 给自己）：清除全部负面状态，30 秒内获得再生 II、抗性 I，蓝量上限 +10。"),
            Map.entry("bless_0_3", "对准友方释放（按住 Ctrl 给自己）：60 秒内魔力值 +15、魔暴击率 +10 个百分点、蓝量上限 +20。"),
            Map.entry("bless_1_1", "展开跟随自身的金色圆圈 30 秒，半径 = 施法范围：圈内友方攻击力 +20、力量 II；期间技能锁定，结束后进入冷却。"),
            Map.entry("bless_1_2", "展开跟随自身的金白圆圈 30 秒，半径 = 施法范围：圈内友方持续驱散负面并获得再生 II、抗性 I、速度 I；期间技能锁定，结束后进入冷却。"),
            Map.entry("bless_1_3", "展开跟随自身的淡金圆圈 30 秒，半径 = 施法范围：圈内友方魔力值 +30、魔暴击率 +15 个百分点、蓝量上限 +40；期间技能锁定，结束后进入冷却。"),
            Map.entry("bless_2_1", "展开跟随自身的神印领域 30 秒，半径 = 施法范围：圈内友方攻击 +40、魔力 +60、魔暴击 +20、魔暴伤 +50、物理/魔法防御 +30%，持续再生与驱散；期间技能锁定，结束后进入冷却。"),
            Map.entry("bless_3_1", "展开跟随自身的众星结界 60 秒（半径 14 格，脚下超阶圆环一直亮到进入冷却）：圈内友方攻击 +100、魔力 +100、魔暴击 +80、魔暴伤 +200、物理/魔法防御 +50%、蓝量上限 +100，持续再生与驱散，并免疫中毒/凋零/饥饿/反胃/失明/缓慢/虚弱及毒系与凋零伤害。60 秒后进入冷却。"));

    /** 音系 8 技能描述（全系范围 + 穿透，2026-11-16 调整） */
    private static final Map<String, String> SOUND_DESCRIPTIONS = Map.ofEntries(
            Map.entry("sound_0_1", "沿视线射出一道穿透音刃：命中路径上全部敌对生物（无数量上限），每个造成 3 点音系伤害并失明 3 秒。"),
            Map.entry("sound_0_2", "在准星落点引发音波震荡：半径 4 格内全部敌对受到 6 点音系伤害、反胃 3 秒并被击退。"),
            Map.entry("sound_0_3", "发出声波脉冲：半径 = 施法范围内的全部敌对受到 9 点音系伤害，范围内所有生物发光显形 10 秒（可揭示隐身）。"),
            Map.entry("sound_1_1", "在准星落点展开范围音弦：半径 5 格内全部敌对均受到 30 点音系伤害并被击退（穿透、无数量上限）。"),
            Map.entry("sound_1_2", "沿视线射出一道贯穿音箭：穿透路径上全部敌对生物（无数量上限），每个受到 60 点音系伤害并被击退（距离 = 施法范围）。"),
            Map.entry("sound_1_3", "在准星落点引发音爆：半径 5 格内全部敌对受到 90 点音系伤害、失明 5 秒并被强力击退。"),
            Map.entry("sound_2_1", "展开跟随自身的音波领域 30 秒，半径 = 施法范围：圈内全部敌对每秒受到 100 点音系伤害并失明、缓慢；期间技能锁定，结束后进入冷却。"),
            Map.entry("sound_3_1", "展开跟随自身的音波结界 60 秒（半径 14 格，脚下超阶圆环一直亮到进入冷却）：圈内全部敌对每秒受到 500 点音系伤害并失明、缓慢，每 5 秒追加一次「帝陨」冲击波额外 200 点并强力击退；期间自身音系技能伤害 +100%。60 秒后进入冷却。"));

    /** 诅咒系 8 技能描述（2026-11-16 设计：恐惧之霾 / 邪蛛之阱 / 鬼刑 / 拘灵遣将） */
    private static final Map<String, String> CURSE_DESCRIPTIONS = Map.ofEntries(
            Map.entry("curse_0_1", "对准星敌对释放寒霜诅咒：造成 9 点诅咒伤害，并附加 8 秒缓慢 II、虚弱 I。"),
            Map.entry("curse_0_2", "在准星落点施展压制诅咒：半径 5 格内敌对受到 6 点伤害，并获得 12 秒虚弱 II 与缓慢 I。"),
            Map.entry("curse_0_3", "对准星敌对施加蚀魂诅咒 12 秒：每秒受到 8 点诅咒伤害。"),
            Map.entry("curse_1_1", "在准星落点布下邪蛛之网 10 秒，半径 5 格：敌对生物被定身，每秒受到 12 点诅咒伤害。"),
            Map.entry("curse_1_2", "在准星落点释放放逐之力：半径 6 格内敌对受到 15 点伤害、失明 5 秒并被强力击退。"),
            Map.entry("curse_1_3", "对准星敌对释放灭魂：立即造成 90 点诅咒伤害，并使其 30 秒内物理/魔法防御 -30%（易伤）。"),
            Map.entry("curse_2_1", "展开跟随自身的鬼刑领域 30 秒，半径 = 施法范围：圈内敌对每秒受到 100 点诅咒伤害、虚弱 II、缓慢 I，且物理/魔法防御 -30%；期间技能锁定，结束后进入冷却。"),
            Map.entry("curse_3_1", "缔结 60 秒灵契（脚下超阶圆环一直亮到进入冷却）：按住 Alt —— 右键吸收准星生物封入灵契（最多 12 只），左键放出一只跟随玩家；放出的灵体会协同攻击玩家正在攻击的同一目标。灵契结束时灵体归还。"));

    /** 植物系 8 技能描述（2026-11-16 设计：藤蔓 / 坤之林 / 鬼木手 / 移花接木） */
    private static final Map<String, String> PLANT_DESCRIPTIONS = Map.ofEntries(
            Map.entry("plant_0_1", "从脚下沿视线方向铺开一条藤蔓带（最长 9 格）：沿途敌对生物受到 20 点植物系伤害并被缠绕 4 秒（缓慢 III + 虚弱）。"),
            Map.entry("plant_0_2", "放出一条贴地追踪的藤蔓：追至准星目标处造成 35 点植物系伤害，缠绕 3 秒并把目标拽向施法者。"),
            Map.entry("plant_0_3", "在准星敌对脚下爆出根系环：造成 10 点伤害并定身 5 秒（缓慢 V，每 tick 压住移动）。"),
            Map.entry("plant_1_1", "在准星落点长出木牢 10 秒（半径 5 格）：圈内敌对每秒受到 25 点植物系伤害，试图逃出边界会被藤蔓拽回。"),
            Map.entry("plant_1_2", "在准星落点疯狂生长出一片植物 8 秒（半径 6 格）：圈内敌对每秒受到 30 点植物系伤害并缓慢 II。"),
            Map.entry("plant_1_3", "在准星落点引爆孢子花：半径 6 格内敌对受到 80 点植物系伤害、中毒 II 与缓慢，持续 8 秒。"),
            Map.entry("plant_2_1", "展开跟随自身的鬼木手领域 30 秒，半径 = 施法范围：巨木手抓住圈内敌对，每秒受到 120 点植物系伤害、被拽向中心并定身 2 秒；期间技能锁定，结束后进入冷却。"),
            Map.entry("plant_3_1", "展开跟随自身的灵树结界 60 秒（半径 12 格，脚下超阶圆环一直亮到进入冷却）：圈内友方每秒回复 20 点生命、敌对每秒受到 200 点植物系伤害并被定身；施法者受到的伤害 -30% 且当次伤害的 50% 立即转为治疗；圈内友方每次施法可免死一次（免死后回到 50% 生命）。60 秒后进入冷却。"));

    /** 亡灵系描述 */
    private static final Map<String, String> UNDEAD_DESCRIPTIONS = Map.ofEntries(
            Map.entry("undead_0_1", "召唤一只骷髅小白协同作战（持弓远程，自动攻击敌对并跟随主人）；召唤物免疫阳光灼烧，按主人单独登记；再次释放收回召唤物，不耗蓝不进冷却；若骷髅死亡则技能进入冷却。所有亡灵仆从优先攻击你刚攻击过的目标，其次攻击你身边的敌对生物；PVP 关闭时己方与他人的召唤物均不可被玩家伤害。"),
            Map.entry("undead_0_2", "召唤一只僵尸协同作战（近战，自动攻击敌对并跟随主人）；召唤物免疫阳光灼烧，按主人单独登记；再次释放收回召唤物，不耗蓝不进冷却；若僵尸死亡则技能进入冷却。"),
            Map.entry("undead_0_3", "召唤 3 只骷髅小白与 3 只僵尸协同作战（免疫阳光、跟随并优先集火你的目标），持续 30 秒后原地爆炸，每只对 3 格内敌对造成 9 点亡灵魔法伤害。"),
            Map.entry("undead_1_1", "召唤一只纯灵体鬼魂：服务端不生成生物实体，只有一层悬浮面片，不会有碰撞、赶路时也不会看到内部恶灵；物理攻击 0、魔法攻击 10、生命值 30，悬浮在地面 1 格高空跟随并施法；鬼魂永久存在并跟随玩家，死亡即消散；鬼魂未死时可叠加召唤，召唤后进入冷却。"),
            Map.entry("undead_1_2", "化身无体之灵 10 秒：免疫近战 / 弹射物等物理伤害，移速 +40%，近战命中额外附加 20 点亡灵魔法伤害；期间周身环绕魂火。"),
            Map.entry("undead_1_3", "召唤最多 3 只强化凋灵骷髅（生命 120、攻击 30）跟随你作战，免疫阳光、按主人单独登记，对不死生物伤害翻倍；优先集火你刚攻击过的目标，其次攻击你身边的敌对生物。召唤物死亡或到期后消散，技能进入冷却。"),
            Map.entry("undead_2_1", "展开跟随自身的亡界领域 30 秒（半径 = 施法范围，无伤害）：圈内敌对每 2 秒叠加一层失明与缓慢（各最多 II）；圈内友方持续获得夜视、生命恢复 II 与抗性 I。结束后进入冷却。"),
            Map.entry("undead_3_1", "展开 60 秒亡灵天灾（半径 = 施法范围）：施法者免疫一切负面状态；范围内亡灵生物获得力量 II / 速度 II / 抗性 I；同时召唤 20 只随机亡灵（骷髅 / 僵尸 / 尸壳 / 流浪者 / 溺尸 / 凋灵骷髅）免疫阳光、跟随作战并优先集火你的目标，死亡的自动随机补召；60 秒结束后所有召唤物爆炸，每只对 4 格内敌对造成 500 点亡灵魔法伤害。"));

    /** 召唤系描述 */
    private static final Map<String, String> SUMMON_DESCRIPTIONS = Map.ofEntries(
            Map.entry("summon_0_1", "召唤一只普通 / 中立生物（狼 / 狐狸 / 北极熊 / 熊猫 / 羊驼 / 蜜蜂 / 山羊 / 铁傀儡）跟随作战，生命、攻击、护甲继承你的一半数值；召唤物按主人单独登记，优先攻击你刚攻击过的目标、其次攻击你身边的敌对生物；PVP 关闭时己方与他人的召唤物均不可被玩家伤害。再次释放收回召唤物（退还耗蓝、不进入冷却）；召唤物死亡时你受到 5% 生命上限的反噬，技能进入冷却。"),
            Map.entry("summon_1_1", "契约视线内任意一只生物，收进契约空间：契约生物被移入独立的地下空间锚点，不会贴身挤撞、拖着玩家走；再次释放可在「放出 / 收回」之间切换，都不耗蓝不冷却。契约生物在原有属性上额外获得你 50% 的生命、攻击、护甲与移速，并优先集火你的目标；契约生物死亡时你受到 10% 生命上限的反噬，技能进入冷却。同时只能契约一只生物。"),
            Map.entry("summon_2_1", "在身后开启 4×8×1 的召唤之门（沿主轴对齐的实体门框 + 屏障，玩家无法穿过）：门内涌出 20 只随机生物（僵尸 / 尸壳 / 溺尸 / 骷髅 / 流浪者 / 蜘蛛 / 洞穴蜘蛛 / 卫道士 / 掠夺者 / 凋灵骷髅）跟随作战，全部属性 +40%，优先集火你的目标；召唤物死亡会从门内再次补召，始终保持 20 只；持续 60 秒后关闭并进入冷却。可与上古魔门同时存在、同时召唤。"),
            Map.entry("summon_3_1", "在身后开启 4×8×1 的上古魔门（沿主轴对齐的实体门框 + 屏障，玩家无法穿过）：门内走出一只精英或地形 BOSS（劫掠兽 / 唤魔者 / 卫道士 / 凋灵骷髅 / 坚守者 / 烈焰人）跟随作战，属性 +40% 并额外获得你 50% 的属性，优先集火你的目标；召唤物死亡立即进入冷却，未死亡则 60 秒后消失并进入冷却。一旦进入冷却，脚下超阶光环立即消失。可与召唤之门同时存在、同时召唤。"));

    /** 空间系描述 */
    private static final Map<String, String> SPACE_DESCRIPTIONS = Map.ofEntries(
            Map.entry("space_0_1", "锁定视线前方 2×2×2 空间（12 tick 粒子画圈前摇）：范围内的生物被吸到身前，掉落物与方块直接吸进背包，背包满了则掉在脚下。"),
            Map.entry("space_0_2", "第一次释放用粒子锁定 3×3×3 空间（退还耗蓝、不进冷却）；15 秒内再次释放，把锁定范围内的生物与掉落物整体搬到新落点（落点超出施法范围按极限计算）；方块不参与移动，保持原样。"),
            Map.entry("space_0_3", "锁定视线前方 4×4×4 空间（12 tick 前摇）：范围内所有方块被空间撕裂并全部掉落，圈内生物受到 9 点空间伤害。"),
            Map.entry("space_1_1", "对准的生物身上撕开空间裂痕：目标受到 30 点空间伤害，其 3 格内的其他生物同样受到 30 点群体伤害；裂痕约 1.5 秒后自动消散，不会残留在场上。"),
            Map.entry("space_1_2", "沿视线射出星空光刃：飞行途中持续向两侧扩张变宽，命中生物造成 60 点空间伤害并将其击退；撞到方块会把方块打成掉落物，光刃本身不消失，继续飞到施法范围极限后才进入冷却。"),
            Map.entry("space_1_3", "在所指位置展开虚空风暴：中央悬浮深空漩涡图片，四周 6 块真实方块碎片环绕旋转（不再是纯粒子），蓄力 1 秒后爆发：6 格内所有敌对受到 90 点空间伤害并被向外掀飞。"),
            Map.entry("space_2_1", "脚下展开明显的青蓝双环传送阵（顺时针转满一圈约 1 秒）：前摇期间法阵与粒子始终跟随你当前位置移动；转完后沿所指方向把玩家与周围 3×3×3 内的生物传送到地表安全落点（不会掉进地下），超出施法范围按极限范围落点，传送后技能进入冷却。"),
            Map.entry("space_3_1", "在所指位置立起空间死轴（紫色外环、黑色内芯、白色菱心）：从 0.5 格膨胀到 3 格后停住，期间持续把周围 8 格内的生物吸向中心，并随机生成白 / 灰色光线吸入菱心消散；每秒对范围内敌对造成 100 点空间伤害，持续 60 秒；60 秒后死轴在 0.5 秒内暴涨到 16 格并爆炸，对 8 格内敌对造成 500 点空间伤害，随后进入冷却。"));

    /** 雷系初阶 3 个技能的正式实现（后续系列照此追加） */
    private static void registerImplementations() {
        // 蟒痕：单条雷电
        implement("thunder_0_1", ctx -> {
            Vec3 from = ctx.origin();
            Vec3 to = ctx.targetPos();
            MagicDamage.line(ctx.level(), from, to, ParticleTypes.ELECTRIC_SPARK, 0.5D);
            MagicDamage.playSound(ctx.level(), to, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.5F, 1.8F);
            LivingEntity target = ctx.targetEntity();
            if (target != null) {
                ctx.damage(target, 3.0D);
            }
        });

        // 狂策：3 条雷电，分别打 3 个目标，没有目标就劈方块的同一位置 3 次
        implement("thunder_0_2", ctx -> {
            Vec3 from = ctx.origin();
            Vec3 center = ctx.targetPos();
            List<LivingEntity> targets = new ArrayList<>();
            LivingEntity primary = ctx.targetEntity();
            if (primary != null) {
                targets.add(primary);
            }
            if (targets.size() < 3) {
                AABB area = new AABB(center, center).inflate(5.0D);
                for (LivingEntity candidate : ctx.level().getEntitiesOfClass(LivingEntity.class, area)) {
                    if (candidate == ctx.player() || !candidate.isAlive() || targets.contains(candidate)) {
                        continue;
                    }
                    targets.add(candidate);
                    if (targets.size() >= 3) {
                        break;
                    }
                }
            }
            if (targets.isEmpty()) {
                for (int i = 0; i < 3; i++) {
                    MagicDamage.line(ctx.level(), from, center, ParticleTypes.ELECTRIC_SPARK, 0.5D);
                }
                MagicDamage.playSound(ctx.level(), center, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.5F, 1.6F);
                return;
            }
            for (LivingEntity target : targets) {
                Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
                MagicDamage.line(ctx.level(), from, hit, ParticleTypes.ELECTRIC_SPARK, 0.5D);
                ctx.damage(target, 3.0D);
            }
            MagicDamage.playSound(ctx.level(), center, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.5F, 1.6F);
        });

        // 怒击：天降雷电
        implement("thunder_0_3", ctx -> {
            Vec3 to = ctx.targetPos();
            MagicDamage.skyBolt(ctx.level(), to);
            LivingEntity target = ctx.targetEntity();
            if (target != null) {
                ctx.damage(target, 9.0D);
            }
        });

        // ---------------- 雷系中阶 ~ 超阶（实现在 ThunderMagic） ----------------
        implement("thunder_1_1", ThunderMagic::skyStrike);   // 轰顶
        implement("thunder_1_2", ThunderMagic::yaksha);      // 夜叉
        implement("thunder_1_3", ThunderMagic::madDance);    // 狂舞
        implement("thunder_2_1", ThunderMagic::deathRay);    // 寂雷死光
        implement("thunder_3_1", ThunderMagic::thunderGod);  // 雷神降临

        // ---------------- 火系（实现在 FireMagic，逐条对照需求文档） ----------------
        implement("fire_0_1", FireMagic::scorch);        // 灼烧
        implement("fire_0_2", FireMagic::burnBone);      // 焚骨
        implement("fire_0_3", FireMagic::burst);         // 爆裂
        implement("fire_1_1", FireMagic::skySmash);      // 轰天
        implement("fire_1_2", FireMagic::earthEvil);     // 地煞
        implement("fire_1_3", FireMagic::ninePalace);    // 九宫
        implement("fire_2_1", FireMagic::skyFireFuneral);// 天炎葬礼
        implement("fire_3_1", FireMagic::fireGod);       // 火神焚天

        // ---------------- 风系（实现在 WindMagic，逐条对照需求文档） ----------------
        implement("wind_0_1", WindMagic::swiftStep);      // 疾行
        implement("wind_0_2", WindMagic::swiftStride);    // 闪步
        implement("wind_0_3", WindMagic::swiftShadow);    // 飘影
        implement("wind_1_1", WindMagic::tornado);        // 龙卷
        implement("wind_1_2", WindMagic::skyNet);         // 天罗
        implement("wind_1_3", WindMagic::teardown);       // 撕裂
        implement("wind_2_1", WindMagic::windWings);      // 风之翼
        implement("wind_3_1", WindMagic::thousandBlades); // 泯风千刃

        // ---------------- 水系（实现在 WaterMagic，逐条对照需求文档） ----------------
        implement("water_0_1", WaterMagic::dissolve);      // 化解
        implement("water_0_2", WaterMagic::guard);         // 守护
        implement("water_0_3", WaterMagic::cycle);         // 循环
        implement("water_1_1", WaterMagic::expel);         // 驱逐
        implement("water_1_2", WaterMagic::surge);         // 惊涛
        implement("water_1_3", WaterMagic::flood);         // 洪卷
        implement("water_2_1", WaterMagic::skyVeil);       // 水花天幕
        implement("water_3_1", WaterMagic::huntBlades);    // 水之猎刃

        // ---------------- 光系（实现在 LightMagic） ----------------
        implement("light_0_1", LightMagic::purify);        // 净化
        implement("light_0_2", LightMagic::blink);         // 闪烁
        implement("light_0_3", LightMagic::blind);         // 失明
        implement("light_1_1", LightMagic::holyShield);    // 圣盾
        implement("light_1_2", LightMagic::paintWall);     // 画壁
        implement("light_1_3", LightMagic::guard);         // 守护
        implement("light_2_1", LightMagic::lightFall);     // 光落漫丈
        implement("light_3_1", LightMagic::holySword);     // 圣光大剑

        // ---------------- 土系（实现在 EarthMagic） ----------------
        implement("earth_0_1", EarthMagic::shift);         // 挪移
        implement("earth_0_2", EarthMagic::slow);          // 迟缓
        implement("earth_0_3", EarthMagic::sink);          // 陷落
        implement("earth_1_1", EarthMagic::spire);         // 嶙石
        implement("earth_1_2", EarthMagic::screen);        // 山屏
        implement("earth_1_3", EarthMagic::stoneShield);   // 石盾
        implement("earth_2_1", EarthMagic::stoneEye);      // 岩魔之瞳
        implement("earth_3_1", EarthMagic::arena);         // 角斗场

        implement("ice_0_1", IceMagic::freeze);            // 冻结
        implement("ice_0_2", IceMagic::condense);          // 凝结
        implement("ice_0_3", IceMagic::cover);             // 覆盖
        implement("ice_1_1", IceMagic::crushBone);         // 碾骨
        implement("ice_1_2", IceMagic::serpent);           // 庞莽
        implement("ice_1_3", IceMagic::wallCity);          // 围城
        implement("ice_2_1", IceMagic::coffin);            // 冰封灵柩
        implement("ice_3_1", IceMagic::domain);            // 云冰晶界

        // ---------------- 心灵系（实现在 MindMagic，需求 2026-11-14） ----------------
        implement("mind_0_1", MindMagic::pacify);          // 安抚
        implement("mind_0_2", MindMagic::impact);          // 冲击
        implement("mind_0_3", MindMagic::mindWall);        // 心灵之墙
        implement("mind_1_1", MindMagic::fear);            // 恐惧
        implement("mind_1_2", MindMagic::agitation);       // 躁动
        implement("mind_1_3", MindMagic::valor);           // 无畏
        implement("mind_2_1", MindMagic::mindControl);     // 摄魄控心
        implement("mind_3_1", MindMagic::fantasy);         // 永恒幻想

        // ---------------- 治愈系（实现在 HealMagic，需求 2026-11-14） ----------------
        implement("heal_0_1", HealMagic::mend);                    // 愈合
        implement("heal_0_2", HealMagic::cleanse);                 // 净化
        implement("heal_0_3", HealMagic::vigor);                   // 生机
        implement("heal_1_1", HealMagic::sprite);                  // 幼芽
        implement("heal_1_2", HealMagic::spriteRing);              // 环舞
        implement("heal_1_3", HealMagic::spriteFlock);             // 群愈
        implement("heal_2_1", HealMagic::starConstellation);       // 宝瓶星芒座
        implement("heal_3_1", HealMagic::rebirth);                 // 复苏

        // ---------------- 毒系（实现在 PoisonMagic，需求 2026-11-14） ----------------
        implement("poison_0_1", PoisonMagic::fang);                // 毒牙
        implement("poison_0_2", PoisonMagic::breath);              // 吐纳
        implement("poison_0_3", PoisonMagic::refine);              // 炼化
        implement("poison_1_1", PoisonMagic::burst);               // 自爆毒
        implement("poison_1_2", PoisonMagic::infection);           // 泛发感染
        implement("poison_1_3", PoisonMagic::parasite);            // 寄生毒
        implement("poison_2_1", PoisonMagic::mutation);            // 毒变
        implement("poison_3_1", PoisonMagic::devouringGale);       // 万噬之风

        // ---------------- 祝福系（实现在 BlessMagic，需求 2026-11-16） ----------------
        implement("bless_0_1", BlessMagic::strengthen);            // 强化
        implement("bless_0_2", BlessMagic::hymn);                  // 圣音
        implement("bless_0_3", BlessMagic::spirit);                // 灵力
        implement("bless_1_1", BlessMagic::chantStrength);         // 强化·祝喃
        implement("bless_1_2", BlessMagic::chantHymn);             // 圣音·祝喃
        implement("bless_1_3", BlessMagic::chantSpirit);           // 灵力·祝喃
        implement("bless_2_1", BlessMagic::divineSeal);            // 神印礼赞
        implement("bless_3_1", BlessMagic::starGuardian);          // 众星守护

        // ---------------- 音系（实现在 SoundMagic，需求 2026-11-16） ----------------
        implement("sound_0_1", SoundMagic::kill);                  // 杀
        implement("sound_0_2", SoundMagic::disrupt);               // 扰
        implement("sound_0_3", SoundMagic::probe);                 // 探
        implement("sound_1_1", SoundMagic::strings);               // 弦
        implement("sound_1_2", SoundMagic::bow);                   // 弓
        implement("sound_1_3", SoundMagic::burst);                 // 爆
        implement("sound_2_1", SoundMagic::dirge);                 // 断葬曲
        implement("sound_3_1", SoundMagic::finale);                // 曲终帝陨

        // ---------------- 诅咒系（实现在 CurseMagic，需求 2026-11-16） ----------------
        implement("curse_0_1", CurseMagic::chill);                  // 寒
        implement("curse_0_2", CurseMagic::press);                  // 压
        implement("curse_0_3", CurseMagic::wither);                 // 伤
        implement("curse_1_1", CurseMagic::spider);                 // 魂蜘
        implement("curse_1_2", CurseMagic::exile);                  // 天涯
        implement("curse_1_3", CurseMagic::soulSlay);               // 灭魂
        implement("curse_2_1", CurseMagic::ghostPunish);            // 鬼刑
        implement("curse_3_1", CurseMagic::soulPact);               // 拘灵遣将

        // 植物系（实体植物渲染：藤蔓 / 木牢 / 巨木手 / 灵树）
        implement("plant_0_1", PlantMagic::grow);                   // 生长
        implement("plant_0_2", PlantMagic::hunt);                   // 追袭
        implement("plant_0_3", PlantMagic::bind);                   // 束缚
        implement("plant_1_1", PlantMagic::prison);                 // 囚牢
        implement("plant_1_2", PlantMagic::overgrowth);             // 疯长
        implement("plant_1_3", PlantMagic::bloom);                  // 绽放
        implement("plant_2_1", PlantMagic::ghostHand);              // 鬼木手
        implement("plant_3_1", PlantMagic::graft);                  // 移花接木

        // ---------------- 亡灵系（实现在 UndeadMagic，需求 2026-11-19） ----------------
        implement("undead_0_1", UndeadMagic::summonSkeleton);       // 亡灵生成
        implement("undead_0_2", UndeadMagic::summonZombie);         // 肉体构造
        implement("undead_0_3", UndeadMagic::undeadSpace);          // 亡灵空间
        implement("undead_1_1", UndeadMagic::soulSummon);           // 招魂术
        implement("undead_1_2", UndeadMagic::wraithForm);           // 无体之灵
        implement("undead_1_3", UndeadMagic::undeadSlayer);         // 亡灵杀手
        implement("undead_2_1", UndeadMagic::undeadWorld);          // 亡界
        implement("undead_3_1", UndeadMagic::cataclysm);            // 亡灵天灾

        // ---------------- 召唤系（实现在 SummonMagic，需求 2026-11-19） ----------------
        implement("summon_0_1", SummonMagic::summonSpace);          // 召唤空间
        implement("summon_1_1", SummonMagic::contractSpace);        // 契约空间
        implement("summon_2_1", SummonMagic::summonGate);           // 召唤之门
        implement("summon_3_1", SummonMagic::ancientGate);          // 上古魔门

        // ---------------- 空间系（实现在 SpaceMagic，需求 2026-11-19） ----------------
        implement("space_0_1", SpaceMagic::absorb);                 // 吸取
        implement("space_0_2", SpaceMagic::move);                   // 移动
        implement("space_0_3", SpaceMagic::destroy);                // 破坏
        implement("space_1_1", SpaceMagic::cleave);                 // 切割
        implement("space_1_2", SpaceMagic::slash);                  // 空间斩
        implement("space_1_3", SpaceMagic::storm);                  // 空间风暴
        implement("space_2_1", SpaceMagic::teleport);               // 瞬移
        implement("space_3_1", SpaceMagic::deathAxis);              // 空间死轴
    }

    // 注意：本静态块必须放在 DESCRIPTIONS 声明之后（Java 静态成员按声明顺序初始化，
    // 否则 defineAllSeries() 读取 DESCRIPTIONS 时会拿到 null 导致 ExceptionInInitializerError）
    static {
        defineAllSeries();
        registerImplementations();
    }
}
