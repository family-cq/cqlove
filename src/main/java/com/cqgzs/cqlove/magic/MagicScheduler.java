package com.cqgzs.cqlove.magic;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;

/**
 * 服务端技能任务调度器：给"延时触发 / 持续多 tick"的技能效果用。
 *
 * <p>典型场景（火系）：</p>
 * <ul>
 *   <li>九宫：第一段爆炸后 2 秒再炸第二段（{@link #later}）</li>
 *   <li>地煞：岩浆地表持续 6 秒，每秒结算一次灼烧（{@link #every}）</li>
 *   <li>天炎葬礼：9 个火焰石头每隔几 tick 落下一个（{@link #every}）</li>
 * </ul>
 *
 * <p>所有任务只存在于运行时，重启服务端后丢失（技能效果都是短时的，无需存档）。
 * 任务绑定维度：世界卸载或换维度后旧任务自动失效（ level 引用不同即跳过）。</p>
 */
public final class MagicScheduler {

    /** 一个调度任务：每 interval tick 执行一次 action，remaining 到 0 后执行 onEnd 并移除 */
    private record Task(ServerLevel level, int interval, int countdown, int remaining,
                        Runnable action, Runnable onEnd) {
    }

    private static final List<Task> TASKS = new ArrayList<>();

    private MagicScheduler() {
    }

    /** delayTicks 后执行一次 action */
    public static void later(ServerLevel level, int delayTicks, Runnable action) {
        TASKS.add(new Task(level, Math.max(1, delayTicks), Math.max(1, delayTicks), 1, action, null));
    }

    /**
     * 每隔 intervalTicks 执行一次 action，持续 totalTicks（第一次在执行调度后的 interval 触发）。
     *
     * @param onEnd 结束时回调（可为 null），例如清理临时实体
     */
    public static void every(ServerLevel level, int intervalTicks, int totalTicks, Runnable action, Runnable onEnd) {
        int times = Math.max(1, totalTicks / Math.max(1, intervalTicks));
        TASKS.add(new Task(level, Math.max(1, intervalTicks), Math.max(1, intervalTicks), times, action, onEnd));
    }

    /** 服务端每 tick 驱动（MagicEvents.onServerTick 调用）。用下标遍历，允许任务执行时追加新任务 */
    public static void tick() {
        for (int i = 0; i < TASKS.size(); i++) {
            Task task = TASKS.get(i);
            int countdown = task.countdown() - 1;
            int remaining = task.remaining();
            if (countdown <= 0) {
                try {
                    task.action().run();
                } catch (Exception exception) {
                    // 单个技能任务出错不能拖垮服务器主线程
                    com.cqgzs.cqlove.PlatformMods.LOGGER.error("[cqlove] 技能调度任务异常", exception);
                }
                remaining--;
                countdown = task.interval();
            }
            if (remaining <= 0) {
                TASKS.remove(i);
                i--;
                if (task.onEnd() != null) {
                    try {
                        task.onEnd().run();
                    } catch (Exception exception) {
                        com.cqgzs.cqlove.PlatformMods.LOGGER.error("[cqlove] 技能调度收尾异常", exception);
                    }
                }
            } else {
                TASKS.set(i, new Task(task.level(), task.interval(), countdown, remaining,
                        task.action(), task.onEnd()));
            }
        }
    }
}
