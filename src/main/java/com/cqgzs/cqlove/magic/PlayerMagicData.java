package com.cqgzs.cqlove.magic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;

/**
 * 单个玩家的魔法数据（需求：每个玩家单独储存）。
 *
 * <p>通过 NeoForge 的 Attachment 挂在玩家身上，随玩家存档自动读写（见 {@link MagicData}）。
 * 内容：</p>
 * <ul>
 *   <li>阶级、当前蓝量、天赋点、10 项天赋等级</li>
 *   <li>已觉醒的魔法系列（顺序 = Z/X/C/V 的绑定顺序，最多 4 个）</li>
 *   <li>已学习的技能 id</li>
 *   <li>每个系列当前选中的技能 id（G 轮盘用）</li>
 *   <li>每个技能各自的冷却结束时间（不写进存档，重登后清空，避免时间轴错乱）</li>
 * </ul>
 */
public final class PlayerMagicData implements ValueIOSerializable {

    /** 蓝量初始值（需求 5.6） */
    public static final double BASE_MANA = 2.0D;
    /** 魔力（魔法攻击力）初始值（需求 5.7） */
    public static final double BASE_MAGIC_POWER = 3.0D;
    /** 觉醒系列硬上限：即使超阶也只有 4 个（对应 Z/X/C/V 四个按键） */
    public static final int MAX_SERIES = 4;

    private MagicRank rank = MagicRank.NOVICE;
    private double mana = BASE_MANA;
    private int talentPoints;
    /** 10 项天赋的等级，下标对应 {@link MagicTalent#ordinal()} */
    private final int[] talents = new int[MagicTalent.values().length];
    /** 已觉醒系列，顺序决定绑定按键（0=Z 1=X 2=C 3=V） */
    private final List<MagicElement> awakened = new ArrayList<>();
    /** 已学习技能 id（有序，便于界面按学习顺序显示） */
    private final Set<String> learned = new LinkedHashSet<>();
    /** 每个系列当前选中的技能 id */
    private final Map<MagicElement, String> selected = new EnumMap<>(MagicElement.class);
    /** 技能冷却：技能 id -> 结束时的游戏 tick */
    private final Map<String, Long> cooldownEnd = new HashMap<>();
    /** 服务端当前游戏 tick（每 tick 由 MagicEvents / MagicCaster 写入，仅供领域内冷却减半计算） */
    private long serverTime;
    /** 蓝量恢复计时（tick），仅运行时用 */
    private int manaRegenTicks;
    /**
     * 云冰晶界领域是否生效（仅运行时，由 {@code IceMagic} 挂/卸）。
     * 生效期间：魔力 +100、蓝量上限 +100、魔暴击伤害 +100、所有技能冷却 -50%。
     */
    private boolean iceDomainActive;
    /**
     * 火神焚天 buff 剩余 tick（仅运行时不存档，重登后清除）。
     * buff 期间：魔力 +100、蓝量上限 +100、魔暴击伤害 +100%（见 {@link MagicDamage}），
     * 攻击 +100 由 {@code FireMagic} 用属性修饰器挂/卸。
     */
    private int fireGodTicks;
    /**
     * 雷神降临 buff 剩余 tick（仅运行时不存档）。
     * buff 期间：攻击/魔力/蓝量/魔暴伤 +100（同火神），免疫雷电/爆炸/火焰/岩浆，
     * 只有"仅觉醒雷系"的玩家才享受冷却 -50%（见 {@link MagicCaster}）。
     */
    private int thunderGodTicks;
    /**
     * 技能锁定：技能 id -> 锁定到的游戏 tick（仅运行时，不存档）。
     *
     * <p>风系持续类技能（加速 3 秒、风之翼 30 秒、泯风千刃直到 32 个旋风全部飞出）
     * 使用期间锁定——<b>只锁造成锁定的那一个技能</b>，其他技能仍可正常释放
     * （见 {@link MagicCaster#cast}）。用 Map 是因为多个持续技能可以同时在跑。</p>
     */
    private final Map<String, Long> skillLocks = new HashMap<>();
    /** 风之翼主动 buff 剩余 tick（护甲/魔抗 +50 与 4 面风盾，仅运行时） */
    private int windWingsTicks;
    /**
     * 心灵系·无畏（金色圆圈）：站在圈内时每 tick 刷新 40 tick，离开圈 2 秒后自动失效。
     * 期间魔力值 +12（见 {@link #magicPower()}），仅运行时不存档。
     */
    private int mindValorTicks;
    /**
     * 心灵系·永恒幻想（超阶结界）：期间魔力值 +100、魔暴击 +80 个百分点、
     * 魔暴伤 +200 个百分点、双防与免疫由 {@code MindMagic} 处理，仅运行时不存档。
     */
    private boolean mindFantasyActive;
    /**
     * 祝福系增益（仅运行时不存档）：由 {@code BlessMagic} 每 tick 刷新，停刷后 3 秒自然失效。
     * 各维度为「同一玩家所受全部祝福的累加值」（祝福可叠加）。
     */
    private int blessTicks;
    private double blessMagicPower;
    private double blessMagicCrit;
    private double blessMagicCritDmg;
    private double blessMana;
    /**
     * 上次广播给客户端的“跟随特效”开关位（翅膀/法阵，仅运行时）。
     * -1 = 还没广播过，首 tick 必发一次（见 {@link MagicFxSync}）。
     */
    private int fxFlagsSent = -1;

