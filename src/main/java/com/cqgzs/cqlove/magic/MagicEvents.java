package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.config.CqloveConfig;
import com.cqgzs.cqlove.lan.PvpApi;

/**
 * 魔法世界服务端事件：登录同步、蓝量恢复。
 *
 * <p>配置里“魔法世界”关闭时，这里全部直接返回（需求 二.1 的功能开关）。</p>
 */
@EventBusSubscriber(modid = PlatformMods.MODID)
public final class MagicEvents {

    private MagicEvents() {
    }

    /** 玩家进入世界：应用天赋属性 + 把魔法数据同步给客户端 */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!CqloveConfig.magicEnabled()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            MagicAttributes.apply(player);
            MagicSync.sync(player);
            // 进入/重生先清一次圆环，避免上个会话残留的状态被客户端继续画
            MagicCastRingSync.clear(player);
            MagicCircleSync.clear(player);
            // 首次进入时给一句引导，方便新玩家知道怎么开始
            PlayerMagicData data = MagicData.get(player);
            if (data.awakened().isEmpty()) {
                NoticeApi.send(player, Component.literal(
                        "魔法世界: 使用【觉醒书】觉醒魔法系列, Z/X/C/V 释放技能, 天赋书学习技能, 体质书加天赋点")
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        }
    }

