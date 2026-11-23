package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import com.cqgzs.cqlove.fx.FxBillboard;
import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.lan.PvpApi;
import com.cqgzs.cqlove.network.MagicCirclePayload;

/**
 * 亡灵系 8 个技能（2026-11-19 设计实现）。
 *
 * <p>四阶沿用需求文档组名：亡骸 / 亡灵 / 亡界 / 亡灵天灾。定位为<b>亡灵仆从 + 灵体作战</b>。</p>
 *
 * <ul>
 *   <li>初阶·亡骸：亡灵生成（召唤骷髅小白协同作战，再次释放收回且不进冷却，骷髅死亡才进冷却）、
 *       肉体构造（同款僵尸）、亡灵空间（3 骷髅 + 3 僵尸，30 秒后原地爆炸，每只 9 点）。</li>
 *   <li>中阶·亡灵：招魂术（召唤鬼魂：生命 30 / 物理攻击 0 / 魔法攻击 10 / 离地一格悬浮，
 *       永久跟随可叠加召唤）、无体之灵（10 秒灵体化：免疫物理伤害 + 移速 40% + 近战附魔 20）、
 *       亡灵杀手（骨矛贯穿：150 点，对不死生物翻倍并剥离增益）。</li>
 *   <li>高阶·亡界：跟随领域 30 秒（无伤害）：敌对每 2 秒叠一层失明 + 缓慢（各至 II），
 *       友方持续夜视 + 生命恢复 II + 抗性 I。</li>
 *   <li>超阶·亡灵天灾：60 秒天灾：施法者免疫负面状态；范围内亡灵生物获得力量 II / 速度 II /
 *       抗性 I；召唤 20 只随机亡灵（小白 / 僵尸 / 尸壳 / 流浪者 / 溺尸 / 凋灵骷髅），
 *       死亡自动补召并跟随玩家攻击目标；60 秒结束全部爆炸（每只 4 格 500 点）。</li>
 * </ul>
 *
 * <p>仆从的敌我判定统一走 {@link CombatTracker}：只打玩家攻击过的生物与敌对生物，
 * 永远不打主人与同门仆从（见 {@link #validServantTarget}）。鬼魂本体用隐形 Vex 承载 AI，
 * 外观由客户端虚拟面片（{@link MagicItems#UNDEAD_GHOST}）渲染，本体物理攻击在
 * {@link #onIncomingDamage} 里转换为亡灵魔法伤害。</p>
 */
public final class UndeadMagic {

    private UndeadMagic() {
    }

    // ---------------- 时长（tick） ----------------
    private static final int SPACE_LIFE = 30 * 20;      // 亡灵空间：召唤物 30 秒后爆炸
    private static final int WRAITH_TICKS = 10 * 20;    // 无体之灵：10 秒
    private static final int WORLD_TICKS = 30 * 20;     // 亡界：30 秒
    private static final int CATACLYSM_TICKS = 60 * 20; // 亡灵天灾：60 秒

    // ---------------- 伤害数值 ----------------
    private static final double SPACE_EXPLOSION = 9.0D;   // 亡灵空间爆炸
    private static final double GHOST_MAGIC = 10.0D;      // 鬼魂魔法攻击
    private static final double WRAITH_MELEE = 20.0D;     // 无体之灵近战附魔
    private static final double SLAYER_ATTACK = 30.0D;     // 亡灵杀手：召唤物基础攻击
    private static final double SLAYER_HEALTH = 120.0D;    // 亡灵杀手：召唤物生命
    private static final int SLAYER_LIMIT = 3;             // 亡灵杀手：可叠加数量
    private static final double GHOST_HEALTH = 30.0D;      // 招魂术：鬼魂生命
    private static final double CATACLYSM_EXPLOSION = 500.0D; // 天灾结束爆炸

    // ---------------- 范围 ----------------
    private static final double FOLLOW_RANGE = 16.0D;     // 仆从自动找敌半径
    private static final double CATACLYSM_ARMY = 20;      // 天灾召唤数量

    /** 运行时：玩家最近攻击的生物（所有仆从协同攻击的同一目标） */
    private static final Map<ServerPlayer, LivingEntity> SHARED_TARGETS = new HashMap<>();

    // ==================================================================
    // 初阶·亡骸
    // ==================================================================

    /** 亡灵生成：召唤骷髅小白；再次释放收回（退蓝不进冷却）；骷髅死亡则进入冷却 */
    public static void summonSkeleton(SkillContext ctx) {
        castPair(ctx, Kind.PAIR_SKELETON, "骷髅小白");
    }

    /** 肉体构造：召唤僵尸；规则与亡灵生成一致 */
    public static void summonZombie(SkillContext ctx) {
        castPair(ctx, Kind.PAIR_ZOMBIE, "僵尸");
    }

