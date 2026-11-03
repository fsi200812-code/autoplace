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
 * Singleplayer helper: on the LAST tick of a jump (right before landing)
 * puts the block in hand under the player, so he lands / pushes off from it.
 * Works only after a real jump (not when walking off an edge).
 * Toggle: V
 */
@Mod(modid = "autoplace", name = "AutoPlace", version = "3.0", clientSideOnly = true)
public class AutoPlace {

    // true = place only while right mouse is held
    private static final boolean REQUIRE_RMB = false;

    private final Minecraft mc = Minecraft.getMinecraft();
    private KeyBinding toggleKey;
    private boolean enabled = true;
    private boolean wasGround = true;
    private boolean armed = false;   // current air phase started with a jump
    private boolean placed = false;  // already placed in this jump

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        toggleKey = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_V, "AutoPlace");
        ClientRegistry.registerKeyBinding(toggleKey);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;

        if (toggleKey.isPressed()) {
            enabled = !enabled;
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText("AutoPlace: " + (enabled ? "ON" : "OFF")));
            }
        }

        EntityPlayerSP p = mc.thePlayer;
        if (!enabled || p == null || mc.theWorld == null || mc.currentScreen != null) return;

        if (p.onGround) {
            wasGround = true;
            armed = false;
            placed = false;
            return;
        }

        // first tick in the air: was it a jump or just a fall?
        if (wasGround) {
            wasGround = false;
            armed = p.motionY > 0;
        }

        if (!armed || placed || p.motionY >= 0) return;
        if (REQUIRE_RMB && !mc.gameSettings.keyBindUseItem.isKeyDown()) return;

        ItemStack held = p.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        // will the feet cross the next whole block level during THIS tick?
        int level = MathHelper.floor_double(p.posY);
        if (p.posY + p.motionY >= level) return;

        double px = p.posX + p.motionX;
        double pz = p.posZ + p.motionZ;
        BlockPos target = new BlockPos(MathHelper.floor_double(px), level - 1, MathHelper.floor_double(pz));
        if (!mc.theWorld.isAirBlock(target)) return;

        if (place(p, held, target)) placed = true;
    }

    private boolean place(EntityPlayerSP p, ItemStack held, BlockPos target) {
        EnumFacing[] order = {
            EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST
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
