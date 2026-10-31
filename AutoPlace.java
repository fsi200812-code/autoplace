package com.example.autoplace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * AutoPlace 2.0 - aim-based speed helper (named MCP mappings).
 *
 * You aim yourself. The mod only decides WHEN the click happens:
 *  - Toggle key: R.
 *  - Works only while RMB is held or clicked fast (10+ CPS).
 *  - Only in the air, falling, on the last tick before landing.
 *  - Only if YOUR crosshair is on a block face and the cell in front of that face
 *    is the empty cell under your feet. The click uses the real hit point of your
 *    crosshair, exactly like a manual right click. No rotation changes, no
 *    "blind" placement, no helper blocks.
 *  - One click per tick at most.
 */
@Mod(modid = AutoPlace.MODID, version = "2.0", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    /** RMB counts as active if held now or clicked within this many ticks (3 ticks = 150 ms). */
    private static final int RMB_GRACE_TICKS = 3;

    /** true = start of the landing tick, false = end of the previous tick. */
    private static final boolean LATE_PHASE = true;

    private static final KeyBinding TOGGLE = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_R, "AutoPlace");
    private boolean enabled = false;
    private int tickCounter = 0;
    private int lastRmbTick = -1000;
    private int lastPlaceTick = -1000;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ClientRegistry.registerKeyBinding(TOGGLE);
        MinecraftForge.EVENT_BUS.register(this);
    }

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
        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) return;
        if (lastPlaceTick == tickCounter) return;

        // Only in the air and falling.
        if (p.onGround || p.motionY >= 0.0D) return;

        AxisAlignedBB bb = p.getEntityBoundingBox();
        double minY = bb.minY;
        double nextMinY = minY + p.motionY;
        int top = MathHelper.floor_double(minY);

        // Last tick: the next move would take the feet through that layer's top.
        if (nextMinY > top) return;

        ItemStack stack = p.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) return;

        // Your own crosshair must be on a block face.
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;

        BlockPos target = mop.getBlockPos();
        BlockPos cell = target.offset(mop.sideHit);

        // The cell in front of the face must be the empty cell under your feet.
        int layerY = top - 1;
        if (cell.getY() != layerY) return;
        if (!mc.theWorld.isAirBlock(cell)) return;

        int x0 = MathHelper.floor_double(bb.minX);
        int x1 = MathHelper.floor_double(bb.maxX);
        int z0 = MathHelper.floor_double(bb.minZ);
        int z1 = MathHelper.floor_double(bb.maxZ);
        if (cell.getX() < x0 || cell.getX() > x1 || cell.getZ() < z0 || cell.getZ() > z1) return;

        // Same call a normal right click makes, with the real hit point of your crosshair.
        if (mc.playerController.onPlayerRightClick(p, mc.theWorld, stack, target, mop.sideHit, mop.hitVec)) {
            p.swingItem();
            lastPlaceTick = tickCounter;
        }
    }
    }
                
