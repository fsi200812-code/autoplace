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
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * AutoPlace 2.3 (Anti-Cheat Bypass Edition)
 * 
 * Обходы:
 * 1. Legit Raytrace (приоритет пересечения луча взгляда)
 * 2. Micro-randomization (избегание флага Perfect Center 0.5)
 * 3. Reach validation (макс. 4.5 блока)
 * 4. Dot Product check (запрет установки за спиной)
 */
@Mod(modid = AutoPlace.MODID, version = "2.3", name = "AutoPlace", clientSideOnly = false, acceptedMinecraftVersions = "[1.8.9]")
public class AutoPlace {
    public static final String MODID = "autoplace";

    private static final int RMB_GRACE_TICKS = 3;
    private static final boolean EDGE_MODE = true;
    private static final int AHEAD_MAX = 3;
    private static final int EDGE_DELAY_TICKS = 1;
    private static final boolean LANDING_MODE = false;
    private static final boolean LATE_PHASE = true;
    private static final boolean SAFETY_NET = true;

    private static final EnumFacing[] SIDES = {EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST};

    private static final KeyBinding TOGGLE = new KeyBinding("Toggle AutoPlace", Keyboard.KEY_R, "AutoPlace");
    private boolean enabled = false;
    private int tickCounter = 0;
    private int lastRmbTick = -1000;
    private boolean havePos = false;
    private double lastX = 0;
    private double lastZ = 0;

