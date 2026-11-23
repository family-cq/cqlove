package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import com.cqgzs.cqlove.network.MagicCirclePayload;
import com.cqgzs.cqlove.fx.FxBlock;

/**
 * 植物系 8 个技能（2026-11-16 设计实现）。
 *
 * <p>四阶沿用需求文档组名：藤蔓 / 坤之林 / 鬼木手 / 移花接木，
 * 补成 初阶 3 + 中阶 3 + 高阶 1 + 超阶 1。定位为<b>实体植物 + 缠绕控场 + 生命汲取</b>。</p>
 *
 * <p><b>视觉上不用贴图面片，改用真实方块模型</b>：所有藤蔓/圆木/树叶/花丛都是
 * {@link FxBlock} 摆到世界里的原版方块（见 {@link PlantFx}），
 * 由客户端原版渲染器绘制，有厚度、有原版植物几何，绕着走也不会穿帮。</p>
 *
 * <ul>
 *   <li>初阶·藤蔓：生长（脚下藤蔓向前铺开 20 点 + 缠绕）、追袭（藤蔓追踪准星目标
 *       35 点 + 缠绕并拽向施法者）、束缚（根系环定身 10 点 + 缓慢 V）。</li>
 *   <li>中阶·坤之林：囚牢（木牢 10 秒，圈内每秒 25 点且逃不出去）、
 *       疯长（半径内爆长植物 8 秒，每秒 30 点 + 缓慢）、绽放（孢子花爆开 80 点 + 中毒 II）。</li>
 *   <li>高阶·鬼木手：跟随领域 30 秒，巨木手抓握，圈内敌对每秒 120 点、被拽向中心并定身。</li>
 *   <li>超阶·移花接木：跟随灵树 60 秒，圈内友方每秒回 20 点、敌对每秒 200 点；
 *       施法者受伤 -30% 且当次伤害的 50% 下一 tick 转为治疗；圈内友方每次施法免死一次。</li>
 * </ul>
 *
 * <p>领域 DOT 按施法者聚合成批结算（{@link #BATCH}），避免原版无敌帧吞掉多段伤害。</p>
 */
public final class PlantMagic {

    private PlantMagic() {
    }

    // ---------------- 时长（tick） ----------------
    private static final int GROW_LIFE = 4 * 20;
    private static final int BIND_TICKS = 5 * 20;
    private static final int PRISON_TICKS = 10 * 20;
    private static final int OVERGROWTH_TICKS = 8 * 20;
    private static final int HAND_TICKS = 30 * 20;
    private static final int GRAFT_TICKS = 60 * 20;
    private static final int DOT_TICKS = 20;
    private static final int TRAIL_LIFE = 20;

    // ---------------- 伤害数值 ----------------
    private static final double GROW_DAMAGE = 20.0D;
    private static final double HUNT_DAMAGE = 35.0D;
    private static final double BIND_DAMAGE = 10.0D;
    private static final double PRISON_DPS = 25.0D;
    private static final double OVERGROWTH_DPS = 30.0D;
    private static final double BLOOM_DAMAGE = 80.0D;
    private static final double HAND_DPS = 120.0D;
    private static final double GRAFT_DPS = 200.0D;
    private static final double GRAFT_HEAL = 20.0D;
    /** 移花接木期间施法者受伤倍率（-30%） */
    private static final double GRAFT_TAKEN = 0.7D;
    /** 移花接木期间当次伤害的 50% 转为治疗 */
    private static final double GRAFT_CONVERT = 0.5D;

    /** 固定范围（领域半径跟施法范围，这些是小范围硬编码） */
    private static final double PRISON_RADIUS = 5.0D;
    private static final double OVERGROWTH_RADIUS = 6.0D;
    private static final double BLOOM_RADIUS = 6.0D;
    private static final double HAND_RADIUS_MAX = 10.0D;
    private static final double GRAFT_RADIUS_MAX = 12.0D;

    // ---------------- 运行时状态（不存档） ----------------
    private enum Kind { PRISON, OVERGROWTH, HAND }

    /** 定身（束缚）：单体，每 tick 压住速度 */
    private static final List<Bind> BINDS = new ArrayList<>();
    /** 中/高阶领域 */
    private static final List<Field> FIELDS = new ArrayList<>();
    /** 超阶移花接木灵树 */
    private static final List<Graft> GRAFTS = new ArrayList<>();
    /** 每 tick 待结算：施法者 -> (目标 -> 本 tick 累计伤害) */
    private static final Map<ServerPlayer, Map<LivingEntity, Double>> BATCH = new HashMap<>();

    // ==================================================================
    // 初阶·藤蔓
    // ==================================================================

