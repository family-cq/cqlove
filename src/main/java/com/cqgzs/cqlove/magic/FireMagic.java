package com.cqgzs.cqlove.magic;

import java.util.ArrayList;
import java.util.List;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import com.cqgzs.cqlove.fx.FxBillboard;
import com.cqgzs.cqlove.fx.FxBlock;
import com.cqgzs.cqlove.fx.FxNet;

/**
 * 火系技能实现（需求文档 5.1.1 火系一节，8 个技能全部落地）。
 *
 * <p>对照文档逐条：</p>
 * <ul>
 *   <li>火滋：灼烧(3) / 焚骨(6+灼烧3s) / 爆裂(9+爆炸+灼烧3s)——从手上放火苗，火焰粒子拖尾</li>
 *   <li>烈拳：轰天(30+点燃方块) / 地煞(3×3 岩浆地面+6s 灼烧 10/s) / 九宫(90，两段爆炸)</li>
 *   <li>天炎葬礼：头上 10 格落 9 个火焰石头，每个 300，多目标分摊、剩余落点炸方块</li>
 *   <li>火神焚天：60 秒火神 buff（属性加成 + 三免疫 + 除水 + 攻击爆炸），结束后再进冷却</li>
 * </ul>
 *
 * <p>性能约定（文档要求少用粒子防卡顿）：拖尾用 {@link MagicDamage#line} 限量粒子线，
 * 地面岩浆用临时 BlockDisplay 图片式贴片，爆炸只做少量粒子 + 音效，不放真 TNT 爆炸（不破坏地形）。</p>
 */
public final class FireMagic {

    /** 火神 buff 的攻击加成属性修饰器 id（挂/卸用同一个 id） */
    private static final Identifier FIRE_GOD_ATTACK =
            Identifier.fromNamespaceAndPath(com.cqgzs.cqlove.PlatformMods.MODID, "fire_god_attack");

    private FireMagic() {
    }

    // ------------------------------------------------------------------
    // 初阶·火滋
    // ------------------------------------------------------------------

    /** 灼烧：火苗到鼠标位置，命中生物 3 点伤害 */
    public static void scorch(SkillContext ctx) {
        boltFx(ctx);
        LivingEntity target = ctx.targetEntity();
        if (target != null) {
            ctx.damage(target, 3.0D);
        }
    }

    /** 焚骨：6 点伤害 + 灼烧 3 秒（每秒 1，原版着火正好 1/s）；水中/雨天灼烧不生效 */
    public static void burnBone(SkillContext ctx) {
        boltFx(ctx);
        LivingEntity target = ctx.targetEntity();
        if (target == null) {
            return;
        }
        ctx.damage(target, 6.0D);
        if (!target.isInWaterOrRain()) {
            target.igniteForSeconds(3.0F);
        }
    }

    /** 爆裂：命中即爆炸，9 点范围伤害 + 灼烧 3 秒；水中/雨天灼烧不生效 */
    public static void burst(SkillContext ctx) {
        boltFx(ctx);
        Vec3 hit = ctx.targetPos();
        explodeFx(ctx.level(), hit);
        areaDamage(ctx, hit, 2.0D, 9.0D);
        LivingEntity target = ctx.targetEntity();
        if (target != null && !target.isInWaterOrRain()) {
            target.igniteForSeconds(3.0F);
        }
    }

    // ------------------------------------------------------------------
    // 中阶·烈拳
    // ------------------------------------------------------------------

    /** 前摇时长（tick）：按下技能键即触发，无需长按 */
    private static final int WINDUP_TICKS = 12;

    /** 轰天：爆炸 30 点范围伤害，并点燃爆炸范围内的方块（水中/雨天不点燃） */
    public static void skySmash(SkillContext ctx) {
        windupThen(ctx, () -> {
            fistFx(ctx);
            Vec3 hit = ctx.targetPos();
            explodeFx(ctx.level(), hit);
            areaDamage(ctx, hit, 3.0D, 30.0D);
            if (!ctx.level().isRainingAt(BlockPos.containing(hit))) {
                igniteGround(ctx.level(), hit, 2);
            }
        });
    }

