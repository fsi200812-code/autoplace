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

/**
 * AutoPlace 1.6 - places the block under your feet with vanilla timing.
 *
 *  - Toggle key: R.
 *  - Nothing happens unless you right click: every fresh click places one
 *    block in the same tick (never earlier than you).
 *  - While RMB is held it repeats at the vanilla hold delay (4 ticks).
 *  - No random delays, no grace window after you let go.
 * Uses named MCP mappings (no SRG names).
 */
@Mod(modid = AutoPlace.MODID, version = "1.6", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    /** Vanilla right-click delay while the button is held. */
    private static final int HOLD_DELAY_TICKS = 4;

    private static final KeyBinding TOGGLE = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_R, "AutoPlace");
    private boolean enabled = false;
    private boolean pendingClick = false;
    private int holdDelay = 0;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ClientRegistry.registerKeyBinding(TOGGLE);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Every fresh physical right click, even very short ones that fall between ticks. */
    @SubscribeEvent
    public void onMouse(MouseEvent e) {
        if (e.button == 1 && e.buttonstate) {
            pendingClick = true;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        EntityPlayerSP p = mc.thePlayer;

        if (TOGGLE.isPressed()) {
            enabled = !enabled;
            pendingClick = false;
            p.addChatMessage(new ChatComponentText("AutoPlace: " + (enabled ? "ON" : "OFF")));
        }

        boolean click = pendingClick;
        pendingClick = false;

        if (holdDelay > 0) holdDelay--;

        if (!enabled || mc.currentScreen != null) return;

        boolean held = Mouse.isButtonDown(1) || mc.gameSettings.keyBindUseItem.isKeyDown();

        // Fresh click: place now. Holding: place every HOLD_DELAY_TICKS like vanilla.
        boolean fire = click || (held && holdDelay <= 0);
        if (!fire) return;

        ItemStack stack = p.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) return;

        // Block position directly under the player's feet
        BlockPos below = new BlockPos(
                p.posX,
                p.getEntityBoundingBox().minY - 0.5,
                p.posZ);

        if (!mc.theWorld.isAirBlock(below)) return;

        // Find a solid neighbour block to place against
        for (EnumFacing dir : EnumFacing.values()) {
            if (dir == EnumFacing.UP) continue;

            BlockPos neighbour = below.offset(dir);
            Block b = mc.theWorld.getBlockState(neighbour).getBlock();
            if (!b.getMaterial().isSolid()) continue;

            EnumFacing side = dir.getOpposite();
            Vec3 hit = new Vec3(
                    neighbour.getX() + 0.5 + side.getFrontOffsetX() * 0.5,
                    neighbour.getY() + 0.5 + side.getFrontOffsetY() * 0.5,
                    neighbour.getZ() + 0.5 + side.getFrontOffsetZ() * 0.5);

            if (mc.playerController.onPlayerRightClick(p, mc.theWorld, stack, neighbour, side, hit)) {
                p.swingItem();
                holdDelay = HOLD_DELAY_TICKS;
            }
            return;
        }
    }
            }
