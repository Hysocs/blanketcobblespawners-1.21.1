package com.blanketcobblespawners.utils.gui.pokemonsettings

import com.blanketcobblespawners.utils.*
import com.blanketcobblespawners.utils.gui.SpawnerPokemonSelectionGui
import com.blanketcobblespawners.utils.gui.SpawnerPokemonSelectionGui.spawnerGuisOpen
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory

object OtherSettingsGui {
    private val logger = LoggerFactory.getLogger(OtherSettingsGui::class.java)

    /**
     * Opens the Other Editable GUI for a specific Pokémon and form.
     */
    fun openOtherEditableGui(player: ServerPlayerEntity, spawnerPos: BlockPos, pokemonName: String, formName: String?) {
        val selectedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName, formName)
        if (selectedEntry == null) {
            player.sendMessage(
                Text.literal("Pokémon '$pokemonName' with form '${formName ?: "Standard"}' not found in spawner."),
                false
            )
            logger.warn("Pokémon '$pokemonName' with form '${formName ?: "Standard"}' not found in spawner at $spawnerPos.")
            return
        }

        val layout = generateOtherEditableLayout(selectedEntry)

        SpawnerPokemonSelectionGui.spawnerGuisOpen[spawnerPos] = player

        val onInteract: (InteractionContext) -> Unit = { context ->
            val clickedItem = context.clickedStack

            when (clickedItem.item) {
                Items.CLOCK -> {
                    toggleSpawnTime(
                        spawnerPos,
                        pokemonName,
                        formName,
                        player,
                        context.slotIndex
                    )
                }
                Items.SUNFLOWER -> {
                    toggleSpawnWeather(
                        spawnerPos,
                        pokemonName,
                        formName,
                        player,
                        context.slotIndex
                    )
                }
                Items.LADDER -> {
                    toggleSpawnLocation(
                        spawnerPos,
                        pokemonName,
                        formName,
                        player,
                        context.slotIndex
                    )
                }
                Items.ARROW -> {
                    CustomGui.closeGui(player)
                    player.sendMessage(Text.literal("Returning to Edit Pokémon menu"), false)
                    SpawnerPokemonSelectionGui.openPokemonEditSubGui(
                        player,
                        spawnerPos,
                        pokemonName,
                        formName
                    )
                }
            }
        }

        val onClose: (Inventory) -> Unit = {
            spawnerGuisOpen.remove(spawnerPos)
            player.sendMessage(
                Text.literal("Other Editable GUI closed for $pokemonName (${selectedEntry.formName ?: "Standard"})"),
                false
            )
        }

