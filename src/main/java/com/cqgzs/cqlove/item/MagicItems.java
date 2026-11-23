package com.cqgzs.cqlove.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.cqgzs.cqlove.PlatformMods;

/**
 * 魔法世界物品注册（需求文档 5.2 ~ 5.5 的四本书）。
 *
 * <p>注意：26.1.2 起 {@link Item.Properties} 在构造 Item 时就必须知道注册 id，
 * 所以必须用 {@code registerItem(name, factory, properties)}，不能自己 new Item 再 register，
 * 否则启动时会抛 “Item id not set”。</p>
 *
 * <p>法杖 / 法套装（文档最后一段）等后续版本再加，这里先把四本书做出来打通流程。</p>
 */
public final class MagicItems {

    /** 物品注册器 */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(PlatformMods.MODID);

    /** 觉醒书：觉醒一个魔法系列 */
    public static final DeferredItem<MagicBookItem> AWAKEN_BOOK = ITEMS.registerItem("awaken_book",
            properties -> new MagicBookItem(MagicBookItem.Kind.AWAKEN, properties),
            () -> new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON));

    /** 天赋书：学习技能 */
    public static final DeferredItem<MagicBookItem> TALENT_BOOK = ITEMS.registerItem("talent_book",
            properties -> new MagicBookItem(MagicBookItem.Kind.LEARN, properties),
            () -> new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON));

    /** 升阶书：初阶→中阶→高阶→超阶 */
    public static final DeferredItem<MagicBookItem> RANK_BOOK = ITEMS.registerItem("rank_book",
            properties -> new MagicBookItem(MagicBookItem.Kind.RANK_UP, properties),
            () -> new Item.Properties().stacksTo(16).rarity(Rarity.RARE));

    /** 体质书：+1 天赋点 */
    public static final DeferredItem<MagicBookItem> BODY_BOOK = ITEMS.registerItem("body_book",
            properties -> new MagicBookItem(MagicBookItem.Kind.BODY, properties),
            () -> new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON));

    /** 蓝量瓶：喝下恢复 10 点蓝量（经验瓶 + 任意花 无序合成） */
    public static final DeferredItem<ManaBottleItem> MANA_BOTTLE = ITEMS.registerItem("mana_bottle",
            ManaBottleItem::new, () -> new Item.Properties().stacksTo(16));

    /** 雷神法阵：雷神降临期间脚下的旋转圆环（只作地面贴图用，不进物品栏） */
    public static final DeferredItem<Item> THUNDER_RING = ITEMS.registerItem("thunder_ring",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 火神法阵：火神焚天期间脚下的旋转圆环（只作地面贴图用，不进物品栏） */
    public static final DeferredItem<Item> FIRE_RING = ITEMS.registerItem("fire_ring",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 岩浆地表图：地煞技能的 3×3 岩浆贴片（只作地面贴图用，不进物品栏） */
    public static final DeferredItem<Item> MAGMA_PATCH = ITEMS.registerItem("magma_patch",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 旋风柱：风盘（龙卷/天罗/撕裂）、泯风千刃的旋涡贴图（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> WIND_CYCLONE = ITEMS.registerItem("wind_cyclone",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 撕裂空间图：撕裂旋风期间随机露出的星空裂口（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> WIND_RIFT = ITEMS.registerItem("wind_rift",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 风之翼：高阶被动，玩家背后的六翼翅膀（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> WIND_WINGS = ITEMS.registerItem("wind_wings",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 风盾：风之翼主动技能环绕玩家的 4 面青色透明盾（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> WIND_SHIELD = ITEMS.registerItem("wind_shield",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 水盾：水系初阶「守护」「循环」挂在生物身上的圆形水盾（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> WATER_SHIELD = ITEMS.registerItem("water_shield",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 波浪：水系中阶「驱逐」「惊涛」贴地推进的浪墙（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> WATER_WAVE = ITEMS.registerItem("water_wave",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 水龙卷：水系中阶「洪卷」的移动水龙卷（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> WATER_TORNADO = ITEMS.registerItem("water_tornado",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 水墙：水系高阶「水花天幕」立在身前的 10×10 水幕（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> WATER_WALL = ITEMS.registerItem("water_wall",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 水弯刀：水系超阶「水之猎刃」绕身的 8 把弯刀（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> WATER_BLADE = ITEMS.registerItem("water_blade",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 光盾：光系中阶「圣盾」环绕玩家的金色盾（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> LIGHT_SHIELD = ITEMS.registerItem("light_shield",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 画壁：光系中阶「画壁」立在身前的光之壁垒（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> LIGHT_WALL = ITEMS.registerItem("light_wall",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 光柱：光系高阶「光落漫丈」从天而降的巨大光柱（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> LIGHT_PILLAR = ITEMS.registerItem("light_pillar",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 圣光大剑：光系超阶「圣光大剑」从天劈落的巨剑（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> LIGHT_SWORD = ITEMS.registerItem("light_sword",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 巨型圣剑：光系超阶「圣光大剑」前摇后砸下的那把超巨大剑（128×256 贴图，只作实体贴图用） */
    public static final DeferredItem<Item> LIGHT_GREATSWORD = ITEMS.registerItem("light_greatsword",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 光球：光系初阶「闪烁」扔出去照明的光球（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> LIGHT_ORB = ITEMS.registerItem("light_orb",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 小山：土系中阶「嶙石」从地下升起的 2×3×2 山体（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> EARTH_MOUNTAIN = ITEMS.registerItem("earth_mountain",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 石柱：土系中阶「山屏」两侧展开的四根竖立石柱（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> EARTH_PILLAR = ITEMS.registerItem("earth_pillar",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 光屏：土系中阶「山屏」中间半透明的琥珀光幕（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> EARTH_SCREEN = ITEMS.registerItem("earth_screen",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 石盾：土系中阶「石盾」环绕目标的灰色石盾（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> EARTH_SHIELD = ITEMS.registerItem("earth_shield",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 石之眼：土系高阶「岩魔之瞳」从眼前推出的石眼（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> EARTH_EYE = ITEMS.registerItem("earth_eye",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 冰锥：冰系初阶「凝结」飞出的冰刺（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> ICE_SPIKE = ITEMS.registerItem("ice_spike",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 冰蟒：冰系中阶「庞莽」冲出的冰之巨蟒（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> ICE_SERPENT = ITEMS.registerItem("ice_serpent",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 冰晶柱：冰系超阶「云冰晶界」领域边界的冰晶（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> ICE_CRYSTAL = ITEMS.registerItem("ice_crystal",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 正面冰蟒头：中阶「庞莽」立体蛇身最前端的蛇头（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> ICE_SERPENT_HEAD = ITEMS.registerItem("ice_serpent_head",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 冰墙板：高阶「冰封灵柩」前摇左右合拢的两面冰墙（只作实体贴图用，不进物品栏） */
    public static final DeferredItem<Item> ICE_COFFIN_WALL = ITEMS.registerItem("ice_coffin_wall",
            Item::new, () -> new Item.Properties().stacksTo(1));

    // ---------------- 心灵系（只作实体贴图用，不进物品栏） ----------------

    /** 安抚：白色贴地圆环（圆圈直径 = 施法范围） */
    public static final DeferredItem<Item> MIND_RING_WHITE = ITEMS.registerItem("mind_ring_white",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 冲击：蓝色贴地圆环 */
    public static final DeferredItem<Item> MIND_RING_BLUE = ITEMS.registerItem("mind_ring_blue",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 恐惧：灰色贴地圆环 */
    public static final DeferredItem<Item> MIND_RING_GRAY = ITEMS.registerItem("mind_ring_gray",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 躁动：红色贴地圆环 */
    public static final DeferredItem<Item> MIND_RING_RED = ITEMS.registerItem("mind_ring_red",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 无畏：金色贴地圆环 */
    public static final DeferredItem<Item> MIND_RING_GOLD = ITEMS.registerItem("mind_ring_gold",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 永恒幻想：白金色结界法阵 */
    public static final DeferredItem<Item> MIND_RING_PLATINUM = ITEMS.registerItem("mind_ring_platinum",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 摄魄控心：玩家与被控生物之间连成一条线的红色符文 */
    public static final DeferredItem<Item> MIND_SIGIL = ITEMS.registerItem("mind_sigil",
            Item::new, () -> new Item.Properties().stacksTo(1));

    // ---------------- 治愈系（只作实体贴图用，不进物品栏） ----------------

    /** 治愈精灵：跟随玩家的薄荷绿光球，中阶三档技能共用 */
    public static final DeferredItem<Item> HEAL_SPRITE = ITEMS.registerItem("heal_sprite",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 宝瓶星芒座：高阶领域悬在玩家头顶的宝瓶 */
    public static final DeferredItem<Item> HEAL_BOTTLE = ITEMS.registerItem("heal_bottle",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 复苏：超阶结界悬在玩家头顶的生命符文 */
    public static final DeferredItem<Item> HEAL_SIGIL = ITEMS.registerItem("heal_sigil",
            Item::new, () -> new Item.Properties().stacksTo(1));

    // ---------------- 毒系（只作实体贴图用，不进物品栏） ----------------

    /** 吐纳 / 寄生毒残留：固定在落点的毒雾云 */
    public static final DeferredItem<Item> POISON_CLOUD = ITEMS.registerItem("poison_cloud",
            Item::new, () -> new Item.Properties().stacksTo(1));

    // ---------------- 祝福系（只作实体贴图用，不进物品栏） ----------------

    /** 神印礼赞：高阶领域悬在玩家头顶的金色神印 */
    public static final DeferredItem<Item> BLESS_SEAL = ITEMS.registerItem("bless_seal",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 众星守护：超阶结界悬在玩家头顶的白金星轮 */
    public static final DeferredItem<Item> BLESS_STARS = ITEMS.registerItem("bless_stars",
            Item::new, () -> new Item.Properties().stacksTo(1));

    // ---------------- 音系（只作实体贴图用，不进物品栏） ----------------

    /** 断葬曲：高阶领域悬在玩家头顶的琥珀音叉 */
    public static final DeferredItem<Item> SOUND_LYRE = ITEMS.registerItem("sound_lyre",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 曲终帝陨：超阶结界悬在玩家头顶的金橙音符 */
    public static final DeferredItem<Item> SOUND_NOTE = ITEMS.registerItem("sound_note",
            Item::new, () -> new Item.Properties().stacksTo(1));

    // ---------------- 诅咒系（只作实体贴图用，不进物品栏） ----------------

    /** 鬼刑：高阶领域悬在玩家头顶的紫黑刑印 */
    public static final DeferredItem<Item> CURSE_GHOST = ITEMS.registerItem("curse_ghost",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 拘灵遣将：超阶灵契悬在玩家头顶的暗紫灵印 */
    public static final DeferredItem<Item> CURSE_PACT = ITEMS.registerItem("curse_pact",
            Item::new, () -> new Item.Properties().stacksTo(1));

    // ---------------- 亡灵系（只作实体贴图用，不进物品栏） ----------------

    /** 招魂术：纯虚拟鬼魂的客户端面片外观（服务端不再生成任何 Vex 本体） */
    public static final DeferredItem<Item> UNDEAD_GHOST = ITEMS.registerItem("undead_ghost",
            Item::new, () -> new Item.Properties().stacksTo(1));

    // ---------------- 召唤系（只作实体贴图用，不进物品栏） ----------------

    /** 召唤之门：传送门内部 4×8 的紫色光面 */
    public static final DeferredItem<Item> SUMMON_PORTAL = ITEMS.registerItem("summon_portal",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 上古魔门：传送门内部 4×8 的暗红魔面 */
    public static final DeferredItem<Item> SUMMON_PORTAL_DARK = ITEMS.registerItem("summon_portal_dark",
            Item::new, () -> new Item.Properties().stacksTo(1));

    // ---------------- 空间系（只作实体贴图用，不进物品栏） ----------------

    /** 切割：目标身上撕开的空间裂痕 */
    public static final DeferredItem<Item> SPACE_RIFT = ITEMS.registerItem("space_rift",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 空间斩：带星空纹理的白色光刃 */
    public static final DeferredItem<Item> SPACE_BLADE = ITEMS.registerItem("space_blade",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 空间死轴：紫环黑芯白菱心的死亡轴心（贴地圆环，会随技能膨胀） */
    public static final DeferredItem<Item> SPACE_AXIS = ITEMS.registerItem("space_axis",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 空间风暴：中央虚空漩涡面片（配合环绕旋转的真实方块碎块） */
    public static final DeferredItem<Item> SPACE_STORM = ITEMS.registerItem("space_storm",
            Item::new, () -> new Item.Properties().stacksTo(1));

    // ---- 元素反应晶核盾：围绕玩家旋转的发光面片（只作实体贴图用，不进物品栏）----
    /** 雷盾：雷+土 晶核反应 */
    public static final DeferredItem<Item> SHIELD_THUNDER = ITEMS.registerItem("shield_thunder",
            Item::new, () -> new Item.Properties().stacksTo(1));
    /** 火盾：火+土 晶核反应 */
    public static final DeferredItem<Item> SHIELD_FIRE = ITEMS.registerItem("shield_fire",
            Item::new, () -> new Item.Properties().stacksTo(1));
    /** 土盾：风+土 晶核反应 */
    public static final DeferredItem<Item> SHIELD_EARTH = ITEMS.registerItem("shield_earth",
            Item::new, () -> new Item.Properties().stacksTo(1));
    /** 冰盾：土+冰 晶核反应 */
    public static final DeferredItem<Item> SHIELD_ICE = ITEMS.registerItem("shield_ice",
            Item::new, () -> new Item.Properties().stacksTo(1));
    /** 毒盾：土+毒 晶核反应 */
    public static final DeferredItem<Item> SHIELD_POISON = ITEMS.registerItem("shield_poison",
            Item::new, () -> new Item.Properties().stacksTo(1));
    /** 草盾：土+草 晶核反应（水盾复用已有 WATER_SHIELD） */
    public static final DeferredItem<Item> SHIELD_PLANT = ITEMS.registerItem("shield_plant",
            Item::new, () -> new Item.Properties().stacksTo(1));

    /** 模组徽记：创造模式物品栏 Tab 图标（贴图 = assets/cqlove/icons/icon_32x32.png 的副本） */    public static final DeferredItem<Item> MOD_ICON = ITEMS.registerItem("mod_icon",
            Item::new, () -> new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));

    private MagicItems() {
    }

    /** 主类构造时调用 */
    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
