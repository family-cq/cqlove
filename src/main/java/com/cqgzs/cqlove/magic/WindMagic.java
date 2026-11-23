package com.cqgzs.cqlove.magic;

import com.cqgzs.cqlove.magic.NoticeApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 风系全部 5 个技能（需求文档“风系”段）。
 *
 * <ul>
 *   <li><b>风轨</b>（初阶）：疾行 +3 / 闪步 +6 / 飘影 +9 速度，持续 3 秒，青色拖尾；使用期间技能锁定，结束后才进冷却。</li>
 *   <li><b>风盘</b>（中阶）：龙卷 / 天罗 / 撕裂 —— 青色光晕从手上射到鼠标位置，落地生成一直旋转的旋风柱，
 *       吸扯范围内生物与物品，每秒结算伤害；撕裂还会随机露出星空裂口。</li>
 *   <li><b>风之翼</b>（高阶）：被动 = 背后六翼 + 创造飞行；主动 = 4 面风盾环绕、护甲/魔抗 +50，30 秒，锁定。</li>
 *   <li><b>泯风千刃</b>（超阶）：32 个迷你旋风绕身旋转，有生物就逐个飞出追踪，命中爆炸 500 点，全部飞出后才进冷却。</li>
 * </ul>
 *
 * <p>所有“一直旋转”都是贴图动画（{@code wind_cyclone.png} 8 帧）在做，服务端只更新位置；
 * 旋风柱 / 撕裂 / 翅膀 / 风盾都是 {@link DisplayFx} 生成的 billboard 面片，比粒子省。</p>
 */
public final class WindMagic {

    /** 风系代表色（用于粒子与技能文字） */
    private static final int WIND_COLOR = MagicElement.WIND.color();

    private static final Identifier DASH_SPEED_ID = id("wind_dash_speed");
    private static final Identifier WINGS_ARMOR_ID = id("wind_wings_armor");

    /** 初阶加速持续 tick（3 秒） */
    private static final int DASH_TICKS = 3 * 20;
    /** 风之翼主动 buff 持续 tick（30 秒） */
    private static final int WINGS_TICKS = 30 * 20;
    /** 泯风千刃最长锁定时间（防止玩家一直没遇到生物被永久锁技能） */
    private static final int BLADES_MAX_TICKS = 30 * 20;
    /** 泯风千刃一次飞出多少个旋风后再次发射（0.25 秒，攻击频率翻倍后） */
    private static final int BLADES_LAUNCH_INTERVAL = 5;
    /** 弭风千刃单个旋风伤害 */
    private static final double BLADES_DAMAGE = 500.0D;
    /** 风之翼主动的 4 面盾 */
    private static final Map<UUID, List<FxBillboard>> SHIELDS = new HashMap<>();
    /** 进行中的旋风柱（龙卷/天罗/撕裂） */
    private static final List<Cyclone> CYCLONES = new ArrayList<>();
    /** 当前占用“速度修饰器”的加速技能（三个加速共用同一个修饰器，先结束的不能抢后一个的） */
    private static final Map<UUID, String> DASH_OWNER = new HashMap<>();
    /** 进行中的泯风千刃 */
    private static final Map<UUID, BladeStorm> STORMS = new HashMap<>();

