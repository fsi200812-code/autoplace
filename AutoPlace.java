
package com.example.autoplace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

@Mod(
    modid = AutoPlace.MODID,
    name = "AutoPlace",
    version = "1.10",
    clientSideOnly = true,
    acceptedMinecraftVersions = "[1.8.9]"
)
public class AutoPlace {

    public static final String MODID = "autoplace";

    private static final int RMB_GRACE_TICKS = 3;

    private static final KeyBinding TOGGLE =
        new KeyBinding(
            "Toggle AutoPlace",
            Keyboard.KEY_R,
            "AutoPlace"
        );

    private boolean enabled = false;
    private int tickCounter = 0;
    private int lastRmbTick = -1000;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(TOGGLE);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.button == 1 && event.buttonstate) {
            lastRmbTick = tickCounter;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        EntityPlayerSP player = mc.thePlayer;

        tickCounter++;

        if (Mouse.isButtonDown(1)
                || mc.gameSettings.keyBindUseItem.isKeyDown()) {
            lastRmbTick = tickCounter;
        }

        if (TOGGLE.isPressed()) {
            enabled = !enabled;

            player.addChatMessage(
                new ChatComponentText(
                    "AutoPlace: " + (enabled ? "ON" : "OFF")
                )
            );
        }

        if (!enabled || mc.currentScreen != null) {
            return;
        }

        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) {
            return;
        }

        if (player.onGround || player.motionY >= 0.0D) {
            return;
        }

        ItemStack stack = player.getHeldItem();

        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            return;
        }

        AxisAlignedBB box = player.getEntityBoundingBox();

        double minY = box.minY;
        double nextMinY = minY + player.motionY;

        int top = MathHelper.floor_double(minY);

        if (nextMinY > top) {
            return;
        }

        int layerY = top - 1;

        final double px = player.posX;
        final double pz = player.posZ;

        List<BlockPos> cells = new ArrayList<BlockPos>();

        int x0 = MathHelper.floor_double(box.minX);
        int x1 = MathHelper.floor_double(box.maxX);
        int z0 = MathHelper.floor_double(box.minZ);
        int z1 = MathHelper.floor_double(box.maxZ);

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                BlockPos pos = new BlockPos(x, layerY, z);

                if (mc.theWorld.isAirBlock(pos)) {
                    cells.add(pos);
                }
            }
        }

        if (cells.isEmpty()) {
            return;
        }

        Collections.sort(
            cells,
            new Comparator<BlockPos>() {
                @Override
                public int compare(BlockPos a, BlockPos b) {
                    double da =
                        square(a.getX() + 0.5D - px)
                        + square(a.getZ() + 0.5D - pz);

                    double db =
                        square(b.getX() + 0.5D - px)
                        + square(b.getZ() + 0.5D - pz);

                    return Double.compare(da, db);
                }
            }
        );

        for (BlockPos pos : cells) {
            if (placeAt(mc, player, stack, pos)) {
                return;
            }
        }
    }

    private static double square(double value) {
        return value * value;
    }

    private boolean placeAt(
        Minecraft mc,
        EntityPlayerSP player,
        ItemStack stack,
        BlockPos target
    ) {
        for (EnumFacing direction : EnumFacing.values()) {

            if (direction == EnumFacing.UP) {
                continue;
            }

            BlockPos neighbour = target.offset(direction);

            if (!mc.theWorld.getBlockState(neighbour)
                    .getBlock()
                    .getMaterial()
                    .isSolid()) {
                continue;
            }

            EnumFacing side = direction.getOpposite();

            Vec3 hit = new Vec3(
                neighbour.getX() + 0.5D
                    + side.getFrontOffsetX() * 0.5D,
                neighbour.getY() + 0.5D
                    + side.getFrontOffsetY() * 0.5D,
                neighbour.getZ() + 0.5D
                    + side.getFrontOffsetZ() * 0.5D
            );

            if (mc.playerController.onPlayerRightClick(
                    player,
                    mc.theWorld,
                    stack,
                    neighbour,
                    side,
                    hit)) {

                player.swingItem();
                return true;
            }
        }

        return false;
    }
    }
        
