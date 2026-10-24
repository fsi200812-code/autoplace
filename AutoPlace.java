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
@Mod(modid = AutoPlace.MODID, version = "1.6", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    /** RMB counts as active if held now or clicked within this many ticks (3 ticks = 150 ms, covers 10+ CPS). */
    private static final int RMB_GRACE_TICKS = 3;

    /**
     * "Late" placement: the block is placed only when you are falling and will reach the
     * top of the target block on this tick or the next one. Raise this value to place
     * a bit EARLIER, lower it (down to 0) to place even LATER.
     */
    private static final double LATE_SLACK = 0.15;

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

        Minecraft mc = Minecraft.func_71410_x();
        if (mc.field_71439_g == null || mc.field_71441_e == null) return;
        EntityPlayerSP p = mc.field_71439_g;

        tickCounter++;
        if (Mouse.isButtonDown(1) || mc.field_71474_y.field_74313_G.func_151470_d()) {
            lastRmbTick = tickCounter;
        }

        if (TOGGLE.func_151468_f()) {
            enabled = !enabled;
            p.func_145747_a(new ChatComponentText("AutoPlace: " + (enabled ? "ON" : "OFF")));
        }

        if (!enabled || mc.field_71462_r != null) return;

        // Only work while RMB is held / being spam clicked.
        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        ItemStack held = p.func_70694_bm();
        if (held == null || !(held.func_77973_b() instanceof ItemBlock)) return;

        // Block position directly under the player's feet
        BlockPos below = new BlockPos(
                p.field_70165_t,
                p.func_174813_aQ().field_72338_b - 0.5,
                p.field_70161_v);

        if (!mc.field_71441_e.func_175623_d(below)) return;

        // LATE gate: only place at the last tick of the jump (falling, about to land).
        // Never while standing/walking on the ground and never while still rising.
        if (p.field_70122_E) return;
        if (p.field_70181_x >= 0) return;
        double nextFeetY = p.func_174813_aQ().field_72338_b + p.field_70181_x;
        double blockTop = below.func_177956_o() + 1.0;
        if (nextFeetY > blockTop + LATE_SLACK) return;

        // Find a solid neighbour block to place against
        for (EnumFacing dir : EnumFacing.values()) {
            if (dir == EnumFacing.UP) continue;

            BlockPos neighbour = below.func_177972_a(dir);
            Block b = mc.field_71441_e.func_180495_p(neighbour).func_177230_c();
            if (!b.func_149688_o().func_76220_a()) continue;

            EnumFacing side = dir.func_176734_d();
            Vec3 hit = new Vec3(
                    neighbour.func_177958_n() + 0.5 + side.func_82601_c() * 0.5,
                    neighbour.func_177956_o() + 0.5 + side.func_96559_d() * 0.5,
                    neighbour.func_177952_p() + 0.5 + side.func_82599_e() * 0.5);

            if (mc.field_71442_b.func_178890_a(p, mc.field_71441_e, held, neighbour, side, hit)) {
                p.func_71038_i();
                cooldown = 2 + rand.nextInt(3); // random delay 2-4 ticks
            }
            return;
        }
    }
    }

