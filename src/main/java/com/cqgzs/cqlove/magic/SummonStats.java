package com.cqgzs.cqlove.magic;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import com.cqgzs.cqlove.PlatformMods;

/**
 * <b>召唤生物统一属性 API</b>（亡灵系 / 召唤系 / 拘灵遣将共用）。
 *
 * <p>需求（2026-11-19）：给所有「生成的生物」一套可统一生成、可后续调整的 10 项属性：</p>
 * <ol>
 *   <li>速度、跳跃、力量(攻击)、血量 —— <b>保持生物自身默认数值</b>，这里只做读取快照，不改原版属性；</li>
 *   <li>魔力值(魔法攻击力) —— 新增属性，<b>默认等于该生物自身的力量(攻击力)</b>；</li>
 *   <li>暴击 5% / 暴击伤害 100% / 魔暴击 5% / 魔暴击伤害 100% / 元素精通 100 —— 全部取
 *       {@link MagicTalent} 的出生默认值，与玩家侧口径一致。</li>
 * </ol>
 *
 * <p>这些「魔法属性」在玩家侧是 {@link PlayerMagicData} 的字段、不是原版 Attribute，所以召唤物这边
 * 用 NeoForge Attachment 挂一份 {@link Stats} 承载（不序列化：召唤物是临时实体，不进存档）。
 * 以后要调数值，统一改 {@link #generate(LivingEntity)} 或用 {@code setXxx} 覆盖即可。</p>
 *
 * <h3>接入方式</h3>
 * 各系生成召唤物后调一次 {@link #generate(LivingEntity)}；读取用 {@code get(entity).xxx()}；
 * 需要让召唤物按这套属性结算伤害时，用 {@link #magicDamage(ServerPlayer, LivingEntity, LivingEntity, double, MagicElement)}
 * 或 {@link #physicalDamage}（本轮先把 API 与数值建好，伤害公式后续可逐系接入）。
 */
public final class SummonStats {

