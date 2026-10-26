package com.example.tellyhelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = "tellyhelper", name = "Telly Helper", version = "1.0", clientSideOnly = true)
public class TellyHelper {

    private static final int USER_BLOCKS = 3;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final KeyBinding toggleKey = new KeyBinding("Toggle Telly Helper", Keyboard.KEY_K, "Telly Helper");

    private boolean enabled = true;
    private boolean wasOnGround = true;
    private boolean placedByMod = false;
    private int userPlaced = 0;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(toggleKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onMouse(MouseEvent e) {
        if (!enabled || e.button != 1 || !e.buttonstate) return;
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.currentScreen != null || p.onGround) return;
        if (!holdingBlock(p)) return;
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            userPlaced++;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        if (toggleKey.isPressed()) {
            enabled = !enabled;
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText("Telly Helper: " + (enabled ? "ON" : "OFF")));
            }
        }

        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null) return;

        if (p.onGround != wasOnGround) {
            wasOnGround = p.onGround;
            userPlaced = 0;
            placedByMod = false;
        }

        if (!enabled || p.onGround || placedByMod || mc.currentScreen != null) return;
        if (userPlaced != USER_BLOCKS) return;
        if (!holdingBlock(p)) return;

        // Spot under the player; no aiming needed
        BlockPos target = new BlockPos(p.posX, p.posY - 1, p.posZ);
        if (!mc.theWorld.isAirBlock(target)) return;

        ItemStack held = p.getHeldItem();
        EnumFacing[] order = {
            EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST,
            EnumFacing.DOWN, EnumFacing.UP
        };
        for (EnumFacing f : order) {
            BlockPos support = target.offset(f);
            if (mc.theWorld.isAirBlock(support)) continue;
            EnumFacing side = f.getOpposite();
            Vec3 hit = new Vec3(
                support.getX() + 0.5 + side.getFrontOffsetX() * 0.5,
                support.getY() + 0.5 + side.getFrontOffsetY() * 0.5,
                support.getZ() + 0.5 + side.getFrontOffsetZ() * 0.5);
            if (mc.playerController.onPlayerRightClick(p, mc.theWorld, held, support, side, hit)) {
                p.swingItem();
                placedByMod = true;
                return;
            }
        }
    }

    private boolean holdingBlock(EntityPlayerSP p) {
        ItemStack s = p.getHeldItem();
        return s != null && s.getItem() instanceof ItemBlock;
    }
}
