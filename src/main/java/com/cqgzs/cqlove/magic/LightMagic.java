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
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.item.MagicItems;
import com.cqgzs.cqlove.fx.FxBillboard;

/**
 * 光系全部 8 个技能（需求文档“光系”段：光耀 / 光佑 / 光落漫丈 / 圣光大剑）。
 *
 * <ul>
 *   <li><b>光耀</b>（初阶）：
 *     <ul>
 *       <li>净化：解除自身全部负面状态效果，并回复 6 点生命，金色光尘冲天。</li>
 *       <li>闪烁：朝面向方向扔出一颗光球（无伤害），撞到方块后在落点悬停 60 秒，
 *           放下一个 15 级光源持续照亮周围；释放即进冷却。</li>
 *       <li>失明：对准生物释放，造成 9 点光伤害并使其失明（附带发光，5 秒）。</li>
 *     </ul>
 *   </li>
 *   <li><b>光佑</b>（中阶）：
 *     <ul>
 *       <li>圣盾：20 秒内受到的所有伤害 -40%，4 面金色光盾环绕，结束后进冷却。</li>
 *       <li>画壁：身前 3 格立起 6×4 光之壁垒，持续 10 秒：推开靠近的生物、销毁敌方弹射物。</li>
 *       <li>守护：对准生物（潜行给自己）回复 60 点生命并附加 10 秒再生 II。</li>
 *     </ul>
 *   </li>
 *   <li><b>光落漫丈</b>（高阶）：鼠标位置降下 6×12 光柱，持续 5 秒，范围内敌对生物每秒 150 点光伤害并失明。</li>
 *   <li><b>圣光大剑</b>（超阶）：对准生物释放 → 1.5 秒前摇（金色粒子围困 + 定身）→
 *       头顶生成超巨大圣剑，8 tick 内砸到目标脚下的实体方块，范围内 1000 点光系魔法伤害并失明 5 秒，
 *       圣剑消失、解除定身，技能从落地这一刻开始进冷却。</li>
 * </ul>
 *
 * <p>圣盾减伤由 {@link MagicEvents#onIncomingDamage} 调 {@link #hasHolyShield} 接入。</p>
 */
public final class LightMagic {

    /** 光系代表色（金） */
    private static final int LIGHT_COLOR = MagicElement.LIGHT.color();

    /** 圣盾持续 tick（20 秒） */
    private static final int SHIELD_TICKS = 20 * 20;
    /** 画壁持续 tick（10 秒） */
    private static final int WALL_TICKS = 10 * 20;
    /** 光落漫丈持续 tick（5 秒） */
    private static final int PILLAR_TICKS = 5 * 20;
    /** 光落漫丈每秒伤害 */
    private static final double PILLAR_DPS = 150.0D;
    /** 光落漫丈光柱半径（格） */
    private static final double PILLAR_RADIUS = 3.5D;
    /** 闪烁光球：飞行速度（格/tick） */
    private static final double ORB_SPEED = 0.85D;
    /** 闪烁光球：最长飞行 tick（约 20 格） */
    /** 光球最长飞行 tick 不再写死：按施法范围 ÷ 速度算（见 SkillContext.travelTicks） */
    /** 闪烁光球：落地后照亮时长（60 秒） */
    private static final int ORB_TICKS = 60 * 20;
    /** 闪烁光球贴图大小（格） */
    private static final float ORB_SIZE = 0.9F;
    /** 画壁：压缩成竖线后的宽度（格） */
    private static final float WALL_LINE_WIDTH = 0.12F;
    /** 画壁：竖线保持 tick（前摇），之后左右展开 */
    private static final int WALL_LINE_TICKS = 8;
    /** 画壁：左右展开动画时长（tick） */
    private static final int WALL_EXPAND_TICKS = 10;
    /** 圣光大剑伤害（需求：1000） */
    private static final double SWORD_DAMAGE = 1000.0D;
    /** 圣光大剑落点伤害半径（格） */
    private static final double SWORD_RADIUS = 5.0D;
    /** 圣光大剑前摇 tick（1.5 秒：粒子围困 + 目标无法移动） */
    private static final int SWORD_WINDUP = 30;
    /** 圣光大剑从悬停位置砸到地面的 tick */
    private static final int SWORD_FALL = 8;
    /** 圣光大剑落地后“停稳”几 tick 再爆（客户端位置插值有 1 tick 延迟，不停稳会出现“剑还在空中就爆炸”） */
    private static final int SWORD_HOLD = 3;
    /** 巨剑显示缩放：剑身宽 7 格；高度在此基础上再拉长（见 {@link #SWORD_HEIGHT_STRETCH}） */
    private static final float SWORD_SCALE = 14.0F;
    /** 巨剑高度额外拉长倍数（需求：高度再拉长 0.5 倍 = 1.5 倍高，共 21 格） */
    private static final float SWORD_HEIGHT_STRETCH = 1.5F;
    /** 巨剑剑心到剑尖的距离（格）：剑尖贴地时实体中心的高度 */
    private static final double SWORD_HALF = 7.0D * SWORD_HEIGHT_STRETCH;
    /** 巨剑刚出现时在落点上方多少格 */
    private static final double SWORD_SPAWN_ABOVE = 28.0D;
    /** 前摇结束时的悬停高度（剑尖离地，格） */
    private static final double SWORD_HOVER_ABOVE = 10.0D;
    /** 圣盾减伤比例（受到伤害 ×0.6） */
    private static final float SHIELD_MULTIPLIER = 0.6F;
    /** 失明持续 tick（5 秒） */
    private static final int BLIND_TICKS = 5 * 20;

    /** 定身用属性修饰器 id（结束后按 id 精确移除，不影响其他来源的加成） */
    private static final Identifier ROOT_SPEED_ID = id("holy_sword_root_speed");
    private static final Identifier ROOT_JUMP_ID = id("holy_sword_root_jump");

    /** 净化 / 失明 / 守护 这类瞬时技能留下的光源时长（4 秒） */
    private static final int FLASH_LIGHT_TICKS = 4 * 20;
    /** 圣盾跟随光源的续期窗口（每 tick 刷新，离开后 0.6 秒自动熄灭） */
    private static final int SHIELD_LIGHT_REFRESH = 12;