    // ------------------------------------------------------------------
    // 超阶技能脚下圆环（仅运行时，不写进存档）
    // ------------------------------------------------------------------
    /** 正在进行的超阶技能 id；null = 当前没有圆环 */
    private @Nullable String superRingSkillId;
    /** 圆环所属系列序号（{@code MagicElement.ordinal()}），-1 = 无 */
    private int superRingElement = -1;
    /** 圆环结束时的服务端游戏 tick；到期自动收掉，防止异常分支残留 */
    private long superRingEndTick;
    /** 上次广播的系列与结束 tick，用于状态变化检测（见 {@link MagicCastRingSync}） */
    private int superRingSentElement = -1;
    private long superRingSentEndTick;

    // ------------------------------------------------------------------
    // 基础属性
    // ------------------------------------------------------------------

    public MagicRank rank() {
        return rank;
    }

    public void setRank(MagicRank rank) {
        this.rank = rank;
        // 升阶后如果超出上限（正常不会），裁掉多余的系列
        while (awakened.size() > Math.min(MAX_SERIES, rank.maxSeries())) {
            MagicElement removed = awakened.remove(awakened.size() - 1);
            selected.remove(removed);
        }
    }

    public double mana() {
        return mana;
    }

    /** 设置当前蓝量，自动夹取到 [0, 上限] */
    public void setMana(double mana) {
        this.mana = Math.max(0.0D, Math.min(maxMana(), mana));
    }

    /** 蓝量上限 = 基础 2 + 蓝量天赋，火神 / 雷神 / 云冰晶界 / 祝福各 +100 */
    public double maxMana() {
        return BASE_MANA + talentLevel(MagicTalent.MANA)
                + (fireGodActive() ? 100.0D : 0.0D)
                + (thunderGodActive() ? 100.0D : 0.0D)
                + (iceDomainActive ? 100.0D : 0.0D)
                + blessMana();
    }

    public void addMana(double amount) {
        setMana(this.mana + amount);
    }

    /** 魔力（魔法攻击力）= 基础 3 + 魔力天赋 + 火神 / 雷神 / 云冰晶界 +100，无畏 +12，永恒幻想 +100，祝福增益 */
    public double magicPower() {
        return BASE_MAGIC_POWER + MagicTalent.MAGIC_POWER.valueAt(talentLevel(MagicTalent.MAGIC_POWER))
                + (fireGodActive() ? 100.0D : 0.0D)
                + (thunderGodActive() ? 100.0D : 0.0D)
                + (iceDomainActive ? 100.0D : 0.0D)
                + (mindValorActive() ? 12.0D : 0.0D)
                + (mindFantasyActive ? 100.0D : 0.0D)
                + blessMagicPower();
    }

    // ------------------------------------------------------------------
    // 初始属性（暴击 / 暴伤 / 魔暴击 / 魔暴伤 / 元素精通）
    // 基础值在 {@link MagicTalent#baseValue()}，这里叠加天赋后返回当前总值
    // ------------------------------------------------------------------