        CustomGui.openGui(
            player,
            "Edit Other Properties for $pokemonName (${selectedEntry.formName ?: "Standard"})",
            layout,
            onInteract,
            onClose
        )
    }

    /**
     * Generates the layout for the Other Editable GUI.
     */
    private fun generateOtherEditableLayout(selectedEntry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { ItemStack.EMPTY }

        // Button for spawn time toggle
        layout[24] = createSpawnTimeToggleButton(selectedEntry.spawnSettings.spawnTime)

        // New button for spawn weather toggle
        layout[20] = createSpawnWeatherToggleButton(selectedEntry.spawnSettings.spawnWeather)

        // Button for spawn location toggle
        layout[31] = createSpawnLocationToggleButton(selectedEntry.spawnSettings.spawnLocation)

        // Fill the rest with gray stained glass panes except for the toggle buttons and back button
        for (i in 0 until 54) {
            if (i !in listOf(20, 24, 31, 49)) {
                layout[i] = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
                    setCustomName(Text.literal(" "))
                }
            }
        }

        // Back Button
        layout[49] = ItemStack(Items.ARROW).apply {
            setCustomName(Text.literal("Back"))
            CustomGui.setItemLore(this, listOf("§eClick to return"))
        }

        return layout
    }

    private fun createSpawnLocationToggleButton(spawnLocation: String): ItemStack {
        val displayName = when (spawnLocation) {
            "SURFACE" -> "Spawn Location: SURFACE"
            "UNDERGROUND" -> "Spawn Location: UNDERGROUND"
            "WATER" -> "Spawn Location: WATER"
            else -> "Spawn Location: ALL"
        }

        return ItemStack(Items.LADDER).apply {
            setCustomName(Text.literal(displayName))
            CustomGui.setItemLore(this, listOf("§eClick to toggle spawn location"))
        }
    }
    /**
     * Creates a toggle button for spawnTime.
     */
    private fun createSpawnTimeToggleButton(spawnTime: String): ItemStack {
        val displayName = when (spawnTime) {
            "DAY" -> "Spawn Time: DAY"
            "NIGHT" -> "Spawn Time: NIGHT"
            else -> "Spawn Time: ALL"
        }

        return ItemStack(Items.CLOCK).apply {
            setCustomName(Text.literal(displayName))
            CustomGui.setItemLore(this, listOf("§eClick to toggle spawn time"))
        }
    }

    /**
     * Creates a toggle button for spawnWeather.
     */
    private fun createSpawnWeatherToggleButton(spawnWeather: String): ItemStack {
        val displayName = when (spawnWeather) {
            "CLEAR" -> "Spawn Weather: CLEAR"
            "RAIN" -> "Spawn Weather: RAIN"
            "THUNDER" -> "Spawn Weather: THUNDER"
            else -> "Spawn Weather: ALL"
        }

        return ItemStack(Items.SUNFLOWER).apply { // Using SUNFLOWER as weather icon
            setCustomName(Text.literal(displayName))
            CustomGui.setItemLore(this, listOf("§eClick to toggle spawn weather"))
        }
    }

    private fun toggleSpawnLocation(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?,
        player: ServerPlayerEntity,
        locationSlot: Int
    ) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName, formName) { selectedEntry ->
            selectedEntry.spawnSettings.spawnLocation = when (selectedEntry.spawnSettings.spawnLocation) {
                "SURFACE" -> "UNDERGROUND"
                "UNDERGROUND" -> "ALL"
                else -> "SURFACE"
            }
        } ?: run {
            player.sendMessage(Text.literal("Failed to toggle spawn location."), false)
            return
        }

        // Update the location item to reflect the new spawn location
        val updatedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName, formName)
        if (updatedEntry != null) {
            val locationItem = createSpawnLocationToggleButton(updatedEntry.spawnSettings.spawnLocation)

            // Update the GUI with the new location item without closing
            val screenHandler = player.currentScreenHandler
            if (locationSlot < screenHandler.slots.size) {
                screenHandler.slots[locationSlot].stack = locationItem
            }

            screenHandler.sendContentUpdates()

            logger.info(
                "Toggled spawn location for $pokemonName (${updatedEntry.formName ?: "Standard"}) at spawner $spawnerPos to ${updatedEntry.spawnSettings.spawnLocation}."
            )

            // Notify the player
            player.sendMessage(
                Text.literal("Set spawn location to ${updatedEntry.spawnSettings.spawnLocation} for $pokemonName."),
                false
            )
        }
    }

    /**
     * Toggles the spawnTime property of the selectedEntry.
     */
    private fun toggleSpawnTime(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?,
        player: ServerPlayerEntity,
        clockSlot: Int
    ) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName, formName) { selectedEntry ->
            selectedEntry.spawnSettings.spawnTime = when (selectedEntry.spawnSettings.spawnTime) {
                "DAY" -> "NIGHT"
                "NIGHT" -> "ALL"
                else -> "DAY"
            }
        } ?: run {
            player.sendMessage(Text.literal("Failed to toggle spawn time."), false)
            return
        }

        // Update the clock item to reflect the new spawn time
        val updatedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName, formName)
        if (updatedEntry != null) {
            val clockItem = createSpawnTimeToggleButton(updatedEntry.spawnSettings.spawnTime)

            // Update the GUI with the new clock item without closing
            val screenHandler = player.currentScreenHandler
            if (clockSlot < screenHandler.slots.size) {
                screenHandler.slots[clockSlot].stack = clockItem
            }

            screenHandler.sendContentUpdates()

            logger.info(
                "Toggled spawn time for $pokemonName (${updatedEntry.formName ?: "Standard"}) at spawner $spawnerPos to ${updatedEntry.spawnSettings.spawnTime}."
            )

            // Notify the player
            player.sendMessage(
                Text.literal("Set spawn time to ${updatedEntry.spawnSettings.spawnTime} for $pokemonName."),
                false
            )
        }
    }

    /**
     * Toggles the spawnWeather property of the selectedEntry.
     */
    private fun toggleSpawnWeather(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?,
        player: ServerPlayerEntity,
        weatherSlot: Int
    ) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName, formName) { selectedEntry ->
            selectedEntry.spawnSettings.spawnWeather = when (selectedEntry.spawnSettings.spawnWeather) {
                "CLEAR" -> "RAIN"
                "RAIN" -> "THUNDER"
                "THUNDER" -> "ALL"
                else -> "CLEAR"
            }
        } ?: run {
            player.sendMessage(Text.literal("Failed to toggle spawn weather."), false)
            return
        }

        // Update the weather item to reflect the new spawn weather
        val updatedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName, formName)
        if (updatedEntry != null) {
            val weatherItem = createSpawnWeatherToggleButton(updatedEntry.spawnSettings.spawnWeather)

            // Update the GUI with the new weather item without closing
            val screenHandler = player.currentScreenHandler
            if (weatherSlot < screenHandler.slots.size) {
                screenHandler.slots[weatherSlot].stack = weatherItem
            }

            screenHandler.sendContentUpdates()

            logger.info(
                "Toggled spawn weather for $pokemonName (${updatedEntry.formName ?: "Standard"}) at spawner $spawnerPos to ${updatedEntry.spawnSettings.spawnWeather}."
            )

            // Notify the player
            player.sendMessage(
                Text.literal("Set spawn weather to ${updatedEntry.spawnSettings.spawnWeather} for $pokemonName."),
                false
            )
        }
    }
}
