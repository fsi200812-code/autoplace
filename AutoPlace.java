
package autoplace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

@Mod(
    modid = "autoplace",
    name = "AutoPlace",
    version = "1.0",
    clientSideOnly = true
)
public class AutoPlace {

    private final Minecraft mc = Minecraft.getMinecraft();

    private boolean enabled = false;
    private int cooldown = 0;

    private final KeyBinding toggleKey = new KeyBinding(
        "Toggle AutoPlace",
        Keyboard.KEY_P,
        "AutoPlace"
    );

    @Mod.EventHandler
    public void init(
        net.minecraftforge.fml.common.event.FMLInitializationEvent event
    ) {
        ClientRegistry.registerKeyBinding(toggleKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        if (toggleKey.isPressed()) {
            enabled = !enabled;
            cooldown = 0;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (!enabled || mc.currentScreen != null) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (mc.thePlayer.getHeldItem() == null) return;

        if (!(mc.thePlayer.getHeldItem().getItem()
                instanceof ItemBlock)) return;

        double mx = mc.thePlayer.motionX;
        double mz = mc.thePlayer.motionZ;
        double speed = Math.sqrt(mx * mx + mz * mz);

        if (speed < 0.02) return;

        double dx = mx / speed;
        double dz = mz / speed;

        double px = mc.thePlayer.posX;
        double py = mc.thePlayer.getEntityBoundingBox().minY;
        double pz = mc.thePlayer.posZ;

        BlockPos support = new BlockPos(
            px - dx * 0.30,
            py - 0.05,
            pz - dz * 0.30
        );

        if (mc.theWorld.isAirBlock(support)) {
            support = support.down();
        }

        if (mc.theWorld.isAirBlock(support)) return;

        EnumFacing face;

        if (Math.abs(dx) > Math.abs(dz)) {
            face = dx > 0
                ? EnumFacing.EAST
                : EnumFacing.WEST;
        } else {
            face = dz > 0
                ? EnumFacing.SOUTH
                : EnumFacing.NORTH;
        }

        BlockPos target = support.offset(face);

        if (!mc.theWorld.isAirBlock(target)) return;

        Vec3 hitVec = new Vec3(
            support.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
            support.getY() + 0.5,
            support.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5
        );

        if (mc.playerController.onPlayerRightClick(
                mc.thePlayer,
                mc.theWorld,
                mc.thePlayer.getHeldItem(),
                support,
                face,
                hitVec
        )) {
            mc.thePlayer.swingItem();
            cooldown = 1;
        }
    }
    }
    
