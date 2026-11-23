package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.fx.FxHandle;
import com.cqgzs.cqlove.fx.FxNet;
import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.lan.PvpApi;

/**
 * 召唤系 4 个技能（2026-11-19 设计实现）。
 *
 * <p>四阶沿用需求文档组名：召唤空间 / 契约空间 / 召唤之门 / 上古魔门。
 * 定位为<b>带实体的召唤流</b>：所有召唤物都是真实生物，服务端只负责生成与驱动，
 * 传送门外观由 {@link FxNet} 的虚拟实体在客户端渲染。</p>
 *
 * <ul>
 *   <li>初阶·召唤空间：召唤一只普通 / 中立生物跟随作战，继承玩家一半属性；
 *       再次释放收回（退蓝、不进冷却）；召唤物死亡则玩家受 5% 生命上限反噬并进入冷却。</li>
 *   <li>中阶·契约空间：契约任意一只生物，收进契约空间 / 放出来可随时切换（不收蓝不进冷却）；
 *       契约生物在原有属性上再加玩家一半属性值；死亡则玩家受 10% 生命上限反噬并进入冷却；只能契约一只。</li>
 *   <li>高阶·召唤之门：玩家身后开启 4×8×1 传送门（真实屏障，玩家无法穿过），
 *       涌出 20 只随机生物（属性 +40%），死亡从门内补召，60 秒后关闭并进入冷却。</li>
 *   <li>超阶·上古魔门：同款传送门，走出一只精英 / 地形 BOSS（属性 +40% 且再加玩家 50%）；
 *       召唤物死亡立即进入冷却，未死亡 60 秒后消失并进入冷却。</li>
 * </ul>
 *
 * <p>传送门用 {@code Blocks.BARRIER} 在 4×8×1 的空位上临时立起一道墙（关闭时逐个还原空气），
 * 视觉是「门框方块实体 + 整张面片」，两者都由客户端生成，服务端世界里没有多余实体。</p>
 */
public final class SummonMagic {

    private SummonMagic() {
    }

    // ---------------- 时长 ----------------
    private static final int GATE_TICKS = 60 * 20;   // 召唤之门 / 上古魔门：60 秒

    // ---------------- 数值 ----------------
    private static final int GATE_ARMY = 20;         // 召唤之门常驻 20 只
    private static final double GATE_BUFF = 0.40D;   // 召唤之门：全属性 +40%
    private static final double ANCIENT_BUFF = 0.40D;       // 上古魔门：全属性 +40%
    private static final double ANCIENT_PLAYER_SHARE = 0.50D; // 上古魔门：再加玩家 50% 属性
    private static final double SPACE_DEATH_PENALTY = 0.05D;     // 召唤空间：死亡反噬 5% 生命上限
    private static final double CONTRACT_DEATH_PENALTY = 0.10D;  // 契约空间：死亡反噬 10% 生命上限

    // ---------------- 范围 ----------------
    private static final double FOLLOW_RANGE = 16.0D;   // 召唤物自动找敌半径
    private static final double GATE_BACK = 2.8D;       // 传送门离玩家的距离（身后）
    private static final int GATE_WIDTH = 4;
    private static final int GATE_HEIGHT = 8;

    // ---------------- 属性修饰符 id ----------------
    private static final Identifier HP_ADD_ID = id("summon_bonus_health");
    private static final Identifier ATK_ADD_ID = id("summon_bonus_attack");
    private static final Identifier ARMOR_ADD_ID = id("summon_bonus_armor");
    private static final Identifier SPEED_ADD_ID = id("summon_bonus_speed");
    private static final Identifier HP_MUL_ID = id("summon_gate_health");
    private static final Identifier ATK_MUL_ID = id("summon_gate_attack");
    private static final Identifier ARMOR_MUL_ID = id("summon_gate_armor");
    private static final Identifier SPEED_MUL_ID = id("summon_gate_speed");

    // ==================================================================
    // 技能池
    // ==================================================================

    /** 召唤空间：普通 / 中立生物（有近战或远程 AI，能真正参战） */
    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Mob>[] SPACE_TYPES = new EntityType[]{
            EntityType.WOLF, EntityType.FOX, EntityType.POLAR_BEAR, EntityType.PANDA,
            EntityType.LLAMA, EntityType.BEE, EntityType.GOAT, EntityType.IRON_GOLEM
    };
    private static final String[] SPACE_TITLES = {
            "召唤·狼", "召唤·狐狸", "召唤·北极熊", "召唤·熊猫",
            "召唤·羊驼", "召唤·蜜蜂", "召唤·山羊", "召唤·铁傀儡"
    };

    /** 召唤之门：能打的常规生物 */
    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Mob>[] GATE_TYPES = new EntityType[]{
            EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
            EntityType.SKELETON, EntityType.STRAY, EntityType.SPIDER,
            EntityType.CAVE_SPIDER, EntityType.VINDICATOR, EntityType.PILLAGER,
            EntityType.WITHER_SKELETON
    };
    private static final String[] GATE_TITLES = {
            "门卫·僵尸", "门卫·尸壳", "门卫·溺尸",
            "门卫·骷髅", "门卫·流浪者", "门卫·蜘蛛",
            "门卫·洞穴蜘蛛", "门卫·卫道士", "门卫·掠夺者",
            "门卫·凋灵骷髅"
    };

