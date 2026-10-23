package com.example.autoplace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
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

/**
 * AutoPlace 1.3 - speed telly helper.
 *
 * Rules:
 *  - Toggle with the keybind (default R). While ON it only works when
 *    right mouse button is held or being clicked fast (about 7+ CPS).
 *  - NEVER places on the ground (walking / sprinting on blocks does nothing).
 *  - Only reacts to a real jump (ground -> air with upward motion).
 *  - At most ONE block per jump.
 *  - Places only the next block of a line that you already built:
 *    the target must have REQUIRED_BEHIND solid blocks directly behind it
 *    (your 2 manual blocks), so a plain flat jump over a gap is left alone.
 *  - Camera and movement are never touched.
 */
@Mod(modid = AutoPlace.MODID, version = "1.3", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    // ---- tuning ----
    /** Earliest air tick (after takeoff) when the block may be placed. */
    private static final int MIN_AIR_TICK = 3;
    /** Last air tick when placing is still allowed. */
    private static final int MAX_AIR_TICK = 9;
    /** Solid blocks required in the line directly behind the target. */
    private static final int REQUIRED_BEHIND = 2;
    /** How far along the line we look for the first empty cell. */
    private static final int SCAN_AHEAD = 4;
    /** RMB counts as active if held now or clicked within this many ticks (3 ticks = 150 ms, covers 10+ CPS). */
    private static final int RMB_GRACE_TICKS = 3;

    private static final KeyBinding TOGGLE = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_R, "AutoPlace");

    private boolean enabled = false;
    private boolean wasOnGround = true;
    private boolean jumping = false;
    private boolean placed = false;
    private int airTicks = 0;
    private int layerY = 0;
    private int dirX = 0;
    private int dirZ = 0;
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

    private void resetJump() {
        jumping = false;
        placed = false;
        airTicks = 0;
        dirX = 0;
        dirZ = 0;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.func_71410_x();
        if (mc.field_71439_g == null || mc.field_71441_e == null) return;
        EntityPlayer p = mc.field_71439_g;

        tickCounter++;
        if (Mouse.isButtonDown(1) || mc.field_71474_y.field_74313_G.func_151470_d()) {
            lastRmbTick = tickCounter;
        }

        if (TOGGLE.func_151468_f()) {
            enabled = !enabled;
            resetJump();
            p.func_145747_a(new ChatComponentText("AutoPlace: " + (enabled ? "ON" : "OFF")));
        }

        boolean onGround = p.field_70122_C;

        if (!enabled || mc.field_71462_r != null) {
            resetJump();
            wasOnGround = onGround;
            return;
        }

        // On the ground: do nothing at all.
        if (onGround) {
            resetJump();
            wasOnGround = true;
            return;
        }

        // Takeoff detection: was on ground last tick, now in the air going up (a real jump).
        if (wasOnGround) {
            resetJump();
            if (p.field_70181_x > 0.3D) {
                double mx = p.field_70159_w;
                double mz = p.field_70179_y;
                if (Math.abs(mx) >= Math.abs(mz)) {
                    if (Math.abs(mx) > 0.02D) dirX = mx > 0 ? 1 : -1;
                } else {
                    if (Math.abs(mz) > 0.02D) dirZ = mz > 0 ? 1 : -1;
                }
                // Layer of the block we jumped from.
                layerY = MathHelper.func_76128_c(p.field_70163_u) - 1;
                jumping = (dirX != 0 || dirZ != 0);
            }
        }
        wasOnGround = false;

        if (!jumping || placed) return;

        airTicks++;
        // Only act while RMB is held / being spam clicked.
        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) return;
        if (airTicks < MIN_AIR_TICK || airTicks > MAX_AIR_TICK) return;

        ItemStack held = p.func_70694_bm();
        if (held == null || !(held.func_77973_b() instanceof ItemBlock)) return;

        int baseX = MathHelper.func_76128_c(p.field_70165_t);
        int baseZ = MathHelper.func_76128_c(p.field_70161_v);

        // Find the first empty cell along the jump line.
        BlockPos target = null;
        for (int i = 0; i <= SCAN_AHEAD; i++) {
            BlockPos c = new BlockPos(baseX + dirX * i, layerY, baseZ + dirZ * i);
            if (mc.field_71441_e.func_175623_d(c)) {
                target = c;
                break;
            }
        }
        if (target == null) return;

        // Need REQUIRED_BEHIND solid blocks directly behind the target.
        for (int k = 1; k <= REQUIRED_BEHIND; k++) {
            BlockPos b = new BlockPos(target.func_177958_n() - dirX * k, layerY, target.func_177952_p() - dirZ * k);
            if (!mc.field_71441_e.func_180495_p(b).func_177230_c().func_149688_o().func_76220_a()) return;
        }

        EnumFacing side; // face of the support block we click (pointing toward target)
        if (dirX > 0) side = EnumFacing.EAST;
        else if (dirX < 0) side = EnumFacing.WEST;
        else if (dirZ > 0) side = EnumFacing.SOUTH;
        else side = EnumFacing.NORTH;

        BlockPos support = new BlockPos(target.func_177958_n() - dirX, layerY, target.func_177952_p() - dirZ);
        Vec3 hit = new Vec3(
                support.func_177958_n() + 0.5D + side.func_82601_c() * 0.5D,
                support.func_177956_o() + 0.5D + side.func_96559_d() * 0.5D,
                support.func_177952_p() + 0.5D + side.func_82599_e() * 0.5D);

        // One attempt per jump, success or not.
        placed = true;
        if (mc.field_71442_b.func_178890_a(p, mc.field_71441_e, held, support, side, hit)) {
            p.func_71038_i();
        }
    }
     }
    
