
package autoplace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;

@Mod(
    modid = "autoplace",
    name = "Vanilla AutoPlace",
    version = "1.0",
    clientSideOnly = true
)
public class AutoPlace {

    private final Minecraft mc = Minecraft.getMinecraft();

    private boolean enabled = false;
    private int cooldown = 0;

    private final KeyBinding toggleKey =
        new KeyBinding("Toggle AutoPlace", Keyboard.KEY_P, "AutoPlace");

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
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (!enabled || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (mc.currentScreen != null) return;

        if (mc.thePlayer.getHeldItem() == null
            || !(mc.thePlayer.getHeldItem().getItem()
                instanceof ItemBlock)) {
            return;
        }

        // Ищем доступную грань блока непосредственно под игроком.
        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.getEntityBoundingBox().minY - 0.05;
        double z = mc.thePlayer.posZ;

        BlockPos below = new BlockPos(x, y, z);

        // Проверяем соседние блоки и их грани.
        for (EnumFacing side : EnumFacing.values()) {
            BlockPos neighbor = below.offset(side);

            if (mc.theWorld.isAirBlock(neighbor)) continue;

            if (!mc.theWorld.getBlockState(neighbor)
                    .getBlock().isFullBlock()) {
                continue;
            }

            // Грань должна быть обращена к свободной позиции.
            EnumFacing face = side.getOpposite();

            MovingObjectPosition hit =
                new MovingObjectPosition(
                    new net.minecraft.util.Vec3(x, y, z),
                    face,
                    neighbor
                );

            if (mc.playerController.onPlayerRightClick(
                    mc.thePlayer,
                    mc.theWorld,
                    mc.thePlayer.getHeldItem(),
                    neighbor,
                    face,
                    hit.hitVec)) {

                mc.thePlayer.swingItem();
                cooldown = 1;
                return;
            }
        }
    }
            }
            