    /** 物理暴击率（百分比点）：初始 5 + 暴击天赋 */
    public double critChance() {
        return MagicTalent.CRIT.totalAt(talentLevel(MagicTalent.CRIT));
    }

    /** 物理暴击伤害（百分比点，100 = 暴击翻倍）：初始 100 + 暴伤天赋 */
    public double critDamage() {
        return MagicTalent.CRIT_DAMAGE.totalAt(talentLevel(MagicTalent.CRIT_DAMAGE));
    }

    /** 魔法暴击率（百分比点）：初始 5 + 魔暴击天赋，永恒幻想 +80，祝福增益 */
    public double magicCritChance() {
        return MagicTalent.MAGIC_CRIT.totalAt(talentLevel(MagicTalent.MAGIC_CRIT))
                + (mindFantasyActive ? 80.0D : 0.0D)
                + blessMagicCrit();
    }

    /** 魔法暴击伤害（百分比点，100 = 暴击翻倍）：初始 100 + 魔暴伤天赋，云冰晶界 +100，永恒幻想 +200，祝福增益 */
    public double magicCritDamage() {
        return MagicTalent.MAGIC_CRIT_DAMAGE.totalAt(talentLevel(MagicTalent.MAGIC_CRIT_DAMAGE))
                + (iceDomainActive ? 100.0D : 0.0D)
                + (mindFantasyActive ? 200.0D : 0.0D)
                + blessMagicCritDmg();
    }

    /** 元素精通：初始 100 + 元素精通天赋（魔法伤害按 精通/100 缩放，100 = 不增不减） */
    public double elementMastery() {
        return MagicTalent.ELEMENT_MASTERY.totalAt(talentLevel(MagicTalent.ELEMENT_MASTERY));
    }

    // ------------------------------------------------------------------
    // 心灵系：无畏 / 永恒幻想（仅运行时 buff）
    // ------------------------------------------------------------------

    /** 站在无畏金色圆圈内时每 tick 刷新（40 tick 后自然失效） */
    public void refreshMindValor() {
        mindValorTicks = 40;
    }

    /** 每 tick 倒计时（MagicEvents.onPlayerTick 调用） */
    public void tickMindValor() {
        if (mindValorTicks > 0) {
            mindValorTicks--;
        }
    }

    public boolean mindValorActive() {
        return mindValorTicks > 0;
    }

    public void clearMindValor() {
        mindValorTicks = 0;
    }

    /** 永恒幻想结界是否生效（增幅由 magicPower / magicCrit* 读取，减伤与免疫由 MindMagic 处理） */
    public boolean mindFantasyActive() {
        return mindFantasyActive;
    }

    public void setMindFantasyActive(boolean active) {
        this.mindFantasyActive = active;
    }

    // ------------------------------------------------------------------
    // 祝福系增益（初阶单体 / 中阶圆圈 / 高阶神印 / 超阶众星，仅运行时）
    // ------------------------------------------------------------------

    /**
     * 由 {@code BlessMagic.tickWorld} 每 tick 刷新：各维度的「当前累加值」由 BlessMagic 聚合后直接覆盖，
     * 这里只需延长倒计时。离开增益范围后不再刷新，{@link #tickBless()} 倒计时归零即自动失效。
     */
    public void refreshBless(int ticks, double magicPower, double magicCrit,
                             double magicCritDmg, double mana) {
        this.blessTicks = Math.max(this.blessTicks, ticks);
        this.blessMagicPower = magicPower;
        this.blessMagicCrit = magicCrit;
        this.blessMagicCritDmg = magicCritDmg;
        this.blessMana = mana;
    }

    /** 每 tick 倒计时（MagicEvents.onPlayerTick 调用） */
    public void tickBless() {
        if (blessTicks > 0) {
            blessTicks--;
            if (blessTicks == 0) {
                clearBless();
            }
        }
    }

    public boolean blessActive() {
        return blessTicks > 0;
    }

    private double blessMagicPower() {
        return blessActive() ? blessMagicPower : 0.0D;
    }

    private double blessMagicCrit() {
        return blessActive() ? blessMagicCrit : 0.0D;
    }

    private double blessMagicCritDmg() {
        return blessActive() ? blessMagicCritDmg : 0.0D;
    }