    private WindMagic() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PlatformMods.MODID, path);
    }

    // ==================================================================
    // 初阶·风轨：疾行 / 闪步 / 飘影
    // ==================================================================

    /** 疾行：速度 +3，持续 3 秒 */
    public static void swiftStep(SkillContext ctx) {
        dash(ctx, 3.0D);
    }

    /** 闪步：速度 +6，持续 3 秒 */
    public static void swiftStride(SkillContext ctx) {
        dash(ctx, 6.0D);
    }

    /** 飘影：速度 +9，持续 3 秒 */
    public static void swiftShadow(SkillContext ctx) {
        dash(ctx, 9.0D);
    }

    /**
     * 统一加速实现。
     *
     * <p>“速度增加 N”按 m/s 理解：原版走速约 4.3 m/s（MOVEMENT_SPEED 基础值 0.1），
     * 用 ADD_MULTIPLIED_BASE 把 +3 折算成基础值的 0.7 倍（约 +3 m/s），+6/+9 线性放大。</p>
     */
    private static void dash(SkillContext ctx, double bonus) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        PlayerMagicData data = MagicData.get(player);
        long now = level.getGameTime();
        String skillId = ctx.skill().id();

        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(DASH_SPEED_ID);
            speed.addTransientModifier(new AttributeModifier(DASH_SPEED_ID,
                    bonus / 3.0D * 0.7D, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        DASH_OWNER.put(player.getUUID(), skillId);
        // 使用期间技能锁定（文档），结束后才写冷却
        data.lockSkill(skillId, now + DASH_TICKS);
        MagicDamage.playSound(level, player.position(), SoundEvents.BREEZE_CHARGE, 0.9F, 1.5F);
        level.sendParticles(ParticleTypes.GUST, player.getX(), player.getY() + 0.2D, player.getZ(),
                8, 0.45D, 0.2D, 0.45D, 0.06D);

        // 3 秒内跟随后背的青色长条光晕拖尾
        MagicScheduler.every(level, 2, DASH_TICKS, () -> {
            if (!player.isAlive()) {
                return;
            }
            Vec3 back = player.position()
                    .subtract(player.getLookAngle().scale(0.45D))
                    .add(0.0D, 0.95D, 0.0D);
            level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, WIND_COLOR),
                    back.x, back.y, back.z, 5, 0.14D, 0.22D, 0.14D, 0.012D);
            level.sendParticles(ParticleTypes.CLOUD, back.x, back.y, back.z, 1, 0.12D, 0.18D, 0.12D, 0.004D);
        }, () -> {
            // 结束：卸速度（只有还占用修饰器时才卸）、只解除本技能的锁定、从此刻开始算冷却
            if (skillId.equals(DASH_OWNER.get(player.getUUID()))) {
                DASH_OWNER.remove(player.getUUID());
                AttributeInstance instance = player.getAttribute(Attributes.MOVEMENT_SPEED);
                if (instance != null) {
                    instance.removeModifier(DASH_SPEED_ID);
                }
            }
            PlayerMagicData current = MagicData.get(player);
            current.clearSkillLock(skillId);
            long cooldown = ctx.skill().cooldownSeconds(current.rank());
            current.setCooldown(skillId, level.getGameTime() + cooldown * 20L);
            MagicSync.sync(player);
        });
    }

    // ==================================================================
    // 中阶·风盘：龙卷 / 天罗 / 撕裂
    // ==================================================================

    /**
     * 龙卷：1×2×1 旋风，吸扯半径 2，持续 5 秒，每秒 9 点。
     */
    public static void tornado(SkillContext ctx) {
        cyclone(ctx, 1.0F, 2.0F, 2.0D, 5, 9.0D, false);
    }

    /**
     * 天罗：2×4×2 旋风，吸扯半径 4，持续 6 秒，每秒 10 点。
     */
    public static void skyNet(SkillContext ctx) {
        cyclone(ctx, 2.0F, 4.0F, 4.0D, 6, 10.0D, false);
    }

    /**
     * 撕裂：3×9×3 旋风，吸扯半径 8，持续 7 秒，每秒 15 点，期间随机露出星空裂口。
     */
    public static void teardown(SkillContext ctx) {
        cyclone(ctx, 3.0F, 9.0F, 8.0D, 7, 15.0D, true);
    }

    /**
     * 从手上射出一团青色光晕飞向鼠标位置，碰到实体方块或生物后在该处生成旋风柱。
     * 超过施法范围则什么也不生成（文档：技能消失）。
     */
    private static void cyclone(SkillContext ctx, float width, float height,
                                double radius, int seconds, double dps, boolean rift) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 from = ctx.origin();
        Vec3 target = ctx.targetPos();
        Vec3 delta = target.subtract(from);
        double distance = delta.length();

        // 目标点就在手边：直接生成
        if (distance < 0.8D) {
            spawnCyclone(player, level, target, width, height, radius, seconds, dps, rift);
            return;
        }

        Vec3 step = delta.normalize().scale(0.9D);
        int maxTicks = (int) Math.ceil(distance / 0.9D) + 2;
        Vec3 cursor = from;
        boolean[] done = {false};
        Vec3[] point = {cursor};

        MagicScheduler.every(level, 1, maxTicks, () -> {
            if (done[0]) {
                return;
            }
            Vec3 next = point[0].add(step);
            // 1. 先看生物（文档：碰到生物以生物 Y 轴生成）
            LivingEntity hit = firstEntity(level, player, next, 0.9D);
            if (hit != null) {
                done[0] = true;
                spawnCyclone(player, level, hit.position(), width, height, radius, seconds, dps, rift);
                return;
            }
            // 2. 再看实体方块
            if (level.getBlockState(BlockPos.containing(next)).isSolidRender()) {
                done[0] = true;
                spawnCyclone(player, level, next, width, height, radius, seconds, dps, rift);
                return;
            }
            point[0] = next;
            // 飞行中的青色光晕
            level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, WIND_COLOR),
                    next.x, next.y, next.z, 3, 0.04D, 0.04D, 0.04D, 0.0D);
            level.sendParticles(ParticleTypes.CLOUD, next.x, next.y, next.z, 1, 0.04D, 0.04D, 0.04D, 0.005D);
        }, () -> done[0] = true);
    }

    /** 生成旋风柱并登记到每 tick 驱动列表 */
    private static void spawnCyclone(ServerPlayer owner, ServerLevel level, Vec3 hit,
                                     float width, float height, double radius,
                                     int seconds, double dps, boolean rift) {
        Vec3 center = new Vec3(hit.x, hit.y + height / 2.0D, hit.z);
        FxBillboard display = DisplayFx.spawn(level, center, MagicItems.WIND_CYCLONE.get(),
                width, height, width, Display.BillboardConstraints.CENTER);
        if (display == null) {
            return;
        }
        long now = level.getGameTime();
        CYCLONES.add(new Cyclone(owner, level, display, center, radius, height, dps,
                now + seconds * 20L, now, rift, now));
        MagicDamage.playSound(level, center, SoundEvents.BREEZE_SHOOT, 1.0F, 0.75F);
        level.sendParticles(ParticleTypes.GUST, center.x, center.y - height / 2.0D + 0.2D, center.z,
                1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    // ==================================================================
    // 高阶·风之翼
    // ==================================================================

    /**
     * 主动：4 面青色透明盾环绕玩家旋转，护甲 +50、魔抗 +50，持续 30 秒，期间技能锁定，
     * 结束后进入冷却。被动部分（六翼 + 创造飞行）见 {@link #tickPlayer}。
     */
    public static void windWings(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        PlayerMagicData data = MagicData.get(player);
        long now = level.getGameTime();

        data.startWindWings();
        ensureWingsArmor(player);
        data.lockSkill(ctx.skill().id(), now + WINGS_TICKS);
        MagicSync.sync(player);

        MagicDamage.playSound(level, player.position(), SoundEvents.BREEZE_CHARGE, 1.0F, 1.1F);
        level.sendParticles(ParticleTypes.GUST_EMITTER_SMALL, player.getX(), player.getY() + 1.0D, player.getZ(),
                1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    /** 护甲 +50（buff 期间每 tick 检查，重生/换维度掉了会自动补） */
    private static void ensureWingsArmor(ServerPlayer player) {
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (armor == null || armor.getModifier(WINGS_ARMOR_ID) != null) {
            return;
        }
        armor.addTransientModifier(new AttributeModifier(WINGS_ARMOR_ID, 50.0D,
                AttributeModifier.Operation.ADD_VALUE));
    }

    /** 风之翼到期：卸护甲、解除锁定、开始冷却 */
    private static void endWindWings(ServerPlayer player) {
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(WINGS_ARMOR_ID);
        }
        PlayerMagicData data = MagicData.get(player);
        data.clearWindWings();
        data.clearSkillLock("wind_2_1");
        SkillDefinition skill = SkillRegistry.byId("wind_2_1");
        if (skill != null) {
            data.setCooldown("wind_2_1", player.level().getGameTime() + skill.cooldownSeconds(data.rank()) * 20L);
        }
        MagicSync.sync(player);
    }

    // ==================================================================
    // 超阶·泯风千刃
    // ==================================================================

    /**
     * 32 个迷你旋风绕身旋转，有生物出现就逐个飞出追踪（每 0.5 秒一个），
     * 命中爆炸 500 点；目标死亡则飞回身边；32 个全部飞出后才进冷却。
     */
    public static void thousandBlades(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        PlayerMagicData data = MagicData.get(player);
        long now = level.getGameTime();

        // 已经有一个风暴就收掉重来
        BladeStorm previous = STORMS.remove(player.getUUID());
        if (previous != null) {
            previous.clear();
        }

        BladeStorm storm = new BladeStorm(player, level, ctx.range());
        for (int i = 0; i < 32; i++) {
            FxBillboard display = DisplayFx.spawn(level, orbitPosition(player, now, i),
                    MagicItems.WIND_CYCLONE.get(), 0.2F, 0.4F, 0.2F, Display.BillboardConstraints.CENTER);
            if (display != null) {
                storm.blades.add(new Blade(display));
            }
        }
        if (storm.blades.isEmpty()) {
            NoticeApi.send(player, Component.literal("泯风千刃生成失败").withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }
        STORMS.put(player.getUUID(), storm);
        // 技能锁定：直到 32 个旋风全部飞出（最长 30 秒，防止一直遇不到生物被永久锁定）
        data.lockSkill("wind_3_1", now + BLADES_MAX_TICKS);
        MagicDamage.playSound(level, player.position(), SoundEvents.BREEZE_INHALE, 1.0F, 0.7F);
    }

    /** 第 i 个风刃的环绕位置 */
    private static Vec3 orbitPosition(ServerPlayer player, long now, int index) {
        double angle = now * 0.12D + index * (Math.PI * 2.0D / 32.0D);
        double radius = 1.6D + (index % 3) * 0.15D;
        double y = player.getY() + 0.55D + (index % 4) * 0.28D;
        return new Vec3(player.getX() + Math.cos(angle) * radius, y, player.getZ() + Math.sin(angle) * radius);
    }

    // ==================================================================
    // 每 tick 驱动
    // ==================================================================

    /** 每玩家每 tick：飞行被动、风之翼 buff 与 4 面盾（MagicEvents.onPlayerTick 调用） */
    public static void tickPlayer(ServerPlayer player) {
        PlayerMagicData data = MagicData.get(player);
        tickFlight(player, data);
        if (data.windWingsActive()) {
            ensureWingsArmor(player);
            tickShields(player);
            if (data.tickWindWings()) {
                endWindWings(player);
            }
        } else if (SHIELDS.containsKey(player.getUUID())) {
            clearShields(player);
        }
    }

    /** 被动：学会风之翼就拥有创造模式飞行 */
    private static void tickFlight(ServerPlayer player, PlayerMagicData data) {
        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        boolean allowed = data.isLearned("wind_2_1");
        var abilities = player.getAbilities();
        if (allowed && !abilities.mayfly) {
            abilities.mayfly = true;
            player.onUpdateAbilities();
        } else if (!allowed && abilities.mayfly) {
            abilities.mayfly = false;
            abilities.flying = false;
            player.onUpdateAbilities();
        }
    }

    /**
     * 背后六翼说明：翅膀已改为<b>客户端渲染层</b>（{@code PlayerFxLayer}，画在玩家模型上，
     * 像披风一样贴身，不再用实体追位置），这里只保留 mayfly 飞行被动与主动风盾。
     */

    /** 4 面风盾绕玩家转（贴图是青色半透明盾，billboard 让它始终正对镜头） */
    private static void tickShields(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        UUID id = player.getUUID();
        List<FxBillboard> shields = SHIELDS.get(id);
        if (shields == null || shields.size() != 4) {
            if (shields != null) {
                shields.forEach(FxBillboard::discard);
            }
            shields = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                FxBillboard shield = DisplayFx.spawn(level, player.position(),
                        MagicItems.WIND_SHIELD.get(), 1.0F, 1.0F, 1.0F, Display.BillboardConstraints.CENTER);
                if (shield != null) {
                    shields.add(shield);
                }
            }
            SHIELDS.put(id, shields);
        }
        long now = level.getGameTime();
        for (int i = 0; i < shields.size(); i++) {
            double angle = now * 0.10D + i * (Math.PI / 2.0D);
            double y = player.getY() + 0.95D;
            shields.get(i).setPos(player.getX() + Math.cos(angle) * 1.15D, y,
                    player.getZ() + Math.sin(angle) * 1.15D);
        }
    }

    private static void clearShields(ServerPlayer player) {
        List<FxBillboard> shields = SHIELDS.remove(player.getUUID());
        if (shields != null) {
            shields.forEach(FxBillboard::discard);
        }
    }

    /** 服务端每 tick：旋风柱吸扯/伤害/撕裂 + 泯风千刃追踪（MagicEvents.onServerTick 调用） */
    public static void tickWorld() {
        long now = -1L;
        for (int i = CYCLONES.size() - 1; i >= 0; i--) {
            Cyclone cyclone = CYCLONES.get(i);
            if (!(cyclone.level instanceof ServerLevel)) {
                CYCLONES.remove(i);
                continue;
            }
            long time = cyclone.level.getGameTime();
            if (now < 0L) {
                now = time;
            }
            if (time >= cyclone.endTick || cyclone.display == null || !cyclone.display.isAlive()
                    || cyclone.owner.isRemoved()) {
                cyclone.display.discard();
                CYCLONES.remove(i);
                continue;
            }
            tickCyclone(cyclone, time);
        }
        tickStorms();
    }

    private static void tickCyclone(Cyclone cyclone, long now) {
        ServerLevel level = cyclone.level;
        AABB area = new AABB(cyclone.center, cyclone.center)
                .inflate(cyclone.radius, cyclone.height / 2.0D, cyclone.radius);

        // 吸扯生物（每秒结算一次伤害）
        boolean damaging = now >= cyclone.nextDamageTick;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (entity == cyclone.owner || !entity.isAlive()) {
                continue;
            }
            pull(cyclone.center, entity);
            if (damaging) {
                MagicDamage.magicDamage(cyclone.owner, entity, cyclone.dps, MagicElement.WIND);
            }
        }
        // 吸扯掉落物
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area)) {
            Vec3 delta = cyclone.center.subtract(item.position());
            Vec3 horizontal = new Vec3(delta.x, 0.0D, delta.z);
            if (horizontal.lengthSqr() > 0.04D) {
                item.setDeltaMovement(item.getDeltaMovement().add(horizontal.normalize().scale(0.09D)));
                item.hurtMarked = true;
            }
        }
        if (damaging) {
            cyclone.nextDamageTick = now + 20L;
        }

        // 撕裂：随机在旋风里露出星空裂口
        if (cyclone.rift && now >= cyclone.nextRiftTick) {
            cyclone.nextRiftTick = now + 6L;
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
            double distance = cyclone.radius * (0.25D + level.getRandom().nextDouble() * 0.7D);
            double height = 0.4D + level.getRandom().nextDouble() * Math.max(0.5D, cyclone.height - 1.0D);
            Vec3 gap = new Vec3(
                    cyclone.center.x + Math.cos(angle) * distance,
                    cyclone.center.y - cyclone.height / 2.0D + height,
                    cyclone.center.z + Math.sin(angle) * distance);
            float scale = 0.8F + level.getRandom().nextFloat() * 0.9F;
            FxBillboard rift = DisplayFx.spawn(level, gap, MagicItems.WIND_RIFT.get(),
                    scale, scale, scale, Display.BillboardConstraints.CENTER);
            if (rift != null) {
                MagicScheduler.later(level, 8, rift::discard);
            }
        }

        // 底部风圈粒子
        if (now % 4L == 0L) {
            level.sendParticles(ParticleTypes.SMALL_GUST,
                    cyclone.center.x, cyclone.center.y - cyclone.height / 2.0D + 0.15D, cyclone.center.z,
                    1, cyclone.radius * 0.5D, 0.05D, cyclone.radius * 0.5D, 0.0D);
        }
    }

    /** 把生物往旋风中心拉（限制只在速度不大时加，避免越吸越快飞出去） */
    private static void pull(Vec3 center, LivingEntity entity) {
        Vec3 delta = center.subtract(entity.position());
        Vec3 horizontal = new Vec3(delta.x, 0.0D, delta.z);
        if (horizontal.lengthSqr() < 0.09D) {
            return;
        }
        Vec3 velocity = entity.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() > 0.49D) {
            return;
        }
        entity.setDeltaMovement(velocity.add(horizontal.normalize().scale(0.06D)).add(0.0D, 0.045D, 0.0D));
        entity.hurtMarked = true;
    }

    /** 驱动所有泯风千刃风暴 */
    private static void tickStorms() {
        if (STORMS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, BladeStorm>> iterator = STORMS.entrySet().iterator();
        while (iterator.hasNext()) {
            BladeStorm storm = iterator.next().getValue();
            ServerPlayer owner = storm.owner;
            ServerLevel level = storm.level;
            if (owner.isRemoved() || level.getServer() == null) {
                storm.clear();
                iterator.remove();
                continue;
            }
            long now = level.getGameTime();
            boolean timeout = now - storm.startTick > BLADES_MAX_TICKS;

            for (int index = 0; index < storm.blades.size(); index++) {
                Blade blade = storm.blades.get(index);
                if (blade.state == Blade.DEAD) {
                    continue;
                }
                if (!blade.display.isAlive()) {
                    blade.state = Blade.DEAD;
                    continue;
                }
                if (blade.state == Blade.ORBIT) {
                    Vec3 orbit = orbitPosition(owner, now, index);
                    blade.display.setPos(orbit.x, orbit.y, orbit.z);
                } else if (blade.state == Blade.FLYING) {
                    LivingEntity target = blade.target;
                    if (target == null || !target.isAlive() || target.level() != level) {
                        // 目标没了：飞回玩家身边
                        blade.state = Blade.RETURNING;
                        blade.target = null;
                        continue;
                    }
                    Vec3 aim = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
                    Vec3 pos = blade.display.position();
                    Vec3 delta = aim.subtract(pos);
                    if (delta.length() < 1.1D) {
                        // 命中爆炸
                        level.sendParticles(ParticleTypes.GUST_EMITTER_SMALL, aim.x, aim.y, aim.z,
                                1, 0.0D, 0.0D, 0.0D, 0.0D);
                        MagicDamage.playSound(level, aim, SoundEvents.BREEZE_DEFLECT, 1.0F, 1.0F);
                        MagicDamage.magicDamage(owner, target, BLADES_DAMAGE, MagicElement.WIND);
                        blade.display.discard();
                        blade.state = Blade.DEAD;
                    } else {
                        Vec3 next = pos.add(delta.normalize().scale(0.9D));
                        blade.display.setPos(next.x, next.y, next.z);
                        level.sendParticles(ParticleTypes.CLOUD, next.x, next.y, next.z, 1,
                                0.05D, 0.05D, 0.05D, 0.005D);
                    }
                } else if (blade.state == Blade.RETURNING) {
                    Vec3 home = owner.position().add(0.0D, 1.0D, 0.0D);
                    Vec3 delta = home.subtract(blade.display.position());
                    if (delta.length() < 1.8D) {
                        blade.state = Blade.ORBIT;
                    } else {
                        Vec3 next = blade.display.position().add(delta.normalize().scale(0.9D));
                        blade.display.setPos(next.x, next.y, next.z);
                    }
                }
            }

            // 发射：每 0.25 秒放一个去追最近的合法目标（敌对生物或玩家攻击过的生物）
            if (!timeout && now >= storm.nextLaunchTick) {
                // 索敌半径按施法时的阶级范围，升阶后能追到更远的生物
                LivingEntity target = nearestTarget(level, owner, storm.range);
                Blade free = storm.firstOrbiting();
                if (target != null && free != null) {
                    free.state = Blade.FLYING;
                    free.target = target;
                    storm.nextLaunchTick = now + BLADES_LAUNCH_INTERVAL;
                    MagicDamage.playSound(level, owner.position(), SoundEvents.BREEZE_SHOOT, 0.7F, 1.7F);
                }
            }

            boolean allDead = true;
            for (Blade blade : storm.blades) {
                if (blade.state != Blade.DEAD) {
                    allDead = false;
                    break;
                }
            }
            if (allDead || timeout) {
                storm.clear();
                iterator.remove();
                finishBlades(owner, level);
            }
        }
    }

    /** 泯风千刃收尾：解除锁定、开始冷却 */
    private static void finishBlades(ServerPlayer owner, ServerLevel level) {
        PlayerMagicData data = MagicData.get(owner);
        data.endSuperRing("wind_3_1");
        data.clearSkillLock("wind_3_1");
        SkillDefinition skill = SkillRegistry.byId("wind_3_1");
        if (skill != null) {
            data.setCooldown("wind_3_1", level.getGameTime() + skill.cooldownSeconds(data.rank()) * 20L);
        }
        MagicSync.sync(owner);
    }

    /**
     * 视野内最近的合法目标（敌对生物或玩家攻击过的生物，不含自己/旁观/已死）。
     * 青牛等被动生物只有在被玩家打过之后才会被泯风千刃追击。
     */
    private static @Nullable LivingEntity nearestTarget(ServerLevel level, ServerPlayer player, double range) {
        AABB area = player.getBoundingBox().inflate(range);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, area)) {
            // PVP 感知：PVP 开启时其他玩家也可被泯风千刃追踪
            if (!CombatTracker.isEnemyTarget(player, candidate)) {
                continue;
            }
            double distance = candidate.distanceToSqr(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** 飞行中的光晕前端碰到生物（用于风盘投射物） */
    private static @Nullable LivingEntity firstEntity(ServerLevel level, ServerPlayer player, Vec3 point, double radius) {
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(point, point).inflate(radius))) {
            if (candidate == player || !candidate.isAlive() || candidate.isSpectator()) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /** 玩家下线：收掉盾、进行中的风暴（旋风柱按自己的生命周期结束；翅膀是渲染层无需清理） */
    public static void removePlayer(ServerPlayer player) {
        clearShields(player);
        BladeStorm storm = STORMS.remove(player.getUUID());
        if (storm != null) {
            storm.clear();
        }
        PlayerMagicData data = MagicData.get(player);
        data.clearWindWings();
        data.clearSkillLock();
        DASH_OWNER.remove(player.getUUID());
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(DASH_SPEED_ID);
        }
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(WINGS_ARMOR_ID);
        }
    }

    /** 服务器关闭：清空全部运行时状态 */
    public static void clearAll() {
        DASH_OWNER.clear();
        SHIELDS.values().forEach(list -> list.forEach(FxBillboard::discard));
        SHIELDS.clear();
        for (Cyclone cyclone : CYCLONES) {
            cyclone.display.discard();
        }
        CYCLONES.clear();
        for (BladeStorm storm : STORMS.values()) {
            storm.clear();
        }
        STORMS.clear();
    }

    // ==================================================================
    // 数据结构
    // ==================================================================

    /** 一根旋风柱 */
    private static final class Cyclone {
        final ServerPlayer owner;
        final ServerLevel level;
        final FxBillboard display;
        final Vec3 center;
        final double radius;
        final float height;
        final double dps;
        final long endTick;
        final boolean rift;
        long nextDamageTick;
        long nextRiftTick;

        Cyclone(ServerPlayer owner, ServerLevel level, FxBillboard display, Vec3 center,
                double radius, float height, double dps, long endTick, long nextDamageTick,
                boolean rift, long nextRiftTick) {
            this.owner = owner;
            this.level = level;
            this.display = display;
            this.center = center;
            this.radius = radius;
            this.height = height;
            this.dps = dps;
            this.endTick = endTick;
            this.nextDamageTick = nextDamageTick;
            this.rift = rift;
            this.nextRiftTick = nextRiftTick;
        }
    }

    /** 泯风千刃里的一个风刃 */
    private static final class Blade {
        static final int ORBIT = 0;
        static final int FLYING = 1;
        static final int RETURNING = 2;
        static final int DEAD = 3;

        final FxBillboard display;
        int state = ORBIT;
        @Nullable LivingEntity target;

        Blade(FxBillboard display) {
            this.display = display;
        }
    }

    /** 一次泯风千刃的完整状态 */
    private static final class BladeStorm {
        final ServerPlayer owner;
        final ServerLevel level;
        final List<Blade> blades = new ArrayList<>(32);
        final long startTick;
        /** 施法时的阶级范围，风刃索敌半径跟着升阶变大 */
        final double range;
        long nextLaunchTick;

        BladeStorm(ServerPlayer owner, ServerLevel level, double range) {
            this.owner = owner;
            this.level = level;
            this.range = range;
            this.startTick = level.getGameTime();
            this.nextLaunchTick = this.startTick;
        }

        @Nullable
        Blade firstOrbiting() {
            for (Blade blade : blades) {
                if (blade.state == Blade.ORBIT) {
                    return blade;
                }
            }
            return null;
        }

        void clear() {
            for (Blade blade : blades) {
                if (blade.display.isAlive()) {
                    blade.display.discard();
                }
            }
            blades.clear();
        }
    }
}
