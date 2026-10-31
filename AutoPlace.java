
private boolean placeAt(
        Minecraft mc,
        EntityPlayerSP player,
        ItemStack stack,
        BlockPos target) {

    // Не ставим блок, если игрок ещё не направил прицел
    // на подходящую поверхность.
    if (mc.objectMouseOver == null) {
        return false;
    }

    if (mc.objectMouseOver.typeOfHit
            != net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK) {
        return false;
    }

    BlockPos hitPos = mc.objectMouseOver.getBlockPos();
    EnumFacing side = mc.objectMouseOver.sideHit;

    if (hitPos == null || side == null) {
        return false;
    }

    // Разрешаем установку только в соседнюю клетку,
    // которую действительно выбирает прицел.
    if (!hitPos.offset(side).equals(target)) {
        return false;
    }

    if (mc.rightClickDelayTimer > 0) {
        return false;
    }

    Vec3 hitVec = mc.objectMouseOver.hitVec;

    boolean placed = mc.playerController.onPlayerRightClick(
        player,
        mc.theWorld,
        stack,
        hitPos,
        side,
        hitVec
    );

    if (placed) {
        player.swingItem();
        mc.rightClickDelayTimer = 4;
        return true;
    }

    return false;
}
