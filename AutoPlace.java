package com.example.autoplace;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * AutoPlace 1.9 - places the block under your feet on the LAST tick before
 * landing, with a safety net so you don't fall.
 *
 *  - Toggle key: R.
 *  - Only works while RMB is held or being clicked fast (10+ CPS).
 *  - Only in the air and only while falling, exactly on the last tick before landing.
 *  - Safety net (SAFETY_NET): looks at every cell under your feet footprint
 *    (not just the centre one), and if none can be placed directly, builds
 *    one extra block next to existing blocks and then the one under you.
 * Uses named MCP mappings (no SRG names).
 */
@Mod(modid = AutoPlace.MODID, version = "1.9", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    /** RMB counts as active if held now or clicked within this many ticks (3 ticks = 150 ms, covers 10+ CPS). */
    private static final int RMB_GRACE_TICKS = 3;

    /**
     * true  = place at the very START of the landing tick (a few ms later, right before your move).
     * false = place at the END of the previous tick (a few ms earlier).
     * The tick before landing is the last moment a block can be placed at all.
     */
    private static final boolean LATE_PHASE = true;

    /** Extra reach so you don't fall: try every cell under the feet footprint and chain one helper block. */
    private static final boolean SAFETY_NET = true;

    private static final EnumFacing[] SIDES = {EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST};

    private static final KeyBinding TOGGLE = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_R, "AutoPlace");
    private boolean enabled = false;
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
        if (e.phase != (LATE_PHASE ? TickEvent.Phase.START : TickEvent.Phase.END)) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        EntityPlayerSP p = mc.thePlayer;

        tickCounter++;
        if (Mouse.isButtonDown(1) || mc.gameSettings.keyBindUseItem.isKeyDown()) {
            lastRmbTick = tickCounter;
        }

        if (TOGGLE.isPressed()) {
            enabled = !enabled;
            p.addChatMessage(new ChatComponentText("AutoPlace: " + (enabled ? "ON" : "OFF")));
        }

        if (!enabled || mc.currentScreen != null) return;

        // Only while RMB is held / being spam clicked.
        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) return;

        // Only in the air and falling.
        if (p.onGround || p.motionY >= 0.0D) return;

        // motionY is the movement that will be applied in the next move.
        AxisAlignedBB bb = p.getEntityBoundingBox();
        double minY = bb.minY;
        double nextMinY = minY + p.motionY;

        // Top of the first block layer below the feet.
        int top = MathHelper.floor_double(minY);

        // Last tick: the next move would take the feet through that layer's top.
        if (nextMinY > top) return;

        ItemStack stack = p.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) return;

        int layerY = top - 1;

        // Cells under the feet footprint, nearest to the player's centre first.
        final double px = p.posX;
        final double pz = p.posZ;
        List<BlockPos> cells = new ArrayList<BlockPos>();
        int x0 = MathHelper.floor_double(SAFETY_NET ? bb.minX : px);
        int x1 = MathHelper.floor_double(SAFETY_NET ? bb.maxX : px);
        int z0 = MathHelper.floor_double(SAFETY_NET ? bb.minZ : pz);
        int z1 = MathHelper.floor_double(SAFETY_NET ? bb.maxZ : pz);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                BlockPos c = new BlockPos(x, layerY, z);
                if (mc.theWorld.isAirBlock(c)) cells.add(c);
            }
        }
        if (cells.isEmpty()) return; // something solid already under us

        Collections.sort(cells, new Comparator<BlockPos>() {
            @Override
            public int compare(BlockPos a, BlockPos b) {
                double da = sq(a.getX() + 0.5 - px) + sq(a.getZ() + 0.5 - pz);
                double db = sq(b.getX() + 0.5 - px) + sq(b.getZ() + 0.5 - pz);
                return Double.compare(da, db);
            }
        });

        // 1) Place directly under the feet, centre cell first.
        for (BlockPos c : cells) {
            if (placeAt(mc, p, stack, c)) return;
        }

        if (!SAFETY_NET) return;

        // 2) Safety net: place one helper block next to an existing block, then the cell under us.
        for (BlockPos c : cells) {
            for (EnumFacing dir : SIDES) {
                BlockPos helper = c.offset(dir);
                if (!mc.theWorld.isAirBlock(helper)) continue;
                if (placeAt(mc, p, stack, helper)) {
                    placeAt(mc, p, stack, c);
                    return;
                }
            }
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Places a block into the empty cell against any solid neighbour. Returns true if the click was accepted. */
    private boolean placeAt(Minecraft mc, EntityPlayerSP p, ItemStack stack, BlockPos cell) {
        for (EnumFacing dir : EnumFacing.values()) {
            if (dir == EnumFacing.UP) continue;

            BlockPos neighbour = cell.offset(dir);
            if (!mc.theWorld.getBlockState(neighbour).getBlock().getMaterial().isSolid()) continue;

            EnumFacing side = dir.getOpposite();
            Vec3 hit = new Vec3(
                    neighbour.getX() + 0.5 + side.getFrontOffsetX() * 0.5,
                    neighbour.getY() + 0.5 + side.getFrontOffsetY() * 0.5,
                    neighbour.getZ() + 0.5 + side.getFrontOffsetZ() * 0.5);

            if (mc.playerController.onPlayerRightClick(p, mc.theWorld, stack, neighbour, side, hit)) {
                p.swingItem();
                return true;
            }
        }
        return false;
    }
                }
