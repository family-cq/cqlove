package com.cqgzs.cqlove.fx;

import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import com.cqgzs.cqlove.network.VisualFxPayload;

/**
 * 服务端侧的「虚拟特效实体」句柄。
 *
 * <p>它<b>不是</b> {@code net.minecraft.world.entity.Entity}，世界里也没有对应实体：
 * 只保存 id + 变换状态，任何变化都折算成一个 {@link VisualFxPayload} 发给附近玩家，
 * 由客户端自己生成 Display 实体并渲染。服务端因此完全不承担实体 tick、区块实体管理、
 * 实体追踪同步的开销。</p>
 *
 * <p>生命周期：{@link FxNet} 每 40 tick 会对所有存活句柄补发一次完整描述，
 * 客户端按 {@code lifeTicks} 本地倒计时，即使 remove 包丢了最多 60 秒后自动清掉。</p>
 */
public abstract class FxHandle {

    /** 客户端兜底生命周期：60 秒（每次 keepalive 重置） */
    protected static final int DEFAULT_LIFE_TICKS = 1200;

    protected final ServerLevel level;
    protected final int id;
    protected final int kind;
    protected Vec3 pos;
    protected float scaleX;
    protected float scaleY;
    protected float scaleZ;
    protected float rotX;
    protected float rotY;
    protected float rotZ = 1.0F;
    protected float rotW;
    protected int lifeTicks = DEFAULT_LIFE_TICKS;
    protected boolean alive = true;

    /** 上次发包时的位置：避免技能每 tick 用同一坐标重复刷包 */
    private Vec3 lastSentPos;
    private long lastKeepAliveTick;
    /** > 0 时表示这是一个短命特效，到点由 FxNet 自动 discard（不再被 keepalive 续命） */
    private long autoDiscardTick = -1L;

    protected FxHandle(ServerLevel level, int id, int kind, Vec3 pos,
                       float scaleX, float scaleY, float scaleZ, @Nullable Quaternionf rotation) {
        this.level = level;
        this.id = id;
        this.kind = kind;
        this.pos = pos;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.scaleZ = scaleZ;
        setRotation(rotation);
        this.lastKeepAliveTick = level.getGameTime();
    }

    public int id() {
        return id;
    }

    public Vec3 position() {
        return pos;
    }

    public double getX() {
        return pos.x;
    }

    public double getY() {
        return pos.y;
    }

    public double getZ() {
        return pos.z;
    }

    public float getYRot() {
        return 0.0F;
    }

    /** Display 实体朝向（本方案用 transformation 定朝向，这里保留为无害空操作） */
    public void setYRot(float yaw) {
    }

    public float getXRot() {
        return 0.0F;
    }

    public void setXRot(float pitch) {
    }

    public void setOldPosAndRot() {
    }

    public boolean isAlive() {
        return alive;
    }

    public void setPos(Vec3 next) {
        setPos(next.x, next.y, next.z);
    }

    /**
     * 移动特效。位置没有实质变化时不发包（技能里常见「原地每 tick 刷一次」的写法）。
     */
    public void setPos(double x, double y, double z) {
        Vec3 next = new Vec3(x, y, z);
        this.pos = next;
        if (!alive) {
            return;
        }
        if (lastSentPos != null && lastSentPos.distanceToSqr(next) < 1.0E-6D) {
            return;
        }
        lastSentPos = next;
        FxNet.broadcast(level, next, updatePayload());
    }

    /** 销毁特效（客户端立刻移除；即使包丢了也会在 lifeTicks 后自行清理） */
    public void discard() {
        if (!alive) {
            return;
        }
        alive = false;
        FxNet.broadcast(level, pos, VisualFxPayload.remove(id, kind));
        FxNet.unregister(this);
    }

    /**
     * 设定一个短命特效的存活时长（tick）。
     *
     * <p>到点后 {@link FxNet#tickServer()} 会自动 {@link #discard()}：服务端句柄表不会泄漏，
     * 客户端也会同步移除；同时 {@code lifeTicks < 200} 会让本句柄不再参与 40 tick 的
     * keepalive 补发，避免被无限续命（空间裂痕「释放后一直不消失」就是这个原因）。</p>
     */
    public void expireAfter(int ticks) {
        int life = Math.max(1, ticks);
        this.lifeTicks = life;
        this.autoDiscardTick = level.getGameTime() + life;
        if (alive) {
            // 立刻把新的寿命同步给客户端（update 包带 lifeTicks，客户端会刷新到期时间）
            FxNet.broadcast(level, pos, updatePayload());
        }
    }

    boolean autoDiscardDue(long now) {
        return autoDiscardTick > 0L && now >= autoDiscardTick;
    }

    ServerLevel fxLevel() {
        return level;
    }

    /** {@link FxNet} 每 40 tick 调用：补发完整描述给附近玩家（覆盖丢包 + 客户端中途进服） */
    void keepAliveIfDue() {
        if (!alive) {
            return;
        }
        // 短命特效（伤害数字这类 1 秒内就消失的）不补发，否则会被一直续命
        if (lifeTicks < 200) {
            return;
        }
        long now = level.getGameTime();
        if (now - lastKeepAliveTick < 40L) {
            return;
        }
        lastKeepAliveTick = now;
        FxNet.broadcast(level, pos, spawnPayload());
    }

    void onRegistered() {
        lastKeepAliveTick = level.getGameTime();
        if (lastSentPos == null) {
            lastSentPos = pos;
        }
    }

    /** 完整描述（SPAWN）：模型 + 变换 */
    protected abstract VisualFxPayload spawnPayload();

    /** 变换描述（UPDATE）：位置 / 缩放 / 旋转 / 续期 */
    protected abstract VisualFxPayload updatePayload();

    protected void setRotation(@Nullable Quaternionf rotation) {
        Quaternionf q = rotation == null ? new Quaternionf() : new Quaternionf(rotation);
        this.rotX = q.x;
        this.rotY = q.y;
        this.rotZ = q.z;
        this.rotW = q.w;
    }
}