    private double blessMana() {
        return blessActive() ? blessMana : 0.0D;
    }

    public void clearBless() {
        blessTicks = 0;
        blessMagicPower = 0.0D;
        blessMagicCrit = 0.0D;
        blessMagicCritDmg = 0.0D;
        blessMana = 0.0D;
    }

    // ------------------------------------------------------------------
    // 云冰晶界（冰系超阶，60 秒领域）
    // ------------------------------------------------------------------

    /** 领域是否生效：期间 +100 魔力 / 蓝量上限 / 魔暴伤，所有技能冷却 -50% */
    public boolean iceDomainActive() {
        return iceDomainActive;
    }

    /** 由 IceMagic 在领域展开 / 消失时挂卸（不存档，重登后自然失效） */
    public void setIceDomainActive(boolean active) {
        this.iceDomainActive = active;
    }

    /** 每 tick 记一次服务端时间，供 {@link #setCooldown(String, long)} 计算"剩余冷却减半" */
    public void noteGameTime(long gameTime) {
        this.serverTime = gameTime;
    }

    // ------------------------------------------------------------------
    // 火神焚天 buff（火系超阶，60 秒）
    // ------------------------------------------------------------------

    public boolean fireGodActive() {
        return fireGodTicks > 0;
    }

    public int fireGodTicks() {
        return fireGodTicks;
    }

    /** 启动/刷新火神 buff（60 秒） */
    public void startFireGod() {
        fireGodTicks = 60 * 20;
        // 火神不走技能锁定，圆环要按 buff 时长一直亮到进入冷却
        holdSuperRing("fire_3_1", serverTime + fireGodTicks);
    }

    /** 每 tick 倒计时，返回 true 表示这一 tick 刚好到期（用于收尾：卸属性、起冷却） */
    public boolean tickFireGod() {
        if (fireGodTicks <= 0) {
            return false;
        }
        fireGodTicks--;
        return fireGodTicks == 0;
    }

    /** 立即结束火神 buff（重生/重置用） */
    public void clearFireGod() {
        fireGodTicks = 0;
    }

    // ------------------------------------------------------------------
    // 雷神降临 buff（雷系超阶，60 秒）
    // ------------------------------------------------------------------

    public boolean thunderGodActive() {
        return thunderGodTicks > 0;
    }

    public int thunderGodTicks() {
        return thunderGodTicks;
    }

    /** 启动/刷新雷神 buff（60 秒） */
    public void startThunderGod() {
        thunderGodTicks = 60 * 20;
        // 雷神不走技能锁定，圆环要按 buff 时长一直亮到进入冷却
        holdSuperRing("thunder_3_1", serverTime + thunderGodTicks);
    }

    /** 每 tick 倒计时，返回 true 表示这一 tick 刚好到期 */
    public boolean tickThunderGod() {
        if (thunderGodTicks <= 0) {
            return false;
        }
        thunderGodTicks--;
        return thunderGodTicks == 0;
    }

    /** 立即结束雷神 buff */
    public void clearThunderGod() {
        thunderGodTicks = 0;
    }

    // ------------------------------------------------------------------
    // 风系：技能锁定 + 风之翼 buff
    // ------------------------------------------------------------------

    /**
     * 指定技能是否处于锁定状态（风系持续技能使用期间，需求文档“技能锁定”）。
     *
     * <p>锁定按技能 id 区分：锁定期间只禁止再次释放<b>这个技能</b>，
     * 其他技能（包括同系列更高/更低的技能）照常可用。</p>
     */
    public boolean isSkillLocked(String skillId, long gameTime) {
        Long end = skillLocks.get(skillId);
        if (end == null) {
            return false;
        }
        if (gameTime >= end) {
            skillLocks.remove(skillId);
            return false;
        }
        return true;
    }

    /** 指定技能的锁定还要持续多少 tick */
    public long lockRemaining(String skillId, long gameTime) {
        Long end = skillLocks.get(skillId);
        return end == null ? 0L : Math.max(0L, end - gameTime);
    }

    /** 锁定指定技能到某个游戏 tick */
    public void lockSkill(String skillId, long untilGameTime) {
        skillLocks.put(skillId, untilGameTime);
        // 超阶技能持续期间会锁住自己：圆环跟着技能时间延长，直到效果结束进入冷却
        if (skillId.equals(superRingSkillId) && untilGameTime > superRingEndTick) {
            superRingEndTick = untilGameTime;
        }
    }

