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
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 水系全部 8 个技能（需求文档“水系”段）。
 *
 * <ul>
 *   <li><b>水御</b>（初阶）：
 *     <ul>
 *       <li>化解：对准生物释放，解除其全部状态效果（牛奶效果），蓝白拖尾；按住 Ctrl 释放时对自己生效。</li>
 *       <li>守护：给目标生成 1 面水盾，可抵挡 1 次魔法攻击（挡不住物理），可无限叠加、永久存在。</li>
 *       <li>循环：给目标生成 4 面环身水盾，30 秒内抵挡任意魔法攻击，期间技能锁定，结束后进冷却。</li>
 *     </ul>
 *   </li>
 *   <li><b>爆浪</b>（中阶）：驱逐 / 惊涛 / 洪卷 —— 贴地推进的浪墙（3×2 / 6×3）撞人推飞并造成 30 / 60 伤害；
 *       洪卷是 3×9 水龙卷，边推边把生物吸向中心，每秒 30 伤害，到极限范围停顿 1 秒消失。</li>
 *   <li><b>水花天幕</b>（高阶）：身前 3 格立起 10×10 水墙，30 秒内替自己吸收魔法伤害、清掉附近弹射物，
 *       范围内的其他生物每秒受到 100 伤害；期间技能锁定，结束后进冷却。</li>
 *   <li><b>水之猎刃</b>（超阶）：8 把水弯刀绕身旋转，发现生物就同时飞出（可分散也可集中打同一目标），
 *       每次命中 500 伤害，命中后飞回身边继续下一轮，直到周围没有生物；最长 1 分钟，结束后进冷却。</li>
 * </ul>
 *
 * <p>水盾拦截魔法伤害的入口在 {@link MagicDamage#magicDamage} 里调 {@link #tryBlockMagic}，
 * 所以本系与其他 17 系的所有技能伤害都会被水盾吸掉，而原版物理伤害不会（文档要求）。</p>
 */
public final class WaterMagic {

    /** 水系代表色（粒子与提示文字） */
    private static final int WATER_COLOR = MagicElement.WATER.color();

    /** 循环水盾持续 tick（30 秒） */
    private static final int CYCLE_TICKS = 30 * 20;
    /** 水花天幕持续 tick（30 秒） */
    private static final int VEIL_TICKS = 30 * 20;
    /** 水之猎刃最长持续 tick（60 秒） */
    private static final int HUNT_TICKS = 60 * 20;
    /** 水花天幕每秒伤害 */
    private static final double VEIL_DPS = 100.0D;
    /** 水花天幕效果半径（格） */
    private static final double VEIL_RADIUS = 3.0D;
    /** 水之猎刃单次伤害 */
    private static final double HUNT_DAMAGE = 500.0D;
    /** 水之猎刃搜索半径不再写死：施法时按阶级范围存进 Hunt（初阶 10 / 中阶 15 / 高阶 20 / 超阶 30） */

    /** 生物 UUID -> 身上的水盾（守护的可叠加盾 + 循环的 4 面盾） */
    private static final Map<UUID, ShieldSet> SHIELDS = new HashMap<>();
    /** 施法者 UUID -> 水花天幕 */
    private static final Map<UUID, Veil> VEILS = new HashMap<>();
    /** 施法者 UUID -> 水之猎刃 */
    private static final Map<UUID, Hunt> HUNTS = new HashMap<>();
    /** 进行中的波浪 / 水龙卷 */
    private static final List<Wave> WAVES = new ArrayList<>();

    private WaterMagic() {
    }

    // ==================================================================
    // 初阶·水御：化解 / 守护 / 循环
    // ==================================================================

    /** 化解：解除目标身上的全部状态效果（牛奶效果），蓝白色光晕拖尾 */
    public static void dissolve(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        LivingEntity target = ctx.targetEntity();
        // 统一规则：按住 Ctrl 释放 = 对自己生效；否则对视线上的生物生效
        if (target == null && ctx.ctrlDown()) {
            target = player;
        }
        if (target == null) {
            failCast(ctx, "化解需要对准生物释放 (按住 Ctrl 可解除自身效果)");
            return;
        }
        // 蓝白色光晕拖尾
        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        MagicDamage.line(level, ctx.origin(), hit, waterParticle(), 0.28D);
        MagicDamage.playSound(level, hit, SoundEvents.BUCKET_EMPTY, 1.0F, 1.3F);

        List<MobEffectInstance> effects = new ArrayList<>(target.getActiveEffects());
        for (MobEffectInstance effect : effects) {
            target.removeEffect(effect.getEffect());
        }
        level.sendParticles(ParticleTypes.SPLASH, hit.x, hit.y, hit.z, 18, 0.4D, 0.5D, 0.4D, 0.05D);
        level.sendParticles(waterParticle(), hit.x, hit.y, hit.z, 14, 0.35D, 0.5D, 0.35D, 0.02D);
    }

    /** 守护：给目标加 1 面水盾，可抵挡 1 次魔法攻击，永久存在、可叠加 */
    public static void guard(SkillContext ctx) {
        LivingEntity target = ctx.targetEntity();
        // 按住 Ctrl 释放 = 给自己套盾；否则对视线上的生物
        if (target == null && ctx.ctrlDown()) {
            target = ctx.player();
        }
        if (target == null) {
            failCast(ctx, "守护需要对准生物释放 (按住 Ctrl 可给自己套盾)");
            return;
        }
        waterTrail(ctx, target);
        LivingEntity host = target;
        ShieldSet set = SHIELDS.computeIfAbsent(host.getUUID(),
                key -> new ShieldSet(host, ctx.level()));
        set.guardCharges++;
        set.syncGuardDisplays();
        MagicDamage.playSound(ctx.level(), target.position(), SoundEvents.BUCKET_FILL, 1.0F, 1.4F);
    }

    /** 循环：给目标加 4 面环身水盾，30 秒内抵挡任意魔法攻击；期间技能锁定，结束后进冷却 */
    public static void cycle(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        LivingEntity target = ctx.targetEntity();
        // 按住 Ctrl 释放 = 给自己套盾；否则对视线上的生物
        if (target == null && ctx.ctrlDown()) {
            target = player;
        }
        if (target == null) {
            failCast(ctx, "循环需要对准生物释放 (按住 Ctrl 可给自己套盾)");
            return;
        }
        waterTrail(ctx, target);
        LivingEntity host = target;
        ShieldSet set = SHIELDS.computeIfAbsent(host.getUUID(), key -> new ShieldSet(host, level));
        set.cycleUntil = level.getGameTime() + CYCLE_TICKS;
        set.syncCycleDisplays();
        MagicDamage.playSound(level, target.position(), SoundEvents.BUCKET_FILL_FISH, 1.0F, 1.2F);

        String skillId = ctx.skill().id();
        PlayerMagicData data = MagicData.get(player);
        data.lockSkill(skillId, level.getGameTime() + CYCLE_TICKS);
        MagicSync.sync(player);

        // 30 秒后解除锁定并进冷却
        MagicScheduler.later(level, CYCLE_TICKS, () -> {
            PlayerMagicData current = MagicData.get(player);
            current.clearSkillLock(skillId);
            SkillDefinition skill = SkillRegistry.byId(skillId);
            if (skill != null) {
                current.setCooldown(skillId,
                        level.getGameTime() + skill.cooldownSeconds(current.rank()) * 20L);
            }
            MagicSync.sync(player);
        });
    }

    // ==================================================================
    // 中阶·爆浪：驱逐 / 惊涛 / 洪卷
    // ==================================================================

    /** 驱逐：3×2×1 浪墙贴地推进，撞人推飞并造成 30 伤害；最远推进 = 当前阶级施法范围 */
    public static void expel(SkillContext ctx) {
        wave(ctx, 3.0F, 2.0F, 0.55D, 30.0D, false, ctx.range());
    }

    /** 惊涛：6×3×1 浪墙贴地推进，撞人推飞并造成 60 伤害；最远推进 = 当前阶级施法范围 */
    public static void surge(SkillContext ctx) {
        wave(ctx, 6.0F, 3.0F, 0.55D, 60.0D, false, ctx.range());
    }

    /** 洪卷：3×9×3 水龙卷贴地推进，推飞 + 吸扯到中心，每秒 30 伤害，到极限停顿 1 秒后消失 */
    public static void flood(SkillContext ctx) {
        wave(ctx, 3.0F, 9.0F, 0.42D, 30.0D, true, ctx.range());
    }

    /**
     * 统一波浪实现：从玩家身前 1.5 格贴地生成，沿水平朝向匀速推进。
     *
     * @param width    浪墙宽度（格）
     * @param height   浪墙高度（格）
     * @param speed    每 tick 前进格数
     * @param damage   单次伤害（洪卷是每秒伤害）
     * @param tornado  true = 水龙卷（吸扯 + 持续伤害 + 到点停顿 1 秒）
     * @param maxRange 最远前进距离（格），调用方传 {@code ctx.range()}——升阶后波浪走得更远
     */
    private static void wave(SkillContext ctx, float width, float height, double speed,
                             double damage, boolean tornado, double maxRange) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        flat = flat.normalize();

        Vec3 start = GroundDecal.groundBelow(level,
                player.position().add(flat.scale(1.5D)).add(0.0D, 1.0D, 0.0D), 4);
        // 朝向：面片默认法线朝 +Z，绕 Y 转 atan2(dx,dz) 后正面就朝着推进方向
        double yaw = Math.atan2(flat.x, flat.z);
        FxBillboard display = DisplayFx.spawn(level,
                new Vec3(start.x, start.y + height / 2.0D, start.z),
                tornado ? MagicItems.WATER_TORNADO.get() : MagicItems.WATER_WAVE.get(),
                width, height, width, Display.BillboardConstraints.FIXED,
                DisplayFx.yawRotation(yaw));
        if (display == null) {
            return;
        }
        long now = level.getGameTime();
        WAVES.add(new Wave(player, level, display, start, flat, speed, damage,
                tornado, maxRange, now, now, width, height));
        MagicDamage.playSound(level, start, SoundEvents.BUCKET_EMPTY, 1.0F, 0.8F);
        level.sendParticles(ParticleTypes.SPLASH, start.x, start.y + 0.3D, start.z,
                24, width / 2.0D, 0.4D, width / 2.0D, 0.12D);
    }

    // ==================================================================
    // 高阶·水花天幕
    // ==================================================================

    /** 水花天幕：身前 3 格立起 10×10 水墙，30 秒内吸收魔法伤害、清弹射物、灼烧范围内其他生物 */
    public static void skyVeil(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();

        // 已有天幕先收掉
        Veil previous = VEILS.remove(player.getUUID());
        if (previous != null) {
            previous.clear();
        }

        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        flat = flat.normalize();
        Vec3 base = GroundDecal.groundBelow(level, player.position().add(flat.scale(3.0D)).add(0.0D, 1.2D, 0.0D), 4);
        Vec3 center = new Vec3(base.x, base.y + 5.0D, base.z);
        double yaw = Math.atan2(flat.x, flat.z);

        FxBillboard wall = DisplayFx.spawn(level, center, MagicItems.WATER_WALL.get(),
                10.0F, 10.0F, 1.0F, Display.BillboardConstraints.FIXED, DisplayFx.yawRotation(yaw));
        if (wall == null) {
            failCast(ctx, "水花天幕生成失败");
            return;
        }
        Veil veil = new Veil(player, level, wall, center, now + VEIL_TICKS, now);
        VEILS.put(player.getUUID(), veil);

        String skillId = ctx.skill().id();
        PlayerMagicData data = MagicData.get(player);
        data.lockSkill(skillId, now + VEIL_TICKS);
        MagicSync.sync(player);
        MagicDamage.playSound(level, center, SoundEvents.BUCKET_EMPTY, 1.0F, 0.7F);
    }

    // ==================================================================
    // 超阶·水之猎刃
    // ==================================================================

    /** 水之猎刃：8 把水弯刀绕身，有生物就同时飞出，每次 500 伤害，命中飞回，循环到没有生物 */
    public static void huntBlades(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();

        Hunt previous = HUNTS.remove(player.getUUID());
        if (previous != null) {
            previous.clear();
        }
        Hunt hunt = new Hunt(player, level, now, ctx.range());
        for (int i = 0; i < 8; i++) {
            FxBillboard display = DisplayFx.spawn(level, orbitPoint(player, now, i),
                    MagicItems.WATER_BLADE.get(), 0.8F, 1.6F, 0.8F, Display.BillboardConstraints.CENTER);
            if (display != null) {
                hunt.blades.add(new Blade(display));
            }
        }
        if (hunt.blades.isEmpty()) {
            failCast(ctx, "水之猎刃生成失败");
            return;
        }
        HUNTS.put(player.getUUID(), hunt);
        String skillId = ctx.skill().id();
        MagicData.get(player).lockSkill(skillId, now + HUNT_TICKS);
        MagicSync.sync(player);
        MagicDamage.playSound(level, player.position(), SoundEvents.BUCKET_FILL, 1.0F, 1.1F);
    }

    /** 第 i 把弯刀的环绕位置 */
    private static Vec3 orbitPoint(ServerPlayer player, long now, int index) {
        double angle = now * 0.16D + index * (Math.PI * 2.0D / 8.0D);
        double radius = 1.5D + (index % 2) * 0.25D;
        double y = player.getY() + 0.75D + (index % 4) * 0.3D;
        return new Vec3(player.getX() + Math.cos(angle) * radius, y, player.getZ() + Math.sin(angle) * radius);
    }

    // ==================================================================
    // 水盾拦截（MagicDamage 调用）
    // ==================================================================

    /**
     * 水盾 / 水花天幕拦截一次魔法伤害。
     *
     * <p>由 {@link MagicDamage#magicDamage} 在结算前调用：命中就整体无效（不扣血、不弹数字）。
     * 守护盾抵挡 1 次后消失；循环盾在 30 秒内无限挡；水花天幕期间施法者免疫魔法伤害。</p>
     *
     * @return true = 这次魔法伤害被吸收
     */
    public static boolean tryBlockMagic(LivingEntity target) {
        if (!(target.level() instanceof ServerLevel level)) {
            return false;
        }
        long now = level.getGameTime();

        // 水花天幕：罩住施法者
        Veil veil = VEILS.get(target.getUUID());
        if (veil != null && veil.owner == target && now < veil.endTick) {
            splashShield(level, target.position().add(0.0D, target.getBbHeight() * 0.7D, 0.0D), 2.0D);
            return true;
        }

        ShieldSet set = SHIELDS.get(target.getUUID());
        if (set == null || set.entity != target) {
            return false;
        }
        Vec3 point = target.position().add(0.0D, target.getBbHeight() * 0.65D, 0.0D);
        if (set.cycleUntil > now) {
            // 循环盾：挡任意次
            splashShield(level, point, 1.2D);
            MagicDamage.playSound(level, point, SoundEvents.BUCKET_EMPTY, 0.7F, 1.6F);
            return true;
        }
        if (set.guardCharges > 0) {
            // 守护盾：挡 1 次后碎掉
            set.guardCharges--;
            set.syncGuardDisplays();
            splashShield(level, point, 1.4D);
            MagicDamage.playSound(level, point, SoundEvents.BUCKET_EMPTY_FISH, 0.9F, 1.5F);
            return true;
        }
        return false;
    }

    /** 水花天幕是否正在保护该玩家（MagicEvents 的伤害拦截会用到） */
    public static boolean hasVeil(ServerPlayer player) {
        Veil veil = VEILS.get(player.getUUID());
        if (veil == null || veil.owner != player) {
            return false;
        }
        return player.level().getGameTime() < veil.endTick;
    }

    /** 水盾被击碎/挡下的水花特效 */
    private static void splashShield(ServerLevel level, Vec3 point, double spread) {
        level.sendParticles(ParticleTypes.SPLASH, point.x, point.y, point.z,
                22, spread, spread, spread, 0.12D);
        level.sendParticles(waterParticle(), point.x, point.y, point.z,
                12, spread, spread, spread, 0.04D);
    }

    // ==================================================================
    // 每 tick 驱动
    // ==================================================================

    /** 服务端每 tick：水盾跟随/到期、波浪推进、水花天幕、水之猎刃（MagicEvents.onServerTick） */
    public static void tickWorld() {
        tickShields();
        tickWaves();
        tickVeils();
        tickHunts();
    }

    /** 盾跟着宿主走，循环盾 30 秒到期消失 */
    private static void tickShields() {
        if (SHIELDS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, ShieldSet>> iterator = SHIELDS.entrySet().iterator();
        while (iterator.hasNext()) {
            ShieldSet set = iterator.next().getValue();
            LivingEntity host = set.entity;
            if (host.isRemoved() || !host.isAlive() || !(host.level() instanceof ServerLevel level)) {
                set.clear();
                iterator.remove();
                continue;
            }
            long now = level.getGameTime();
            if (set.cycleUntil > 0L && now >= set.cycleUntil) {
                set.cycleUntil = 0L;
                set.clearCycleDisplays();
            }
            set.tick(level, now);
            if (set.isEmpty()) {
                iterator.remove();
            }
        }
    }

    /** 波浪/水龙卷：推进、推飞、伤害、吸扯、到点消失 */
    private static void tickWaves() {
        for (int i = WAVES.size() - 1; i >= 0; i--) {
            Wave wave = WAVES.get(i);
            ServerLevel level = wave.level;
            long now = level.getGameTime();
            if (wave.display == null || !wave.display.isAlive() || wave.owner.isRemoved()) {
                wave.display.discard();
                WAVES.remove(i);
                continue;
            }
            // 洪卷到极限范围：停顿 1 秒再消失
            if (wave.pauseUntil > 0L) {
                if (now >= wave.pauseUntil) {
                    wave.display.discard();
                    WAVES.remove(i);
                }
                continue;
            }
            wave.distance += wave.speed;
            if (wave.distance > wave.maxRange) {
                if (wave.tornado) {
                    // 洪卷：到极限范围原地转 1 秒再消失
                    wave.distance = wave.maxRange;
                    wave.pauseUntil = now + 20L;
                    Vec3 stay = wave.start.add(wave.dir.scale(wave.maxRange));
                    wave.display.setPos(stay.x, wave.start.y + wave.height / 2.0D, stay.z);
                    continue;
                }
                wave.display.discard();
                WAVES.remove(i);
                continue;
            }
            Vec3 pos = wave.start.add(wave.dir.scale(wave.distance));
            pos = new Vec3(pos.x, wave.start.y + wave.height / 2.0D, pos.z);
            wave.display.setPos(pos.x, pos.y, pos.z);

            double halfWidth = wave.width / 2.0D + 0.6D;
            AABB area = new AABB(pos.x - halfWidth, wave.start.y - 0.4D, pos.z - halfWidth,
                    pos.x + halfWidth, wave.start.y + wave.height + 0.4D, pos.z + halfWidth);
            boolean damageRound = now >= wave.nextDamageTick;
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                if (entity == wave.owner || !entity.isAlive()) {
                    continue;
                }
                if (wave.tornado) {
                    pullToCenter(pos, entity);
                    if (damageRound) {
                        MagicDamage.magicDamage(wave.owner, entity, wave.damage, MagicElement.WATER);
                    }
                } else if (wave.hitOnce.add(entity.getUUID())) {
                    // 浪墙：推飞 + 一次性伤害
                    Vec3 push = wave.dir.scale(0.85D).add(0.0D, 0.35D, 0.0D);
                    entity.setDeltaMovement(entity.getDeltaMovement().add(push));
                    entity.hurtMarked = true;
                    MagicDamage.magicDamage(wave.owner, entity, wave.damage, MagicElement.WATER);
                }
            }
            if (damageRound) {
                wave.nextDamageTick = now + 20L;
            }
            // 浪花粒子
            level.sendParticles(ParticleTypes.SPLASH, pos.x, wave.start.y + 0.35D, pos.z,
                    wave.tornado ? 16 : 10, halfWidth * 0.7D, 0.3D, halfWidth * 0.7D, 0.08D);
            if (now % 2L == 0L) {
                level.sendParticles(waterParticle(), pos.x, pos.y, pos.z,
                        4, halfWidth * 0.5D, wave.height * 0.3D, halfWidth * 0.5D, 0.01D);
            }
        }
    }

    /** 把生物往水龙卷中心拉 */
    private static void pullToCenter(Vec3 center, LivingEntity entity) {
        Vec3 delta = center.subtract(entity.position());
        Vec3 horizontal = new Vec3(delta.x, 0.0D, delta.z);
        if (horizontal.lengthSqr() < 0.09D) {
            return;
        }
        Vec3 velocity = entity.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() > 0.49D) {
            return;
        }
        entity.setDeltaMovement(velocity.add(horizontal.normalize().scale(0.07D)).add(0.0D, 0.03D, 0.0D));
        entity.hurtMarked = true;
    }

    /** 水花天幕：清理弹射物、灼烧范围内的其他生物、到期收尾 */
    private static void tickVeils() {
        if (VEILS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Veil>> iterator = VEILS.entrySet().iterator();
        while (iterator.hasNext()) {
            Veil veil = iterator.next().getValue();
            ServerLevel level = veil.level;
            long now = level.getGameTime();
            if (veil.owner.isRemoved() || !veil.wall.isAlive()) {
                veil.clear();
                iterator.remove();
                continue;
            }
            if (now >= veil.endTick) {
                veil.clear();
                iterator.remove();
                finishVeil(veil.owner, level);
                continue;
            }
            AABB area = new AABB(veil.center, veil.center).inflate(VEIL_RADIUS);
            // 清掉范围内的弹射物（箭、雪球、火球等）
            for (Projectile projectile : level.getEntitiesOfClass(Projectile.class, area)) {
                if (projectile.getOwner() == veil.owner) {
                    continue;
                }
                level.sendParticles(ParticleTypes.SPLASH, projectile.getX(), projectile.getY(), projectile.getZ(),
                        6, 0.15D, 0.15D, 0.15D, 0.05D);
                projectile.discard();
            }
            // 范围内的其他生物每秒 100
            if (now >= veil.nextDamageTick) {
                veil.nextDamageTick = now + 20L;
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                    if (entity == veil.owner || !entity.isAlive() || entity.isSpectator()) {
                        continue;
                    }
                    MagicDamage.magicDamage(veil.owner, entity, VEIL_DPS, MagicElement.WATER);
                }
            }
            // 水幕下落的粒子
            if (now % 3L == 0L) {
                level.sendParticles(ParticleTypes.FALLING_WATER,
                        veil.center.x, veil.center.y + 4.2D, veil.center.z,
                        8, 4.6D, 0.1D, 0.5D, 0.0D);
            }
        }
    }

    /** 水花天幕结束：解除锁定额、进冷却 */
    private static void finishVeil(ServerPlayer owner, ServerLevel level) {
        String skillId = "water_2_1";
        PlayerMagicData data = MagicData.get(owner);
        data.clearSkillLock(skillId);
        SkillDefinition skill = SkillRegistry.byId(skillId);
        if (skill != null) {
            data.setCooldown(skillId, level.getGameTime() + skill.cooldownSeconds(data.rank()) * 20L);
        }
        MagicSync.sync(owner);
    }

    /** 水之猎刃：环绕 → 同时飞出 → 命中飞回 → 再出发，1 分钟结束 */
    private static void tickHunts() {
        if (HUNTS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Hunt>> iterator = HUNTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Hunt hunt = iterator.next().getValue();
            ServerPlayer owner = hunt.owner;
            ServerLevel level = hunt.level;
            if (owner.isRemoved() || level.getServer() == null) {
                hunt.clear();
                iterator.remove();
                continue;
            }
            long now = level.getGameTime();
            if (now - hunt.startTick > HUNT_TICKS) {
                hunt.clear();
                iterator.remove();
                finishHunt(owner, level);
                continue;
            }

            boolean allOrbiting = true;
            int alive = 0;
            for (int index = 0; index < hunt.blades.size(); index++) {
                Blade blade = hunt.blades.get(index);
                if (blade.state == Blade.DEAD) {
                    continue;
                }
                if (!blade.display.isAlive()) {
                    blade.state = Blade.DEAD;
                    continue;
                }
                alive++;
                if (blade.state == Blade.FLYING) {
                    allOrbiting = false;
                    tickFlyingBlade(owner, level, blade);
                } else if (blade.state == Blade.RETURNING) {
                    allOrbiting = false;
                    Vec3 home = orbitPoint(owner, now, index);
                    Vec3 delta = home.subtract(blade.display.position());
                    if (delta.length() < 0.7D) {
                        blade.state = Blade.ORBIT;
                        blade.target = null;
                    } else {
                        Vec3 next = blade.display.position().add(delta.normalize().scale(1.0D));
                        blade.display.setPos(next.x, next.y, next.z);
                    }
                } else {
                    Vec3 orbit = orbitPoint(owner, now, index);
                    blade.display.setPos(orbit.x, orbit.y, orbit.z);
                }
            }

            // 全部回到身边后才开始下一轮；有生物就 8 把同时飞出
            if (alive == 0) {
                hunt.clear();
                iterator.remove();
                finishHunt(owner, level);
                continue;
            }
            if (allOrbiting && now >= hunt.nextLaunchTick) {
                List<LivingEntity> targets = nearbyTargets(level, owner, hunt.range);
                if (!targets.isEmpty()) {
                    List<Blade> free = new ArrayList<>();
                    for (Blade blade : hunt.blades) {
                        if (blade.state == Blade.ORBIT) {
                            free.add(blade);
                        }
                    }
                    for (int i = 0; i < free.size(); i++) {
                        Blade blade = free.get(i);
                        blade.state = Blade.FLYING;
                        blade.target = targets.get(i % targets.size());
                        blade.lifeTicks = 0;
                    }
                    hunt.nextLaunchTick = now + 12L;
                    MagicDamage.playSound(level, owner.position(), SoundEvents.BUCKET_EMPTY, 0.9F, 1.5F);
                }
            }
        }
    }

    /** 飞行中的弯刀：追目标、命中结算 500 伤害并飞回 */
    private static void tickFlyingBlade(ServerPlayer owner, ServerLevel level, Blade blade) {
        blade.lifeTicks++;
        LivingEntity target = blade.target;
        // 目标死了/丢了/追太久：飞回
        if (target == null || !target.isAlive() || target.level() != level || blade.lifeTicks > 60) {
            blade.state = Blade.RETURNING;
            blade.target = null;
            return;
        }
        Vec3 aim = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        Vec3 pos = blade.display.position();
        Vec3 delta = aim.subtract(pos);
        if (delta.length() < 1.2D) {
            level.sendParticles(ParticleTypes.SPLASH, aim.x, aim.y, aim.z, 16, 0.3D, 0.3D, 0.3D, 0.1D);
            MagicDamage.playSound(level, aim, SoundEvents.BUCKET_EMPTY_FISH, 0.8F, 1.5F);
            MagicDamage.magicDamage(owner, target, HUNT_DAMAGE, MagicElement.WATER);
            blade.state = Blade.RETURNING;
            blade.target = null;
            return;
        }
        Vec3 next = pos.add(delta.normalize().scale(1.1D));
        blade.display.setPos(next.x, next.y, next.z);
        // 蓝白色光晕拖尾
        level.sendParticles(waterParticle(), next.x, next.y, next.z, 3, 0.08D, 0.08D, 0.08D, 0.01D);
        level.sendParticles(ParticleTypes.SPLASH, next.x, next.y, next.z, 1, 0.05D, 0.05D, 0.05D, 0.02D);
    }

    /** 水之猎刃结束：解除锁定、进冷却 */
    private static void finishHunt(ServerPlayer owner, ServerLevel level) {
        String skillId = "water_3_1";
        PlayerMagicData data = MagicData.get(owner);
        data.endSuperRing(skillId);
        data.clearSkillLock(skillId);
        SkillDefinition skill = SkillRegistry.byId(skillId);
        if (skill != null) {
            data.setCooldown(skillId, level.getGameTime() + skill.cooldownSeconds(data.rank()) * 20L);
        }
        MagicSync.sync(owner);
    }

    /**
     * 周围的合法目标（敌对生物或玩家攻击过的生物，不含自己/旁观/已死）。
     * 被动生物只有在被玩家打过之后才会被水之猎刃追击。
     */
    private static List<LivingEntity> nearbyTargets(ServerLevel level, ServerPlayer owner, double range) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                owner.getBoundingBox().inflate(range))) {
            // PVP 感知：PVP 开启时其他玩家也可被水之猎刃追踪
            if (!CombatTracker.isEnemyTarget(owner, candidate)) {
                continue;
            }
            result.add(candidate);
        }
        return result;
    }

    // ==================================================================
    // 清理
    // ==================================================================

    /** 玩家下线/死亡：收掉天幕与猎刃（水盾在宿主身上，按自己的生命周期结束） */
    public static void removePlayer(ServerPlayer player) {
        Veil veil = VEILS.remove(player.getUUID());
        if (veil != null) {
            veil.clear();
        }
        Hunt hunt = HUNTS.remove(player.getUUID());
        if (hunt != null) {
            hunt.clear();
        }
    }

    /** 服务器关闭：清空全部运行时状态 */
    public static void clearAll() {
        for (ShieldSet set : SHIELDS.values()) {
            set.clear();
        }
        SHIELDS.clear();
        for (Veil veil : VEILS.values()) {
            veil.clear();
        }
        VEILS.clear();
        for (Hunt hunt : HUNTS.values()) {
            hunt.clear();
        }
        HUNTS.clear();
        for (Wave wave : WAVES) {
            wave.display.discard();
        }
        WAVES.clear();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 水系蓝白粒子 */
    private static ColorParticleOption waterParticle() {
        return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, WATER_COLOR);
    }

    /** 从手到目标的蓝白色光晕拖尾 */
    private static void waterTrail(SkillContext ctx, LivingEntity target) {
        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        MagicDamage.line(ctx.level(), ctx.origin(), hit, waterParticle(), 0.28D);
        ctx.level().sendParticles(ParticleTypes.SPLASH, hit.x, hit.y, hit.z, 10, 0.25D, 0.35D, 0.25D, 0.05D);
    }

    /**
     * 施法失败（没有目标等）：把 MagicCaster 已经扣掉的蓝量和写下的冷却还回去。
     */
    private static void failCast(SkillContext ctx, String message) {
        ServerPlayer player = ctx.player();
        PlayerMagicData data = MagicData.get(player);
        data.setMana(data.mana() + ctx.skill().manaCost());
        data.setCooldown(ctx.skill().id(), 0L);
        MagicSync.sync(player);
        NoticeApi.send(player, Component.literal(message).withStyle(ChatFormatting.RED));
    }

    // ==================================================================
    // 数据结构
    // ==================================================================

    /** 一个生物身上的水盾：守护的可叠加永久盾 + 循环的 4 面临时盾 */
    private static final class ShieldSet {
        final LivingEntity entity;
        final ServerLevel level;
        /** 守护盾剩余次数（每面能挡 1 次魔法） */
        int guardCharges;
        /** 循环盾到期 tick，0 = 没有 */
        long cycleUntil;
        final List<FxBillboard> guardDisplays = new ArrayList<>();
        final List<FxBillboard> cycleDisplays = new ArrayList<>();

        ShieldSet(LivingEntity entity, ServerLevel level) {
            this.entity = entity;
            this.level = level;
        }

        /** 让守护盾的面片数量与层数一致（多退少补） */
        void syncGuardDisplays() {
            while (guardDisplays.size() > guardCharges) {
                guardDisplays.remove(guardDisplays.size() - 1).discard();
            }
            while (guardDisplays.size() < guardCharges && guardDisplays.size() < 8) {
                FxBillboard display = DisplayFx.spawn(level,
                        entity.position().add(0.0D, entity.getBbHeight() * 0.6D, 0.0D),
                        MagicItems.WATER_SHIELD.get(), 1.1F, 1.1F, 1.1F,
                        Display.BillboardConstraints.CENTER);
                if (display == null) {
                    break;
                }
                guardDisplays.add(display);
            }
        }

        /** 循环盾：4 面绕身 */
        void syncCycleDisplays() {
            if (cycleDisplays.size() == 4) {
                return;
            }
            clearCycleDisplays();
            for (int i = 0; i < 4; i++) {
                FxBillboard display = DisplayFx.spawn(level,
                        entity.position().add(0.0D, entity.getBbHeight() * 0.6D, 0.0D),
                        MagicItems.WATER_SHIELD.get(), 1.0F, 1.0F, 1.0F,
                        Display.BillboardConstraints.CENTER);
                if (display != null) {
                    cycleDisplays.add(display);
                }
            }
        }

        void clearCycleDisplays() {
            cycleDisplays.forEach(FxBillboard::discard);
            cycleDisplays.clear();
        }

        boolean isEmpty() {
            return guardCharges <= 0 && cycleUntil <= 0L
                    && guardDisplays.isEmpty() && cycleDisplays.isEmpty();
        }

        void clear() {
            guardDisplays.forEach(FxBillboard::discard);
            guardDisplays.clear();
            clearCycleDisplays();
            guardCharges = 0;
            cycleUntil = 0L;
        }

        /** 每 tick：守护盾在宿主周围缓慢绕圈，循环盾 4 面快速环绕 */
        void tick(ServerLevel level, long now) {
            Vec3 base = entity.position().add(0.0D, entity.getBbHeight() * 0.6D, 0.0D);
            for (int i = 0; i < guardDisplays.size(); i++) {
                double angle = now * 0.05D + i * (Math.PI * 2.0D / Math.max(1, guardDisplays.size()));
                FxBillboard display = guardDisplays.get(i);
                display.setPos(entity.getX() + Math.cos(angle) * 0.75D, base.y,
                        entity.getZ() + Math.sin(angle) * 0.75D);
            }
            for (int i = 0; i < cycleDisplays.size(); i++) {
                double angle = now * 0.14D + i * (Math.PI / 2.0D);
                FxBillboard display = cycleDisplays.get(i);
                display.setPos(entity.getX() + Math.cos(angle) * 1.15D, base.y,
                        entity.getZ() + Math.sin(angle) * 1.15D);
            }
        }
    }

    /** 一面推进中的浪墙 / 水龙卷 */
    private static final class Wave {
        final ServerPlayer owner;
        final ServerLevel level;
        final FxBillboard display;
        final Vec3 start;
        final Vec3 dir;
        final double speed;
        final double damage;
        final boolean tornado;
        final double maxRange;
        final float width;
        final float height;
        final Set<UUID> hitOnce = new HashSet<>();
        double distance;
        long nextDamageTick;
        /** 洪卷到极限后的停顿结束 tick，0 = 没在停顿 */
        long pauseUntil;

        Wave(ServerPlayer owner, ServerLevel level, FxBillboard display, Vec3 start, Vec3 dir,
             double speed, double damage, boolean tornado, double maxRange,
             long now, long nextDamageTick, float width, float height) {
            this.owner = owner;
            this.level = level;
            this.display = display;
            this.start = start;
            this.dir = dir;
            this.speed = speed;
            this.damage = damage;
            this.tornado = tornado;
            this.maxRange = maxRange;
            this.width = width;
            this.height = height;
            this.nextDamageTick = nextDamageTick;
        }
    }

    /** 水花天幕 */
    private static final class Veil {
        final ServerPlayer owner;
        final ServerLevel level;
        final FxBillboard wall;
        final Vec3 center;
        final long endTick;
        long nextDamageTick;

        Veil(ServerPlayer owner, ServerLevel level, FxBillboard wall, Vec3 center,
             long endTick, long nextDamageTick) {
            this.owner = owner;
            this.level = level;
            this.wall = wall;
            this.center = center;
            this.endTick = endTick;
            this.nextDamageTick = nextDamageTick;
        }

        void clear() {
            if (wall.isAlive()) {
                wall.discard();
            }
        }
    }

    /** 一把水弯刀 */
    private static final class Blade {
        static final int ORBIT = 0;
        static final int FLYING = 1;
        static final int RETURNING = 2;
        static final int DEAD = 3;

        final FxBillboard display;
        int state = ORBIT;
        @Nullable
        LivingEntity target;
        int lifeTicks;

        Blade(FxBillboard display) {
            this.display = display;
        }
    }

    /** 一次水之猎刃的完整状态 */
    private static final class Hunt {
        final ServerPlayer owner;
        final ServerLevel level;
        final List<Blade> blades = new ArrayList<>(8);
        final long startTick;
        /** 施法时的阶级范围，弯刀索敌半径跟着升阶变大 */
        final double range;
        long nextLaunchTick;

        Hunt(ServerPlayer owner, ServerLevel level, long now, double range) {
            this.owner = owner;
            this.level = level;
            this.startTick = now;
            this.range = range;
            this.nextLaunchTick = now;
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
