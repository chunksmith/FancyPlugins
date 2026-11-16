package com.fancyinnovations.fancynpcsmodel.listeners;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DisplayEntityDataGuard implements Listener {

    private static final String HANDLER_NAME = "fancynpcsmodel_display_data_guard";
    private static final String ENCODER_NAME = "encoder";
    private static final int HEALTH_INDEX = 9;

    private final Class<?> addEntityPacketClass;
    private final Class<?> removeEntitiesPacketClass;
    private final Class<?> entityDataPacketClass;
    private final Method addedEntityId;
    private final Method addedEntityType;
    private final Method removedEntityIds;
    private final Method entityDataId;
    private final Method entityDataItems;
    private final Method dataValueIndex;
    private final Method dataValueValue;
    private final Set<Object> displayTypes = new HashSet<>();

    public DisplayEntityDataGuard() throws ReflectiveOperationException {
        addEntityPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
        removeEntitiesPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
        entityDataPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
        addedEntityId = addEntityPacketClass.getMethod("getId");
        addedEntityType = addEntityPacketClass.getMethod("getType");
        removedEntityIds = removeEntitiesPacketClass.getMethod("getEntityIds");
        entityDataId = entityDataPacketClass.getMethod("id");
        entityDataItems = entityDataPacketClass.getMethod("packedItems");
        Class<?> dataValueClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue");
        dataValueIndex = dataValueClass.getMethod("id");
        dataValueValue = dataValueClass.getMethod("value");
        Class<?> entityTypeClass = Class.forName("net.minecraft.world.entity.EntityTypes");
        for (String name : List.of("ITEM_DISPLAY", "BLOCK_DISPLAY", "TEXT_DISPLAY"))
            displayTypes.add(entityTypeClass.getField(name).get(null));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        inject(event.getPlayer());
    }

    public void inject(Player player) {
        try {
            ChannelPipeline pipeline = channel(player).pipeline();
            if (pipeline.get(HANDLER_NAME) == null && pipeline.get(ENCODER_NAME) != null)
                pipeline.addAfter(ENCODER_NAME, HANDLER_NAME, new Guard());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            player.getServer().getLogger().warning("[FancyNpcsModel] Display data guard injection failed for " + player.getName() + ": " + exception);
        }
    }

    private class Guard extends ChannelOutboundHandlerAdapter {

        private final Set<Integer> displayIds = new HashSet<>();

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            if (isLivingDataOnDisplay(message)) {
                promise.setSuccess();
                return;
            }
            super.write(context, message, promise);
        }

        private boolean isLivingDataOnDisplay(Object message) {
            try {
                Class<?> type = message.getClass();
                if (type == addEntityPacketClass) {
                    int entityId = (int) addedEntityId.invoke(message);
                    if (displayTypes.contains(addedEntityType.invoke(message))) displayIds.add(entityId);
                    else displayIds.remove(entityId);
                } else if (type == removeEntitiesPacketClass) {
                    displayIds.removeAll((List<?>) removedEntityIds.invoke(message));
                } else if (type == entityDataPacketClass && displayIds.contains((int) entityDataId.invoke(message))) {
                    return hasHealth(message);
                }
            } catch (ReflectiveOperationException ignored) {
            }
            return false;
        }

        private boolean hasHealth(Object entityDataPacket) throws ReflectiveOperationException {
            for (Object dataValue : (List<?>) entityDataItems.invoke(entityDataPacket))
                if ((int) dataValueIndex.invoke(dataValue) == HEALTH_INDEX && dataValueValue.invoke(dataValue) instanceof Float)
                    return true;
            return false;
        }
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