    /** 施法者 UUID -> 圣盾 */
    private static final Map<UUID, HolyShield> SHIELDS = new HashMap<>();
    /** 施法者 UUID -> 画壁 */
    private static final Map<UUID, LightWall> WALLS = new HashMap<>();
    /** 飞行中 / 悬浮中的光球（闪烁） */
    private static final List<LightOrb> ORBS = new ArrayList<>();
    /** 进行中的光柱 */
    private static final List<Pillar> PILLARS = new ArrayList<>();
    /** 进行中的圣光大剑 */
    private static final List<Sword> SWORDS = new ArrayList<>();
    /** 光系技能临时点亮的光源方块（维度 + 坐标 -> 到期 tick） */
    private static final Map<LightKey, LightSpot> TEMP_LIGHTS = new HashMap<>();

    private LightMagic() {
    }

    // ==================================================================
    // 初阶·光耀：净化 / 闪烁 / 失明
    // ==================================================================

    /** 净化：解除自身全部负面状态效果 + 回复 6 点生命 */
    public static void purify(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();

        int removed = 0;
        for (MobEffectInstance effect : new ArrayList<>(player.getActiveEffects())) {
            if (!effect.getEffect().value().isBeneficial()) {
                player.removeEffect(effect.getEffect());
                removed++;
            }
        }
        player.heal(6.0F);
        // 金色光尘从脚下升起
        for (int i = 0; i < 26; i++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
            double radius = 0.15D + level.getRandom().nextDouble() * 0.55D;
            level.sendParticles(lightParticle(),
                    player.getX() + Math.cos(angle) * radius,
                    player.getY() + level.getRandom().nextDouble() * 2.2D,
                    player.getZ() + Math.sin(angle) * radius,
                    1, 0.0D, 0.08D, 0.0D, 0.01D);
        }
        level.sendParticles(ParticleTypes.END_ROD,
                player.getX(), player.getY() + 0.4D, player.getZ(), 16, 0.45D, 0.5D, 0.45D, 0.06D);
        // 净化：脚下亮起光源，向外爆一圈光
        placeLight(level, player.position(), FLASH_LIGHT_TICKS);
        glowBurst(level, player.position().add(0.0D, 0.2D, 0.0D), 2.2D);
        MagicDamage.playSound(level, player.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 0.9F, 1.4F);
    }

    /**
     * 闪烁：向前方扔出一颗光球（无伤害，纯照明）。
     *
     * <p>光球沿视线方向飞出，撞到方块就停下（没撞到则最多飞满<b>施法范围</b>：
     * 初阶 10 / 中阶 15 / 高阶 20 / 超阶 30 格），落点悬停 60 秒：
     * 放下一个 15 级真实光源 + 一颗缓缓浮动的光球贴图，持续照亮周围区域。
     * 技能释放即进入冷却（由 {@link MagicCaster} 统一写入），不需要技能锁定。</p>
     */
    public static void blink(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 look = player.getLookAngle();
        Vec3 start = player.getEyePosition().add(look.scale(0.6D));

        FxBillboard display = DisplayFx.spawn(level, start, MagicItems.LIGHT_ORB.get(),
                ORB_SIZE, ORB_SIZE, ORB_SIZE, Display.BillboardConstraints.CENTER);
        if (display == null) {
            failCast(ctx, "闪烁: 光球生成失败");
            return;
        }
        ORBS.add(new LightOrb(player, level, display, start, look.scale(ORB_SPEED),
                ctx.travelTicks(ORB_SPEED)));

        MagicDamage.playSound(level, start, SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.7F);
        level.sendParticles(lightParticle(), start.x, start.y, start.z, 12, 0.2D, 0.2D, 0.2D, 0.03D);
    }

