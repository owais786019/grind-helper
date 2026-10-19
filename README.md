# Grind Helper

Meteor Client addon for Minecraft **1.21.11**.

## Modules

### Auto Enchanter

Rerolls an enchanting table until a target enchantment (default: Protection IV) is offered,
then enchants the item with it.

**Loop**

1. Opens the enchanting table (must be within 4 blocks).
2. Puts your item in the item slot and lapis in the lapis slot.
3. Reads the three offers.
4. If the target is offered on an affordable row — make sure you have enough XP levels, then enchant and stop.
5. Otherwise burn the seed by enchanting the cheapest row, then strip the enchantment off at a grindstone (within 4 blocks) and repeat.

Your gear is never permanently enchanted until the final success, so it cannot be ruined.

**Settings**

| Setting | Default | Meaning |
|---|---|---|
| `target` | `minecraft:protection` | Enchantments that stop the loop |
| `target-level` | `4` | Required level of the target |
| `max-attempts` | `100` | Give up after this many rerolls |
| `required-level` | `30` | XP level needed for the bottom row (30 with 15 bookshelves) |
| `refill-xp` | `true` | Throw XP bottles when levels are too low |
| `delay` | `3` | Ticks between actions |

**Requirements**

- Enchanting table within 4 blocks, with 15 bookshelves for level-30 offers.
- Grindstone within 4 blocks.
- Lapis lazuli and (optionally) Bottles o' Enchanting in your inventory.

## Building

Built by GitHub Actions on every push. Grab the jar from the **Actions** artifacts or the
`latest` release.

## Install

Drop the jar into `.minecraft/mods` alongside `meteor-client`.