    /** 解除指定技能的锁定（该技能效果结束时调用） */
    public void clearSkillLock(String skillId) {
        skillLocks.remove(skillId);
    }

    /** 解除全部技能锁定（死亡/下线/重置时调用） */
    public void clearSkillLock() {
        skillLocks.clear();
    }

    // ------------------------------------------------------------------
    // 超阶圆环状态（服务端持有，客户端由 MagicCastRingSync 广播绘制）
    // ------------------------------------------------------------------

    /**
     * 释放超阶技能时开启圆环。
     *
     * @param defaultTicks 兜底时长：技能没有上报持续时间时（瞬发、生成失败）至少显示这么久
     */
    public void beginSuperRing(String skillId, int elementOrdinal, long now, int defaultTicks) {
        superRingSkillId = skillId;
        superRingElement = elementOrdinal;
        superRingEndTick = now + defaultTicks;
    }

    /**
     * 把当前超阶圆环延长到技能效果结束（火神/雷神等不走技能锁定的 buff 用）。
     * 只对正在进行的那个超阶技能生效，避免误改别的技能。
     */
    public void holdSuperRing(String skillId, long endTick) {
        if (skillId.equals(superRingSkillId) && endTick > superRingEndTick) {
            superRingEndTick = endTick;
        }
    }

    /** 技能正式进入冷却：圆环立刻结束（下一 tick 由同步器广播清除） */
    public void endSuperRing(String skillId) {
        if (skillId.equals(superRingSkillId)) {
            superRingEndTick = Math.min(superRingEndTick, serverTime);
        }
    }

    /** 强制清掉圆环（死亡/下线/重置） */
    public void clearSuperRing() {
        superRingSkillId = null;
        superRingElement = -1;
        superRingEndTick = 0L;
    }

    /** 当前是否该画圆环 */
    public boolean superRingActive(long now) {
        return superRingSkillId != null && superRingElement >= 0 && now < superRingEndTick;
    }

    public int superRingElement() {
        return superRingElement;
    }

    public long superRingEndTick() {
        return superRingEndTick;
    }

    public int superRingSentElement() {
        return superRingSentElement;
    }

    public long superRingSentEndTick() {
        return superRingSentEndTick;
    }

    /** 记录这次广播的状态，避免每 tick 重复发包 */
    public void markSuperRingSent(int elementOrdinal, long endTick) {
        superRingSentElement = elementOrdinal;
        superRingSentEndTick = endTick;
    }

    /** 风之翼主动 buff 是否生效（护甲/魔抗 +50、4 面风盾） */
    public boolean windWingsActive() {
        return windWingsTicks > 0;
    }

    public int windWingsTicks() {
        return windWingsTicks;
    }

    /** 启动/刷新风之翼 buff（30 秒） */
    public void startWindWings() {
        windWingsTicks = 30 * 20;
    }

    /** 每 tick 倒计时，返回 true 表示这一 tick 刚好到期 */
    public boolean tickWindWings() {
        if (windWingsTicks <= 0) {
            return false;
        }
        windWingsTicks--;
        return windWingsTicks == 0;
    }

    /** 立即结束风之翼 buff */
    public void clearWindWings() {
        windWingsTicks = 0;
    }

    /** 上次广播的跟随特效开关位（见 {@link MagicFxSync}） */
    public int fxFlagsSent() {
        return fxFlagsSent;
    }

    public void fxFlagsSent(int flags) {
        this.fxFlagsSent = flags;
    }

    public int talentPoints() {
        return talentPoints;
    }

    public void setTalentPoints(int points) {
        this.talentPoints = Math.max(0, points);
    }

    public void addTalentPoints(int points) {
        setTalentPoints(this.talentPoints + points);
    }

    public int talentLevel(MagicTalent talent) {
        return talents[talent.ordinal()];
    }

    public int[] talentsCopy() {
        return talents.clone();
    }