    private boolean pending = false;
    private int pendingTicks = 0;
    private int pendCx, pendCz, pendLayer, pendDirX, pendDirZ;

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
            p.addChatMessage(new ChatComponentText("§aAutoPlace: " + (enabled ? "ON" : "§cOFF")));
        }

        if (!enabled || mc.currentScreen != null) {
            pending = false;
            return;
        }

        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) {
            pending = false;
            return;
        }

        ItemStack stack = p.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            pending = false;
            return;
        }

        if (EDGE_MODE) {
            if (pending) {
                pendingTicks--;
                if (pendingTicks <= 0) {
                    pending = false;
                    edgePlace(mc, p, stack);
                }
            } else if (p.onGround) {
                edgeArm(mc, p, stack, dX, dZ);
            }
        }

        if (!p.onGround && LANDING_MODE) {
            landingPlace(mc, p, stack);
        }
    }

    private void edgeArm(Minecraft mc, EntityPlayerSP p, ItemStack stack, double dX, double dZ) {
        if (Math.abs(dX) < 0.05D && Math.abs(dZ) < 0.05D) return;

        int dirX = 0;
        int dirZ = 0;
        if (Math.abs(dX) >= Math.abs(dZ)) dirX = dX > 0 ? 1 : -1;
        else dirZ = dZ > 0 ? 1 : -1;

        int cx = MathHelper.floor_double(p.posX);
        int cz = MathHelper.floor_double(p.posZ);
        int nx = MathHelper.floor_double(p.posX + dX);
        int nz = MathHelper.floor_double(p.posZ + dZ);
        if (nx == cx && nz == cz) return;

        pendCx = cx;
        pendCz = cz;
        pendDirX = dirX;
        pendDirZ = dirZ;
        pendLayer = MathHelper.floor_double(p.getEntityBoundingBox().minY - 0.001D);

        if (EDGE_DELAY_TICKS <= 0) {
            edgePlace(mc, p, stack);
        } else {
            pending = true;
            pendingTicks = EDGE_DELAY_TICKS;
        }
    }

    private void edgePlace(Minecraft mc, EntityPlayerSP p, ItemStack stack) {
        BlockPos support = new BlockPos(pendCx, pendLayer, pendCz);
        if (!isSolid(mc, support)) return;

        EnumFacing side;
        if (pendDirX > 0) side = EnumFacing.EAST;
        else if (pendDirX < 0) side = EnumFacing.WEST;
        else if (pendDirZ > 0) side = EnumFacing.SOUTH;
        else side = EnumFacing.NORTH;

        for (int i = 1; i <= AHEAD_MAX; i++) {
            BlockPos cell = new BlockPos(pendCx + pendDirX * i, pendLayer, pendCz + pendDirZ * i);
            if (!mc.theWorld.isAirBlock(cell)) continue;

            BlockPos prev = new BlockPos(pendCx + pendDirX * (i - 1), pendLayer, pendCz + pendDirZ * (i - 1));
            if (!isSolid(mc, prev)) return;

            // Используем новый умный метод установки вместо старого clickFace
            if (smartPlace(mc, p, stack, cell)) return;
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
                double da = sq(a.getX() + 0.5 - px) + sq(a.getZ() + 0.5 - pz);
                double db = sq(b.getX() + 0.5 - px) + sq(b.getZ() + 0.5 - pz);
                return Double.compare(da, db);
            }
        });

        for (BlockPos c : cells) {
            if (smartPlace(mc, p, stack, c)) return;
        }

        if (!SAFETY_NET) return;

        for (BlockPos c : cells) {
            for (EnumFacing dir : SIDES) {
                BlockPos helper = c.offset(dir);
                if (!mc.theWorld.isAirBlock(helper)) continue;
                if (smartPlace(mc, p, stack, helper)) {
                    smartPlace(mc, p, stack, c);
                    return;
                }
            }
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private boolean isSolid(Minecraft mc, BlockPos pos) {
        return mc.theWorld.getBlockState(pos).getBlock().getMaterial().isSolid();
    }

    /**
     * УМНАЯ УСТАНОВКА С ОБХОДОМ АНТИЧИТА
     * Заменяет старые placeAt и clickFace
     */
    private boolean smartPlace(Minecraft mc, EntityPlayerSP p, ItemStack stack, BlockPos targetCell) {
        Vec3 eyePos = new Vec3(p.posX, p.posY + p.getEyeHeight(), p.posZ);
        Vec3 lookVec = p.getLook(1.0F);
        double maxReach = 4.5;
        Vec3 endPos = eyePos.addVector(lookVec.xCoord * maxReach, lookVec.yCoord * maxReach, lookVec.zCoord * maxReach);

        // Приоритетные направления для поиска опоры (UP обычно не нужен для этих режимов)
        EnumFacing[] dirs = {EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};

        // ШАГ 1: Строгий рейтрейс (100% легитность, если луч взгляда пересекает блок)
        for (EnumFacing dir : dirs) {
            if (dir == EnumFacing.UP) continue;
            BlockPos neighbour = targetCell.offset(dir);
            if (!isSolid(mc, neighbour)) continue;

            AxisAlignedBB bb = new AxisAlignedBB(neighbour.getX(), neighbour.getY(), neighbour.getZ(),
                                                 neighbour.getX() + 1, neighbour.getY() + 1, neighbour.getZ() + 1);
            bb = bb.expand(0.05, 0.05, 0.05); // Микро-расширение для стабильности
            MovingObjectPosition mop = bb.calculateIntercept(eyePos, endPos);

            if (mop != null) {
                if (eyePos.distanceTo(mop.hitVec) <= maxReach) {
                    if (mc.playerController.onPlayerRightClick(p, mc.theWorld, stack, neighbour, mop.sideHit, mop.hitVec)) {
                        p.swingItem();
                        return true;
                    }
                }
            }
        }

        // ШАГ 2: Фоллбэк с микро-рандомизацией (если взгляд в стороне, но блок в радиусе)
        for (EnumFacing dir : dirs) {
            if (dir == EnumFacing.UP) continue;
            BlockPos neighbour = targetCell.offset(dir);
            if (!isSolid(mc, neighbour)) continue;

            EnumFacing side = dir.getOpposite(); // Грань, по которой кликаем
            
            double centerX = neighbour.getX() + 0.5;
            double centerY = neighbour.getY() + 0.5;
            double centerZ = neighbour.getZ() + 0.5;

            double hitX = centerX;
            double hitY = centerY;
            double hitZ = centerZ;

            // Прижимаем точку к нужной грани
            if (side.getFrontOffsetX() != 0) hitX = neighbour.getX() + (side.getFrontOffsetX() == 1 ? 1.0 : 0.0);
            if (side.getFrontOffsetY() != 0) hitY = neighbour.getY() + (side.getFrontOffsetY() == 1 ? 1.0 : 0.0);
            if (side.getFrontOffsetZ() != 0) hitZ = neighbour.getZ() + (side.getFrontOffsetZ() == 1 ? 1.0 : 0.0);

            // МИКРО-РАНДОМ: Избегаем идеального центра (0.5), который палит античит
            double randomOffset = 0.35; 
            if (side.getFrontOffsetX() == 0) hitX += (mc.theWorld.rand.nextDouble() - 0.5) * randomOffset;
            if (side.getFrontOffsetY() == 0) hitY += (mc.theWorld.rand.nextDouble() - 0.5) * randomOffset;
            if (side.getFrontOffsetZ() == 0) hitZ += (mc.theWorld.rand.nextDouble() - 0.5) * randomOffset;

            Vec3 hit = new Vec3(hitX, hitY, hitZ);

            // Проверка дистанции
            if (eyePos.distanceTo(hit) > maxReach) continue;

            // Проверка направления (точка должна быть перед игроком, а не за спиной)
            Vec3 toHit = hit.subtract(eyePos).normalize();
            if (toHit.dotProduct(lookVec) > -0.1) { // -0.1 допускает небольшое периферийное зрение
                if (mc.playerController.onPlayerRightClick(p, mc.theWorld, stack, neighbour, side, hit)) {
                    p.swingItem();
                    return true;
                }
            }
        }

        return false;
    }
    }
