#!/usr/bin/env bash
# Temporary helper: prints Minecraft/Fabric API signatures into the CI log.
set -u
mkdir -p dump idx
CANDIDATES=$(find "$HOME/.gradle/caches/fabric-loom" .gradle "$HOME/.gradle/caches/modules-2/files-2.1" -name '*.jar' 2>/dev/null | grep -Ei 'minecraft|fabric|loom' )
n=0
for j in $CANDIDATES; do n=$((n+1)); unzip -Z1 "$j" > "idx/$n.txt" 2>/dev/null; echo "$j" > "idx/$n.path"; done

jar_for() { # $1 = path inside jar
  for f in idx/*.txt; do if grep -qx "$1" "$f"; then cat "${f%.txt}.path"; return; fi; done
}
jp() { # javap a class: $1 = dotted name
  local p="${1//.//}.class"; local j; j=$(jar_for "$p")
  echo "=================== javap $1  ($j)"
  if [ -n "$j" ]; then javap -p -cp "$j" "$1" 2>&1; else echo "NOT FOUND"; fi
}
src() { # decompiled source: $1 = dotted name
  local p="${1//.//}.java"; local j; j=$(jar_for "$p")
  echo "=================== source $1  ($j)"
  if [ -n "$j" ]; then unzip -p "$j" "$p" 2>&1; else echo "NOT FOUND (falling back to javap -c)"; jpc "$1"; fi
}
jpc() { local p="${1//.//}.class"; local j; j=$(jar_for "$p"); [ -n "$j" ] && javap -p -c -cp "$j" "$1" 2>&1 | head -1500; }
list() { # list classes matching a regex
  echo "=================== classes matching $1"
  cat idx/*.txt | grep -E "$1" | grep -v '\$' | sort -u
}

{
  echo "Jars:"; for f in idx/*.path; do echo "$(cat "$f") $(wc -l < "${f%.path}.txt")"; done
  list '^net/minecraft/client/gui/[A-Za-z]+\.class$'
  list '^net/minecraft/client/gui/screens/inventory/[A-Za-z]+\.class$'
  list '^net/minecraft/client/gui/components/[A-Za-z]+\.class$'
  list '^net/minecraft/client/gui/components/events/[A-Za-z]+\.class$'
  list '^net/minecraft/client/input/[A-Za-z]+\.class$'
  list '^net/minecraft/world/item/enchantment/[A-Za-z]+\.class$'
  list '^net/fabricmc/fabric/api/client/screen/v1/.*\.class$'
  list '^net/fabricmc/fabric/api/client/event/lifecycle/v1/.*\.class$'
  list '^net/fabricmc/fabric/api/client/networking/v1/.*\.class$'
} > dump/part1.txt

{
  jp net.minecraft.client.gui.GuiGraphics
  jp net.minecraft.client.gui.GuiGraphicsExtractor
  jp net.minecraft.client.gui.Font
  jp net.minecraft.client.renderer.RenderPipelines
  jp net.minecraft.client.gui.ActiveTextCollector
} > dump/part2.txt

{
  jp net.minecraft.client.gui.components.AbstractWidget
  jp net.minecraft.client.gui.components.events.GuiEventListener
  jp net.minecraft.client.gui.components.events.ContainerEventHandler
  jp net.minecraft.client.input.MouseButtonEvent
  jp net.minecraft.client.input.MouseButtonInfo
  jp net.minecraft.client.input.InputWithModifiers
  jp net.minecraft.client.gui.components.Renderable
  jp net.minecraft.client.gui.narration.NarratableEntry
  jp net.minecraft.client.gui.narration.NarrationElementOutput
  jp net.minecraft.client.gui.components.Tooltip
  jp net.minecraft.client.gui.screens.Screen
} > dump/part3.txt

{
  src net.minecraft.client.gui.screens.inventory.AnvilScreen
  src net.minecraft.client.gui.screens.inventory.ItemCombinerScreen
  src net.minecraft.world.inventory.AnvilMenu
  src net.minecraft.world.inventory.ItemCombinerMenu
} > dump/part4.txt

{
  src net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
} > dump/part5.txt

{
  src net.minecraft.client.gui.components.AbstractWidget
  src net.minecraft.client.gui.components.AbstractButton
  src net.minecraft.client.gui.components.Button
  src net.minecraft.client.gui.components.Checkbox
} > dump/part6.txt

{
  jp net.minecraft.world.item.enchantment.Enchantment
  jp 'net.minecraft.world.item.enchantment.Enchantment$EnchantmentDefinition'
  jp net.minecraft.world.item.enchantment.ItemEnchantments
  jp 'net.minecraft.world.item.enchantment.ItemEnchantments$Mutable'
  jp net.minecraft.world.item.enchantment.EnchantmentHelper
  jp net.minecraft.tags.EnchantmentTags
  echo "=================== DataComponents (filtered)"
  j=$(jar_for net/minecraft/core/component/DataComponents.class); javap -p -cp "$j" net.minecraft.core.component.DataComponents | grep -Ei 'ENCHANT|REPAIR|CUSTOM_NAME|ITEM_NAME'
  echo "=================== ItemStack (filtered)"
  j=$(jar_for net/minecraft/world/item/ItemStack.class); javap -p -cp "$j" net.minecraft.world.item.ItemStack | grep -Ei 'enchant|getItem|is\(|isEmpty|copy|matches|same|get\(|getOrDefault|has\(|HoverName|getCount|Holder|repair'
  echo "=================== Player (filtered)"
  j=$(jar_for net/minecraft/world/entity/player/Player.class); javap -p -cp "$j" net.minecraft.world.entity.player.Player | grep -Ei 'experience|abilit|infinite|creative|inventory'
  echo "=================== AbstractContainerMenu (filtered)"
  j=$(jar_for net/minecraft/world/inventory/AbstractContainerMenu.class); javap -p -cp "$j" net.minecraft.world.inventory.AbstractContainerMenu | grep -Ei 'slots|getSlot|carried|containerId'
  jp net.minecraft.world.inventory.Slot
} > dump/part7.txt

{
  jp net.minecraft.core.Holder
  jp net.minecraft.core.HolderSet
  jp 'net.minecraft.core.HolderLookup$Provider'
  jp 'net.minecraft.core.HolderLookup$RegistryLookup'
  jp net.minecraft.core.RegistryAccess
  jp net.minecraft.network.chat.Component
  jp net.minecraft.resources.Identifier
  jp net.minecraft.util.ARGB
  echo "=================== Minecraft (filtered)"
  j=$(jar_for net/minecraft/client/Minecraft.class); javap -p -cp "$j" net.minecraft.client.Minecraft | grep -Ei ' player| level|getConnection|font|getInstance|screen'
  echo "=================== ClientPacketListener (filtered)"
  j=$(jar_for net/minecraft/client/multiplayer/ClientPacketListener.class); javap -p -cp "$j" net.minecraft.client.multiplayer.ClientPacketListener | grep -Ei 'registryAccess'
  echo "=================== ClientLevel (filtered)"
  j=$(jar_for net/minecraft/client/multiplayer/ClientLevel.class); javap -p -cp "$j" net.minecraft.client.multiplayer.ClientLevel | grep -Ei 'registryAccess'
} > dump/part8.txt

{
  jp net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
  jp 'net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit'
  jp 'net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterRender'
  jp 'net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterTick'
  jp 'net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$Remove'
  jp net.fabricmc.fabric.api.client.screen.v1.Screens
  jp net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
  jp 'net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents$AllowMouseClick'
  jp 'net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents$AllowMouseScroll'
  jp 'net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents$AllowMouseRelease'
  jp 'net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterExtract'
  jp net.minecraft.core.HolderGetter
  jp net.minecraft.core.Registry
  jp net.minecraft.world.item.ItemInstance
  jp net.minecraft.core.component.DataComponentHolder
  jp net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
  jp net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
  jp 'net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Disconnect'
} > dump/part9.txt

wc -l dump/*.txt