    /** 加天赋点，返回实际加上的点数（受上限限制） */
    public int addTalent(MagicTalent talent, int points) {
        int index = talent.ordinal();
        int before = talents[index];
        talents[index] = Math.min(talent.maxLevel(), before + Math.max(0, points));
        int used = talents[index] - before;
        talentPoints = Math.max(0, talentPoints - used);
        return used;
    }

    /** 直接设置天赋等级（指令/读档用） */
    public void setTalentLevel(MagicTalent talent, int level) {
        talents[talent.ordinal()] = Math.max(0, Math.min(talent.maxLevel(), level));
    }

    // ------------------------------------------------------------------
    // 觉醒与技能
    // ------------------------------------------------------------------

    public List<MagicElement> awakened() {
        return List.copyOf(awakened);
    }

    public boolean hasAwakened(MagicElement element) {
        return awakened.contains(element);
    }

    /** 还能不能再觉醒一个系列 */
    public boolean canAwaken() {
        return awakened.size() < Math.min(MAX_SERIES, rank.maxSeries());
    }

    /** 觉醒一个系列，成功返回 true */
    public boolean awaken(MagicElement element) {
        if (!canAwaken() || awakened.contains(element)) {
            return false;
        }
        awakened.add(element);
        return true;
    }

    /** 已觉醒系列对应的按键序号（0..3），未觉醒返回 -1 */
    public int slotOf(MagicElement element) {
        return awakened.indexOf(element);
    }

    public Set<String> learnedSkills() {
        return Set.copyOf(learned);
    }

    public boolean isLearned(String skillId) {
        return learned.contains(skillId);
    }

    public void learn(String skillId) {
        learned.add(skillId);
    }

    public void unlearn(String skillId) {
        learned.remove(skillId);
    }

    /** 某系列已学习的技能（按注册表顺序） */
    public List<SkillDefinition> learnedSkills(MagicElement element) {
        List<SkillDefinition> result = new ArrayList<>();
        for (SkillDefinition skill : SkillRegistry.of(element)) {
            if (learned.contains(skill.id())) {
                result.add(skill);
            }
        }
        return result;
    }

    /** 某系列当前选中的技能（未选过则取第一个已学技能） */
    public @Nullable SkillDefinition selectedSkill(MagicElement element) {
        String id = selected.get(element);
        if (id != null) {
            SkillDefinition skill = SkillRegistry.byId(id);
            if (skill != null && learned.contains(id)) {
                return skill;
            }
        }
        List<SkillDefinition> list = learnedSkills(element);
        return list.isEmpty() ? null : list.get(0);
    }

    public @Nullable String selectedId(MagicElement element) {
        SkillDefinition skill = selectedSkill(element);
        return skill == null ? null : skill.id();
    }

    public void selectSkill(MagicElement element, String skillId) {
        selected.put(element, skillId);
    }

    // ------------------------------------------------------------------
    // 冷却（每个技能单独计时）
    // ------------------------------------------------------------------

    /** 该技能还要冷却多少 tick（0 表示可用） */
    public long cooldownRemaining(String skillId, long gameTime) {
        Long end = cooldownEnd.get(skillId);
        if (end == null) {
            return 0L;
        }
        long remain = end - gameTime;
        if (remain <= 0L) {
            cooldownEnd.remove(skillId);
            return 0L;
        }
        return remain;
    }

    /**
     * 写入冷却结束 tick。云冰晶界生效期间，<b>所有技能</b>的冷却统一减半：
     * 这里按"剩余时长"压缩，所以技能内部"效果结束后补写完整冷却"的分支同样自动生效。
     */
    public void setCooldown(String skillId, long endTick) {
        if (iceDomainActive && endTick > 0L && endTick > serverTime) {
            endTick = serverTime + (endTick - serverTime) / 2L;
        }
        cooldownEnd.put(skillId, endTick);
    }

    public Map<String, Long> cooldowns() {
        return cooldownEnd;
    }

    /** 清空全部冷却（调试/重生用） */
    public void clearCooldowns() {
        cooldownEnd.clear();
    }

    // ------------------------------------------------------------------
    // 蓝量恢复
    // ------------------------------------------------------------------

