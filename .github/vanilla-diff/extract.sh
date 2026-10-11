#!/usr/bin/env bash
# TEMPORARY: copies the decompiled vanilla classes the mod relies on out of Loom's sources jars.
# Usage: extract.sh <minecraft version> <output dir>
set -u
ver="$1"; out="$2"
mkdir -p "$out"
jars=$(find .gradle ~/.gradle/caches -name "*${ver}*sources.jar" 2>/dev/null | grep -v "fabric-api\|fabric-loader" | sort -u)
echo "jars for $ver:"; echo "$jars"
classes="
net/minecraft/world/inventory/AnvilMenu.java
net/minecraft/world/inventory/ItemCombinerMenu.java
net/minecraft/world/inventory/ItemCombinerMenuSlotDefinition.java
net/minecraft/world/inventory/AbstractContainerMenu.java
net/minecraft/world/inventory/Slot.java
net/minecraft/world/inventory/ContainerInput.java
net/minecraft/client/gui/screens/inventory/AnvilScreen.java
net/minecraft/client/gui/screens/inventory/ItemCombinerScreen.java
net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java
net/minecraft/client/gui/components/AbstractWidget.java
net/minecraft/client/gui/components/EditBox.java
net/minecraft/client/gui/GuiGraphicsExtractor.java
net/minecraft/client/multiplayer/MultiPlayerGameMode.java
net/minecraft/client/multiplayer/PlayerInfo.java
net/minecraft/client/input/KeyEvent.java
net/minecraft/client/input/MouseButtonEvent.java
net/minecraft/client/input/CharacterEvent.java
net/minecraft/world/item/ItemStack.java
net/minecraft/world/item/enchantment/EnchantmentHelper.java
net/minecraft/world/item/enchantment/Enchantment.java
net/minecraft/world/item/enchantment/ItemEnchantments.java
net/minecraft/core/component/DataComponentMap.java
net/minecraft/core/component/PatchedDataComponentMap.java
net/minecraft/util/StringUtil.java
net/minecraft/tags/EnchantmentTags.java
net/minecraft/network/protocol/game/ServerboundRenameItemPacket.java
com/mojang/blaze3d/platform/InputConstants.java
net/minecraft/client/KeyboardHandler.java
net/minecraft/client/input/InputWithModifiers.java
"
for c in $classes; do
  found=0
  for j in $jars; do
    if unzip -l "$j" "$c" >/dev/null 2>&1; then
      mkdir -p "$out/$(dirname "$c")"
      unzip -p "$j" "$c" > "$out/$c"
      found=1
      break
    fi
  done
  [ "$found" = 1 ] || echo "MISSING in $ver: $c"
done
