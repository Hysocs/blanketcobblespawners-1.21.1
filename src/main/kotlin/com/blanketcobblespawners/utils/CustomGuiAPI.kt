// File: CustomGuiAPI.kt
package com.blanketcobblespawners.utils

import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.component.type.ProfileComponent
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.ScreenHandlerType
import net.minecraft.screen.SimpleNamedScreenHandlerFactory
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ClickType
import org.slf4j.LoggerFactory
import java.util.UUID

data class InteractionContext(
    val slotIndex: Int,
    val clickType: ClickType,
    val button: Int,
    val clickedStack: ItemStack,
    val player: ServerPlayerEntity
)

object CustomGui {
    private val logger = LoggerFactory.getLogger(CustomGui::class.java)

    /**
     * Opens a custom GUI for the specified player without using custom packets.
     * The layout is displayed in the GUI, and interactions with certain slots can be blocked.
     */
    fun openGui(
        player: ServerPlayerEntity,
        title: String,
        layout: List<ItemStack>,
        onInteract: (InteractionContext) -> Unit,
        onClose: (Inventory) -> Unit
    ) {
        val factory = SimpleNamedScreenHandlerFactory({ syncId, inv, _ ->
            CustomScreenHandler(syncId, inv, layout, onInteract, onClose)
        }, Text.literal(title))
        player.openHandledScreen(factory)
    }

    /**
     * Creates a GUI button using a player head with a custom texture.
     */
    fun createPlayerHeadButton(
        textureName: String,
        title: Text,
        lore: List<Text>,
        textureValue: String
    ): ItemStack {
        val itemStack = ItemStack(Items.PLAYER_HEAD)
        itemStack.set(DataComponentTypes.ITEM_NAME, title)
        itemStack.set(DataComponentTypes.LORE, LoreComponent(lore))

        // Use a dummy name (like "custom_head") for the profile to bypass the 16-character limit
        val profile = GameProfile(UUID.randomUUID(), "custom_head")
        profile.properties.put("textures", Property("textures", textureValue))
        itemStack.set(DataComponentTypes.PROFILE, ProfileComponent(profile))

        return itemStack
    }


    /**
     * Creates a GUI button using a normal item.
     */
    fun createNormalButton(
        item: ItemStack,
        displayName: String,
        lore: List<String>
    ): ItemStack {
        val newStack = item.copy()
        newStack.set(DataComponentTypes.ITEM_NAME, Text.literal(displayName))
        val loreText = lore.map { Text.literal(it) }
        newStack.set(DataComponentTypes.LORE, LoreComponent(loreText))
        return newStack
    }

    /**
     * Sets item lore with proper error handling.
     */
    fun setItemLore(itemStack: ItemStack, loreLines: List<Any?>) {
        val textLines = loreLines.mapNotNull { line ->
            when (line) {
                is Text -> line
                is String -> Text.literal(line)
                null -> null
                else -> Text.literal(line.toString())
            }
        }

        if (textLines.size != loreLines.size) {
            itemStack.set(
                DataComponentTypes.LORE,
                LoreComponent(listOf(Text.literal("§cError setting lore"), Text.literal("§7One of the lines was null")))
            )
            return
        }

        itemStack.set(DataComponentTypes.LORE, LoreComponent(textLines))
    }

    /**
     * Strips formatting codes.
     */
    fun stripFormatting(text: String): String {
        return text.replace(Regex("§[0-9a-fk-or]"), "")
    }

    /**
     * Adds an enchantment glint effect to an ItemStack.
     */
    fun addEnchantmentGlint(itemStack: ItemStack) {
        itemStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
    }


    /**
     * Refreshes the GUI layout for the specified player.
     * This updates the internal inventory on the server side and the changes are automatically synced to the client.
     */
    fun refreshGui(player: ServerPlayerEntity, newLayout: List<ItemStack>) {
        val screenHandler = player.currentScreenHandler as? CustomScreenHandler
        if (screenHandler != null) {
            screenHandler.updateInventory(newLayout)
        } else {
            logger.warn("Player ${player.name.string} does not have the expected screen handler open.")
        }
    }