    /** 亡灵空间：3 骷髅 + 3 僵尸，30 秒后爆炸（每只 9 点） */
    public static void undeadSpace(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        long expire = now + SPACE_LIFE;
        for (int i = 0; i < 3; i++) {
            spawnServant(ctx, Kind.SPACE, EntityType.SKELETON, "亡灵空间·骷髅", expire, SPACE_EXPLOSION, 3.0D);
            spawnServant(ctx, Kind.SPACE, EntityType.ZOMBIE, "亡灵空间·僵尸", expire, SPACE_EXPLOSION, 3.0D);
        }
        Vec3 at = player.position();
        soulBurst(level, at.add(0.0D, 1.0D, 0.0D), 2.2D);
        play(level, at, SoundEvents.WITHER_SPAWN, 0.8F, 1.6F);
        NoticeApi.send(player, Component.literal("亡灵空间: 6 只亡灵仆从已展开, 30 秒后爆炸")
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * 初阶单召唤（亡灵生成 / 肉体构造）：有活着的召唤物就收回（退蓝 + 清冷却），没有就召唤。
     * 召唤后立刻清掉施法流程写入的冷却——技能只在召唤物死亡时才进入冷却。
     */
    private static void castPair(SkillContext ctx, Kind kind, String title) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        String skillId = ctx.skill().id();

        Servant existing = findPairServant(player, skillId);
        if (existing != null) {
            recallServant(existing);
            PlayerMagicData data = MagicData.get(player);
            data.setMana(data.mana() + ctx.skill().manaCost());
            data.setCooldown(skillId, 0L);
            MagicSync.sync(player);
            NoticeApi.send(player, Component.literal(title + "已收回 (耗蓝与冷却已退还)")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        spawnServant(ctx, kind, kind == Kind.PAIR_SKELETON ? EntityType.SKELETON : EntityType.ZOMBIE,
                title, 0L, 0.0D, 0.0D);
        // 召唤成功：清冷却（死亡时再写回），让"再次释放=收回"可以立即生效
        PlayerMagicData data = MagicData.get(player);
        data.setCooldown(skillId, 0L);
        MagicSync.sync(player);
        Vec3 at = player.position().add(0.0D, 1.0D, 0.0D);
        soulBurst(level, at, 1.2D);
        play(level, at, SoundEvents.WITHER_SPAWN, 0.6F, 1.9F);
        NoticeApi.send(player, Component.literal(title + "已召唤, 再次释放可收回")
                .withStyle(ChatFormatting.GRAY));
    }

    // ==================================================================
    // 中阶·亡灵
    // ==================================================================

    /**
     * 招魂术：召唤一只<b>纯虚拟鬼魂</b>。
     *
     * <p>旧实现用隐形 Vex 承载 AI，赶路时玩家会透过半透明面片看到里面的恼鬼模型；
     * 现在改成服务端零实体：只有一张客户端面片 + 一份位置/生命记录，由
     * {@link #tickGhosts()} 每 tick 驱动跟随、悬浮与魔法攻击。服务端只发消息，
     * 客户端只渲染面片，彻底看不到「恶灵在里面」。</p>
     *
     * <p>生命 30、物理攻击 0（没有实体也就没有物理命中）、每 20 tick 对目标造成
     * 10 点亡灵魔法伤害、离地约一格悬浮、永久跟随、可叠加召唤。</p>
     */
    public static void soulSummon(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 pos = player.position().add(1.4D, 1.3D, 0.0D);
        // 立体鬼魂：真实方块拼出的漂浮人形，不再是平面图片
        GhostVisual visual = new GhostVisual(level, pos);
        GHOSTS.add(new Ghost(player, level, pos, visual));
        soulBurst(level, pos, 1.4D);
        play(level, pos, SoundEvents.SOUL_ESCAPE.value(), 1.2F, 0.6F);
        NoticeApi.send(player, Component.literal("鬼魂已降临 (生命 30 / 魔法攻击 10 / 永久跟随, 可叠加召唤)")
                .withStyle(ChatFormatting.GRAY));
    }

    /** 无体之灵：10 秒灵体化——免疫物理伤害 + 移速 +40% + 近战附加 20 点亡灵伤害 */
    public static void wraithForm(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        WRAITHS.add(new Wraith(player, level, now + WRAITH_TICKS));
        applyModifier(player, Attributes.MOVEMENT_SPEED, WRAITH_SPEED_ID, 0.4D,
                AttributeModifier.Operation.ADD_VALUE);
        soulBurst(level, player.position().add(0.0D, 1.0D, 0.0D), 1.6D);
        play(level, player.position(), SoundEvents.SOUL_ESCAPE.value(), 1.0F, 0.8F);
        NoticeApi.send(player, Component.literal("无体之灵: 10 秒灵体化, 免疫物理伤害")
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * 亡灵杀手（重做）：不再是一次性攻击，而是<b>召唤一只精英亡灵跟随玩家作战</b>。
     *
     * <p>召唤强化凋灵骷髅（生命 120 / 攻击 30 / 免疫阳光），最多同时存在 3 只，
     * 统一走 {@link SummonRegistry} 的选敌优先级：先打玩家攻击过的生物，再打玩家身边的敌对生物。
     * 攻击不死生物时伤害翻倍（在 {@link #onIncomingDamage} 里结算）。
     * 任意一只阵亡，技能进入冷却。</p>
     */
    public static void undeadSlayer(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        int alive = 0;
        for (Servant servant : SERVANTS) {
            if (servant.owner == player && servant.kind == Kind.SLAYER
                    && servant.entity.isAlive() && !servant.entity.isRemoved()) {
                alive++;
            }
        }
        if (alive >= SLAYER_LIMIT) {
            failCast(ctx, "亡灵杀手最多同时存在 " + SLAYER_LIMIT + " 只");
            return;
        }
        spawnServant(ctx, Kind.SLAYER, EntityType.WITHER_SKELETON, "亡灵杀手",
                0L, 0.0D, 0.0D);
        // 召唤后清冷却：让「叠加召唤」立刻可用，阵亡时再写回冷却
        PlayerMagicData data = MagicData.get(player);
        data.setCooldown(ctx.skill().id(), 0L);
        MagicSync.sync(player);
        Vec3 at = player.position().add(0.0D, 1.0D, 0.0D);
        soulBurst(level, at, 1.6D);
        play(level, at, SoundEvents.WITHER_SPAWN, 0.9F, 1.5F);
        NoticeApi.send(player, Component.literal(String.format(
                "亡灵杀手已降临 (%d/%d, 对不死生物伤害翻倍, 阵亡则进入冷却)", alive + 1, SLAYER_LIMIT))
                .withStyle(ChatFormatting.GRAY));
    }

    // ==================================================================
    // 高阶·亡界
    // ==================================================================

    /** 亡界：跟随领域 30 秒，无伤害；敌对叠失明/缓慢，友方持续增益 */
    public static void undeadWorld(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        beginTimed(ctx, WORLD_TICKS);
        double radius = ctx.range();
        DOMAINS.add(new Domain(player, level, now + WORLD_TICKS, radius, now + 40L, 0));
        MagicCircleSync.start(player, MagicCirclePayload.UNDEAD_WORLD, WORLD_TICKS, (float) radius);
        soulBurst(level, player.position().add(0.0D, 0.2D, 0.0D), radius);
        play(level, player.position(), SoundEvents.WITHER_SPAWN, 0.9F, 1.2F);
        NoticeApi.send(player, Component.literal(String.format(
                "亡界已展开 (%.0f 格, 30 秒): 敌对叠加失明与缓慢, 友方持续增益", radius))
                .withStyle(ChatFormatting.GRAY));
    }

    // ==================================================================
    // 超阶·亡灵天灾
    // ==================================================================

    /** 亡灵天灾：60 秒。免疫负面 + 增益圈内亡灵 + 20 只随机亡灵补召作战，结束全部爆炸 500 点 */
    public static void cataclysm(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        beginTimed(ctx, CATACLYSM_TICKS);
        double radius = ctx.range();
        purgeHarmfulEffects(player);
        Cataclysm cataclysm = new Cataclysm(player, level, now + CATACLYSM_TICKS, radius,
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()), now + 20L);
        CATACLYSMS.add(cataclysm);
        // 第一波 10 只立即召唤，剩余由补召逻辑逐步补满
        for (int i = 0; i < 10; i++) {
            spawnArmyMinionFor(cataclysm, now + CATACLYSM_TICKS);
        }
        MagicCircleSync.start(player, MagicCirclePayload.UNDEAD_WORLD, CATACLYSM_TICKS, (float) radius);
        soulBurst(level, player.position().add(0.0D, 0.3D, 0.0D), radius);
        play(level, player.position(), SoundEvents.WITHER_SPAWN, 1.2F, 0.8F);
        NoticeApi.send(player, Component.literal(String.format(
                "亡灵天灾: 60 秒 (%.0f 格) — 免疫负面 / 增益亡灵 / %d 只随机亡灵作战, 结束时全部爆炸",
                radius, CATACLYSM_ARMY))
                .withStyle(ChatFormatting.GRAY));
    }

    // ==================================================================
    // 仆从（召唤物）系统
    // ==================================================================

    private enum Kind { PAIR_SKELETON, PAIR_ZOMBIE, SPACE, GHOST, SLAYER, ARMY }

    /** 一只仆从：初阶召唤 / 空间亡灵 / 鬼魂 / 天灾大军通用 */
    private static final class Servant {
        final ServerLevel level;
        final ServerPlayer owner;
        final LivingEntity entity;
        final Kind kind;
        final String skillId;
        final int cooldownSeconds;
        final long expireTick;      // 0 = 永久（鬼魂）
        final double explodeDamage; // 0 = 到期不爆炸
        final double explodeRadius;
        final @Nullable FxBillboard visual; // 鬼魂的客户端面片外观

        Servant(ServerLevel level, ServerPlayer owner, LivingEntity entity, Kind kind,
                String skillId, int cooldownSeconds, long expireTick,
                double explodeDamage, double explodeRadius, @Nullable FxBillboard visual) {
            this.level = level;
            this.owner = owner;
            this.entity = entity;
            this.kind = kind;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
            this.expireTick = expireTick;
            this.explodeDamage = explodeDamage;
            this.explodeRadius = explodeRadius;
            this.visual = visual;
        }
    }

    private static final List<Servant> SERVANTS = new ArrayList<>();

    /** 召一只仆从并登记（配合存活的 AI 定向 / 跟随逻辑） */
    private static void spawnServant(SkillContext ctx, Kind kind, EntityType<? extends Mob> type,
                                     String title, long expireTick, double explodeDamage, double explodeRadius) {
        ServerPlayer owner = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        Mob mob = type.create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
        if (mob == null) {
            return;
        }
        Vec3 pos = groundSpot(level, owner.position(), 1.8D + level.getRandom().nextDouble() * 1.2D);
        mob.setPos(pos.x, pos.y, pos.z);
        mob.setPersistenceRequired(); // 不自然消失
        mob.setCustomName(Component.literal(title).withStyle(ChatFormatting.GRAY));
        if (mob instanceof Vex vex) {
            // 旧鬼魂载体（招魂术已改为纯虚拟，这里保留兼容）
            vex.getAttribute(Attributes.MAX_HEALTH).setBaseValue(30.0D);
            vex.setHealth(30.0F);
            vex.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(1.0D);
            vex.setInvisible(true);
        } else if (kind == Kind.SLAYER) {
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(SLAYER_HEALTH);
            mob.setHealth((float) SLAYER_HEALTH);
            mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(SLAYER_ATTACK);
            mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.32D);
            mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 40, 0, false, false));
        } else if (mob.getType() == EntityType.SKELETON) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        }
        level.addFreshEntity(mob);
        // 全模组统一召唤物登记表：区分不同玩家归属，PVP 关闭时受保护
        SummonRegistry.register(mob, owner);

        FxBillboard visual = null;
        if (kind == Kind.GHOST) {
            visual = DisplayFx.spawn(level, mob.position().add(0.0D, 0.35D, 0.0D),
                    MagicItems.UNDEAD_GHOST.get(), 1.15F, 1.15F, 1.15F,
                    net.minecraft.world.entity.Display.BillboardConstraints.CENTER);
        }
        String skillId = ctx.skill().id();
        int cooldownSeconds = ctx.skill().cooldownSeconds(MagicData.get(owner).rank());
        SERVANTS.add(new Servant(level, owner, mob, kind, skillId, cooldownSeconds,
                expireTick, explodeDamage, explodeRadius, visual));
    }

    // ==================================================================
    // 仆从（召唤物）系统
    // ==================================================================
    /** 天灾大军可随机召唤的亡灵类型 */
    private static final EntityType<? extends Mob>[] ARMY_TYPES = new EntityType[]{
            EntityType.SKELETON, EntityType.ZOMBIE, EntityType.HUSK,
            EntityType.STRAY, EntityType.DROWNED, EntityType.WITHER_SKELETON
    };

    private static final String[] ARMY_TITLES = {"天灾·骷髅", "天灾·僵尸", "天灾·尸壳", "天灾·流浪者", "天灾·溺尸", "天灾·凋灵骷髅"};

    private static int titleIndexOf(EntityType<? extends Mob> type) {
        EntityType<?>[] all = ARMY_TYPES;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == type) {
                return i;
            }
        }
        return 0;
    }

    /** 找初阶单召唤的现有仆从 */
    private static @Nullable Servant findPairServant(ServerPlayer owner, String skillId) {
        for (Servant servant : SERVANTS) {
            if (servant.owner == owner && servant.skillId.equals(skillId)
                    && (servant.kind == Kind.PAIR_SKELETON || servant.kind == Kind.PAIR_ZOMBIE)
                    && servant.entity.isAlive() && !servant.entity.isRemoved()) {
                return servant;
            }
        }
        return null;
    }

    /** 收回一只仆从（正常消失，不爆炸） */
    private static void recallServant(Servant servant) {
        if (servant.visual != null) {
            servant.visual.discard();
        }
        SummonRegistry.unregister(servant.entity);
        servant.entity.discard();
        soulBurst(servant.level, servant.entity.position().add(0.0D, 1.0D, 0.0D), 1.0D);
    }

    /** 仆从死亡：初阶单召唤写回冷却，其余自然移除 */
    private static void onServantDeath(Servant servant) {
        if (servant.visual != null) {
            servant.visual.discard();
        }
        SummonRegistry.unregister(servant.entity);
        if ((servant.kind == Kind.PAIR_SKELETON || servant.kind == Kind.PAIR_ZOMBIE
                || servant.kind == Kind.SLAYER)
                && servant.owner.isAlive()) {
            PlayerMagicData data = MagicData.get(servant.owner);
            data.setCooldown(servant.skillId,
                    servant.level.getGameTime() + servant.cooldownSeconds * 20L);
            MagicSync.sync(servant.owner);
            NoticeApi.send(servant.owner, Component.literal(
                    servant.entity.getName().getString() + "陨落, 技能进入冷却")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    // ==================================================================
    // 每 tick 驱动（MagicEvents.onServerTick）
    // ==================================================================

    public static void tickWorld() {
        long pruneTick = -1L;
        Iterator<Servant> iterator = SERVANTS.iterator();
        while (iterator.hasNext()) {
            Servant servant = iterator.next();
            LivingEntity entity = servant.entity;
            if (entity.isRemoved() || !entity.isAlive()) {
                onServantDeath(servant);
                iterator.remove();
                continue;
            }
            if (!servant.owner.isAlive() || servant.owner.isRemoved()
                    || servant.owner.level() != servant.level) {
                recallServant(servant);
                iterator.remove();
                continue;
            }
            long now = servant.level.getGameTime();

            // 召唤出来的亡灵不怕阳光：每 tick 熄灭自燃 + 常驻防火，
            // 原版骷髅/僵尸/尸壳/流浪者/溺尸白天会自燃，这里统一压住
            protectFromSunlight(entity, now);

            //  vanilla AI 选了不该打的目标（主人/同门仆从/友方）时立刻纠正
            if (entity instanceof Mob mob && mob.getTarget() != null
                    && !validServantTarget(servant.owner, mob.getTarget())) {
                mob.setTarget(null);
            }

            // 每 10 tick（按实体 id 错峰）定向 + 跟随
            if (now % 10L == Math.floorMod(entity.getId(), 10)) {
                retarget(servant);
                follow(servant);
            }

            // 鬼魂：面片跟随 + 离地悬浮 + 魂火
            if (servant.kind == Kind.GHOST) {
                double bob = Math.sin(now * 0.12D + entity.getId()) * 0.12D;
                if (servant.visual != null) {
                    servant.visual.setPos(entity.getX(), entity.getY() + 0.35D + bob, entity.getZ());
                }
                keepHovering(servant.level, entity);
                if (now % 12L == 0L) {
                    servant.level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                            entity.getX(), entity.getY() + 0.6D, entity.getZ(),
                            2, 0.25D, 0.35D, 0.25D, 0.01D);
                }
            }

            // 到期爆炸（亡灵空间 30 秒 / 天灾大军 60 秒）
            if (servant.expireTick > 0L && now >= servant.expireTick) {
                explode(servant.owner, servant.level, entity.position()
                                .add(0.0D, entity.getBbHeight() * 0.5D, 0.0D),
                        servant.explodeRadius, servant.explodeDamage);
                if (servant.visual != null) {
                    servant.visual.discard();
                }
                SummonRegistry.unregister(entity);
                entity.discard();
                iterator.remove();
                continue;
            }
            pruneTick = now;
        }

        tickGhosts();
        tickWraiths();
        tickDomains();
        tickCataclysms();
        // 协同攻击目标记录定期清一次失效项
        if (pruneTick > 0L && pruneTick % 200L == 0L) {
            SHARED_TARGETS.values().removeIf(target -> !target.isAlive() || target.isRemoved());
        }
    }

    /**
     * 给仆从指定目标：统一走 {@link SummonRegistry#preferredTarget} 的优先级
     * —— 第一优先玩家攻击过的生物，第二优先玩家身边的敌对生物。
     * 有优先目标时强制转火（不保留 vanilla 自选目标），否则让 vanilla 自己选。
     */
    private static void retarget(Servant servant) {
        if (!(servant.entity instanceof Mob mob)) {
            return;
        }
        LivingEntity preferred = SummonRegistry.preferredTarget(servant.owner, servant.level,
                mob, FOLLOW_RANGE);
        if (preferred != null && mob.getTarget() != preferred) {
            mob.setTarget(preferred);
        }
    }

    /** 跟随主人：太远传送回身边，较远寻路跟随 */
    private static void follow(Servant servant) {
        LivingEntity entity = servant.entity;
        ServerPlayer owner = servant.owner;
        double distSqr = entity.distanceToSqr(owner);
        if (distSqr > 24.0D * 24.0D) {
            Vec3 spot = groundSpot(servant.level, owner.position(), 1.5D);
            entity.teleportTo(spot.x, spot.y, spot.z);
            if (servant.visual != null) {
                servant.visual.setPos(spot.x, spot.y + 0.35D, spot.z);
            }
            return;
        }
        if (distSqr > 10.0D * 10.0D && entity instanceof Mob mob) {
            mob.getNavigation().moveTo(owner.getX(), owner.getY(), owner.getZ(), 1.15D);
        }
    }

    /** 仆从的合法目标：非主人 / 非同门 / 非隐身，并走统一敌我判定 */
    private static boolean validServantTarget(ServerPlayer owner, LivingEntity candidate) {
        return SummonRegistry.isValidEnemy(owner, candidate);
    }

    private static boolean isServant(Entity entity) {
        return SummonRegistry.isSummoned(entity);
    }

    /** 亡灵仆从不怕阳光：熄灭自燃 + 常驻防火（原版亡灵白天自燃会掉血并冒火） */
    private static void protectFromSunlight(LivingEntity entity, long now) {
        if (entity.getRemainingFireTicks() > 0) {
            entity.clearFire();
        }
        if (now % 40L == 0L) {
            entity.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 100, 0, false, false));
        }
    }

    /** 鬼魂保持离地一格悬浮（无目标闲飘时向下压得太低就托起来） */
    private static void keepHovering(ServerLevel level, LivingEntity entity) {
        HitResult hit = level.clip(new ClipContext(entity.position(),
                entity.position().add(0.0D, -6.0D, 0.0D),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        if (hit.getType() == HitResult.Type.MISS) {
            return;
        }
        double groundY = hit.getLocation().y;
        if (entity.getY() < groundY + 0.7D) {
            Vec3 motion = entity.getDeltaMovement();
            entity.setDeltaMovement(motion.x, Math.max(motion.y, 0.08D), motion.z);
        }
    }

    // ==================================================================
    // 招魂术：纯虚拟鬼魂（服务端零实体，客户端只有一张面片）
    // ==================================================================

    /**
     * 立体鬼魂外观：一组真实方块拼出的漂浮人形（头 / 躯干 / 双臂 / 双眼），
     * 由客户端 Display.BlockDisplay 渲染，有厚度、绕着看不穿帮，也不是平面贴图。
     */
    private static final class GhostVisual {
        final List<com.cqgzs.cqlove.fx.FxBlock> blocks = new ArrayList<>();
        /** 各块相对鬼魂中心的偏移：x, y, z, sx, sy, sz, 方块 */
        private static final Object[][] PARTS = {
                {0.0D, 0.62D, 0.0D, 0.42F, 0.42F, 0.42F, net.minecraft.world.level.block.Blocks.SOUL_SAND},      // 头
                {0.0D, 0.12D, 0.0D, 0.52F, 0.62F, 0.42F, net.minecraft.world.level.block.Blocks.SOUL_SOIL},     // 躯干
                {-0.44D, 0.18D, 0.0D, 0.22F, 0.50F, 0.22F, net.minecraft.world.level.block.Blocks.SOUL_SOIL},  // 左臂
                {0.44D, 0.18D, 0.0D, 0.22F, 0.50F, 0.22F, net.minecraft.world.level.block.Blocks.SOUL_SOIL},   // 右臂
                {-0.14D, 0.66D, 0.23D, 0.12F, 0.12F, 0.12F, net.minecraft.world.level.block.Blocks.GLOWSTONE}, // 左眼
                {0.14D, 0.66D, 0.23D, 0.12F, 0.12F, 0.12F, net.minecraft.world.level.block.Blocks.GLOWSTONE}   // 右眼
        };

        GhostVisual(ServerLevel level, Vec3 pos) {
            for (Object[] part : PARTS) {
                net.minecraft.world.level.block.Block block = (net.minecraft.world.level.block.Block) part[6];
                blocks.add(com.cqgzs.cqlove.fx.FxNet.block(level, pos, block.defaultBlockState(),
                        (Float) part[3], (Float) part[4], (Float) part[5], null, true));
            }
            setPos(pos);
        }

        void setPos(Vec3 pos) {
            for (int i = 0; i < blocks.size(); i++) {
                Object[] part = PARTS[i];
                // FxBlock 锚点在方块下角，回退半个自身尺寸让人形各部位相对中心对称
                double hx = (Float) part[3] * 0.5D;
                double hy = (Float) part[4] * 0.5D;
                double hz = (Float) part[5] * 0.5D;
                blocks.get(i).setPos(pos.x + (Double) part[0] - hx,
                        pos.y + (Double) part[1] - hy,
                        pos.z + (Double) part[2] - hz);
            }
        }

        void discard() {
            for (var block : blocks) {
                block.discard();
            }
            blocks.clear();
        }
    }

    private static final class Ghost {
        final ServerPlayer owner;
        final ServerLevel level;
        Vec3 pos;
        final GhostVisual visual;
        double hp = GHOST_HEALTH;
        int attackCooldown;

        Ghost(ServerPlayer owner, ServerLevel level, Vec3 pos, GhostVisual visual) {
            this.owner = owner;
            this.level = level;
            this.pos = pos;
            this.visual = visual;
        }
    }

    private static final List<Ghost> GHOSTS = new ArrayList<>();

    /** 每 tick 驱动虚拟鬼魂：跟随 / 离地悬浮 / 魔法攻击 / 被围攻时消耗灵体值 */
    private static void tickGhosts() {
        Iterator<Ghost> iterator = GHOSTS.iterator();
        while (iterator.hasNext()) {
            Ghost ghost = iterator.next();
            long now = ghost.level.getGameTime();
            if (!ghost.owner.isAlive() || ghost.owner.isRemoved()
                    || ghost.owner.level() != ghost.level || ghost.hp <= 0.0D) {
                ghost.visual.discard();
                soulBurst(ghost.level, ghost.pos, 0.9D);
                iterator.remove();
                continue;
            }

            Vec3 home = ghost.owner.position().add(0.0D, 1.15D, 0.0D);
            // 统一选敌：第一优先玩家攻击过的生物，第二优先玩家身边的敌对生物
            LivingEntity target = SummonRegistry.preferredTarget(ghost.owner, ghost.level,
                    ghost.pos, FOLLOW_RANGE);
            Vec3 want;
            if (target != null) {
                Vec3 aim = target.position().add(0.0D, target.getBbHeight() * 0.75D, 0.0D);
                Vec3 delta = aim.subtract(ghost.pos);
                double len = delta.length();
                want = len > 1.6D
                        ? ghost.pos.add(delta.scale(Math.min(0.5D, len - 1.6D) / len))
                        : ghost.pos;
            } else {
                double angle = now * 0.055D + ghost.owner.getId() * 0.7D;
                want = home.add(Math.cos(angle) * 1.7D, Math.sin(now * 0.11D) * 0.22D,
                        Math.sin(angle) * 1.7D);
            }
            // 离地约一格：夹在主人脚下与头顶上方 1.4 格之间
            double y = Math.max(home.y - 0.15D, Math.min(home.y + 1.4D, want.y));
            want = new Vec3(want.x, y, want.z);
            ghost.pos = ghost.pos.add(want.subtract(ghost.pos).scale(0.35D));
            ghost.visual.setPos(ghost.pos);

            if (now % 10L == 0L) {
                ghost.level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                        ghost.pos.x, ghost.pos.y, ghost.pos.z, 2, 0.2D, 0.3D, 0.2D, 0.01D);
            }

            // 魔法攻击：贴近目标后每 20 tick 造成 10 点亡灵魔法伤害（物理攻击为 0）
            if (ghost.attackCooldown > 0) {
                ghost.attackCooldown--;
            }
            if (target != null && ghost.attackCooldown <= 0
                    && ghost.pos.distanceTo(target.position().add(0.0D,
                            target.getBbHeight() * 0.6D, 0.0D)) < 2.6D) {
                ghost.attackCooldown = 20;
                MagicDamage.magicDamage(ghost.owner, target, GHOST_MAGIC, MagicElement.UNDEAD);
                ghost.level.sendParticles(ParticleTypes.SOUL,
                        target.getX(), target.getY() + 1.0D, target.getZ(), 10,
                        0.3D, 0.4D, 0.3D, 0.03D);
            }

            // 灵体值 30：被周围敌对生物近身时逐渐消散（每 20 tick 每只 -2）
            if (now % 20L == 0L) {
                for (LivingEntity foe : ghost.level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(ghost.pos, ghost.pos).inflate(1.6D))) {
                    if (SummonRegistry.isValidEnemy(ghost.owner, foe)) {
                        ghost.hp -= 2.0D;
                    }
                }
            }
        }
    }

    // ==================================================================
    // 无体之灵
    // ==================================================================

    private static final List<Wraith> WRAITHS = new ArrayList<>();
    private static final net.minecraft.resources.Identifier WRAITH_SPEED_ID =
            net.minecraft.resources.Identifier.fromNamespaceAndPath(com.cqgzs.cqlove.PlatformMods.MODID,
                    "undead_wraith_speed");

    private record Wraith(ServerPlayer owner, ServerLevel level, long endTick) {
    }

    private static void tickWraiths() {
        Iterator<Wraith> iterator = WRAITHS.iterator();
        while (iterator.hasNext()) {
            Wraith wraith = iterator.next();
            long now = wraith.level().getGameTime();
            if (!wraith.owner().isAlive() || now >= wraith.endTick()) {
                removeModifier(wraith.owner(), Attributes.MOVEMENT_SPEED, WRAITH_SPEED_ID);
                if (wraith.owner().isAlive()) {
                    soulBurst(wraith.level, wraith.owner().position().add(0.0D, 1.0D, 0.0D), 1.0D);
                }
                iterator.remove();
                continue;
            }
            if (now % 8L == 0L) {
                Vec3 at = wraith.owner().position().add(0.0D, 1.0D, 0.0D);
                wraith.level.sendParticles(ParticleTypes.SOUL,
                        at.x, at.y, at.z, 2, 0.45D, 0.7D, 0.45D, 0.02D);
                wraith.level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                        at.x, at.y, at.z, 1, 0.5D, 0.8D, 0.5D, 0.01D);
            }
        }
    }

    /** 该玩家是否处于无体之灵 */
    public static boolean isWraith(ServerPlayer player) {
        for (Wraith wraith : WRAITHS) {
            if (wraith.owner() == player) {
                return true;
            }
        }
        return false;
    }

    /** 无体之灵近战附魔：物理近战命中时附加 20 点亡灵魔法伤害（MagicEvents.onLivingDamage 调用） */
    public static void onWraithMelee(ServerPlayer attacker, LivingEntity victim) {
        if (!isWraith(attacker) || victim == attacker || !victim.isAlive()) {
            return;
        }
        if (!(victim.level() instanceof ServerLevel level)) {
            return;
        }
        victim.hurtServer(level, MagicDamageTypes.source(level, MagicElement.UNDEAD, attacker),
                (float) WRAITH_MELEE);
        DamageNumbers.spawn(level, victim.position().add(0.0D, victim.getBbHeight() + 0.25D, 0.0D),
                WRAITH_MELEE, MagicElement.UNDEAD.color());
    }

    // ==================================================================
    // 高阶·亡界
    // ==================================================================

    private static final List<Domain> DOMAINS = new ArrayList<>();

    private static final class Domain {
        final ServerPlayer owner;
        final ServerLevel level;
        final long endTick;
        final double radius;
        long nextPulseTick;
        int pulses;

        Domain(ServerPlayer owner, ServerLevel level, long endTick, double radius,
               long nextPulseTick, int pulses) {
            this.owner = owner;
            this.level = level;
            this.endTick = endTick;
            this.radius = radius;
            this.nextPulseTick = nextPulseTick;
            this.pulses = pulses;
        }
    }

    private static void tickDomains() {
        Iterator<Domain> iterator = DOMAINS.iterator();
        while (iterator.hasNext()) {
            Domain domain = iterator.next();
            long now = domain.level.getGameTime();
            if (now >= domain.endTick || !domain.owner.isAlive()
                    || domain.owner.level() != domain.level) {
                MagicCircleSync.stop(domain.owner, MagicCirclePayload.UNDEAD_WORLD);
                iterator.remove();
                continue;
            }
            if (now < domain.nextPulseTick) {
                continue;
            }
            domain.nextPulseTick += 40L;
            domain.pulses++;
            // 叠层：第 2 次脉冲起升到 II（失明 / 缓慢各至 II）
            int amplifier = Math.min(1, domain.pulses / 2);
            AABB area = domain.owner.getBoundingBox().inflate(domain.radius);
            for (LivingEntity entity : domain.level.getEntitiesOfClass(LivingEntity.class, area)) {
                if (CombatTracker.isEnemyTarget(domain.owner, entity)) {
                    entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 80, amplifier));
                    entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, amplifier));
                    domain.level.sendParticles(ParticleTypes.SMOKE,
                            entity.getX(), entity.getY() + entity.getBbHeight() * 0.5D, entity.getZ(),
                            6, 0.3D, 0.4D, 0.3D, 0.01D);
                } else if (CombatTracker.isAllyTarget(domain.owner, entity)) {
                    entity.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 160, 0));
                    entity.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 80, 1));
                    entity.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 80, 0));
                    domain.level.sendParticles(ParticleTypes.SOUL,
                            entity.getX(), entity.getY() + entity.getBbHeight() * 0.5D, entity.getZ(),
                            4, 0.3D, 0.4D, 0.3D, 0.01D);
                }
            }
            if (now % 120L == 0L) {
                soulBurst(domain.level, domain.owner.position().add(0.0D, 0.2D, 0.0D), domain.radius * 0.5D);
            }
        }
    }

    // ==================================================================
    // 超阶·亡灵天灾
    // ==================================================================

    private static final List<Cataclysm> CATACLYSMS = new ArrayList<>();

    private static final class Cataclysm {
        final ServerPlayer owner;
        final ServerLevel level;
        final long endTick;
        final double radius;
        final int cooldownSeconds;
        long nextRespawnTick;

        Cataclysm(ServerPlayer owner, ServerLevel level, long endTick, double radius,
                  int cooldownSeconds, long nextRespawnTick) {
            this.owner = owner;
            this.level = level;
            this.endTick = endTick;
            this.radius = radius;
            this.cooldownSeconds = cooldownSeconds;
            this.nextRespawnTick = nextRespawnTick;
        }
    }

    private static void tickCataclysms() {
        Iterator<Cataclysm> iterator = CATACLYSMS.iterator();
        while (iterator.hasNext()) {
            Cataclysm cataclysm = iterator.next();
            long now = cataclysm.level.getGameTime();
            if (now >= cataclysm.endTick || !cataclysm.owner.isAlive()
                    || cataclysm.owner.level() != cataclysm.level) {
                finishCataclysm(cataclysm, now);
                iterator.remove();
                continue;
            }
            // 1. 免疫负面：每 20 tick 清一次已附着的负面（新施加的由事件钩子拦截）
            if (now % 20L == 0L) {
                purgeHarmfulEffects(cataclysm.owner);
            }
            // 2. 伤亡补召：每 20 tick 最多补 1 只
            if (now >= cataclysm.nextRespawnTick) {
                cataclysm.nextRespawnTick = now + 20L;
                int alive = 0;
                for (Servant servant : SERVANTS) {
                    if (servant.owner == cataclysm.owner && servant.kind == Kind.ARMY
                            && servant.entity.isAlive() && !servant.entity.isRemoved()) {
                        alive++;
                    }
                }
                if (alive < CATACLYSM_ARMY) {
                    spawnArmyMinionFor(cataclysm, cataclysm.endTick);
                }
            }
            // 3. 圈内亡灵生物增益：每 40 tick 力量 II / 速度 II / 抗性 I
            if (now % 40L == 0L) {
                AABB area = cataclysm.owner.getBoundingBox().inflate(cataclysm.radius);
                for (Mob mob : cataclysm.level.getEntitiesOfClass(Mob.class, area)) {
                    if (!mob.getType().builtInRegistryHolder().is(EntityTypeTags.UNDEAD)) {
                        continue;
                    }
                    mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 100, 1));
                    mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 1));
                    mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 0));
                }
            }
        }
    }

    /** 为天灾记录补召一只随机亡灵 */
    private static void spawnArmyMinionFor(Cataclysm cataclysm, long expireTick) {
        ServerPlayer owner = cataclysm.owner;
        ServerLevel level = cataclysm.level;
        EntityType<? extends Mob> type = ARMY_TYPES[level.getRandom().nextInt(ARMY_TYPES.length)];
        String title = ARMY_TITLES[titleIndexOf(type)];
        Mob mob = spawnArmyBodyAt(level, owner, type, title);
        if (mob == null) {
            return;
        }
        SummonRegistry.register(mob, owner);
        SERVANTS.add(new Servant(level, owner, mob, Kind.ARMY, "undead_3_1",
                cataclysm.cooldownSeconds, expireTick, CATACLYSM_EXPLOSION, 4.0D, null));
    }

    /** 天灾本体生成（不依赖 SkillContext：补召路径直接传记录） */
    private static @Nullable Mob spawnArmyBodyAt(ServerLevel level, ServerPlayer owner,
                                                 EntityType<? extends Mob> type, String title) {
        Mob mob = type.create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
        if (mob == null) {
            return null;
        }
        Vec3 pos = groundSpot(level, owner.position(), 3.0D + level.getRandom().nextDouble() * 4.0D);
        mob.setPos(pos.x, pos.y, pos.z);
        mob.setPersistenceRequired();
        mob.setCustomName(Component.literal(title).withStyle(ChatFormatting.DARK_GRAY));
        if (mob.getType() == EntityType.SKELETON || mob.getType() == EntityType.STRAY) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        }
        level.addFreshEntity(mob);
        level.sendParticles(ParticleTypes.SOUL, pos.x, pos.y + 1.0D, pos.z, 10, 0.4D, 0.6D, 0.4D, 0.02D);
        return mob;
    }

    /** 天灾结束：写回冷却 + 收圆环（爆炸由仆从到期逻辑在同一 tick 完成） */
    private static void finishCataclysm(Cataclysm cataclysm, long now) {
        ServerPlayer owner = cataclysm.owner;
        if (!owner.isAlive()) {
            return;
        }
        PlayerMagicData data = MagicData.get(owner);
        data.clearSkillLock("undead_3_1");
        data.setCooldown("undead_3_1", now + cataclysm.cooldownSeconds * 20L);
        data.endSuperRing("undead_3_1");
        MagicCircleSync.stop(owner, MagicCirclePayload.UNDEAD_WORLD);
        MagicSync.sync(owner);
        NoticeApi.send(owner, Component.literal("亡灵天灾已结束").withStyle(ChatFormatting.GRAY));
    }

    // ==================================================================
    // 事件钩子（MagicEvents 调用）
    // ==================================================================

    /**
     * 伤害事件统一入口，返回 true 表示事件已处理（调用方直接 return）。
     *
     * <ol>
     *   <li>鬼魂攻击：物理命中取消，转成 10 点亡灵魔法伤害；</li>
     *   <li>无体之灵：主人受到的物理伤害（近战/弹射物，非魔法）直接免疫；</li>
     *   <li>仆从误伤：仆从打主人 / 打 PVP 关闭下的玩家，直接吞掉。</li>
     * </ol>
     */
    public static boolean onIncomingDamage(LivingIncomingDamageEvent event) {
        DamageLikeSource source = new DamageLikeSource(event.getSource().getEntity(),
                MagicDamageTypes.isElementMagic(event.getSource()));

        LivingEntity victim = event.getEntity();

        // 1. 亡灵杀手（召唤物）攻击不死生物：伤害翻倍
        if (source.entity() != null && isSlayerBody(source.entity())
                && victim.getType().builtInRegistryHolder().is(EntityTypeTags.UNDEAD)) {
            event.setAmount(event.getAmount() * 2.0F);
        }

        // 2. 无体之灵：免疫物理伤害（非魔法的近战 / 弹射物）
        if (victim instanceof ServerPlayer player && isWraith(player) && !source.elementMagic()
                && isPhysicalSource(event)) {
            event.setCanceled(true);
            Vec3 at = player.position().add(0.0D, 1.0D, 0.0D);
            if (player.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.SOUL,
                        at.x, at.y, at.z, 3, 0.4D, 0.5D, 0.4D, 0.01D);
            }
            return true;
        }

        // 3. 仆从不伤主人 / PVP 关闭时不伤玩家
        if (source.entity() != null && isServantBody(source.entity())) {
            if (victim == source.entity()) {
                event.setCanceled(true);
                return true;
            }
            if (victim instanceof ServerPlayer victimPlayer
                    && !PvpApi.canHurt(servantOwnerOf(source.entity()), victimPlayer)) {
                event.setCanceled(true);
                return true;
            }
        }
        return false;
    }

    /** 亡灵杀手本体（强化凋灵骷髅召唤物） */
    private static boolean isSlayerBody(Entity entity) {
        for (Servant servant : SERVANTS) {
            if (servant.entity == entity && servant.kind == Kind.SLAYER) {
                return true;
            }
        }
        return false;
    }

    /** 任意仆从（含鬼魂） */
    private static boolean isServantBody(Entity entity) {
        return isServant(entity);
    }

    private static @Nullable ServerPlayer servantOwnerOf(Entity entity) {
        for (Servant servant : SERVANTS) {
            if (servant.entity == entity) {
                return servant.owner;
            }
        }
        return null;
    }

    /** 物理伤害：直接来源是生物或弹射物，且不是魔法伤害 */
    private static boolean isPhysicalSource(LivingIncomingDamageEvent event) {
        var source = event.getSource();
        if (source.is(net.minecraft.world.damagesource.DamageTypes.MAGIC)
                || source.is(net.minecraft.world.damagesource.DamageTypes.INDIRECT_MAGIC)
                || MagicDamageTypes.isElementMagic(source)) {
            return false;
        }
        return source.getDirectEntity() instanceof LivingEntity
                || source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile;
    }

    /** 亡灵天灾：免疫负面状态（MobEffectEvent.Applicable 调用，与祝福/毒系的判定同型） */
    public static boolean isEffectImmune(ServerPlayer player, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        for (Cataclysm cataclysm : CATACLYSMS) {
            if (cataclysm.owner == player && effect.value().getCategory() == MobEffectCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    /** 玩家造成伤害时由 MagicEvents 调用：登记仆从们的协同攻击目标 */
    public static void notifyPlayerAttack(ServerPlayer attacker, LivingEntity victim) {
        if (victim == null || victim == attacker) {
            return;
        }
        SHARED_TARGETS.put(attacker, victim);
    }

    /** 服务器关闭 / 重开清场 */
    public static void clearAll() {
        for (Servant servant : SERVANTS) {
            if (servant.visual != null) {
                servant.visual.discard();
            }
            SummonRegistry.unregister(servant.entity);
            if (servant.entity.isAlive()) {
                servant.entity.discard();
            }
        }
        SERVANTS.clear();
        for (Ghost ghost : GHOSTS) {
            ghost.visual.discard();
        }
        GHOSTS.clear();
        for (Wraith wraith : WRAITHS) {
            removeModifier(wraith.owner(), Attributes.MOVEMENT_SPEED, WRAITH_SPEED_ID);
        }
        WRAITHS.clear();
        for (Domain domain : DOMAINS) {
            MagicCircleSync.stop(domain.owner, MagicCirclePayload.UNDEAD_WORLD);
        }
        DOMAINS.clear();
        CATACLYSMS.clear();
        SHARED_TARGETS.clear();
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 负面状态清除（天灾开启时先把已有负面洗掉） */
    private static void purgeHarmfulEffects(ServerPlayer player) {
        for (MobEffectInstance instance : new ArrayList<>(player.getActiveEffects())) {
            if (instance.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                player.removeEffect(instance.getEffect());
            }
        }
    }

    /** 爆炸：范围亡灵魔法伤害（跳过主人 / 仆从 / PVP 关闭时的玩家）+ 魂火爆散 */
    private static void explode(ServerPlayer owner, ServerLevel level, Vec3 center,
                                double radius, double damage) {
        if (damage <= 0.0D || radius <= 0.0D) {
            return;
        }
        soulBurst(level, center, radius);
        play(level, center, SoundEvents.GENERIC_EXPLODE.value(), 1.1F, 1.4F);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius))) {
            if (!target.isAlive() || target.isSpectator() || target == owner || isServant(target)) {
                continue;
            }
            if (target instanceof Player victim && !PvpApi.canHurt(owner, victim)) {
                continue;
            }
            MagicDamage.magicDamage(owner, target, damage, MagicElement.UNDEAD);
        }
    }

    /** 魂火爆散：视觉 + 音效分离的通用特效 */
    private static void soulBurst(ServerLevel level, Vec3 center, double radius) {
        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.SOUL,
                center.x, center.y + 0.5D, center.z, (int) Math.min(60, 12 + radius * 4),
                radius * 0.4D, radius * 0.35D, radius * 0.4D, 0.05D);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                center.x, center.y + 0.4D, center.z, (int) Math.min(40, 8 + radius * 3),
                radius * 0.35D, radius * 0.3D, radius * 0.35D, 0.04D);
    }

    /** 主人脚边的落地点（ summon 时从地面钻出来的位置感） */
    private static Vec3 groundSpot(ServerLevel level, Vec3 center, double distance) {
        double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
        int x = (int) Math.floor(center.x + Math.cos(angle) * distance);
        int z = (int) Math.floor(center.z + Math.sin(angle) * distance);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return new Vec3(x + 0.5D, y, z + 0.5D);
    }

    private static void applyModifier(LivingEntity entity, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                      net.minecraft.resources.Identifier modifierId, double amount,
                                      AttributeModifier.Operation operation) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(new AttributeModifier(modifierId, amount, operation));
        }
    }

    private static void removeModifier(LivingEntity entity, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                       net.minecraft.resources.Identifier modifierId) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(modifierId);
        }
    }

    private static void play(ServerLevel level, Vec3 pos, net.minecraft.sounds.SoundEvent sound,
                             float volume, float pitch) {
        level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, volume, pitch);
    }

    private static void beginTimed(SkillContext ctx, int ticks) {
        PlayerMagicData data = MagicData.get(ctx.player());
        long now = ctx.level().getGameTime();
        data.setCooldown(ctx.skill().id(), 0L);
        data.lockSkill(ctx.skill().id(), now + ticks + 4L);
    }

    private static void failCast(SkillContext ctx, String message) {
        PlayerMagicData data = MagicData.get(ctx.player());
        data.setMana(data.mana() + ctx.skill().manaCost());
        data.setCooldown(ctx.skill().id(), 0L);
        MagicSync.sync(ctx.player());
        NoticeApi.send(ctx.player(), Component.literal(message).withStyle(ChatFormatting.RED));
    }

    /** 只读小结构：事件里判断攻击者 + 是否元素魔法 */
    private record DamageLikeSource(@org.jspecify.annotations.Nullable Entity entity, boolean elementMagic) {
    }
}