    /**
     * 所有生物（含原版怪物 / 动物 / 村民，以及召唤物）进入世界时生成统一 10 项属性。
     *
     * <p>玩家不在此列：玩家属性走 {@link PlayerMagicData} + {@link MagicAttributes} 那条链路。
     * 只在服务端生成（客户端实体不需要属性数据）。召唤物随后在 SummonRegistry.register 里
     * 会再 generate 一次，用「加成后的最终攻击力」覆盖魔力值。</p>
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!CqloveConfig.magicEnabled() || event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof LivingEntity living
                && !(living instanceof net.minecraft.world.entity.player.Player)) {
            SummonStats.generate(living);
        }
    }

    /**
     * 兜底：旧存档里已经存在于世界的原版生物不会触发 EntityJoinLevelEvent，
     * 这里在实体每 tick 后检查一次，未生成过属性的补生成（已经生成则零开销跳过）。
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!CqloveConfig.magicEnabled()) {
            return;
        }
        if (event.getEntity() instanceof LivingEntity living
                && !(living instanceof net.minecraft.world.entity.player.Player)
                && living.level().isClientSide() == false
                && !SummonStats.isGenerated(living)) {
            SummonStats.generate(living);
        }
    }

    /** 每 tick：按阶级速度恢复蓝量（需求 5.1.3 d）+ 火神/雷神 buff 倒计时 + 脚下法阵 */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!CqloveConfig.magicEnabled()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerMagicData data = MagicData.get(player);
            // 云冰晶界：记录服务端时间，供"所有技能冷却 -50%"按剩余时长压缩
            data.noteGameTime(player.level().getGameTime());
            if (data.tickManaRegen()) {
                MagicSync.sync(player);
            }
            // 心灵系·无畏：站在金色圈内每 tick 刷新，离开 2 秒后魔力值 +12 自动失效
            data.tickMindValor();
            // 祝福系增益：BlessMagic.tickWorld 每 tick 刷新，离开增益范围后倒计时自然失效
            data.tickBless();
            FireMagic.tickBuff(player);
            ThunderMagic.tickBuff(player);
            WindMagic.tickPlayer(player);
            // 翅膀/法阵是客户端渲染层（贴身无延迟），这里只在状态变化时广播开关位
            MagicFxSync.tick(player, data);
            // 超阶圆环：技能持续期间持续补发剩余时间，技能进冷却时广播清除
            MagicCastRingSync.tick(player, data);
            // 心灵系圆圈：客户端渲染层贴身绘制，这里只做低频对齐（不逐 tick 发包）
            MagicCircleSync.tick(player);
        }
    }

    /** 玩家下线：收掉风系实体，免得实体留在世界里（法阵/翅膀是渲染层，无实体可收） */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MagicData.get(player).clearSuperRing();
            MagicCastRingSync.clear(player);
            WindMagic.removePlayer(player);
            WaterMagic.removePlayer(player);
            LightMagic.removePlayer(player);
            EarthMagic.removePlayer(player);
            IceMagic.removePlayer(player);
            MindMagic.removePlayer(player);
            HealMagic.removePlayer(player);
            PoisonMagic.removePlayer(player);
            BlessMagic.removePlayer(player);
            SoundMagic.removePlayer(player);
            CurseMagic.removePlayer(player);
            PlantMagic.removePlayer(player);
        }
    }

    /** 服务器关闭：清掉心灵系圆圈的服务端状态表，避免下次开服残留 */
    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        MindMagic.clearAll();
        HealMagic.clearAll();
        PoisonMagic.clearAll();
        BlessMagic.clearAll();
        SoundMagic.clearAll();
        CurseMagic.clearAll();
        PlantMagic.clearAll();
        UndeadMagic.clearAll();
        SummonMagic.clearAll();
        SpaceMagic.clearAll();
        // 元素附着 / 反应盾：同一个 JVM 重开集成服务器时不能带着上一局的实体引用
        ElementAttachment.clearAll();
        // 召唤物归属登记表：同一个 JVM 重开集成服务器时不能带着上一局的 UUID / 实体引用
        SummonRegistry.clearAll();
        // 客户端圆圈的服务端状态表也要清掉，避免同一个 JVM 里再开一次集成服务器时残留
        MagicCircleSync.clearAll();
        // 虚拟特效句柄表：同一个 JVM 重开集成服务器时不能带着上一局的 id
        com.cqgzs.cqlove.fx.FxNet.clearAll();
    }

    /** 玩家死亡：清掉全部临时 buff 与技能锁定，避免重生后还带着火神/风之翼/锁定 */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!CqloveConfig.magicEnabled()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerMagicData data = MagicData.get(player);
            data.clearFireGod();
            data.clearThunderGod();
            data.clearWindWings();
            data.clearSkillLock();
            // 死亡会清掉所有持续技能，圆环也一并收掉
            data.clearSuperRing();
            MagicCastRingSync.clear(player);
            WindMagic.removePlayer(player);
            WaterMagic.removePlayer(player);
            LightMagic.removePlayer(player);
            EarthMagic.removePlayer(player);
            IceMagic.removePlayer(player);
            MindMagic.removePlayer(player);
            HealMagic.removePlayer(player);
            PoisonMagic.removePlayer(player);
            BlessMagic.removePlayer(player);
            SoundMagic.removePlayer(player);
            CurseMagic.removePlayer(player);
            PlantMagic.removePlayer(player);
        }
    }

    /**
     * 火神焚天 buff：免疫爆炸 / 岩浆 / 火焰（需求文档火系超阶）。
     * 在伤害结算前直接取消，连击退与着火动画都不触发。
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!CqloveConfig.magicEnabled()) {
            return;
        }
        // 统一召唤物登记表：PVP 关闭时玩家不能打任何玩家的召唤物、召唤物不伤主人/玩家/同门。
        // 放在最前面，保证 MobMagic 转换后二次进入本事件时也会被拦住。
        if (SummonRegistry.onIncomingDamage(event)) {
            return;
        }
        // 心灵系：被「安抚」的生物已变成友好生物、被「心灵之墙」围住的生物无法释放魔法攻击，
        // 这两种攻击者造成的伤害（含弹射物与元素魔法）在转换之前就直接吞掉
        if (MindMagic.isPacifiedSource(event.getSource()) || MindMagic.isSilencedSource(event.getSource())) {
            event.setCanceled(true);
            return;
        }
        // 特定生物的攻击 -> 元素魔法伤害（潜影贝空间系 / 岩浆怪烈焰人火系 / 守卫者水系 ...）。
        // 转换后的伤害会再次进入本事件走下面的魔抗/免疫判定， MobMagic 内部有防递归标记
        if (MobMagic.tryConvert(event)) {
            return;
        }
        // 亡灵系：鬼魂的物理攻击转为亡灵魔法伤害 / 无体之灵免疫物理伤害 / 仆从不伤主人
        if (UndeadMagic.onIncomingDamage(event)) {
            return;
        }
        // 召唤系：召唤物打主人 / PVP 关闭时打玩家 / 同门召唤物互殴一律吞掉
        if (SummonMagic.onIncomingDamage(event)) {
            return;
        }
        // 土系：石盾减伤（物理 -60% / 魔法 -40%）、石化增伤、角斗场锁定的攻防削弱，
        // 对玩家与生物都生效（石盾也能套在生物身上）
        boolean magicHit = isMagicSource(event.getSource());
        float earthMultiplier = EarthMagic.incomingMultiplier(event.getEntity(), magicHit);
        if (earthMultiplier != 1.0F) {
            event.setAmount(event.getAmount() * earthMultiplier);
        }
        // 冰系：被封进冰封灵柩的生物受到的所有伤害 +30%
        float iceMultiplier = IceMagic.incomingMultiplier(event.getEntity());
        if (iceMultiplier != 1.0F) {
            event.setAmount(event.getAmount() * iceMultiplier);
        }
        // 心灵系：冲击 / 躁动 / 无畏 / 心灵之墙 / 永恒幻想的物理与魔法防御增减（乘算叠加）
        float mindMultiplier = MindMagic.incomingMultiplier(event.getEntity(), magicHit);
        if (mindMultiplier != 1.0F) {
            event.setAmount(event.getAmount() * mindMultiplier);
        }
        // 治愈系：生机 / 群愈 / 宝瓶星芒座 / 复苏结界的友方防御增益（乘算叠加）
        float healMultiplier = HealMagic.incomingMultiplier(event.getEntity(), magicHit);
        if (healMultiplier != 1.0F) {
            event.setAmount(event.getAmount() * healMultiplier);
        }
        // 毒系：毒牙 -10% / 毒变 -30% / 万噬之风 -50% 使圈内敌对受伤放大
        float poisonMultiplier = PoisonMagic.incomingMultiplier(event.getEntity());
        if (poisonMultiplier != 1.0F) {
            event.setAmount(event.getAmount() * poisonMultiplier);
        }
        // 祝福系：神印礼赞 +30% / 众星守护 +50% 使圈内友方受伤减少
        float blessMultiplier = BlessMagic.incomingMultiplier(event.getEntity(), magicHit);
        if (blessMultiplier != 1.0F) {
            event.setAmount(event.getAmount() * blessMultiplier);
        }
        // 诅咒系：鬼刑领域 / 灭魂易伤使敌对受伤放大（双防 -30%）
        float curseMultiplier = CurseMagic.incomingMultiplier(event.getEntity());
        if (curseMultiplier != 1.0F) {
            event.setAmount(event.getAmount() * curseMultiplier);
        }
        // 玩家初始属性·物理暴击：近战/弹射物 5% 概率暴击，暴击伤害初始 100%（翻倍），天赋再加成
        if (event.getSource().getEntity() instanceof ServerPlayer attacker
                && event.getEntity() != attacker
                && isPlayerPhysicalAttack(event.getSource(), attacker)) {
            PlayerMagicData stats = MagicData.get(attacker);
            if (attacker.getRandom().nextDouble() < stats.critChance() / 100.0D) {
                float multiplier = (float) (stats.critDamage() / 100.0D);
                event.setAmount(event.getAmount() * multiplier);
                LivingEntity critTarget = event.getEntity();
                if (critTarget.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.CRIT,
                            critTarget.getX(), critTarget.getY() + critTarget.getBbHeight() * 0.6D, critTarget.getZ(),
                            12, 0.3D, 0.35D, 0.3D, 0.2D);
                    level.playSound(null, critTarget.getX(), critTarget.getY(), critTarget.getZ(),
                            SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 1.2F);
                }
            }
        }
        // 祝福系·众星守护：结界内友方免疫毒系魔法伤害与凋零伤害（对非玩家友方同样生效）
        if (BlessMagic.isImmune(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            // 非玩家友方也要能触发复苏结界 / 灵树代受的「致死免死」
            PlantMagic.applyGraftReduction(event);
            HealMagic.onIncomingDamage(event);
            PlantMagic.onIncomingDamage(event);
            return;
        }
        PlayerMagicData data = MagicData.get(player);
        DamageSource source = event.getSource();

        // 毒系·炼化 / 毒变 / 万噬之风：施法者本人百毒不侵（负面状态由 PoisonMagic 每 tick 清除）
        if (PoisonMagic.isImmune(player, source)) {
            event.setCanceled(true);
            return;
        }

        // 心灵系·永恒幻想：免疫火焰 / 爆炸 / 岩浆 / 雷电（中毒 / 失明由 MindMagic 每 tick 清除）
        if (MindMagic.hasFantasy(player) && MindMagic.isImmuneSource(source)) {
            event.setCanceled(true);
            return;
        }

        // 云冰晶界：60 秒领域内免疫爆炸（缓慢 / 失明 / 冰冻由 IceMagic 每 tick 主动清除）
        if (IceMagic.hasDomainBuff(player)
                && (source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.PLAYER_EXPLOSION))) {
            event.setCanceled(true);
            return;
        }

        // 水花天幕：30 秒内替施法者吸收所有伤害（文档：抵挡并吸收附近 3 格的所有伤害）
        if (WaterMagic.hasVeil(player)) {
            event.setCanceled(true);
            return;
        }

        // 光系·圣盾：20 秒内受到的所有伤害 -40%
        if (LightMagic.hasHolyShield(player)) {
            event.setAmount(event.getAmount() * LightMagic.shieldMultiplier(player));
        }

        // 火神：免疫爆炸 / 岩浆 / 火焰
        if (data.fireGodActive()
                && (source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.PLAYER_EXPLOSION)
                || source.is(DamageTypes.LAVA) || source.is(DamageTypes.ON_FIRE)
                || source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.HOT_FLOOR))) {
            event.setCanceled(true);
            return;
        }
        // 雷神：免疫雷电 / 爆炸 / 火焰 / 岩浆
        if (data.thunderGodActive()
                && (source.is(DamageTypes.LIGHTNING_BOLT) || source.is(DamageTypes.EXPLOSION)
                || source.is(DamageTypes.PLAYER_EXPLOSION) || source.is(DamageTypes.LAVA)
                || source.is(DamageTypes.ON_FIRE) || source.is(DamageTypes.IN_FIRE)
                || source.is(DamageTypes.HOT_FLOOR))) {
            event.setCanceled(true);
            return;
        }
        // 风之翼：魔抗 +50 -> 受到的魔法伤害减半（含本模组的元素魔法伤害类型）
        if (data.windWingsActive() && (source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.INDIRECT_MAGIC) || MagicDamageTypes.isElementMagic(source))) {
            event.setAmount(event.getAmount() * 0.5F);
        }
        // 植物系·移花接木：伤害转移（-30% 且当次伤害 50% 转治疗），必须先于致死判定
        PlantMagic.applyGraftReduction(event);
        // 复苏结界：放在所有减伤/免疫之后，只有真正会被打死的那一击才触发
        HealMagic.onIncomingDamage(event);
        // 植物系·移花接木：灵树代受致死一击（每次施法每个友方一次）
        PlantMagic.onIncomingDamage(event);
    }

    /**
     * 万噬之风：施法者免疫中毒 / 凋零 / 饥饿 / 反胃 / 失明 / 缓慢 / 虚弱等负面状态。
     * 伤害类免疫（毒伤 / 凋零伤害）在 {@link #onIncomingDamage} 的 {@link PoisonMagic#isImmune} 处理，这里只挡状态施加。
     */
    @SubscribeEvent
    public static void onMobEffectApplicable(MobEffectEvent.Applicable event) {
        if (!CqloveConfig.magicEnabled()) {
            return;
        }
        LivingEntity affected = event.getEntity();
        Holder<MobEffect> effect = event.getEffectInstance().getEffect();
        if (affected instanceof ServerPlayer player
                && PoisonMagic.isEffectImmune(player, effect)) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
            return;
        }
        // 祝福系·众星守护：结界内友方（含非玩家）免疫负面状态
        if (BlessMagic.isEffectImmune(affected, effect)) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        }
        // 亡灵系·亡灵天灾：施法者免疫一切负面状态
        if (affected instanceof ServerPlayer player && UndeadMagic.isEffectImmune(player, effect)) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        }
    }

    /** 火神焚天 buff：玩家的弓箭/雪球/鸡蛋等弹射物命中时爆炸（需求文档火系超阶） */
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!CqloveConfig.magicEnabled()) {
            return;
        }
        if (event.getProjectile().getOwner() instanceof ServerPlayer owner
                && MagicData.get(owner).fireGodActive()) {
            FireMagic.attackExplosion(owner, event.getRayTraceResult().getLocation());
        }
    }

    /**
     * 重生/换维度时补一次属性与同步（copyOnDeath 保证数据不丢，但属性修饰符需要重挂）。
     */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!CqloveConfig.magicEnabled()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            MagicAttributes.apply(player);
            MagicSync.sync(player);
            // 进入/重生先清一次圆环，避免上个会话残留的状态被客户端继续画
            MagicCastRingSync.clear(player);
            MagicCircleSync.clear(player);
        }
    }

    /** 服务端每 tick：驱动伤害数字的上浮与到期移除 + 技能调度器（延时/持续技能效果） + 风系旋风/风刃 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        DamageNumbers.tick();
        // 虚拟特效实体：每 40 tick 补发一次完整描述（覆盖丢包 / 玩家中途进服）
        com.cqgzs.cqlove.fx.FxNet.tickServer();
        if (CqloveConfig.magicEnabled()) {
            MagicScheduler.tick();
            WindMagic.tickWorld();
            WaterMagic.tickWorld();
            LightMagic.tickWorld();
            EarthMagic.tickWorld();
            IceMagic.tickWorld();
            MindMagic.tickWorld();
            HealMagic.tickWorld();
            PoisonMagic.tickWorld();
            BlessMagic.tickWorld();
            SoundMagic.tickWorld();
            CurseMagic.tickWorld();
            PlantMagic.tickWorld();
            UndeadMagic.tickWorld();
            SummonMagic.tickWorld();
            SpaceMagic.tickWorld();
            // 元素附着：清理过期附着 + 低频同步给客户端（Jade tooltip 用）
            ElementAttachment.tickWorld();
            // 元素反应晶核盾：围绕玩家旋转 + 失效清理
            ElementShield.tickShields(event.getServer().overworld());
            // 召唤物登记表定期清理已移除实体，避免 UUID 表无限增长
            if (event.getServer().getTickCount() % 200L == 0L) {
                SummonRegistry.prune();
            }
            // 每 10 秒清一次过期的“玩家攻击记录”，防止 map 无限增长
            long now = event.getServer().overworld().getGameTime();
            if (now % 200L == 0L) {
                CombatTracker.prune(now);
            }
        }
    }

    /**
     * 物理伤害数字（需求：物理伤害显示白色数字）。
     *
     * <p>玩家直接造成的非魔法伤害（近战、弓箭等）结算后在目标头顶弹白色数字；
     * 魔法伤害（MAGIC / INDIRECT_MAGIC / 本模组的 cqlove:magic_* 元素伤害）由 {@link MagicDamage}
     * 自己用系列色报，这里跳过防止重复。</p>
     */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        if (!CqloveConfig.magicEnabled()) {
            return;
        }
        DamageSource source = event.getSource();

        // 元素附着与反应（全局兜底）：任何 cqlove:magic_* 元素魔法伤害都附着。
        // 反应伤害用的是原版 magic 源，不会被这里再次附着，避免连锁反应。
        if (MagicDamageTypes.isElementMagic(source)) {
            MagicElement element = MagicDamageTypes.elementOf(source);
            net.minecraft.world.entity.Entity cause = source.getEntity();
            if (element != null && cause instanceof LivingEntity livingCause) {
                ServerPlayer elementOwner = cause instanceof ServerPlayer sp
                        ? sp
                        : SummonRegistry.findOwner(SummonRegistry.ownerIdOf(livingCause),
                                livingCause.level() instanceof ServerLevel sl ? sl : null);
                if (elementOwner != null) {
                    float dealt = event.getHealthDamage() > 0.0F
                            ? event.getHealthDamage() : event.getInflictedDamage();
                    ElementAttachment.apply(elementOwner, livingCause, event.getEntity(), element, dealt);
                }
            }
        }

        if (source.getEntity() instanceof ServerPlayer attacker) {
            // 记录玩家攻击过的生物（风系/水系超阶技能的自动追踪目标用）
            CombatTracker.record(attacker, event.getEntity());
            // 拘灵遣将：玩家打中的目标登记为契约灵体协同攻击的同一目标
            CurseMagic.notifyPlayerAttack(attacker, event.getEntity());
            // 亡灵系：玩家打中的目标登记为亡灵仆从的协同攻击目标
            UndeadMagic.notifyPlayerAttack(attacker, event.getEntity());
            // 无体之灵：物理近战命中附加 20 点亡灵魔法伤害
            if (isPlayerPhysicalAttack(source, attacker)) {
                UndeadMagic.onWraithMelee(attacker, event.getEntity());
            }
        }
        if (!(source.getEntity() instanceof ServerPlayer)) {
            return;
        }
        if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)
                || MagicDamageTypes.isElementMagic(source)) {
            return;
        }
        float amount = event.getHealthDamage();
        if (amount <= 0.0F) {
            amount = event.getInflictedDamage();
        }
        if (amount <= 0.0F) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target.level() instanceof ServerLevel level) {
            DamageNumbers.spawn(level, target.position().add(0.0D, target.getBbHeight() + 0.25D, 0.0D),
                    amount, DamageNumbers.PHYSICAL_COLOR);
        }

        // 火神焚天 buff：玩家近战攻击附带爆炸（魔法/爆炸伤害已在上面 return，不会递归）
        if (source.getEntity() instanceof ServerPlayer attacker
                && !source.is(DamageTypes.EXPLOSION) && !source.is(DamageTypes.PLAYER_EXPLOSION)
                && MagicData.get(attacker).fireGodActive()) {
            FireMagic.attackExplosion(attacker, target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D));
        }
        // 雷神降临 buff：玩家攻击附带雷电特效（纯表现）
        if (source.getEntity() instanceof ServerPlayer attacker
                && MagicData.get(attacker).thunderGodActive()) {
            ThunderMagic.attackSpark(attacker, target);
        }
    }

    /** 供指令/书使用的 PVP 提示（避免重复代码） */
    public static void warnIfPvpDisabled(ServerPlayer player) {
        if (!PvpApi.isPvpAllowed()) {
            NoticeApi.send(player, Component.literal("提示: 当前 PVP 关闭, 技能对玩家不造成伤害")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * 这次伤害是不是该玩家<b>亲手打出</b>的物理攻击（近战 / 自己的弹射物）。
     *
     * <p>只认 direct 实体是玩家本人或玩家拥有的弹射物——荆棘（direct 是受击者）、
     * 爆炸、魔法/元素伤害都不算，防止物理暴击误伤到非攻击来源。</p>
     */
    private static boolean isPlayerPhysicalAttack(DamageSource source, ServerPlayer attacker) {
        if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)
                || MagicDamageTypes.isElementMagic(source)) {
            return false;
        }
        if (source.getDirectEntity() == attacker) {
            return true;
        }
        return source.getDirectEntity() instanceof Projectile projectile && projectile.getOwner() == attacker;
    }

    /** 这次伤害是不是魔法伤害（原版魔法 + 本模组的元素魔法） */
    private static boolean isMagicSource(DamageSource source) {
        return source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)
                || MagicDamageTypes.isElementMagic(source);
    }

    /** 空值安全的名字获取（日志用） */
    static String nameOf(@Nullable ServerPlayer player) {
        return player == null ? "<null>" : player.getName().getString();
    }
}
