package com.example.autotree;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Predicate;

public class AutoTreeClient implements ClientModInitializer {

    // ===== CẤU HÌNH =====
    static final double REACH = 4.5;             // tầm với (block)
    static final int DELAY = 3;                  // tick giữa mỗi lần trồng/bón
    static final int MAX_BONE = 16;              // bón quá số lần này mà khu chưa lớn -> bỏ qua
    static final int MAX_PLANT_TRIES = 8;        // thử trồng 1 chỗ tối đa bao nhiêu lần
    static final int TREE_RADIUS = 4;            // chặt log cách khu tối đa bấy nhiêu block (ngang)
    static final int HARVEST_TIMEOUT = 20 * 40;  // mỗi khu chặt tối đa 40 giây
    static final int MAX_TREE_LOGS = 5000;       // giới hạn đếm khúc gỗ
    static final int FETCH_TIMEOUT = 100;        // chờ mở rương / lấy đồ tối đa (tick)
    static final int SLOT_CLICK_DELAY = 3;       // tick giữa các lần lấy đồ trong rương

    enum Phase { PLANT, BONE, HARVEST }

    private static KeyBinding toggleKey;

    private boolean enabled = false;
    private long tick = 0;
    private long nextAction = 0;
    private long nextDetect = 0;
    private String status = "";

    // khu cây
    private List<List<BlockPos>> zones = new ArrayList<>();
    private Phase phase = Phase.PLANT;
    private int zoneIdx = 0;
    private int zoneBone = 0;
    private int skippedZones = 0;
    private final Map<BlockPos, Integer> plantTries = new HashMap<>();
    private boolean noSaplingWarned = false;
    private boolean harvestStarted = false;
    private int treeTotal = 0;
    private long harvestStartTick = 0;

