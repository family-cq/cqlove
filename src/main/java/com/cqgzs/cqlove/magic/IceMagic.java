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

import org.joml.Quaternionf;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 冰系全部 8 个技能（2026-11-14 设计：冰漫 / 冰锁 / 冰封灵柩 / 云冰晶界）。
 *
 * <ul>
 *   <li><b>冰漫</b>（初阶）：
 *     <ul>
 *       <li>冻结：对准生物冰封定身 3 秒 + 缓慢 III + 3 点冰系伤害；</li>
 *       <li>凝结：射出一根冰锥，命中 6 点冰系伤害并减速 3 秒；</li>
 *       <li>覆盖：前方地面铺 5×3 冰面（浮冰方块）10 秒，踩上去打滑；
 *           敌对生物踏上时缓慢 5 秒并受到 9 点冰系伤害，到时还原。</li>
 *     </ul>
 *   </li>
 *   <li><b>冰锁</b>（中阶）：
 *     <ul>
 *       <li>碾骨：冰锁缠身定身 5 秒，每秒 8 点，结束时碎裂爆发 15 点；</li>
 *       <li>庞莽：立体冰之巨蟒（正面蛇头 + 3 节交叉躯干）沿视线冲出 8 格，
 *           沿途 35 点 + 击退 + 缓慢；</li>
 *       <li>围城：准星指向处强制升起 5×3 冰墙（覆盖地形、到期还原），挡 15 秒；
 *           墙周围 2 格的生物每秒受 10 点冰系伤害。</li>
 *     </ul>
 *   </li>
 *   <li><b>冰封灵柩</b>（高阶）：前摇左右两面冰墙向锁定生物中间靠拢，合拢成型后
 *       定身 30 秒、每秒 15 点、棺内受到的所有伤害 ×1.3，30 秒后冰棺爆碎再造成 100 点；
 *       生物中途消失，实体冰块仍保留到技能结束。</li>
 *   <li><b>云冰晶界</b>（超阶）：以施法点为中心、半径 = 技能施法范围（超阶 30 格，至少 9 格）
 *       的冰晶领域，持续 60 秒：整片领域持续落雪，域内敌对生物每秒 12 点并缓慢 60 秒；
 *       连续停留 3 秒冻结 2 秒。领域存在期间施法者获得 +100 攻击 / 魔力 / 蓝量上限 /
 *       魔暴击伤害，免疫爆炸 / 缓慢 / 冰冻 / 失明，所有技能前摇与冷却时间减半。</li>
 * </ul>
 *
 * <p>冰面 / 冰棺只替换“可替换方块”；围城冰墙按需求强制覆盖地形（基岩、屏障、
 * 带容器的方块除外）。所有改动都记录原 BlockState，到期只在仍是浮冰时还原，
 * 不会永久挖走玩家的建筑；浮冰方块全部用原版 {@link Blocks#PACKED_ICE}。</p>
 */
public final class IceMagic {

    private static final int ICE_COLOR = MagicElement.ICE.color();

    // ---------------- 初阶 ----------------
    /** 冻结定身时长 */
    private static final int FREEZE_TICKS = 3 * 20;
    private static final double FREEZE_DAMAGE = 3.0D;
    /** 冰锥飞行速度（格/tick）与伤害 */
    private static final double SPIKE_SPEED = 0.9D;
    private static final double SPIKE_DAMAGE = 6.0D;
    private static final int SPIKE_SLOW_TICKS = 3 * 20;
    /** 冰面持续时长 / 踩上冰面的敌对生物：缓慢 5 秒 + 一次性 9 点冰系伤害 */
    private static final int COVER_TICKS = 10 * 20;
    private static final int COVER_SLOW_TICKS = 5 * 20;
    private static final double COVER_DAMAGE = 9.0D;

    // ---------------- 中阶 ----------------
    private static final int CRUSH_TICKS = 5 * 20;
    private static final double CRUSH_DPS = 8.0D;
    private static final double CRUSH_BURST = 15.0D;
    private static final double SERPENT_DAMAGE = 35.0D;
    private static final double SERPENT_LENGTH = 8.0D;
    private static final double SERPENT_SPEED = 0.55D;
    /** 庞莽立体结构：正面蛇头尺寸 / 躯干节数 / 节间距 / 躯干面片尺寸 */
    private static final float SERPENT_HEAD_SIZE = 1.9F;
    private static final int SERPENT_BODY_SEGMENTS = 3;
    private static final double SERPENT_SEGMENT_GAP = 1.0D;
    /** 躯干贴图是 2:1，模型元素是 1x0.5 格，等比缩放才不会把冰鳞拉长 */
    private static final float SERPENT_BODY_W = 2.0F;
    private static final float SERPENT_BODY_H = 2.0F;
    private static final double SERPENT_BODY_REACH = 3.0D;
    /** 冰墙挡 15 秒；周围 2 格的生物每秒受 10 点冰系伤害 */
    private static final int WALL_TICKS = 15 * 20;
    private static final int WALL_WINDUP = 10;
    private static final int WALL_W = 5;
    private static final int WALL_H = 3;
    private static final double WALL_AURA_RADIUS = 2.0D;
    private static final double WALL_DPS = 10.0D;
    /** 冰墙落点跟随准星，距离钳制在这段范围内（格），避免贴脸或飞到天边 */
    private static final double WALL_MIN_DIST = 2.0D;
    /** 冰墙落点上限不再写死 6 格：直接跟随施法范围（初阶 10 / 中阶 15 / 高阶 20 / 超阶 30） */

    // ---------------- 高阶 ----------------
    private static final int COFFIN_SEAL_TICKS = 30;
    /** 定身 30 秒 / 每秒 15 点 / 结束时封棺爆炸 100 点 */
    private static final int COFFIN_TICKS = 30 * 20;
    private static final double COFFIN_DPS = 15.0D;
    private static final double COFFIN_BURST = 100.0D;
    private static final double COFFIN_WEAKEN = 1.3D;
    /** 前摇：左右两面冰墙从 ±SPREAD 靠拢到 ±CLOSE（格），合拢瞬间才算冰封完成 */
    private static final double COFFIN_WALL_SPREAD = 2.2D;
    private static final double COFFIN_WALL_CLOSE = 0.55D;
    /** 冰墙板贴图 64x128 = 1:2，模型 1x1 格，等比放大成 1.4 宽 × 2.8 高 */
    private static final float COFFIN_WALL_W = 1.4F;
    private static final float COFFIN_WALL_H = 2.8F;

    // ---------------- 超阶 ----------------
    /** 领域半径下限；实际半径 = 施法者当前阶级的技能施法范围（超阶 30 格），不小于 9 格 */
    private static final double DOMAIN_MIN_RADIUS = 9.0D;
    /** 冰晶柱：模型是标准 16x16 元素，靠 Y 缩放拉成 4 格高（贴图 32x128 与之等比） */
    private static final float CRYSTAL_HEIGHT_SCALE = 4.0F;
    /** 领域持续 60 秒；域内敌对生物缓慢 60 秒 */
    private static final int DOMAIN_TICKS = 60 * 20;
    private static final int DOMAIN_SLOW_TICKS = 60 * 20;
    private static final double DOMAIN_DPS = 12.0D;
    /** 连续在领域内多少 tick 触发一次冻结 */
    private static final int DOMAIN_CHILL_TICKS = 3 * 20;
    private static final int DOMAIN_FREEZE_TICKS = 2 * 20;
    /** 云冰晶界给施法者的增益（与火神/雷神一致：+100 攻击/魔力/蓝量上限/魔暴伤） */
    private static final Identifier DOMAIN_ATTACK_ID = id("ice_domain_attack");

    /** 定身 / 减速用属性修饰器 id（按 id 精确移除，不影响其他来源） */
    private static final Identifier FREEZE_SPEED_ID = id("ice_freeze_speed");
    private static final Identifier FREEZE_JUMP_ID = id("ice_freeze_jump");
    private static final Identifier CRUSH_SPEED_ID = id("ice_crush_speed");
    private static final Identifier CRUSH_JUMP_ID = id("ice_crush_jump");
    private static final Identifier COFFIN_SPEED_ID = id("ice_coffin_speed");
    private static final Identifier COFFIN_JUMP_ID = id("ice_coffin_jump");

    /** 飞行中的冰锥 */
    private static final List<Spike> SPIKES = new ArrayList<>();
    /** 定身中的生物（冻结 / 碾骨 / 领域冻结） */
    private static final List<Frozen> FROZENS = new ArrayList<>();
    /** 铺在地上的冰面 */
    private static final List<Cover> COVERS = new ArrayList<>();
    /** 冲出的冰蟒 */
    private static final List<Serpent> SERPENTS = new ArrayList<>();
    /** 升起的冰墙 */
    private static final List<IceWall> WALLS = new ArrayList<>();
    /** 碾骨中的生物 */
    private static final List<Crush> CRUSHES = new ArrayList<>();
    /** 冰封灵柩 */
    private static final List<Coffin> COFFINS = new ArrayList<>();
    /** 云冰晶界领域 */
    private static final List<Domain> DOMAINS = new ArrayList<>();

    private IceMagic() {
    }

    // ==================================================================
    // 初阶·冰漫
    // ==================================================================

    /** 冻结：对准生物冰封定身 3 秒 + 缓慢 III + 3 点冰系伤害 */
    public static void freeze(SkillContext ctx) {
        LivingEntity target = ctx.targetEntity();
        if (target == null) {
            failCast(ctx, "冻结需要对准生物释放");
            return;
        }
        ServerLevel level = ctx.level();
        long now = level.getGameTime();

        ctx.damage(target, FREEZE_DAMAGE);
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, FREEZE_TICKS, 2));
        Frozen previous = findFrozen(target.getUUID());
        if (previous != null) {
            previous.release();
            FROZENS.remove(previous);
        }
        applyRoot(target, FREEZE_SPEED_ID, FREEZE_JUMP_ID);
        FROZENS.add(new Frozen(target, now + FREEZE_TICKS, FREEZE_SPEED_ID, FREEZE_JUMP_ID));

        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        MagicDamage.playSound(level, hit, SoundEvents.GLASS_BREAK, 0.9F, 1.7F);
        iceBurst(level, hit, 1.5D);
        // 冰封住的方块状粒子
        level.sendParticles(ParticleTypes.ITEM_SNOWBALL, hit.x, hit.y, hit.z, 20, 0.45D, 0.6D, 0.45D, 0.03D);
    }

    /** 凝结：射出一根冰锥，命中 6 点冰系伤害并减速 3 秒 */
    public static void condense(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        Vec3 from = ctx.origin();
        Vec3 dir = player.getLookAngle().normalize();

        FxBillboard display = DisplayFx.spawn(level, from, MagicItems.ICE_SPIKE.get(),
                1.6F, 1.6F, 1.6F, Display.BillboardConstraints.CENTER);
        if (display == null) {
            failCast(ctx, "凝结生成失败");
            return;
        }
        // 冰锥飞满施法范围即碎裂消失，不额外多飞 6 格（超范围不生效）
        SPIKES.add(new Spike(player, level, display, from, dir, ctx.range(),
                level.getGameTime() + 100L));
        MagicDamage.playSound(level, from, SoundEvents.GLASS_PLACE, 0.8F, 1.5F);
    }

    /** 覆盖：前方地面铺 5×3 冰面，10 秒内踩上去打滑；敌对生物缓慢 5 秒 + 9 点冰系伤害，到时还原 */
    public static void cover(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        flat = flat.normalize();
        Vec3 side = new Vec3(-flat.z, 0.0D, flat.x);

        Map<BlockPos, BlockState> originals = new HashMap<>();
        Vec3 base = player.position();
        for (int forward = 2; forward <= 6; forward++) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                Vec3 probe = base.add(flat.scale(forward)).add(side.scale(lateral));
                Vec3 ground = GroundDecal.groundBelow(level, probe.add(0.0D, 1.6D, 0.0D), 5);
                BlockPos pos = BlockPos.containing(ground.x, ground.y - 0.05D, ground.z);
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || !state.isSolidRender()) {
                    continue;
                }
                if (level.getBlockState(pos.above()).isSolidRender()) {
                    continue;
                }
                originals.putIfAbsent(pos.immutable(), state);
                level.setBlock(pos, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        if (originals.isEmpty()) {
            failCast(ctx, "覆盖失败: 脚下没有可结冰的地面");
            return;
        }
        long now = level.getGameTime();
        COVERS.add(new Cover(player, level, originals, now + COVER_TICKS));
        MagicDamage.playSound(level, base, SoundEvents.GLASS_PLACE, 1.0F, 0.9F);
        level.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                base.x + flat.x * 4.0D, base.y + 0.2D, base.z + flat.z * 4.0D,
                24, 1.6D, 0.1D, 1.6D, 0.02D);
    }

    // ==================================================================
    // 中阶·冰锁
    // ==================================================================

    /** 碾骨：冰锁缠身定身 5 秒，每秒 8 点，结束时碎裂爆发 15 点 */
    public static void crushBone(SkillContext ctx) {
        LivingEntity target = ctx.targetEntity();
        if (target == null) {
            failCast(ctx, "碾骨需要对准生物释放");
            return;
        }
        ServerLevel level = ctx.level();
        long now = level.getGameTime();

        Frozen previous = findFrozen(target.getUUID());
        if (previous != null) {
            previous.release();
            FROZENS.remove(previous);
        }
        applyRoot(target, CRUSH_SPEED_ID, CRUSH_JUMP_ID);
        FROZENS.add(new Frozen(target, now + CRUSH_TICKS, CRUSH_SPEED_ID, CRUSH_JUMP_ID));

        PlayerMagicData data = MagicData.get(ctx.player());
        int cooldownSeconds = ctx.skill().cooldownSeconds(data.rank());
        data.setCooldown(ctx.skill().id(), now + CRUSH_TICKS);
        data.lockSkill(ctx.skill().id(), now + CRUSH_TICKS + 2L);

        CRUSHES.add(new Crush(ctx.player(), level, target, now + CRUSH_TICKS,
                now + 20L, ctx.skill().id(), cooldownSeconds));
        MagicDamage.playSound(level, target.position(), SoundEvents.CHAIN_PLACE, 1.0F, 1.2F);
        level.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                target.getX(), target.getY() + 1.0D, target.getZ(), 18, 0.4D, 0.7D, 0.4D, 0.02D);
    }

    /**
     * 庞莽：冰之巨蟒沿视线冲出 8 格，沿途 35 点 + 击退 + 缓慢。
     *
     * <p>立体结构：最前端是 3 张交叉面片组成的「正面蛇头」，后面拖 3 节、
     * 每节 2 张正交交叉面片的「冰鳞躯干」。面片全部用 {@code FIXED} 朝向 +
     * 沿视线方向的固定旋转，不再跟着相机转，所以冲出去时不会像一张横图平移。</p>
     */
    public static void serpent(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        flat = flat.normalize();
        Vec3 side = new Vec3(-flat.z, 0.0D, flat.x);
        Vec3 start = GroundDecal.groundBelow(level, player.position().add(flat.scale(1.2D)), 4)
                .add(0.0D, 1.1D, 0.0D);

        // 面片法线基准朝飞行方向：正面蛇头正对着前进方向，交叉面片补出体积
        double yaw = Math.atan2(flat.x, flat.z);
        List<SerpentSegment> segments = new ArrayList<>();
        // 蛇头：3 张 60° 交叉面片，处在整条蛇的最前方
        for (int i = 0; i < 3; i++) {
            Quaternionf rotation = DisplayFx.yawRotation(yaw + Math.toRadians(i * 60.0D));
            FxBillboard display = DisplayFx.spawn(level, start.add(flat.scale(0.6D)),
                    MagicItems.ICE_SERPENT_HEAD.get(), SERPENT_HEAD_SIZE, SERPENT_HEAD_SIZE,
                    SERPENT_HEAD_SIZE, Display.BillboardConstraints.FIXED, rotation);
            if (display != null) {
                segments.add(new SerpentSegment(display, -0.6D, 0.0D));
            }
        }
        // 蛇身：3 节，每节 2 张正交交叉面片，沿 -dir 方向逐节拖在头后
        for (int s = 0; s < SERPENT_BODY_SEGMENTS; s++) {
            double back = 0.5D + s * SERPENT_SEGMENT_GAP;
            for (int p = 0; p < 2; p++) {
                Quaternionf rotation = DisplayFx.yawRotation(yaw + Math.toRadians(p * 90.0D));
                FxBillboard display = DisplayFx.spawn(level, start.add(flat.scale(-back)),
                        MagicItems.ICE_SERPENT.get(), SERPENT_BODY_W, SERPENT_BODY_H,
                        SERPENT_BODY_H, Display.BillboardConstraints.FIXED, rotation);
                if (display != null) {
                    segments.add(new SerpentSegment(display, back, s + 1.0D));
                }
            }
        }
        if (segments.isEmpty()) {
            failCast(ctx, "庞莽生成失败");
            return;
        }
        SERPENTS.add(new Serpent(player, level, segments, start, flat, side, SERPENT_LENGTH));
        MagicDamage.playSound(level, start, SoundEvents.GLASS_BREAK, 1.0F, 0.6F);
    }

    /**
     * 围城：在准星指向的位置立起 5×3 冰墙，逐列升起，挡 15 秒并灼伤周围 2 格的生物，到时还原。
     *
     * <p>不再要求「前方有空位」：目标格一律强制改成浮冰（记录原状，到期还原），
     * 因此哪怕前面是石头、树干、雪堆也能把墙立起来。落点取准星指向点的水平投影，
     * 距离钳制在 2~6 格，避免贴脸或飘到天边。</p>
     */
    public static void wallCity(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        flat = flat.normalize();
        Vec3 side = new Vec3(-flat.z, 0.0D, flat.x);

        // 落点跟随准星（指令位置），水平距离钳制
        Vec3 aim = ctx.targetPos();
        double aimDistance = Math.sqrt((aim.x - player.getX()) * (aim.x - player.getX())
                + (aim.z - player.getZ()) * (aim.z - player.getZ()));
        // 准星落点：至少 2 格（别贴脸），最多就是当前阶级的施法范围
        double wallDistance = Math.max(WALL_MIN_DIST, ctx.clampDistance(aimDistance));
        Vec3 base = player.position().add(flat.scale(wallDistance));
        Vec3 ground = GroundDecal.groundBelow(level, base.add(0.0D, 1.6D, 0.0D), 8);
        BlockPos center = BlockPos.containing(ground);
        // 所有列共用同一个地面高度，墙才整齐；foot 是地面方块「上方第一格」
        BlockPos foot = center;

        // 强制更改地形：逐格记录原状后覆盖，到期只在仍是浮冰时还原
        Map<BlockPos, BlockState> originals = new HashMap<>();
        List<List<BlockPos>> columns = new ArrayList<>();
        for (int w = 0; w < WALL_W; w++) {
            int offset = w - (WALL_W - 1) / 2;
            Vec3 colCenter = base.add(side.scale(offset));
            BlockPos colFoot = BlockPos.containing(colCenter.x, foot.getY() + 0.02D, colCenter.z);
            List<BlockPos> column = new ArrayList<>(WALL_H);
            for (int h = 0; h < WALL_H; h++) {
                BlockPos pos = colFoot.above(h).immutable();
                BlockState state = level.getBlockState(pos);
                // 基岩/屏障/容器不动（容器 NBT 无法随 BlockState 一起还原）
                if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)
                        || level.getBlockEntity(pos) != null) {
                    continue;
                }
                originals.putIfAbsent(pos, state);
                column.add(pos);
            }
            columns.add(column);
        }

        long now = level.getGameTime();
        // 云冰晶界领域内：所有技能施法时间（前摇）-50%
        int windup = castTicks(ctx.player(), WALL_WINDUP);
        IceWall wall = new IceWall(ctx.player(), level, originals, columns, center, now,
                now + windup, now + windup + WALL_TICKS, ctx.skill().id(),
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()));
        WALLS.add(wall);

        PlayerMagicData data = MagicData.get(player);
        // 前摇结束就开始冷却（需求：前摇动画完后进入冷却）
        data.setCooldown(ctx.skill().id(), now + windup + 1L);
        data.lockSkill(ctx.skill().id(), now + windup + 4L);

        // 前摇：地面结霜 + 寒气汇聚
        level.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                center.getX() + 0.5D, center.getY() + 0.3D, center.getZ() + 0.5D,
                20, 2.2D, 0.1D, 0.4D, 0.01D);
        MagicDamage.playSound(level, Vec3.atCenterOf(center), SoundEvents.GLASS_PLACE, 1.0F, 0.8F);
    }

    // ==================================================================
    // 高阶·冰封灵柩
    // ==================================================================

    /**
     * 冰封灵柩：前摇左右两面冰墙向锁定生物中间靠拢，合拢完成才算「冰封」成型；
     * 成型后定身 30 秒 + 每秒 15 点 + 受伤 ×1.3，30 秒后封棺爆碎 100 点。
     *
     * <p>目标中途死亡/消失不会提前融化冰块：实体冰棺按锁定时的位置生成并保留，
     * 直到技能时间结束才一起爆碎还原。</p>
     */
    public static void coffin(SkillContext ctx) {
        LivingEntity target = ctx.targetEntity();
        if (target == null) {
            failCast(ctx, "冰封灵柩需要锁定一个生物");
            return;
        }
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        long now = level.getGameTime();

        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        flat = flat.normalize();
        Vec3 side = new Vec3(-flat.z, 0.0D, flat.x);
        Vec3 center = target.position().add(0.0D, 1.2D, 0.0D);

        // 前摇：左右两面冰墙，面片朝向施法者，从 ±SPREAD 向中间靠拢
        double yaw = Math.atan2(flat.x, flat.z);
        List<FxBillboard> sealWalls = new ArrayList<>(2);
        for (int k = 0; k < 2; k++) {
            double sign = k == 0 ? -1.0D : 1.0D;
            Vec3 pos = center.add(side.scale(sign * COFFIN_WALL_SPREAD));
            Quaternionf rotation = DisplayFx.yawRotation(yaw);
            FxBillboard wall = DisplayFx.spawn(level, pos, MagicItems.ICE_COFFIN_WALL.get(),
                    COFFIN_WALL_W, COFFIN_WALL_H, 1.0F, Display.BillboardConstraints.FIXED, rotation);
            if (wall != null) {
                sealWalls.add(wall);
            }
        }

        // 云冰晶界领域内：所有技能施法时间（前摇）-50%
        int seal = castTicks(ctx.player(), COFFIN_SEAL_TICKS);
        Frozen previous = findFrozen(target.getUUID());
        if (previous != null) {
            previous.release();
            FROZENS.remove(previous);
        }
        // 前摇期间就锁定生物，保证左右冰墙合拢时目标不会跑掉
        applyRoot(target, COFFIN_SPEED_ID, COFFIN_JUMP_ID);
        FROZENS.add(new Frozen(target, now + seal + COFFIN_TICKS,
                COFFIN_SPEED_ID, COFFIN_JUMP_ID));

        PlayerMagicData data = MagicData.get(player);
        int cooldownSeconds = ctx.skill().cooldownSeconds(data.rank());
        // 封印 + 存在期间锁定技能，结束后由 tick 写完整冷却（先写"效果时长"兜底）
        data.setCooldown(ctx.skill().id(), now + seal + COFFIN_TICKS);
        data.lockSkill(ctx.skill().id(), now + seal + COFFIN_TICKS + 4L);

        COFFINS.add(new Coffin(player, level, target, center, side, sealWalls, now,
                now + seal, now + seal + COFFIN_TICKS,
                ctx.skill().id(), cooldownSeconds));

        MagicDamage.playSound(level, target.position(), SoundEvents.GLASS_PLACE, 1.0F, 0.7F);
        iceBurst(level, target.position().add(0.0D, 0.6D, 0.0D), 1.8D);
    }

    // ==================================================================
    // 超阶·云冰晶界
    // ==================================================================

    /**
     * 云冰晶界：半径 = 施法者当前阶级的技能施法范围（超阶 30 格，不小于 9 格），持续 60 秒。
     * 领域内持续落雪；敌对生物每秒 12 点冰系伤害、缓慢 60 秒；连续停留 3 秒冻结 2 秒。
     * 领域存在期间施法者获得 +100 攻击 / 魔力 / 蓝量上限 / 魔暴伤，免疫爆炸 / 缓慢 / 冰冻 / 失明，
     * 所有技能前摇与冷却时间 -50%。
     */
    public static void domain(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        Vec3 ground = GroundDecal.groundBelow(level, ctx.targetPos(), 6);
        long now = level.getGameTime();
        double radius = Math.max(DOMAIN_MIN_RADIUS, ctx.range());

        // 边界冰晶柱（面朝中心）：半径越大柱子越多，避免大领域边界过于稀疏。
        // 模型是标准 16x16 元素，靠 Y 缩放拉成 4 格高；中心抬到 2 格让底部正好贴地。
        int pillars = Math.min(36, Math.max(12, (int) Math.ceil(radius / 1.2D)));
        List<FxBillboard> crystals = new ArrayList<>(pillars);
        for (int i = 0; i < pillars; i++) {
            double angle = i * Math.PI * 2.0D / pillars;
            Vec3 pos = new Vec3(ground.x + Math.cos(angle) * radius,
                    ground.y + CRYSTAL_HEIGHT_SCALE * 0.5D,
                    ground.z + Math.sin(angle) * radius);
            // 面片朝圆心：面片法线默认 +Z，绕 Y 旋 -(角度+90°) 后正好指向圆心
            Quaternionf rotation = DisplayFx.yawRotation(-(angle + Math.PI / 2.0D));
            FxBillboard crystal = DisplayFx.spawn(level, pos, MagicItems.ICE_CRYSTAL.get(),
                    1.0F, CRYSTAL_HEIGHT_SCALE, 1.0F, Display.BillboardConstraints.FIXED, rotation);
            if (crystal != null) {
                crystals.add(crystal);
            }
        }
        if (crystals.isEmpty()) {
            failCast(ctx, "云冰晶界生成失败");
            return;
        }

        PlayerMagicData data = MagicData.get(player);
        int cooldownSeconds = ctx.skill().cooldownSeconds(data.rank());
        // 领域存在期间锁定技能，结束后写完整冷却（先写"效果时长"兜底）
        data.setCooldown(ctx.skill().id(), now + DOMAIN_TICKS);
        data.lockSkill(ctx.skill().id(), now + DOMAIN_TICKS + 4L);

        DOMAINS.add(new Domain(player, level, ground, radius, crystals, now + DOMAIN_TICKS,
                now, ctx.skill().id(), cooldownSeconds));
        applyDomainBuff(player);

        MagicDamage.playSound(level, ground, SoundEvents.GLASS_BREAK, 1.2F, 0.6F);
        // 开场：漫天飘雪铺满整个领域
        level.sendParticles(ParticleTypes.SNOWFLAKE, ground.x, ground.y + 1.0D, ground.z,
                180, radius * 0.8D, 2.2D, radius * 0.8D, 0.02D);
        for (int i = 0; i < 32; i++) {
            double angle = i * Math.PI * 2.0D / 32.0D;
            MagicDamage.line(level,
                    ground.add(Math.cos(angle) * 0.6D, 0.15D, Math.sin(angle) * 0.6D),
                    ground.add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius),
                    iceParticle(), Math.max(0.7D, radius / 18.0D));
        }
    }

    // ==================================================================
    // 每 tick 驱动
    // ==================================================================

    /** 服务端每 tick（MagicEvents.onServerTick） */
    public static void tickWorld() {
        tickSpikes();
        tickFrozens();
        tickCovers();
        tickCrushes();
        tickSerpents();
        tickWalls();
        tickCoffins();
        tickDomains();
    }

    private static void tickSpikes() {
        for (int i = SPIKES.size() - 1; i >= 0; i--) {
            Spike spike = SPIKES.get(i);
            ServerLevel level = spike.level;
            if (!spike.display.isAlive() || level.getGameTime() > spike.expireTick) {
                spike.display.discard();
                SPIKES.remove(i);
                continue;
            }
            Vec3 next = spike.pos.add(spike.dir.scale(SPIKE_SPEED));
            // 撞方块：碎裂
            BlockPos cell = BlockPos.containing(next);
            if (level.getBlockState(cell).isSolidRender()) {
                spike.display.discard();
                level.sendParticles(ParticleTypes.ITEM_SNOWBALL, next.x, next.y, next.z,
                        14, 0.25D, 0.25D, 0.25D, 0.04D);
                MagicDamage.playSound(level, next, SoundEvents.GLASS_BREAK, 0.6F, 1.9F);
                SPIKES.remove(i);
                continue;
            }
            // 命中生物（不含施法者）
            AABB area = new AABB(next, next).inflate(0.9D);
            LivingEntity victim = null;
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                if (entity == spike.owner || !entity.isAlive() || entity.isSpectator()) {
                    continue;
                }
                victim = entity;
                break;
            }
            if (victim != null) {
                MagicDamage.magicDamage(spike.owner, victim, SPIKE_DAMAGE, MagicElement.ICE);
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SPIKE_SLOW_TICKS, 1));
                level.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                        victim.getX(), victim.getY() + victim.getBbHeight() * 0.5D, victim.getZ(),
                        18, 0.35D, 0.45D, 0.35D, 0.03D);
                MagicDamage.playSound(level, next, SoundEvents.GLASS_BREAK, 0.9F, 1.5F);
                spike.display.discard();
                SPIKES.remove(i);
                continue;
            }
            spike.pos = next;
            spike.display.setPos(next.x, next.y, next.z);
            level.sendParticles(iceParticle(), next.x, next.y, next.z, 2, 0.06D, 0.06D, 0.06D, 0.0D);
        }
    }

    private static void tickFrozens() {
        long now = 0L;
        for (int i = FROZENS.size() - 1; i >= 0; i--) {
            Frozen frozen = FROZENS.get(i);
            if (!(frozen.entity.level() instanceof ServerLevel level)) {
                FROZENS.remove(i);
                continue;
            }
            now = level.getGameTime();
            if (!frozen.entity.isAlive() || now >= frozen.endTick) {
                frozen.release();
                FROZENS.remove(i);
                continue;
            }
            holdStill(frozen.entity);
            // 冰封外观：身上持续冒寒气
            if (now % 4L == 0L) {
                level.sendParticles(ParticleTypes.SNOWFLAKE,
                        frozen.entity.getX(), frozen.entity.getY() + frozen.entity.getBbHeight() * 0.5D,
                        frozen.entity.getZ(), 2, 0.28D, 0.4D, 0.28D, 0.01D);
            }
        }
    }

    private static void tickCovers() {
        for (int i = COVERS.size() - 1; i >= 0; i--) {
            Cover cover = COVERS.get(i);
            ServerLevel level = cover.level;
            long now = level.getGameTime();
            if (now >= cover.endTick) {
                cover.restore();
                COVERS.remove(i);
                continue;
            }
            // 冰面打滑：站在上面的生物随机侧滑；
            // 敌对生物踏上时缓慢 5 秒 + 一次性 9 点冰系伤害（每片冰面对同一只生物只结算一次）
            AABB area = cover.bounds.inflate(0.2D, 0.6D, 0.2D);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                if (!entity.onGround() || entity.isSpectator()) {
                    continue;
                }
                if (now % 6L == 0L) {
                    double angle = entity.getRandom().nextDouble() * Math.PI * 2.0D;
                    entity.setDeltaMovement(entity.getDeltaMovement()
                            .add(Math.cos(angle) * 0.09D, 0.0D, Math.sin(angle) * 0.09D));
                    entity.hurtMarked = true;
                }
                if (entity == cover.owner || !entity.isAlive()
                        || !isHostile(cover.owner, entity)) {
                    continue;
                }
                if (now % 10L == 0L || !entity.hasEffect(MobEffects.SLOWNESS)) {
                    entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, COVER_SLOW_TICKS, 1, false, false));
                }
                if (cover.damaged.add(entity.getUUID())) {
                    MagicDamage.magicDamage(cover.owner, entity, COVER_DAMAGE, MagicElement.ICE);
                    level.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                            entity.getX(), entity.getY() + 0.4D, entity.getZ(),
                            12, 0.35D, 0.3D, 0.35D, 0.03D);
                }
            }
        }
    }

    private static void tickCrushes() {
        for (int i = CRUSHES.size() - 1; i >= 0; i--) {
            Crush crush = CRUSHES.get(i);
            ServerLevel level = crush.level;
            long now = level.getGameTime();
            if (!crush.target.isAlive()) {
                releaseCrush(crush, false);
                CRUSHES.remove(i);
                continue;
            }
            if (now >= crush.nextDotTick) {
                crush.nextDotTick = now + 20L;
                MagicDamage.magicDamage(crush.owner, crush.target, CRUSH_DPS, MagicElement.ICE);
                level.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                        crush.target.getX(), crush.target.getY() + crush.target.getBbHeight() * 0.5D,
                        crush.target.getZ(), 8, 0.3D, 0.5D, 0.3D, 0.02D);
            }
            if (now >= crush.endTick) {
                releaseCrush(crush, true);
                CRUSHES.remove(i);
            }
        }
    }

    private static void releaseCrush(Crush crush, boolean burst) {
        ServerLevel level = crush.level;
        Frozen frozen = findFrozen(crush.target.getUUID());
        if (frozen != null) {
            frozen.release();
            FROZENS.remove(frozen);
        }
        if (burst && crush.target.isAlive()) {
            MagicDamage.magicDamage(crush.owner, crush.target, CRUSH_BURST, MagicElement.ICE);
            iceBurst(level, crush.target.position().add(0.0D, 0.8D, 0.0D), 2.0D);
            level.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                    crush.target.getX(), crush.target.getY() + 0.9D, crush.target.getZ(),
                    30, 0.5D, 0.6D, 0.5D, 0.06D);
            MagicDamage.playSound(level, crush.target.position(), SoundEvents.GLASS_BREAK, 1.1F, 0.9F);
        }
        PlayerMagicData data = MagicData.get(crush.owner);
        data.setCooldown(crush.skillId, level.getGameTime() + crush.cooldownSeconds * 20L);
        MagicSync.sync(crush.owner);
    }

    private static void tickSerpents() {
        for (int i = SERPENTS.size() - 1; i >= 0; i--) {
            Serpent serpent = SERPENTS.get(i);
            ServerLevel level = serpent.level;
            if (serpent.segments.isEmpty() || !serpent.segments.get(0).display.isAlive()) {
                serpent.discardAll();
                SERPENTS.remove(i);
                continue;
            }
            Vec3 next = serpent.pos.add(serpent.dir.scale(SERPENT_SPEED));
            double travelled = next.distanceTo(serpent.start);
            BlockPos cell = BlockPos.containing(next);
            boolean blocked = level.getBlockState(cell).isSolidRender()
                    || level.getBlockState(cell.above()).isSolidRender();
            if (blocked || travelled >= serpent.length) {
                serpent.discardAll();
                level.sendParticles(ParticleTypes.ITEM_SNOWBALL, next.x, next.y, next.z,
                        22, 0.6D, 0.5D, 0.6D, 0.05D);
                MagicDamage.playSound(level, next, SoundEvents.GLASS_BREAK, 1.0F, 1.1F);
                SERPENTS.remove(i);
                continue;
            }
            serpent.pos = next;
            serpent.age++;
            // 每一节按自己的相位左右摆尾 + 上下起伏，整条蛇看起来在游动
            for (SerpentSegment segment : serpent.segments) {
                double sway = Math.sin(serpent.age * 0.38D - segment.phase * 0.9D) * 0.30D;
                double bob = Math.sin(serpent.age * 0.30D - segment.phase * 0.7D) * 0.14D;
                Vec3 point = next.add(serpent.dir.scale(-segment.back))
                        .add(serpent.side.scale(sway)).add(0.0D, bob, 0.0D);
                segment.display.setPos(point.x, point.y, point.z);
            }

            // 伤害范围覆盖整条蛇身（不只蛇头那一点）
            AABB area = new AABB(next.add(serpent.dir.scale(0.9D)),
                    next.add(serpent.dir.scale(-SERPENT_BODY_REACH))).inflate(1.1D, 1.2D, 1.1D);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                if (entity == serpent.owner || !entity.isAlive() || entity.isSpectator()) {
                    continue;
                }
                if (!serpent.hits.add(entity.getUUID())) {
                    continue;
                }
                MagicDamage.magicDamage(serpent.owner, entity, SERPENT_DAMAGE, MagicElement.ICE);
                entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 4 * 20, 1));
                Vec3 push = entity.position().subtract(serpent.pos).multiply(1.0D, 0.0D, 1.0D);
                if (push.lengthSqr() > 1.0E-3D) {
                    push = push.normalize().scale(0.7D);
                    entity.setDeltaMovement(entity.getDeltaMovement().add(push.x, 0.35D, push.z));
                    entity.hurtMarked = true;
                }
                iceBurst(level, entity.position().add(0.0D, 0.8D, 0.0D), 1.4D);
            }
            level.sendParticles(ParticleTypes.SNOWFLAKE, next.x, next.y + 0.4D, next.z,
                    3, 0.3D, 0.3D, 0.3D, 0.01D);
        }
    }

    private static void tickWalls() {
        for (int i = WALLS.size() - 1; i >= 0; i--) {
            IceWall wall = WALLS.get(i);
            ServerLevel level = wall.level;
            long now = level.getGameTime();
            if (!wall.owner.isAlive() || wall.owner.isRemoved()) {
                wall.restore();
                WALLS.remove(i);
                continue;
            }
            // 逐列升起：前摇结束后每 2 tick 起一列
            while (wall.raisedColumns < wall.columns.size()
                    && now >= wall.windupTick + wall.raisedColumns * 2L) {
                List<BlockPos> column = wall.columns.get(wall.raisedColumns);
                for (BlockPos pos : column) {
                    level.setBlock(pos, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_ALL);
                }
                if (!column.isEmpty()) {
                    Vec3 top = Vec3.atCenterOf(column.get(column.size() - 1));
                    level.sendParticles(ParticleTypes.SNOWFLAKE, top.x, top.y + 0.6D, top.z,
                            10, 0.2D, 0.2D, 0.2D, 0.03D);
                    MagicDamage.playSound(level, top, SoundEvents.GLASS_PLACE, 0.5F, 1.3F);
                }
                wall.raisedColumns++;
            }
            // 冰墙寒气：墙周围 2 格内的生物每秒受 10 点冰系伤害（施法者自己不受影响）
            if (now >= wall.windupTick && now < wall.endTick && now >= wall.nextDotTick) {
                wall.nextDotTick = now + 20L;
                AABB aura = wall.bounds.inflate(WALL_AURA_RADIUS);
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, aura)) {
                    if (entity == wall.owner || !entity.isAlive() || entity.isSpectator()) {
                        continue;
                    }
                    MagicDamage.magicDamage(wall.owner, entity, WALL_DPS, MagicElement.ICE);
                    level.sendParticles(ParticleTypes.SNOWFLAKE,
                            entity.getX(), entity.getY() + entity.getBbHeight() * 0.5D, entity.getZ(),
                            6, 0.25D, 0.35D, 0.25D, 0.02D);
                }
            }
            // 到时融化
            if (now >= wall.endTick) {
                wall.restore();
                WALLS.remove(i);
                // 冷却在前摇结束时已写入，这里只补一次同步（让客户端冷却条立刻刷新）
                MagicSync.sync(wall.owner);
            }
        }
    }

    private static void tickCoffins() {
        for (int i = COFFINS.size() - 1; i >= 0; i--) {
            Coffin coffin = COFFINS.get(i);
            ServerLevel level = coffin.level;
            long now = level.getGameTime();
            // 只有施法者没了才提前收；目标死亡/消失时冰块继续保留到技能结束
            if (!coffin.owner.isAlive() || coffin.owner.isRemoved()) {
                coffin.restore();
                releaseCoffin(coffin, false);
                COFFINS.remove(i);
                continue;
            }
            boolean targetGone = !coffin.target.isAlive() || coffin.target.isRemoved();

            // 前摇：左右两面冰墙向生物中间靠拢
            if (now < coffin.sealEndTick) {
                coffin.sealStep++;
                double total = (double) (coffin.sealEndTick - coffin.castTick);
                double progress = Math.max(0.0D, Math.min(1.0D,
                        (now - coffin.castTick + 1.0D) / Math.max(1.0D, total)));
                double spread = COFFIN_WALL_SPREAD
                        + (COFFIN_WALL_CLOSE - COFFIN_WALL_SPREAD) * progress;
                for (int k = 0; k < coffin.sealWalls.size(); k++) {
                    double sign = k == 0 ? -1.0D : 1.0D;
                    Vec3 point = coffin.center.add(coffin.side.scale(sign * spread));
                    coffin.sealWalls.get(k).setPos(point.x, point.y, point.z);
                }
                if (coffin.sealStep % 2 == 0) {
                    level.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                            coffin.center.x, coffin.center.y + coffin.sealStep * 0.04D, coffin.center.z,
                            6, 0.45D, 0.1D, 0.45D, 0.02D);
                }
                continue;
            }

            // 冰封成型：合拢完成，撤掉贴图墙、按锁定位置生成实体冰棺（目标消失也一样生成）
            if (!coffin.built) {
                coffin.discardWalls();
                coffin.build();
                MagicDamage.playSound(level, coffin.center, SoundEvents.GLASS_BREAK, 1.0F, 0.8F);
            }
            // 每秒伤害：目标已经消失就跳过，但冰块和计时都继续
            if (!targetGone && now >= coffin.nextDotTick) {
                coffin.nextDotTick = now + 20L;
                MagicDamage.magicDamage(coffin.owner, coffin.target, COFFIN_DPS, MagicElement.ICE);
            }
            if (now >= coffin.endTick) {
                // 30 秒到：封棺爆碎（生物已消失就不再结算爆炸伤害）
                if (!targetGone) {
                    MagicDamage.magicDamage(coffin.owner, coffin.target, COFFIN_BURST, MagicElement.ICE);
                }
                coffin.restore();
                iceBurst(level, coffin.center.add(0.0D, 1.0D, 0.0D), 3.0D);
                level.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                        coffin.center.x, coffin.center.y + 1.0D, coffin.center.z,
                        60, 1.2D, 1.2D, 1.2D, 0.08D);
                MagicDamage.playSound(level, coffin.center, SoundEvents.GLASS_BREAK, 1.3F, 0.7F);
                releaseCoffin(coffin, true);
                COFFINS.remove(i);
            }
        }
    }

    private static void releaseCoffin(Coffin coffin, boolean normalEnd) {
        Frozen frozen = findFrozen(coffin.target.getUUID());
        if (frozen != null) {
            frozen.release();
            FROZENS.remove(frozen);
        }
        PlayerMagicData data = MagicData.get(coffin.owner);
        data.setCooldown(coffin.skillId, coffin.level.getGameTime() + coffin.cooldownSeconds * 20L);
        MagicSync.sync(coffin.owner);
    }

    private static void tickDomains() {
        for (int i = DOMAINS.size() - 1; i >= 0; i--) {
            Domain domain = DOMAINS.get(i);
            ServerLevel level = domain.level;
            long now = level.getGameTime();
            if (!domain.owner.isAlive() || now >= domain.endTick) {
                domain.clear();
                DOMAINS.remove(i);
                // 领域先消失再卸增益：结束后的完整冷却不再享受"冷却 -50%"
                removeDomainBuff(domain.owner);
                PlayerMagicData data = MagicData.get(domain.owner);
                data.endSuperRing(domain.skillId);
                data.setCooldown(domain.skillId, now + domain.cooldownSeconds * 20L);
                MagicSync.sync(domain.owner);
                continue;
            }

            // 领域内施法者：免疫缓慢 / 失明 / 冰冻（含本模组的冰封定身）
            protectOwner(domain.owner);
            // 落雪特效：整片领域持续飘雪，60 秒不断
            level.sendParticles(ParticleTypes.SNOWFLAKE,
                    domain.center.x, domain.center.y + 3.0D, domain.center.z,
                    32, domain.radius * 0.75D, 3.0D, domain.radius * 0.75D, 0.02D);

            // 每秒结算一次伤害 + 缓慢（缓慢持续 60 秒，离开领域后也继续）
            boolean damageTick = now % 20L == 0L;
            AABB area = new AABB(domain.center, domain.center)
                    .inflate(domain.radius, 6.0D, domain.radius);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                if (entity == domain.owner || !entity.isAlive() || entity.isSpectator()) {
                    continue;
                }
                if (!isHostile(domain.owner, entity)) {
                    continue;
                }
                double horizontal = Math.sqrt(
                        (entity.getX() - domain.center.x) * (entity.getX() - domain.center.x)
                                + (entity.getZ() - domain.center.z) * (entity.getZ() - domain.center.z));
                if (horizontal > domain.radius) {
                    continue;
                }
                if (damageTick) {
                    entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DOMAIN_SLOW_TICKS, 1, false, false));
                    MagicDamage.magicDamage(domain.owner, entity, DOMAIN_DPS, MagicElement.ICE);
                }
                // 连续停留累计：每秒 +1，攒满 3 秒冻结 2 秒
                int chill = damageTick
                        ? domain.chill.merge(entity.getUUID(), 1, Integer::sum)
                        : domain.chill.getOrDefault(entity.getUUID(), 0);
                if (chill >= DOMAIN_CHILL_TICKS / 20) {
                    domain.chill.put(entity.getUUID(), 0);
                    Frozen previous = findFrozen(entity.getUUID());
                    if (previous != null) {
                        previous.release();
                        FROZENS.remove(previous);
                    }
                    applyRoot(entity, FREEZE_SPEED_ID, FREEZE_JUMP_ID);
                    FROZENS.add(new Frozen(entity, now + DOMAIN_FREEZE_TICKS,
                            FREEZE_SPEED_ID, FREEZE_JUMP_ID));
                    level.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                            entity.getX(), entity.getY() + entity.getBbHeight() * 0.5D, entity.getZ(),
                            24, 0.4D, 0.7D, 0.4D, 0.04D);
                    MagicDamage.playSound(level, entity.position(), SoundEvents.GLASS_BREAK, 0.9F, 1.4F);
                }
            }
            // 离开领域太久就把累计清零（简化：每 20 tick 统一衰减）
            if (damageTick) {
                domain.chill.replaceAll((key, value) -> Math.max(0, value - 1));
            }
        }
    }

    // ==================================================================
    // 对外查询 / 清理
    // ==================================================================

    /** 该玩家是否有正在生效的云冰晶界（增益 / 免疫 / 冷却减半统一走这里） */
    public static boolean hasDomainBuff(ServerPlayer player) {
        for (Domain domain : DOMAINS) {
            if (domain.owner == player) {
                return true;
            }
        }
        return false;
    }

    /** 云冰晶界领域内：所有技能施法时间（前摇）-50%，至少保留 1 tick */
    public static int castTicks(ServerPlayer player, int baseTicks) {
        return hasDomainBuff(player) ? Math.max(1, baseTicks / 2) : baseTicks;
    }

    /** 挂上领域增益：+100 攻击 / 魔力 / 蓝量上限 / 魔暴伤，后三项在 PlayerMagicData / MagicDamage 里算 */
    private static void applyDomainBuff(ServerPlayer player) {
        PlayerMagicData data = MagicData.get(player);
        data.setIceDomainActive(true);
        var attack = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.addOrUpdateTransientModifier(
                    new AttributeModifier(DOMAIN_ATTACK_ID, 100.0D, AttributeModifier.Operation.ADD_VALUE));
        }
        // 蓝量上限 +100 立即生效，顺便补满给玩家直观反馈
        data.addMana(100.0D);
        MagicSync.sync(player);
    }

    /** 卸下领域增益（玩家还有别的领域在时不卸） */
    private static void removeDomainBuff(ServerPlayer player) {
        for (Domain domain : DOMAINS) {
            if (domain.owner == player) {
                return;
            }
        }
        PlayerMagicData data = MagicData.get(player);
        if (!data.iceDomainActive()) {
            return;
        }
        data.setIceDomainActive(false);
        var attack = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.removeModifier(DOMAIN_ATTACK_ID);
        }
        // 上限回落后把当前蓝量夹回合法范围
        data.setMana(data.mana());
        if (player.connection != null) {
            MagicSync.sync(player);
        }
    }

    /** 领域内免疫：缓慢 / 失明 / 冰冻（原版 freeze tick + 本模组的冰封定身） */
    private static void protectOwner(ServerPlayer player) {
        player.removeEffect(MobEffects.SLOWNESS);
        player.removeEffect(MobEffects.BLINDNESS);
        player.setTicksFrozen(0);
        Frozen frozen = findFrozen(player.getUUID());
        if (frozen != null) {
            frozen.release();
            FROZENS.remove(frozen);
        }
    }

    /** 冰棺内的生物受到的所有伤害 ×1.3（MagicEvents 调用） */
    public static float incomingMultiplier(LivingEntity entity) {
        for (Coffin coffin : COFFINS) {
            if (coffin.built && coffin.target == entity) {
                return (float) COFFIN_WEAKEN;
            }
        }
        return 1.0F;
    }

    /** 玩家下线/死亡：收掉他自己的所有冰系效果 */
    public static void removePlayer(ServerPlayer player) {
        for (int i = SPIKES.size() - 1; i >= 0; i--) {
            if (SPIKES.get(i).owner == player) {
                SPIKES.get(i).display.discard();
                SPIKES.remove(i);
            }
        }
        for (int i = SERPENTS.size() - 1; i >= 0; i--) {
            if (SERPENTS.get(i).owner == player) {
                SERPENTS.get(i).discardAll();
                SERPENTS.remove(i);
            }
        }
        for (int i = WALLS.size() - 1; i >= 0; i--) {
            if (WALLS.get(i).owner == player) {
                WALLS.get(i).restore();
                WALLS.remove(i);
            }
        }
        for (int i = COFFINS.size() - 1; i >= 0; i--) {
            Coffin coffin = COFFINS.get(i);
            if (coffin.owner == player) {
                coffin.restore();
                releaseCoffin(coffin, false);
                COFFINS.remove(i);
            }
        }
        for (int i = DOMAINS.size() - 1; i >= 0; i--) {
            if (DOMAINS.get(i).owner == player) {
                DOMAINS.get(i).clear();
                DOMAINS.remove(i);
            }
        }
        for (int i = CRUSHES.size() - 1; i >= 0; i--) {
            if (CRUSHES.get(i).owner == player) {
                releaseCrush(CRUSHES.get(i), false);
                CRUSHES.remove(i);
            }
        }
        // 领域已收掉，增益也要卸干净（否则下线/重生后仍带着 +100 攻击、免疫与减冷却）
        removeDomainBuff(player);
    }

    /** 服务器关闭：全部收干净、方块还原成原样 */
    public static void clearAll() {
        Set<ServerPlayer> domainOwners = new HashSet<>();
        DOMAINS.forEach(domain -> domainOwners.add(domain.owner));
        SPIKES.forEach(spike -> spike.display.discard());
        SPIKES.clear();
        FROZENS.forEach(Frozen::release);
        FROZENS.clear();
        COVERS.forEach(Cover::restore);
        COVERS.clear();
        SERPENTS.forEach(Serpent::discardAll);
        SERPENTS.clear();
        WALLS.forEach(IceWall::restore);
        WALLS.clear();
        COFFINS.forEach(Coffin::restore);
        COFFINS.clear();
        DOMAINS.forEach(Domain::clear);
        DOMAINS.clear();
        CRUSHES.clear();
        domainOwners.forEach(IceMagic::removeDomainBuff);
    }

    // ==================================================================
    // 内部类
    // ==================================================================

    /** 飞行中的冰锥 */
    private static final class Spike {
        final ServerPlayer owner;
        final ServerLevel level;
        final FxBillboard display;
        final Vec3 dir;
        final double maxDistance;
        final long expireTick;
        Vec3 pos;

        Spike(ServerPlayer owner, ServerLevel level, FxBillboard display,
              Vec3 pos, Vec3 dir, double maxDistance, long expireTick) {
            this.owner = owner;
            this.level = level;
            this.display = display;
            this.pos = pos;
            this.dir = dir;
            this.maxDistance = maxDistance;
            this.expireTick = expireTick;
        }
    }

    /** 定身中的生物（按 id 移除属性修饰器即可解除） */
    private static final class Frozen {
        final LivingEntity entity;
        final long endTick;
        final Identifier speedId;
        final Identifier jumpId;

        Frozen(LivingEntity entity, long endTick, Identifier speedId, Identifier jumpId) {
            this.entity = entity;
            this.endTick = endTick;
            this.speedId = speedId;
            this.jumpId = jumpId;
        }

        void release() {
            removeModifier(entity, Attributes.MOVEMENT_SPEED, speedId);
            removeModifier(entity, Attributes.JUMP_STRENGTH, jumpId);
        }
    }

    /** 碾骨：定身 + 每秒伤害 + 结束爆发 */
    private static final class Crush {
        final ServerPlayer owner;
        final ServerLevel level;
        final LivingEntity target;
        final long endTick;
        long nextDotTick;
        final String skillId;
        final int cooldownSeconds;

        Crush(ServerPlayer owner, ServerLevel level, LivingEntity target, long endTick,
              long nextDotTick, String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.target = target;
            this.endTick = endTick;
            this.nextDotTick = nextDotTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }
    }

    /** 冰面：记录被替换的方块，到期还原 */
    private static final class Cover {
        final ServerPlayer owner;
        final ServerLevel level;
        final Map<BlockPos, BlockState> originals;
        final long endTick;
        final AABB bounds;
        /** 同一片冰面对同一只敌对生物只结算一次 9 点伤害 */
        final Set<UUID> damaged = new HashSet<>();

        Cover(ServerPlayer owner, ServerLevel level, Map<BlockPos, BlockState> originals, long endTick) {
            this.owner = owner;
            this.level = level;
            this.originals = originals;
            this.endTick = endTick;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (BlockPos pos : originals.keySet()) {
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX() + 1.0D);
                maxY = Math.max(maxY, pos.getY() + 1.0D);
                maxZ = Math.max(maxZ, pos.getZ() + 1.0D);
            }
            this.bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }

        void restore() {
            for (Map.Entry<BlockPos, BlockState> entry : originals.entrySet()) {
                BlockState current = level.getBlockState(entry.getKey());
                // 只还原仍是我们铺的浮冰，避免把玩家后来放的东西一起改掉
                if (current.is(Blocks.PACKED_ICE)) {
                    level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** 冰蟒的一张面片：back = 沿 -dir 落后蛇头的格数，phase = 摆动相位 */
    private record SerpentSegment(FxBillboard display, double back, double phase) {
    }

    /** 冲出的冰蟒（立体：蛇头 3 张交叉 + 躯干 3 节各 2 张交叉） */
    private static final class Serpent {
        final ServerPlayer owner;
        final ServerLevel level;
        final List<SerpentSegment> segments;
        final Vec3 start;
        final Vec3 dir;
        final Vec3 side;
        final double length;
        final Set<UUID> hits = new HashSet<>();
        Vec3 pos;
        int age;

        Serpent(ServerPlayer owner, ServerLevel level, List<SerpentSegment> segments,
                Vec3 start, Vec3 dir, Vec3 side, double length) {
            this.owner = owner;
            this.level = level;
            this.segments = segments;
            this.start = start;
            this.pos = start;
            this.dir = dir;
            this.side = side;
            this.length = length;
        }

        void discardAll() {
            segments.forEach(segment -> segment.display.discard());
            segments.clear();
        }
    }

    /** 围城冰墙 */
    private static final class IceWall {
        final ServerPlayer owner;
        final ServerLevel level;
        final Map<BlockPos, BlockState> originals;
        final List<List<BlockPos>> columns;
        final long castTick;
        final long windupTick;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        final AABB bounds;
        int raisedColumns;
        /** 下一次"周围 2 格寒气伤害"的结算 tick */
        long nextDotTick;

        IceWall(ServerPlayer owner, ServerLevel level, Map<BlockPos, BlockState> originals,
                List<List<BlockPos>> columns, BlockPos center, long castTick, long windupTick,
                long endTick, String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.originals = originals;
            this.columns = columns;
            this.castTick = castTick;
            this.windupTick = windupTick;
            this.endTick = endTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
            this.nextDotTick = windupTick + 20L;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (BlockPos pos : originals.keySet()) {
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX() + 1.0D);
                maxY = Math.max(maxY, pos.getY() + 1.0D);
                maxZ = Math.max(maxZ, pos.getZ() + 1.0D);
            }
            // 目标格全是基岩/容器时退化成落点处零体积 AABB，避免非法坐标
            if (minX > maxX) {
                minX = maxX = center.getX() + 0.5D;
                minY = maxY = center.getY() + 0.5D;
                minZ = maxZ = center.getZ() + 0.5D;
            }
            this.bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }

        void restore() {
            for (Map.Entry<BlockPos, BlockState> entry : originals.entrySet()) {
                BlockState current = level.getBlockState(entry.getKey());
                if (current.is(Blocks.PACKED_ICE)) {
                    level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** 冰封灵柩 */
    private static final class Coffin {
        final ServerPlayer owner;
        final ServerLevel level;
        final LivingEntity target;
        /** 施法时锁定的中心（= 目标脚底 +1.2），目标消失后冰块仍按这里生成/爆碎 */
        final Vec3 center;
        final Vec3 side;
        final List<FxBillboard> sealWalls;
        final long castTick;
        final long sealEndTick;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        final Map<BlockPos, BlockState> originals = new HashMap<>();
        long nextDotTick;
        int sealStep;
        boolean built;

        Coffin(ServerPlayer owner, ServerLevel level, LivingEntity target, Vec3 center, Vec3 side,
               List<FxBillboard> sealWalls, long castTick,
               long sealEndTick, long endTick, String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.target = target;
            this.center = center;
            this.side = side;
            this.sealWalls = sealWalls;
            this.castTick = castTick;
            this.sealEndTick = sealEndTick;
            this.endTick = endTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
            this.nextDotTick = sealEndTick + 20L;
        }

        void discardWalls() {
            sealWalls.forEach(FxBillboard::discard);
            sealWalls.clear();
        }

        /** 冰棺成型：锁定位置四周 8 列浮冰 + 顶部封盖（只填可替换方块） */
        void build() {
            built = true;
            BlockPos feet = BlockPos.containing(center.x, center.y - 1.1D, center.z);
            int height = Math.max(2, (int) Math.ceil(target.getBbHeight()) + 1);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy < height; dy++) {
                        if (dx == 0 && dz == 0 && dy < height - 1) {
                            continue; // 中间留给生物本体，只封顶部
                        }
                        BlockPos pos = feet.offset(dx, dy, dz);
                        BlockState state = level.getBlockState(pos);
                        if (!state.canBeReplaced()) {
                            continue;
                        }
                        originals.putIfAbsent(pos.immutable(), state);
                        level.setBlock(pos, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }

        void restore() {
            discardWalls();
            for (Map.Entry<BlockPos, BlockState> entry : originals.entrySet()) {
                BlockState current = level.getBlockState(entry.getKey());
                if (current.is(Blocks.PACKED_ICE)) {
                    level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
                }
            }
            originals.clear();
        }
    }

    /** 云冰晶界领域 */
    private static final class Domain {
        final ServerPlayer owner;
        final ServerLevel level;
        final Vec3 center;
        /** 领域半径 = 施法者施法范围（超阶 30 格），不小于 9 格 */
        final double radius;
        final List<FxBillboard> crystals;
        final long endTick;
        final long startTick;
        final String skillId;
        final int cooldownSeconds;
        /** 每只生物在领域内连续停留的“秒数” */
        final Map<UUID, Integer> chill = new HashMap<>();

        Domain(ServerPlayer owner, ServerLevel level, Vec3 center, double radius,
               List<FxBillboard> crystals, long endTick, long startTick,
               String skillId, int cooldownSeconds) {
            this.owner = owner;
            this.level = level;
            this.center = center;
            this.radius = radius;
            this.crystals = crystals;
            this.endTick = endTick;
            this.startTick = startTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
        }

        void clear() {
            crystals.forEach(FxBillboard::discard);
            crystals.clear();
            chill.clear();
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 冰蓝白粒子 */
    private static ParticleOptions iceParticle() {
        return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, ICE_COLOR);
    }

    /** 冰光向外炸开一圈（纯视觉） */
    private static void iceBurst(ServerLevel level, Vec3 center, double radius) {
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI * 2.0D / 16.0D;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            MagicDamage.line(level, center.add(dx * 0.3D, 0.0D, dz * 0.3D),
                    center.add(dx * radius, 0.1D, dz * radius), iceParticle(), 0.35D);
        }
        level.sendParticles(ParticleTypes.SNOWFLAKE, center.x, center.y, center.z,
                12, radius * 0.6D, radius * 0.4D, radius * 0.6D, 0.02D);
    }

    private static @org.jspecify.annotations.Nullable Frozen findFrozen(UUID id) {
        for (Frozen frozen : FROZENS) {
            if (frozen.entity.getUUID().equals(id)) {
                return frozen;
            }
        }
        return null;
    }

    /** 敌对判定（PVP 感知）：敌对生物 / 玩家近期攻击过的生物 / PVP 开启时的其他玩家 */
    private static boolean isHostile(ServerPlayer owner, LivingEntity entity) {
        return CombatTracker.isEnemyTarget(owner, entity);
    }

    /** 定身：速度归零 + 跳跃封死 + 每 tick 清位移（在 tickFrozens 里调用 holdStill） */
    private static void applyRoot(LivingEntity entity, Identifier speedId, Identifier jumpId) {
        applyModifier(entity, Attributes.MOVEMENT_SPEED, speedId, -1.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        applyModifier(entity, Attributes.JUMP_STRENGTH, jumpId, -10.0D,
                AttributeModifier.Operation.ADD_VALUE);
        holdStill(entity);
    }

    private static void holdStill(LivingEntity entity) {
        Vec3 motion = entity.getDeltaMovement();
        entity.setDeltaMovement(0.0D, Math.min(0.0D, motion.y), 0.0D);
        entity.hurtMarked = true;
        if (entity instanceof net.minecraft.world.entity.Mob mob) {
            mob.getNavigation().stop();
        }
    }

    private static void applyModifier(LivingEntity entity, Holder<Attribute> attribute,
                                      Identifier modifierId, double amount,
                                      AttributeModifier.Operation operation) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(new AttributeModifier(modifierId, amount, operation));
        }
    }

    private static void removeModifier(LivingEntity entity, Holder<Attribute> attribute,
                                       Identifier modifierId) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(modifierId);
        }
    }

    /** 施法失败：退还蓝量与冷却 */
    private static void failCast(SkillContext ctx, String message) {
        PlayerMagicData data = MagicData.get(ctx.player());
        data.setMana(data.mana() + ctx.skill().manaCost());
        data.setCooldown(ctx.skill().id(), 0L);
        MagicSync.sync(ctx.player());
        NoticeApi.send(ctx.player(), Component.literal(message).withStyle(ChatFormatting.RED));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PlatformMods.MODID, path);
    }
}
