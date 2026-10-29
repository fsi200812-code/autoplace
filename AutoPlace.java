
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

@Mod(
    modid = AutoPlace.MODID,
    version = "1.10",
    name = "AutoPlace",
    clientSideOnly = true,
    acceptedMinecraftVersions = "[1.8.9]"
)
public class AutoPlace {

    public static final String MODID = "autoplace";

    private static final int RMB_GRACE_TICKS = 3;

    // END checks the placement earlier than the previous START phase.
    private static final TickEvent.Phase PLACEMENT_PHASE =
        TickEvent.Phase.END;

    // Do not place additional helper blocks.
    private static final boolean SAFETY_NET = false;

    private static final KeyBinding TOGGLE =
        new KeyBinding(
            "Toggle AutoPlace",
            Keyboard.KEY_R,
            "AutoPlace"
        );

    private boolean enabled = false;
    private int tickCounter = 0;
    private int lastRmbTick = -1000;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(TOGGLE);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.button == 1 && event.buttonstate) {
            lastRmbTick = tickCounter;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {

        if (event.phase != PLACEMENT_PHASE) {
            return;
        }

        Minecraft mc = Minecraft.func_71410_x();

        if (mc.field_71439_g == null ||
            mc.field_71441_e == null) {
            return;
        }

        EntityPlayerSP player = mc.field_71439_g;

        tickCounter++;

        if (Mouse.isButtonDown(1) ||
            mc.field_71474_y.field_74313_G.func_151470_d()) {
            lastRmbTick = tickCounter;
        }

        if (TOGGLE.func_151468_f()) {
            enabled = !enabled;

            player.func_145747_a(
                new ChatComponentText(
                    "AutoPlace: " + (enabled ? "ON" : "OFF")
                )
            );
        }

        if (!enabled || mc.field_71462_r != null) {
            return;
        }

        // Only when right mouse button is active.
        if (tickCounter - lastRmbTick > RMB_GRACE_TICKS) {
            return;
        }

        // Only while airborne and falling.
        if (player.field_70122_E ||
            player.field_70181_x >= 0.0D) {
            return;
        }

        ItemStack stack = player.func_70694_bm();

        if (stack == null ||
            !(stack.func_77973_b() instanceof ItemBlock)) {
            return;
        }

        AxisAlignedBB box = player.func_174813_aQ();

        double minY = box.field_72338_b;
        double nextMinY = minY + player.field_70181_x;

        int top = MathHelper.func_76128_c(minY);

        // Wait until the next movement would cross the block layer.
        if (nextMinY > top) {
            return;
        }

        int layerY = top - 1;

        final double px = player.field_70165_t;
        final double pz = player.field_70161_v;

        List<BlockPos> cells = new ArrayList<BlockPos>();

        int x0 = MathHelper.func_76128_c(box.field_72340_a);
        int x1 = MathHelper.func_76128_c(box.field_72336_d);
        int z0 = MathHelper.func_76128_c(box.field_72339_c);
        int z1 = MathHelper.func_76128_c(box.field_72334_f);

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {

                BlockPos pos = new BlockPos(x, layerY, z);

                if (mc.field_71441_e.func_175623_d(pos)) {
                    cells.add(pos);
                }
            }
        }

        if (cells.isEmpty()) {
            return;
        }

        Collections.sort(
            cells,
            new Comparator<BlockPos>() {
                @Override
                public int compare(BlockPos a, BlockPos b) {

                    double da =
                        sq(a.func_177958_n() + 0.5D - px) +
                        sq(a.func_177952_p() + 0.5D - pz);

                    double db =
                        sq(b.func_177958_n() + 0.5D - px) +
                        sq(b.func_177952_p() + 0.5D - pz);

                    return Double.compare(da, db);
                }
            }
        );

        // Try a single block directly under the player's feet.
        for (BlockPos pos : cells) {
            if (placeAt(mc, player, stack, pos)) {
                return;
            }
        }

        // SAFETY_NET is disabled, so no extra placement attempts.
        if (!SAFETY_NET) {
            return;
        }
    }

    private static double sq(double value) {
        return value * value;
    }

    private boolean placeAt(
        Minecraft mc,
        EntityPlayerSP player,
        ItemStack stack,
        BlockPos cell
    ) {

        for (EnumFacing direction : EnumFacing.values()) {

            if (direction == EnumFacing.UP) {
                continue;
            }

            BlockPos neighbour = cell.func_177972_a(direction);

            if (!mc.field_71441_e
                    .func_180495_p(neighbour)
                    .func_177230_c()
                    .func_149688_o()
                    .func_76220_a()) {
                continue;
            }

            EnumFacing side = direction.func_176734_d();

            Vec3 hit = new Vec3(
                neighbour.func_177958_n() + 0.5D
                    + side.func_82601_c() * 0.5D,
                neighbour.func_177956_o() + 0.5D
                    + side.func_96559_d() * 0.5D,
                neighbour.func_177952_p() + 0.5D
                    + side.func_82599_e() * 0.5D
            );

            if (mc.field_71442_b.func_178890_a(
                    player,
                    mc.field_71441_e,
                    stack,
                    neighbour,
                    side,
                    hit)) {

                player.func_71038_i();
                return true;
            }
        }

        return false;
    }
    }
                    