    /**
     * 每 tick 调用：按当前阶级的恢复速度回蓝。
     *
     * @return 是否发生了变化（变化了就需要同步给客户端）
     */
    public boolean tickManaRegen() {
        if (mana >= maxMana()) {
            manaRegenTicks = 0;
            return false;
        }
        if (++manaRegenTicks < rank.manaRegenSeconds() * 20) {
            return false;
        }
        manaRegenTicks = 0;
        mana = Math.min(maxMana(), mana + 1.0D);
        return true;
    }

    /** 重置全部数据（指令 /magic reset） */
    public void resetAll() {
        rank = MagicRank.NOVICE;
        mana = BASE_MANA;
        talentPoints = 0;
        java.util.Arrays.fill(talents, 0);
        awakened.clear();
        learned.clear();
        selected.clear();
        cooldownEnd.clear();
        manaRegenTicks = 0;
        fireGodTicks = 0;
        thunderGodTicks = 0;
        windWingsTicks = 0;
        mindValorTicks = 0;
        mindFantasyActive = false;
        clearBless();
        iceDomainActive = false;
        clearSkillLock();
    }

    // ------------------------------------------------------------------
    // 存档（ValueIO）：全部用简单字符串编码，避免复杂 Codec，读档容错
    // ------------------------------------------------------------------

    @Override
    public void serialize(ValueOutput out) {
        out.putInt("rank", rank.ordinal());
        out.putDouble("mana", mana);
        out.putInt("talentPoints", talentPoints);

        StringBuilder talentBuf = new StringBuilder();
        for (int i = 0; i < talents.length; i++) {
            if (i > 0) {
                talentBuf.append(',');
            }
            talentBuf.append(talents[i]);
        }
        out.putString("talents", talentBuf.toString());

        StringBuilder awakenBuf = new StringBuilder();
        for (MagicElement element : awakened) {
            if (awakenBuf.length() > 0) {
                awakenBuf.append(',');
            }
            awakenBuf.append(element.id());
        }
        out.putString("awakened", awakenBuf.toString());

        out.putString("learned", String.join(",", learned));

        StringBuilder selectBuf = new StringBuilder();
        for (Map.Entry<MagicElement, String> entry : selected.entrySet()) {
            if (selectBuf.length() > 0) {
                selectBuf.append(',');
            }
            selectBuf.append(entry.getKey().id()).append('=').append(entry.getValue());
        }
        out.putString("selected", selectBuf.toString());
    }

    @Override
    public void deserialize(ValueInput in) {
        rank = MagicRank.byOrdinal(in.getIntOr("rank", 0));
        talentPoints = Math.max(0, in.getIntOr("talentPoints", 0));

        java.util.Arrays.fill(talents, 0);
        String talentCsv = in.getStringOr("talents", "");
        if (!talentCsv.isEmpty()) {
            String[] parts = talentCsv.split(",");
            for (int i = 0; i < parts.length && i < talents.length; i++) {
                try {
                    talents[i] = Math.max(0, Integer.parseInt(parts[i].trim()));
                } catch (NumberFormatException ignored) {
                    talents[i] = 0;
                }
            }
        }
        mana = in.getDoubleOr("mana", BASE_MANA);
        mana = Math.max(0.0D, Math.min(maxMana(), mana));

        awakened.clear();
        String awakenCsv = in.getStringOr("awakened", "");
        if (!awakenCsv.isEmpty()) {
            for (String id : awakenCsv.split(",")) {
                MagicElement element = MagicElement.byId(id.trim());
                if (element != null && !awakened.contains(element)) {
                    awakened.add(element);
                }
            }
        }

        learned.clear();
        String learnedCsv = in.getStringOr("learned", "");
        if (!learnedCsv.isEmpty()) {
            for (String id : learnedCsv.split(",")) {
                if (SkillRegistry.byId(id.trim()) != null) {
                    learned.add(id.trim());
                }
            }
        }

        selected.clear();
        String selectCsv = in.getStringOr("selected", "");
        if (!selectCsv.isEmpty()) {
            for (String pair : selectCsv.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                MagicElement element = MagicElement.byId(pair.substring(0, eq).trim());
                String skillId = pair.substring(eq + 1).trim();
                if (element != null && SkillRegistry.byId(skillId) != null) {
                    selected.put(element, skillId);
                }
            }
        }

        // 冷却不跨存档保留
        cooldownEnd.clear();
        manaRegenTicks = 0;
    }
}
