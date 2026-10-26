package com.example.autoplace;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
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
 * AutoPlace 2.3 - works only while the right mouse button is held or being clicked.
 * It never touches your movement, jump or camera: it only places blocks.
 *
 * Pattern mode (default, key G toggles): builds the speed-telly staircase for you:
 * up, forward, forward, up, forward, then repeats. Each forward block is placed when you
 * are at least MIN_PROGRESS of the way across the previous block (middle or a bit later).
 *
 * Single mode (pattern mode off): one block per jump, at the last moment before landing.
 *
 * Toggle key for the whole mod: R. Uses named MCP mappings (no func_/field_ names).
 */
@Mod(modid = AutoPlace.MODID, version = "2.3", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    /** RMB counts as active if held now or clicked within this many ticks (3 ticks = 150 ms, covers 10+ CPS). */
    private static final int RMB_GRACE_TICKS = 3;

    /**
     * How far across a block (0.0 = its back edge, 0.5 = middle, 1.0 = front edge, in your
     * direction of movement) you must be before the NEXT block is placed. 0.6 = a bit past
     * the middle. Raise it to place LATER, lower it to place EARLIER.
     */
    private static final double MIN_PROGRESS = 0.6;

    /** Single mode only: raise to place a bit EARLIER in the fall, lower (down to 0) to place LATER. */
    private static final double LATE_SLACK = 0.15;

    /** Stair pattern: 0 = block on TOP of the previous one, 1 = block FORWARD (side of the previous one). */
    private static final int[] PATTERN = {0, 1, 1, 0, 1};
    /** Max distance from your eyes to the face the mod places against (vanilla survival reach is about 4.5). */
    private static final double REACH = 4.5;
    /** Pattern mode: do not place a block farther than this (blocks, horizontally) from you. */
    private static final double LEAD_H = 2.5;
    /** Pattern mode: do not place a block higher than your feet + this (blocks). */
    private static final double LEAD_UP = 0.6;
    /** Delay between pattern blocks in ticks: PATTERN_DELAY_MIN + random(0..PATTERN_DELAY_RANGE-1). */
    private static final int PATTERN_DELAY_MIN = 0;
    private static final int PATTERN_DELAY_RANGE = 1;
    /** How many pattern blocks may be placed within ONE tick (2 = fast, 1 = safer). */
    private static final int MAX_BLOCKS_PER_TICK = 2;
    /** true = the staircase keeps building without waiting for you to land. */
    private static final boolean CONTINUOUS = true;

    /**
     * true = before every placement the mod tells the server you are looking at the face it
     * places against, so the block is placed even when your crosshair is NOT on it.
     * Your own camera is not moved.
     */
    private static final boolean AIM_AT_FACE = true;

    private static final KeyBinding TOGGLE = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_R, "AutoPlace");
    private static final KeyBinding PATTERN_TOGGLE = new KeyBinding("Toggle stair pattern mode", Keyboard.KEY_G, "AutoPlace");

    private final Random rand = new Random();
    private boolean enabled = false;
    private boolean patternMode = true;
    private int cooldown = 0;
    private int tickCounter = 0;
    private int lastRmbTick = -1000;
    /** Height of the ground (feet Y) you last stood on: the new blocks go at this level. */
    private double lastGroundY = Double.NaN;

    private int patIdx = 0;
    private boolean patDone = false;
    private BlockPos patTip = null;
    private EnumFacing patDir = EnumFacing.NORTH;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ClientRegistry.registerKeyBinding(TOGGLE);
        ClientRegistry.registerKeyBinding(PATTERN_TOGGLE);
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
            resetPattern();
            p.addChatMessage(new ChatComponentText("AutoPlace: " + (enabled ? "ON" : "OFF")));
        }
        if (PATTERN_TOGGLE.isPressed()) {
            patternMode = !patternMode;
            resetPattern();
            p.addChatMessage(new ChatComponentText("AutoPlace stair pattern: " + (patternMode ? "ON" : "OFF")));
        }

        if (!enabled || mc.currentScreen != null) return;

        // RMB released and standing on the ground: forget an unfinished pattern.
        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) {
            if (p.onGround) resetPattern();
            return;
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        ItemStack held = p.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        if (patternMode) {
            patternTick(mc, p, held);
        } else {
            singleTick(mc, p, held);
        }
    }

    // ------------------------------------------------------------------ single block mode

    private void singleTick(Minecraft mc, EntityPlayerSP p, ItemStack held) {
        if (Double.isNaN(lastGroundY)) return;
        if (p.onGround) return;      // standing / walking: never place
        if (p.motionY >= 0) return;  // still rising: never place

        // Last tick of the jump: place only when you are about to come down to ground level.
        double nextFeetY = p.getEntityBoundingBox().minY + p.motionY;
        if (nextFeetY > lastGroundY + LATE_SLACK) return;

        // Block at ground level under where you are now.
        BlockPos below = new BlockPos(p.posX, lastGroundY - 0.5, p.posZ);
        if (!mc.theWorld.isAirBlock(below)) return;

        // Be at least MIN_PROGRESS of the way across this block before placing.
        double mx = p.motionX;
        double mz = p.motionZ;
        if (Math.abs(mx) + Math.abs(mz) > 0.03) {
            double progress;
            if (Math.abs(mx) >= Math.abs(mz)) {
                double f = p.posX - Math.floor(p.posX);
                progress = mx > 0 ? f : 1.0 - f;
            } else {
                double f = p.posZ - Math.floor(p.posZ);
                progress = mz > 0 ? f : 1.0 - f;
            }
            if (progress < MIN_PROGRESS) return;
        }

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

            if (placeAimed(mc, p, held, neighbour, side, hit)) {
                p.swingItem();
                cooldown = 2 + rand.nextInt(3); // random delay 2-4 ticks
            }
            return;
        }
    }

    // ------------------------------------------------------------------ stair pattern mode

    /** One tick of the stair pattern: places up to MAX_BLOCKS_PER_TICK blocks. */
    private void patternTick(Minecraft mc, EntityPlayerSP p, ItemStack held) {
        for (int i = 0; i < MAX_BLOCKS_PER_TICK; i++) {
            if (!patternStep(mc, p, held)) break;
            ItemStack now = p.getHeldItem();
            if (now == null || !(now.getItem() instanceof ItemBlock)) break;
            held = now;
        }
    }

    /** Tries to place the next block of PATTERN; true if a block was placed (or a filled spot skipped). */
    private boolean patternStep(Minecraft mc, EntityPlayerSP p, ItemStack held) {
        if (patDone) {
            if (p.onGround) resetPattern(); // pattern finished: wait until you land, then be ready again
            return false;
        }

        if (patTip == null) {
            // Start of a cycle: begins once you are in the air, from the block you jumped off.
            if (p.onGround || Double.isNaN(lastGroundY)) return false;
            BlockPos start = new BlockPos(p.posX, lastGroundY - 0.5, p.posZ);
            if (!mc.theWorld.getBlockState(start).getBlock().getMaterial().isSolid()) return false;
            patTip = start;
            patIdx = 0;
            updateDirection(p);
        }

        EnumFacing face = PATTERN[patIdx] == 0 ? EnumFacing.UP : patDir;
        BlockPos target = patTip.offset(face);

        // Spot already filled: count the step as done and move on from that block.
        if (!mc.theWorld.isAirBlock(target)) {
            advancePattern(p, target);
            return true;
        }

        // Timing: EVERY block (up or forward) is placed only once you are MIN_PROGRESS of the way
        // across the block you are heading over (the last placed one). Before that, nothing is placed.
        if (progressAcross(p, patTip, patDir) < MIN_PROGRESS) return false;

        // Never build ahead of the player: only place blocks close to where you are right now.
        double cx = target.getX() + 0.5;
        double cz = target.getZ() + 0.5;
        if (Math.hypot(cx - p.posX, cz - p.posZ) > LEAD_H) return false;
        if (target.getY() > p.getEntityBoundingBox().minY + LEAD_UP) return false;

        Vec3 hit = new Vec3(
                patTip.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                patTip.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
                patTip.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5);
        Vec3 eye = new Vec3(p.posX, p.posY + p.getEyeHeight(), p.posZ);
        if (eye.distanceTo(hit) > REACH) return false;

        // If the block cannot be placed yet (for example you are still inside that spot while
        // rising), this returns false and the mod simply tries again next tick.
        if (placeAimed(mc, p, held, patTip, face, hit)) {
            p.swingItem();
            cooldown = PATTERN_DELAY_MIN + rand.nextInt(PATTERN_DELAY_RANGE);
            advancePattern(p, target);
            return true;
        }
        return false;
    }

    /** How far across the given block cell you are, in direction dir (0 = back edge, 1 = front edge). */
    private double progressAcross(EntityPlayerSP p, BlockPos cell, EnumFacing dir) {
        switch (dir) {
            case EAST:  return p.posX - cell.getX();
            case WEST:  return (cell.getX() + 1) - p.posX;
            case SOUTH: return p.posZ - cell.getZ();
            case NORTH: return (cell.getZ() + 1) - p.posZ;
            default:    return 1.0;
        }
    }

    /** "Forward" = the direction you are moving, snapped to north/south/east/west. */
    private void updateDirection(EntityPlayerSP p) {
        double mx = p.motionX;
        double mz = p.motionZ;
        if (Math.abs(mx) + Math.abs(mz) > 0.05) {
            if (Math.abs(mx) > Math.abs(mz)) {
                patDir = mx > 0 ? EnumFacing.EAST : EnumFacing.WEST;
            } else {
                patDir = mz > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
            }
        }
    }

    private void advancePattern(EntityPlayerSP p, BlockPos placed) {
        patTip = placed;
        patIdx++;
        if (patIdx >= PATTERN.length) {
            if (CONTINUOUS) {
                patIdx = 0;          // start the next cycle right away from the last block
                updateDirection(p);  // so a turn you made is picked up here
            } else {
                patDone = true;
            }
        }
    }

    private void resetPattern() {
        patIdx = 0;
        patDone = false;
        patTip = null;
    }

    // ------------------------------------------------------------------ shared

    /** Right-click on a block face, first telling the server we are looking at that exact spot. */
    private boolean placeAimed(Minecraft mc, EntityPlayerSP p, ItemStack held, BlockPos pos, EnumFacing face, Vec3 hit) {
        if (AIM_AT_FACE) {
            double dx = hit.xCoord - p.posX;
            double dy = hit.yCoord - (p.posY + p.getEyeHeight());
            double dz = hit.zCoord - p.posZ;
            double flat = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) (-Math.toDegrees(Math.atan2(dy, flat)));
            p.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(yaw, pitch, p.onGround));
        }
        return mc.playerController.onPlayerRightClick(p, mc.theWorld, held, pos, face, hit);
    }
            }
        
