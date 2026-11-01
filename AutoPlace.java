package com.example.autoplace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * AutoPlace 2.2
 *
 * Ставит блоки максимально близко к тому, как это делает человек:
 * - рандомная точка на грани
 * - небольшой случайный кулдаун после постановки
 * - оригинальная логика определения края
 *
 * EDGE_MODE (по умолчанию): ставит вперёд с края блока.
 * LANDING_MODE (выкл): старый режим под ноги.
 *
 * Работает только пока держишь/спамишь ПКМ. Тоггл: R.
 */
@Mod(modid = AutoPlace.MODID, version = "2.2", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    private static final int RMB_GRACE_TICKS = 3;
    private static final boolean EDGE_MODE = true;
    private static final boolean LANDING_MODE = false;
    private static final boolean LATE_PHASE = true;
    private static final boolean SAFETY_NET = true;

    private static final EnumFacing[] SIDES = {EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST};
    private static final Random RAND = new Random();

    private static final KeyBinding TOGGLE = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_R, "AutoPlace");
    private boolean enabled = false;
    private int tickCounter = 0;
    private int lastRmbTick = -1000;
    private int placeCooldown = 0;
    private boolean havePos = false;
    private double lastX = 0;
    private double lastZ = 0;

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
        if (mc.thePlayer == null || mc.theWorld == null) {
            havePos = false;
            placeCooldown = 0;
            return;
        }
        EntityPlayerSP p = mc.thePlayer;

        double dX = 0;
        double dZ = 0;
        if (havePos) {
            dX = p.posX - lastX;
            dZ = p.posZ - lastZ;
            if (Math.abs(dX) > 1.5 || Math.abs(dZ) > 1.5) {
                dX = 0;
                dZ = 0;
            }
        }
        lastX = p.posX;
        lastZ = p.posZ;
        havePos = true;

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

        if (placeCooldown > 0) {
            placeCooldown--;
            return;
        }

        ItemStack stack = p.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) return;

        if (p.onGround) {
            if (EDGE_MODE) edgePlace(mc, p, stack, dX, dZ);
        } else if (LANDING_MODE) {
            landingPlace(mc, p, stack);
        }
    }

    private void edgePlace(Minecraft mc, EntityPlayerSP p, ItemStack stack, double dX, double dZ) {
        // Слишком медленно — не ставим
        if (Math.abs(dX) < 0.05D && Math.abs(dZ) < 0.05D) return;

        int dirX = 0;
        int dirZ = 0;
        if (Math.abs(dX) >= Math.abs(dZ)) dirX = dX > 0 ? 1 : -1;
        else dirZ = dZ > 0 ? 1 : -1;

        int cx = MathHelper.floor_double(p.posX);
        int cz = MathHelper.floor_double(p.posZ);
        int nx = MathHelper.floor_double(p.posX + dX);
        int nz = MathHelper.floor_double(p.posZ + dZ);

        // Ещё не последний тик на этом блоке
        if (nx == cx && nz == cz) return;

        int layerY = MathHelper.floor_double(p.getEntityBoundingBox().minY - 0.001D);
        BlockPos support = new BlockPos(cx, layerY, cz);
        BlockPos target = new BlockPos(cx + dirX, layerY, cz + dirZ);

        if (!isSolid(mc, support)) return;
        if (!mc.theWorld.isAirBlock(target)) return;

        EnumFacing side;
        if (dirX > 0) side = EnumFacing.EAST;
        else if (dirX < 0) side = EnumFacing.WEST;
        else if (dirZ > 0) side = EnumFacing.SOUTH;
        else side = EnumFacing.NORTH;

        if (clickFace(mc, p, stack, support, side)) {
            // Небольшой случайный кулдаун — выглядит живее
            placeCooldown = RAND.nextInt(3); // 0, 1 или 2 тика
        }
    }

    private void landingPlace(Minecraft mc, EntityPlayerSP p, ItemStack stack) {
        if (p.motionY >= 0.0D) return;

        AxisAlignedBB bb = p.getEntityBoundingBox();
        double minY = bb.minY;
        double nextMinY = minY + p.motionY;
        int top = MathHelper.floor_double(minY);
        if (nextMinY > top) return;

        int layerY = top - 1;

        final double px = p.posX;
        final double pz = p.posZ;
        List<BlockPos> cells = new ArrayList<BlockPos>();
        int x0 = MathHelper.floor_double(SAFETY_NET ? bb.minX : px);
        int x1 = MathHelper.floor_double(SAFETY_NET ? bb.maxX : px);
        int z0 = MathHelper.floor_double(SAFETY_NET ? bb.minZ : pz);
        int z1 = MathHelper.floor_double(SAFETY_NET ? bb.maxZ : pz);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                BlockPos c = new BlockPos(x, layerY, z);
                if (mc.theWorld.isAirBlock(c)) cells.add(c);
            }
        }
        if (cells.isEmpty()) return;

        Collections.sort(cells, new Comparator<BlockPos>() {
            @Override
            public int compare(BlockPos a, BlockPos b) {
                double da = sq(a.getX() + 0.5 - px) + sq(a.getZ() + 0
