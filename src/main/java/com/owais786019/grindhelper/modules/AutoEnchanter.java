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
 * cheapest row, grind the item clean, repeat. Nothing is enchanted permanently except the final
 * successful one, so the gear can never be ruined.
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
    private Step step = Step.OPEN_TABLE;

    // BlockIterator fills results across a tick, so the position appears on a later call.
    private BlockPos pendingPos;
    private Block pendingBlock;

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
        if (mc.player == null || mc.world == null) return;
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
        if (!locate(Blocks.ENCHANTING_TABLE)) return;

        interact(pendingPos);
        pendingPos = null;
        step = Step.ENSURE_ITEM;
    }

    private void ensureItem() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler menu)) return;

        if (!menu.getSlot(0).hasStack()) {
            int slot = findEnchantableSlot();
            if (slot == -1) {
                warning("No enchantable item found in inventory.");
                toggle();
                return;
            }
            InvUtils.move().from(slot).toId(0);
            return;
        }

        if (!menu.getSlot(1).hasStack()) {
            int lapis = findItemSlot(Items.LAPIS_LAZULI);
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
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler menu)) return;

        int row = findTargetRow(menu);
        if (row != -1) {
            boolean creative = mc.player.getAbilities().creativeMode;

            if (!creative && mc.player.experienceLevel < requiredLevel.get()) {
                if (refillXp.get() && throwBottle()) return;

                warning("Need %d XP levels (have %d) and no XP bottles left.", requiredLevel.get(), mc.player.experienceLevel);
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
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler menu)) return;

        if (!mc.player.getAbilities().creativeMode && mc.player.experienceLevel < 1) {
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
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler menu)) return;

        // Pull the burned item back out of the table first.
        if (menu.getSlot(0).hasStack()) {
            InvUtils.shiftClick().slotId(0);
            return;
        }

        step = Step.OPEN_GRINDSTONE;
    }

    private void openGrindstone() {
        if (!locate(Blocks.GRINDSTONE)) return;

        interact(pendingPos);
        pendingPos = null;
        step = Step.GRIND_TAKE;
    }

    private void grindTake() {
        if (!(mc.player.currentScreenHandler instanceof GrindstoneScreenHandler menu)) return;

        if (!menu.getSlot(0).hasStack()) {
            int slot = findEnchantedSlot();
            if (slot == -1) {
                warning("Lost track of the item.");
                toggle();
                return;
            }
            InvUtils.move().from(slot).toId(0);
            return;
        }

        if (!menu.getSlot(2).hasStack()) return;

        // Shift-click the output. Vanilla empties the inputs on take.
        InvUtils.shiftClick().slotId(2);
        step = Step.OPEN_TABLE;
    }

    // --- helpers ---

    /**
     * Kicks off (or polls) a block search. BlockIterator fills results across the tick, so the
     * first call only starts the sweep and returns false; the position appears on a later tick.
     */
    private boolean locate(Block block) {
        if (pendingPos != null) return true;
        if (pendingBlock == block) return false;

        pendingBlock = block;
        final BlockPos[] found = {null};

        BlockIterator.register(4, 4, (pos, state) -> {
            if (found[0] == null && state.isOf(block)) found[0] = pos.toImmutable();
        });

        BlockIterator.after(() -> {
            if (found[0] != null) pendingPos = found[0];
        });

        return false;
    }

    private void interact(BlockPos pos) {
        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 50, () -> {
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        });
    }

    private int findTargetRow(EnchantmentScreenHandler menu) {
        Registry<Enchantment> registry = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);

        for (int i = 0; i < 3; i++) {
            int id = menu.enchantmentId[i];
            if (id < 0) continue;

            Enchantment ench = registry.get(id);
            if (ench == null) continue;

            RegistryKey<Enchantment> key = registry.getKey(ench).orElse(null);
            if (key == null || !target.get().contains(key)) continue;
            if (menu.enchantmentLevel[i] < targetLevel.get()) continue;

            return i;
        }

        return -1;
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

    private int findEnchantedSlot() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
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

    private boolean throwBottle() {
        FindItemResult bottle = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
        if (!bottle.found()) {
            bottle = InvUtils.find(Items.EXPERIENCE_BOTTLE);
            if (!bottle.found()) return false;
            InvUtils.move().from(bottle.slot()).toHotbar(0);
            return true;
        }

        FindItemResult hand = bottle;
        Rotations.rotate(mc.player.getYaw(), 90, () -> {
            if (hand.getHand() != null) {
                mc.interactionManager.interactItem(mc.player, hand.getHand());
            } else {
                InvUtils.swap(hand.slot(), true);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                InvUtils.swapBack();
            }
        });

        return true;
    }
}
