package com.owais786019.grindhelper.modules;

import com.owais786019.grindhelper.GrindHelper;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnchantmentListSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.class_1268;
import net.minecraft.class_1718;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_1802;
import net.minecraft.class_1887;
import net.minecraft.class_2248;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_2350;
import net.minecraft.class_2378;
import net.minecraft.class_243;
import net.minecraft.class_3803;
import net.minecraft.class_3965;
import net.minecraft.class_5321;
import net.minecraft.class_7924;

import java.util.Set;

/**
 * Rerolls an enchanting table until a target enchantment is offered.
 *
 * <p>Loop: item in table -> read offers -> target present? enchant it : burn the seed at the
 * cheapest row, grind the item clean, repeat. Nothing is enchanted permanently except the final
 * successful one, so the gear can never be ruined.
 */
public class AutoEnchanter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Set<class_5321<class_1887>>> target = sgGeneral.add(new EnchantmentListSetting.Builder()
        .name("target")
        .description("Stop when any of these enchantments is offered.")
        .defaultValue(class_1887.field_9111)
        .build()
    );

    private final Setting<Integer> targetLevel = sgGeneral.add(new IntSetting.Builder()
        .name("target-level")
        .description("Required level of the target enchantment.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Integer> maxAttempts = sgGeneral.add(new IntSetting.Builder()
        .name("max-attempts")
        .description("Give up after this many rerolls.")
        .defaultValue(100)
        .min(1)
        .sliderRange(1, 500)
        .build()
    );

    private final Setting<Integer> requiredLevel = sgGeneral.add(new IntSetting.Builder()
        .name("required-level")
        .description("XP level needed to unlock the bottom row. 30 with 15 bookshelves.")
        .defaultValue(30)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> refillXp = sgGeneral.add(new BoolSetting.Builder()
        .name("refill-xp")
        .description("Throw XP bottles from your inventory when levels are too low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between actions.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private int timer;
    private int attempts;
    private Step step = Step.IDLE;

    // Block search is async (BlockIterator fills over the tick), so we poll for the result.
    private class_2338 pendingPos;
    private class_2248 pendingBlock;

    private enum Step {
        OPEN_TABLE,
        ENSURE_ITEM,
        READ_OFFERS,
        BURN,
        GRIND,
        OPEN_GRINDSTONE,
        GRIND_TAKE
    }

    public AutoEnchanter() {
        super(GrindHelper.CATEGORY, "auto-enchanter", "Rerolls an enchanting table until the target enchantment appears.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        attempts = 0;
        pendingPos = null;
        pendingBlock = null;
        step = Step.OPEN_TABLE;
    }

    @Override
    public void onDeactivate() {
        pendingPos = null;
        pendingBlock = null;
        step = Step.OPEN_TABLE;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.field_1724 == null || mc.field_1687 == null) return;
        if (++timer < delay.get()) return;
        timer = 0;

        switch (step) {
            case OPEN_TABLE -> openTable();
            case ENSURE_ITEM -> ensureItem();
            case READ_OFFERS -> readOffers();
            case BURN -> burn();
            case GRIND -> grind();
            case OPEN_GRINDSTONE -> openGrindstone();
            case GRIND_TAKE -> grindTake();
        }
    }

    // --- steps ---

    private void openTable() {
        if (!locate(class_2246.field_10485)) return;

        interact(pendingPos);
        pendingPos = null;
        step = Step.ENSURE_ITEM;
    }

    private void ensureItem() {
        if (!(mc.field_1724.field_7512 instanceof class_1718 menu)) return;

        if (!menu.method_7611(0).method_7677()) {
            int slot = findEnchantableSlot();
            if (slot == -1) {
                warning("No enchantable item found in inventory.");
                toggle();
                return;
            }
            InvUtils.move().from(slot).toId(0);
            return;
        }

        if (!menu.method_7611(1).method_7677()) {
            int lapis = findItemSlot(class_1802.field_8759);
            if (lapis == -1) {
                warning("Out of lapis lazuli.");
                toggle();
                return;
            }
            InvUtils.move().from(lapis).toId(1);
            return;
        }

        step = Step.READ_OFFERS;
    }

    private void readOffers() {
        if (!(mc.field_1724.field_7512 instanceof class_1718 menu)) return;

        int row = findTargetRow(menu);
        if (row != -1) {
            boolean creative = mc.field_1724.field_7503.field_7477;

            if (!creative && mc.field_1724.field_7502 < requiredLevel.get()) {
                if (refillXp.get() && throwBottle()) return;

                warning("Need %d XP levels (have %d) and no XP bottles left.", requiredLevel.get(), mc.field_1724.field_7502);
                toggle();
                return;
            }

            clickRow(menu, row);
            info("Got the target after %d reroll(s).", attempts);
            toggle();
            return;
        }

        if (attempts >= maxAttempts.get()) {
            warning("Gave up after %d rerolls.", attempts);
            toggle();
            return;
        }

        step = Step.BURN;
    }

    private void burn() {
        if (!(mc.field_1724.field_7512 instanceof class_1718 menu)) return;

        if (!mc.field_1724.field_7503.field_7477 && mc.field_1724.field_7502 < 1) {
            if (refillXp.get() && throwBottle()) return;

            warning("Out of XP levels and no XP bottles left.");
            toggle();
            return;
        }

        clickRow(menu, 0);
        attempts++;
        step = Step.GRIND;
    }

    private void grind() {
        if (!(mc.field_1724.field_7512 instanceof class_1718 menu)) return;

        // Pull the burned item back out of the table first.
        if (!menu.method_7611(0).method_7677()) {
            InvUtils.shiftClick().slotId(0);
            return;
        }

        step = Step.OPEN_GRINDSTONE;
    }

    private void openGrindstone() {
        if (!locate(class_2246.field_16337)) return;

        interact(pendingPos);
        pendingPos = null;
        step = Step.GRIND_TAKE;
    }

    private void grindTake() {
        if (!(mc.field_1724.field_7512 instanceof class_3803 menu)) return;

        if (!menu.method_7611(0).method_7677()) {
            int slot = findEnchantedSlot();
            if (slot == -1) {
                warning("Lost track of the item.");
                toggle();
                return;
            }
            InvUtils.move().from(slot).toId(0);
            return;
        }

        if (menu.method_7611(2).method_7677()) return;

        // Shift-click the output. Vanilla empties the inputs on take.
        InvUtils.shiftClick().slotId(2);
        step = Step.OPEN_TABLE;
    }

    // --- helpers ---

    /**
     * Kicks off (or polls) a block search. BlockIterator fills results across the tick, so the
     * first call only starts the sweep and returns false; the position appears on a later tick.
     */
    private boolean locate(class_2248 block) {
        if (pendingPos != null) return true;

        if (pendingBlock == block) {
            // already sweeping, nothing found yet
            return false;
        }

        pendingBlock = block;
        final class_2338[] found = {null};

        BlockIterator.register(4, 4, (pos, state) -> {
            if (found[0] == null && state.method_27852(block)) found[0] = pos.method_10062();
        });

        BlockIterator.after(() -> {
            if (found[0] != null) pendingPos = found[0];
        });

        return false;
    }

    private void interact(class_2338 pos) {
        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 50, () -> {
            class_3965 hit = new class_3965(class_243.method_24953(pos), class_2350.field_11036, pos, false);
            mc.field_1761.method_2896(mc.field_1724, class_1268.field_5808, hit);
        });
    }

    private int findTargetRow(class_1718 menu) {
        class_2378<class_1887> registry = mc.field_1687.method_30349().method_30530(class_7924.field_41265);

        for (int i = 0; i < 3; i++) {
            int id = menu.field_7812[i];
            if (id < 0) continue;

            class_1887 ench = registry.method_10200(id);
            if (ench == null) continue;

            class_5321<class_1887> key = registry.method_10221(ench);
            if (key == null || !target.get().contains(key)) continue;
            if (menu.field_7810[i] < targetLevel.get()) continue;

            return i;
        }

        return -1;
    }

    private void clickRow(class_1718 menu, int row) {
        mc.field_1761.method_2900(menu.field_7763, row);
    }

    private int findEnchantableSlot() {
        for (int i = 0; i < 36; i++) {
            class_1799 stack = mc.field_1724.method_31548().method_5438(i);
            if (stack.method_7960()) continue;
            if (!stack.method_7909().method_7885()) continue;
            if (stack.method_57530()) continue;
            return i;
        }
        return -1;
    }

    private int findEnchantedSlot() {
        for (int i = 0; i < 36; i++) {
            class_1799 stack = mc.field_1724.method_31548().method_5438(i);
            if (stack.method_7960()) continue;
            if (stack.method_57530()) return i;
        }
        return -1;
    }

    private int findItemSlot(class_1792 item) {
        for (int i = 0; i < 36; i++) {
            if (mc.field_1724.method_31548().method_5438(i).method_7909() == item) return i;
        }
        return -1;
    }

    private boolean throwBottle() {
        FindItemResult bottle = InvUtils.findInHotbar(class_1802.field_8287);
        if (!bottle.found()) {
            bottle = InvUtils.find(class_1802.field_8287);
            if (!bottle.found()) return false;
            InvUtils.move().from(bottle.slot()).toHotbar(0);
            return true;
        }

        FindItemResult hand = bottle;
        Rotations.rotate(mc.field_1724.method_36454(), 90, () -> {
            if (hand.getHand() != null) {
                mc.field_1761.method_2919(mc.field_1724, hand.getHand());
            } else {
                InvUtils.swap(hand.slot(), true);
                mc.field_1761.method_2919(mc.field_1724, class_1268.field_5808);
                InvUtils.swapBack();
            }
        });

        return true;
    }
}