    /** 生长：从脚下沿视线方向铺开一条藤蔓带（最长 9 格），沿途敌对 20 点 + 缠绕 4 秒 */
    public static void grow(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        Vec3 look = player.getLookAngle();
        double flat = Math.sqrt(look.x * look.x + look.z * look.z);
        double dx = flat < 0.01D ? 0.0D : look.x / flat;
        double dz = flat < 0.01D ? 1.0D : look.z / flat;
        double length = Math.min(ctx.range(), 9.0D);
        Vec3 base = player.position();

        List<FxBlock> vines = new ArrayList<>();
        Set<UUID> damaged = new HashSet<>();
        int steps = (int) Math.ceil(length / 0.9D);

        for (int i = 1; i <= steps; i++) {
            final int index = i;
            // 逐格长出：每 tick 一格，看起来是藤蔓"爬"出去而不是瞬间铺满
            MagicScheduler.later(level, index, () -> {
                Vec3 at = base.add(dx * index * 0.9D, 0.08D, dz * index * 0.9D);
                FxBlock vine = PlantFx.block(level, at, Blocks.WEEPING_VINES, 1.05F, true);
                if (vine != null) {
                    vines.add(vine);
                }
                for (LivingEntity entity : hostilesInRange(level, player, at, 1.7D)) {
                    if (!damaged.add(entity.getUUID())) {
                        continue;
                    }
                    MagicDamage.magicDamage(player, entity, GROW_DAMAGE, MagicElement.PLANT);
                    entangle(entity, 80, 2);
                }
                if (index % 3 == 0) {
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 0.3D, at.z,
                            4, 0.3D, 0.25D, 0.3D, 0.01D);
                }
            });
        }

