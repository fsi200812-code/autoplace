import net.minecraft.client.Minecraft;

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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = "speedtellyhelper", name = "SpeedTellyHelper", version = "1.0", clientSideOnly = true)
public class AutoPlace {

    // на сколько блоков вперёд от места посадки ставить (0 = под ногами)
    private static final int AHEAD = 0;

    private final Minecraft mc = Minecraft.getMinecraft();
    private KeyBinding toggleKey;
    private boolean enabled = false;

    private boolean wasOnGround = true;
    private double jumpStartY = 0;
    private boolean placedThisJump = false;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        toggleKey = new KeyBinding("Toggle SpeedTelly helper", Keyboard.KEY_R, "SpeedTelly");
        ClientRegistry.registerKeyBinding(toggleKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null) return;

        while (toggleKey.isPressed()) {
            enabled = !enabled;
            p.addChatMessage(new ChatComponentText("SpeedTelly helper: " + (enabled ? "ON" : "OFF")));
        }

        boolean onGround = p.onGround;

        // момент отрыва от земли: запоминаем высоту прыжка
        if (wasOnGround && !onGround && p.motionY > 0) {
            jumpStartY = p.prevPosY;
            placedThisJump = false;
        }
        wasOnGround = onGround;

        if (!enabled) return;
        if (!mc.isSingleplayer()) return;          // только одиночка
        if (mc.currentScreen != null) return;
        if (!mc.gameSettings.keyBindUseItem.isKeyDown()) return; // только пока зажата ПКМ
        if (onGround || placedThisJump || p.motionY >= 0) return;

        // последний тик: на следующем тике ноги опустятся до высоты прыжка
        double nextY = p.posY + p.motionY;
        if (nextY > jumpStartY) return;

        if (placeBlock(p)) placedThisJump = true;
    }

    private boolean placeBlock(EntityPlayerSP p) {
        ItemStack held = p.getHeldItem();           // блок из руки, хотбар не трогаем
        if (held == null || !(held.getItem() instanceof ItemBlock)) return false;

        // направление берём по движению, а не по камере
        EnumFacing dir;
        if (Math.abs(p.motionX) > Math.abs(p.motionZ)) {
            dir = p.motionX > 0 ? EnumFacing.EAST : EnumFacing.WEST;
        } else {
            dir = p.motionZ > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
        }

        BlockPos target = new BlockPos(
                MathHelper.floor_double(p.posX),
                MathHelper.floor_double(jumpStartY + 0.01) - 1,
                MathHelper.floor_double(p.posZ));
        if (AHEAD != 0) target = target.offset(dir, AHEAD);

        if (!mc.theWorld.getBlockState(target).getBlock().getMaterial().isReplaceable()) return false;

        // ищем соседний твёрдый блок, к которому прикрепить новый
        EnumFacing[] order = {
                dir.getOpposite(), EnumFacing.DOWN,
                dir.rotateY(), dir.rotateYCCW(), dir
        };
        for (EnumFacing f : order) {
            BlockPos n = target.offset(f);
            if (!mc.theWorld.getBlockState(n).getBlock().getMaterial().isSolid()) continue;

            EnumFacing side = f.getOpposite();      // грань соседа, смотрящая на target
            Vec3 hit = new Vec3(
                    n.getX() + 0.5 + side.getFrontOffsetX() * 0.5,
                    n.getY() + 0.5 + side.getFrontOffsetY() * 0.5,
                    n.getZ() + 0.5 + side.getFrontOffsetZ() * 0.5);

            boolean ok = mc.playerController.onPlayerRightClick(p, mc.theWorld, held, n, side, hit);
            if (ok) p.swingItem();
            return ok;
        }
        return false;
    }
}