    /**
     * Closes the currently open GUI for the specified player.
     */
    fun closeGui(player: ServerPlayerEntity) {
        player.closeHandledScreen()
    }
}

class CustomScreenHandler(
    syncId: Int,
    playerInventory: PlayerInventory,
    layout: List<ItemStack>,
    private var onInteract: ((InteractionContext) -> Unit)?,
    private var onClose: ((Inventory) -> Unit)?
) : ScreenHandler(ScreenHandlerType.GENERIC_9X6, syncId) {

    private val guiInventory: Inventory = object : Inventory {
        private val items = Array<ItemStack?>(54) { ItemStack.EMPTY }

        init {
            layout.forEachIndexed { index, itemStack ->
                if (index < size()) items[index] = itemStack
            }
        }

        override fun clear() {
            items.fill(ItemStack.EMPTY)
        }

        override fun size() = items.size
        override fun isEmpty() = items.all { it?.isEmpty ?: true }
        override fun getStack(slot: Int) = items[slot] ?: ItemStack.EMPTY

        override fun removeStack(slot: Int, amount: Int): ItemStack {
            val stack = getStack(slot)
            return if (stack.count <= amount) {
                removeStack(slot)
            } else {
                val splitStack = stack.split(amount)
                items[slot] = stack
                splitStack
            }
        }

        override fun removeStack(slot: Int): ItemStack {
            val stack = getStack(slot)
            items[slot] = ItemStack.EMPTY
            return stack
        }

        override fun setStack(slot: Int, stack: ItemStack) {
            items[slot] = stack
        }

        override fun markDirty() {}
        override fun canPlayerUse(player: PlayerEntity) = true
    }

    init {
        // Define which slots are interactive. In this example, we block all interaction.
        // If you want some slots interactive, include their indices in a list.
        val nonInteractiveSlots = (0 until guiInventory.size()).toList()
        for (slotIndex in 0 until guiInventory.size()) {
            val isInteractive = !nonInteractiveSlots.contains(slotIndex)
            addSlot(InteractiveSlot(guiInventory, slotIndex, isInteractive))
        }

        // Add player inventory slots (if you want them blocked too, you can also use InteractiveSlot)
        for (i in 0..2) {
            for (j in 0..8) {
                val index = j + i * 9 + 9
                addSlot(Slot(playerInventory, index, 8 + j * 18, 84 + i * 18))
            }
        }
        for (k in 0..8) {
            addSlot(Slot(playerInventory, k, 8 + k * 18, 142))
        }
    }

    override fun canUse(player: PlayerEntity) = true

    override fun onSlotClick(slotIndex: Int, button: Int, actionType: SlotActionType, player: PlayerEntity) {
        val isCustomGuiSlot = slotIndex in 0 until guiInventory.size()
        if (isCustomGuiSlot && player is ServerPlayerEntity) {
            val stack = guiInventory.getStack(slotIndex)
            val clickType = if (button == 0) ClickType.LEFT else ClickType.RIGHT
            val context = InteractionContext(slotIndex, clickType, button, stack, player)
            onInteract?.invoke(context)
            return
        }
        super.onSlotClick(slotIndex, button, actionType, player)
    }

    override fun quickMove(player: PlayerEntity, index: Int) = ItemStack.EMPTY

    override fun onClosed(player: PlayerEntity) {
        super.onClosed(player)
        onClose?.invoke(guiInventory)
        onInteract = null
        onClose = null
    }

    fun updateInventory(newLayout: List<ItemStack>) {
        newLayout.forEachIndexed { index, itemStack ->
            guiInventory.setStack(index, itemStack)
        }
        sendContentUpdates()
    }

    fun updateSlot(slotIndex: Int, itemStack: ItemStack) {
        guiInventory.setStack(slotIndex, itemStack)
        sendContentUpdates()
    }

}

class InteractiveSlot(inventory: Inventory, index: Int, private val isInteractive: Boolean) : Slot(inventory, index, 0, 0) {
    override fun canInsert(stack: ItemStack) = isInteractive
    override fun canTakeItems(player: PlayerEntity) = isInteractive
}

fun ItemStack.setCustomName(name: Text) {
    this.set(DataComponentTypes.ITEM_NAME, name)
}

