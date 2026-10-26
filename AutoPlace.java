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
 * AutoPlace 1.8 - adds STAIR PATTERN mode (key G): while RMB is held the mod builds the
 * speed-telly staircase for you: up, forward, forward, up, forward (then repeats on the next
 * jump). You only jump, steer and turn; the mod does the placing. With pattern mode off it
 * works as the old single-block mod (1.6 behaviour below). Toggle key: R.
 * Older: AutoPlace 1.6 - places the block under your feet against a solid neighbour only while
 * the right mouse button is held or being clicked, and only at the LAST moment of the
 * jump (just before landing), not right after takeoff. Toggle key: R.
 * Uses named MCP mappings (no func_/field_ names).
 */
@Mod(modid = AutoPlace.MODID, version = "1.8", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
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
    private static final KeyBinding PATTERN_TOGGLE = new KeyBinding("Toggle stair pattern mode", Keyboard.KEY_G, "AutoPlace");

    /** Stair pattern: 0 = block on TOP of the previous one, 1 = block FORWARD (side of the previous one). */
    private static final int[] PATTERN = {0, 1, 1, 0, 1};
    /** Max distance from your eyes to the face the mod places against (vanilla survival reach is about 4.5). */
    private static final double REACH = 4.5;
    /** Delay between pattern blocks in ticks: PATTERN_DELAY_MIN + random(0..PATTERN_DELAY_RANGE-1). */
    private static final int PATTERN_DELAY_MIN = 1;
    private static final int PATTERN_DELAY_RANGE = 2;

    private boolean patternMode = true;
    private int patIdx = 0;
    private boolean patDone = false;
    private BlockPos patTip = null;
    private EnumFacing patDir = EnumFacing.NORTH;

    private final Random rand = new Random();
    private boolean enabled = false;
    private int cooldown = 0;
    private int tickCounter = 0;
    private int lastRmbTick = -1000;

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
            p.addChatMessage(new ChatComponentText("AutoPlace: " + (enabled ? "ON" : "OFF")));
        }

        if (PATTERN_TOGGLE.isPressed()) {
            patternMode = !patternMode;
            resetPattern();
            p.addChatMessage(new ChatComponentText("AutoPlace stair pattern: " + (patternMode ? "ON" : "OFF")));
        }

        if (!enabled || mc.currentScreen != null) return;

        // RMB released and standing on the ground: forget the unfinished pattern.
        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS && p.onGround) {
            resetPattern();
        }

        // Only work while RMB is held / being spam clicked.
        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        ItemStack held = p.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        if (patternMode) {
            patternTick(mc, p, held);
            return;
        }

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

    private void resetPattern() {
        patIdx = 0;
        patDone = false;
        patTip = null;
    }

    /** One tick of the stair pattern: places the next block of PATTERN if it is possible right now. */
    private void patternTick(Minecraft mc, EntityPlayerSP p, ItemStack held) {
        if (patDone) {
            if (p.onGround) resetPattern(); // pattern finished: wait until you land, then be ready again
            return;
        }

        if (patTip == null) {
            // Start of a cycle: begins once you are in the air, from the block you jumped off.
            if (p.onGround || Double.isNaN(lastGroundY)) return;
            BlockPos start = new BlockPos(p.posX, lastGroundY - 0.5, p.posZ);
            if (!mc.theWorld.getBlockState(start).getBlock().getMaterial().isSolid()) return;
            patTip = start;
            patIdx = 0;

            // "Forward" = the direction you are moving, snapped to north/south/east/west.
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

        EnumFacing face = PATTERN[patIdx] == 0 ? EnumFacing.UP : patDir;
        BlockPos target = patTip.offset(face);

        // Spot already filled: count the step as done and move on from that block.
        if (!mc.theWorld.isAirBlock(target)) {
            advancePattern(target);
            return;
        }

        Vec3 hit = new Vec3(
                patTip.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                patTip.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
                patTip.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5);
        Vec3 eye = new Vec3(p.posX, p.posY + p.getEyeHeight(), p.posZ);
        if (eye.distanceTo(hit) > REACH) return;

        // If the block cannot be placed yet (for example you are still inside that spot while
        // rising), this returns false and the mod simply tries again next tick.
        if (mc.playerController.onPlayerRightClick(p, mc.theWorld, held, patTip, face, hit)) {
            p.swingItem();
            cooldown = PATTERN_DELAY_MIN + rand.nextInt(PATTERN_DELAY_RANGE);
            advancePattern(target);
        }
    }

    private void advancePattern(BlockPos placed) {
        patTip = placed;
        patIdx++;
        if (patIdx >= PATTERN.length) {
            patDone = true;
        }
    }
    }
        