    /**
     * 地煞：落点生成 3×3 岩浆地表图片，持续 6 秒，范围内生物每秒 10 点灼烧。
     * 水中也生成并附气泡柱粒子；雨天附蒸发气体粒子。
     */
    public static void earthEvil(SkillContext ctx) {
        windupThen(ctx, () -> {
            fistFx(ctx);
            ServerLevel level = ctx.level();
            // 关键：落点可能在空中（打到飞行生物/方块侧面），一律往下找真正的地面再铺岩浆
            Vec3 hit = GroundDecal.groundBelow(level, ctx.targetPos(), 12);
            explodeFx(level, hit);

            // 3×3 岩浆地表图（32×32 透明底、边缘抖动渐隐，和周围方块自然过渡）
            final FxBillboard magma = GroundDecal.spawn(level, hit,
                    com.cqgzs.cqlove.item.MagicItems.MAGMA_PATCH.get(), 3.0F);

            AABB area = new AABB(hit, hit).inflate(1.5D, 0.5D, 1.5D);
            MagicScheduler.every(level, 20, 6 * 20, () -> {
                // 每秒：范围内生物 10 点灼烧
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                    if (entity != ctx.player() && entity.isAlive()) {
                        ctx.damage(entity, 10.0D);
                    }
                }
                // 环境反馈粒子（少量）：水中气泡柱 / 雨天蒸发气体 / 平常岩浆火星
                boolean water = !level.getBlockState(BlockPos.containing(hit)).getFluidState().isEmpty();
                var particle = water ? ParticleTypes.BUBBLE_COLUMN_UP
                        : (level.isRainingAt(BlockPos.containing(hit)) ? ParticleTypes.CLOUD : ParticleTypes.LAVA);
                level.sendParticles(particle, hit.x, hit.y + 0.2D, hit.z, 4, 1.2D, 0.15D, 1.2D, 0.0D);
            }, magma == null ? null : magma::discard);
        });
    }

    /** 落石飞行时长（tick）：石头从头顶砸到目标的过程，够长才看得清 */
    private static final int STONE_FALL_TICKS = 8;

    /**
     * 生成一颗可见的"火焰石头"（岩浆块，放大 1.2 倍、全亮，飞行途中带火焰拖尾）。
     *
     * <p>与其他特效一致：服务端只发描述包，方块实体由客户端自己生成渲染。</p>
     */
    private static @org.jspecify.annotations.Nullable FxBlock createStoneDisplay(ServerLevel level, Vec3 pos) {
        return FxNet.block(level, pos, Blocks.MAGMA_BLOCK.defaultBlockState(),
                1.2F, 1.2F, 1.2F, null, true);
    }

    /** 九宫：90 点爆炸，2 秒后以落点为中心 8 个方位（±2）同时再炸一次 */
    public static void ninePalace(SkillContext ctx) {
        windupThen(ctx, () -> {
            fistFx(ctx);
            ServerLevel level = ctx.level();
            Vec3 hit = ctx.targetPos();
            explodeFx(level, hit);
            areaDamage(ctx, hit, 2.5D, 90.0D);

            MagicScheduler.later(level, 40, () -> {
                int[][] offsets = {{-2, -2}, {0, -2}, {2, -2}, {-2, 0}, {2, 0}, {-2, 2}, {0, 2}, {2, 2}};
                for (int i = 0; i < offsets.length; i++) {
                    int[] offset = offsets[i];
                    Vec3 pos = hit.add(offset[0], 0.0D, offset[1]);
                    // 八宫按顺时针逐格炸开（每格间隔 1 tick），粒子才看得清、也更像"宫位连爆"
                    MagicScheduler.later(level, i, () -> {
                        explodeFxBig(level, pos);
                        areaDamage(ctx, pos, 2.0D, 90.0D);
                    });
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // 高阶·天炎葬礼
    // ------------------------------------------------------------------

    /**
     * 天炎葬礼：从玩家头上 10 格向鼠标位置落 9 个火焰石头，每个 300 点爆炸伤害。
     * 落点附近有多个生物时每只各挨一个，剩余的落在鼠标指的方块位置；1~9 只时按顺序分配。
     */
    public static void skyFireFuneral(SkillContext ctx) {
        windupThen(ctx, () -> skyFireFuneralBurst(ctx));
    }

    /** 天炎葬礼的爆发段：头顶依次砸下 9 颗可见的火焰石头 */
    private static void skyFireFuneralBurst(SkillContext ctx) {
        ServerLevel level = ctx.level();
        Vec3 hit = ctx.targetPos();
        Vec3 spawnBase = ctx.player().position().add(0.0D, 10.0D, 0.0D);
        MagicDamage.playSound(level, ctx.player().position(), SoundEvents.BLAZE_SHOOT, 1.0F, 0.6F);

        // 预先锁定目标列表（最多 9 只，按距离排序）
        List<LivingEntity> targets = new ArrayList<>(
                level.getEntitiesOfClass(LivingEntity.class, new AABB(hit, hit).inflate(6.0D)));
        targets.removeIf(entity -> entity == ctx.player() || !entity.isAlive());
        targets.sort((a, b) -> Double.compare(a.distanceToSqr(hit), b.distanceToSqr(hit)));
        while (targets.size() > 9) {
            targets.remove(targets.size() - 1);
        }

        final int[] stone = {0};
        MagicScheduler.every(level, 3, 9 * 3, () -> {
            int index = stone[0]++;
            // 石头生成点：玩家头顶附近散开一点
            Vec3 from = spawnBase.add((index % 3 - 1) * 1.2D, 0.0D, (index / 3 - 1) * 1.2D);
            LivingEntity target = index < targets.size() && targets.get(index).isAlive() ? targets.get(index) : null;
            launchStone(ctx, from, target, hit);
        }, null);
    }

    /**
     * 发射一颗火焰石头：可见的岩浆块从天而降（带火焰拖尾），落地才结算伤害。
     *
     * @param target   要砸的生物（可为 null，null 时砸 fallback 坐标）
     * @param fallback 没有生物时砸的坐标
     */
    private static void launchStone(SkillContext ctx, Vec3 from, LivingEntity target, Vec3 fallback) {
        ServerLevel level = ctx.level();
        final FxBlock stone = createStoneDisplay(level, from);
        final int[] step = {0};
        MagicScheduler.every(level, 1, STONE_FALL_TICKS, () -> {
            step[0]++;
            Vec3 to = impactPoint(target, fallback);
            double progress = Math.min(1.0D, (double) step[0] / STONE_FALL_TICKS);
            Vec3 pos = from.lerp(to, progress);
            if (stone != null) {
                stone.setPos(pos.x, pos.y, pos.z);
            }
            // 火焰拖尾：石头飞到哪儿烧到哪儿，老远就能看到
            level.sendParticles(ParticleTypes.FLAME, pos.x, pos.y, pos.z, 4, 0.28D, 0.28D, 0.28D, 0.015D);
            level.sendParticles(ParticleTypes.LAVA, pos.x, pos.y, pos.z, 1, 0.12D, 0.12D, 0.12D, 0.0D);
            level.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y + 0.3D, pos.z, 2, 0.2D, 0.2D, 0.2D, 0.01D);
        }, () -> {
            if (stone != null) {
                stone.discard();
            }
            Vec3 impact = impactPoint(target, fallback);
            explodeFx(level, impact);
            level.sendParticles(ParticleTypes.LAVA, impact.x, impact.y, impact.z, 24, 0.5D, 0.4D, 0.5D, 0.15D);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, impact.x, impact.y, impact.z, 8, 0.4D, 0.3D, 0.4D, 0.02D);
            if (target != null && target.isAlive()) {
                ctx.damage(target, 300.0D);
            } else {
                areaDamage(ctx, impact, 1.5D, 300.0D);
            }
        });
    }

    /** 撞击点：生物活着就打它当前位置，否则打固定坐标 */
    private static Vec3 impactPoint(LivingEntity target, Vec3 fallback) {
        if (target != null && target.isAlive()) {
            return target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        }
        return fallback;
    }

    // ------------------------------------------------------------------
    // 超阶·火神焚天
    // ------------------------------------------------------------------

    /**
     * 火神焚天：60 秒火神 buff。
     * 攻击/魔力/蓝量/魔暴伤 +100（魔力、蓝量在 {@link PlayerMagicData} 里算，魔暴伤在 {@link MagicDamage}，
     * 攻击在这里挂属性修饰器）；三免疫与攻击爆炸、除水在 {@link MagicEvents} 的事件里判定。
     * 冷却：文档要求"buff 结束 + 冷却结束"才能再放，所以 buff 到期时才写 60 秒冷却（见 {@link #tickBuff}）。
     */
    public static void fireGod(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        PlayerMagicData data = MagicData.get(player);
        data.startFireGod();
        var attack = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.addOrUpdateTransientModifier(
                    new AttributeModifier(FIRE_GOD_ATTACK, 100.0D, AttributeModifier.Operation.ADD_VALUE));
        }
        // 蓝量上限 +100 立即生效（maxMana 已带 buff 加成），顺便把蓝补满给玩家直观反馈
        data.addMana(100.0D);
        MagicSync.sync(player);
        ServerLevel level = ctx.level();
        level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getY() + 1.0D, player.getZ(),
                30, 0.5D, 1.0D, 0.5D, 0.02D);
        MagicDamage.playSound(level, player.position(), SoundEvents.FIRE_AMBIENT, 1.0F, 0.8F);
        // 脚下红色法阵是客户端渲染层（PlayerFxLayer），由 MagicFxSync 广播开关位，这里不直接碰
    }

    /**
     * 每 tick 驱动火神 buff（MagicEvents.onPlayerTick 调用）：
     * 删除周围 3×3×3 的水方块并放蒸发音效；到期时卸攻击加成、技能进 60 秒冷却。
     */
    public static void tickBuff(ServerPlayer player) {
        PlayerMagicData data = MagicData.get(player);
        if (!data.fireGodActive()) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();

        // 每 10 tick 除一次水，避免每 tick 扫方块
        if (data.fireGodTicks() % 10 == 0) {
            BlockPos center = player.blockPosition();
            boolean removed = false;
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
                if (level.getBlockState(pos).is(Blocks.WATER)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    removed = true;
                }
            }
            if (removed) {
                MagicDamage.playSound(level, player.position(), SoundEvents.FIRE_EXTINGUISH, 0.7F, 1.5F);
                level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.5D, player.getZ(),
                        6, 0.6D, 0.5D, 0.6D, 0.01D);
            }
        }

        // buff 到期收尾
        if (data.tickFireGod()) {
            var attack = player.getAttribute(Attributes.ATTACK_DAMAGE);
            if (attack != null) {
                attack.removeModifier(FIRE_GOD_ATTACK);
            }
            // buff 结束才开始算 60 秒冷却（文档：buff 完 + 冷却完才能再放）
            data.endSuperRing("fire_3_1");
            data.setCooldown("fire_3_1", level.getGameTime() + 60L * 20L);
            MagicSync.sync(player);
        }
    }

    /**
     * 火神 buff 期间：玩家近战命中 / 弓箭雪球鸡蛋等弹射物命中时的爆炸效果（MagicEvents 调用）。
     * 附带 10 点范围魔法伤害（火系色数字，含 PVP 判定）。
     */
    public static void attackExplosion(ServerPlayer player, Vec3 center) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        explodeFx(level, center);
        for (LivingEntity entity : level.getEntitiesOfClass(
                LivingEntity.class, new AABB(center, center).inflate(2.0D))) {
            if (entity != player && entity.isAlive()) {
                MagicDamage.magicDamage(player, entity, 10.0D, MagicElement.FIRE);
            }
        }
    }

    /**
     * "蓄力-炸开"前摇：按下技能键即触发（无需长按），0.6 秒后执行技能本体。
     * 目标点先出现一圈向内收缩、边收边旋转的火环，中心火苗越聚越旺；
     * 收拢瞬间炸开一团岩浆火星 + 爆响，随后技能正式落下。
     */
    private static void windupThen(SkillContext ctx, Runnable effect) {
        ServerLevel level = ctx.level();
        Vec3 center = ctx.targetPos();
        // 起手：一声火焰蓄能（点火的"呼"声）
        MagicDamage.playSound(level, center, SoundEvents.FIRECHARGE_USE, 0.9F, 1.15F);
        final int[] elapsed = {0};
        // 云冰晶界领域内：所有技能施法时间（前摇）-50%
        int windup = IceMagic.castTicks(ctx.player(), WINDUP_TICKS);
        MagicScheduler.every(level, 1, windup, () -> {
            elapsed[0]++;
            double progress = (double) elapsed[0] / windup;
            // 1. 收缩中的旋转火环：半径 2.2 格收到中心
            double radius = 2.2D * (1.0D - progress);
            int points = 9;
            for (int i = 0; i < points; i++) {
                double angle = i * Math.PI * 2.0D / points - progress * 2.6D;
                level.sendParticles(ParticleTypes.FLAME,
                        center.x + Math.cos(angle) * radius, center.y + 0.35D, center.z + Math.sin(angle) * radius,
                        1, 0.02D, 0.02D, 0.02D, 0.01D);
            }
            // 2. 中心火苗升腾，越蓄越旺
            level.sendParticles(ParticleTypes.FLAME, center.x, center.y + 0.3D, center.z,
                    2 + (int) (progress * 7.0D), 0.18D, 0.45D, 0.18D, 0.02D);
            // 3. 底部火星与烟，接近爆发时更浓
            level.sendParticles(ParticleTypes.SMOKE, center.x, center.y + 0.15D, center.z,
                    1 + (int) (progress * 3.0D), 0.25D, 0.2D, 0.25D, 0.01D);
            // 4. 收拢瞬间：岩浆火星炸开 + 爆响，紧接着技能落下
            if (elapsed[0] == windup) {
                level.sendParticles(ParticleTypes.LAVA, center.x, center.y + 0.4D, center.z,
                        18, 0.45D, 0.35D, 0.45D, 0.12D);
                level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.5D, center.z,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
                MagicDamage.playSound(level, center, SoundEvents.GENERIC_EXPLODE.value(), 0.7F, 1.5F);
            }
        }, effect);
    }

    // ------------------------------------------------------------------
    // 公共特效 / 工具
    // ------------------------------------------------------------------

    /** 火苗拖尾（初阶）：少量火焰粒子线 */
    private static void boltFx(SkillContext ctx) {
        MagicDamage.line(ctx.level(), ctx.origin(), ctx.targetPos(), ParticleTypes.FLAME, 0.5D);
        MagicDamage.playSound(ctx.level(), ctx.targetPos(), SoundEvents.FIRE_AMBIENT, 0.6F, 1.2F);
    }

    /** 火焰拳头拖尾（中阶）：稍粗的火焰粒子线 + 发射音 */
    private static void fistFx(SkillContext ctx) {
        MagicDamage.line(ctx.level(), ctx.origin(), ctx.targetPos(), ParticleTypes.FLAME, 0.35D);
        MagicDamage.playSound(ctx.level(), ctx.player().position(), SoundEvents.BLAZE_SHOOT, 0.8F, 1.0F);
    }

    /** 爆炸特效（不破坏地形）：爆炸粒子 + 爆炸音 */
    private static void explodeFx(ServerLevel level, Vec3 center) {
        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.5D, center.z, 3, 0.4D, 0.3D, 0.4D, 0.0D);
        MagicDamage.playSound(level, center, SoundEvents.GENERIC_EXPLODE.value(), 0.9F, 1.0F);
    }

    /**
     * 大火球爆炸特效（不破坏地形）：原版大型爆炸 + 火焰 + 岩浆火星 + 浓烟，用在高阶招式上。
     */
    private static void explodeFxBig(ServerLevel level, Vec3 center) {
        // 原版爆炸发射器：一团完整的大爆炸烟球
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y + 0.6D, center.z,
                1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.6D, center.z,
                6, 0.9D, 0.5D, 0.9D, 0.0D);
        level.sendParticles(ParticleTypes.FLAME, center.x, center.y + 0.5D, center.z,
                24, 0.8D, 0.6D, 0.8D, 0.09D);
        level.sendParticles(ParticleTypes.LAVA, center.x, center.y + 0.4D, center.z,
                14, 0.7D, 0.5D, 0.7D, 0.12D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, center.x, center.y + 0.7D, center.z,
                12, 0.8D, 0.5D, 0.8D, 0.03D);
        MagicDamage.playSound(level, center, SoundEvents.GENERIC_EXPLODE.value(), 1.0F, 0.8F);
    }

    /** 对范围内生物结算一次魔法伤害（含 PVP 判定与魔暴击，伤害数字用火系色） */
    private static void areaDamage(SkillContext ctx, Vec3 center, double radius, double damage) {
        for (LivingEntity entity : ctx.level().getEntitiesOfClass(
                LivingEntity.class, new AABB(center, center).inflate(radius))) {
            if (entity != ctx.player() && entity.isAlive()) {
                ctx.damage(entity, damage);
            }
        }
    }

    /** 点燃落点周围的地面：在固体方块上方的空气块放火（不破坏方块本身） */
    private static void igniteGround(ServerLevel level, Vec3 center, int radius) {
        BlockPos base = BlockPos.containing(center);
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-radius, 0, -radius), base.offset(radius, 1, radius))) {
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }
            BlockPos below = pos.below();
            if (level.getBlockState(below).isSolidRender()) {
                level.setBlock(pos, Blocks.FIRE.defaultBlockState(), 3);
            }
        }
    }
}
