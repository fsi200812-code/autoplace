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

import java.util.Random;

/**
 * AutoPlace 1.6 - places the block under your feet against a solid neighbour only while
 * the right mouse button is held or being clicked, and only at the LAST moment of the
 * jump (just before landing), not right after takeoff. Toggle key: R.
 * Uses named MCP mappings (no func_/field_ names).
 */
@Mod(modid = AutoPlace.MODID, version = "1.7", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    /** RMB counts as active if held now or clicked within this many ticks (3 ticks = 150 ms, covers 10+ CPS). */
    private static final int RMB_GRACE_TICKS = 3;

    /**
     * "Late" placement: the block is placed only when you are falling and will reach the
     * top of the target block on this tick or the next one. Raise this value to place
     * a bit EARLIER, lower it (down to 0) to place even LATER.
     */
    private static final double LATE_SLACK = 0.05;

    /** How many ticks ahead the target block is aimed (0 = under you now, 1 = under where you will be next tick). */
    private static final double LOOKAHEAD_TICKS = 1.0;

    /** Height of the ground (feet Y) you last stood on. The new block goes at THIS level, not higher. */
    private double lastGroundY = Double.NaN;

    private static final KeyBinding TOGGLE = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_R, "AutoPlace");
    private final Random rand = new Random();
    private boolean enabled = false;
    private int cooldown = 0;
    private int tickCounter = 0;
    private int lastRmbTick = -1000;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ClientRegistry.registerKeyBinding(TOGGLE);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Catches every physical right click, even very short ones that fall between ticks. */
    @SubscribeEvent
    public void onMouse(MouseEvent e) {
        if (e.button == 1 && e.buttonstate) {
            lastRmbTick = tickCounter;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        EntityPlayerSP p = mc.thePlayer;

        tickCounter++;
        if (p.onGround) {
            lastGroundY = p.getEntityBoundingBox().minY;
        }
        if (Mouse.isButtonDown(1) || mc.gameSettings.keyBindUseItem.isKeyDown()) {
            lastRmbTick = tickCounter;
        }

        if (TOGGLE.isPressed()) {
            enabled = !enabled;
            p.addChatMessage(new ChatComponentText("AutoPlace: " + (enabled ? "ON" : "OFF")));
        }

        if (!enabled || mc.currentScreen != null) return;

        // Only work while RMB is held / being spam clicked.
        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        ItemStack held = p.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        if (Double.isNaN(lastGroundY)) return;
        if (p.onGround) return;      // standing / walking: never place
        if (p.motionY >= 0) return;  // still rising: never place

        // LAST-TICK gate: place only when the next tick would take your feet down to the
        // ground level you jumped from (i.e. you are about to land).
        double nextFeetY = p.getEntityBoundingBox().minY + p.motionY;
        if (nextFeetY > lastGroundY + LATE_SLACK) return;

        // Target block: at the level of the ground you jumped from, under the spot you are
        // about to land on (not under where you were mid-air earlier in the jump).
        BlockPos below = new BlockPos(
                p.posX + p.motionX * LOOKAHEAD_TICKS,
                lastGroundY - 0.5,
                p.posZ + p.motionZ * LOOKAHEAD_TICKS);

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

            if (mc.playerController.onPlayerRightClick(p, mc.theWorld, held, neighbour, side, hit)) {
                p.swingItem();
                cooldown = 2 + rand.nextInt(3); // random delay 2-4 ticks
            }
            return;
        }
    }
    }

                
