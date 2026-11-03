import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Singleplayer helper for speed telly.
 * On the LAST tick of a jump (right before landing) puts the block in hand
 * under the player, so he lands / pushes off from it.
 *
 * Works only if:
 *  - the air phase started with a real jump (not a fall off an edge);
 *  - YOU placed at least MIN_OWN_SWINGS blocks yourself during this jump
 *    (so ramp jumps / plain jumps with no clicking are ignored).
 *
 * V = toggle mod, B = toggle debug messages in chat (one line per jump).
 */
@Mod(modid = "autoplace", name = "AutoPlace", version = "3.1", clientSideOnly = true)
public class AutoPlace {

    // how many of YOUR placements (arm swings) are needed in the jump before the mod helps
    private static final int MIN_OWN_SWINGS = 1;
    // shift of the target forward along movement (in blocks). 0 = under predicted position
    private static final double FORWARD = 0.0;

    private final Minecraft mc = Minecraft.getMinecraft();
    private KeyBinding toggleKey;
    private KeyBinding debugKey;
    private boolean enabled = true;
    private boolean debug = false;

    private boolean wasGround = true;
    private boolean armed = false;   // current air phase started with a jump
    private boolean decided = false; // last-tick decision already made in this jump
    private int groundLevel = 0;     // feet level where the jump started
    private int swings = 0;          // own block placements during this jump

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        toggleKey = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_V, "AutoPlace");
        debugKey = new KeyBinding("AutoPlace debug", Keyboard.KEY_B, "AutoPlace");
        ClientRegistry.registerKeyBinding(toggleKey);
        ClientRegistry.registerKeyBinding(debugKey);
        FMLCommonHandler.instance().bus().register(this);
    }

    private void say(String s) {
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(s));
    }

    private void dbg(String s) {
        if (debug) say("[AP] " + s);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;

        if (toggleKey.isPressed()) {
            enabled = !enabled;
            say("AutoPlace: " + (enabled ? "ON" : "OFF"));
        }
        if (debugKey.isPressed()) {
            debug = !debug;
            say("AutoPlace debug: " + (debug ? "ON" : "OFF"));
        }

        EntityPlayerSP p = mc.thePlayer;
        if (!enabled || p == null || mc.theWorld == null || mc.currentScreen != null) return;

        if (p.onGround) {
            wasGround = true;
            groundLevel = MathHelper.floor_double(p.posY + 0.01);
            armed = false;
            decided = false;
            swings = 0;
            return;
        }

        // first tick in the air: was it a jump or just a fall?
        if (wasGround) {
            wasGround = false;
            armed = p.motionY > 0;
            swings = 0;
        }

        // count own block placements (each placement starts a new arm swing)
        if (p.isSwingInProgress && p.swingProgressInt == 0) swings++;

        if (!armed || decided || p.motionY >= 0) return;

        // the tick when the feet cross the takeoff level = the last moment to place the block
        if (p.posY < groundLevel || p.posY + p.motionY >= groundLevel) return;

        decided = true;

        if (swings < MIN_OWN_SWINGS) {
            dbg("skip: you placed no blocks in this jump");
            return;
        }

        ItemStack held = p.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            dbg("skip: no block in hand");
            return;
        }

        EnumFacing travel = EnumFacing.getFacingFromVector((float) p.motionX, 0F, (float) p.motionZ);
        EnumFacing back = travel.getOpposite();

        double nx = p.posX + p.motionX + travel.getFrontOffsetX() * FORWARD;
        double nz = p.posZ + p.motionZ + travel.getFrontOffsetZ() * FORWARD;
        int ty = groundLevel - 1;
        double hw = p.width / 2.0;

        BlockPos primary = new BlockPos(MathHelper.floor_double(nx), ty, MathHelper.floor_double(nz));
        if (!mc.theWorld.isAirBlock(primary)) {
            dbg("skip: landing cell is already a block");
            return;
        }

        // primary cell first, then the other cells under the player's box
        int[] xs = { primary.getX(), MathHelper.floor_double(nx - hw), MathHelper.floor_double(nx + hw) };
        int[] zs = { primary.getZ(), MathHelper.floor_double(nz - hw), MathHelper.floor_double(nz + hw) };
        for (int xi : xs) {
            for (int zi : zs) {
                BlockPos c = new BlockPos(xi, ty, zi);
                if (!mc.theWorld.isAirBlock(c)) continue;
                if (place(p, held, c, back)) {
                    dbg("placed at " + xi + " " + ty + " " + zi);
                    return;
                }
            }
        }
        dbg("skip: no block next to the landing cell to click on");
    }

    private boolean place(EntityPlayerSP p, ItemStack held, BlockPos target, EnumFacing back) {
        // chain is usually behind us: try the block behind first
        EnumFacing[] order = {
            back, back.rotateY(), back.rotateYCCW(), back.getOpposite(), EnumFacing.DOWN, EnumFacing.UP
        };
        for (EnumFacing f : order) {
            BlockPos n = target.offset(f);
            if (mc.theWorld.isAirBlock(n)) continue;
            EnumFacing side = f.getOpposite();
            Vec3 hit = new Vec3(
                n.getX() + 0.5 + side.getFrontOffsetX() * 0.5,
                n.getY() + 0.5 + side.getFrontOffsetY() * 0.5,
                n.getZ() + 0.5 + side.getFrontOffsetZ() * 0.5
            );
            if (mc.playerController.onPlayerRightClick(p, mc.theWorld, held, n, side, hit)) {
                p.swingItem();
                return true;
            }
        }
        return false;
    }
                }
