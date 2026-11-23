package com.cqgzs.cqlove.integration;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import com.cqgzs.cqlove.PlatformMods;
import com.cqgzs.cqlove.client.ElementClientState;
import com.cqgzs.cqlove.magic.MagicElement;

import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.TooltipPosition;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade 集成：在准星指向的生物 tooltip 里显示其附着的元素（可多个）。
 *
 * <p>元素数据由服务端 {@code ElementStatePayload} 同步到 {@link ElementClientState}，
 * 这里只读客户端缓存，不额外请求数据。</p>
 *
 * <p>本类只在 Jade 存在时被 Jade 的注解扫描加载；本模组不使用 Jade 的 API 做逻辑判断，
 * 缺 Jade 时整个 integration 包不会被触及。</p>
 */
@WailaPlugin(PlatformMods.MODID)
public final class ElementJadePlugin implements IWailaPlugin {

    private static final Identifier UID =
            Identifier.fromNamespaceAndPath(PlatformMods.MODID, "entity_elements");

    @Override
    public void register(IWailaCommonRegistration registration) {
        // 元素数据走我们自己的网络包，不用 Jade 的 server data provider
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        // registerEntityComponent 内部会自动 tryAddConfig(provider)（用 getUid() 作 key，
        // 默认值取 enabledByDefault()）；不要再手动 addConfig，否则 Duplicate config key 崩溃。
        registration.registerEntityComponent(new Provider(), Entity.class);
    }

    /** 附着元素行 provider */
    public static final class Provider implements IEntityComponentProvider {

        @Override
        public Identifier getUid() {
            return UID;
        }

        @Override
        public int getDefaultPriority() {
            return TooltipPosition.TAIL;
        }

        @Override
        public boolean enabledByDefault() {
            return true;
        }

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (!config.get(UID)) {
                return;
            }
            Entity entity = accessor.getEntity();
            if (entity == null) {
                return;
            }
            List<MagicElement> elements = ElementClientState.elementsOf(entity.getUUID());
            if (elements.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder("附着元素: ");
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) {
                    sb.append(" / ");
                }
                MagicElement e = elements.get(i);
                sb.append(e.display().replace("系", ""));
            }
            tooltip.add(Component.literal(sb.toString())
                    .withStyle(style -> style.withColor(0x9BD8FF)));
        }
    }
}
