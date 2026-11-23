package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.fx.FxNet;
import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.lan.PvpApi;
import com.cqgzs.cqlove.network.MagicCirclePayload;

/**
 * 空间系 8 个技能（2026-11-19 设计实现）。
 *
 * <p>四阶沿用需求文档组名：念控 / 念攻 / 瞬移 / 空间死轴。定位为<b>位移 + 拆解 + 虚空爆发</b>。</p>
 *
 * <ul>
 *   <li>初阶·念控：吸取（2×2×2 前摇锁定后把生物拽到面前、物品与方块直接进背包）、
 *       移动（两次锁定：第一次记住 3×3×3 范围，第二次整块搬走）、
 *       破坏（4×4×4 全部打碎落地，圈内生物 9 点伤害）。</li>
 *   <li>中阶·念攻：切割（目标身上撕开空间裂痕，30 点并波及周围）、
 *       空间斩（沿视线飞出星空光刃，60 点，撞墙即落地）、
 *       空间风暴（锁定点收缩蓄力后 6 格内 90 点爆发）。</li>
 *   <li>高阶·瞬移：脚下 3×3×3 传送圆环顺时针转满一圈（1 秒）后，
 *       玩家与周围 3×3×3 内的生物一起沿视线传送到极限范围。</li>
 *   <li>超阶·空间死轴：在所指位置立起紫色外环 / 黑色内芯 / 白色菱心的死轴，
 *       0.5 → 3 逐渐膨胀，每秒 100 点持续伤害 60 秒；结束时 0.5 秒暴涨到 16 并 500 点爆炸。</li>
 * </ul>
 *
 * <p>前摇统一用「服务端每 tick 画粒子圈 + {@link MagicScheduler} 延时结算」，
 * 光刃 / 死轴等持续物件由 {@link #tickWorld()} 驱动，不依赖实体 tick。</p>
 */
public final class SpaceMagic {

    private SpaceMagic() {
    }

    // ---------------- 节奏（tick） ----------------
    private static final int TELEGRAPH_TICKS = 12;   // 吸取 / 破坏的前摇
    private static final int TELEPORT_TICKS = 20;    // 瞬移：圆环转满一圈（1 秒）
    private static final int STORM_TICKS = 50;       // 空间风暴蓄力（延长展示：约 2.5 秒蓄力 + 爆发残留）
    private static final int AXIS_LIFE = 60 * 20;    // 空间死轴持续 60 秒
    private static final int AXIS_GROW = 100;        // 0.5 → 3 的膨胀过程
    private static final int AXIS_BLAST_GROW = 10;   // 3 → 16 的暴涨过程（0.5 秒）

    // ---------------- 数值 ----------------
    private static final double ABSORB_HALF = 1.0D;     // 2×2×2
    private static final double MOVE_HALF = 1.5D;       // 3×3×3
    private static final double DESTROY_HALF = 2.0D;    // 4×4×4
    private static final double TELEPORT_HALF = 1.5D;   // 瞬移带走 3×3×3 内的生物

    private static final double CLEAVE_DAMAGE = 30.0D;
    private static final double CLEAVE_RADIUS = 3.0D;
    private static final double SLASH_DAMAGE = 60.0D;
    private static final double SLASH_STEP = 1.2D;
    private static final double SLASH_HIT_RADIUS = 2.0D;
    private static final double STORM_DAMAGE = 90.0D;
    private static final double STORM_RADIUS = 6.0D;
    private static final double DESTROY_DAMAGE = 9.0D;

    private static final double AXIS_START = 0.5D;
    private static final double AXIS_MID = 3.0D;
    private static final double AXIS_END = 16.0D;
    private static final double AXIS_DPS = 100.0D;
    private static final double AXIS_BLAST_DAMAGE = 500.0D;
    private static final double AXIS_BLAST_RADIUS = 8.0D;
    private static final double AXIS_PULL_RADIUS = 8.0D;  // 持续把 8 格内生物吸向中心

    // ==================================================================
    // 初阶·念控
    // ==================================================================

    /** 吸取：前摇锁定 2×2×2，生物拽到面前，物品与方块吸进背包（满了掉地上） */
    public static void absorb(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        lockBriefly(ctx, TELEGRAPH_TICKS);
        play(level, center, SoundEvents.ENDERMAN_TELEPORT, 0.7F, 1.6F);

        MagicScheduler.every(level, 1, TELEGRAPH_TICKS,
                () -> volumeRing(level, center, ABSORB_HALF, ParticleTypes.PORTAL),
                () -> {
                    if (!player.isAlive() || player.level() != level) {
                        return;
                    }
                    doAbsorb(player, level, center);
                });
        NoticeApi.send(player, Component.literal("吸取: 锁定 2×2×2 空间")
                .withStyle(ChatFormatting.GRAY));
    }