    // lấy bột xương trong rương
    private boolean fetching = false;
    private boolean boneFetchTried = false;
    private boolean fetchReady = false;
    private boolean grabbed = false;
    private long fetchStart = 0;
    private long fetchReadyTick = 0;
    private long fetchDeadline = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autotree.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.autotree"));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register((ctx, tc) -> {
            if (!enabled || status.isEmpty()) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            ctx.drawTextWithShadow(mc.textRenderer, "[AutoTree] " + status, 4, 4, 0xFF55FF55);
        });
    }

    private void msg(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoTree] " + s), true);
    }

    private void chat(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoTree] " + s), false);
    }

    private void onTick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            fetching = false;
            boneFetchTried = false;
            nextAction = 0;
            nextDetect = 0;
            zones = new ArrayList<>();
            status = "";
            msg(mc, enabled ? "BẬT" : "TẮT");
        }
        if (!enabled || mc.player == null || mc.world == null || mc.interactionManager == null) return;

        tick++;

        if (fetching) {
            handleFetch(mc);
            return;
        }
        if (mc.currentScreen != null) return;

        // Chưa có khu nào -> dò khu
        if (zones.isEmpty()) {
            if (tick < nextDetect) return;
            nextDetect = tick + 40;
            zones = detectZones(mc);
            if (zones.isEmpty()) {
                status = "Không thấy khu cây nào";
                return;
            }
            phase = Phase.PLANT;
            zoneIdx = 0;
            plantTries.clear();
            noSaplingWarned = false;
            chat(mc, "Phát hiện " + zones.size() + " khu. Bắt đầu chu kỳ mới.");
            return;
        }

        switch (phase) {
            case PLANT -> plantStep(mc);
            case BONE -> boneStep(mc);
            case HARVEST -> harvestStep(mc);
        }
    }

    // ===== DÒ KHU =====
    private List<List<BlockPos>> detectZones(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        Vec3d eye = p.getEyePos();
        BlockPos c = p.getBlockPos();
        BlockPos under = c.down();
        int r = 6;

        Set<BlockPos> left = new HashSet<>();
        for (BlockPos q : BlockPos.iterate(c.add(-r, -r, -r), c.add(r, r, r))) {
            BlockState bs = mc.world.getBlockState(q);
            if (!(bs.isOf(Blocks.PODZOL) || bs.isOf(Blocks.DIRT) || bs.isOf(Blocks.COARSE_DIRT))) continue;
            BlockPos soil = q.toImmutable();
            if (soil.equals(under)) continue; // block đang đứng
            BlockPos up = soil.up();
            BlockState us = mc.world.getBlockState(up);
            if (!(us.isAir() || us.isIn(BlockTags.SAPLINGS) || us.isIn(BlockTags.LOGS))) continue;
            if (eye.squaredDistanceTo(Vec3d.ofCenter(up)) > REACH * REACH) continue;
            left.add(soil);
        }

        // gom các khối sát nhau (8 hướng, cùng độ cao) thành 1 khu
        List<List<BlockPos>> out = new ArrayList<>();
        List<BlockPos> all = new ArrayList<>(left);
        for (BlockPos start : all) {
            if (!left.remove(start)) continue;
            List<BlockPos> zone = new ArrayList<>();
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            q.add(start);
            while (!q.isEmpty()) {
                BlockPos b = q.poll();
                zone.add(b);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = b.add(dx, 0, dz);
                        if (left.remove(n)) q.add(n);
                    }
                }
            }
            out.add(zone);
        }
        out.sort(Comparator.comparingDouble(z -> minDist(eye, z)));
        return out;
    }

    private double minDist(Vec3d eye, List<BlockPos> zone) {
        double best = Double.MAX_VALUE;
        for (BlockPos b : zone) best = Math.min(best, eye.squaredDistanceTo(Vec3d.ofCenter(b)));
        return best;
    }

    // ===== GIAI ĐOẠN 1: TRỒNG MẦM =====
    private void plantStep(MinecraftClient mc) {
        if (tick < nextAction) return;
        nextAction = tick + DELAY;
        ClientPlayerEntity p = mc.player;

        if (zoneIdx >= zones.size()) {
            startBonePhase(mc);
            return;
        }
        status = "Đang trồng mầm (" + (zoneIdx + 1) + "/" + zones.size() + " khu)";

        for (BlockPos soil : zones.get(zoneIdx)) {
            BlockPos above = soil.up();
            if (!mc.world.getBlockState(above).isAir()) continue;
            if (plantTries.getOrDefault(soil, 0) >= MAX_PLANT_TRIES) continue;
            if (!inReach(p, above)) continue;

            if (count(mc, st -> st.isIn(ItemTags.SAPLINGS)) == 0) {
                if (!noSaplingWarned) {
                    chat(mc, "Hết mầm, bỏ qua phần trồng còn lại");
                    noSaplingWarned = true;
                }
                startBonePhase(mc);
                return;
            }
            hold(mc, st -> st.isIn(ItemTags.SAPLINGS), 2);
            Vec3d top = new Vec3d(soil.getX() + 0.5, soil.getY() + 1.0, soil.getZ() + 0.5);
            face(p, top);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                    new BlockHitResult(top, Direction.UP, soil, false));
            plantTries.merge(soil, 1, Integer::sum);
            return;
        }
        zoneIdx++;
    }

    private void startBonePhase(MinecraftClient mc) {
        phase = Phase.BONE;
        zoneIdx = 0;
        zoneBone = 0;
        skippedZones = 0;
        boneFetchTried = false;
        chat(mc, "Đã trồng mầm xong cả " + zones.size() + " khu. Bắt đầu bón bột xương.");
    }

    // ===== GIAI ĐOẠN 2: BÓN BỘT XƯƠNG =====
    private void boneStep(MinecraftClient mc) {
        if (tick < nextAction) return;
        nextAction = tick + DELAY;
        ClientPlayerEntity p = mc.player;

        if (zoneIdx >= zones.size()) {
            startHarvestPhase(mc);
            return;
        }
        status = "Đang bón bột xương (" + (zoneIdx + 1) + "/" + zones.size() + " khu)";

        List<BlockPos> saplings = new ArrayList<>();
        for (BlockPos soil : zones.get(zoneIdx)) {
            BlockPos above = soil.up();
            if (mc.world.getBlockState(above).isIn(BlockTags.SAPLINGS) && inReach(p, above)) {
                saplings.add(above);
            }
        }
        if (saplings.isEmpty()) { // đã lớn (hoặc khu trống)
            zoneIdx++;
            zoneBone = 0;
            return;
        }
        if (zoneBone >= MAX_BONE) {
            chat(mc, "Khu " + (zoneIdx + 1) + " chưa lớn sau " + MAX_BONE + " lần bón bột xương, tạm bỏ qua.");
            skippedZones++;
            zoneIdx++;
            zoneBone = 0;
            return;
        }

        // Hết bột xương -> lấy trong rương gần nhất
        if (count(mc, st -> st.isOf(Items.BONE_MEAL)) == 0) {
            if (boneFetchTried) { // vừa lấy rồi mà vẫn không có
                boneFetchTried = false;
                chat(mc, "Rương không còn bột xương, chuyển sang thu hoạch.");
                startHarvestPhase(mc);
                return;
            }
            status = "Hết bột xương, đang lấy trong rương";
            if (!startChestFetch(mc)) {
                chat(mc, "Hết bột xương và không có rương gần đó, chuyển sang thu hoạch.");
                startHarvestPhase(mc);
                return;
            }
            boneFetchTried = true;
            return;
        }
        boneFetchTried = false;

        BlockPos target = saplings.get(zoneBone % saplings.size());
        hold(mc, st -> st.isOf(Items.BONE_MEAL), 1);
        face(p, Vec3d.ofCenter(target));
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false));
        zoneBone++;
    }

    private void startHarvestPhase(MinecraftClient mc) {
        phase = Phase.HARVEST;
        zoneIdx = 0;
        harvestStarted = false;
        if (skippedZones == 0) chat(mc, "Tất cả cây đã lớn! Bắt đầu giai đoạn thu hoạch.");
        else chat(mc, "Bón xong (" + skippedZones + " khu chưa lớn). Bắt đầu giai đoạn thu hoạch.");
    }

    // ===== GIAI ĐOẠN 3: CHẶT GỖ =====
    private void harvestStep(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;

        if (zoneIdx >= zones.size()) {
            chat(mc, "Hoàn tất chu kỳ đa khu. Bắt đầu chu kỳ mới.");
            zones = new ArrayList<>(); // dò lại khu ở chu kỳ sau
            nextDetect = 0;
            return;
        }
        List<BlockPos> zone = zones.get(zoneIdx);

        if (!harvestStarted) {
            treeTotal = countLogs(mc, zone);
            harvestStartTick = tick;
            if (treeTotal == 0) { // khu này không có cây để chặt
                zoneIdx++;
                return;
            }
            harvestStarted = true;
            chat(mc, "Khu " + (zoneIdx + 1) + ": phát hiện cây có " + treeTotal + " khúc gỗ cần chặt.");
        }
        status = "Đang chặt gỗ (" + (zoneIdx + 1) + "/" + zones.size() + " khu)";

        List<BlockPos> logs = scan(mc, s -> s.isIn(BlockTags.LOGS));
        logs.removeIf(l -> !nearZone(zone, l));

        if (!logs.isEmpty() && tick - harvestStartTick < HARVEST_TIMEOUT) {
            BlockPos log = logs.get(0);
            hold(mc, st -> st.getItem() instanceof AxeItem, 0);
            Vec3d cc = Vec3d.ofCenter(log);
            face(p, cc);
            Vec3d eye = p.getEyePos();
            Direction side = Direction.getFacing(eye.x - cc.x, eye.y - cc.y, eye.z - cc.z);
            mc.interactionManager.updateBlockBreakingProgress(log, side);
            p.swingHand(Hand.MAIN_HAND);
            return;
        }

        int remaining = countLogs(mc, zone);
        int chopped = Math.max(0, treeTotal - remaining);
        chat(mc, "Đã chặt xong cây " + chopped + "/" + treeTotal
                + " khúc gỗ (bỏ qua " + remaining + " khúc khuất/ngoài tầm với).");
        zoneIdx++;
        harvestStarted = false;
    }

    private boolean nearZone(List<BlockPos> zone, BlockPos l) {
        for (BlockPos s : zone) {
            if (Math.abs(s.getX() - l.getX()) <= TREE_RADIUS && Math.abs(s.getZ() - l.getZ()) <= TREE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** Đếm các khúc gỗ nối liền với gốc cây của khu (kể cả khúc ngoài tầm với). */
    private int countLogs(MinecraftClient mc, List<BlockPos> zone) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        for (BlockPos soil : zone) {
            BlockPos s = soil.up();
            if (mc.world.getBlockState(s).isIn(BlockTags.LOGS) && seen.add(s)) q.add(s);
        }
        while (!q.isEmpty() && seen.size() < MAX_TREE_LOGS) {
            BlockPos b = q.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = b.add(dx, dy, dz);
                        if (seen.contains(n)) continue;
                        if (mc.world.getBlockState(n).isIn(BlockTags.LOGS)) {
                            seen.add(n);
                            q.add(n);
                        }
                    }
                }
            }
        }
        return seen.size();
    }

    // ===== LẤY BỘT XƯƠNG TRONG RƯƠNG =====
    /** Mở rương/barrel/ender chest/shulker gần nhất trong tầm với. false = không thấy rương. */
    private boolean startChestFetch(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        List<BlockPos> boxes = scan(mc, s -> {
            var b = s.getBlock();
            return b instanceof ChestBlock || b instanceof BarrelBlock
                    || b instanceof EnderChestBlock || b instanceof ShulkerBoxBlock;
        });
        if (boxes.isEmpty()) return false;

        BlockPos box = boxes.get(0);
        Vec3d c = Vec3d.ofCenter(box);
        face(p, c);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                new BlockHitResult(c, Direction.UP, box, false));

        fetching = true;
        fetchReady = false;
        grabbed = false;
        fetchStart = tick;
        fetchDeadline = tick + FETCH_TIMEOUT;
        return true;
    }

    private void endFetch(MinecraftClient mc) {
        fetching = false;
        if (mc.player != null && mc.currentScreen instanceof HandledScreen) mc.player.closeHandledScreen();
    }

    private void handleFetch(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (tick > fetchDeadline) { endFetch(mc); return; }

        ScreenHandler h = p.currentScreenHandler;
        boolean open = mc.currentScreen instanceof HandledScreen && h != p.playerScreenHandler;
        if (!open) {
            if (grabbed) endFetch(mc); // rương tự đóng sau khi lấy
            return;
        }

        if (!fetchReady) { // chờ rương load item (rương trống thì chờ tối đa 10 tick)
            if (!hasChestItems(h, p) && tick - fetchStart < 10) return;
            fetchReady = true;
            fetchReadyTick = tick;
        }
        if (tick < nextAction) return;
        nextAction = tick + SLOT_CLICK_DELAY;

        for (Slot s : h.slots) {
            if (s.inventory == p.getInventory()) continue;
            ItemStack st = s.getStack();
            if (!st.isEmpty() && st.isOf(Items.BONE_MEAL)) {
                mc.interactionManager.clickSlot(h.syncId, s.id, 0, SlotActionType.QUICK_MOVE, p);
                grabbed = true;
                return;
            }
        }
        // không còn bột xương trong rương
        if (grabbed || tick - fetchReadyTick > 15) endFetch(mc);
    }

    private boolean hasChestItems(ScreenHandler h, ClientPlayerEntity p) {
        for (Slot s : h.slots) {
            if (s.inventory == p.getInventory()) continue;
            if (!s.getStack().isEmpty()) return true;
        }
        return false;
    }

    // ===== TIỆN ÍCH =====
    private boolean inReach(ClientPlayerEntity p, BlockPos pos) {
        return p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= REACH * REACH;
    }

    private List<BlockPos> scan(MinecraftClient mc, Predicate<BlockState> f) {
        ClientPlayerEntity p = mc.player;
        Vec3d eye = p.getEyePos();
        BlockPos c = p.getBlockPos();
        int r = 5;
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos q : BlockPos.iterate(c.add(-r, -r, -r), c.add(r, r, r))) {
            if (eye.squaredDistanceTo(Vec3d.ofCenter(q)) > REACH * REACH) continue;
            if (f.test(mc.world.getBlockState(q))) out.add(q.toImmutable());
        }
        out.sort(Comparator.comparingDouble(q -> eye.squaredDistanceTo(Vec3d.ofCenter(q))));
        return out;
    }

    private int count(MinecraftClient mc, Predicate<ItemStack> pred) {
        PlayerInventory inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && pred.test(st)) n += st.getCount();
        }
        return n;
    }

    private boolean hold(MinecraftClient mc, Predicate<ItemStack> pred, int pref) {
        ClientPlayerEntity p = mc.player;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && pred.test(st)) {
                inv.selectedSlot = i;
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && pred.test(st)) {
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, pref, SlotActionType.SWAP, p);
                inv.selectedSlot = pref;
                return true;
            }
        }
        return false;
    }

    private void face(ClientPlayerEntity p, Vec3d t) {
        Vec3d d = t.subtract(p.getEyePos());
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        p.setYaw((float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0));
        p.setPitch((float) -Math.toDegrees(Math.atan2(d.y, h)));
    }
        }
