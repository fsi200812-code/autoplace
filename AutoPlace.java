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
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

/**
 * AutoPlace 2.5 — Speed Telly helper
 * Чаще ставит и лучше попадает в нужные места (розовые блоки)
 */
@Mod(modid = AutoPlace.MODID, version = "2.5", name = "AutoPlace", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    private static final int RMB_GRACE_TICKS = 4;
    private static final boolean EDGE_MODE = true;
    private static final boolean LANDING_MODE = true;   // включил сильнее
    private static final boolean LATE_PHASE = true;
    private static final boolean SAFETY_NET = true;

    private static final int MIN_CPS = 10;
    private static final int MAX_CPS = 50;

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

    private final Queue<Long> clickTimes = new LinkedList<Long>();

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ClientRegistry.registerKeyBinding(TOGGLE);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onMouse(MouseEvent e) {
        if (e.button == 1 && e.buttonstate) {
            lastRmbTick = tickCounter;
            long now = System.currentTimeMillis();
            clickTimes.add(now);
            while (!clickTimes.isEmpty() && now - clickTimes.peek() > 1000) {
                clickTimes.poll();
            }
        }
    }

    private int getCurrentCPS() {
        long now = System.currentTimeMillis();
        while (!clickTimes.isEmpty() && now - clickTimes.peek() > 1000) {
            clickTimes.poll();
        }
        return clickTimes.size();
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
            if (Math.abs(dX) > 1.8 || Math.abs(dZ) > 1.8) {
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

        int cps = getCurrentCPS();
        if (cps < MIN_CPS || cps > MAX_CPS) return;

        if (placeCooldown > 0) {
            placeCooldown--;
            return;
        }

        ItemStack stack = p.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) return;

        boolean placed = false;

        // Сначала пробуем landing (для розовых блоков, на которые падаешь)
        if (LANDING_MODE && !p.onGround) {
            placed = landingPlace(mc, p, stack);
        }

        // Потом edge
        if (!placed && EDGE_MODE) {
            placed = edgePlace(mc, p, stack, dX, dZ);
        }

        if (placed) {
            // Очень маленький кулдаун — ставит чаще
            placeCooldown = RAND.nextInt(2); // 0 или 1 тик
        }
    }

    private boolean edgePlace(Minecraft mc, EntityPlayerSP p, ItemStack stack, double dX, double dZ) {
        // Берём направление даже если движение небольшое
        if (Math.abs(dX) < 0.02D && Math.abs(dZ) < 0.02D) {
            // Если почти стоим — пробуем по взгляду
            float yaw = p.rotationYaw;
            dX = -MathHelper.sin(yaw * 0.017453292F);
            dZ = MathHelper.cos(yaw * 0.017453292F);
        }

        int dirX = 0;
        int dirZ = 0;
        if (Math.abs(dX) >= Math.abs(dZ)) dirX = dX > 0 ? 1 : -1;
        else dirZ = dZ > 0 ? 1 : -1;

        int cx = MathHelper.floor_double(p.posX);
        int cz = MathHelper.floor_double(p.posZ);

        int layerY = MathHelper.floor_double(p.getEntityBoundingBox().minY - 0.01D);
        BlockPos support = new BlockPos(cx, layerY, cz);
        BlockPos target = new BlockPos(cx + dirX, layerY, cz + dirZ);

        if (!isSolid(mc, support)) return false;
        if (!mc.theWorld.isAirBlock(target)) return false;

        EnumFacing side;
        if (dirX > 0) side = EnumFacing.EAST;
        else if (dirX < 0) side = EnumFacing.WEST;
        else if (dirZ > 0) side = EnumFacing.SOUTH;
        else side = EnumFacing.NORTH;

        return clickFace(mc, p, stack, support, side);
    }

    private boolean landingPlace(Minecraft mc, EntityPlayerSP p, ItemStack stack) {
        if (p.motionY > 0.1D) return false; // только когда падаем или почти

        AxisAlignedBB bb = p.getEntityBoundingBox();
        int layerY = MathHelper.floor_double(bb.minY - 0.2D);

        final double px = p.posX;
        final double pz = p.posZ;

        List<BlockPos> cells = new ArrayList<BlockPos>();
        int range = 1;
        int x0 = MathHelper.floor_double(px) - range;
        int x1 = MathHelper.floor_double(px) + range;
        int z0 = MathHelper.floor_double(pz) - range;
        int z1 = MathHelper.floor_double(pz) + range;

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                BlockPos c = new BlockPos(x, layerY, z);
                if (mc.theWorld.isAirBlock(c)) {
                    cells.add(c);
                }
            }
        }

        if (cells.isEmpty()) return false;

        // Сортируем по близости к игроку
        Collections.sort(cells, new Comparator<BlockPos>() {
            @Override
            public int compare(BlockPos a, BlockPos b) {
                double da = sq(a.getX() + 0.5 - px) + sq(a.getZ() + 0.5 - pz);
                double db = sq(b.getX() + 0.5 - px) + sq(b.getZ() + 0.5 - pz);
                return Double.compare(da, db);
            }
        });

        for (BlockPos c : cells) {
            if (placeAt(mc, p, stack, c)) return true;
        }

        return false;
    }

    private static double sq(double v) {
        return v * v;
    }

    private boolean isSolid(Minecraft mc, BlockPos pos) {
        return mc.theWorld.getBlockState(pos).getBlock().getMaterial().isSolid();
    }

    private boolean placeAt(Minecraft mc, EntityPlayerSP p, ItemStack stack, BlockPos cell) {
        for (EnumFacing dir : EnumFacing.values()) {
            if (dir == EnumFacing.UP) continue;
            BlockPos neighbour = cell.offset(dir);
            if (!isSolid(mc, neighbour)) continue;
            if (clickFace(mc, p, stack, neighbour, dir.getOpposite())) return true;
        }
        return false;
    }

    private boolean clickFace(Minecraft mc, EntityPlayerSP p, ItemStack stack, BlockPos block, EnumFacing side) {
        double hx = block.getX() + 0.5 + side.getFrontOffsetX() * 0.5;
        double hy = block.getY() + 0.5 + side.getFrontOffsetY() * 0.5;
        double hz = block.getZ() + 0.5 + side.getFrontOffsetZ() * 0.5;

        // Меньше рандома — точнее попадает
        double ox = (RAND.nextDouble() - 0.5) * 0.3;
        double oy = (RAND.nextDouble() - 0.5) * 0.3;
        double oz = (RAND.nextDouble() - 0.5) * 0.3;

        if (side.getFrontOffsetX() != 0) {
            hy += oy;
            hz += oz;
        } else if (side.getFrontOffsetY() != 0) {
            hx += ox;
            hz += oz;
        } else {
            hx += ox;
            hy += oy;
        }

        Vec3 hit = new Vec3(hx, hy, hz);

        if (mc.playerController.onPlayerRightClick(p, mc.theWorld, stack, block, side, hit)) {
            p.swingItem();
            return true;
        }
        return false;
    }
    }