    private static void doAbsorb(ServerPlayer player, ServerLevel level, Vec3 center) {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        Vec3 front = player.position().add(flat.normalize().scale(1.9D)).add(0.0D, 0.3D, 0.0D);
        AABB box = new AABB(center, center).inflate(ABSORB_HALF);

        // 1. 生物：拽到玩家面前
        int pulled = 0;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (target == player || !target.isAlive() || target.isSpectator()) {
                continue;
            }
            if (target instanceof Player victim && !PvpApi.canHurt(player, victim)) {
                continue;
            }
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
            double radius = 0.6D + level.getRandom().nextDouble() * 0.9D;
            Vec3 spot = front.add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
            target.teleportTo(spot.x, spot.y, spot.z);
            target.setDeltaMovement(Vec3.ZERO);
            level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                    10, 0.3D, 0.4D, 0.3D, 0.05D);
            pulled++;
        }

        // 2. 掉落物：直接进背包
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            ItemStack stack = item.getItem().copy();
            item.discard();
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            pulled++;
        }

        // 3. 方块：破坏并塞进背包（背包满就掉在脚下）
        int blocks = 0;
        for (BlockPos pos : cube(center, ABSORB_HALF)) {
            if (level.getBlockState(pos).isAir()) {
                continue;
            }
            for (ItemStack stack : dropsOf(level, pos, player)) {
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
            level.destroyBlock(pos, false, player, 512);
            blocks++;
        }

        Vec3 at = center.add(0.0D, 0.4D, 0.0D);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 40, 0.7D, 0.7D, 0.7D, 0.12D);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 16, 0.5D, 0.5D, 0.5D, 0.04D);
        play(level, at, SoundEvents.ENDERMAN_TELEPORT, 1.0F, 0.7F);
        if (pulled == 0 && blocks == 0) {
            NoticeApi.send(player, Component.literal("吸取: 锁定范围内没有可吸取的目标")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * 移动：第一次释放记住 3×3×3 范围（不耗蓝不进冷却），第二次把里面的生物 / 物品 / 方块整体搬走。
     */
    public static void move(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        UUID key = player.getUUID();
        long now = level.getGameTime();
        Lock lock = LOCKS.get(key);

        if (lock == null || lock.expireTick < now || lock.level != level) {
            Vec3 center = ctx.targetPos();
            LOCKS.put(key, new Lock(level, center, now + 300L));
            volumeRing(level, center, MOVE_HALF, ParticleTypes.END_ROD);
            play(level, center, SoundEvents.ENDER_EYE_LAUNCH, 0.8F, 1.4F);
            // 第一次点击只是落点：退还耗蓝与冷却，方便立刻点第二次
            refundCast(ctx);
            NoticeApi.send(player, Component.literal("移动: 已锁定 3×3×3 空间, 再次释放选择落点")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        LOCKS.remove(key);
        // 落点抬回地表：和瞬移一样，准星瞄地面时视线是斜向下的，直接用 targetPos
        // 会把生物传送到地下；这里先做地表修正，再把整组相对偏移平移过去。
        Vec3 destination = safeSpot(level, ctx.targetPos());
        doMove(player, level, lock.pos, destination);
    }

    private static void doMove(ServerPlayer player, ServerLevel level, Vec3 source, Vec3 destination) {
        AABB box = new AABB(source, source).inflate(MOVE_HALF);
        int moved = 0;

        // 只平移生物与掉落物；需求明确「不对方块移动」，方块全部保持原样
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (target == player || !target.isAlive() || target.isSpectator()) {
                continue;
            }
            if (target instanceof Player victim && !PvpApi.canHurt(player, victim)) {
                continue;
            }
            Vec3 offset = target.position().subtract(source);
            Vec3 spot = destination.add(offset.x, offset.y, offset.z);
            // 逐只落点校正：相对偏移后的 y 可能钻进地面，抬到该 xz 的地表之上
            spot = safeSpot(level, spot);
            target.teleportTo(spot.x, spot.y, spot.z);
            target.setDeltaMovement(Vec3.ZERO);
            level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                    8, 0.3D, 0.4D, 0.3D, 0.04D);
            moved++;
        }
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            Vec3 offset = item.position().subtract(source);
            Vec3 spot = safeSpot(level, destination.add(offset.x, offset.y, offset.z));
            item.teleportTo(spot.x, spot.y, spot.z);
            item.setDeltaMovement(Vec3.ZERO);
            moved++;
        }

        for (Vec3 at : List.of(source, destination)) {
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 0.8D, at.z,
                    30, 1.2D, 1.2D, 1.2D, 0.1D);
        }
        play(level, source, SoundEvents.ENDERMAN_TELEPORT, 1.0F, 0.8F);
        play(level, destination, SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.3F);
        NoticeApi.send(player, Component.literal("移动完成: 传送了 " + moved + " 个生物/掉落物 (方块不移动)")
                .withStyle(ChatFormatting.GRAY));
    }

    /** 破坏：前摇锁定 4×4×4，全部方块打碎落地，圈内生物 9 点伤害 */
    public static void destroy(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        lockBriefly(ctx, TELEGRAPH_TICKS);
        play(level, center, SoundEvents.ENDER_EYE_LAUNCH, 0.8F, 0.9F);

        MagicScheduler.every(level, 1, TELEGRAPH_TICKS,
                () -> volumeRing(level, center, DESTROY_HALF, ParticleTypes.REVERSE_PORTAL),
                () -> {
                    if (!player.isAlive() || player.level() != level) {
                        return;
                    }
                    doDestroy(player, level, center);
                });
        NoticeApi.send(player, Component.literal("破坏: 锁定 4×4×4 空间")
                .withStyle(ChatFormatting.GRAY));
    }

    private static void doDestroy(ServerPlayer player, ServerLevel level, Vec3 center) {
        int broken = 0;
        for (BlockPos pos : cube(center, DESTROY_HALF)) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F) {
                continue;
            }
            // 走自己的掉落表：优先真实战利品，取不到就退回方块本体物品
            for (ItemStack stack : dropsOf(level, pos, player)) {
                Block.popResource(level, pos, stack);
            }
            level.destroyBlock(pos, false, player, 512);
            broken++;
        }

        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(DESTROY_HALF))) {
            if (target == player || !target.isAlive() || target.isSpectator()) {
                continue;
            }
            if (target instanceof Player victim && !PvpApi.canHurt(player, victim)) {
                continue;
            }
            MagicDamage.magicDamage(player, target, DESTROY_DAMAGE, MagicElement.SPACE);
        }

        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 3, 1.2D, 1.2D, 1.2D, 0.0D);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, center.x, center.y + 0.5D, center.z,
                60, 1.8D, 1.8D, 1.8D, 0.16D);
        play(level, center, SoundEvents.GENERIC_EXPLODE.value(), 1.0F, 1.6F);
        play(level, center, SoundEvents.STONE_BREAK, 1.2F, 0.6F);
        NoticeApi.send(player, Component.literal("破坏完成: 打碎 " + broken + " 个方块")
                .withStyle(ChatFormatting.GRAY));
    }

    // ==================================================================
    // 中阶·念攻
    // ==================================================================

    /** 切割：目标身上撕开空间裂痕，30 点伤害，并把周围生物一起切到 */
    public static void cleave(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        LivingEntity target = ctx.targetEntity();
        if (target == null) {
            failCast(ctx, "切割需要对准一个生物");
            return;
        }
        Vec3 center = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);

        // 三道交错的空间裂痕：贴图是 32×64 竖长条，缩放也按竖向给；
        // expireAfter 让面片 30 tick 后自动回收（旧实现没有生命周期，会一直挂在原地）
        for (int i = 0; i < 3; i++) {
            double yaw = level.getRandom().nextDouble() * Math.PI * 2.0D;
            var rift = DisplayFx.spawn(level, center, MagicItems.SPACE_RIFT.get(),
                    1.1F, 2.2F, 1.0F, Display.BillboardConstraints.FIXED,
                    DisplayFx.yawRotation(yaw));
            rift.expireAfter(30);
        }
        level.sendParticles(ParticleTypes.END_ROD, center.x, center.y, center.z, 24, 0.6D, 0.9D, 0.6D, 0.06D);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, center.x, center.y, center.z, 18, 0.5D, 0.7D, 0.5D, 0.05D);
        play(level, center, SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.7F);
        play(level, center, SoundEvents.GLASS_BREAK, 0.8F, 1.4F);

        ctx.damage(target, CLEAVE_DAMAGE);
        for (LivingEntity nearby : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(CLEAVE_RADIUS))) {
            if (nearby == target || nearby == player || !nearby.isAlive() || nearby.isSpectator()) {
                continue;
            }
            if (nearby instanceof Player victim && !PvpApi.canHurt(player, victim)) {
                continue;
            }
            MagicDamage.magicDamage(player, nearby, CLEAVE_DAMAGE, MagicElement.SPACE);
        }
    }

    /** 空间斩：沿视线飞出星空光刃，命中 60 点，撞方块落地 */
    public static void slash(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 origin = player.getEyePosition().add(player.getLookAngle().scale(0.8D));
        Vec3 dir = player.getLookAngle().normalize();
        int ticks = ctx.travelTicks(SLASH_STEP);
        beginTimed(ctx, ticks);

        var handle = DisplayFx.spawn(level, origin, MagicItems.SPACE_BLADE.get(),
                2.4F, 1.3F, 1.0F, Display.BillboardConstraints.FIXED,
                DisplayFx.yawRotation(Math.atan2(dir.x, dir.z)));
        SLASHES.add(new Slash(player, level, origin, dir, ticks, handle,
                new HashSet<>(), ctx.skill().id(),
                ctx.skill().cooldownSeconds(MagicData.get(player).rank())));
        play(level, origin, SoundEvents.PLAYER_ATTACK_SWEEP, 1.1F, 0.6F);
        play(level, origin, SoundEvents.ENDER_DRAGON_FLAP, 0.6F, 1.8F);
    }

    /**
     * 空间风暴（重做）：不再是纯粒子。
     *
     * <p>蓄力 1 秒期间：中央展开一张虚空漩涡面片（{@code space_storm} 图片），
     * 外围 6 块真实方块碎块（紫水晶 / 黑曜石 / 末地石…）绕中心高速旋转并逐渐收缩；
     * 爆发时碎块向外掀飞、漩涡面片放大后消散，同时结算 6 格内 90 点伤害。
     * 全部实体由客户端生成，服务端只发包。</p>
     */
    public static void storm(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        lockBriefly(ctx, STORM_TICKS);
        play(level, center, SoundEvents.END_PORTAL_SPAWN, 0.7F, 1.6F);

        // 1) 中央虚空漩涡图片（始终面向相机）
        var halo = DisplayFx.spawn(level, center.add(0.0D, 0.9D, 0.0D),
                MagicItems.SPACE_STORM.get(), 6.5F, 6.5F, 1.0F,
                Display.BillboardConstraints.CENTER);
        halo.expireAfter(STORM_TICKS + 26);

        // 2) 6 块绕中心旋转收缩的真实方块碎块
        BlockState[] shardStates = {
                Blocks.AMETHYST_BLOCK.defaultBlockState(),
                Blocks.OBSIDIAN.defaultBlockState(),
                Blocks.CRYING_OBSIDIAN.defaultBlockState(),
                Blocks.END_STONE.defaultBlockState(),
                Blocks.PURPUR_BLOCK.defaultBlockState(),
                Blocks.BLACKSTONE.defaultBlockState()
        };
        List<com.cqgzs.cqlove.fx.FxHandle> debris = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            var handle = FxNet.block(level, center.add(0.0D, 1.0D, 0.0D), shardStates[i],
                    0.85F, 0.85F, 0.85F,
                    DisplayFx.yawRotation(i * Math.PI / 3.0D), true);
            handle.expireAfter(STORM_TICKS + 26);
            debris.add(handle);
        }

        MagicScheduler.every(level, 1, STORM_TICKS,
                new Runnable() {
                    int tick = 0;

                    @Override
                    public void run() {
                        tick++;
                        double shrink = 1.0D - tick / (double) STORM_TICKS;
                        double radius = STORM_RADIUS * (0.30D + 0.70D * shrink);
                        for (int i = 0; i < debris.size(); i++) {
                            double angle = i * Math.PI * 2.0D / debris.size() + tick * 0.34D;
                            debris.get(i).setPos(center.add(
                                    Math.cos(angle) * radius,
                                    0.9D + Math.sin(tick * 0.24D + i) * 0.6D,
                                    Math.sin(angle) * radius));
                        }
                        // 粒子只做点缀：一圈旋转光点 + 中心收缩星尘
                        for (int i = 0; i < 18; i++) {
                            double angle = i * Math.PI * 2.0D / 18.0D + tick * 0.35D;
                            level.sendParticles(ParticleTypes.END_ROD,
                                    center.x + Math.cos(angle) * radius,
                                    center.y + 0.6D + Math.sin(tick * 0.2D) * 0.4D,
                                    center.z + Math.sin(angle) * radius,
                                    1, 0.0D, 0.0D, 0.0D, 0.0D);
                        }
                        level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                                center.x, center.y + 0.7D, center.z,
                                4, radius * 0.5D, 0.3D, radius * 0.5D, 0.02D);
                    }
                },
                () -> {
                    if (!player.isAlive() || player.level() != level) {
                        halo.discard();
                        for (var handle : debris) {
                            handle.discard();
                        }
                        return;
                    }
                    // 爆发：碎块向外掀飞、漩涡面片放大后短命消散
                    for (int i = 0; i < debris.size(); i++) {
                        double angle = i * Math.PI * 2.0D / debris.size();
                        debris.get(i).setPos(center.add(Math.cos(angle) * STORM_RADIUS,
                                2.0D + i * 0.3D, Math.sin(angle) * STORM_RADIUS));
                        debris.get(i).expireAfter(60);
                    }
                    DisplayFx.reshape(halo, level, new ItemStack(MagicItems.SPACE_STORM.get()),
                            (float) (STORM_RADIUS * 2.2D), (float) (STORM_RADIUS * 2.2D), 1.0F, null, 4);
                    halo.expireAfter(60);
                    doStorm(player, level, center);
                });
        NoticeApi.send(player, Component.literal("空间风暴: 虚空漩涡蓄力中").withStyle(ChatFormatting.GRAY));
    }

    private static void doStorm(ServerPlayer player, ServerLevel level, Vec3 center) {
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y + 0.5D, center.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, center.x, center.y + 0.8D, center.z,
                90, STORM_RADIUS * 0.45D, 1.2D, STORM_RADIUS * 0.45D, 0.2D);
        level.sendParticles(ParticleTypes.END_ROD, center.x, center.y + 0.8D, center.z,
                40, STORM_RADIUS * 0.35D, 0.8D, STORM_RADIUS * 0.35D, 0.12D);
        play(level, center, SoundEvents.GENERIC_EXPLODE.value(), 1.3F, 0.8F);
        play(level, center, SoundEvents.ENDER_DRAGON_GROWL, 0.7F, 1.4F);

        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(STORM_RADIUS))) {
            if (target == player || !target.isAlive() || target.isSpectator()) {
                continue;
            }
            if (target instanceof Player victim && !PvpApi.canHurt(player, victim)) {
                continue;
            }
            MagicDamage.magicDamage(player, target, STORM_DAMAGE, MagicElement.SPACE);
            Vec3 push = target.position().subtract(center);
            if (push.lengthSqr() > 1.0E-4D) {
                push = push.normalize().scale(0.8D);
                target.setDeltaMovement(target.getDeltaMovement().add(push.x, 0.35D, push.z));
            }
        }
    }

    // ==================================================================
    // 高阶·瞬移
    // ==================================================================

    /** 瞬移：脚下 3×3×3 圆环顺时针转满一圈后，连人带周围生物一起传送 */
    public static void teleport(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        int ticks = TELEPORT_TICKS;
        beginTimed(ctx, ticks);
        // 半径给大一些，配合渲染层「瞬移圆环不淡入淡出」的专属处理，脚下法阵整秒清晰可见
        MagicCircleSync.start(player, MagicCirclePayload.SPACE_TELEPORT, ticks, 2.4F);
        play(level, player.position(), SoundEvents.END_PORTAL_FRAME_FILL, 0.9F, 1.4F);

        double range = ctx.range();
        String skillId = ctx.skill().id();
        int cooldown = ctx.skill().cooldownSeconds(MagicData.get(player).rank());

        MagicScheduler.every(level, 1, ticks,
                new Runnable() {
                    int tick = 0;

                    @Override
                    public void run() {
                        tick++;
                        // 每 tick 按玩家「当前」位置重画整圈：玩家在前摇期间走动时，
                        // 场景里的光点不会留在原地形成「法阵没跟着走」的拖影
                        double head = tick * Math.PI * 2.0D / ticks;
                        double px = player.getX();
                        double py = player.getY();
                        double pz = player.getZ();
                        for (int i = 0; i < 24; i++) {
                            double angle = i * Math.PI * 2.0D / 24.0D + head;
                            boolean leading = i == 0;
                            level.sendParticles(leading ? ParticleTypes.END_ROD : ParticleTypes.ENCHANT,
                                    px + Math.cos(angle) * TELEPORT_HALF,
                                    py + 0.15D,
                                    pz + Math.sin(angle) * TELEPORT_HALF,
                                    leading ? 2 : 1, 0.0D, 0.02D, 0.0D, 0.0D);
                        }
                    }
                },
                () -> {
                    if (!player.isAlive() || player.level() != level) {
                        return;
                    }
                    MagicCircleSync.stop(player, MagicCirclePayload.SPACE_TELEPORT);
                    doTeleport(player, level, range, skillId, cooldown);
                    if (player.isAlive()) {
                        PlayerMagicData data = MagicData.get(player);
                        data.clearSkillLock(skillId);
                        data.setCooldown(skillId, level.getGameTime() + cooldown * 20L);
                        MagicSync.sync(player);
                    }
                });
        NoticeApi.send(player, Component.literal("瞬移: 传送圆环充能中").withStyle(ChatFormatting.GRAY));
    }

    private static void doTeleport(ServerPlayer player, ServerLevel level, double range,
                                   String skillId, int cooldown) {
        Vec3 look = player.getLookAngle().normalize();
        Vec3 raw = player.position().add(look.scale(range));
        Vec3 destination = safeSpot(level, raw);

        // 3×3×3 内的生物一起走
        List<LivingEntity> passengers = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(TELEPORT_HALF))) {
            if (entity == player || !entity.isAlive() || entity.isSpectator()) {
                continue;
            }
            if (entity instanceof Player victim && !PvpApi.canHurt(player, victim)) {
                continue;
            }
            passengers.add(entity);
        }

        Vec3 from = player.position();
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1.0D, from.z,
                40, 0.6D, 1.0D, 0.6D, 0.12D);
        play(level, from, SoundEvents.ENDERMAN_TELEPORT, 1.0F, 0.6F);

        player.teleportTo(destination.x, destination.y, destination.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;

        for (LivingEntity passenger : passengers) {
            Vec3 offset = passenger.position().subtract(from);
            Vec3 spot = safeSpot(level, destination.add(offset.x, offset.y, offset.z));
            passenger.teleportTo(spot.x, spot.y, spot.z);
            passenger.setDeltaMovement(Vec3.ZERO);
            passenger.fallDistance = 0.0F;
            level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                    spot.x, spot.y + 1.0D, spot.z, 16, 0.3D, 0.6D, 0.3D, 0.06D);
        }

        level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                destination.x, destination.y + 1.0D, destination.z, 40, 0.6D, 1.0D, 0.6D, 0.12D);
        level.sendParticles(ParticleTypes.END_ROD,
                destination.x, destination.y + 1.0D, destination.z, 18, 0.4D, 0.7D, 0.4D, 0.06D);
        play(level, destination, SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.3F);
        NoticeApi.send(player, Component.literal("瞬移完成 (带走 " + passengers.size() + " 个生物)")
                .withStyle(ChatFormatting.GRAY));
    }

    // ==================================================================
    // 超阶·空间死轴
    // ==================================================================

    /** 空间死轴：所指位置立起死轴，0.5 → 3 膨胀，每秒 100 点，60 秒后暴涨 16 并 500 点爆炸 */
    public static void deathAxis(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 center = GroundDecal.groundBelow(level, ctx.targetPos(), 6);
        long now = level.getGameTime();
        beginTimed(ctx, AXIS_LIFE + AXIS_BLAST_GROW + 4);

        // 立体死轴：真实方块拼出的「紫色外环 + 黑色内芯 + 白色菱心」，不是平贴图片
        Axis axis = new Axis(player, level, center, now,
                ctx.skill().id(), ctx.skill().cooldownSeconds(MagicData.get(player).rank()));
        buildAxisBlocks(axis, AXIS_START);
        AXES.add(axis);

        level.sendParticles(ParticleTypes.REVERSE_PORTAL, center.x, center.y + 0.2D, center.z,
                14, 0.4D, 0.2D, 0.4D, 0.05D);
        play(level, center, SoundEvents.END_PORTAL_SPAWN, 1.1F, 0.5F);
        NoticeApi.send(player, Component.literal("空间死轴已展开: 60 秒后爆炸")
                .withStyle(ChatFormatting.DARK_PURPLE));
    }

    /** 一根死轴 */
    private static final class Axis {
        final ServerPlayer owner;
        final ServerLevel level;
        final Vec3 center;
        final long startTick;
        /** 立体结构的方块句柄：外环 8 + 内芯 1 + 白菱心 1 */
        final List<com.cqgzs.cqlove.fx.FxBlock> blocks = new ArrayList<>();
        final String skillId;
        final int cooldownSeconds;
        long lastDamageTick;
        int lastRenderSize = -1;
        boolean blasting;

        Axis(ServerPlayer owner, ServerLevel level, Vec3 center, long startTick,
             String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.center = center;
            this.startTick = startTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
            this.lastDamageTick = startTick;
        }

        double size(long now) {
            long elapsed = now - startTick;
            if (elapsed <= AXIS_GROW) {
                return AXIS_START + (AXIS_MID - AXIS_START) * (elapsed / (double) AXIS_GROW);
            }
            return AXIS_MID;
        }
    }

    private static final List<Axis> AXES = new ArrayList<>();

    private static void tickAxes() {
        Iterator<Axis> iterator = AXES.iterator();
        while (iterator.hasNext()) {
            Axis axis = iterator.next();
            long now = axis.level.getGameTime();
            long elapsed = now - axis.startTick;

            // 60 秒到点：0.5 秒暴涨到 16，然后爆炸
            if (elapsed >= AXIS_LIFE) {
                long blastElapsed = elapsed - AXIS_LIFE;
                if (blastElapsed >= AXIS_BLAST_GROW) {
                    explodeAxis(axis);
                    iterator.remove();
                    continue;
                }
                double size = AXIS_MID + (AXIS_END - AXIS_MID) * (blastElapsed / (double) AXIS_BLAST_GROW);
                renderAxis(axis, size);
                axisParticles(axis, size);
                continue;
            }

            double size = axis.size(now);
            renderAxis(axis, size);
            axisParticles(axis, size);

            // 每秒 100 点持续伤害
            if (now - axis.lastDamageTick >= 20L) {
                axis.lastDamageTick = now;
                double radius = Math.max(0.4D, size * 0.5D);
                for (LivingEntity target : axis.level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(axis.center, axis.center).inflate(radius, 2.0D, radius))) {
                    if (target == axis.owner || !target.isAlive() || target.isSpectator()) {
                        continue;
                    }
                    if (target instanceof Player victim && !PvpApi.canHurt(axis.owner, victim)) {
                        continue;
                    }
                    MagicDamage.magicDamage(axis.owner, target, AXIS_DPS, MagicElement.SPACE);
                }
            }

            // 释放过程中持续把周围 8 格的生物吸向中心
            if (now % 2L == 0L) {
                pullTowardsAxis(axis);
            }
            // 周围 8 格随机白灰色线条，被吸进中间后消失
            if (now % 5L == 0L) {
                spawnAxisLines(axis);
            }
        }
    }

    /** 把 8 格内的敌对生物一格一格拉向死轴中心 */
    private static void pullTowardsAxis(Axis axis) {
        Vec3 core = axis.center.add(0.0D, 0.8D, 0.0D);
        for (LivingEntity target : axis.level.getEntitiesOfClass(LivingEntity.class,
                new AABB(axis.center, axis.center).inflate(AXIS_PULL_RADIUS, 4.0D, AXIS_PULL_RADIUS))) {
            if (target == axis.owner || !target.isAlive() || target.isSpectator()) {
                continue;
            }
            if (target instanceof Player victim && !PvpApi.canHurt(axis.owner, victim)) {
                continue;
            }
            if (!CombatTracker.isEnemyTarget(axis.owner, target)) {
                continue;
            }
            Vec3 to = core.subtract(target.position());
            double dist = to.length();
            if (dist < 1.0D) {
                continue;
            }
            Vec3 step = to.normalize().scale(Math.min(0.42D, dist - 0.9D));
            Vec3 spot = target.position().add(step.x, step.y * 0.35D, step.z);
            target.teleportTo(spot.x, spot.y, spot.z);
            target.setDeltaMovement(Vec3.ZERO);
        }
    }

    /** 8 格外随机生成白 / 灰细线（真实方块实体），朝中心收缩并在中心消失 */
    private static void spawnAxisLines(Axis axis) {
        ServerLevel level = axis.level;
        Vec3 core = axis.center.add(0.0D, 0.7D, 0.0D);
        for (int i = 0; i < 3; i++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
            double r = AXIS_PULL_RADIUS * (0.55D + level.getRandom().nextDouble() * 0.45D);
            Vec3 from = core.add(Math.cos(angle) * r,
                    (level.getRandom().nextDouble() - 0.5D) * 3.0D,
                    Math.sin(angle) * r);
            Vec3 dir = core.subtract(from).normalize();
            BlockState state = level.getRandom().nextBoolean()
                    ? Blocks.WHITE_CONCRETE.defaultBlockState()
                    : Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
            var handle = FxNet.block(level, from.add(dir.scale(1.0D)), state,
                    0.05F, 0.05F, 2.0F,
                    DisplayFx.yawRotation(Math.atan2(dir.x, dir.z)), true);
            handle.expireAfter(12);
            LINES.add(new AxisLine(handle, from, core));
        }
    }

    private static final class AxisLine {
        final com.cqgzs.cqlove.fx.FxHandle handle;
        final Vec3 from;
        final Vec3 to;
        int age;

        AxisLine(com.cqgzs.cqlove.fx.FxHandle handle, Vec3 from, Vec3 to) {
            this.handle = handle;
            this.from = from;
            this.to = to;
        }
    }

    private static final List<AxisLine> LINES = new ArrayList<>();

    /** 白灰线条每 tick 向中心插值，12 tick 后由 expireAfter 自动消失 */
    private static void tickLines() {
        Iterator<AxisLine> iterator = LINES.iterator();
        while (iterator.hasNext()) {
            AxisLine line = iterator.next();
            line.age++;
            if (line.age >= 12 || !line.handle.isAlive()) {
                iterator.remove();
                continue;
            }
            line.handle.setPos(line.from.add(line.to.subtract(line.from).scale(line.age / 12.0D)));
        }
    }

    /** 外环块数 / 每块基础尺寸：外圈紫色、内芯黑色、菱心白色，全部是真实方块（立体） */
    private static final int AXIS_RING_BLOCKS = 8;

    /** 首次生成立体死轴的方块句柄 */
    private static void buildAxisBlocks(Axis axis, double size) {
        // 8 块紫色外环（紫水晶块）
        for (int i = 0; i < AXIS_RING_BLOCKS; i++) {
            var handle = FxNet.block(axis.level, axis.center,
                    Blocks.AMETHYST_BLOCK.defaultBlockState(),
                    0.55F, 0.55F, 0.55F, null, true);
            axis.blocks.add(handle);
        }
        // 黑色内芯（黑曜石）
        axis.blocks.add(FxNet.block(axis.level, axis.center,
                Blocks.OBSIDIAN.defaultBlockState(), 0.7F, 0.7F, 0.7F, null, true));
        // 中央白色菱心（白色混凝土，略高于内芯保证可见）
        axis.blocks.add(FxNet.block(axis.level, axis.center,
                Blocks.WHITE_CONCRETE.defaultBlockState(), 0.4F, 0.4F, 0.4F, null, true));
        renderAxis(axis, size);
    }

    /** 按当前 size 摆放立体死轴的每一块：外环铺成圆环、内芯与白菱心在中央 */
    private static void renderAxis(Axis axis, double size) {
        int bucket = (int) Math.round(size * 10.0D);
        if (bucket == axis.lastRenderSize || axis.blocks.size() < AXIS_RING_BLOCKS + 2) {
            return;
        }
        axis.lastRenderSize = bucket;
        double radius = Math.max(0.25D, size * 0.5D);
        double y = axis.center.y + 0.55D;
        long tick = axis.level.getGameTime();
        // 外环：8 块紫色围成圆，缓慢自转；FxBlock 锚点在下角，回退半个尺寸 0.275
        for (int i = 0; i < AXIS_RING_BLOCKS; i++) {
            double angle = i * Math.PI * 2.0D / AXIS_RING_BLOCKS + tick * 0.03D;
            axis.blocks.get(i).setPos(new Vec3(
                    axis.center.x + Math.cos(angle) * radius - 0.275D, y - 0.275D,
                    axis.center.z + Math.sin(angle) * radius - 0.275D));
        }
        // 内芯黑曜石（半尺寸 0.35）+ 白菱心（半尺寸 0.2，略高形成叠层立体感）
        axis.blocks.get(AXIS_RING_BLOCKS).setPos(new Vec3(
                axis.center.x - 0.35D, y - 0.35D, axis.center.z - 0.35D));
        axis.blocks.get(AXIS_RING_BLOCKS + 1).setPos(new Vec3(
                axis.center.x - 0.2D, y + 0.45D - 0.2D, axis.center.z - 0.2D));
    }

    /** 白色丝线大幅减少：只留少量紫色ReversePortal点缀，不再刷屏白色END_ROD */
    private static void axisParticles(Axis axis, double size) {
        Vec3 center = axis.center;
        double radius = Math.max(0.4D, size * 0.5D);
        if (axis.level.getGameTime() % 6L == 0L) {
            axis.level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                    center.x, center.y + 0.3D, center.z,
                    3, radius * 0.5D, 0.3D, radius * 0.5D, 0.02D);
        }
    }

    private static void explodeAxis(Axis axis) {
        Vec3 center = axis.center;
        for (var block : axis.blocks) {
            block.discard();
        }
        axis.blocks.clear();
        axis.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER,
                center.x, center.y + 1.0D, center.z, 1, 0, 0, 0, 0);
        axis.level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                center.x, center.y + 1.0D, center.z, 120,
                AXIS_BLAST_RADIUS * 0.4D, 3.0D, AXIS_BLAST_RADIUS * 0.4D, 0.25D);
        axis.level.sendParticles(ParticleTypes.END_ROD,
                center.x, center.y + 1.0D, center.z, 60,
                AXIS_BLAST_RADIUS * 0.35D, 2.0D, AXIS_BLAST_RADIUS * 0.35D, 0.18D);
        play(axis.level, center, SoundEvents.GENERIC_EXPLODE.value(), 1.6F, 0.6F);
        play(axis.level, center, SoundEvents.END_PORTAL_SPAWN, 1.2F, 0.5F);

        for (LivingEntity target : axis.level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(AXIS_BLAST_RADIUS))) {
            if (target == axis.owner || !target.isAlive() || target.isSpectator()) {
                continue;
            }
            if (target instanceof Player victim && !PvpApi.canHurt(axis.owner, victim)) {
                continue;
            }
            MagicDamage.magicDamage(axis.owner, target, AXIS_BLAST_DAMAGE, MagicElement.SPACE);
        }

        if (axis.owner.isAlive()) {
            PlayerMagicData data = MagicData.get(axis.owner);
            data.clearSkillLock(axis.skillId);
            data.setCooldown(axis.skillId,
                    axis.level.getGameTime() + axis.cooldownSeconds * 20L);
            data.endSuperRing(axis.skillId);
            MagicSync.sync(axis.owner);
            NoticeApi.send(axis.owner, Component.literal("空间死轴爆炸完毕, 技能进入冷却")
                    .withStyle(ChatFormatting.DARK_PURPLE));
        }
    }

    // ==================================================================
    // 每 tick 驱动（MagicEvents.onServerTick）
    // ==================================================================

    public static void tickWorld() {
        tickSlashes();
        tickAxes();
        tickLines();
        for (Iterator<Map.Entry<UUID, Lock>> iterator = LOCKS.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<UUID, Lock> entry = iterator.next();
            if (entry.getValue().expireTick < entry.getValue().level.getGameTime()) {
                iterator.remove();
            }
        }
    }

    private static void tickSlashes() {
        Iterator<Slash> iterator = SLASHES.iterator();
        while (iterator.hasNext()) {
            Slash slash = iterator.next();
            // 只有飞满技能范围极限才结束：撞到方块不再让光刃消失
            if (slash.ticksLeft-- <= 0) {
                finishSlash(slash);
                iterator.remove();
                continue;
            }
            Vec3 previous = slash.pos;
            Vec3 next = previous.add(slash.dir.scale(SLASH_STEP));
            slash.pos = next;

            // 1. 飞行过程中一直向两边扩大
            slash.width = Math.min(9.0D, slash.width + 0.22D);
            slash.height = Math.min(4.8D, slash.height + 0.12D);
            DisplayFx.reshape(slash.handle, slash.level,
                    new ItemStack(MagicItems.SPACE_BLADE.get()),
                    (float) slash.width, (float) slash.height, 1.0F,
                    DisplayFx.yawRotation(Math.atan2(slash.dir.x, slash.dir.z)), 0);
            slash.handle.setPos(next);
            slash.level.sendParticles(ParticleTypes.END_ROD, next.x, next.y, next.z,
                    3, 0.2D, 0.2D, 0.2D, 0.02D);

            // 2. 碰到的方块直接掉落，但光刃继续飞（不再 finishSlash）
            breakBlocksAlong(slash, next);

            // 3. 命中生物：判定半径随光刃变宽一起扩大，每只只吃一次 60 点
            double hitRadius = Math.min(4.5D, SLASH_HIT_RADIUS + (slash.width - 2.4D) * 0.35D);
            for (LivingEntity target : slash.level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(next, next).inflate(hitRadius))) {
                if (!target.isAlive() || target.isSpectator() || target == slash.owner) {
                    continue;
                }
                if (!slash.hit.add(target.getUUID())) {
                    continue;
                }
                if (target instanceof Player victim && !PvpApi.canHurt(slash.owner, victim)) {
                    continue;
                }
                MagicDamage.magicDamage(slash.owner, target, SLASH_DAMAGE, MagicElement.SPACE);
                Vec3 push = target.position().subtract(previous).normalize().scale(0.6D);
                target.setDeltaMovement(target.getDeltaMovement().add(push.x, 0.25D, push.z));
                slash.level.sendParticles(ParticleTypes.CRIT,
                        target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                        18, 0.4D, 0.5D, 0.4D, 0.1D);
            }
        }
    }

    /** 光刃扫过的方块掉落（跳过空气 / 流体 / 基岩），用 broken 集合去重避免重复掉落 */
    private static void breakBlocksAlong(Slash slash, Vec3 at) {
        ServerLevel level = slash.level;
        Vec3 side = new Vec3(-slash.dir.z, 0.0D, slash.dir.x);
        if (side.lengthSqr() < 1.0E-4D) {
            side = new Vec3(1.0D, 0.0D, 0.0D);
        }
        side = side.normalize();
        int half = (int) Math.min(3.0D, Math.max(0.0D, (slash.width - 2.4D) / 2.2D));
        int vHalf = (int) Math.min(2.0D, Math.max(0.0D, (slash.height - 1.3D) / 1.8D));
        for (int k = -half; k <= half; k++) {
            for (int dy = -vHalf; dy <= vHalf; dy++) {
                Vec3 point = at.add(side.scale(k)).add(0.0D, dy, 0.0D);
                BlockPos pos = BlockPos.containing(point);
                if (!slash.broken.add(pos) || !level.isLoaded(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || !state.getFluidState().isEmpty()
                        || state.getDestroySpeed(level, pos) < 0.0F) {
                    continue;
                }
                for (ItemStack stack : dropsOf(level, pos, slash.owner)) {
                    Block.popResource(level, pos, stack);
                }
                level.destroyBlock(pos, false, slash.owner, 512);
                level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                        pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                        4, 0.3D, 0.3D, 0.3D, 0.02D);
            }
        }
    }

    private static void finishSlash(Slash slash) {
        slash.handle.discard();
        slash.level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                slash.pos.x, slash.pos.y, slash.pos.z, 30, 0.5D, 0.5D, 0.5D, 0.1D);
        if (slash.owner.isAlive()) {
            PlayerMagicData data = MagicData.get(slash.owner);
            data.clearSkillLock(slash.skillId);
            data.setCooldown(slash.skillId,
                    slash.level.getGameTime() + slash.cooldownSeconds * 20L);
            MagicSync.sync(slash.owner);
        }
    }

    /** 服务器关闭 / 重开清场 */
    public static void clearAll() {
        for (Slash slash : SLASHES) {
            slash.handle.discard();
            if (slash.owner.isAlive()) {
                PlayerMagicData data = MagicData.get(slash.owner);
                data.clearSkillLock(slash.skillId);
            }
        }
        SLASHES.clear();
        for (Axis axis : AXES) {
            for (var block : axis.blocks) {
                block.discard();
            }
            axis.blocks.clear();
            if (axis.owner.isAlive()) {
                PlayerMagicData data = MagicData.get(axis.owner);
                data.clearSkillLock(axis.skillId);
                data.endSuperRing(axis.skillId);
            }
        }
        AXES.clear();
        for (AxisLine line : LINES) {
            line.handle.discard();
        }
        LINES.clear();
        LOCKS.clear();
    }

    // ==================================================================
    // 内部结构
    // ==================================================================

    /** 一次锁定的落点（移动技能的第一次点击） */
    private record Lock(ServerLevel level, Vec3 pos, long expireTick) {
    }

    private static final Map<UUID, Lock> LOCKS = new HashMap<>();

    /** 一道飞行中的空间光刃 */
    private static final class Slash {
        final ServerPlayer owner;
        final ServerLevel level;
        Vec3 pos;
        final Vec3 dir;
        int ticksLeft;
        final com.cqgzs.cqlove.fx.FxBillboard handle;
        final Set<UUID> hit;
        final String skillId;
        final int cooldownSeconds;
        /** 已经打掉过的方块，防止每 tick 重复掉落 */
        final Set<BlockPos> broken = new HashSet<>();
        /** 光刃当前宽 / 高，飞行中持续增大 */
        double width = 2.4D;
        double height = 1.3D;

        Slash(ServerPlayer owner, ServerLevel level, Vec3 pos, Vec3 dir, int ticksLeft,
              com.cqgzs.cqlove.fx.FxBillboard handle, Set<UUID> hit,
              String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.pos = pos;
            this.dir = dir;
            this.ticksLeft = ticksLeft;
            this.handle = handle;
            this.hit = hit;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }
    }

    private static final List<Slash> SLASHES = new ArrayList<>();

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 以 center 为中心、边长为 half*2 的立方体里所有方块坐标 */
    private static List<BlockPos> cube(Vec3 center, double half) {
        BlockPos min = BlockPos.containing(center.x - half + 0.001D,
                center.y - half + 0.001D, center.z - half + 0.001D);
        BlockPos max = BlockPos.containing(center.x + half - 0.001D,
                center.y + half - 0.001D, center.z + half - 0.001D);
        List<BlockPos> list = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            list.add(pos.immutable());
        }
        return list;
    }

    /** 方块掉落：优先真实战利品，取不到就退回方块本体物品 */
    private static List<ItemStack> dropsOf(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player) {
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        List<ItemStack> drops = new ArrayList<>();
        if (player != null) {
            drops.addAll(Block.getDrops(state, level, pos, blockEntity, player, ItemStack.EMPTY));
        } else {
            drops.addAll(Block.getDrops(state, level, pos, blockEntity));
        }
        drops.removeIf(ItemStack::isEmpty);
        if (drops.isEmpty()) {
            ItemStack self = new ItemStack(state.getBlock());
            if (!self.isEmpty()) {
                drops.add(self);
            }
        }
        return drops;
    }

    /** 立方体范围提示圈：上下两圈 + 四根立柱 */
    private static void volumeRing(ServerLevel level, Vec3 center, double half, ParticleOptions particle) {
        int count = 20;
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2.0D / count;
            double x = center.x + Math.cos(angle) * half;
            double z = center.z + Math.sin(angle) * half;
            level.sendParticles(particle, x, center.y - half + 0.05D, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            level.sendParticles(particle, x, center.y + half - 0.05D, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2.0D;
            double x = center.x + Math.cos(angle) * half;
            double z = center.z + Math.sin(angle) * half;
            for (int k = -2; k <= 2; k++) {
                level.sendParticles(particle, x, center.y + k * half / 2.0D, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    /**
     * 落地点修正：先把 y 抬回地表，再往下找两层空气 + 一层实心的位置。
     *
     * <p>玩家对着前方地面时视线是斜向下的，{@code player.position() + look*range}
     * 会落在地面以下；旧实现找不到安全格就把这个地下坐标原样返回，于是人被传进地里。</p>
     */
    private static Vec3 safeSpot(ServerLevel level, Vec3 target) {
        BlockPos base = BlockPos.containing(target);
        double surfaceY = target.y;
        BlockPos column = new BlockPos(base.getX(), 0, base.getZ());
        if (level.isLoaded(column)) {
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
            surfaceY = Math.max(target.y, top.getY() + 1.0D);
        }
        base = BlockPos.containing(target.x, surfaceY, target.z);
        for (int dy = 2; dy >= -5; dy--) {
            BlockPos feet = base.above(dy);
            if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) {
                return new Vec3(target.x, feet.getY(), target.z);
            }
        }
        // 找不到落脚点也绝不回地下
        return new Vec3(target.x, surfaceY, target.z);
    }

    private static void play(ServerLevel level, Vec3 pos, net.minecraft.sounds.SoundEvent sound,
                             float volume, float pitch) {
        MagicDamage.playSound(level, pos, sound, volume, pitch);
    }

    /** 前摇期间锁住该技能，避免连点重复触发（不改冷却） */
    private static void lockBriefly(SkillContext ctx, int ticks) {
        PlayerMagicData data = MagicData.get(ctx.player());
        data.lockSkill(ctx.skill().id(), ctx.level().getGameTime() + ticks + 4L);
    }

    private static void beginTimed(SkillContext ctx, int ticks) {
        PlayerMagicData data = MagicData.get(ctx.player());
        long now = ctx.level().getGameTime();
        data.setCooldown(ctx.skill().id(), 0L);
        data.lockSkill(ctx.skill().id(), now + ticks + 4L);
    }

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
