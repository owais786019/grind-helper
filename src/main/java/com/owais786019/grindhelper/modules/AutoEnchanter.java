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
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * Rerolls an enchanting table until a target enchantment is offered.
 *
 * <p>Loop: item in table -> read offers -> target present? enchant it : burn the seed at the
 * cheapest row, grind the item clean, repeat. The gear is only ever enchanted for real on the
 * final, successful attempt.
 */
public class AutoEnchanter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Set<RegistryKey<Enchantment>>> target = sgGeneral.add(new EnchantmentListSetting.Builder()
        .name("target")
        .description("Stop when any of these enchantments is offered.")
        .defaultValue(Enchantments.PROTECTION)
        .build()
    );

    private final Setting<Integer> targetLevel = sgGeneral.add(new IntSetting.Builder()
        .name("target-level")
        .description("Required level of the target enchantment.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 4)
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
    private int stall;
    private int burnWait;
    private int needLevel;
    private int doneWait;
    private Step step = Step.OPEN_TABLE;

    /** Item type of the piece being rerolled, so we never grab unrelated gear. */
    private Item targetItem;

    /** Filled by BlockIterator's sweep, consumed by locate(). */
    private BlockPos pendingPos;

    private enum Step {
        OPEN_TABLE,
        ENSURE_ITEM,
        READ_OFFERS,
        BURN,
        GRIND,
        OPEN_GRINDSTONE,
        GRIND_TAKE,
        REFILL_XP,
        DONE
    }

    public AutoEnchanter() {
        super(GrindHelper.CATEGORY, "auto-enchanter", "Rerolls an enchanting table until the target enchantment appears.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        attempts = 0;
        stall = 0;
        burnWait = 0;
        needLevel = 0;
        doneWait = 0;
        targetItem = null;
        pendingPos = null;
        step = Step.OPEN_TABLE;
    }

    @Override
    public void onDeactivate() {
        pendingPos = null;
        targetItem = null;
        closeScreen();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (++timer < delay.get()) return;
        timer = 0;

        Step before = step;
        switch (step) {
            case OPEN_TABLE -> openTable();
            case ENSURE_ITEM -> ensureItem();
            case READ_OFFERS -> readOffers();
            case BURN -> burn();
            case GRIND -> grind();
            case OPEN_GRINDSTONE -> openGrindstone();
            case GRIND_TAKE -> grindTake();
            case REFILL_XP -> refillXp();
            case DONE -> done();
        }

        // Every step either advances or performs one action; if neither happens for a long time
        // something is desynced, so bail out instead of spinning forever.
        if (step == before) {
            if (++stall > 200) {
                warning("Stuck in %s, giving up.", step);
                toggle();
            }
        } else {
            stall = 0;
        }
    }

    // --- steps ---

    private void openTable() {
        if (!locate(Blocks.ENCHANTING_TABLE)) return;

        closeScreen();
        interact(pendingPos);
        pendingPos = null;
        step = Step.ENSURE_ITEM;
    }

    private void ensureItem() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler menu)) return;

        if (!menu.getSlot(0).hasStack()) {
            if (targetItem == null) {
                int slot = findEnchantableSlot();
                if (slot == -1) {
                    warning("No enchantable item found in inventory.");
                    toggle();
                    return;
                }
                targetItem = mc.player.getInventory().getStack(slot).getItem();
                InvUtils.move().from(slot).toId(0);
                return;
            }

            int slot = findItemSlot(targetItem);
            if (slot == -1) {
                warning("Lost the item being enchanted.");
                toggle();
                return;
            }
            InvUtils.move().from(slot).toId(0);
            return;
        }

        if (targetItem == null) targetItem = menu.getSlot(0).getStack().getItem();

        // Top up towards the 3 a bottom-row pick could need, but keep going with what we have.
        if (menu.getLapisCount() < 3) {
            int lapis = findItemSlot(Items.LAPIS_LAZULI);
            if (lapis != -1) {
                InvUtils.move().from(lapis).toId(1);
                return;
            }
            if (menu.getLapisCount() < 1) {
                warning("Out of lapis lazuli.");
                toggle();
                return;
            }
        }

        step = Step.READ_OFFERS;
    }

    private void readOffers() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler menu)) return;

        int row = findTargetRow(menu);
        if (row != -1) {
            // Server requires both the row's level cost and its displayed power.
            int need = Math.max(row + 1, menu.enchantmentPower[row]);
            if (!mc.player.getAbilities().creativeMode && mc.player.experienceLevel < need) {
                if (refillXp.get()) {
                    needLevel = need;
                    step = Step.REFILL_XP;
                    return;
                }

                warning("Need %d XP levels for row %d (have %d).", need, row + 1, mc.player.experienceLevel);
                toggle();
                return;
            }

            clickRow(menu, row);
            doneWait = 0;
            step = Step.DONE;
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
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler menu)) return;

        // enchantmentPower is 0 when the row is unavailable (no lapis or not enough levels).
        if (menu.enchantmentPower[0] <= 0) {
            if (menu.getLapisCount() < 1) {
                step = Step.ENSURE_ITEM;
                return;
            }

            // Reopening cannot reroll the offers (the seed only advances after an enchant), so a
            // greyed-out row with lapis in place means this item simply cannot be enchanted.
            warning("This item cannot be enchanted.");
            toggle();
            return;
        }

        if (!mc.player.getAbilities().creativeMode && mc.player.experienceLevel < 1) {
            if (refillXp.get()) {
                needLevel = 1;
                step = Step.REFILL_XP;
                return;
            }

            warning("Out of XP levels and no XP bottles left.");
            toggle();
            return;
        }

        clickRow(menu, 0);
        attempts++;
        burnWait = 0;
        step = Step.GRIND;
    }

    private void grind() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler menu)) return;

        if (menu.getSlot(0).hasStack()) {
            // The burn is applied server-side, so give it a moment before deciding it failed.
            if (!menu.getSlot(0).getStack().hasEnchantments()) {
                if (++burnWait > 40) {
                    warning("Reroll click was rejected, stopping.");
                    toggle();
                }
                return;
            }
            InvUtils.shiftClick().slotId(0);
            return;
        }

        step = Step.OPEN_GRINDSTONE;
    }

    private void openGrindstone() {
        if (!locate(Blocks.GRINDSTONE)) return;

        closeScreen();
        interact(pendingPos);
        pendingPos = null;
        step = Step.GRIND_TAKE;
    }

    private void grindTake() {
        if (!(mc.player.currentScreenHandler instanceof GrindstoneScreenHandler menu)) return;

        if (!menu.getSlot(0).hasStack()) {
            int slot = findEnchantedSlot();
            if (slot == -1) {
                warning("Lost the item being ground.");
                toggle();
                return;
            }
            InvUtils.move().from(slot).toId(0);
            return;
        }

        if (!menu.getSlot(2).hasStack()) return;

        InvUtils.shiftClick().slotId(2);
        step = Step.OPEN_TABLE;
    }

    private void done() {
        if (++doneWait < 5) return;
        info("Got the target after %d reroll(s).", attempts);
        toggle();
    }

    private void refillXp() {
        if (mc.player.experienceLevel >= needLevel) {
            step = Step.OPEN_TABLE;
            return;
        }

        // Bottles cannot be thrown from inside a container screen.
        closeScreen();
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;

        if (!throwBottle()) {
            warning("Out of XP bottles, need level %d.", needLevel);
            toggle();
        }
    }

    // --- helpers ---

    /**
     * Polls a block search. BlockIterator sweeps at a lower event priority than this module, so a
     * registration only lands on the following tick — hence re-registering every tick until the
     * position shows up.
     */
    private boolean locate(Block block) {
        if (pendingPos != null) return true;

        BlockIterator.register(4, 4, (pos, state) -> {
            if (pendingPos == null && state.isOf(block)) pendingPos = pos.toImmutable();
        });

        return false;
    }

    private void closeScreen() {
        if (mc.player == null) return;
        if (mc.player.currentScreenHandler == mc.player.playerScreenHandler) return;
        mc.player.closeScreen();
    }

    private void interact(BlockPos pos) {
        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 50, () -> {
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        });
    }

    /** Highest row offering the target, so the strongest qualifying result wins. */
    private int findTargetRow(EnchantmentScreenHandler menu) {
        Registry<Enchantment> registry = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        int best = -1;

        for (int i = 0; i < 3; i++) {
            int id = menu.enchantmentId[i];
            if (id < 0) continue;

            Enchantment ench = registry.get(id);
            if (ench == null) continue;

            RegistryKey<Enchantment> key = registry.getKey(ench).orElse(null);
            if (key == null || !target.get().contains(key)) continue;
            if (menu.enchantmentLevel[i] < targetLevel.get()) continue;
            // clickMenuButton consumes row+1 lapis, so a row we cannot pay for is not an offer.
            if (menu.getLapisCount() < i + 1) continue;

            best = i;
        }

        return best;
    }

    private void clickRow(EnchantmentScreenHandler menu, int row) {
        mc.interactionManager.clickButton(menu.syncId, row);
    }

    private int findEnchantableSlot() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!stack.isEnchantable()) continue;
            if (stack.hasEnchantments()) continue;
            return i;
        }
        return -1;
    }

    /** Only ever returns the tracked item type, never unrelated enchanted gear. */
    private int findEnchantedSlot() {
        if (targetItem == null) return -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() != targetItem) continue;
            if (stack.hasEnchantments()) return i;
        }
        return -1;
    }

    private int findItemSlot(Item item) {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    /** True when an action was taken: either a bottle was thrown or one was moved to hand. */
    private boolean throwBottle() {
        FindItemResult bottle = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
        if (!bottle.found()) {
            bottle = InvUtils.find(stack -> stack.getItem() == Items.EXPERIENCE_BOTTLE, 0, 35);
            if (!bottle.found()) return false;

            InvUtils.move().from(bottle.slot()).toHotbar(0);
            return true;
        }

        int slot = bottle.slot();
        Rotations.rotate(mc.player.getYaw(), 90, () -> {
            InvUtils.swap(slot, true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
        });

        return true;
    }
}
