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
 * AutoPlace 2.1 - aim-based speed helper (named MCP mappings).
 *
 * You aim yourself. The mod only decides WHEN the click happens:
 *  - R      : toggle on/off.
 *  - = (+)  : place one tick EARLIER (lead + 1).
 *  - - (-)  : place one tick LATER   (lead - 1).
 *  - Works only while RMB is held or clicked fast (10+ CPS).
 *  - Only in the air and falling.
 *  - Window of 2 ticks: with lead 0 it clicks on the last or second-to-last tick
 *    before landing; each +1 of lead shifts the window one tick earlier.
 *  - Only if YOUR crosshair is on a block face and the cell in front of that face
 *    is the empty cell under your feet. The click uses the real hit point of your
 *    crosshair, exactly like a manual right click. No rotation changes, no
 *    blind placement, no helper blocks. One click per tick at most.
 */
@Mod(modid = AutoPlace.MODID, version = "2.1", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    private static final int RMB_GRACE_TICKS = 3;
    private static final int WINDOW_TICKS = 2;
    private static final int MAX_LEAD = 5;

    private static final KeyBinding TOGGLE = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_R, "AutoPlace");
    private static final KeyBinding EARLIER = new KeyBinding("AutoPlace: earlier (+)", Keyboard.KEY_EQUALS, "AutoPlace");
    private static final KeyBinding LATER = new KeyBinding("AutoPlace: later (-)", Keyboard.KEY_MINUS, "AutoPlace");

    private boolean enabled = false;
    private int lead = 0;
    private int tickCounter = 0;
    private int lastRmbTick = -1000;
    private int lastPlaceTick = -1000;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ClientRegistry.registerKeyBinding(TOGGLE);
        ClientRegistry.registerKeyBinding(EARLIER);
        ClientRegistry.registerKeyBinding(LATER);
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
        // Start of the tick, right before your own move.
        if (e.phase != TickEvent.Phase.START) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        EntityPlayerSP p = mc.thePlayer;

        tickCounter++;
        if (Mouse.isButtonDown(1) || mc.gameSettings.keyBindUseItem.isKeyDown()) {
            lastRmbTick = tickCounter;
        }

        if (TOGGLE.isPressed()) {
            enabled = !enabled;
            p.addChatMessage(new ChatComponentText("AutoPlace: " + (enabled ? "ON" : "OFF") + " (lead " + lead + ")"));
        }
        if (EARLIER.isPressed()) {
            if (lead < MAX_LEAD) lead++;
            p.addChatMessage(new ChatComponentText("AutoPlace lead: " + lead + " (earlier)"));
        }
        if (LATER.isPressed()) {
            if (lead > 0) lead--;
            p.addChatMessage(new ChatComponentText("AutoPlace lead: " + lead + " (later)"));
        }

        if (!enabled || mc.currentScreen != null) return;
        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) return;
        if (lastPlaceTick == tickCounter) return;

        // Only in the air and falling.
        if (p.onGround || p.motionY >= 0.0D) return;

        AxisAlignedBB bb = p.getEntityBoundingBox();
        double minY = bb.minY;
        int top = MathHelper.floor_double(minY);

        // How many ticks until the feet reach the top of the layer below (1 = next move lands).
        int n = ticksToLand(minY, p.motionY, top);
        if (n < lead + 1 || n > lead + WINDOW_TICKS) return;

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

    /** Simulates vanilla falling: returns the tick number in which the feet would reach 'top' (1 = this coming move). */
    private static int ticksToLand(double y, double vy, int top) {
        for (int i = 1; i <= 12; i++) {
            y += vy;
            if (y <= top) return i;
            vy = (vy - 0.08D) * 0.98D;
        }
        return 99;
    }
    }
            