    /** 附件注册器，挂到 mod 事件总线（见 PlatformMods 构造） */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PlatformMods.MODID);

    /** 每个生物一份的属性数据：cqlove:summon_stats */
    public static final Supplier<AttachmentType<Stats>> CREATURE_STATS =
            ATTACHMENTS.register("summon_stats",
                    () -> AttachmentType.builder(Stats::new).build());

    private SummonStats() {
    }

    // ==================================================================
    // 数据
    // ==================================================================

    /** 一只生物的 10 项属性。前 4 项来自原版 Attribute 快照，后 6 项是模组自定义数值。 */
    public static final class Stats {
        /** 是否已生成过（Attachment 首次创建为 false，generate 后置 true），避免每 tick 重算 */
        private boolean generated;
        public boolean isGenerated() { return generated; }
        private void markGenerated() { this.generated = true; }

        // 原版 4 项（快照，保持生物自身默认，不写回实体）
        private double speed;
        private double jump;
        private double strength;
        private double health;
        // 模组 6 项
        private double magicPower;
        private double critChance;
        private double critDamage;
        private double magicCritChance;
        private double magicCritDamage;
        private double elementMastery;

        public double speed() { return speed; }
        public double jump() { return jump; }
        public double strength() { return strength; }
        public double health() { return health; }
        public double magicPower() { return magicPower; }
        public double critChance() { return critChance; }
        public double critDamage() { return critDamage; }
        public double magicCritChance() { return magicCritChance; }
        public double magicCritDamage() { return magicCritDamage; }
        public double elementMastery() { return elementMastery; }

        public void setSpeed(double v) { this.speed = v; }
        public void setJump(double v) { this.jump = v; }
        public void setStrength(double v) { this.strength = v; }
        public void setHealth(double v) { this.health = v; }
        public void setMagicPower(double v) { this.magicPower = v; }
        public void setCritChance(double v) { this.critChance = v; }
        public void setCritDamage(double v) { this.critDamage = v; }
        public void setMagicCritChance(double v) { this.magicCritChance = v; }
        public void setMagicCritDamage(double v) { this.magicCritDamage = v; }
        public void setElementMastery(double v) { this.elementMastery = v; }

        @Override
        public String toString() {
            return String.format("SummonStats{speed=%.3f jump=%.3f atk=%.1f hp=%.1f magic=%.1f "
                            + "crit=%.1f%% critDmg=%.0f%% mCrit=%.1f%% mCritDmg=%.0f%% mastery=%.0f}",
                    speed, jump, strength, health, magicPower,
                    critChance, critDamage, magicCritChance, magicCritDamage, elementMastery);
        }
    }

    // ==================================================================
    // 生成 / 访问 API
    // ==================================================================

    /** 取生物的属性数据（不存在时自动创建一份全 0 的空白数据；正式使用前请先 generate） */
    public static Stats get(LivingEntity entity) {
        return entity.getData(CREATURE_STATS);
    }

    /**
     * 生成一只召唤生物的全部属性（幂等：重复调用会按实体当前原版属性重算）。
     *
     * <p>原版 4 项只读快照、不写回实体，所以「生物数值默认」不变；新增魔力值取当前攻击力，
     * 其余百分比类直接取玩家侧同款出生默认值。</p>
     */
    public static Stats generate(LivingEntity entity) {
        Stats s = get(entity);
        // 1) 原版 4 项：读取实体自身当前值作为快照，不修改实体
        s.setSpeed(safeAttribute(entity, Attributes.MOVEMENT_SPEED));
        s.setJump(safeAttribute(entity, Attributes.JUMP_STRENGTH));
        double attack = safeAttribute(entity, Attributes.ATTACK_DAMAGE);
        s.setStrength(attack);
        s.setHealth(entity.getMaxHealth());

        // 2) 新增魔力值 = 生物自身力量(攻击力)；后续各系可 setMagicPower 覆盖
        s.setMagicPower(attack);

        // 3) 百分比类：直接复用玩家侧出生默认，保持口径一致
        s.setCritChance(MagicTalent.CRIT.baseValue());
        s.setCritDamage(MagicTalent.CRIT_DAMAGE.baseValue());
        s.setMagicCritChance(MagicTalent.MAGIC_CRIT.baseValue());
        s.setMagicCritDamage(MagicTalent.MAGIC_CRIT_DAMAGE.baseValue());
        s.setElementMastery(MagicTalent.ELEMENT_MASTERY.baseValue());
        s.markGenerated();
        return s;
    }

    /** 该生物是否已生成过属性（供全局兜底事件判断是否跳过） */
    public static boolean isGenerated(LivingEntity entity) {
        return get(entity).isGenerated();
    }

    /** 安全读取原版属性：某些生物没有对应 Attribute（例如无攻击力的生物）时返回 0 */
    private static double safeAttribute(LivingEntity entity, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        var instance = entity.getAttribute(attribute);
        return instance == null ? 0.0D : instance.getValue();
    }

    // ==================================================================
    // 伤害结算辅助（API 预留，伤害公式后续可逐系接入）
    // ==================================================================

    /**
     * 用召唤物的属性结算一次魔法伤害（魔力值 + 元素精通缩放 + 魔暴击）。
     *
     * @param owner       召唤物主人（用于 PVP 判定 / 伤害来源归属）
     * @param attacker    实际攻击的召唤生物（属性来源）
     * @param target      受击目标
     * @param skillDamage 技能面板伤害
     */
    public static boolean magicDamage(net.minecraft.server.level.ServerPlayer owner,
                                      LivingEntity attacker, LivingEntity target,
                                      double skillDamage, MagicElement element) {
        if (target == attacker || !target.isAlive()) {
            return false;
        }
        if (target instanceof net.minecraft.world.entity.player.Player victim
                && !com.cqgzs.cqlove.lan.PvpApi.canHurt(owner, victim)) {
            return false;
        }
        Stats s = get(attacker);
        double base = skillDamage + s.magicPower();
        base *= s.elementMastery() / 100.0D;
        double chance = s.magicCritChance() / 100.0D;
        double critDmg = s.magicCritDamage() / 100.0D;
        boolean crit = attacker.getRandom().nextDouble() < chance;
        double finalDamage = crit ? base * (1.0D + critDmg) : base;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) attacker.level();
        return target.hurtServer(level, MagicDamageTypes.source(level, element, owner), (float) finalDamage);
    }

    /**
     * 用召唤物的属性结算一次物理伤害（暴击 / 暴击伤害）。
     * 调用方传入已由原版攻击算好的基础伤害，这里只按暴击放大。
     */
    public static boolean physicalDamage(net.minecraft.server.level.ServerPlayer owner,
                                         LivingEntity attacker, LivingEntity target, double baseDamage) {
        if (target == attacker || !target.isAlive()) {
            return false;
        }
        if (target instanceof net.minecraft.world.entity.player.Player victim
                && !com.cqgzs.cqlove.lan.PvpApi.canHurt(owner, victim)) {
            return false;
        }
        Stats s = get(attacker);
        double chance = s.critChance() / 100.0D;
        double critDmg = s.critDamage() / 100.0D;
        boolean crit = attacker.getRandom().nextDouble() < chance;
        double finalDamage = crit ? baseDamage * (1.0D + critDmg) : baseDamage;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) attacker.level();
        return target.hurtServer(level, MagicDamageTypes.source(level, MagicElement.EARTH, owner), (float) finalDamage);
    }
}
