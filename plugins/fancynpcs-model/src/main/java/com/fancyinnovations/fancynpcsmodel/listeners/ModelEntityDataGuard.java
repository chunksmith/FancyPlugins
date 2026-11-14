package com.fancyinnovations.fancynpcsmodel.listeners;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import kr.toxicity.model.api.tracker.EntityTrackerRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

@ChannelHandler.Sharable
public class ModelEntityDataGuard extends ChannelOutboundHandlerAdapter implements Listener {

    private static final String HANDLER_NAME = "fancynpcsmodel_entity_data_guard";
    private static final int HEALTH_INDEX = 9;

    private final Class<?> entityDataPacketClass;
    private final Method packetEntityId;
    private final Method packetItems;
    private final Method dataValueIndex;
    private final Method dataValueValue;

    public ModelEntityDataGuard() throws ReflectiveOperationException {
        entityDataPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
        packetEntityId = entityDataPacketClass.getMethod("id");
        packetItems = entityDataPacketClass.getMethod("packedItems");
        Class<?> dataValueClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue");
        dataValueIndex = dataValueClass.getMethod("id");
        dataValueValue = dataValueClass.getMethod("value");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        inject(event.getPlayer());
    }

    public void inject(Player player) {
        try {
            Channel channel = channel(player);
            if (channel.pipeline().get(HANDLER_NAME) == null && channel.pipeline().get("packet_handler") != null)
                channel.pipeline().addBefore("packet_handler", HANDLER_NAME, this);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            player.getServer().getLogger().warning("[FancyNpcsModel] Entity data guard injection failed for " + player.getName() + ": " + exception);
        }
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (isLivingDataOnModel(message)) {
            promise.setSuccess();
            return;
        }
        super.write(context, message, promise);
    }

    private boolean isLivingDataOnModel(Object message) {
        if (message.getClass() != entityDataPacketClass) return false;
        try {
            if (EntityTrackerRegistry.registry((int) packetEntityId.invoke(message)) == null) return false;
            for (Object dataValue : (List<?>) packetItems.invoke(message))
                if ((int) dataValueIndex.invoke(dataValue) == HEALTH_INDEX && dataValueValue.invoke(dataValue) instanceof Float)
                    return true;
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    private static Channel channel(Player player) throws ReflectiveOperationException {
        Object serverPlayer = player.getClass().getMethod("getHandle").invoke(player);
        Object listener = field(serverPlayer, "connection");
        Object connection = field(listener, "connection");
        return (Channel) field(connection, "channel");
    }

    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