        play(level, base, SoundEvents.CROP_PLANTED, 1.1F, 1.2F);
        play(level, base, SoundEvents.AZALEA_LEAVES_PLACE, 1.0F, 0.9F);
        // 整条藤蔓带存活 4 秒后回收
        MagicScheduler.later(level, GROW_LIFE, () -> PlantFx.discardAll(vines));
    }

    /** 追袭：藤蔓头部贴着地面追踪准星目标，命中 35 点 + 缠绕 + 拽向施法者 */
    public static void hunt(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        LivingEntity target = ctx.targetEntity();

        Vec3 start = player.position().add(0.0D, 0.35D, 0.0D);
        List<FxBlock> parts = new ArrayList<>();
        FxBlock head = PlantFx.block(level, start, Blocks.MANGROVE_ROOTS, 0.9F, true);
        if (head != null) {
            parts.add(head);
        }

        Vec3[] pos = {start};
        Vec3[] aim = {target != null ? target.position() : ctx.targetPos()};
        boolean[] done = {false};
        int life = ctx.travelTicks(0.85D);

        MagicScheduler.every(level, 1, life, () -> {
            if (done[0]) {
                return;
            }
            if (target != null && target.isAlive() && target.level() == level) {
                aim[0] = target.position().add(0.0D, target.getBbHeight() * 0.45D, 0.0D);
            }
            Vec3 to = aim[0].subtract(pos[0]);
            if (to.length() < 1.1D) {
                done[0] = true;
                for (LivingEntity entity : hostilesInRange(level, player, pos[0], 2.0D)) {
                    MagicDamage.magicDamage(player, entity, HUNT_DAMAGE, MagicElement.PLANT);
                    entangle(entity, 60, 3);
                    pullToward(entity, player.position(), 0.85D);
                }
                level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, pos[0].x, pos[0].y + 0.4D, pos[0].z,
                        18, 0.6D, 0.5D, 0.6D, 0.02D);
                play(level, pos[0], SoundEvents.ROOTS_PLACE, 1.1F, 0.8F);
                PlantFx.discardAll(parts);
                return;
            }
            pos[0] = pos[0].add(to.normalize().scale(0.85D));
            PlantFx.move(head, pos[0]);
            // 拖尾：每 2 tick 留下一小段藤蔓，20 tick 后自行消失
            if (level.getGameTime() % 2L == 0L) {
                FxBlock trail = PlantFx.block(level, pos[0].add(0.0D, -0.25D, 0.0D),
                        Blocks.WEEPING_VINES, 0.55F, false);
                if (trail != null) {
                    MagicScheduler.later(level, TRAIL_LIFE, trail::discard);
                }
            }
        }, () -> PlantFx.discardAll(parts));

        play(level, start, SoundEvents.CAVE_VINES_PLACE, 1.0F, 1.1F);
    }

    /** 束缚：目标脚下爆出根系环，10 点 + 定身 5 秒（缓慢 V + 每 tick 压速度） */
    public static void bind(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        LivingEntity target = resolveHostileTarget(ctx, 2.0D);
        if (target == null) {
            failCast(ctx, "藤蔓·束缚失败: 准星没有指向敌对生物");
            return;
        }
        long now = level.getGameTime();
        Vec3 base = target.position();
        List<FxBlock> parts = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            double angle = i * Math.PI * 2.0D / 6.0D;
            Vec3 at = base.add(Math.cos(angle) * 1.15D, 0.05D, Math.sin(angle) * 1.15D);
            FxBlock root = PlantFx.block(level, at, Blocks.MANGROVE_ROOTS.defaultBlockState(),
                    0.5F, 1.5F, 0.5F, PlantFx.tilt((float) angle, 0.32F), true);
            if (root != null) {
                parts.add(root);
            }
        }
        FxBlock mat = PlantFx.block(level, base.add(0.0D, 0.02D, 0.0D),
                Blocks.MOSS_CARPET, 2.4F, false);
        if (mat != null) {
            parts.add(mat);
        }

        BINDS.add(new Bind(target, level, now + BIND_TICKS, parts));
        MagicDamage.magicDamage(player, target, BIND_DAMAGE, MagicElement.PLANT);
        entangle(target, BIND_TICKS, 4);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, base.x, base.y + 0.5D, base.z,
                12, 0.7D, 0.4D, 0.7D, 0.02D);
        play(level, base, SoundEvents.ROOTS_PLACE, 1.2F, 0.75F);
    }

    // ==================================================================
    // 中阶·坤之林
    // ==================================================================

    /** 囚牢：落点半径 5 长出木牢 10 秒，圈内敌对每秒 25 点，试图逃出会被藤蔓拽回 */
    public static void prison(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        long now = level.getGameTime();
        Vec3 center = ctx.targetPos();
        double radius = PRISON_RADIUS;

        Field field = new Field(Kind.PRISON, player, level, (float) radius, false, center,
                now + PRISON_TICKS, ctx.skill().id(),
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()), now);

        // 12 根圆木立柱 + 顶部一圈树叶，围成木牢
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI * 2.0D / 12.0D;
            Vec3 at = center.add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
            FxBlock pillar = PlantFx.block(level, at, Blocks.OAK_LOG.defaultBlockState(),
                    0.7F, 2.6F, 0.7F, PlantFx.yaw((float) angle), true);
            if (pillar != null) {
                field.parts.add(pillar);
            }
            if (i % 2 == 0) {
                FxBlock leaf = PlantFx.block(level,
                        at.add(0.0D, 2.7D, 0.0D), Blocks.OAK_LEAVES, 1.5F, true);
                if (leaf != null) {
                    field.parts.add(leaf);
                }
            }
        }
        FIELDS.add(field);
        MagicCircleSync.start(player, MagicCirclePayload.PLANT_PRISON, PRISON_TICKS, (float) radius);

        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x, center.y + 0.4D, center.z,
                32, radius * 0.6D, 0.5D, radius * 0.6D, 0.02D);
        play(level, center, SoundEvents.GRASS_PLACE, 1.4F, 0.7F);
        play(level, center, SoundEvents.ROOTS_PLACE, 1.2F, 0.8F);
    }

    /** 疯长：落点半径 6 地面爆长一片植物，8 秒内圈内敌对每秒 30 点 + 缓慢 II */
    public static void overgrowth(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        long now = level.getGameTime();
        Vec3 center = ctx.targetPos();
        double radius = OVERGROWTH_RADIUS;

        Field field = new Field(Kind.OVERGROWTH, player, level, (float) radius, false, center,
                now + OVERGROWTH_TICKS, ctx.skill().id(),
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()), now);

        var random = level.getRandom();
        var plants = new net.minecraft.world.level.block.Block[]{
                Blocks.SHORT_GRASS, Blocks.FERN, Blocks.SHORT_GRASS, Blocks.SPORE_BLOSSOM,
                Blocks.MOSS_CARPET, Blocks.LARGE_FERN
        };
        for (int i = 0; i < 26; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double dist = Math.sqrt(random.nextDouble()) * radius;
            Vec3 at = center.add(Math.cos(angle) * dist, 0.02D, Math.sin(angle) * dist);
            float scale = 0.9F + random.nextFloat() * 0.6F;
            FxBlock plant = PlantFx.block(level, at, plants[random.nextInt(plants.length)],
                    scale, i % 3 == 0);
            if (plant != null) {
                field.parts.add(plant);
            }
        }
        FIELDS.add(field);
        MagicCircleSync.start(player, MagicCirclePayload.PLANT_OVERGROWTH, OVERGROWTH_TICKS, (float) radius);

        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x, center.y + 0.4D, center.z,
                40, radius * 0.7D, 0.4D, radius * 0.7D, 0.03D);
        play(level, center, SoundEvents.GRASS_PLACE, 1.4F, 0.8F);
        play(level, center, SoundEvents.CROP_PLANTED, 1.2F, 0.9F);
    }

    /** 绽放：落点半径 6 孢子花向外炸开，80 点 + 中毒 II 8 秒 */
    public static void bloom(SkillContext ctx) {
        ServerLevel level = ctx.level();
        ServerPlayer player = ctx.player();
        Vec3 center = ctx.targetPos();
        double radius = BLOOM_RADIUS;

        for (LivingEntity entity : hostilesInRange(level, player, center, radius)) {
            MagicDamage.magicDamage(player, entity, BLOOM_DAMAGE, MagicElement.PLANT);
            entity.addEffect(new MobEffectInstance(MobEffects.POISON, 8 * 20, 1));
            entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 8 * 20, 0));
        }

        // 12 朵花向外炸开：先短动画，再滞留 1.5 秒
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI * 2.0D / 12.0D;
            Vec3 at = center.add(Math.cos(angle) * 0.6D, 0.6D, Math.sin(angle) * 0.6D);
            var block = i % 3 == 0 ? Blocks.SUNFLOWER : Blocks.FLOWERING_AZALEA_LEAVES;
            FxBlock flower = PlantFx.block(level, at, block, 0.9F, true);
            if (flower == null) {
                continue;
            }
            for (int step = 1; step <= 6; step++) {
                final int index = step;
                MagicScheduler.later(level, index, () -> PlantFx.move(flower,
                        center.add(Math.cos(angle) * radius * index / 6.0D,
                                0.4D + index * 0.05D,
                                Math.sin(angle) * radius * index / 6.0D)));
            }
            MagicScheduler.later(level, 6 + 30, flower::discard);
        }

        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, center.x, center.y + 1.0D, center.z,
                50, radius * 0.6D, 1.0D, radius * 0.6D, 0.04D);
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, center.x, center.y + 1.2D, center.z,
                30, radius * 0.5D, 1.0D, radius * 0.5D, 0.02D);
        play(level, center, SoundEvents.AZALEA_LEAVES_PLACE, 1.4F, 1.1F);
        play(level, center, SoundEvents.BIG_DRIPLEAF_TILT_DOWN, 1.2F, 1.0F);
    }

    // ==================================================================
    // 高阶·鬼木手
    // ==================================================================

    /** 鬼木手：跟随领域 30 秒，巨木手抓握 —— 圈内敌对每秒 120 点、被拽向中心并定身 */
    public static void ghostHand(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        float radius = (float) Math.min(ctx.range(), HAND_RADIUS_MAX);
        beginTimed(ctx, HAND_TICKS);

        Field field = new Field(Kind.HAND, player, level, radius, true, player.position(),
                now + HAND_TICKS, ctx.skill().id(),
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()), now);

        Vec3 base = player.position();
        // 掌心：一块压扁的深色原木，像一只从地里探出来的木掌
        FxBlock palm = PlantFx.block(level, base.add(0.0D, 0.22D, 0.0D),
                Blocks.DARK_OAK_LOG.defaultBlockState(), 2.4F, 0.35F, 2.4F, PlantFx.yaw(0.0F), true);
        if (palm != null) {
            field.parts.add(palm);
        }
        // 5 根手指：高 3.2 格的原木柱，围成一圈
        for (int i = 0; i < 5; i++) {
            double angle = i * Math.PI * 2.0D / 5.0D;
            Vec3 at = base.add(Math.cos(angle) * 2.1D, 0.05D, Math.sin(angle) * 2.1D);
            FxBlock finger = PlantFx.block(level, at, Blocks.OAK_LOG.defaultBlockState(),
                    0.55F, 3.2F, 0.55F, PlantFx.tilt((float) angle, -0.22F), true);
            if (finger != null) {
                field.parts.add(finger);
            }
        }
        FIELDS.add(field);
        MagicCircleSync.start(player, MagicCirclePayload.PLANT_HAND, HAND_TICKS, radius);

        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, base.x, base.y + 0.6D, base.z,
                30, 2.0D, 0.5D, 2.0D, 0.03D);
        play(level, base, SoundEvents.GRASS_PLACE, 1.6F, 0.5F);
        play(level, base, SoundEvents.ROOTS_PLACE, 1.4F, 0.6F);
        play(level, base, SoundEvents.BELL_RESONATE, 1.0F, 0.7F);
    }

    // ==================================================================
    // 超阶·移花接木
    // ==================================================================

    /**
     * 移花接木：跟随灵树 60 秒。圈内友方每秒回 20 点、敌对每秒 200 点；
     * 施法者受伤 -30% 且当次伤害 50% 下一 tick 转为治疗；
     * 圈内友方每次施法可免死一次（{@link #onIncomingDamage}）。
     */
    public static void graft(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        float radius = (float) Math.min(ctx.range(), GRAFT_RADIUS_MAX);
        beginTimed(ctx, GRAFT_TICKS);

        Graft graft = new Graft(player, level, radius, now + GRAFT_TICKS, ctx.skill().id(),
                ctx.skill().cooldownSeconds(MagicData.get(player).rank()), now);
        buildTree(level, player.position(), graft);

        GRAFTS.add(graft);
        MagicCircleSync.start(player, MagicCirclePayload.PLANT_GRAFT, GRAFT_TICKS, radius);

        Vec3 at = player.position().add(0.0D, 0.15D, 0.0D);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 1.0D, at.z,
                48, radius * 0.4D, 1.2D, radius * 0.4D, 0.04D);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, at.x, at.y + 2.0D, at.z,
                32, 2.0D, 1.5D, 2.0D, 0.03D);
        play(level, at, SoundEvents.TOTEM_USE, 1.2F, 0.8F);
        play(level, at, SoundEvents.AZALEA_LEAVES_PLACE, 1.2F, 0.7F);
        NoticeApi.send(player, Component.literal("移花接木: 灵树结界展开 —— 圈内友方回血、免死一次, 敌方持续受创")
                .withStyle(ChatFormatting.GREEN));
    }

    /** 造一棵灵树：3 段树干 + 8 片树冠 + 4 条根系（都记进 graft.parts 便于回收/跟随） */
    private static void buildTree(ServerLevel level, Vec3 base, Graft graft) {
        for (int i = 0; i < 3; i++) {
            FxBlock trunk = PlantFx.block(level, base.add(0.0D, 0.55D + i * 0.95D, 0.0D),
                    Blocks.OAK_LOG.defaultBlockState(), 0.95F, 1.0F, 0.95F,
                    PlantFx.yaw(0.0F), true);
            if (trunk != null) {
                graft.parts.add(trunk);
            }
        }
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI * 2.0D / 8.0D;
            Vec3 at = base.add(Math.cos(angle) * 1.75D, 3.25D, Math.sin(angle) * 1.75D);
            FxBlock leaf = PlantFx.block(level, at, Blocks.AZALEA_LEAVES, 1.6F, true);
            if (leaf != null) {
                graft.parts.add(leaf);
            }
        }
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI * 2.0D / 4.0D;
            Vec3 at = base.add(Math.cos(angle) * 1.05D, 0.05D, Math.sin(angle) * 1.05D);
            FxBlock root = PlantFx.block(level, at, Blocks.MANGROVE_ROOTS.defaultBlockState(),
                    0.55F, 1.1F, 0.55F, PlantFx.tilt((float) angle, 0.4F), false);
            if (root != null) {
                graft.parts.add(root);
            }
        }
    }

    // ==================================================================
    // 每 tick 驱动（MagicEvents.onServerTick 调用）
    // ==================================================================

    public static void tickWorld() {
        BATCH.clear();
        long now = -1L;

        // 束缚：每 tick 压住速度
        for (int i = BINDS.size() - 1; i >= 0; i--) {
            Bind bind = BINDS.get(i);
            now = bind.level.getGameTime();
            if (now >= bind.endTick || !bind.target.isAlive()) {
                PlantFx.discardAll(bind.parts);
                BINDS.remove(i);
                continue;
            }
            if (bind.target instanceof Mob mob) {
                holdStill(mob);
            }
            if (now % 10L == 0L) {
                Vec3 at = bind.target.position().add(0.0D, 0.2D, 0.0D);
                bind.level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y, at.z,
                        4, 0.5D, 0.15D, 0.5D, 0.01D);
            }
        }

        // 中/高阶领域
        for (int i = FIELDS.size() - 1; i >= 0; i--) {
            Field field = FIELDS.get(i);
            ServerLevel level = field.level;
            ServerPlayer owner = field.owner;
            now = level.getGameTime();
            if (!owner.isAlive() || now >= field.endTick) {
                PlantFx.discardAll(field.parts);
                MagicCircleSync.stop(owner, circleOf(field.kind));
                finishTimed(owner, field.skillId, field.cooldownSeconds, now);
                FIELDS.remove(i);
                continue;
            }

            if (field.kind == Kind.HAND) {
                field.center = owner.position();
                animateHand(field, now);
            }

            if (now >= field.nextPulse) {
                field.nextPulse = now + DOT_TICKS;
                double dps = field.kind == Kind.PRISON ? PRISON_DPS
                        : field.kind == Kind.OVERGROWTH ? OVERGROWTH_DPS : HAND_DPS;
                for (LivingEntity entity : hostilesInRange(level, owner, field.center, field.radius)) {
                    addDamage(owner, entity, dps);
                    if (field.kind == Kind.OVERGROWTH) {
                        entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1));
                    } else {
                        entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 3));
                        if (entity instanceof Mob mob) {
                            holdStill(mob);
                        }
                    }
                }
                ringParticles(level, field.center, field.radius, 20, field.kind);
            }

            // 囚牢 / 鬼木手：跑出圈外的敌对会被藤蔓拽回
            if (field.kind != Kind.OVERGROWTH) {
                double edge = field.radius - 0.6D;
                for (LivingEntity entity : nearbyHostiles(level, owner, field.center, field.radius + 3.0D)) {
                    if (flatDistance(field.center, entity.position()) > edge) {
                        pullToward(entity, field.center, field.kind == Kind.HAND ? 0.85D : 0.55D);
                    }
                }
            }
        }

        // 超阶灵树
        for (int i = GRAFTS.size() - 1; i >= 0; i--) {
            Graft graft = GRAFTS.get(i);
            ServerLevel level = graft.level;
            ServerPlayer owner = graft.owner;
            now = level.getGameTime();
            if (!owner.isAlive() || now >= graft.endTick) {
                PlantFx.discardAll(graft.parts);
                MagicCircleSync.stop(owner, MagicCirclePayload.PLANT_GRAFT);
                finishTimed(owner, graft.skillId, graft.cooldownSeconds, now);
                GRAFTS.remove(i);
                continue;
            }
            Vec3 base = owner.position();
            animateTree(graft, base, now);

            if (now >= graft.nextPulse) {
                graft.nextPulse = now + DOT_TICKS;
                for (LivingEntity entity : hostilesInRange(level, owner, base, graft.radius)) {
                    addDamage(owner, entity, GRAFT_DPS);
                    entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 2));
                    if (entity instanceof Mob mob) {
                        holdStill(mob);
                    }
                }
                for (LivingEntity ally : alliesInRange(level, owner, base, graft.radius)) {
                    ally.heal((float) GRAFT_HEAL);
                    if (ally.level() instanceof ServerLevel allyLevel) {
                        Vec3 at = ally.position().add(0.0D, ally.getBbHeight() * 0.7D, 0.0D);
                        allyLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y, at.z,
                                3, 0.4D, 0.4D, 0.4D, 0.01D);
                    }
                }
                ringParticles(level, base, graft.radius, 24, null);
            }
        }

        flush();
    }

    /** 巨木手：指尖绕掌心缓慢旋转，整只手随玩家移动 */
    private static void animateHand(Field field, long now) {
        Vec3 center = field.center;
        field.spin = now * 0.03F;
        for (int i = 1; i < field.parts.size(); i++) {
            double angle = (i - 1) * Math.PI * 2.0D / 5.0D + field.spin;
            double lift = Math.sin(now * 0.08D + i) * 0.12D;
            PlantFx.move(field.parts.get(i),
                    center.add(Math.cos(angle) * 2.1D, 0.05D + lift, Math.sin(angle) * 2.1D));
        }
        if (!field.parts.isEmpty()) {
            PlantFx.move(field.parts.get(0), center.add(0.0D, 0.22D, 0.0D));
        }
    }

    /** 灵树：树干/根系固定在玩家身上，树冠绕轴慢转，整体有轻微呼吸起伏 */
    private static void animateTree(Graft graft, Vec3 base, long now) {
        float spin = now * 0.02F;
        int index = 0;
        // 0..2 树干
        for (int i = 0; i < 3 && index < graft.parts.size(); i++, index++) {
            PlantFx.move(graft.parts.get(index), base.add(0.0D, 0.55D + i * 0.95D, 0.0D));
        }
        // 3..10 树冠
        for (int i = 0; i < 8 && index < graft.parts.size(); i++, index++) {
            double angle = i * Math.PI * 2.0D / 8.0D + spin;
            double sway = Math.sin(now * 0.06D + i) * 0.1D;
            PlantFx.move(graft.parts.get(index),
                    base.add(Math.cos(angle) * 1.75D, 3.25D + sway, Math.sin(angle) * 1.75D));
        }
        // 11..14 根系（静止）
        for (int i = 0; i < 4 && index < graft.parts.size(); i++, index++) {
            double angle = i * Math.PI * 2.0D / 4.0D;
            PlantFx.move(graft.parts.get(index),
                    base.add(Math.cos(angle) * 1.05D, 0.05D, Math.sin(angle) * 1.05D));
        }
    }

    // ==================================================================
    // 伤害事件：移花接木（减伤 + 伤害转治疗 + 免死）
    // ==================================================================

    /**
     * 移花接木的「伤害转移」：受伤 -30%，当次伤害的 50% 下一 tick 转成治疗。
     * 必须在其它致死判定之前调用，否则减免后的真实伤害会被误判成致死。
     */
    public static void applyGraftReduction(LivingIncomingDamageEvent event) {
        if (event.isCanceled()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity == null || !entity.isAlive() || graftCovering(entity) == null) {
            return;
        }
        float raw = event.getAmount();
        event.setAmount(raw * (float) GRAFT_TAKEN);
        if (entity.level() instanceof ServerLevel level) {
            MagicScheduler.later(level, 1, () -> {
                if (entity.isAlive()) {
                    entity.heal((float) (raw * GRAFT_CONVERT));
                }
            });
        }
    }

    /**
     * 由 {@code MagicEvents} 在其它减伤/免疫结算之后调用：
     * 圈内友方致死时由灵树代受，免死一次并回到 50% 生命。
     */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.isCanceled()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity == null || !entity.isAlive()) {
            return;
        }
        Graft graft = graftCovering(entity);
        if (graft == null) {
            return;
        }
        if (entity.getHealth() - event.getAmount() > 0.0F) {
            return;
        }

        // 致死：每次灵树只替每个友方挡一次
        if (!graft.used.add(entity.getUUID())) {
            return;
        }
        event.setCanceled(true);
        entity.setHealth(Math.max(1.0F, entity.getMaxHealth() * 0.5F));
        if (entity.level() instanceof ServerLevel level) {
            Vec3 at = entity.position().add(0.0D, entity.getBbHeight() * 0.6D, 0.0D);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y, at.z,
                    30, 0.7D, 0.9D, 0.7D, 0.05D);
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, at.x, at.y, at.z,
                    20, 0.6D, 1.0D, 0.6D, 0.03D);
            play(level, at, SoundEvents.TOTEM_USE, 1.4F, 1.0F);
        }
        if (entity instanceof ServerPlayer player) {
            NoticeApi.send(player, Component.literal("移花接木: 灵树替你承下了这一击")
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    private static @Nullable Graft graftCovering(LivingEntity entity) {
        for (Graft graft : GRAFTS) {
            if (!graft.owner.isAlive()) {
                continue;
            }
            if (graft.owner.level() != entity.level()) {
                continue;
            }
            if (!CombatTracker.isAllyTarget(graft.owner, entity)) {
                continue;
            }
            if (flatDistance(graft.owner.position(), entity.position()) <= graft.radius) {
                return graft;
            }
        }
        return null;
    }

    // ==================================================================
    // 清理
    // ==================================================================

    public static void removePlayer(ServerPlayer player) {
        BINDS.removeIf(bind -> {
            if (bind.target == player) {
                PlantFx.discardAll(bind.parts);
                return true;
            }
            return false;
        });
        for (int i = FIELDS.size() - 1; i >= 0; i--) {
            if (FIELDS.get(i).owner == player) {
                Field field = FIELDS.remove(i);
                PlantFx.discardAll(field.parts);
                MagicCircleSync.stop(player, circleOf(field.kind));
            }
        }
        for (int i = GRAFTS.size() - 1; i >= 0; i--) {
            if (GRAFTS.get(i).owner == player) {
                PlantFx.discardAll(GRAFTS.remove(i).parts);
                MagicCircleSync.stop(player, MagicCirclePayload.PLANT_GRAFT);
            }
        }
    }

    public static void clearAll() {
        for (Bind bind : BINDS) {
            PlantFx.discardAll(bind.parts);
        }
        BINDS.clear();
        for (Field field : FIELDS) {
            PlantFx.discardAll(field.parts);
        }
        FIELDS.clear();
        for (Graft graft : GRAFTS) {
            PlantFx.discardAll(graft.parts);
        }
        GRAFTS.clear();
        BATCH.clear();
    }

    private static int circleOf(Kind kind) {
        return switch (kind) {
            case PRISON -> MagicCirclePayload.PLANT_PRISON;
            case OVERGROWTH -> MagicCirclePayload.PLANT_OVERGROWTH;
            case HAND -> MagicCirclePayload.PLANT_HAND;
        };
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static @Nullable LivingEntity resolveHostileTarget(SkillContext ctx, double fallbackRadius) {
        LivingEntity target = ctx.targetEntity();
        if (target != null && target.isAlive() && !target.isSpectator()
                && target != ctx.player() && hostile(ctx.player(), target)) {
            return target;
        }
        LivingEntity best = null;
        double bestDist = fallbackRadius * fallbackRadius;
        for (LivingEntity entity : nearbyHostiles(ctx.level(), ctx.player(), ctx.targetPos(), fallbackRadius)) {
            double dist = flatDistance(ctx.targetPos(), entity.position());
            if (dist < bestDist) {
                bestDist = dist;
                best = entity;
            }
        }
        return best;
    }

    private static List<LivingEntity> nearbyHostiles(ServerLevel level, ServerPlayer owner,
                                                     Vec3 center, double radius) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius, 8.0D, radius))) {
            if (!hostile(owner, entity)) {
                continue;
            }
            result.add(entity);
        }
        return result;
    }

    private static List<LivingEntity> hostilesInRange(ServerLevel level, ServerPlayer owner,
                                                      Vec3 center, double radius) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity entity : nearbyHostiles(level, owner, center, radius)) {
            if (within(center, entity.position(), radius)) {
                result.add(entity);
            }
        }
        return result;
    }

    private static List<LivingEntity> alliesInRange(ServerLevel level, ServerPlayer owner,
                                                    Vec3 center, double radius) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius, 8.0D, radius))) {
            if (entity == owner || !entity.isAlive() || entity.isSpectator()) {
                continue;
            }
            if (entity instanceof Player) {
                // 其他玩家永远算友方（PVP 开关不改变治疗/增益对象）
                if (within(center, entity.position(), radius)) {
                    result.add(entity);
                }
                continue;
            }
            if (entity instanceof net.minecraft.world.entity.monster.Enemy) {
                continue;
            }
            if (within(center, entity.position(), radius)) {
                result.add(entity);
            }
        }
        // 施法者自己也算友方
        if (owner.isAlive()) {
            result.add(owner);
        }
        return result;
    }

    private static boolean hostile(ServerPlayer owner, LivingEntity entity) {
        return CombatTracker.isEnemyTarget(owner, entity);
    }

    private static boolean within(Vec3 center, Vec3 pos, double radius) {
        return flatDistance(center, pos) <= radius;
    }

    private static double flatDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 缠绕：缓慢 + 虚弱（每秒结算一次，40 tick 续命） */
    private static void entangle(LivingEntity entity, int ticks, int amplifier) {
        entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, amplifier));
        entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0));
    }

    private static void pullToward(LivingEntity entity, Vec3 center, double strength) {
        Vec3 delta = center.subtract(entity.position());
        double len = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (len < 0.05D) {
            return;
        }
        entity.setDeltaMovement(entity.getDeltaMovement()
                .add(delta.x / len * strength, 0.0D, delta.z / len * strength));
        entity.hurtMarked = true;
    }

    private static void holdStill(Mob mob) {
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0D);
        mob.setSpeed(0.0F);
        mob.setXxa(0.0F);
        Vec3 velocity = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0D, velocity.y, 0.0D);
        mob.hurtMarked = true;
    }

    private static void addDamage(ServerPlayer owner, LivingEntity target, double base) {
        if (owner == null || target == null || target == owner || !target.isAlive() || target.isRemoved()) {
            return;
        }
        BATCH.computeIfAbsent(owner, key -> new HashMap<>())
                .merge(target, base, Double::sum);
    }

    private static void flush() {
        for (Map.Entry<ServerPlayer, Map<LivingEntity, Double>> caster : BATCH.entrySet()) {
            ServerPlayer owner = caster.getKey();
            for (Map.Entry<LivingEntity, Double> hit : caster.getValue().entrySet()) {
                LivingEntity target = hit.getKey();
                if (target == owner || !target.isAlive() || target.isRemoved()) {
                    continue;
                }
                MagicDamage.magicDamage(owner, target, hit.getValue(), MagicElement.PLANT);
            }
        }
    }

    private static void ringParticles(ServerLevel level, Vec3 center, double radius, int count,
                                      @Nullable Kind kind) {
        double phase = level.getGameTime() * 0.05D;
        var particle = kind == Kind.OVERGROWTH || kind == null
                ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SPORE_BLOSSOM_AIR;
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2.0D / count + phase;
            level.sendParticles(particle,
                    center.x + Math.cos(angle) * radius, center.y + 0.25D,
                    center.z + Math.sin(angle) * radius,
                    2, 0.04D, 0.3D, 0.04D, 0.0D);
        }
    }

    private static void play(ServerLevel level, Vec3 pos, net.minecraft.sounds.SoundEvent sound,
                             float volume, float pitch) {
        MagicDamage.playSound(level, pos, sound, volume, pitch);
    }

    private static void beginTimed(SkillContext ctx, int ticks) {
        PlayerMagicData data = MagicData.get(ctx.player());
        long now = ctx.level().getGameTime();
        data.setCooldown(ctx.skill().id(), 0L);
        data.lockSkill(ctx.skill().id(), now + ticks + 4L);
    }

    private static void finishTimed(ServerPlayer owner, String skillId, int cooldownSeconds, long now) {
        PlayerMagicData data = MagicData.get(owner);
        data.clearSkillLock(skillId);
        data.setCooldown(skillId, now + cooldownSeconds * 20L);
        data.endSuperRing(skillId);
        if (owner.connection != null) {
            MagicSync.sync(owner);
        }
    }

    private static void failCast(SkillContext ctx, String message) {
        PlayerMagicData data = MagicData.get(ctx.player());
        data.setMana(data.mana() + ctx.skill().manaCost());
        data.setCooldown(ctx.skill().id(), 0L);
        MagicSync.sync(ctx.player());
        NoticeApi.send(ctx.player(), Component.literal(message).withStyle(ChatFormatting.RED));
    }

    // ---------------- 状态记录 ----------------

    private static final class Bind {
        final LivingEntity target;
        final ServerLevel level;
        final long endTick;
        final List<FxBlock> parts = new ArrayList<>();

        Bind(LivingEntity target, ServerLevel level, long endTick, List<FxBlock> parts) {
            this.target = target;
            this.level = level;
            this.endTick = endTick;
            this.parts.addAll(parts);
        }
    }

    private static final class Field {
        final Kind kind;
        final ServerPlayer owner;
        final ServerLevel level;
        final float radius;
        final boolean follow;
        Vec3 center;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        final List<FxBlock> parts = new ArrayList<>();
        long nextPulse;
        float spin;

        Field(Kind kind, ServerPlayer owner, ServerLevel level, float radius, boolean follow,
              Vec3 center, long endTick, String skillId, int cooldownSeconds, long now) {
            this.kind = kind;
            this.owner = owner;
            this.level = level;
            this.radius = radius;
            this.follow = follow;
            this.center = center;
            this.endTick = endTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
            this.nextPulse = now;
        }
    }

    private static final class Graft {
        final ServerPlayer owner;
        final ServerLevel level;
        final float radius;
        final long endTick;
        final String skillId;
        final int cooldownSeconds;
        final List<FxBlock> parts = new ArrayList<>();
        final Set<UUID> used = new HashSet<>();
        long nextPulse;

        Graft(ServerPlayer owner, ServerLevel level, float radius, long endTick,
              String skillId, int cooldownSeconds, long now) {
            this.owner = owner;
            this.level = level;
            this.radius = radius;
            this.endTick = endTick;
            this.skillId = skillId;
            this.cooldownSeconds = cooldownSeconds;
            this.nextPulse = now;
        }
    }
}
