
package com.example.autoplace;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
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
    version = "1.7",
    name = "AutoPlace",
    clientSideOnly = true,
    acceptedMinecraftVersions = "[1.8.9]"
)
public class AutoPlace {
    public static final String MODID = "autoplace";

    private static final int MAX_BLOCKS = 5;
    private static final int RMB_GRACE_TICKS = 3;

    private static final KeyBinding TOGGLE =
        new KeyBinding("Toggle AutoPlace", Keyboard.KEY_R, "AutoPlace");

    private boolean enabled = false;
    private int tickCounter = 0;
    private int lastRmbTick = -1000;
    private int placedCount = 0;
    private int cooldown = 0;
    private boolean wasActive = false;

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
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();

        if (mc.thePlayer == null || mc.theWorld == null) return;

        EntityPlayerSP p = mc.thePlayer;
        tickCounter++;

        boolean rmb = Mouse.isButtonDown(1)
                || mc.gameSettings.keyBindUseItem.isKeyDown();

        if (rmb) {
            lastRmbTick = tickCounter;
        } else {
            placedCount = 0;
            wasActive = false;
        }

        if (TOGGLE.isPressed()) {
            enabled = !enabled;
            p.addChatMessage(new ChatComponentText(
                "AutoPlace: " + (enabled ? "ON" : "OFF")
            ));
        }

        if (!enabled || mc.currentScreen != null) return;

        if (rmb && !wasActive) {
            placedCount = 0;
        }
        wasActive = rmb;

        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) return;
        if (placedCount >= MAX_BLOCKS) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        ItemStack held = p.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        // Target block directly below the player's feet.
        int y = (int) Math.floor(p.getEntityBoundingBox().minY) - 1;

        BlockPos target = new BlockPos(p.posX, y, p.posZ);

        // If occupied, try one block ahead using current movement direction.
        if (!mc.theWorld.isAirBlock(target)) {
            double dx = p.motionX;
            double dz = p.motionZ;
            double length = Math.sqrt(dx * dx + dz * dz);

            if (length < 0.01) {
                double yaw = Math.toRadians(p.rotationYaw);
                dx = -Math.sin(yaw);
                dz = Math.cos(yaw);
            } else {
                dx /= length;
                dz /= length;
            }

            target = new BlockPos(
                p.posX + dx * 0.8,
                y,
                p.posZ + dz * 0.8
            );
        }

        if (!mc.theWorld.isAirBlock(target)) return;

        // Find a solid neighbouring block.
        for (EnumFacing dir : EnumFacing.values()) {
            BlockPos neighbour = target.offset(dir);
            Block block = mc.theWorld
                .getBlockState(neighbour)
                .getBlock();

            if (!block.getMaterial().isSolid()) continue;

            EnumFacing side = dir.getOpposite();

            Vec3 hit = new Vec3(
                neighbour.getX() + 0.5
                    + side.getFrontOffsetX() * 0.5,
                neighbour.getY() + 0.5
                    + side.getFrontOffsetY() * 0.5,
                neighbour.getZ() + 0.5
                    + side.getFrontOffsetZ() * 0.5
            );

            if (mc.playerController.onPlayerRightClick(
                    p, mc.theWorld, held, neighbour, side, hit)) {
                p.swingItem();
                placedCount++;
                cooldown = 0;
            }

            return;
        }
    }
    }
            