    /** 失明：对准生物，9 点光伤害 + 失明 5 秒（附带发光，方便队友集火） */
    public static void blind(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        LivingEntity target = ctx.targetEntity();
        if (target == null) {
            failCast(ctx, "失明需要对准生物释放");
            return;
        }
        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D);
        MagicDamage.line(level, ctx.origin(), hit, lightParticle(), 0.3D);
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF8E0), hit.x, hit.y, hit.z, 0, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.END_ROD, hit.x, hit.y, hit.z, 12, 0.25D, 0.3D, 0.25D, 0.05D);
        target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_TICKS, 0));
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, BLIND_TICKS, 0));
        // 失明：命中点炸开强光并留下 4 秒光源
        placeLight(level, target.position(), FLASH_LIGHT_TICKS);
        placeLight(level, hit, FLASH_LIGHT_TICKS);
        glowBurst(level, hit, 2.6D);
        MagicDamage.playSound(level, hit, SoundEvents.AMETHYST_BLOCK_BREAK, 1.0F, 1.6F);
        ctx.damage(target, 9.0D);
    }

    // ==================================================================
    // 中阶·光佑：圣盾 / 画壁 / 守护
    // ==================================================================

    /** 圣盾：20 秒内受到的所有伤害 -40%，4 面光盾环绕 */
    public static void holyShield(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();

        HolyShield previous = SHIELDS.remove(player.getUUID());
        if (previous != null) {
            previous.clear();
        }
        HolyShield shield = new HolyShield(player, level, now + SHIELD_TICKS);
        shield.ensureDisplays();
        SHIELDS.put(player.getUUID(), shield);

        MagicDamage.playSound(level, player.position(), SoundEvents.BEACON_ACTIVATE, 0.7F, 1.6F);
        level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0D, player.getZ(),
                20, 0.6D, 0.8D, 0.6D, 0.08D);
        // 圣盾：脚下立刻点亮，之后由 HolyShield 跟随玩家移动
        shield.tickLight(now);
        glowBurst(level, player.position().add(0.0D, 0.3D, 0.0D), 3.0D);
    }

    /** 画壁：身前 3 格立起 6×4 光之壁垒，持续 10 秒，推开生物、销毁敌方弹射物 */
    public static void paintWall(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();

        LightWall previous = WALLS.remove(player.getUUID());
        if (previous != null) {
            previous.clear();
        }

        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        flat = flat.normalize();
        Vec3 base = GroundDecal.groundBelow(level,
                player.position().add(flat.scale(3.0D)).add(0.0D, 1.2D, 0.0D), 4);
        Vec3 center = new Vec3(base.x, base.y + 2.0D, base.z);
        double yaw = Math.atan2(flat.x, flat.z);
        org.joml.Quaternionf rotation = DisplayFx.yawRotation(yaw);

        // 前摇：墙面先被压缩成一根竖直的细线（高度不变），5 tick 后再左右展开成 6×4
        FxBillboard display = DisplayFx.spawn(level, center, MagicItems.LIGHT_WALL.get(),
                WALL_LINE_WIDTH, 4.0F, 1.0F, Display.BillboardConstraints.FIXED, rotation);
        if (display == null) {
            failCast(ctx, "画壁生成失败");
            return;
        }
        WALLS.put(player.getUUID(), new LightWall(player, level, display, center, flat,
                rotation, now + WALL_LINE_TICKS, now + WALL_TICKS));

        MagicDamage.playSound(level, center, SoundEvents.BEACON_ACTIVATE, 0.6F, 1.5F);
        // 竖线形态的视觉强化：沿墙面画一条竖直光带
        for (int i = 0; i < 16; i++) {
            level.sendParticles(ParticleTypes.END_ROD,
                    center.x, center.y - 2.0D + i * 0.25D, center.z, 1, 0.05D, 0.0D, 0.05D, 0.0D);
        }
    }

    /** 守护：对准生物（潜行给自己）回复 60 点生命 + 10 秒再生 II */
    public static void guard(SkillContext ctx) {
        LivingEntity target = ctx.targetEntity();
        if (target == null && ctx.player().isShiftKeyDown()) {
            target = ctx.player();
        }
        if (target == null) {
            failCast(ctx, "守护需要对准生物释放 (潜行释放可给自己)");
            return;
        }
        ServerLevel level = ctx.level();
        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        MagicDamage.line(level, ctx.origin(), hit, lightParticle(), 0.3D);
        target.heal(60.0F);
        target.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1));

        for (int i = 0; i < 22; i++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
            double radius = 0.2D + level.getRandom().nextDouble() * 0.6D;
            level.sendParticles(lightParticle(),
                    target.getX() + Math.cos(angle) * radius,
                    target.getY() + level.getRandom().nextDouble() * target.getBbHeight(),
                    target.getZ() + Math.sin(angle) * radius,
                    1, 0.0D, 0.1D, 0.0D, 0.01D);
        }
        level.sendParticles(ParticleTypes.END_ROD, hit.x, hit.y, hit.z, 18, 0.4D, 0.5D, 0.4D, 0.06D);
        placeLight(level, target.position(), FLASH_LIGHT_TICKS);
        glowBurst(level, hit, 2.4D);
        MagicDamage.playSound(level, hit, SoundEvents.BEACON_POWER_SELECT, 0.8F, 1.5F);
    }

    // ==================================================================
    // 高阶·光落漫丈
    // ==================================================================

    /** 光落漫丈：鼠标位置降下 6×12 光柱，5 秒内范围内每秒 150 点光伤害并失明 */
    public static void lightFall(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        Vec3 ground = GroundDecal.groundBelow(level, ctx.targetPos().add(0.0D, 1.0D, 0.0D), 6);
        Vec3 center = new Vec3(ground.x, ground.y + 6.0D, ground.z);

        FxBillboard display = DisplayFx.spawn(level, center, MagicItems.LIGHT_PILLAR.get(),
                6.0F, 12.0F, 6.0F, Display.BillboardConstraints.CENTER);
        if (display == null) {
            failCast(ctx, "光落漫丈生成失败");
            return;
        }
        long now = level.getGameTime();
        PILLARS.add(new Pillar(player, level, display, ground, now + PILLAR_TICKS, now));

        // 光柱落点：地面一圈 8 个光源，整根柱子真的照亮周围
        placeLight(level, ground.add(0.0D, 1.0D, 0.0D), PILLAR_TICKS);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI * 2.0D / 8.0D;
            placeLight(level, ground.add(Math.cos(angle) * 2.6D, 0.9D, Math.sin(angle) * 2.6D), PILLAR_TICKS);
        }
        glowBurst(level, ground.add(0.0D, 0.3D, 0.0D), PILLAR_RADIUS);

        MagicDamage.playSound(level, center, SoundEvents.BEACON_ACTIVATE, 1.0F, 0.9F);
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF8E0), center.x, ground.y + 1.0D, center.z, 0, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.END_ROD, center.x, ground.y + 3.0D, center.z,
                40, 2.6D, 5.0D, 2.6D, 0.04D);
    }

    // ==================================================================
    // 超阶·圣光大剑
    // ==================================================================

    /**
     * 圣光大剑（超阶）：按键立即进入前摇 ——
     * <ol>
     *   <li>用金色粒子把锁定的生物整个围住（地面光环 + 竖直光笼 + 上升符文）；</li>
     *   <li>目标被定身（移动速度归零、不能起跳、位移清零），1.5 秒内无法移动；</li>
     *   <li>目标头顶高空生成一把超巨大的圣剑，缓缓下降到头顶；</li>
     *   <li>8 tick 内砸到脚下的实体方块，范围内 1000 点光系魔法伤害；</li>
     *   <li>圣剑消失、解除定身、技能从这一刻开始进冷却。</li>
     * </ol>
     */
    public static void holySword(SkillContext ctx) {
        ServerPlayer player = ctx.player();
        ServerLevel level = ctx.level();
        LivingEntity target = ctx.targetEntity();
        if (target == null) {
            failCast(ctx, "圣光大剑需要锁定一个生物 (对准生物释放)");
            return;
        }
        if (target instanceof Player victim && !com.cqgzs.cqlove.lan.PvpApi.canHurt(player, victim)) {
            failCast(ctx, "PVP 关闭, 不能对玩家释放圣光大剑");
            return;
        }

        // 落点 = 目标脚下的实体方块顶面；剑心抬高半个剑身，落下来正好剑尖插在地上
        Vec3 feet = GroundDecal.groundBelow(level, target.position().add(0.0D, 0.5D, 0.0D), 10);
        Vec3 rest = new Vec3(feet.x, feet.y + SWORD_HALF, feet.z);

        FxBillboard display = DisplayFx.spawn(level,
                new Vec3(rest.x, rest.y + SWORD_SPAWN_ABOVE, rest.z), MagicItems.LIGHT_GREATSWORD.get(),
                SWORD_SCALE, SWORD_SCALE * SWORD_HEIGHT_STRETCH, SWORD_SCALE,
                Display.BillboardConstraints.CENTER);
        if (display == null) {
            failCast(ctx, "圣光大剑召唤失败");
            return;
        }

        long now = level.getGameTime();
        PlayerMagicData data = MagicData.get(player);
        // 冷却从“圣剑落地”开始算（需求），先把施法时写入的冷却清掉，
        // 前摇+下落这段时间用技能锁定顶住，防止中途连按
        int cooldownSeconds = ctx.skill().cooldownSeconds(data.rank());
        // 云冰晶界领域内：所有技能施法时间（前摇）-50%
        int windup = IceMagic.castTicks(player, SWORD_WINDUP);
        data.setCooldown(ctx.skill().id(), 0L);
        data.lockSkill(ctx.skill().id(), now + windup + SWORD_FALL + SWORD_HOLD + 2L);

        rootTarget(target);
        SWORDS.add(new Sword(ctx.skill().id(), player, level, display, target, rest, now,
                now + windup, now + windup + SWORD_FALL + SWORD_HOLD, cooldownSeconds));

        // 落点提前点亮（前摇 + 下落 + 落地后 2 秒），目标脚下整片区域都亮起来
        placeLight(level, feet, windup + SWORD_FALL + 40);
        placeLight(level, feet.add(1.5D, 0.0D, 0.0D), windup + SWORD_FALL + 40);
        placeLight(level, feet.add(-1.5D, 0.0D, 0.0D), windup + SWORD_FALL + 40);
        placeLight(level, feet.add(0.0D, 0.0D, 1.5D), windup + SWORD_FALL + 40);
        placeLight(level, feet.add(0.0D, 0.0D, -1.5D), windup + SWORD_FALL + 40);

        MagicDamage.playSound(level, rest, SoundEvents.BEACON_POWER_SELECT, 1.0F, 0.7F);
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF8E0),
                target.getX(), target.getY() + 1.0D, target.getZ(), 0, 0.0D, 0.0D, 0.0D, 0.0D);
        // 从天而降的光柱，提示“有东西正在下来”
        for (double h = 1.0D; h < 26.0D; h += 1.2D) {
            level.sendParticles(lightParticle(), rest.x, rest.y + h, rest.z, 1, 0.05D, 0.0D, 0.05D, 0.0D);
        }
    }

    // ==================================================================
    // 伤害减免（MagicEvents.onIncomingDamage 调用）
    // ==================================================================

    /** 圣盾是否生效中（受到伤害会 ×0.6） */
    public static boolean hasHolyShield(ServerPlayer player) {
        HolyShield shield = SHIELDS.get(player.getUUID());
        return shield != null && shield.owner == player && player.level().getGameTime() < shield.endTick;
    }

    /** 圣盾减伤系数（没有盾返回 1） */
    public static float shieldMultiplier(ServerPlayer player) {
        return hasHolyShield(player) ? SHIELD_MULTIPLIER : 1.0F;
    }

    // ==================================================================
    // 每 tick 驱动
    // ==================================================================

    /** 服务端每 tick：圣盾跟随/到期、画壁推人、光球飞行、光柱伤害、巨剑劈落（MagicEvents.onServerTick） */
    public static void tickWorld() {
        tickShields();
        tickWalls();
        tickOrbs();
        tickPillars();
        tickSwords();
        tickLights();
    }

    private static void tickShields() {
        if (SHIELDS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, HolyShield>> iterator = SHIELDS.entrySet().iterator();
        while (iterator.hasNext()) {
            HolyShield shield = iterator.next().getValue();
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

    private static void tickWalls() {
        if (WALLS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, LightWall>> iterator = WALLS.entrySet().iterator();
        while (iterator.hasNext()) {
            LightWall wall = iterator.next().getValue();
            if (wall.owner.isRemoved() || !wall.display.isAlive()) {
                wall.clear();
                iterator.remove();
                continue;
            }
            long now = wall.level.getGameTime();
            if (now >= wall.endTick) {
                wall.clear();
                iterator.remove();
                continue;
            }
            wall.tick();
        }
    }

    /** 闪烁光球：飞行 → 落地放光 → 60 秒后熄灭回收 */
    private static void tickOrbs() {
        for (int i = ORBS.size() - 1; i >= 0; i--) {
            LightOrb orb = ORBS.get(i);
            ServerLevel level = orb.level;
            long now = level.getGameTime();
            if (orb.owner.isRemoved() || !orb.display.isAlive()) {
                orb.clear();
                ORBS.remove(i);
                continue;
            }
            if (orb.settled) {
                // 悬浮阶段：轻轻上下浮动 + 偶尔洒光尘，到时间熄灭
                double bob = Math.sin((now - orb.settleTick) * 0.12D) * 0.12D;
                orb.display.setPos(orb.pos.x, orb.pos.y + bob, orb.pos.z);
                if (now % 8L == 0L) {
                    level.sendParticles(lightParticle(), orb.pos.x, orb.pos.y + bob, orb.pos.z,
                            2, 0.22D, 0.22D, 0.22D, 0.004D);
                }
                if (now >= orb.endTick) {
                    orb.clear();
                    ORBS.remove(i);
                }
                continue;
            }
            // 飞行阶段：沿视线推进，撞到方块就落地
            Vec3 next = orb.pos.add(orb.velocity);
            HitResult hit = level.clip(new ClipContext(orb.pos, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, orb.owner));
            Vec3 dest = hit.getType() == HitResult.Type.MISS
                    ? next
                    : hit.getLocation().subtract(orb.velocity.normalize().scale(0.3D));
            orb.pos = dest;
            orb.flyingTicks++;
            orb.display.setPos(dest.x, dest.y, dest.z);
            // 飞行途中照亮经过的方块（短时），整颗光球是一路发光的
            placeLight(level, dest, 12);
            level.sendParticles(lightParticle(), dest.x, dest.y, dest.z, 3, 0.08D, 0.08D, 0.08D, 0.0D);
            if (hit.getType() != HitResult.Type.MISS || orb.flyingTicks >= orb.maxFlyingTicks) {
                orb.settle(now);
            }
        }
    }

    private static void tickPillars() {
        for (int i = PILLARS.size() - 1; i >= 0; i--) {
            Pillar pillar = PILLARS.get(i);
            ServerLevel level = pillar.level;
            long now = level.getGameTime();
            if (pillar.owner.isRemoved() || !pillar.display.isAlive()) {
                pillar.display.discard();
                PILLARS.remove(i);
                continue;
            }
            if (now >= pillar.endTick) {
                pillar.display.discard();
                PILLARS.remove(i);
                continue;
            }
            // 每秒 150 点光伤害 + 失明 2 秒
            if (now >= pillar.nextDamageTick) {
                pillar.nextDamageTick = now + 20L;
                Vec3 center = pillar.ground.add(0.0D, 3.0D, 0.0D);
                AABB area = new AABB(pillar.ground, pillar.ground)
                        .inflate(PILLAR_RADIUS, 6.0D, PILLAR_RADIUS);
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                    if (entity == pillar.owner || !entity.isAlive() || entity.isSpectator()) {
                        continue;
                    }
                    entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0));
                    MagicDamage.magicDamage(pillar.owner, entity, PILLAR_DPS, MagicElement.LIGHT);
                }
                level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF8E0), center.x, pillar.ground.y + 1.0D, center.z,
                        0, 0.0D, 0.0D, 0.0D, 0.0D);
            }
            // 光柱内不断上升的光尘
            level.sendParticles(ParticleTypes.END_ROD,
                    pillar.center.x, pillar.ground.y + 1.0D, pillar.center.z,
                    6, PILLAR_RADIUS * 0.55D, 0.6D, PILLAR_RADIUS * 0.55D, 0.03D);
            if (now % 4L == 0L) {
                level.sendParticles(ParticleTypes.ENCHANT,
                        pillar.center.x, pillar.ground.y + 0.6D, pillar.center.z,
                        8, PILLAR_RADIUS * 0.6D, 0.2D, PILLAR_RADIUS * 0.6D, 0.4D);
            }
        }
    }

    private static void tickSwords() {
        for (int i = SWORDS.size() - 1; i >= 0; i--) {
            Sword sword = SWORDS.get(i);
            ServerLevel level = sword.level;
            long now = level.getGameTime();
            if (sword.owner.isRemoved() || !sword.owner.isAlive() || !sword.display.isAlive()) {
                sword.cancel();
                SWORDS.remove(i);
                continue;
            }
            if (now < sword.windupEnd) {
                // ---- 前摇：定身 + 粒子围困 + 巨剑缓缓压到头顶 ----
                swordRootTick(sword.target);
                swordWindupFx(sword, now);
                // 用实际前摇长度（可能已被云冰晶界减半）算进度
                double progress = (now - sword.startTick) / (double) (sword.windupEnd - sword.startTick);
                double eased = 1.0D - (1.0D - progress) * (1.0D - progress);
                double y = sword.rest.y + SWORD_SPAWN_ABOVE
                        - (SWORD_SPAWN_ABOVE - SWORD_HOVER_ABOVE) * eased;
                sword.display.setPos(sword.rest.x, y, sword.rest.z);
            } else if (now < sword.impactTick) {
                // ---- 下落：8 tick 内从悬停高度加速砸到地面 ----
                double t = Math.min(1.0D, (now - sword.windupEnd + 1.0D) / (double) SWORD_FALL);
                double y = sword.rest.y + SWORD_HOVER_ABOVE * (1.0D - t * t);
                sword.display.setPos(sword.rest.x, y, sword.rest.z);
                // 剑身拖出光尾 + 剑尖下压气流
                for (int k = 0; k < 4; k++) {
                    double ty = y + 1.0D + k * 1.8D;
                    level.sendParticles(ParticleTypes.END_ROD,
                            sword.rest.x, ty, sword.rest.z, 2, 0.35D, 0.2D, 0.35D, 0.02D);
                }
                level.sendParticles(lightParticle(), sword.rest.x, y - SWORD_HALF + 0.2D, sword.rest.z,
                        6, 0.5D, 0.1D, 0.5D, 0.05D);
            } else {
                swordImpact(sword);
                SWORDS.remove(i);
            }
        }
    }

    /** 前摇期间的粒子围困：地面光环 + 八根竖直光柱 + 收缩的符文环 */
    private static void swordWindupFx(Sword sword, long now) {
        LivingEntity target = sword.target;
        ServerLevel level = sword.level;
        // 用实际前摇长度（可能已被云冰晶界减半）算进度
        double progress = Math.min(1.0D,
                (now - sword.startTick) / (double) (sword.windupEnd - sword.startTick));
        double radius = 1.75D - 0.55D * progress;
        double bx = target.getX();
        double bz = target.getZ();
        double by = target.getY();

        // 地面光环（缓慢旋转）
        int points = 24;
        for (int i = 0; i < points; i++) {
            double angle = i * Math.PI * 2.0D / points + now * 0.10D;
            level.sendParticles(lightParticle(),
                    bx + Math.cos(angle) * radius, by + 0.08D, bz + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        // 竖直光笼：8 根，反向旋转
        double height = target.getBbHeight() + 1.6D;
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI * 2.0D / 8.0D - now * 0.06D;
            double x = bx + Math.cos(angle) * (radius + 0.15D);
            double z = bz + Math.sin(angle) * (radius + 0.15D);
            for (double h = 0.15D; h < height; h += 0.5D) {
                level.sendParticles(ParticleTypes.END_ROD, x, by + h, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        // 上升符文（每 3 tick 一批）
        if (now % 3L == 0L) {
            level.sendParticles(ParticleTypes.ENCHANT,
                    bx, by + 0.6D, bz, 10, radius * 0.8D, 0.3D, radius * 0.8D, 0.6D);
            level.sendParticles(lightParticle(),
                    bx, by + height * 0.6D, bz, 6, radius * 0.6D, 0.3D, radius * 0.6D, 0.02D);
        }
        // 巨剑下降时从剑身往下洒光屑
        if (now % 2L == 0L) {
            Vec3 pos = sword.display.position();
            level.sendParticles(lightParticle(), pos.x, pos.y - SWORD_HALF * 0.6D, pos.z,
                    3, 0.6D, 0.4D, 0.6D, 0.01D);
        }
    }

    /** 定身：把移动速度压到 0、跳跃力量压到 0，并每 tick 清掉位移（对付 AI 强行迈步） */
    private static void swordRootTick(LivingEntity target) {
        if (!target.isAlive() || target.isRemoved()) {
            return;
        }
        target.setDeltaMovement(Vec3.ZERO);
        target.hurtMarked = true;
        if (target instanceof Mob mob) {
            mob.getNavigation().stop();
        }
    }

    /** 开始定身：给目标挂上速度 / 跳跃的属性修饰器 */
    private static void rootTarget(LivingEntity target) {
        AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.addOrUpdateTransientModifier(new AttributeModifier(ROOT_SPEED_ID,
                    -1.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        AttributeInstance jump = target.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) {
            jump.addOrUpdateTransientModifier(new AttributeModifier(ROOT_JUMP_ID,
                    -10.0D, AttributeModifier.Operation.ADD_VALUE));
        }
        swordRootTick(target);
    }

    /** 解除定身 */
    private static void releaseRoot(LivingEntity target) {
        if (target == null) {
            return;
        }
        AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(ROOT_SPEED_ID);
        }
        AttributeInstance jump = target.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(ROOT_JUMP_ID);
        }
    }

    /**
     * 巨剑落地：5 格内 1000 点光系魔法伤害 + 失明 5 秒 + 冲击波，
     * 圣剑消失、解除定身，技能从这一刻开始进冷却。
     */
    private static void swordImpact(Sword sword) {
        Vec3 ground = new Vec3(sword.rest.x, sword.rest.y - SWORD_HALF, sword.rest.z);
        ServerLevel level = sword.level;
        sword.display.discard();
        releaseRoot(sword.target);

        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF8E0),
                ground.x, ground.y + 1.0D, ground.z, 0, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, ground.x, ground.y + 1.0D, ground.z,
                3, 1.6D, 0.3D, 1.6D, 0.0D);
        level.sendParticles(ParticleTypes.END_ROD, ground.x, ground.y + 1.0D, ground.z,
                90, 2.0D, 1.2D, 2.0D, 0.35D);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, ground.x, ground.y + 0.6D, ground.z,
                24, 2.4D, 0.4D, 2.4D, 0.06D);
        // 地面冲击环
        for (int i = 0; i < 32; i++) {
            double angle = i * Math.PI * 2.0D / 32.0D;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            MagicDamage.line(level, ground.add(dx * 0.5D, 0.2D, dz * 0.5D),
                    ground.add(dx * SWORD_RADIUS, 0.25D, dz * SWORD_RADIUS),
                    ParticleTypes.END_ROD, 0.9D);
        }
        // 落地爆亮：外圈 8 个光源点亮 3 秒，整片区域被照亮
        glowBurst(level, ground.add(0.0D, 0.3D, 0.0D), SWORD_RADIUS);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI * 2.0D / 8.0D;
            placeLight(level, ground.add(Math.cos(angle) * 3.0D, 0.5D, Math.sin(angle) * 3.0D), 60);
        }

        int hits = 0;
        AABB area = new AABB(ground, ground).inflate(SWORD_RADIUS, 6.0D, SWORD_RADIUS);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (entity == sword.owner || !entity.isAlive() || entity.isSpectator()) {
                continue;
            }
            entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_TICKS, 0));
            if (MagicDamage.magicDamage(sword.owner, entity, SWORD_DAMAGE, MagicElement.LIGHT)) {
                hits++;
            }
        }

        level.playSound(null, ground.x, ground.y, ground.z,
                SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1.6F, 0.55F);
        MagicDamage.playSound(level, ground, SoundEvents.LIGHTNING_BOLT_IMPACT, 1.2F, 0.7F);
        MagicDamage.playSound(level, ground, SoundEvents.BELL_RESONATE, 1.0F, 0.5F);

        // ---- 冷却从圣剑落地开始算 ----
        PlayerMagicData data = MagicData.get(sword.owner);
        data.endSuperRing(sword.skillId);
        data.clearSkillLock(sword.skillId);
        if (sword.cooldownSeconds > 0) {
            data.setCooldown(sword.skillId, level.getGameTime() + sword.cooldownSeconds * 20L);
        }
        MagicSync.sync(sword.owner);
    }

    // ==================================================================
    // 清理
    // ==================================================================

    /** 玩家下线/死亡：收掉圣盾、画壁、光柱、巨剑 */
    public static void removePlayer(ServerPlayer player) {
        HolyShield shield = SHIELDS.remove(player.getUUID());
        if (shield != null) {
            shield.clear();
        }
        LightWall wall = WALLS.remove(player.getUUID());
        if (wall != null) {
            wall.clear();
        }
        ORBS.removeIf(orb -> {
            if (orb.owner == player) {
                orb.clear();
                return true;
            }
            return false;
        });
        PILLARS.removeIf(pillar -> {
            if (pillar.owner == player) {
                pillar.display.discard();
                return true;
            }
            return false;
        });
        SWORDS.removeIf(sword -> {
            if (sword.owner == player) {
                sword.cancel();
                return true;
            }
            return false;
        });
    }

    /** 服务器关闭：清空全部运行时状态 */
    public static void clearAll() {
        SHIELDS.values().forEach(HolyShield::clear);
        SHIELDS.clear();
        WALLS.values().forEach(LightWall::clear);
        WALLS.clear();
        ORBS.forEach(LightOrb::clear);
        ORBS.clear();
        PILLARS.forEach(pillar -> pillar.display.discard());
        PILLARS.clear();
        SWORDS.forEach(Sword::cancel);
        SWORDS.clear();
        // 把所有我们放下的临时光源方块还原成空气
        for (LightSpot spot : TEMP_LIGHTS.values()) {
            if (spot.level.getBlockState(spot.pos).is(Blocks.LIGHT)) {
                spot.level.setBlock(spot.pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        TEMP_LIGHTS.clear();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    // ------------------------------------------------------------------
    // 临时光源：光系技能“向外发光”的落地实现（真实点亮周围方块）
    // ------------------------------------------------------------------

    /** 在 pos 所在方块放一个 15 级光源（只替换空气，绝不破坏地形），持续 ticks */
    private static void placeLight(ServerLevel level, Vec3 pos, int ticks) {
        placeLight(level, BlockPos.containing(pos), ticks);
    }

    private static void placeLight(ServerLevel level, BlockPos pos, int ticks) {
        if (!level.isLoaded(pos)) {
            return;
        }
        LightKey key = new LightKey(level.dimension(), pos);
        long expire = level.getGameTime() + Math.max(1, ticks);
        LightSpot spot = TEMP_LIGHTS.get(key);
        if (spot != null) {
            spot.expireTick = Math.max(spot.expireTick, expire);
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.isAir()) {
            return; // 不覆盖任何已有方块（包括别人放的灯）
        }
        level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, LightBlock.MAX_LEVEL),
                Block.UPDATE_ALL);
        TEMP_LIGHTS.put(key, new LightSpot(level, pos, expire));
    }

    /** 立刻熄掉某处我们放的光源（圣盾移动 / 提前结束时用） */
    private static void removeLight(ServerLevel level, @Nullable BlockPos pos) {
        if (pos == null) {
            return;
        }
        LightSpot spot = TEMP_LIGHTS.remove(new LightKey(level.dimension(), pos));
        if (spot != null && spot.level.getBlockState(spot.pos).is(Blocks.LIGHT)) {
            spot.level.setBlock(spot.pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** 每 tick 回收到期光源 */
    private static void tickLights() {
        if (TEMP_LIGHTS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<LightKey, LightSpot>> iterator = TEMP_LIGHTS.entrySet().iterator();
        while (iterator.hasNext()) {
            LightSpot spot = iterator.next().getValue();
            if (spot.level.getGameTime() < spot.expireTick) {
                continue;
            }
            iterator.remove();
            if (spot.level.getBlockState(spot.pos).is(Blocks.LIGHT)) {
                spot.level.setBlock(spot.pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /** 施法瞬间“向外炸开一圈光”：闪光 + 放射状光带 */
    private static void glowBurst(ServerLevel level, Vec3 pos, double radius) {
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF8E0),
                pos.x, pos.y, pos.z, 0, 0.0D, 0.0D, 0.0D, 0.0D);
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI * 2.0D / 12.0D;
            MagicDamage.line(level, pos, pos.add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius),
                    ParticleTypes.END_ROD, 0.7D);
        }
    }

    /** 金色光粒子 */
    private static ColorParticleOption lightParticle() {
        return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, LIGHT_COLOR);
    }

    /** 属性修饰器 id（命名空间固定 cqlove） */
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PlatformMods.MODID, path);
    }

    /** 施法失败：退还蓝量与冷却 */
    private static void failCast(SkillContext ctx, String message) {
        PlayerMagicData data = MagicData.get(ctx.player());
        data.setMana(data.mana() + ctx.skill().manaCost());
        data.setCooldown(ctx.skill().id(), 0L);
        MagicSync.sync(ctx.player());
        NoticeApi.send(ctx.player(), Component.literal(message).withStyle(ChatFormatting.RED));
    }

    // ==================================================================
    // 数据结构
    // ==================================================================

    /** 临时光源的唯一键：维度 + 方块坐标（不同维度同一坐标不会互相顶掉） */
    private record LightKey(ResourceKey<Level> dimension, BlockPos pos) {
    }

    /** 已放下的临时光源 */
    private static final class LightSpot {
        final ServerLevel level;
        final BlockPos pos;
        long expireTick;

        LightSpot(ServerLevel level, BlockPos pos, long expireTick) {
            this.level = level;
            this.pos = pos;
            this.expireTick = expireTick;
        }
    }

    /** 圣盾：4 面金色光盾环绕 + 到期时间 */
    private static final class HolyShield {
        final ServerPlayer owner;
        final ServerLevel level;
        final long endTick;
        final List<FxBillboard> displays = new ArrayList<>(4);
        /** 跟随玩家的光源方块（换格时先熄掉旧的） */
        @Nullable
        BlockPos lightPos;

        HolyShield(ServerPlayer owner, ServerLevel level, long endTick) {
            this.owner = owner;
            this.level = level;
            this.endTick = endTick;
        }

        void ensureDisplays() {
            if (displays.size() == 4) {
                return;
            }
            displays.forEach(FxBillboard::discard);
            displays.clear();
            for (int i = 0; i < 4; i++) {
                FxBillboard display = DisplayFx.spawn(level, owner.position(),
                        MagicItems.LIGHT_SHIELD.get(), 1.1F, 1.1F, 1.1F,
                        Display.BillboardConstraints.CENTER);
                if (display != null) {
                    displays.add(display);
                }
            }
        }

        void tick(long now) {
            Vec3 base = owner.position().add(0.0D, owner.getBbHeight() * 0.55D, 0.0D);
            for (int i = 0; i < displays.size(); i++) {
                double angle = now * 0.09D + i * (Math.PI / 2.0D);
                FxBillboard display = displays.get(i);
                display.setPos(base.x + Math.cos(angle) * 1.2D,
                        base.y + Math.sin(now * 0.05D + i) * 0.15D,
                        base.z + Math.sin(angle) * 1.2D);
            }
            tickLight(now);
        }

        /** 光源跟着玩家走：每 tick 续期，离开的格子立刻熄掉（不会留下一路光点） */
        void tickLight(long now) {
            BlockPos want = BlockPos.containing(owner.getX(), owner.getY() + 0.1D, owner.getZ());
            if (!want.equals(lightPos)) {
                removeLight(level, lightPos);
                lightPos = want;
            }
            placeLight(level, want, SHIELD_LIGHT_REFRESH);
        }

        void clear() {
            displays.forEach(FxBillboard::discard);
            displays.clear();
            removeLight(level, lightPos);
            lightPos = null;
        }
    }

    /** 画壁：一面固定朝向的光墙，推开靠近的生物、销毁敌方弹射物 */
    private static final class LightWall {
        final ServerPlayer owner;
        final ServerLevel level;
        final FxBillboard display;
        final Vec3 center;
        /** 墙的法线（水平朝向） */
        final Vec3 normal;
        /** 墙面朝向（展开时重新灌 transformation 要保持同一个朝向） */
        final org.joml.Quaternionf rotation;
        /** 从竖线展开成完整墙面的 tick */
        final long expandTick;
        final long endTick;
        /** 是否已经展开（只展开一次） */
        boolean expanded;
        /** 展开后在墙面上点亮的临时光源 */
        final List<BlockPos> lights = new ArrayList<>(6);

        LightWall(ServerPlayer owner, ServerLevel level, FxBillboard display,
                  Vec3 center, Vec3 normal, org.joml.Quaternionf rotation,
                  long expandTick, long endTick) {
            this.owner = owner;
            this.level = level;
            this.display = display;
            this.center = center;
            this.normal = normal;
            this.rotation = rotation;
            this.expandTick = expandTick;
            this.endTick = endTick;
        }

        void tick() {
            long now = level.getGameTime();
            // 前摇结束：从竖线左右展开（10 tick 平滑过渡）
            if (!expanded && now >= expandTick) {
                expanded = true;
                DisplayFx.reshape(display, level, new net.minecraft.world.item.ItemStack(MagicItems.LIGHT_WALL.get()),
                        6.0F, 4.0F, 1.0F, rotation, WALL_EXPAND_TICKS);
                MagicDamage.playSound(level, center, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.8F, 1.2F);
                level.sendParticles(ParticleTypes.END_ROD, center.x, center.y, center.z,
                        20, 2.6D, 1.8D, 0.4D, 0.02D);
                // 展开瞬间：墙面上下两排各 3 个光源，整面墙向外发光
                Vec3 tangent = new Vec3(-normal.z, 0.0D, normal.x);
                int lightTicks = (int) Math.max(1L, endTick - now);
                for (int row = 0; row < 2; row++) {
                    for (int col = -1; col <= 1; col++) {
                        Vec3 p = new Vec3(center.x + tangent.x * col * 2.0D,
                                center.y - 1.5D + row * 2.2D,
                                center.z + tangent.z * col * 2.0D);
                        BlockPos bp = BlockPos.containing(p);
                        placeLight(level, bp, lightTicks);
                        lights.add(bp);
                    }
                }
                glowBurst(level, center, 3.4D);
            }
            // 前摇阶段只有一根竖线，还没有形成“墙”，推开与挡弹射物等实体效果等展开后才生效
            if (!expanded) {
                return;
            }
            AABB area = new AABB(center, center).inflate(4.5D, 3.0D, 4.5D);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                if (entity == owner || !entity.isAlive() || entity.isSpectator()) {
                    continue;
                }
                Vec3 delta = entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D).subtract(center);
                double along = delta.dot(normal);
                if (Math.abs(along) > 1.2D) {
                    continue;
                }
                Vec3 lateral = delta.subtract(normal.scale(along));
                if (Math.abs(lateral.y) > 2.2D || lateral.horizontalDistance() > 3.6D) {
                    continue;
                }
                // 沿法线方向推开（离墙越近推得越狠）
                double push = (along >= 0.0D ? 1.0D : -1.0D) * (0.28D + (1.2D - Math.abs(along)) * 0.35D);
                Vec3 velocity = entity.getDeltaMovement();
                entity.setDeltaMovement(velocity.add(normal.scale(push)).add(0.0D, 0.06D, 0.0D));
                entity.hurtMarked = true;
            }
            // 销毁敌方弹射物
            AABB projArea = new AABB(center, center).inflate(4.5D, 3.0D, 4.5D);
            for (Projectile projectile : level.getEntitiesOfClass(Projectile.class, projArea)) {
                if (projectile.getOwner() == owner) {
                    continue;
                }
                Vec3 delta = projectile.position().subtract(center);
                if (Math.abs(delta.dot(normal)) > 1.2D) {
                    continue;
                }
                level.sendParticles(ParticleTypes.END_ROD,
                        projectile.getX(), projectile.getY(), projectile.getZ(), 6, 0.1D, 0.1D, 0.1D, 0.03D);
                projectile.discard();
            }
            // 墙面流光
            level.sendParticles(ParticleTypes.END_ROD,
                    center.x, center.y - 1.5D, center.z, 2, 2.4D, 1.4D, 2.4D, 0.02D);
        }

        void clear() {
            if (display.isAlive()) {
                display.discard();
            }
            lights.forEach(bp -> removeLight(level, bp));
            lights.clear();
        }
    }

    /**
     * 闪烁光球：飞行阶段按速度推进，落地后放下 15 级光源并悬浮照亮 60 秒。
     */
    private static final class LightOrb {
        final ServerPlayer owner;
        final ServerLevel level;
        final FxBillboard display;
        /** 当前世界坐标 */
        Vec3 pos;
        /** 每 tick 位移 */
        final Vec3 velocity;
        /** 最多可飞行的 tick（= 施法范围 ÷ 速度），飞满即落地，超出范围不会继续生效 */
        final int maxFlyingTicks;
        int flyingTicks;
        boolean settled;
        long settleTick;
        long endTick;
        /** 落地后放下的光源方块（没有空位可放时为 null） */
        net.minecraft.core.BlockPos lightPos;

        LightOrb(ServerPlayer owner, ServerLevel level, FxBillboard display, Vec3 pos,
                 Vec3 velocity, int maxFlyingTicks) {
            this.owner = owner;
            this.level = level;
            this.display = display;
            this.pos = pos;
            this.velocity = velocity;
            this.maxFlyingTicks = maxFlyingTicks;
        }

        /** 落地：放到脚下那一刻的位置，并尽量放一个真实光源方块（只替换空气，不破坏地形） */
        void settle(long now) {
            settled = true;
            settleTick = now;
            endTick = now + ORB_TICKS;
            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(this.pos);
            for (int up = 0; up <= 2; up++) {
                net.minecraft.core.BlockPos candidate = pos.above(up);
                if (level.getBlockState(candidate).isAir()) {
                    level.setBlock(candidate, net.minecraft.world.level.block.Blocks.LIGHT.defaultBlockState(),
                            net.minecraft.world.level.block.Block.UPDATE_ALL);
                    lightPos = candidate;
                    break;
                }
            }
            MagicDamage.playSound(level, this.pos, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.1F);
            level.sendParticles(ParticleTypes.END_ROD, this.pos.x, this.pos.y, this.pos.z,
                    14, 0.35D, 0.35D, 0.35D, 0.04D);
        }

        /** 收尾：收掉贴图，并把自己放下的光源方块清掉（只清仍是光源方块的位置，避免误删建筑） */
        void clear() {
            if (display.isAlive()) {
                display.discard();
            }
            if (lightPos != null
                    && level.getBlockState(lightPos).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                level.removeBlock(lightPos, false);
            }
        }
    }

    /** 光落漫丈光柱 */
    private static final class Pillar {
        final ServerPlayer owner;
        final ServerLevel level;
        final FxBillboard display;
        final Vec3 ground;
        final Vec3 center;
        final long endTick;
        long nextDamageTick;

        Pillar(ServerPlayer owner, ServerLevel level, FxBillboard display, Vec3 ground,
               long endTick, long nextDamageTick) {
            this.owner = owner;
            this.level = level;
            this.display = display;
            this.ground = ground;
            this.center = new Vec3(ground.x, ground.y + 6.0D, ground.z);
            this.endTick = endTick;
            this.nextDamageTick = nextDamageTick;
        }
    }

    /** 圣光大剑：巨剑实体 + 被锁定的目标 + 落点 + 时间轴 */
    private static final class Sword {
        final String skillId;
        final ServerPlayer owner;
        final ServerLevel level;
        final FxBillboard display;
        final LivingEntity target;
        /** 剑心的落点（剑尖正好踩在地面方块上时的实体中心） */
        final Vec3 rest;
        final long startTick;
        /** 前摇结束（开始下落）的 tick */
        final long windupEnd;
        /** 落地结算的 tick */
        final long impactTick;
        /** 落地后写入的冷却秒数 */
        final int cooldownSeconds;

        Sword(String skillId, ServerPlayer owner, ServerLevel level, FxBillboard display,
              LivingEntity target, Vec3 rest, long startTick, long windupEnd, long impactTick,
              int cooldownSeconds) {
            this.skillId = skillId;
            this.owner = owner;
            this.level = level;
            this.display = display;
            this.target = target;
            this.rest = rest;
            this.startTick = startTick;
            this.windupEnd = windupEnd;
            this.impactTick = impactTick;
            this.cooldownSeconds = cooldownSeconds;
        }

        /** 玩家死亡/下线等异常中断：收剑、解除定身、解除技能锁定 */
        void cancel() {
            if (display.isAlive()) {
                display.discard();
            }
            releaseRoot(target);
            PlayerMagicData data = MagicData.get(owner);
            if (data != null) {
                data.clearSkillLock(skillId);
            }
        }
    }
}
