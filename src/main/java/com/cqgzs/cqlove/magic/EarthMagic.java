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
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.lan.PvpApi;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 土系全部 8 个技能（需求文档“土系”段：地波 / 岩障 / 岩魔之瞳 / 角斗场）。
 *
 * <ul>
 *   <li><b>地波</b>（初阶）：
 *     <ul>
 *       <li>挪移：1 秒内向前推进 8 格（不是瞬移，有移动画面），路上撞到生物不停，撞到方块才停。</li>
 *       <li>迟缓：对准生物，附加 10 秒迟缓，并造成 6 点土系魔法伤害。</li>
 *       <li>陷落：对准生物，固定在原地无法移动 5 秒，并造成 9 点土系魔法伤害。</li>
 *     </ul>
 *   </li>
 *   <li><b>岩障</b>（中阶）：
 *     <ul>
 *       <li>嶙石：前摇后地面升起 2×3×2 的小山，碰到生物造成 30 点伤害，3 秒后消失并进入冷却。</li>
 *       <li>山屏：前摇后四根石柱向两侧展开，中间是半透明琥珀光屏，持续 10 秒把生物向外推并造成 60 点伤害；
 *           前摇结束就开始冷却。</li>
 *       <li>石盾：目标周围 4 面灰色石盾，物理伤害 -60%、魔法伤害 -40%，持续 10 秒，可给其他生物使用。</li>
 *     </ul>
 *   </li>
 *   <li><b>岩魔之瞳</b>（高阶）：前摇后石之眼从眼前推出 1 格，前方范围内所有敌对生物石化 ——
 *       30 秒无法移动 + 失明，物理防御 -90%、魔法防御 -60%，30 秒后进入冷却。</li>
 *   <li><b>角斗场</b>（超阶）：锁定一个生物，以玩家为中心用原版石头砌出半径 9、高 4 的围墙，
 *       把其它生物全部推出去；被锁定的生物攻/魔/物防/魔防 -50%，玩家 +20%，
 *       持续 60 秒（击杀目标可提前结束）；石墙永久保留。</li>
 * </ul>
 *
 * <p>减伤 / 增伤接入点：</p>
 * <ul>
 *   <li>石盾 / 石化 / 角斗场 的受伤倍率：{@link #incomingMultiplier}（MagicEvents 里按物理/魔法分别乘）。</li>
 *   <li>角斗场的魔力加成 / 削弱：{@link #casterMagicMultiplier}（MagicDamage 里乘基础伤害）。</li>
 * </ul>
 */
public final class EarthMagic {

    /** 土系代表色（琥珀棕） */
    private static final int EARTH_COLOR = MagicElement.EARTH.color();

    // ---------------- 挪移 ----------------
    /** 挪移总时长（1 秒） */
    private static final int SHIFT_TICKS = 20;
    /** 挪移总距离（8 格） */
    private static final double SHIFT_DISTANCE = 8.0D;

    // ---------------- 迟缓 / 陷落 ----------------
    /** 迟缓持续（10 秒） */
    private static final int SLOW_TICKS = 10 * 20;
    /** 陷落定身持续（5 秒） */
    private static final int SINK_TICKS = 5 * 20;

    // ---------------- 嶙石 ----------------
    /** 嶙石前摇（0.5 秒：地面裂开聚土） */
    private static final int SPIRE_WINDUP = 10;
    /** 嶙石升起动画时长 */
    private static final int SPIRE_RISE = 8;
    /** 嶙石存在时间（3 秒，从完全升起算起） */
    private static final int SPIRE_LIFE = 3 * 20;
    /** 嶙石伤害 */
    private static final double SPIRE_DAMAGE = 30.0D;

    // ---------------- 山屏 ----------------
    /** 山屏前摇 tick */
    private static final int SCREEN_WINDUP = 10;
    /** 山屏石柱向两侧展开的 tick */
    private static final int SCREEN_SPREAD = 8;
    /** 山屏存在时间（10 秒，从展开开始） */
    private static final int SCREEN_TICKS = 10 * 20;
    /** 山屏光屏宽度（格） */
    private static final float SCREEN_WIDTH = 7.0F;
    /** 山屏高度（格） */
    private static final float SCREEN_HEIGHT = 4.0F;
    /** 山屏伤害 */
    private static final double SCREEN_DAMAGE = 60.0D;
    /** 四根石柱展开后的横向偏移 */
    private static final double[] PILLAR_OFFSETS = {-3.8D, -2.3D, 2.3D, 3.8D};

    // ---------------- 石盾 ----------------
    /** 石盾持续（10 秒） */
    private static final int STONE_SHIELD_TICKS = 10 * 20;
    /** 石盾物理减伤（受到物理伤害 ×0.4 = -60%） */
    private static final float SHIELD_PHYSICAL = 0.4F;
    /** 石盾魔法减伤（受到魔法伤害 ×0.6 = -40%） */
    private static final float SHIELD_MAGIC = 0.6F;

    // ---------------- 岩魔之瞳 ----------------
    /** 石眼前摇 */
    private static final int EYE_WINDUP = 15;
    /** 石化持续（30 秒） */
    private static final int EYE_TICKS = 30 * 20;
    /** 石眼作用距离（格，实际取技能范围与它的较小值） */
    /** 扇形距离不再写死：直接用 ctx.range()（初阶 10 / 中阶 15 / 高阶 20 / 超阶 30） */
    /** 前方扇形半角（度） */
    private static final double EYE_CONE_DEGREES = 45.0D;

    // ---------------- 角斗场 ----------------
    /** 围墙半径（格） */
    private static final double ARENA_RADIUS = 9.0D;
    /** 围墙高度（格） */
    private static final int ARENA_HEIGHT = 4;
    /** 角斗场持续（60 秒） */
    private static final int ARENA_TICKS = 60 * 20;

    /** 定身用属性修饰器 id（结束后按 id 精确移除） */
    private static final Identifier SINK_SPEED_ID = id("earth_sink_speed");
    private static final Identifier SINK_JUMP_ID = id("earth_sink_jump");
    private static final Identifier PETRIFY_SPEED_ID = id("earth_petrify_speed");
    private static final Identifier PETRIFY_JUMP_ID = id("earth_petrify_jump");
    private static final Identifier PETRIFY_ARMOR_ID = id("earth_petrify_armor");
    private static final Identifier ARENA_OWNER_ATTACK_ID = id("earth_arena_owner_attack");
    private static final Identifier ARENA_OWNER_ARMOR_ID = id("earth_arena_owner_armor");
    private static final Identifier ARENA_TARGET_ATTACK_ID = id("earth_arena_target_attack");
    private static final Identifier ARENA_TARGET_ARMOR_ID = id("earth_arena_target_armor");

    /** 进行中的挪移 */
    private static final List<Slide> SLIDES = new ArrayList<>();
    /** 陷落定身中的生物 */
    private static final List<Rooted> ROOTS = new ArrayList<>();
    /** 进行中的嶙石 */
    private static final List<Spire> SPIRES = new ArrayList<>();
    /** 进行中的山屏 */
    private static final List<Screen> SCREENS = new ArrayList<>();
    /** 目标 UUID -> 石盾 */
    private static final Map<UUID, StoneShield> SHIELDS = new HashMap<>();
    /** 进行中的岩魔之瞳 */
    private static final List<Eye> EYES = new ArrayList<>();
    /** 石化中的生物 */
    private static final List<Petrified> PETRIFIED = new ArrayList<>();
    /** 进行中的角斗场 */
    private static final List<Arena> ARENAS = new ArrayList<>();

    private EarthMagic() {
    }

    // ==================================================================
    // 初阶·地波：挪移 / 迟缓 / 陷落
    // ==================================================================

    /**
     * 挪移：1 秒内向前推进 8 格。
     *
     * <p>不是瞬移：每 tick 沿水平视线方向平移 0.4 格（客户端有位置插值，画面上是连续滑行）。
     * 沿途的生物不阻挡（不做实体碰撞），只有前方出现方块时才停下。</p>
     */
    public static void shift(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 look = player.getLookAngle();
        Vec3 dir = new Vec3(look.x, 0.0D, look.z);
        if (dir.lengthSqr() < 1.0E-4D) {
            failCast(ctx, "挪移需要面朝一个水平方向");
            return;
        }
        dir = dir.normalize();
        SLIDES.add(new Slide(player, level, dir, SHIFT_DISTANCE, SHIFT_TICKS));

        MagicDamage.playSound(level, player.position(), SoundEvents.GRAVEL_STEP, 1.0F, 1.4F);
        level.sendParticles(earthParticle(), player.getX(), player.getY() + 0.2D, player.getZ(),
                14, 0.4D, 0.15D, 0.4D, 0.02D);
    }

    /** 迟缓：10 秒迟缓 + 6 点土系魔法伤害 */
    public static void slow(SkillContext ctx) {
        ServerLevel level = ctx.level();
        LivingEntity target = ctx.targetEntity();
        if (target == null) {
            failCast(ctx, "迟缓需要对准生物释放");
            return;
        }

        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.45D, 0.0D);
        MagicDamage.line(level, ctx.origin(), hit, earthParticle(), 0.35D);
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOW_TICKS, 1));
        // 脚下缠上一圈泥
        for (int i = 0; i < 18; i++) {
            double angle = i * Math.PI * 2.0D / 18.0D;
            level.sendParticles(ParticleTypes.POOF,
                    target.getX() + Math.cos(angle) * 0.55D, target.getY() + 0.1D,
                    target.getZ() + Math.sin(angle) * 0.55D, 1, 0.0D, 0.05D, 0.0D, 0.0D);
        }
        MagicDamage.playSound(level, hit, SoundEvents.GRAVEL_BREAK, 0.9F, 0.9F);
        ctx.damage(target, 6.0D);
    }

    /** 陷落：把生物固定在原地 5 秒，并造成 9 点土系魔法伤害 */
    public static void sink(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        LivingEntity target = ctx.targetEntity();
        if (target == null) {
            failCast(ctx, "陷落需要对准生物释放");
            return;
        }
        long now = level.getGameTime();

        applyRoot(target, SINK_SPEED_ID, SINK_JUMP_ID);
        ROOTS.add(new Rooted(level, target, now + SINK_TICKS));
        // 下沉的泥土 + 地面裂纹
        Vec3 base = target.position();
        for (int i = 0; i < 26; i++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
            double radius = 0.2D + level.getRandom().nextDouble() * 0.9D;
            level.sendParticles(ParticleTypes.POOF,
                    base.x + Math.cos(angle) * radius, base.y + level.getRandom().nextDouble() * 0.8D,
                    base.z + Math.sin(angle) * radius, 1, 0.0D, -0.02D, 0.0D, 0.01D);
        }
        level.sendParticles(earthParticle(), base.x, base.y + 0.3D, base.z, 18, 0.5D, 0.2D, 0.5D, 0.02D);
        MagicDamage.playSound(level, base, SoundEvents.GRAVEL_BREAK, 1.0F, 0.7F);
        ctx.damage(target, 9.0D);
    }

    // ==================================================================
    // 中阶·岩障：嶙石 / 山屏 / 石盾
    // ==================================================================

    /**
     * 嶙石：前摇（地面裂开聚土）→ 小山从地下升起（2×3×2）→ 碰到的生物 30 点伤害 →
     * 3 秒后沉回地下、技能进入冷却。
     */
    public static void spire(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 ground = GroundDecal.groundBelow(level, ctx.targetPos().add(0.0D, 1.0D, 0.0D), 6);
        Vec3 center = new Vec3(ground.x, ground.y + 1.5D, ground.z);

        // 3 张固定朝向的面片按 60° 交叉（米字形），从任何方向看都是立体的小山，
        // 不再是单张“永远面向相机”的平面图片
        List<FxBillboard> displays = new ArrayList<>(3);
        List<Quaternionf> rotations = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            Quaternionf rotation = DisplayFx.yawRotation(Math.toRadians(i * 60.0D));
            FxBillboard panel = DisplayFx.spawn(level, ground.add(0.0D, 0.15D, 0.0D),
                    MagicItems.EARTH_MOUNTAIN.get(), 2.0F, 0.25F, 2.0F,
                    Display.BillboardConstraints.FIXED, rotation);
            if (panel == null) {
                displays.forEach(FxBillboard::discard);
                failCast(ctx, "嶙石生成失败");
                return;
            }
            displays.add(panel);
            rotations.add(rotation);
        }

        long now = level.getGameTime();
        PlayerMagicData data = MagicData.get(player);
        int cooldownSeconds = ctx.skill().cooldownSeconds(data.rank());
        // 云冰晶界领域内：所有技能施法时间（前摇）-50%
        int windup = IceMagic.castTicks(player, SPIRE_WINDUP);
        // 冷却等小山消失后再算，前摇/存在期间用技能锁定顶住
        data.setCooldown(ctx.skill().id(), 0L);
        int total = windup + SPIRE_RISE + SPIRE_LIFE + 10;
        data.lockSkill(ctx.skill().id(), now + total);

        SPIRES.add(new Spire(ctx.skill().id(), player, level, displays, rotations, ground, center, now,
                now + windup, now + windup + SPIRE_RISE,
                now + windup + SPIRE_RISE + SPIRE_LIFE, cooldownSeconds));

        MagicDamage.playSound(level, ground, SoundEvents.GRAVEL_BREAK, 0.9F, 0.8F);
    }

    /**
     * 山屏：前摇后四根石柱向两侧展开、中间铺开半透明琥珀光屏，持续 10 秒：
     * 把范围内的生物向两侧推出去，并造成 60 点伤害；前摇结束就开始冷却。
     */
    public static void screen(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        flat = flat.normalize();
        Vec3 tangent = new Vec3(-flat.z, 0.0D, flat.x);
        Vec3 ground = GroundDecal.groundBelow(level, player.position().add(flat.scale(3.0D)).add(0.0D, 1.2D, 0.0D), 6);
        Vec3 center = new Vec3(ground.x, ground.y + SCREEN_HEIGHT * 0.5D, ground.z);
        Quaternionf rotation = DisplayFx.yawRotation(Math.atan2(flat.x, flat.z));

        // 光屏：先被压成一根竖线，前摇结束后左右展开
        FxBillboard screenDisplay = DisplayFx.spawn(level, center, MagicItems.EARTH_SCREEN.get(),
                0.12F, SCREEN_HEIGHT, 1.0F, Display.BillboardConstraints.FIXED, rotation);
        if (screenDisplay == null) {
            failCast(ctx, "山屏生成失败");
            return;
        }
        // 四根石柱：起始都叠在中间，展开时向两侧滑开
        List<FxBillboard> pillars = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            FxBillboard pillar = DisplayFx.spawn(level, center, MagicItems.EARTH_PILLAR.get(),
                    0.9F, SCREEN_HEIGHT * 1.03F, 0.9F, Display.BillboardConstraints.FIXED, rotation);
            if (pillar != null) {
                pillars.add(pillar);
            }
        }

        long now = level.getGameTime();
        PlayerMagicData data = MagicData.get(player);
        // 云冰晶界领域内：所有技能施法时间（前摇）-50%
        int windup = IceMagic.castTicks(player, SCREEN_WINDUP);
        SCREENS.add(new Screen(ctx.skill().id(), player, level, screenDisplay, pillars, center, flat, tangent,
                ground, now, now + windup, now + windup + SCREEN_SPREAD,
                now + windup + SCREEN_TICKS, ctx.skill().cooldownSeconds(data.rank())));

        MagicDamage.playSound(level, center, SoundEvents.BASALT_BREAK, 0.9F, 1.1F);
        for (int i = 0; i < 12; i++) {
            level.sendParticles(ParticleTypes.POOF, center.x, center.y - 2.0D + i * 0.35D, center.z,
                    1, 0.06D, 0.0D, 0.06D, 0.0D);
        }
    }

    /**
     * 石盾：按住 Ctrl 释放 = 给自己加盾；否则对视线上的生物加盾（都没对准则施法失败退还蓝量）。
     * 4 面石盾环绕，物理 -60% / 魔法 -40%，持续 10 秒。
     */
    public static void stoneShield(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        LivingEntity target = ctx.ctrlDown() ? player : ctx.targetEntity();
        if (target == null) {
            failCast(ctx, "按住 Ctrl 对自己释放，或将准心对准要保护的生物");
            return;
        }
        if (target != player && target instanceof Player victim && !PvpApi.canHurt(player, victim)) {
            failCast(ctx, "PVP 关闭, 不能给该玩家套石盾");
            return;
        }
        long now = level.getGameTime();

        StoneShield previous = SHIELDS.remove(target.getUUID());
        if (previous != null) {
            previous.clear();
        }
        StoneShield shield = new StoneShield(target, level, now + STONE_SHIELD_TICKS);
        shield.ensureDisplays();
        SHIELDS.put(target.getUUID(), shield);
        shield.tick(now);

        PlayerMagicData data = MagicData.get(player);
        String skillId = ctx.skill().id();
        int cooldownSeconds = ctx.skill().cooldownSeconds(data.rank());
        // 10 秒内既锁定又不可重放；10 秒后由调度器写入完整冷却
        // （先写一份“效果时长”兜底：即使玩家中途换维度导致调度任务失效，也不会出现无冷却连放）
        data.setCooldown(skillId, now + STONE_SHIELD_TICKS);
        data.lockSkill(skillId, now + STONE_SHIELD_TICKS + 2L);
        MagicScheduler.later(level, STONE_SHIELD_TICKS + 2, () -> {
            PlayerMagicData fresh = MagicData.get(player);
            fresh.setCooldown(skillId, level.getGameTime() + cooldownSeconds * 20L);
            MagicSync.sync(player);
        });

        MagicDamage.playSound(level, target.position(), SoundEvents.BASALT_BREAK, 0.8F, 1.3F);
        earthBurst(level, target.position().add(0.0D, 1.0D, 0.0D), 2.0D);
    }

    // ==================================================================
    // 高阶·岩魔之瞳
    // ==================================================================

    /**
     * 岩魔之瞳：前摇（眼前聚能）→ 石之眼向前推出 1 格 → 前方扇形范围内所有敌对生物石化：
     * 30 秒无法移动 + 失明，物理防御 -90%、魔法防御 -60%，30 秒后进入冷却。
     */
    public static void stoneEye(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 origin = player.getEyePosition().add(player.getLookAngle().scale(0.35D));
        Vec3 dir = player.getLookAngle();

        FxBillboard display = DisplayFx.spawn(level, origin, MagicItems.EARTH_EYE.get(),
                1.0F, 1.0F, 1.0F, Display.BillboardConstraints.CENTER);
        if (display == null) {
            failCast(ctx, "岩魔之瞳生成失败");
            return;
        }

        long now = level.getGameTime();
        PlayerMagicData data = MagicData.get(player);
        String skillId = ctx.skill().id();
        int cooldownSeconds = ctx.skill().cooldownSeconds(data.rank());
        // 云冰晶界领域内：所有技能施法时间（前摇）-50%
        int windup = IceMagic.castTicks(player, EYE_WINDUP);
        // 前摇 + 石化期间既锁定又不可重放，30 秒后由调度器写入完整冷却
        data.setCooldown(skillId, now + windup + EYE_TICKS);
        data.lockSkill(skillId, now + windup + EYE_TICKS + 4L);

        EYES.add(new Eye(player, level, display, origin, dir, now, now + windup, now + windup + 5L,
                ctx.range()));
        MagicScheduler.later(level, windup + EYE_TICKS + 4, () -> {
            PlayerMagicData fresh = MagicData.get(player);
            fresh.setCooldown(skillId, level.getGameTime() + cooldownSeconds * 20L);
            MagicSync.sync(player);
        });

        MagicDamage.playSound(level, origin, SoundEvents.BASALT_BREAK, 1.0F, 0.9F);
    }

    // ==================================================================
    // 超阶·角斗场
    // ==================================================================

    /**
     * 角斗场：锁定生物 → 以玩家为中心砌出半径 9、高 4 的石头围墙（永久保留）→
     * 其它生物全部推出去；被锁定的生物攻/物防减半，玩家攻/物防 +20%、
     * 魔攻 +20%、魔防 +20%，持续 60 秒（目标死亡提前结束）；结束后进入冷却。
     */
    public static void arena(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        LivingEntity target = ctx.targetEntity();
        if (target == null) {
            failCast(ctx, "角斗场需要锁定一个生物 (对准生物释放)");
            return;
        }
        if (target instanceof Player victim && !PvpApi.canHurt(player, victim)) {
            failCast(ctx, "PVP 关闭, 不能把玩家锁进角斗场");
            return;
        }

        Vec3 ground = GroundDecal.groundBelow(level, player.position(), 10);
        int baseY = (int) Math.floor(ground.y);
        int placed = buildWall(level, player.blockPosition().getX(), baseY, player.blockPosition().getZ());
        pushIntruders(level, player, target, baseY);

        applyArenaBuffs(player, target);

        long now = level.getGameTime();
        PlayerMagicData data = MagicData.get(player);
        String skillId = ctx.skill().id();
        int cooldownSeconds = ctx.skill().cooldownSeconds(data.rank());
        data.setCooldown(skillId, 0L);
        data.lockSkill(skillId, now + ARENA_TICKS + 4L);
        ARENAS.add(new Arena(skillId, player, level, target, now + ARENA_TICKS, cooldownSeconds));

        MagicDamage.playSound(level, ground, SoundEvents.BASALT_BREAK, 1.0F, 0.6F);
        MagicDamage.playSound(level, ground, SoundEvents.GRAVEL_BREAK, 1.0F, 0.5F);
        for (int i = 0; i < 48; i++) {
            double angle = i * Math.PI * 2.0D / 48.0D;
            level.sendParticles(ParticleTypes.POOF,
                    ground.x + Math.cos(angle) * ARENA_RADIUS, ground.y + 0.2D,
                    ground.z + Math.sin(angle) * ARENA_RADIUS, 2, 0.0D, 0.6D, 0.0D, 0.01D);
        }
        PlatformMods.LOGGER.debug("[cqlove] 角斗场: 半径 {} 围墙放置 {} 个石头方块", ARENA_RADIUS, placed);
    }

    // ==================================================================
    // 对外：受伤 / 增伤倍率（MagicEvents、MagicDamage 调用）
    // ==================================================================

    /** 目标是否带着石盾（物理 ×0.4 / 魔法 ×0.6） */
    public static boolean hasStoneShield(LivingEntity entity) {
        StoneShield shield = SHIELDS.get(entity.getUUID());
        return shield != null && shield.owner == entity
                && entity.level().getGameTime() < shield.endTick;
    }

    /** 目标是否处于石化（受伤增加） */
    public static boolean isPetrified(LivingEntity entity) {
        for (Petrified petrified : PETRIFIED) {
            if (petrified.entity == entity) {
                return true;
            }
        }
        return false;
    }

    /** 玩家是否是某个角斗场的场主（攻击/魔力 +20%） */
    public static boolean isArenaOwner(LivingEntity entity) {
        for (Arena arena : ARENAS) {
            if (arena.owner == entity) {
                return true;
            }
        }
        return false;
    }

    /** 生物是否被锁在角斗场里（攻/魔/物防/魔防 -50%） */
    public static boolean isArenaLocked(LivingEntity entity) {
        for (Arena arena : ARENAS) {
            if (arena.target == entity) {
                return true;
            }
        }
        return false;
    }

    /**
     * 施法者的魔法伤害倍率：角斗场场主 +20%（魔力），被锁定的生物 -50%（魔力）。
     */
    public static float casterMagicMultiplier(LivingEntity caster) {
        if (isArenaOwner(caster)) {
            return 1.2F;
        }
        if (isArenaLocked(caster)) {
            return 0.5F;
        }
        return 1.0F;
    }

    /**
     * 受击者的受伤倍率（物理 / 魔法分别算）。
     *
     * <p>石盾：物理 ×0.4、魔法 ×0.6；石化：物理 ×1.9、魔法 ×1.6（物防 -90% / 魔防 -60%）；
     * 角斗场锁定：×1.5；场主魔法防御 +20%：魔法 ×0.85。</p>
     */
    public static float incomingMultiplier(LivingEntity target, boolean magic) {
        float multiplier = 1.0F;
        if (hasStoneShield(target)) {
            multiplier *= magic ? SHIELD_MAGIC : SHIELD_PHYSICAL;
        }
        if (isPetrified(target)) {
            multiplier *= magic ? 1.6F : 1.9F;
        }
        if (isArenaLocked(target)) {
            multiplier *= 1.5F;
        } else if (magic && isArenaOwner(target)) {
            multiplier *= 0.85F;
        }
        return multiplier;
    }

    // ==================================================================
    // 每 tick 驱动
    // ==================================================================

    /** 服务端每 tick（MagicEvents.onServerTick 调用） */
    public static void tickWorld() {
        tickSlides();
        tickRoots();
        tickSpires();
        tickScreens();
        tickShields();
        tickEyes();
        tickPetrified();
        tickArenas();
    }

    /**
     * 挪移：每 tick 平移剩余距离 / 剩余 tick 数（收尾自然减速）。
     * 只撞方块停，不理会生物。
     */
    private static void tickSlides() {
        for (int i = SLIDES.size() - 1; i >= 0; i--) {
            Slide slide = SLIDES.get(i);
            ServerPlayer player = slide.player;
            ServerLevel level = slide.level;
            if (player.isRemoved() || !player.isAlive() || player.level() != level || slide.ticksLeft <= 0) {
                SLIDES.remove(i);
                continue;
            }
            double step = slide.remaining / slide.ticksLeft;
            Vec3 from = player.position();
            Vec3 next = new Vec3(from.x + slide.dir.x * step, from.y, from.z + slide.dir.z * step);
            // 先用射线查前方方块，再看落点是否站得下人（脚 + 头两格）
            HitResult hit = level.clip(new ClipContext(from.add(0.0D, 0.35D, 0.0D),
                    next.add(0.0D, 0.35D, 0.0D), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.MISS || !isFree(level, next)) {
                // 撞墙：抖一层土，停下
                level.sendParticles(ParticleTypes.POOF, next.x, next.y + 0.4D, next.z, 10, 0.3D, 0.3D, 0.3D, 0.02D);
                MagicDamage.playSound(level, next, SoundEvents.GRAVEL_BREAK, 0.7F, 1.2F);
                SLIDES.remove(i);
                continue;
            }

            player.teleportTo(next.x, next.y, next.z);
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
            player.hurtMarked = true;

            slide.remaining -= step;
            slide.ticksLeft--;
            // 拖尾：脚下的土尘 + 琥珀光点
            level.sendParticles(ParticleTypes.POOF, next.x, next.y + 0.1D, next.z, 3, 0.25D, 0.05D, 0.25D, 0.01D);
            level.sendParticles(earthParticle(), next.x, next.y + 0.5D, next.z, 2, 0.2D, 0.25D, 0.2D, 0.01D);
            if (slide.remaining <= 0.05D) {
                SLIDES.remove(i);
            }
        }
    }

    /** 陷落：每 tick 把目标按在原地（属性 + 位移清零），到期解除 */
    private static void tickRoots() {
        for (int i = ROOTS.size() - 1; i >= 0; i--) {
            Rooted root = ROOTS.get(i);
            LivingEntity entity = root.entity;
            if (entity.isRemoved() || !entity.isAlive()) {
                removeModifier(entity, Attributes.MOVEMENT_SPEED, SINK_SPEED_ID);
                removeModifier(entity, Attributes.JUMP_STRENGTH, SINK_JUMP_ID);
                ROOTS.remove(i);
                continue;
            }
            long now = root.level.getGameTime();
            if (now >= root.endTick) {
                removeModifier(entity, Attributes.MOVEMENT_SPEED, SINK_SPEED_ID);
                removeModifier(entity, Attributes.JUMP_STRENGTH, SINK_JUMP_ID);
                ROOTS.remove(i);
                continue;
            }
            holdStill(entity);
            if (now % 6L == 0L) {
                root.level.sendParticles(ParticleTypes.POOF,
                        entity.getX(), entity.getY() + 0.1D, entity.getZ(), 2, 0.3D, 0.05D, 0.3D, 0.01D);
            }
        }
    }

    /** 嶙石：前摇 → 升起 → 造成伤害 → 3 秒后沉回地下并开始冷却 */
    private static void tickSpires() {
        for (int i = SPIRES.size() - 1; i >= 0; i--) {
            Spire spire = SPIRES.get(i);
            ServerLevel level = spire.level;
            long now = level.getGameTime();
            if (spire.owner.isRemoved() || !spire.owner.isAlive() || !spire.displays.get(0).isAlive()) {
                spire.discardAll();
                SPIRES.remove(i);
                continue;
            }

            if (now < spire.riseStart) {
                // 前摇：地面裂开、土屑向中心汇聚
                // 用实际前摇长度（可能已被云冰晶界减半）算进度，避免动画与时序错拍
                double progress = (now - spire.start + 1.0D) / (spire.riseStart - spire.start);
                level.sendParticles(ParticleTypes.POOF,
                        spire.ground.x, spire.ground.y + 0.2D, spire.ground.z,
                        4, 0.9D - progress * 0.5D, 0.1D, 0.9D - progress * 0.5D, 0.01D);
                if (now % 3L == 0L) {
                    level.sendParticles(earthParticle(),
                            spire.ground.x, spire.ground.y + 0.35D, spire.ground.z,
                            3, 0.7D, 0.1D, 0.7D, 0.02D);
                }
                continue;
            }

            if (!spire.risen) {
                // 升起：3 张面片一起从地面长到 3 格高（客户端插值平滑）
                spire.risen = true;
                for (int p = 0; p < spire.displays.size(); p++) {
                    DisplayFx.reshape(spire.displays.get(p), level,
                            new ItemStack(MagicItems.EARTH_MOUNTAIN.get()),
                            2.0F, 3.0F, 2.0F, spire.rotations.get(p), SPIRE_RISE);
                    spire.displays.get(p).setPos(spire.center.x, spire.center.y, spire.center.z);
                }
                MagicDamage.playSound(level, spire.ground, SoundEvents.GRAVEL_BREAK, 1.0F, 0.6F);
                level.sendParticles(ParticleTypes.POOF, spire.ground.x, spire.ground.y + 0.4D, spire.ground.z,
                        30, 1.1D, 0.3D, 1.1D, 0.03D);
            }

            // 碰到就掉 30 点（同一只生物只结算一次），并向外顶开一点
            AABB area = new AABB(spire.center.x - 1.6D, spire.ground.y, spire.center.z - 1.6D,
                    spire.center.x + 1.6D, spire.ground.y + 3.1D, spire.center.z + 1.6D);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                if (entity == spire.owner || !entity.isAlive() || entity.isSpectator()) {
                    continue;
                }
                if (!spire.hit.add(entity.getUUID())) {
                    continue;
                }
                MagicDamage.magicDamage(spire.owner, entity, SPIRE_DAMAGE, MagicElement.EARTH);
                Vec3 push = entity.position().subtract(spire.center).multiply(1.0D, 0.0D, 1.0D);
                if (push.lengthSqr() > 1.0E-3D) {
                    push = push.normalize().scale(0.45D);
                    entity.setDeltaMovement(entity.getDeltaMovement().add(push.x, 0.35D, push.z));
                    entity.hurtMarked = true;
                }
            }

            if (now >= spire.endTick) {
                // 沉回地下：压扁 → 移除，然后开始冷却
                for (int p = 0; p < spire.displays.size(); p++) {
                    DisplayFx.reshape(spire.displays.get(p), level,
                            new ItemStack(MagicItems.EARTH_MOUNTAIN.get()),
                            2.0F, 0.15F, 2.0F, spire.rotations.get(p), 6);
                }
                List<FxBillboard> dying = new ArrayList<>(spire.displays);
                ServerLevel dyingLevel = level;
                Vec3 dyingPos = spire.center;
                MagicScheduler.later(level, 8, () -> {
                    dying.forEach(FxBillboard::discard);
                    dyingLevel.sendParticles(ParticleTypes.POOF, dyingPos.x, dyingPos.y - 1.4D, dyingPos.z,
                            20, 1.0D, 0.2D, 1.0D, 0.02D);
                });
                PlayerMagicData data = MagicData.get(spire.owner);
                data.setCooldown(spire.skillId, now + spire.cooldownSeconds * 20L);
                MagicSync.sync(spire.owner);
                SPIRES.remove(i);
            }
        }
    }

    /** 山屏：前摇（竖线）→ 石柱向两侧展开 + 光屏铺开 → 10 秒推人 + 伤害 */
    private static void tickScreens() {
        for (int i = SCREENS.size() - 1; i >= 0; i--) {
            Screen screen = SCREENS.get(i);
            ServerLevel level = screen.level;
            long now = level.getGameTime();
            if (screen.owner.isRemoved() || !screen.owner.isAlive() || !screen.display.isAlive()) {
                screen.clear();
                SCREENS.remove(i);
                continue;
            }

            if (!screen.spread && now >= screen.spreadStart) {
                screen.spread = true;
                DisplayFx.reshape(screen.display, level, new ItemStack(MagicItems.EARTH_SCREEN.get()),
                        SCREEN_WIDTH, SCREEN_HEIGHT, 1.0F, screen.rotation(), SCREEN_SPREAD);
                screen.display.setPos(screen.center.x, screen.center.y, screen.center.z);
                MagicDamage.playSound(level, screen.center, SoundEvents.BASALT_BREAK, 1.0F, 0.8F);
                for (int k = 0; k < 20; k++) {
                    level.sendParticles(ParticleTypes.POOF,
                            screen.center.x, screen.center.y - 2.0D + k * 0.2D, screen.center.z,
                            2, 0.2D, 0.0D, 0.2D, 0.0D);
                }
                // 前摇结束就开始冷却（需求：前摇动画完后进入技能冷却）
                PlayerMagicData data = MagicData.get(screen.owner);
                data.setCooldown(screen.skillId, now + screen.cooldownSeconds * 20L);
                MagicSync.sync(screen.owner);
            }

            if (screen.spread) {
                // 石柱滑到两侧目标偏移
                for (int p = 0; p < screen.pillars.size(); p++) {
                    FxBillboard pillar = screen.pillars.get(p);
                    if (!pillar.isAlive()) {
                        continue;
                    }
                    double target = PILLAR_OFFSETS[Math.min(p, PILLAR_OFFSETS.length - 1)];
                    Vec3 current = pillar.position();
                    Vec3 goal = new Vec3(screen.center.x + screen.tangent.x * target, screen.center.y,
                            screen.center.z + screen.tangent.z * target);
                    Vec3 next = current.add(goal.subtract(current).scale(0.45D));
                    pillar.setPos(next.x, next.y, next.z);
                }
                pushAndDamage(screen, now);
                if (now % 4L == 0L) {
                    level.sendParticles(earthParticle(), screen.center.x, screen.center.y, screen.center.z,
                            4, SCREEN_WIDTH * 0.5D, SCREEN_HEIGHT * 0.45D, 0.35D, 0.01D);
                }
            } else {
                // 前摇：竖线在地面上"划"出来
                level.sendParticles(ParticleTypes.POOF,
                        screen.center.x, screen.center.y - 2.0D + (now % 8L) * 0.5D, screen.center.z,
                        2, 0.05D, 0.0D, 0.05D, 0.0D);
            }

            if (now >= screen.endTick) {
                screen.clear();
                SCREENS.remove(i);
            }
        }
    }

    /** 山屏：把范围内的生物沿墙法线向两侧推出去，并结算一次 60 点伤害 */
    private static void pushAndDamage(Screen screen, long now) {
        ServerLevel level = screen.level;
        AABB area = new AABB(screen.center.x - 4.6D, screen.ground.y, screen.center.z - 4.6D,
                screen.center.x + 4.6D, screen.ground.y + SCREEN_HEIGHT + 0.6D, screen.center.z + 4.6D);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (entity == screen.owner || !entity.isAlive() || entity.isSpectator()) {
                continue;
            }
            // 只推墙面前后 1.6 格内的
            Vec3 offset = entity.position().subtract(screen.center);
            double alongWall = Math.abs(offset.x * screen.tangent.x + offset.z * screen.tangent.z);
            double acrossWall = offset.x * screen.flat.x + offset.z * screen.flat.z;
            if (alongWall > 4.2D || Math.abs(acrossWall) > 1.6D) {
                continue;
            }
            double side = acrossWall >= 0.0D ? 1.0D : -1.0D;
            entity.setDeltaMovement(entity.getDeltaMovement().add(
                    screen.flat.x * side * 0.85D, 0.32D, screen.flat.z * side * 0.85D));
            entity.hurtMarked = true;
            if (screen.hit.add(entity.getUUID())) {
                MagicDamage.magicDamage(screen.owner, entity, SCREEN_DAMAGE, MagicElement.EARTH);
            }
            level.sendParticles(ParticleTypes.POOF,
                    entity.getX(), entity.getY() + 0.4D, entity.getZ(), 3, 0.3D, 0.3D, 0.3D, 0.01D);
        }
        if (now % 10L == 0L) {
            MagicDamage.playSound(level, screen.center, SoundEvents.GRAVEL_STEP, 0.5F, 1.4F);
        }
    }

    /** 石盾：4 面盾绕目标旋转 */
    private static void tickShields() {
        if (SHIELDS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, StoneShield>> iterator = SHIELDS.entrySet().iterator();
        while (iterator.hasNext()) {
            StoneShield shield = iterator.next().getValue();
            if (shield.owner.isRemoved() || !shield.owner.isAlive()) {
                shield.clear();
                iterator.remove();
                continue;
            }
            long now = shield.level.getGameTime();
            if (now >= shield.endTick) {
                shield.clear();
                iterator.remove();
                continue;
            }
            shield.tick(now);
        }
    }

    /** 岩魔之瞳：前摇聚能 → 石眼前推 1 格 → 石化扇形范围内的敌对生物 */
    private static void tickEyes() {
        for (int i = EYES.size() - 1; i >= 0; i--) {
            Eye eye = EYES.get(i);
            ServerLevel level = eye.level;
            long now = level.getGameTime();
            if (eye.owner.isRemoved() || !eye.owner.isAlive() || !eye.display.isAlive()) {
                eye.display.discard();
                EYES.remove(i);
                continue;
            }

            if (now < eye.fireTick) {
                // 前摇：眼睛位置聚能（向内收拢的粒子 + 琥珀光环）
                // 用实际前摇长度（可能已被云冰晶界减半）算进度
                double t = (now - eye.start + 1.0D) / (eye.fireTick - eye.start);
                for (int k = 0; k < 3; k++) {
                    double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
                    double r = 1.6D * (1.0D - t * 0.6D);
                    level.sendParticles(earthParticle(),
                            eye.origin.x + Math.cos(angle) * r, eye.origin.y + Math.sin(angle) * r * 0.7D,
                            eye.origin.z + Math.sin(angle * 1.7D) * r, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
                // 眼睛轻微前探
                double push = 0.35D - 0.15D * t;
                eye.display.setPos(eye.origin.x + eye.dir.x * push, eye.origin.y - 0.1D,
                        eye.origin.z + eye.dir.z * push);
                continue;
            }

            if (!eye.fired) {
                eye.fired = true;
                petrifyCone(eye);
                MagicDamage.playSound(level, eye.origin, SoundEvents.BASALT_BREAK, 1.0F, 0.5F);
                level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFD08A40),
                        eye.origin.x, eye.origin.y, eye.origin.z, 0, 0.0D, 0.0D, 0.0D, 0.0D);
            }

            // 石眼向前推出 1 格（插值）
            if (now <= eye.moveEnd) {
                double t = Math.min(1.0D, (now - eye.fireTick + 1.0D) / 5.0D);
                double dist = t;
                eye.display.setPos(eye.origin.x + eye.dir.x * dist, eye.origin.y - 0.1D,
                        eye.origin.z + eye.dir.z * dist);
            }
            if (now >= eye.moveEnd + 35L) {
                eye.display.discard();
                EYES.remove(i);
            }
        }
    }

    /** 石化：前方扇形内所有敌对生物（含玩家 10 秒内攻击过的生物） */
    private static void petrifyCone(Eye eye) {
        ServerLevel level = eye.level;
        // 直接取施法时的阶级范围，不再用 20/32 格硬上限把高阶/超阶压回去
        double range = eye.range;
        AABB area = new AABB(eye.origin, eye.origin).inflate(range);
        double cosLimit = Math.cos(Math.toRadians(EYE_CONE_DEGREES));
        long now = level.getGameTime();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (entity == eye.owner || !entity.isAlive() || entity.isSpectator()) {
                continue;
            }
            // PVP 感知：PVP 开启时其他玩家也算合法石化目标
            if (!CombatTracker.isEnemyTarget(eye.owner, entity)) {
                continue;
            }
            Vec3 delta = entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D).subtract(eye.origin);
            double distance = delta.length();
            if (distance < 0.1D || distance > range) {
                continue;
            }
            if (delta.normalize().dot(eye.dir) < cosLimit) {
                continue;
            }
            petrify(entity, level, now);
        }
    }

    private static void petrify(LivingEntity entity, ServerLevel level, long now) {
        applyModifier(entity, Attributes.MOVEMENT_SPEED, PETRIFY_SPEED_ID, -1.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        applyModifier(entity, Attributes.JUMP_STRENGTH, PETRIFY_JUMP_ID, -10.0D,
                AttributeModifier.Operation.ADD_VALUE);
        applyModifier(entity, Attributes.ARMOR, PETRIFY_ARMOR_ID, -0.9D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, EYE_TICKS, 0));
        entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, EYE_TICKS, 6));
        PETRIFIED.add(new Petrified(level, entity, now + EYE_TICKS));
        holdStill(entity);
        // 石化瞬间：灰白石灰从脚下往上裹
        level.sendParticles(ParticleTypes.POOF,
                entity.getX(), entity.getY() + 0.3D, entity.getZ(),
                20, 0.4D, 0.6D, 0.4D, 0.02D);
        level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFF8C8C8C),
                entity.getX(), entity.getY() + entity.getBbHeight() * 0.6D, entity.getZ(),
                16, 0.35D, 0.5D, 0.35D, 0.02D);
    }

    /** 石化中的生物：每 tick 按住、定时冒石屑，到期解除 */
    private static void tickPetrified() {
        for (int i = PETRIFIED.size() - 1; i >= 0; i--) {
            Petrified petrified = PETRIFIED.get(i);
            LivingEntity entity = petrified.entity;
            if (entity.isRemoved() || !entity.isAlive()) {
                releasePetrify(entity);
                PETRIFIED.remove(i);
                continue;
            }
            long now = petrified.level.getGameTime();
            if (now >= petrified.endTick) {
                releasePetrify(entity);
                PETRIFIED.remove(i);
                continue;
            }
            holdStill(entity);
            if (now % 5L == 0L) {
                petrified.level.sendParticles(earthParticle(),
                        entity.getX(), entity.getY() + entity.getBbHeight() * 0.5D, entity.getZ(),
                        2, 0.3D, 0.4D, 0.3D, 0.01D);
            }
        }
    }

    private static void releasePetrify(LivingEntity entity) {
        removeModifier(entity, Attributes.MOVEMENT_SPEED, PETRIFY_SPEED_ID);
        removeModifier(entity, Attributes.JUMP_STRENGTH, PETRIFY_JUMP_ID);
        removeModifier(entity, Attributes.ARMOR, PETRIFY_ARMOR_ID);
    }

    /** 角斗场：维护围墙内的 buff（目标死亡或到期就收掉，围墙保留） */
    private static void tickArenas() {
        for (int i = ARENAS.size() - 1; i >= 0; i--) {
            Arena arena = ARENAS.get(i);
            ServerLevel level = arena.level;
            long now = level.getGameTime();
            boolean targetGone = arena.target.isRemoved() || !arena.target.isAlive();
            if (arena.owner.isRemoved() || !arena.owner.isAlive() || targetGone || now >= arena.endTick) {
                clearArena(arena, targetGone && !arena.owner.isRemoved() && arena.owner.isAlive());
                ARENAS.remove(i);
                continue;
            }
            if (now % 10L == 0L) {
                Vec3 center = arena.owner.position();
                for (int k = 0; k < 24; k++) {
                    double angle = k * Math.PI * 2.0D / 24.0D + now * 0.02D;
                    level.sendParticles(earthParticle(),
                            center.x + Math.cos(angle) * (ARENA_RADIUS - 0.6D), center.y + 0.15D,
                            center.z + Math.sin(angle) * (ARENA_RADIUS - 0.6D), 1, 0.0D, 0.02D, 0.0D, 0.0D);
                }
            }
        }
    }

    /** 结束角斗场：撤掉玩家与目标的属性修饰符，并开始冷却（围墙不拆） */
    private static void clearArena(Arena arena, boolean targetKilled) {
        removeModifier(arena.owner, Attributes.ATTACK_DAMAGE, ARENA_OWNER_ATTACK_ID);
        removeModifier(arena.owner, Attributes.ARMOR, ARENA_OWNER_ARMOR_ID);
        removeModifier(arena.target, Attributes.ATTACK_DAMAGE, ARENA_TARGET_ATTACK_ID);
        removeModifier(arena.target, Attributes.ARMOR, ARENA_TARGET_ARMOR_ID);
        PlayerMagicData data = MagicData.get(arena.owner);
        data.endSuperRing(arena.skillId);
        data.setCooldown(arena.skillId, arena.level.getGameTime() + arena.cooldownSeconds * 20L);
        MagicSync.sync(arena.owner);
        if (targetKilled) {
            MagicDamage.playSound(arena.level, arena.owner.position(), SoundEvents.BASALT_BREAK, 0.8F, 1.4F);
        }
    }

    // ==================================================================
    // 清理（下线 / 死亡 / 关服）
    // ==================================================================

    public static void removePlayer(ServerPlayer player) {
        SLIDES.removeIf(slide -> slide.player == player);
        for (int i = SPIRES.size() - 1; i >= 0; i--) {
            if (SPIRES.get(i).owner == player) {
                SPIRES.get(i).discardAll();
                SPIRES.remove(i);
            }
        }
        for (int i = SCREENS.size() - 1; i >= 0; i--) {
            if (SCREENS.get(i).owner == player) {
                SCREENS.get(i).clear();
                SCREENS.remove(i);
            }
        }
        for (int i = EYES.size() - 1; i >= 0; i--) {
            if (EYES.get(i).owner == player) {
                EYES.get(i).display.discard();
                EYES.remove(i);
            }
        }
        for (int i = ARENAS.size() - 1; i >= 0; i--) {
            Arena arena = ARENAS.get(i);
            if (arena.owner == player) {
                clearArena(arena, false);
                ARENAS.remove(i);
            }
        }
        StoneShield shield = SHIELDS.remove(player.getUUID());
        if (shield != null) {
            shield.clear();
        }
    }

    public static void clearAll() {
        SLIDES.clear();
        for (Rooted root : ROOTS) {
            removeModifier(root.entity, Attributes.MOVEMENT_SPEED, SINK_SPEED_ID);
            removeModifier(root.entity, Attributes.JUMP_STRENGTH, SINK_JUMP_ID);
        }
        ROOTS.clear();
        SPIRES.forEach(Spire::discardAll);
        SPIRES.clear();
        SCREENS.forEach(Screen::clear);
        SCREENS.clear();
        SHIELDS.values().forEach(StoneShield::clear);
        SHIELDS.clear();
        EYES.forEach(eye -> eye.display.discard());
        EYES.clear();
        for (Petrified petrified : PETRIFIED) {
            releasePetrify(petrified.entity);
        }
        PETRIFIED.clear();
        for (Arena arena : ARENAS) {
            clearArena(arena, false);
        }
        ARENAS.clear();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 琥珀色土系粒子 */
    private static ParticleOptions earthParticle() {
        return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, EARTH_COLOR);
    }

    /** 施法点向外炸开的一圈土光（纯视觉） */
    private static void earthBurst(ServerLevel level, Vec3 center, double radius) {
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI * 2.0D / 16.0D;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            MagicDamage.line(level, center, center.add(dx * radius, 0.1D, dz * radius), earthParticle(), 0.5D);
        }
        level.sendParticles(ParticleTypes.POOF, center.x, center.y, center.z, 18, radius * 0.5D, 0.3D, radius * 0.5D, 0.02D);
    }

    /** 按住：清位移 + 停止寻路（属性已经把速度压到 0，这层对付 AI 强行迈步） */
    private static void holdStill(LivingEntity entity) {
        Vec3 motion = entity.getDeltaMovement();
        entity.setDeltaMovement(0.0D, Math.min(0.0D, motion.y), 0.0D);
        entity.hurtMarked = true;
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
        }
    }

    /** 定身属性（速度 -100%、跳跃 -10） */
    private static void applyRoot(LivingEntity entity, Identifier speedId, Identifier jumpId) {
        applyModifier(entity, Attributes.MOVEMENT_SPEED, speedId, -1.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        applyModifier(entity, Attributes.JUMP_STRENGTH, jumpId, -10.0D,
                AttributeModifier.Operation.ADD_VALUE);
        holdStill(entity);
    }

    private static void applyModifier(LivingEntity entity, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                      Identifier modifierId, double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(new AttributeModifier(modifierId, amount, operation));
        }
    }

    private static void removeModifier(LivingEntity entity, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                       Identifier modifierId) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(modifierId);
        }
    }

    /** 落点是否能站人（脚与头两格都不是实体方块） */
    private static boolean isFree(ServerLevel level, Vec3 feet) {
        BlockPos pos = BlockPos.containing(feet);
        return !level.getBlockState(pos).isSolidRender()
                && !level.getBlockState(pos.above()).isSolidRender();
    }

    /** 施法失败：退还蓝量与冷却 */
    private static void failCast(SkillContext ctx, String message) {
        PlayerMagicData data = MagicData.get(ctx.player());
        data.setMana(data.mana() + ctx.skill().manaCost());
        data.setCooldown(ctx.skill().id(), 0L);
        MagicSync.sync(ctx.player());
        NoticeApi.send(ctx.player(), net.minecraft.network.chat.Component.literal(message)
                .withStyle(net.minecraft.ChatFormatting.RED));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PlatformMods.MODID, path);
    }

    // ==================================================================
    // 角斗场围墙
    // ==================================================================

    /**
     * 砌围墙：以 (centerX, centerZ) 为圆心，半径 {@link #ARENA_RADIUS} 的一圈（约 1.2 格厚）、
     * 高 {@link #ARENA_HEIGHT} 格，全部用原版石头；只填可替换的方块，不动山体/建筑。
     *
     * @return 实际放置的方块数
     */
    private static int buildWall(ServerLevel level, int centerX, int baseY, int centerZ) {
        int radius = (int) Math.ceil(ARENA_RADIUS);
        int placed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < ARENA_RADIUS - 0.8D || distance > ARENA_RADIUS + 0.5D) {
                    continue;
                }
                int x = centerX + dx;
                int z = centerZ + dz;
                // 每根柱子各自找地面，围墙跟着地形起伏（避免坡上出现缺口）
                Vec3 ground = GroundDecal.groundBelow(level, new Vec3(x + 0.5D, baseY + 3.0D, z + 0.5D), 8);
                int columnY = (int) Math.floor(ground.y);
                for (int dy = 0; dy < ARENA_HEIGHT; dy++) {
                    BlockPos pos = new BlockPos(x, columnY + dy, z);
                    if (!level.isInWorldBounds(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isSolidRender() || !state.canBeReplaced()) {
                        continue;
                    }
                    level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    placed++;
                }
            }
        }
        return placed;
    }

    /** 把围墙内的其它生物全部推到墙外（被锁定的目标留在场内） */
    private static void pushIntruders(ServerLevel level, ServerPlayer owner, LivingEntity locked, int baseY) {
        AABB area = new AABB(owner.position(), owner.position()).inflate(ARENA_RADIUS + 3.0D, 6.0D, ARENA_RADIUS + 3.0D);
        Vec3 center = owner.position();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (entity == owner || entity == locked || !entity.isAlive() || entity.isSpectator()) {
                continue;
            }
            Vec3 offset = entity.position().subtract(center);
            if (offset.length() > ARENA_RADIUS + 1.0D) {
                continue;
            }
            Vec3 out = new Vec3(offset.x, 0.0D, offset.z);
            if (out.lengthSqr() < 1.0E-3D) {
                out = new Vec3(1.0D, 0.0D, 0.0D);
            }
            out = out.normalize().scale(ARENA_RADIUS + 1.8D);
            Vec3 landing = GroundDecal.groundBelow(level,
                    new Vec3(center.x + out.x, baseY + 1.0D, center.z + out.z), 6);
            entity.teleportTo(landing.x, landing.y, landing.z);
            entity.setDeltaMovement(Vec3.ZERO);
            entity.resetFallDistance();
            entity.hurtMarked = true;
            level.sendParticles(ParticleTypes.POOF, landing.x, landing.y + 0.4D, landing.z,
                    8, 0.4D, 0.3D, 0.4D, 0.02D);
        }
    }

    /** 角斗场 buff：玩家攻/物防 +20%，目标攻/物防 -50%（魔力在 MagicDamage 里按倍率算） */
    private static void applyArenaBuffs(ServerPlayer owner, LivingEntity target) {
        applyModifier(owner, Attributes.ATTACK_DAMAGE, ARENA_OWNER_ATTACK_ID, 0.2D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        applyModifier(owner, Attributes.ARMOR, ARENA_OWNER_ARMOR_ID, 0.2D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        applyModifier(target, Attributes.ATTACK_DAMAGE, ARENA_TARGET_ATTACK_ID, -0.5D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        applyModifier(target, Attributes.ARMOR, ARENA_TARGET_ARMOR_ID, -0.5D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    // ==================================================================
    // 数据结构
    // ==================================================================

    /** 一次挪移（可变状态，用类不用 record） */
    private static final class Slide {
        final ServerPlayer player;
        final ServerLevel level;
        final Vec3 dir;
        double remaining;
        int ticksLeft;

        Slide(ServerPlayer player, ServerLevel level, Vec3 dir, double remaining, int ticksLeft) {
            this.player = player;
            this.level = level;
            this.dir = dir;
            this.remaining = remaining;
            this.ticksLeft = ticksLeft;
        }
    }

    /** 陷落定身 */
    private record Rooted(ServerLevel level, LivingEntity entity, long endTick) {
    }

    /** 嶙石 */
    private static final class Spire {
        final String skillId;
        final ServerPlayer owner;
        final ServerLevel level;
        /** 3 张 60° 交叉的面片（伪立体） */
        final List<FxBillboard> displays;
        /** 每张面片的基础朝向（reshape 时必须原样传回，否则面片朝向会被重置） */
        final List<Quaternionf> rotations;
        final Vec3 ground;
        final Vec3 center;
        final long start;
        final long riseStart;
        final long riseEnd;
        final long endTick;
        final int cooldownSeconds;
        final Set<UUID> hit = new HashSet<>();
        boolean risen;

        Spire(String skillId, ServerPlayer owner, ServerLevel level,
              List<FxBillboard> displays, List<Quaternionf> rotations,
              Vec3 ground, Vec3 center, long start, long riseStart, long riseEnd, long endTick,
              int cooldownSeconds) {
            this.skillId = skillId;
            this.owner = owner;
            this.level = level;
            this.displays = displays;
            this.rotations = rotations;
            this.ground = ground;
            this.center = center;
            this.start = start;
            this.riseStart = riseStart;
            this.riseEnd = riseEnd;
            this.endTick = endTick;
            this.cooldownSeconds = cooldownSeconds;
        }

        void discardAll() {
            displays.forEach(FxBillboard::discard);
            displays.clear();
        }
    }

    /** 山屏 */
    private static final class Screen {
        final String skillId;
        final ServerPlayer owner;
        final ServerLevel level;
        final FxBillboard display;
        final List<FxBillboard> pillars;
        final Vec3 center;
        /** 墙面法线（水平朝向） */
        final Vec3 flat;
        /** 墙面切向（左右方向） */
        final Vec3 tangent;
        final Vec3 ground;
        final long start;
        final long spreadStart;
        final long spreadEnd;
        final long endTick;
        final int cooldownSeconds;
        final Set<UUID> hit = new HashSet<>();
        boolean spread;

        Screen(String skillId, ServerPlayer owner, ServerLevel level, FxBillboard display,
               List<FxBillboard> pillars, Vec3 center, Vec3 flat, Vec3 tangent, Vec3 ground,
               long start, long spreadStart, long spreadEnd, long endTick, int cooldownSeconds) {
            this.skillId = skillId;
            this.owner = owner;
            this.level = level;
            this.display = display;
            this.pillars = pillars;
            this.center = center;
            this.flat = flat;
            this.tangent = tangent;
            this.ground = ground;
            this.start = start;
            this.spreadStart = spreadStart;
            this.spreadEnd = spreadEnd;
            this.endTick = endTick;
            this.cooldownSeconds = cooldownSeconds;
        }

        Quaternionf rotation() {
            return DisplayFx.yawRotation(Math.atan2(flat.x, flat.z));
        }

        void clear() {
            if (display.isAlive()) {
                display.discard();
            }
            pillars.forEach(pillar -> {
                if (pillar.isAlive()) {
                    pillar.discard();
                }
            });
        }
    }

    /** 石盾：4 面绕身石盾 */
    private static final class StoneShield {
        final LivingEntity owner;
        final ServerLevel level;
        final long endTick;
        final List<FxBillboard> displays = new ArrayList<>(4);

        StoneShield(LivingEntity owner, ServerLevel level, long endTick) {
            this.owner = owner;
            this.level = level;
            this.endTick = endTick;
        }

        void ensureDisplays() {
            if (!displays.isEmpty()) {
                return;
            }
            Vec3 base = owner.position().add(0.0D, owner.getBbHeight() * 0.55D, 0.0D);
            for (int i = 0; i < 4; i++) {
                double angle = i * (Math.PI / 2.0D);
                FxBillboard display = DisplayFx.spawn(level,
                        base.add(Math.cos(angle) * 1.0D, 0.0D, Math.sin(angle) * 1.0D),
                        MagicItems.EARTH_SHIELD.get(), 0.75F, 0.75F, 0.75F,
                        Display.BillboardConstraints.CENTER);
                if (display != null) {
                    displays.add(display);
                }
            }
        }

        void tick(long now) {
            Vec3 base = owner.position().add(0.0D, owner.getBbHeight() * 0.55D, 0.0D);
            for (int i = 0; i < displays.size(); i++) {
                double angle = now * 0.08D + i * (Math.PI / 2.0D);
                FxBillboard display = displays.get(i);
                display.setPos(base.x + Math.cos(angle) * 1.05D,
                        base.y + Math.sin(now * 0.06D + i) * 0.12D,
                        base.z + Math.sin(angle) * 1.05D);
            }
        }

        void clear() {
            displays.forEach(FxBillboard::discard);
            displays.clear();
        }
    }

    /** 岩魔之瞳的石眼 */
    private static final class Eye {
        final ServerPlayer owner;
        final ServerLevel level;
        final FxBillboard display;
        final Vec3 origin;
        final Vec3 dir;
        final long start;
        final long fireTick;
        final long moveEnd;
        /** 施法范围（按玩家阶级 10/15/20/30） */
        final double range;
        boolean fired;

        Eye(ServerPlayer owner, ServerLevel level, FxBillboard display, Vec3 origin, Vec3 dir,
            long start, long fireTick, long moveEnd, double range) {
            this.owner = owner;
            this.level = level;
            this.display = display;
            this.origin = origin;
            this.dir = dir;
            this.start = start;
            this.fireTick = fireTick;
            this.moveEnd = moveEnd;
            this.range = range;
        }
    }

    /** 石化中的生物 */
    private record Petrified(ServerLevel level, LivingEntity entity, long endTick) {
    }

    /** 角斗场 */
    private static final class Arena {
        final String skillId;
        final ServerPlayer owner;
        final ServerLevel level;
        final LivingEntity target;
        final long endTick;
        final int cooldownSeconds;

        Arena(String skillId, ServerPlayer owner, ServerLevel level, LivingEntity target,
              long endTick, int cooldownSeconds) {
            this.skillId = skillId;
            this.owner = owner;
            this.level = level;
            this.target = target;
            this.endTick = endTick;
            this.cooldownSeconds = cooldownSeconds;
        }
    }
}
