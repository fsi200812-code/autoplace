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
 * AutoPlace 2.2
 *
 * EDGE_MODE (default): you stand on a block and run/jump toward its edge. On the
 * LAST tick you are still over that block, the mod places the next block AHEAD
 * of you against the block you stand on, so you push off from the edge.
 * It looks further along the line: blocks that already exist (the ones you placed
 * yourself) are skipped, and the first empty cell after them gets the block.
 *
 * LANDING_MODE (off by default): the old behaviour, places the block under your
 * feet on the last falling tick before landing.
 *
 * Both only work while RMB is held or clicked fast (10+ CPS). Toggle key: R.
 * Uses named MCP mappings (no SRG names).
 */
@Mod(modid = AutoPlace.MODID, version = "2.2", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    /** RMB counts as active if held now or clicked within this many ticks (3 ticks = 150 ms, covers 10+ CPS). */
    private static final int RMB_GRACE_TICKS = 3;

    /** Place the next block ahead while standing on a block, on the last tick before leaving its edge. */
    private static final boolean EDGE_MODE = true;

    /** How many cells ahead of the block you stand on the mod may reach (existing blocks are skipped). */
    private static final int AHEAD_MAX = 3;

    /**
     * How many ticks AFTER the last tick over the block the block is placed (1 tick = 50 ms).
     * 0 = on the last tick over the block, 1 = one tick later (default), 2 = two ticks later.
     * The centre of the player is then already past the edge, while the feet still touch the block.
     */
    private static final int EDGE_DELAY_TICKS = 1;

    /** Old mode: place under the feet on the last falling tick before landing. */
    private static final boolean LANDING_MODE = false;

    /** true = run at the start of the tick (a few ms later), false = at the end of the previous tick. */
    private static final boolean LATE_PHASE = true;

    /** Landing mode only: try every cell under the feet footprint and chain one helper block. */
    private static final boolean SAFETY_NET = true;

    private static final EnumFacing[] SIDES = {EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST};

    private static final KeyBinding TOGGLE = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_R, "AutoPlace");
    private boolean enabled = false;
    private int tickCounter = 0;
    private int lastRmbTick = -1000;
    private boolean havePos = false;
    private double lastX = 0;
    private double lastZ = 0;

    // pending edge placement (when EDGE_DELAY_TICKS > 0)
    private boolean pending = false;
    private int pendingTicks = 0;
    private int pendCx, pendCz, pendLayer, pendDirX, pendDirZ;

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
        if (mc.thePlayer == null || mc.theWorld == null) {
            havePos = false;
            return;
        }
        EntityPlayerSP p = mc.thePlayer;

        // Movement of the previous tick (used to predict the next one).
        double dX = 0;
        double dZ = 0;
        if (havePos) {
            dX = p.posX - lastX;
            dZ = p.posZ - lastZ;
            if (Math.abs(dX) > 1.5 || Math.abs(dZ) > 1.5) { // teleport / respawn
                dX = 0;
                dZ = 0;
            }
        }
        lastX = p.posX;
        lastZ = p.posZ;
        havePos = true;

        tickCounter++;
        if (Mouse.isButtonDown(1) || mc.gameSettings.keyBindUseItem.isKeyDown()) {
            lastRmbTick = tickCounter;
        }

        if (TOGGLE.isPressed()) {
            enabled = !enabled;
            p.addChatMessage(new ChatComponentText("AutoPlace: " + (enabled ? "ON" : "OFF")));
        }

        if (!enabled || mc.currentScreen != null) {
            pending = false;
            return;
        }

        // Only while RMB is held / being spam clicked.
        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) {
            pending = false;
            return;
        }

        ItemStack stack = p.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            pending = false;
            return;
        }

        if (EDGE_MODE) {
            if (pending) {
                pendingTicks--;
                if (pendingTicks <= 0) {
                    pending = false;
                    edgePlace(mc, p, stack);
                }
            } else if (p.onGround) {
                edgeArm(mc, p, stack, dX, dZ);
            }
        }

        if (!p.onGround && LANDING_MODE) {
            landingPlace(mc, p, stack);
        }
    }

    /** Standing on a block, last tick before the player centre leaves it: remember the block and the direction. */
    private void edgeArm(Minecraft mc, EntityPlayerSP p, ItemStack stack, double dX, double dZ) {
        if (Math.abs(dX) < 0.05D && Math.abs(dZ) < 0.05D) return; // standing still / too slow

        int dirX = 0;
        int dirZ = 0;
        if (Math.abs(dX) >= Math.abs(dZ)) dirX = dX > 0 ? 1 : -1;
        else dirZ = dZ > 0 ? 1 : -1;

        int cx = MathHelper.floor_double(p.posX);
        int cz = MathHelper.floor_double(p.posZ);
        int nx = MathHelper.floor_double(p.posX + dX);
        int nz = MathHelper.floor_double(p.posZ + dZ);
        if (nx == cx && nz == cz) return; // next tick we are still over this block, not the last tick yet

        pendCx = cx;
        pendCz = cz;
        pendDirX = dirX;
        pendDirZ = dirZ;
        pendLayer = MathHelper.floor_double(p.getEntityBoundingBox().minY - 0.001D);

        if (EDGE_DELAY_TICKS <= 0) {
            edgePlace(mc, p, stack);
        } else {
            pending = true;
            pendingTicks = EDGE_DELAY_TICKS;
        }
    }

    /** Places the block ahead of the remembered support block, skipping cells that are already filled. */
    private void edgePlace(Minecraft mc, EntityPlayerSP p, ItemStack stack) {
        BlockPos support = new BlockPos(pendCx, pendLayer, pendCz);
        if (!isSolid(mc, support)) return;

        EnumFacing side;
        if (pendDirX > 0) side = EnumFacing.EAST;
        else if (pendDirX < 0) side = EnumFacing.WEST;
        else if (pendDirZ > 0) side = EnumFacing.SOUTH;
        else side = EnumFacing.NORTH;

        for (int i = 1; i <= AHEAD_MAX; i++) {
            BlockPos cell = new BlockPos(pendCx + pendDirX * i, pendLayer, pendCz + pendDirZ * i);
            if (!mc.theWorld.isAirBlock(cell)) continue;

            BlockPos prev = new BlockPos(pendCx + pendDirX * (i - 1), pendLayer, pendCz + pendDirZ * (i - 1));
            if (!isSolid(mc, prev)) return;

            clickFace(mc, p, stack, prev, side);
            return;
        }
    }

    /** Old behaviour: last falling tick before landing, block under the feet. */
    private void landingPlace(Minecraft mc, EntityPlayerSP p, ItemStack stack) {
        if (p.motionY >= 0.0D) return;

        AxisAlignedBB bb = p.getEntityBoundingBox();
        double minY = bb.minY;
        double nextMinY = minY + p.motionY;
        int top = MathHelper.floor_double(minY);
        if (nextMinY > top) return;

        int layerY = top - 1;

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
        if (cells.isEmpty()) return;

        Collections.sort(cells, new Comparator<BlockPos>() {
            @Override
            public int compare(BlockPos a, BlockPos b) {
                double da = sq(a.getX() + 0.5 - px) + sq(a.getZ() + 0.5 - pz);
                double db = sq(b.getX() + 0.5 - px) + sq(b.getZ() + 0.5 - pz);
                return Double.compare(da, db);
            }
        });

        for (BlockPos c : cells) {
            if (placeAt(mc, p, stack, c)) return;
        }

        if (!SAFETY_NET) return;

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

    private boolean isSolid(Minecraft mc, BlockPos pos) {
        return mc.theWorld.getBlockState(pos).getBlock().getMaterial().isSolid();
    }

    /** Places a block into the empty cell against any solid neighbour. Returns true if the click was accepted. */
    private boolean placeAt(Minecraft mc, EntityPlayerSP p, ItemStack stack, BlockPos cell) {
        for (EnumFacing dir : EnumFacing.values()) {
            if (dir == EnumFacing.UP) continue;
            BlockPos neighbour = cell.offset(dir);
            if (!isSolid(mc, neighbour)) continue;
            if (clickFace(mc, p, stack, neighbour, dir.getOpposite())) return true;
        }
        return false;
    }

    /** Right-clicks the given face of a block (centre of the face). */
    private boolean clickFace(Minecraft mc, EntityPlayerSP p, ItemStack stack, BlockPos block, EnumFacing side) {
        Vec3 hit = new Vec3(
                block.getX() + 0.5 + side.getFrontOffsetX() * 0.5,
                block.getY() + 0.5 + side.getFrontOffsetY() * 0.5,
                block.getZ() + 0.5 + side.getFrontOffsetZ() * 0.5);
        if (mc.playerController.onPlayerRightClick(p, mc.theWorld, stack, block, side, hit)) {
            p.swingItem();
            return true;
        }
        return false;
    }
    }
    