    /** 上古魔门：精英 / 地形 BOSS */
    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Mob>[] ANCIENT_TYPES = new EntityType[]{
            EntityType.RAVAGER, EntityType.EVOKER, EntityType.VINDICATOR,
            EntityType.WITHER_SKELETON, EntityType.WARDEN, EntityType.BLAZE
    };
    private static final String[] ANCIENT_TITLES = {
            "上古·劫掠兽", "上古·唤魔者", "上古·卫道士",
            "上古·凋灵骷髅", "上古·坚守者", "上古·烈焰人"
    };

    // ==================================================================
    // 初阶·召唤空间
    // ==================================================================

    /** 召唤空间：召唤 / 收回一只普通·中立生物 */
    public static void summonSpace(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        String skillId = ctx.skill().id();

        Minion existing = find(player, Kind.SPACE);
        if (existing != null) {
            discardMinion(existing);
            MINIONS.remove(existing);
            refundCast(ctx);
            Vec3 at = player.position().add(0.0D, 1.0D, 0.0D);
            summonBurst(level, at, 1.2D);
            play(level, at, SoundEvents.ENDERMAN_TELEPORT, 0.7F, 1.4F);
            NoticeApi.send(player, Component.literal("召唤物已收回 (耗蓝与冷却已退还)")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        int index = level.getRandom().nextInt(SPACE_TYPES.length);
        Mob mob = spawnBody(level, player, SPACE_TYPES[index], SPACE_TITLES[index], 2.2D);
        if (mob == null) {
            failCast(ctx, "召唤失败, 请稍后再试");
            return;
        }
        inheritHalf(mob, player);
        SummonRegistry.register(mob, player);
        MINIONS.add(new Minion(level, player, mob, Kind.SPACE, skillId,
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()), 0L, null));

        // 召唤成功：清冷却（召唤物死亡时再写回），保证「再次释放=收回」立即生效
        PlayerMagicData data = MagicData.get(player);
        data.setCooldown(skillId, 0L);
        MagicSync.sync(player);

        Vec3 at = mob.position().add(0.0D, 1.0D, 0.0D);
        summonBurst(level, at, 1.4D);
        play(level, at, SoundEvents.ENDERMAN_TELEPORT, 0.8F, 1.1F);
        NoticeApi.send(player, Component.literal(SPACE_TITLES[index]
                + " 已响应召唤 (继承你 50% 属性, 再次释放可收回)").withStyle(ChatFormatting.GRAY));
    }

    // ==================================================================
    // 中阶·契约空间
    // ==================================================================

    /**
     * 契约空间：
     * <ol>
     *   <li>没有契约生物时，对视线生物契约（收进契约空间）；</li>
     *   <li>已有契约生物时，在「放出 / 收进契约空间」之间切换，不耗蓝不冷却。</li>
     * </ol>
     */
    public static void contractSpace(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        String skillId = ctx.skill().id();

        Minion current = find(player, Kind.CONTRACT);
        if (current != null) {
            if (current.stored) {
                releaseContract(current);
                NoticeApi.send(player, Component.literal(current.entity.getName().getString()
                        + " 已从契约空间放出").withStyle(ChatFormatting.GRAY));
            } else {
                storeContract(current);
                NoticeApi.send(player, Component.literal(current.entity.getName().getString()
                        + " 已收回契约空间").withStyle(ChatFormatting.GRAY));
            }
            refundCast(ctx);
            return;
        }

        LivingEntity target = ctx.targetEntity();
        if (!(target instanceof Mob mob) || target instanceof Player || isMinion(target)) {
            failCast(ctx, "契约空间需要对准一只生物 (不能是玩家或已有召唤物)");
            return;
        }

        // 契约：原有属性 + 玩家一半属性值
        mob.setPersistenceRequired();
        mob.setCustomName(Component.literal("契约·" + mob.getName().getString())
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        applyContractBonus(mob, player);
        SummonRegistry.register(mob, player);
        Minion minion = new Minion(level, player, mob, Kind.CONTRACT, skillId,
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()), 0L, null);
        MINIONS.add(minion);
        storeContract(minion);

        Vec3 at = mob.position().add(0.0D, 1.0D, 0.0D);
        summonBurst(level, at, 1.6D);
        play(level, at, SoundEvents.ENCHANTMENT_TABLE_USE, 0.9F, 0.7F);
        NoticeApi.send(player, Component.literal("契约成功: " + mob.getName().getString()
                + " (属性 + 你的 50%, 再次释放可放出)").withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    /** 契约加成：原有属性上再加玩家一半属性值（快照，契约期间固定） */
    private static void applyContractBonus(Mob mob, ServerPlayer player) {
        addModifier(mob, Attributes.MAX_HEALTH, HP_ADD_ID, player.getMaxHealth() * 0.5D);
        addModifier(mob, Attributes.ATTACK_DAMAGE, ATK_ADD_ID,
                player.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.5D);
        addModifier(mob, Attributes.ARMOR, ARMOR_ADD_ID,
                player.getAttributeValue(Attributes.ARMOR) * 0.5D);
        addModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ADD_ID,
                player.getAttributeValue(Attributes.MOVEMENT_SPEED) * 0.5D);
        mob.setHealth(mob.getMaxHealth());
    }

    /**
     * 契约空间锚点：x/z 跟着玩家（保证所在区块被加载、不会丢实体），
     * y 压到地下约 45 格。<b>不再把生物贴身挂在玩家身上</b>，
     * 这是「吸进契约空间后像被生物拖着走」的根因。
     */
    private static Vec3 contractAnchor(Minion minion) {
        ServerLevel level = minion.level;
        double lowest = level.getMinY() + 5.0D;
        double y = Math.max(lowest, minion.owner.getY() - 45.0D);
        return new Vec3(minion.owner.getX(), y, minion.owner.getZ());
    }

    /** 收进契约空间：隐身 + 无敌 + 关物理 + 停 AI，收进远离玩家的契约空间锚点 */
    private static void storeContract(Minion minion) {
        LivingEntity entity = minion.entity;
        entity.setInvisible(true);
        entity.setInvulnerable(true);
        entity.setNoGravity(true);
        entity.setDeltaMovement(Vec3.ZERO);
        // 关掉碰撞（双保险，锚点本身已在地下，不会再与玩家重叠）
        entity.noPhysics = true;
        if (entity instanceof Mob mob) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            mob.setNoAi(true);
        }
        minion.stored = true;
        Vec3 anchor = contractAnchor(minion);
        entity.teleportTo(anchor.x, anchor.y, anchor.z);
    }

    /** 从契约空间放出：恢复全部状态并落在主人脚边 */
    private static void releaseContract(Minion minion) {
        LivingEntity entity = minion.entity;
        Vec3 spot = groundSpot(minion.level, minion.owner.position(), 1.8D);
        entity.teleportTo(spot.x, spot.y, spot.z);
        entity.setInvisible(false);
        entity.setInvulnerable(false);
        entity.setNoGravity(false);
        entity.noPhysics = false;
        entity.setDeltaMovement(Vec3.ZERO);
        if (entity instanceof Mob mob) {
            mob.setNoAi(false);
            mob.setTarget(null);
        }
        minion.stored = false;
        summonBurst(minion.level, spot.add(0.0D, 1.0D, 0.0D), 1.4D);
        play(minion.level, spot, SoundEvents.ENDERMAN_TELEPORT, 0.8F, 1.2F);
    }

    // ==================================================================
    // 高阶·召唤之门
    // ==================================================================

    /** 召唤之门：身后开门，20 只随机生物 +40%，死亡补召，60 秒关闭 */
    public static void summonGate(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        if (findGate(player, false) != null) {
            failCast(ctx, "你的召唤之门已经开启了");
            return;
        }
        Gate gate = openGate(ctx, false);
        if (gate == null) {
            failCast(ctx, "召唤之门开启失败");
            return;
        }
        for (int i = 0; i < GATE_ARMY; i++) {
            spawnGateMinion(gate);
        }
        beginTimed(ctx, GATE_TICKS);
        NoticeApi.send(player, Component.literal("召唤之门已开启: 20 只召唤物 (+40% 属性), 持续 60 秒")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    // ==================================================================
    // 超阶·上古魔门
    // ==================================================================

    /** 上古魔门：身后开门，1 只精英 / 地形 BOSS，+40% 且再加玩家 50% */
    public static void ancientGate(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        if (findGate(player, true) != null) {
            failCast(ctx, "你的上古魔门已经开启了");
            return;
        }
        Gate gate = openGate(ctx, true);
        if (gate == null) {
            failCast(ctx, "上古魔门开启失败");
            return;
        }
        int index = level.getRandom().nextInt(ANCIENT_TYPES.length);
        Mob boss = spawnBody(level, player, ANCIENT_TYPES[index], ANCIENT_TITLES[index], 2.4D);
        if (boss == null) {
            closeGate(gate, "");
            return;
        }
        applyGateBonus(boss, player, ANCIENT_BUFF, ANCIENT_PLAYER_SHARE);
        SummonRegistry.register(boss, player);
        Minion minion = new Minion(level, player, boss, Kind.ANCIENT, "summon_3_1",
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()), 0L, gate);
        gate.members.add(minion);
        gate.boss = boss;
        MINIONS.add(minion);

        beginTimed(ctx, GATE_TICKS);
        play(level, boss.position(), SoundEvents.WITHER_SPAWN, 1.0F, 0.8F);
        NoticeApi.send(player, Component.literal(ANCIENT_TITLES[index]
                + " 跨过魔门 (属性 +40% 与你的 50%), 持续 60 秒").withStyle(ChatFormatting.DARK_PURPLE));
    }

    // ==================================================================
    // 传送门
    // ==================================================================

    /** 一次传送门：位置 / 朝向 / 屏障方块 / 客户端特效句柄 / 召唤物 */
    private static final class Gate {
        final ServerLevel level;
        final ServerPlayer owner;
        final long endTick;
        final boolean ancient;
        final Vec3 center;
        final double baseY;
        final Vec3 look;
        final Vec3 right;
        final int cooldownSeconds;
        final Set<BlockPos> barriers = new LinkedHashSet<>();
        final List<FxHandle> visuals = new ArrayList<>();
        final List<Minion> members = new ArrayList<>();
        @Nullable Mob boss;
        long nextSpawnTick;

        Gate(ServerLevel level, ServerPlayer owner, long endTick, boolean ancient, Vec3 center,
             double baseY, Vec3 look, Vec3 right, int cooldownSeconds) {
            this.level = level;
            this.owner = owner;
            this.endTick = endTick;
            this.ancient = ancient;
            this.center = center;
            this.baseY = baseY;
            this.look = look;
            this.right = right;
            this.cooldownSeconds = cooldownSeconds;
            this.nextSpawnTick = level.getGameTime() + 10L;
        }

        int aliveCount() {
            int alive = 0;
            for (Minion minion : members) {
                if (minion.entity.isAlive() && !minion.entity.isRemoved()) {
                    alive++;
                }
            }
            return alive;
        }
    }

    private static final List<Gate> GATES = new ArrayList<>();

    /** 在玩家身后立起 4×8×1 的传送门（屏障墙 + 客户端特效） */
    private static @Nullable Gate openGate(SkillContext ctx, boolean ancient) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        // 门朝向吸附到最近主轴：屏障是整数格，只有轴对齐才能让 4 列屏障、
        // 4 段门框与中央面片严格重合（斜向时浮点偏移取整会缺列/错位 = 框架定位错误）
        Vec3 rawLook = flatLook(player);
        boolean alongX = Math.abs(rawLook.x) >= Math.abs(rawLook.z);
        Vec3 look = alongX
                ? new Vec3(rawLook.x >= 0.0D ? 1.0D : -1.0D, 0.0D, 0.0D)
                : new Vec3(0.0D, 0.0D, rawLook.z >= 0.0D ? 1.0D : -1.0D);
        Vec3 right = new Vec3(-look.z, 0.0D, look.x).normalize();
        BlockPos anchor = BlockPos.containing(player.position().add(look.scale(-GATE_BACK)));
        double baseY = anchor.getY();
        // 屏障 4 列格心偏移 -2..1，几何中心落在 anchor 格心沿 right 的 -0.5 格处；
        // 门面/门框必须用同一个中心，否则和屏障错开一整格（旧 bug：反向看时门框飘一边）。
        Vec3 center = new Vec3(anchor.getX() + 0.5D, baseY, anchor.getZ() + 0.5D)
                .add(right.scale(-0.5D));

        String skillId = ctx.skill().id();
        Gate gate = new Gate(level, player, level.getGameTime() + GATE_TICKS, ancient, center,
                baseY, look, right, ctx.skill().cooldownSeconds(MagicData.get(player).rank()));

        // 1. 屏障：整数格，沿 right 轴 4 列 × 8 行，关闭时逐个还原。
        // 列偏移用右向量 rightSign 跟随 look 的正负号，保证和门面/门框的 center 严格重合。
        int rightSign = alongX ? (look.x >= 0.0D ? 1 : -1) : (look.z >= 0.0D ? 1 : -1);
        for (int col = 0; col < GATE_WIDTH; col++) {
            int offset = (col - GATE_WIDTH / 2) * rightSign;
            for (int row = 0; row < GATE_HEIGHT; row++) {
                BlockPos pos = alongX
                        ? anchor.offset(0, row, offset)
                        : anchor.offset(offset, row, 0);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.canBeReplaced()) {
                    level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), 3);
                    gate.barriers.add(pos);
                }
            }
        }

        // 2. 视觉：一张整面片 + 4 段门框（黄铜/紫晶色）
        buildPortalVisuals(gate);

        GATES.add(gate);
        Vec3 at = new Vec3(center.x, baseY + GATE_HEIGHT / 2.0D, center.z);
        level.sendParticles(ancient ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.REVERSE_PORTAL,
                at.x, at.y, at.z, 30, 2.0D, 3.6D, 0.4D, 0.05D);
        play(level, at, ancient ? SoundEvents.END_PORTAL_SPAWN : SoundEvents.PORTAL_TRIGGER, 1.0F, ancient ? 0.6F : 1.2F);
        return gate;
    }

    private static void buildPortalVisuals(Gate gate) {
        double yaw = Math.atan2(gate.look.x, gate.look.z);
        Quaternionf rotation = DisplayFx.yawRotation(yaw);
        Vec3 mid = new Vec3(gate.center.x, gate.baseY + GATE_HEIGHT / 2.0D, gate.center.z);

        gate.visuals.add(DisplayFx.spawn(gate.level, mid,
                gate.ancient ? MagicItems.SUMMON_PORTAL_DARK.get() : MagicItems.SUMMON_PORTAL.get(),
                GATE_WIDTH, GATE_HEIGHT, 1.0F, Display.BillboardConstraints.FIXED, rotation));

        BlockState frame = (gate.ancient ? Blocks.GILDED_BLACKSTONE : Blocks.CRYING_OBSIDIAN)
                .defaultBlockState();
        // BlockDisplay 的方块模型锚点在 (0,0,0) 下角、scale 从这里向正方向生长；
        // 所以位置要给「左下起点」而不是中心。之前给中心导致立柱上移半高、横梁右移半宽。
        Vec3 right = gate.right;
        double half = GATE_WIDTH / 2.0D;
        double pillarW = 0.35D;
        // 左右立柱：从门底 baseY 向上长满 GATE_HEIGHT，横向落在 ±half 处（锚点回退半个柱宽居中）
        for (int side = -1; side <= 1; side += 2) {
            Vec3 foot = gate.center.add(right.scale(side * half - (side > 0 ? pillarW : 0.0D)));
            gate.visuals.add(FxNet.block(gate.level,
                    new Vec3(foot.x, gate.baseY, foot.z), frame,
                    0.35F, (float) GATE_HEIGHT, 0.35F, null, true));
        }
        // 上下横梁：从左端（center - right*half）沿 right 方向长 GATE_WIDTH；
        // 底梁在 baseY，顶梁在 baseY + GATE_HEIGHT - 梁高，避免高出一半。
        // 方块沿局部 +X 生长，用 leftRotation 把 +X 旋到 right 方向（门沿 X 或 Z 都对）。
        double beamYaw = Math.atan2(-gate.look.x, -gate.look.z);
        Quaternionf beamRot = new Quaternionf().rotateY((float) beamYaw);
        double beamH = 0.35D;
        for (int row = 0; row <= 1; row++) {
            double y = row == 0 ? gate.baseY : gate.baseY + GATE_HEIGHT - beamH;
            Vec3 start = gate.center.add(right.scale(-half));
            gate.visuals.add(FxNet.block(gate.level,
                    new Vec3(start.x, y, start.z), frame,
                    (float) GATE_WIDTH, 0.35F, 0.35F, beamRot, true));
        }
    }

    /** 关闭传送门：还原屏障、清特效、清召唤物、写冷却 */
    private static void closeGate(Gate gate, String message) {
        for (BlockPos pos : gate.barriers) {
            if (gate.level.isLoaded(pos)
                    && gate.level.getBlockState(pos).is(Blocks.BARRIER)) {
                gate.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        gate.barriers.clear();

        for (FxHandle visual : gate.visuals) {
            visual.discard();
        }
        gate.visuals.clear();

        List<Minion> copy = new ArrayList<>(gate.members);
        for (Minion minion : copy) {
            SummonRegistry.unregister(minion.entity);
            if (!minion.entity.isRemoved()) {
                summonBurst(gate.level, minion.entity.position().add(0.0D, 1.0D, 0.0D), 1.2D);
                minion.entity.discard();
            }
            MINIONS.remove(minion);
        }
        gate.members.clear();
        gate.boss = null;

        Vec3 at = new Vec3(gate.center.x, gate.baseY + GATE_HEIGHT / 2.0D, gate.center.z);
        gate.level.sendParticles(ParticleTypes.PORTAL, at.x, at.y, at.z, 40, 2.0D, 3.6D, 0.4D, 0.08D);
        play(gate.level, at, SoundEvents.PORTAL_TRAVEL, 0.9F, 0.8F);

        if (gate.owner.isAlive()) {
            PlayerMagicData data = MagicData.get(gate.owner);
            data.clearSkillLock(gate.ancient ? "summon_3_1" : "summon_2_1");
            String skillId = gate.ancient ? "summon_3_1" : "summon_2_1";
            data.setCooldown(skillId, gate.level.getGameTime() + gate.cooldownSeconds * 20L);
            // 进入冷却后脚下超阶圆环立刻消失（setCooldown 会把圆环延长到冷却结束，必须显式收掉）
            data.endSuperRing(skillId);
            MagicSync.sync(gate.owner);
            if (!message.isEmpty()) {
                NoticeApi.send(gate.owner, Component.literal(message).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    /** 按类型查找：召唤之门与上古魔门可以同时存在、同时召唤 */
    private static @Nullable Gate findGate(ServerPlayer owner, boolean ancient) {
        for (Gate gate : GATES) {
            if (gate.owner == owner && gate.ancient == ancient) {
                return gate;
            }
        }
        return null;
    }

    /** 门内走出一只：位置在门前沿，沿门宽随机散开 */
    private static void spawnGateMinion(Gate gate) {
        EntityType<? extends Mob> type = GATE_TYPES[gate.level.getRandom().nextInt(GATE_TYPES.length)];
        String title = GATE_TITLES[indexOf(GATE_TYPES, type)];
        double spread = (gate.level.getRandom().nextDouble() - 0.5D) * (GATE_WIDTH - 0.6D);
        double x = gate.center.x + gate.right.x * spread + gate.look.x * 0.9D;
        double z = gate.center.z + gate.right.z * spread + gate.look.z * 0.9D;
        Mob mob = spawnBodyAt(gate.level, type, title, snapGround(gate.level, x, z, gate.baseY));
        if (mob == null) {
            return;
        }
        applyGateBonus(mob, gate.owner, GATE_BUFF, 0.0D);
        SummonRegistry.register(mob, gate.owner);
        Minion minion = new Minion(gate.level, gate.owner, mob, Kind.GATE, "summon_2_1", 0, 0L, gate);
        gate.members.add(minion);
        MINIONS.add(minion);
        gate.level.sendParticles(ParticleTypes.PORTAL,
                mob.getX(), mob.getY() + 1.0D, mob.getZ(), 8, 0.3D, 0.5D, 0.3D, 0.04D);
    }

    // ==================================================================
    // 召唤物系统
    // ==================================================================

    private enum Kind { SPACE, CONTRACT, GATE, ANCIENT }

    /** 一只召唤物：召唤空间 / 契约生物 / 传送门小兵 / 上古魔门 BOSS 通用 */
    private static final class Minion {
        final ServerLevel level;
        final ServerPlayer owner;
        final LivingEntity entity;
        final Kind kind;
        final String skillId;
        final int cooldownSeconds;
        final long expireTick;
        final @Nullable Gate gate;
        boolean stored;

        Minion(ServerLevel level, ServerPlayer owner, LivingEntity entity, Kind kind, String skillId,
               int cooldownSeconds, long expireTick, @Nullable Gate gate) {
            this.level = level;
            this.owner = owner;
            this.entity = entity;
            this.kind = kind;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
            this.expireTick = expireTick;
            this.gate = gate;
        }
    }

    private static final List<Minion> MINIONS = new ArrayList<>();

    private static @Nullable Minion find(ServerPlayer owner, Kind kind) {
        for (Minion minion : MINIONS) {
            if (minion.owner == owner && minion.kind == kind
                    && minion.entity.isAlive() && !minion.entity.isRemoved()) {
                return minion;
            }
        }
        return null;
    }

    /** 生成一只召唤物本体（带名字、不自然消失，骷髅类补弓） */
    private static @Nullable Mob spawnBody(ServerLevel level, ServerPlayer owner,
                                           EntityType<? extends Mob> type, String title, double distance) {
        Vec3 spot = groundSpot(level, owner.position(), distance);
        return spawnBodyAt(level, type, title, spot);
    }

    private static @Nullable Mob spawnBodyAt(ServerLevel level, EntityType<? extends Mob> type,
                                             String title, Vec3 spot) {
        Mob mob = type.create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
        if (mob == null) {
            return null;
        }
        mob.setPos(spot.x, spot.y, spot.z);
        mob.setPersistenceRequired();
        mob.setCustomName(Component.literal(title).withStyle(ChatFormatting.LIGHT_PURPLE));
        if (type == EntityType.SKELETON || type == EntityType.STRAY || type == EntityType.WITHER_SKELETON) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        }
        level.addFreshEntity(mob);
        return mob;
    }

    /** 召唤空间：把玩家属性的一半直接写成召唤物的基础属性 */
    private static void inheritHalf(Mob mob, ServerPlayer player) {
        setBase(mob, Attributes.MAX_HEALTH, Math.max(4.0D, player.getMaxHealth() * 0.5D));
        setBase(mob, Attributes.ATTACK_DAMAGE,
                Math.max(1.0D, player.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.5D));
        setBase(mob, Attributes.ARMOR, player.getAttributeValue(Attributes.ARMOR) * 0.5D);
        mob.setHealth(mob.getMaxHealth());
    }

    /** 传送门加成：percent 为乘算加成，playerShare 为「再加玩家属性的一定比例」 */
    private static void applyGateBonus(Mob mob, ServerPlayer owner, double percent, double playerShare) {
        if (percent > 0.0D) {
            multiply(mob, Attributes.MAX_HEALTH, HP_MUL_ID, percent);
            multiply(mob, Attributes.ATTACK_DAMAGE, ATK_MUL_ID, percent);
            multiply(mob, Attributes.ARMOR, ARMOR_MUL_ID, percent);
            multiply(mob, Attributes.MOVEMENT_SPEED, SPEED_MUL_ID, percent);
        }
        if (playerShare > 0.0D) {
            addModifier(mob, Attributes.MAX_HEALTH, HP_ADD_ID, owner.getMaxHealth() * playerShare);
            addModifier(mob, Attributes.ATTACK_DAMAGE, ATK_ADD_ID,
                    owner.getAttributeValue(Attributes.ATTACK_DAMAGE) * playerShare);
            addModifier(mob, Attributes.ARMOR, ARMOR_ADD_ID,
                    owner.getAttributeValue(Attributes.ARMOR) * playerShare);
            addModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ADD_ID,
                    owner.getAttributeValue(Attributes.MOVEMENT_SPEED) * playerShare);
        }
        mob.setHealth(mob.getMaxHealth());
    }

    // ==================================================================
    // 每 tick 驱动（MagicEvents.onServerTick）
    // ==================================================================

    public static void tickWorld() {
        tickMinions();
        tickGates();
    }

    private static void tickMinions() {
        Iterator<Minion> iterator = MINIONS.iterator();
        while (iterator.hasNext()) {
            Minion minion = iterator.next();
            LivingEntity entity = minion.entity;
            if (entity.isRemoved() || !entity.isAlive()) {
                SummonRegistry.unregister(entity);
                onMinionDeath(minion);
                iterator.remove();
                if (minion.gate != null) {
                    minion.gate.members.remove(minion);
                }
                continue;
            }
            if (!minion.owner.isAlive() || minion.owner.isRemoved()
                    || minion.owner.level() != minion.level) {
                SummonRegistry.unregister(entity);
                discardMinion(minion);
                iterator.remove();
                if (minion.gate != null) {
                    minion.gate.members.remove(minion);
                }
                continue;
            }
            long now = minion.level.getGameTime();

            // 契约生物躺在契约空间里：只跟随玩家所在区块的 x/z，y 始终压在地下，不做 AI
            if (minion.kind == Kind.CONTRACT && minion.stored) {
                if (now % 40L == 0L) {
                    Vec3 anchor = contractAnchor(minion);
                    entity.teleportTo(anchor.x, anchor.y, anchor.z);
                    entity.setDeltaMovement(Vec3.ZERO);
                }
                continue;
            }

            // vanilla AI 选了不该打的目标（主人 / 同门 / 友方）时立刻纠正
            if (entity instanceof Mob mob && mob.getTarget() != null
                    && !validTarget(minion.owner, mob.getTarget())) {
                mob.setTarget(null);
            }

            if (now % 10L == Math.floorMod(entity.getId(), 10)) {
                retarget(minion);
                follow(minion);
            }
        }
    }

    private static void tickGates() {
        Iterator<Gate> iterator = GATES.iterator();
        while (iterator.hasNext()) {
            Gate gate = iterator.next();
            long now = gate.level.getGameTime();
            if (!gate.owner.isAlive() || gate.owner.level() != gate.level) {
                closeGate(gate, "");
                iterator.remove();
                continue;
            }
            if (now >= gate.endTick) {
                closeGate(gate, gate.ancient ? "上古魔门已关闭, 技能进入冷却" : "召唤之门已关闭, 技能进入冷却");
                iterator.remove();
                continue;
            }
            if (gate.ancient) {
                if (gate.boss == null || gate.boss.isRemoved() || !gate.boss.isAlive()) {
                    closeGate(gate, "上古召唤物陨落, 技能进入冷却");
                    iterator.remove();
                    continue;
                }
            } else if (now >= gate.nextSpawnTick) {
                gate.nextSpawnTick = now + 10L;
                if (gate.aliveCount() < GATE_ARMY) {
                    spawnGateMinion(gate);
                }
            }
            if (now % 5L == 0L) {
                portalParticles(gate);
            }
        }
    }

    private static void portalParticles(Gate gate) {
        for (int i = 0; i < 6; i++) {
            double width = (gate.level.getRandom().nextDouble() - 0.5D) * (GATE_WIDTH - 0.4D);
            double height = gate.level.getRandom().nextDouble() * (GATE_HEIGHT - 0.4D);
            double x = gate.center.x + gate.right.x * width;
            double z = gate.center.z + gate.right.z * width;
            gate.level.sendParticles(
                    gate.ancient ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.REVERSE_PORTAL,
                    x, gate.baseY + height, z, 1, 0.0D, 0.0D, 0.0D, 0.01D);
        }
    }

    /** 收回 / 主人失效：直接移除（不结算死亡反噬） */
    private static void discardMinion(Minion minion) {
        SummonRegistry.unregister(minion.entity);
        if (!minion.entity.isRemoved()) {
            summonBurst(minion.level, minion.entity.position().add(0.0D, 1.0D, 0.0D), 1.0D);
            minion.entity.discard();
        }
    }

    /** 召唤物死亡：召唤空间 5% / 契约 10% 生命上限反噬，并进入冷却 */
    private static void onMinionDeath(Minion minion) {
        if (!minion.owner.isAlive()) {
            return;
        }
        if (minion.kind == Kind.SPACE) {
            punish(minion, SPACE_DEATH_PENALTY, "召唤物陨落, 你受到 5% 生命上限的反噬");
        } else if (minion.kind == Kind.CONTRACT) {
            punish(minion, CONTRACT_DEATH_PENALTY, "契约生物死亡, 你受到 10% 生命上限的反噬");
        }
    }

    private static void punish(Minion minion, double ratio, String message) {
        ServerPlayer owner = minion.owner;
        float damage = (float) Math.max(1.0D, owner.getMaxHealth() * ratio);
        owner.hurtServer(minion.level, minion.level.damageSources().magic(), damage);
        PlayerMagicData data = MagicData.get(owner);
        data.setCooldown(minion.skillId, minion.level.getGameTime() + minion.cooldownSeconds * 20L);
        MagicSync.sync(owner);
        NoticeApi.send(owner, Component.literal(message + ", 技能进入冷却")
                .withStyle(ChatFormatting.YELLOW));
    }

    /**
     * 给召唤物指定目标：统一走 {@link SummonRegistry#preferredTarget} 的优先级
     * —— 第一优先主人攻击过的生物，第二优先主人身边的敌对生物；有优先目标就强制转火。
     */
    private static void retarget(Minion minion) {
        if (!(minion.entity instanceof Mob mob)) {
            return;
        }
        LivingEntity preferred = SummonRegistry.preferredTarget(minion.owner, minion.level,
                mob, FOLLOW_RANGE);
        if (preferred != null && mob.getTarget() != preferred) {
            mob.setTarget(preferred);
        }
    }

    /** 跟随主人：太远直接传送，较远寻路跟上 */
    private static void follow(Minion minion) {
        LivingEntity entity = minion.entity;
        ServerPlayer owner = minion.owner;
        double distSqr = entity.distanceToSqr(owner);
        if (distSqr > 24.0D * 24.0D) {
            Vec3 spot = groundSpot(minion.level, owner.position(), 1.6D);
            entity.teleportTo(spot.x, spot.y, spot.z);
            return;
        }
        if (distSqr > 10.0D * 10.0D && entity instanceof Mob mob) {
            mob.getNavigation().moveTo(owner.getX(), owner.getY(), owner.getZ(), 1.15D);
        }
    }

    /** 合法目标：不是主人 / 不是召唤物 / 不是隐身，并走统一敌我判定（PVP 关闭时不认玩家） */
    private static boolean validTarget(ServerPlayer owner, LivingEntity candidate) {
        return SummonRegistry.isValidEnemy(owner, candidate);
    }

    private static boolean isMinion(Entity entity) {
        return SummonRegistry.isSummoned(entity);
    }

    private static @Nullable ServerPlayer minionOwnerOf(Entity entity) {
        for (Minion minion : MINIONS) {
            if (minion.entity == entity) {
                return minion.owner;
            }
        }
        return null;
    }

    // ==================================================================
    // 事件钩子（MagicEvents 调用）
    // ==================================================================

    /**
     * 伤害事件：召唤物打主人 / PVP 关闭时打玩家 / 召唤物内讧，一律吞掉。
     *
     * <p>带敌意 AI 的生物（僵尸、劫掠兽…）会本能地把主人当目标，
     * 每 tick 纠正 target 仍有同一 tick 内命中的可能，所以这里做最后一道拦截。</p>
     */
    public static boolean onIncomingDamage(LivingIncomingDamageEvent event) {
        Entity source = event.getSource().getEntity();
        if (source == null) {
            return false;
        }
        ServerPlayer owner = minionOwnerOf(source);
        if (owner == null) {
            return false;
        }
        LivingEntity victim = event.getEntity();
        if (victim == source) {
            event.setCanceled(true);
            return true;
        }
        if (victim instanceof ServerPlayer victimPlayer
                && !PvpApi.canHurt(owner, victimPlayer)) {
            event.setCanceled(true);
            return true;
        }
        // 同门召唤物互殴：直接吞掉
        if (isMinion(victim) && minionOwnerOf(victim) == owner) {
            event.setCanceled(true);
            return true;
        }
        return false;
    }

    /** 服务器关闭 / 重开清场 */
    public static void clearAll() {
        for (Gate gate : new ArrayList<>(GATES)) {
            closeGate(gate, "");
        }
        GATES.clear();
        for (Minion minion : MINIONS) {
            SummonRegistry.unregister(minion.entity);
            if (!minion.entity.isRemoved()) {
                minion.entity.discard();
            }
        }
        MINIONS.clear();
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PlatformMods.MODID, path);
    }

    /** 水平朝向（看向正上方/正下方时退回玩家朝向） */
    private static Vec3 flatLook(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            return Vec3.directionFromRotation(0.0F, player.getYRot());
        }
        return flat.normalize();
    }

    private static int indexOf(EntityType<? extends Mob>[] pool, EntityType<?> type) {
        for (int i = 0; i < pool.length; i++) {
            if (pool[i] == type) {
                return i;
            }
        }
        return 0;
    }

    /** 找地面：取该 xz 的地表高度，低于门底时用门底兜底 */
    private static Vec3 snapGround(ServerLevel level, double x, double z, double fallbackY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        if (!level.getBlockState(BlockPos.containing(bx, y - 1, bz)).isSolid()) {
            y = (int) Math.floor(fallbackY);
        }
        return new Vec3(x, Math.max(y, fallbackY), z);
    }

    /** 主人脚边的落地点（召唤时从地面钻出来的位置感） */
    private static Vec3 groundSpot(ServerLevel level, Vec3 center, double distance) {
        double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
        int x = (int) Math.floor(center.x + Math.cos(angle) * distance);
        int z = (int) Math.floor(center.z + Math.sin(angle) * distance);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return new Vec3(x + 0.5D, y, z + 0.5D);
    }

    /** 召唤火花：紫黑光点 + 末影粒子 */
    private static void summonBurst(ServerLevel level, Vec3 center, double radius) {
        level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                center.x, center.y, center.z, (int) Math.min(60, 14 + radius * 6),
                radius * 0.4D, radius * 0.4D, radius * 0.4D, 0.06D);
        level.sendParticles(ParticleTypes.END_ROD,
                center.x, center.y, center.z, (int) Math.min(24, 6 + radius * 3),
                radius * 0.3D, radius * 0.35D, radius * 0.3D, 0.02D);
    }

    private static void play(ServerLevel level, Vec3 pos, net.minecraft.sounds.SoundEvent sound,
                             float volume, float pitch) {
        level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, volume, pitch);
    }

    private static void setBase(LivingEntity entity, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static void addModifier(LivingEntity entity, Holder<Attribute> attribute,
                                    Identifier modifierId, double amount) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(
                    new AttributeModifier(modifierId, amount, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    private static void multiply(LivingEntity entity, Holder<Attribute> attribute,
                                 Identifier modifierId, double percent) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(new AttributeModifier(modifierId, percent,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void beginTimed(SkillContext ctx, int ticks) {
        PlayerMagicData data = MagicData.get(ctx.player());
        long now = ctx.level().getGameTime();
        data.setCooldown(ctx.skill().id(), 0L);
        data.lockSkill(ctx.skill().id(), now + ticks + 4L);
    }

    /** 收回 / 切换形态：退还本次耗蓝与冷却 */
    private static void refundCast(SkillContext ctx) {
        PlayerMagicData data = MagicData.get(ctx.player());
        data.setMana(data.mana() + ctx.skill().manaCost());
        data.setCooldown(ctx.skill().id(), 0L);
        MagicSync.sync(ctx.player());
    }

    private static void failCast(SkillContext ctx, String message) {
        refundCast(ctx);
        NoticeApi.send(ctx.player(), Component.literal(message).withStyle(ChatFormatting.RED));
    }
}
