// File: CommandRegistrar.kt
package com.blanketcobblespawners.utils

import com.blanketcobblespawners.BlanketCobbleSpawners
import com.blanketcobblespawners.BlanketCobbleSpawners.parseDimension
import com.blanketcobblespawners.utils.ConfigManager.logDebug
import com.blanketcobblespawners.utils.gui.SpawnerListGui
import com.blanketcobblespawners.utils.ParticleUtils.toggleVisualization
import com.blanketcobblespawners.utils.gui.SpawnerPokemonSelectionGui
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.block.Blocks
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.CustomModelDataComponent
import net.minecraft.component.type.LoreComponent
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

object CommandRegistrar {

    private val logger = LoggerFactory.getLogger("CommandRegistrar")

    fun registerCommands() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            logger.info("Registering commands for BlanketCobbleSpawners.")
            registerBlanketCobbleSpawnersCommand(dispatcher)
        }
    }

    fun registerBlanketCobbleSpawnersCommand(dispatcher: CommandDispatcher<ServerCommandSource>) {
        val mainCommand = literal("blanketcobblespawners")
            // This isnt finished and is horrible, please done code like this
            .requires { source ->
                val player = source.player
                player != null && (
                        hasPermission(player, "BlanketCobbleSpawners.Base", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Edit", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.GUI", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Rename", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Addmon", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Removemon", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Killspawned", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.ToggleVisibility", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.ToggleRadius", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Teleport", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.List", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Reload", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.GiveSpawnerBlock", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Help", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Debug", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Debug.SpawnCustomPokemon", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Debug.LogSpeciesAndDex", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Debug.GivePokemonInspectWand", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Debug.CalculateMapEntryCount", 2)
                                || hasPermission(player, "BlanketCobbleSpawners.Debug.ListForms", 2)
                        )
            }
            .executes { context ->
                context.source.sendFeedback({ Text.literal("BlanketCobbleSpawners v1.0.0") }, false)
                1
            }
            // Edit command
            .then(
                literal("edit")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.Edit", 2)
                    }
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                val player = context.source.player as? ServerPlayerEntity

                                if (spawnerEntry == null) {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    return@executes 0
                                }

                                if (player != null) {
                                    SpawnerPokemonSelectionGui.openSpawnerGui(player, spawnerEntry.spawnerPos)
                                    context.source.sendFeedback({ Text.literal("GUI for spawner '$spawnerName' has been opened.") }, true)
                                    return@executes 1
                                } else {
                                    context.source.sendError(Text.literal("Only players can run this command."))
                                    return@executes 0
                                }
                            }
                    )
            )
            // Rename command
            .then(
                literal("rename")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.Rename", 2)
                    }
                    .then(
                        argument("currentName", word())
                            .suggests(spawnerNameSuggestions)
                            .then(
                                argument("newName", word())
                                    .executes { context ->
                                        val currentName = getString(context, "currentName")
                                        val newName = getString(context, "newName")
                                        val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == currentName }

                                        if (spawnerEntry == null) {
                                            context.source.sendError(Text.literal("Spawner '$currentName' not found."))
                                            return@executes 0
                                        }

                                        if (ConfigManager.spawners.values.any { it.spawnerName == newName }) {
                                            context.source.sendError(Text.literal("Spawner name '$newName' is already in use."))
                                            return@executes 0
                                        }

                                        spawnerEntry.spawnerName = newName
                                        ConfigManager.saveSpawnerData()

                                        context.source.sendFeedback(
                                            { Text.literal("Spawner renamed from '$currentName' to '$newName'.") },
                                            true
                                        )
                                        1
                                    }
                            )
                    )
            )
            // Addmon command
            .then(
                literal("addmon")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.Addmon", 2)
                    }
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .then(
                                argument("pokemonName", word())
                                    .suggests(pokemonNameSuggestions)
                                    .then(
                                        argument("formName", word())
                                            .suggests(formNameSuggestions)
                                            .executes { context ->
                                                val spawnerName = getString(context, "spawnerName")
                                                val pokemonName = getString(context, "pokemonName").lowercase()
                                                val formName = getString(context, "formName").lowercase()

                                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                                if (spawnerEntry == null) {
                                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                                    return@executes 0
                                                }

                                                val species = PokemonSpecies.getByName(pokemonName)
                                                if (species == null) {
                                                    context.source.sendError(Text.literal("Pokémon '$pokemonName' not found. Please check the spelling."))
                                                    return@executes 0
                                                }

                                                val selectedForm = when {
                                                    species.forms.isEmpty() -> "Normal"
                                                    species.forms.any { it.name.equals(formName, ignoreCase = true) } -> formName
                                                    formName.isBlank() || formName.equals("normal", ignoreCase = true) -> "Normal"
                                                    else -> {
                                                        context.source.sendError(Text.literal("Form '$formName' does not exist for Pokémon '$pokemonName'. Defaulting to 'Normal'."))
                                                        "Normal"
                                                    }
                                                }

                                                val newEntry = PokemonSpawnEntry(
                                                    pokemonName = pokemonName,
                                                    formName = selectedForm,
                                                    spawnChance = 50.0,
                                                    shinyChance = 0.0,
                                                    minLevel = 1,
                                                    maxLevel = 100,
                                                    captureSettings = CaptureSettings(
                                                        isCatchable = true,
                                                        restrictCaptureToLimitedBalls = true,
                                                        requiredPokeBalls = listOf("safari_ball")
                                                    ),
                                                    ivSettings = IVSettings(
                                                        allowCustomIvs = false,
                                                        minIVHp = 0,
                                                        maxIVHp = 31,
                                                        minIVAttack = 0,
                                                        maxIVAttack = 31,
                                                        minIVDefense = 0,
                                                        maxIVDefense = 31,
                                                        minIVSpecialAttack = 0,
                                                        maxIVSpecialAttack = 31,
                                                        minIVSpecialDefense = 0,
                                                        maxIVSpecialDefense = 31,
                                                        minIVSpeed = 0,
                                                        maxIVSpeed = 31
                                                    ),
                                                    evSettings = EVSettings(
                                                        allowCustomEvsOnDefeat = false,
                                                        evHp = 0,
                                                        evAttack = 0,
                                                        evDefense = 0,
                                                        evSpecialAttack = 0,
                                                        evSpecialDefense = 0,
                                                        evSpeed = 0
                                                    ),
                                                    spawnSettings = SpawnSettings(
                                                        spawnTime = "ALL",
                                                        spawnWeather = "ALL"
                                                    )
                                                )

                                                if (ConfigManager.addPokemonSpawnEntry(spawnerEntry.spawnerPos, newEntry)) {
                                                    context.source.sendFeedback(
                                                        { Text.literal("Added Pokémon '$pokemonName' with form '$selectedForm' to spawner '$spawnerName'.") },
                                                        true
                                                    )
                                                    1
                                                } else {
                                                    context.source.sendError(Text.literal("Failed to add Pokémon '$pokemonName' to spawner '$spawnerName'."))
                                                    0
                                                }
                                            }
                                    )
                                    .executes { context ->
                                        val spawnerName = getString(context, "spawnerName")
                                        val pokemonName = getString(context, "pokemonName").lowercase()

                                        val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                        if (spawnerEntry == null) {
                                            context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                            return@executes 0
                                        }

                                        val species = PokemonSpecies.getByName(pokemonName)
                                        if (species == null) {
                                            context.source.sendError(Text.literal("Pokémon '$pokemonName' not found. Please check the spelling."))
                                            return@executes 0
                                        }

                                        val selectedForm = if (species.forms.isEmpty()) "Normal" else "default"

                                        val newEntry = PokemonSpawnEntry(
                                            pokemonName = pokemonName,
                                            formName = selectedForm,
                                            spawnChance = 50.0,
                                            shinyChance = 0.0,
                                            minLevel = 1,
                                            maxLevel = 100,
                                            captureSettings = CaptureSettings(
                                                isCatchable = true,
                                                restrictCaptureToLimitedBalls = true,
                                                requiredPokeBalls = listOf("safari_ball")
                                            ),
                                            ivSettings = IVSettings(
                                                allowCustomIvs = false,
                                                minIVHp = 0,
                                                maxIVHp = 31,
                                                minIVAttack = 0,
                                                maxIVAttack = 31,
                                                minIVDefense = 0,
                                                maxIVDefense = 31,
                                                minIVSpecialAttack = 0,
                                                maxIVSpecialAttack = 31,
                                                minIVSpecialDefense = 0,
                                                maxIVSpecialDefense = 0,
                                                minIVSpeed = 0,
                                                maxIVSpeed = 31
                                            ),
                                            evSettings = EVSettings(
                                                allowCustomEvsOnDefeat = false,
                                                evHp = 0,
                                                evAttack = 0,
                                                evDefense = 0,
                                                evSpecialAttack = 0,
                                                evSpecialDefense = 0,
                                                evSpeed = 0
                                            ),
                                            spawnSettings = SpawnSettings(
                                                spawnTime = "ALL",
                                                spawnWeather = "ALL"
                                            )
                                        )

                                        if (ConfigManager.addPokemonSpawnEntry(spawnerEntry.spawnerPos, newEntry)) {
                                            context.source.sendFeedback(
                                                { Text.literal("Added Pokémon '$pokemonName' to spawner '$spawnerName' with form '$selectedForm'.") },
                                                true
                                            )
                                            1
                                        } else {
                                            context.source.sendError(Text.literal("Failed to add Pokémon '$pokemonName' to spawner '$spawnerName'."))
                                            0
                                        }
                                    }
                            )
                    )
            )
            // Removemon command
            .then(
                literal("removemon")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.Removemon", 2)
                    }
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .then(
                                argument("pokemonName", word())
                                    .suggests { context, builder ->
                                        val spawnerName = getString(context, "spawnerName")
                                        val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                        spawnerEntry?.selectedPokemon?.forEach { entry ->
                                            if (entry.pokemonName.startsWith(builder.remainingLowerCase)) {
                                                builder.suggest(entry.pokemonName)
                                            }
                                        }
                                        builder.buildFuture()
                                    }
                                    .then(
                                        argument("formName", word())
                                            .suggests(formNameSuggestions)
                                            .executes { context ->
                                                val spawnerName = getString(context, "spawnerName")
                                                val pokemonName = getString(context, "pokemonName")
                                                val formName = getString(context, "formName")

                                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                                if (spawnerEntry == null) {
                                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                                    return@executes 0
                                                }

                                                val existingEntry = spawnerEntry.selectedPokemon.find {
                                                    it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                                                            it.formName.equals(formName, ignoreCase = true)
                                                }

                                                if (existingEntry != null) {
                                                    if (ConfigManager.removePokemonSpawnEntry(spawnerEntry.spawnerPos, pokemonName, formName)) {
                                                        context.source.sendFeedback(
                                                            { Text.literal("Removed Pokémon '$pokemonName' with form '$formName' from spawner '$spawnerName'.") },
                                                            true
                                                        )
                                                        1
                                                    } else {
                                                        context.source.sendError(Text.literal("Failed to remove Pokémon '$pokemonName' from spawner '$spawnerName'."))
                                                        0
                                                    }
                                                } else {
                                                    context.source.sendError(Text.literal("Pokémon '$pokemonName' with form '$formName' is not selected for spawner '$spawnerName'."))
                                                    0
                                                }
                                            }
                                    )
                                    .executes { context ->
                                        val spawnerName = getString(context, "spawnerName")
                                        val pokemonName = getString(context, "pokemonName")

                                        val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                        if (spawnerEntry == null) {
                                            context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                            return@executes 0
                                        }

                                        val matchingEntries = spawnerEntry.selectedPokemon.filter { it.pokemonName.equals(pokemonName, ignoreCase = true) }

                                        when {
                                            matchingEntries.isEmpty() -> {
                                                context.source.sendError(Text.literal("Pokémon '$pokemonName' is not selected for spawner '$spawnerName'."))
                                                0
                                            }
                                            matchingEntries.size == 1 -> {
                                                val entry = matchingEntries.first()
                                                if (ConfigManager.removePokemonSpawnEntry(spawnerEntry.spawnerPos, pokemonName, entry.formName)) {
                                                    context.source.sendFeedback(
                                                        { Text.literal("Removed Pokémon '$pokemonName' with form '${entry.formName}' from spawner '$spawnerName'.") },
                                                        true
                                                    )
                                                    1
                                                } else {
                                                    context.source.sendError(Text.literal("Failed to remove Pokémon '$pokemonName' from spawner '$spawnerName'."))
                                                    0
                                                }
                                            }
                                            else -> {
                                                context.source.sendError(Text.literal("Multiple forms found for Pokémon '$pokemonName'. Please specify a form name."))
                                                0
                                            }
                                        }
                                    }
                            )
                    )
            )
            // Killspawned command
            .then(
                literal("killspawned")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.Killspawned", 2)
                    }
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                if (spawnerEntry == null) {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    return@executes 0
                                }
                                val spawnerPos = spawnerEntry.spawnerPos
                                val server = context.source.server
                                val registryKey = parseDimension(spawnerEntry.dimension)
                                val serverWorld = server.getWorld(registryKey)
                                if (serverWorld == null) {
                                    context.source.sendError(Text.literal("World '${registryKey.value}' not found for spawner '$spawnerName'."))
                                    return@executes 0
                                }
                                val uuids = SpawnerUUIDManager.getUUIDsForSpawner(spawnerPos)
                                uuids.forEach { uuid ->
                                    val entity = serverWorld.getEntity(uuid)
                                    if (entity is PokemonEntity) {
                                        entity.discard()
                                        SpawnerUUIDManager.removePokemon(uuid)
                                        logDebug("Despawned Pokémon with UUID $uuid from spawner at $spawnerPos")
                                    }
                                }
                                context.source.sendFeedback(
                                    { Text.literal("All Pokémon spawned by spawner '$spawnerName' have been removed.") },
                                    true
                                )
                                1
                            }
                    )
            )
            // Togglevisibility command
            .then(
                literal("togglevisibility")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.ToggleVisibility", 2)
                    }
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerData = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }

                                if (spawnerData != null) {
                                    val success = toggleSpawnerVisibility(context.source.server, spawnerData.spawnerPos)
                                    if (success) {
                                        context.source.sendFeedback(
                                            { Text.literal("Spawner '$spawnerName' visibility has been toggled.") },
                                            true
                                        )
                                        1
                                    } else {
                                        context.source.sendError(Text.literal("Failed to toggle visibility for spawner '$spawnerName'."))
                                        0
                                    }
                                } else {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    0
                                }
                            }
                    )
            )
            // Toggleradius command
            .then(
                literal("toggleradius")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.ToggleRadius", 2)
                    }
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                val player = context.source.player as? ServerPlayerEntity

                                if (spawnerEntry == null) {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    return@executes 0
                                }

                                if (player != null) {
                                    toggleVisualization(player, spawnerEntry)
                                    context.source.sendFeedback(
                                        { Text.literal("Spawn radius visualization toggled for spawner '$spawnerName'.") },
                                        true
                                    )
                                    1
                                } else {
                                    context.source.sendError(Text.literal("Only players can run this command."))
                                    0
                                }
                            }
                    )
            )
            // Teleport command
            .then(
                literal("teleport")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.Teleport", 2)
                    }
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                val player = context.source.player as? ServerPlayerEntity

                                if (spawnerEntry == null) {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    return@executes 0
                                }

                                if (player != null) {
                                    val spawnerPos = spawnerEntry.spawnerPos
                                    val dimension = parseDimension(spawnerEntry.dimension)
                                    val world = context.source.server.getWorld(dimension)
                                    if (world != null) {
                                        player.teleport(world, spawnerPos.x.toDouble(), spawnerPos.y.toDouble(), spawnerPos.z.toDouble(), player.yaw, player.pitch)
                                        context.source.sendFeedback({ Text.literal("Teleported to spawner '$spawnerName'.") }, true)
                                        1
                                    } else {
                                        context.source.sendError(Text.literal("World '${spawnerEntry.dimension}' not found."))
                                        0
                                    }
                                } else {
                                    context.source.sendError(Text.literal("Only players can run this command."))
                                    0
                                }
                            }
                    )
            )
            // List command
            .then(
                literal("list")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.List", 2)
                    }
                    .executes { context ->
                        val player = context.source.player as? ServerPlayerEntity ?: return@executes 0
                        val spawnerList = ConfigManager.spawners.map { (pos, data) ->
                            "${data.spawnerName}: ${pos.x}, ${pos.y}, ${pos.z} (${data.dimension})"
                        }

                        if (spawnerList.isEmpty()) {
                            player.sendMessage(Text.literal("No spawners found."), false)
                        } else {
                            player.sendMessage(Text.literal("Spawners:\n${spawnerList.joinToString("\n")}"), false)
                        }
                        1
                    }
            )
            // GUI command
            .then(
                literal("gui")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.GUI", 2)
                    }
                    .executes { context ->
                        val player = context.source.player as? ServerPlayerEntity ?: return@executes 0
                        SpawnerListGui.openSpawnerListGui(player)
                        context.source.sendFeedback({ Text.literal("Spawner GUI has been opened.") }, true)
                        1
                    }
            )
            // Reload command
            .then(
                literal("reload")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.Reload", 2)
                    }
                    .executes { context ->
                        ConfigManager.reloadSpawnerData()
                        context.source.sendFeedback(
                            { Text.literal("Configuration for BlanketCobbleSpawners has been successfully reloaded.") },
                            true
                        )
                        logDebug("Configuration reloaded for BlanketCobbleSpawners.")
                        1
                    }
            )
            // Give command
            // Give command
            .then(
                literal("givespawnerblock")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.GiveSpawnerBlock", 2)
                    }
                    .executes { context ->
                        (context.source.player as? ServerPlayerEntity)?.let { player ->
                            val customSpawnerItem = ItemStack(Items.SPAWNER).apply {
                                count = 1

                                // Set custom model data
                                val customModelDataComponent = CustomModelDataComponent(16666) // Assuming constructor works
                                set(DataComponentTypes.CUSTOM_MODEL_DATA, customModelDataComponent)

                                // Set the custom name using DataComponents
                                set(DataComponentTypes.ITEM_NAME, Text.literal("Custom Cobble Spawner").styled { style ->
                                    style.withColor(0xFFD700).withItalic(false)
                                })

                                // Set the lore using DataComponents
                                val loreLines = listOf(
                                    Text.literal("A special spawner.").styled { s -> s.withColor(0x808080).withItalic(true) },
                                    Text.literal("Used to spawn cobble-based entities.").styled { s -> s.withColor(0xA9A9A9).withItalic(false) }
                                )
                                set(DataComponentTypes.LORE, LoreComponent(loreLines))
                            }

                            // Add the item to the player's inventory
                            if (player.inventory.insertStack(customSpawnerItem)) {
                                player.sendMessage(Text.literal("A custom spawner has been added to your inventory."), false)
                                logDebug("Custom spawner given to player ${player.name.string}.")
                            } else {
                                player.sendMessage(Text.literal("Inventory full. Couldn't add the custom spawner."), false)
                            }
                        }
                        1
                    }
            )

            // Help command
            .then(
                literal("help")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.Help", 2)
                    }
                    .executes { context ->
                        val helpText = Text.literal("**BlanketCobbleSpawners Commands:**\n").styled { it.withColor(0xFFFFFF) }
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners reload").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Reloads the spawner configuration.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners givespawnerblock").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Gives a custom spawner to the player.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners list").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Lists all spawners with their coordinates.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners gui").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Opens the GUI listing all spawners.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners edit <spawnerName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Opens the GUI to edit the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners rename <currentName> <newName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Renames the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners addmon <spawnerName> <pokemonName> [formName]").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Adds a Pokémon to the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners removemon <spawnerName> <pokemonName> [formName]").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Removes a Pokémon from the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners killspawned <spawnerName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Removes all Pokémon spawned by the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners togglevisibility <spawnerName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Toggles the visibility of the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners toggleradius <spawnerName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Toggles spawn radius visualization for the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners teleport <spawnerName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Teleports you to the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )

                        val player = context.source.player as? ServerPlayerEntity
                        if (player != null) {
                            player.sendMessage(helpText, false)
                            1
                        } else {
                            context.source.sendError(Text.literal("Only players can run this command."))
                            0
                        }
                    }
            )
            // Debug commands group
            .then(
                literal("debug")
                    .requires { source ->
                        val player = source.player
                        player != null && hasPermission(player, "BlanketCobbleSpawners.Debug", 2)
                    }
                    .then(
                        literal("spawn-custom-pokemon")
                            .requires { source ->
                                val player = source.player
                                player != null && hasPermission(player, "BlanketCobbleSpawners.Debug.SpawnCustomPokemon", 2)
                            }
                            .then(
                                argument("pokemonName", string())
                                    .suggests(pokemonNameSuggestions)
                                    .then(
                                        argument("formName", string())
                                            .suggests(formNameSuggestions)
                                            .executes { context ->
                                                val pokemonName = getString(context, "pokemonName").lowercase()
                                                val formName = getString(context, "formName").lowercase()
                                                executeSpawnCustom(context, pokemonName, formName)
                                            }
                                    )
                                    .executes { context ->
                                        val pokemonName = getString(context, "pokemonName").lowercase()
                                        executeSpawnCustom(context, pokemonName, null)
                                    }
                            )
                    )
                    .then(
                        literal("log-pokemon-species-and-dex")
                            .requires { source ->
                                val player = source.player
                                player != null && hasPermission(player, "BlanketCobbleSpawners.Debug.LogSpeciesAndDex", 2)
                            }
                            .executes { context ->
                                val speciesList = PokemonSpecies.species
                                if (speciesList.isNotEmpty()) {
                                    speciesList.forEach { species ->
                                        logger.info("Species: ${species.name}, Dex Number: ${species.nationalPokedexNumber}")
                                    }
                                    context.source.sendFeedback(
                                        { Text.literal("Logged all Pokémon species and dex numbers to the console.") },
                                        true
                                    )
                                } else {
                                    context.source.sendError(Text.literal("No Pokémon species found."))
                                }
                                1
                            }
                    )
                    .then(
                        literal("givepokemoninspectwand")
                            .requires { source ->
                                val player = source.player
                                player != null && hasPermission(player, "BlanketCobbleSpawners.Debug.GivePokemonInspectWand", 2)
                            }
                            .executes { context ->
                                val player = context.source.player as? ServerPlayerEntity ?: return@executes 0
                                givePokemonInspectStick(player)
                                context.source.sendFeedback({ Text.literal("Given a Pokemon Inspect Wand!") }, true)
                                1
                            }
                    )
                    .then(
                        literal("calculateMapEntryCount")
                            .requires { source ->
                                val player = source.player
                                player != null && hasPermission(player, "BlanketCobbleSpawners.Debug.CalculateMapEntryCount", 2)
                            }
                            .executes { context ->
                                val memoryUsageMessage = calculateMapEntryCount()
                                context.source.sendFeedback(Supplier { Text.literal(memoryUsageMessage) }, false)
                                1
                            }
                    )
                    .then(
                        literal("listforms")
                            .requires { source ->
                                val player = source.player
                                player != null && hasPermission(player, "BlanketCobbleSpawners.Debug.ListForms", 2)
                            }
                            .then(
                                argument("pokemonName", word())
                                    .suggests(pokemonNameSuggestions)
                                    .executes { context ->
                                        val pokemonName = getString(context, "pokemonName").lowercase()

                                        val species = PokemonSpecies.getByName(pokemonName)

                                        if (species == null) {
                                            context.source.sendError(Text.literal("Pokémon '$pokemonName' not found."))
                                            return@executes 0
                                        }

                                        val formNames = species.forms.map { it.name ?: "Default" }

                                        if (formNames.isEmpty()) {
                                            context.source.sendFeedback({ Text.literal("No available forms for Pokémon '$pokemonName'.") }, false)
                                        } else {
                                            val formsList = formNames.joinToString(", ")
                                            context.source.sendFeedback({ Text.literal("Available forms for Pokémon '$pokemonName': $formsList") }, false)
                                        }

                                        1
                                    }
                            )
                    )
            )

        val builtMainCommand = dispatcher.register(mainCommand)

        val aliasSpawnerCommand1 = literal("cobblespawners").redirect(builtMainCommand)
        val aliasSpawnerCommand2 = literal("bcs").redirect(builtMainCommand)

        dispatcher.register(aliasSpawnerCommand1)
        dispatcher.register(aliasSpawnerCommand2)

        logDebug("Registering commands: /blanketcobblespawners, /cobblespawners, /bcs")
    }


    private fun calculateMapEntryCount(): String {
        val uuidMapCount = SpawnerUUIDManager.pokemonUUIDMap.size
        val visualizationMapCount = ParticleUtils.activeVisualizations.size
        val spawnerConfigCount = ConfigManager.spawners.size
        val cachedValidPositionsCount = BlanketCobbleSpawners.spawnerValidPositions.size
        val ongoingBattlesCount = BattleTracker().ongoingBattles.size
        val playerPagesCount = SpawnerPokemonSelectionGui.playerPages.size
        val spawnerGuisOpenCount = SpawnerPokemonSelectionGui.spawnerGuisOpen.size
        val selectedPokemonListCount = ConfigManager.spawners.values.sumOf { it.selectedPokemon.size }
        val speciesFormsListCount = SpawnerPokemonSelectionGui.getSortedSpeciesList(emptyList()).size
        val lastSpawnTicksCount = ConfigManager.lastSpawnTicks.size
        val spawnerValidPositionsCount = BlanketCobbleSpawners.spawnerValidPositions.size

        val totalEntries = uuidMapCount + visualizationMapCount + spawnerConfigCount +
                cachedValidPositionsCount + ongoingBattlesCount + playerPagesCount +
                spawnerGuisOpenCount + selectedPokemonListCount + speciesFormsListCount +
                lastSpawnTicksCount + spawnerValidPositionsCount

        return """
        |[BlanketCobbleSpawners Map Entry Counts]
        |UUID Manager Entry Count: $uuidMapCount
        |Active Visualizations Entry Count: $visualizationMapCount
        |Spawner Config Entry Count: $spawnerConfigCount
        |Cached Valid Positions Entry Count: $cachedValidPositionsCount
        |Ongoing Battles Entry Count: $ongoingBattlesCount
        |Player Pages Map Entry Count: $playerPagesCount
        |Spawner GUIs Open Entry Count: $spawnerGuisOpenCount
        |Selected Pokémon List Entry Count: $selectedPokemonListCount
        |Species Forms List Entry Count: $speciesFormsListCount
        |Last Spawn Ticks Map Entry Count: $lastSpawnTicksCount
        |Spawner Valid Positions Entry Count: $spawnerValidPositionsCount
        |Total Map Entries: $totalEntries
    """.trimMargin()
    }

    private val spawnerNameSuggestions: SuggestionProvider<ServerCommandSource> = SuggestionProvider { context, builder ->
        val spawnerNames = ConfigManager.spawners.values.map { it.spawnerName }
        spawnerNames.filter { it.startsWith(builder.remainingLowerCase) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    private val pokemonNameSuggestions: SuggestionProvider<ServerCommandSource> = SuggestionProvider { context, builder ->
        val input = builder.remaining.lowercase()
        PokemonSpecies.species
            .map { it.name }
            .filter { it.lowercase().startsWith(input) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    private val formNameSuggestions: SuggestionProvider<ServerCommandSource> = SuggestionProvider { context, builder ->
        val pokemonName = try {
            getString(context, "pokemonName").lowercase()
        } catch (e: IllegalArgumentException) {
            ""
        }

        val species = PokemonSpecies.getByName(pokemonName)
        if (species != null) {
            builder.suggest("Normal")
            species.forms.forEach { form ->
                val formName = form.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    ?: "Normal"
                if (formName != "Normal") {
                    builder.suggest(formName)
                }
            }
        } else {
            builder.suggest("Normal")
        }
        builder.buildFuture()
    }

    private fun givePokemonInspectStick(player: ServerPlayerEntity) {
        val inspectStick = ItemStack(Items.STICK).apply {
            count = 1
            // Set item name using DataComponents
            set(DataComponentTypes.ITEM_NAME, Text.literal("Pokemon Inspect Stick").styled { style ->
                style.withColor(0x00FF00).withItalic(false)
            })

            // Set item lore using DataComponents
            val loreLines = listOf(
                Text.literal("Use this to inspect Pokémon.").styled { s -> s.withColor(0x006400).withItalic(true) },
                Text.literal("Track your Pokémon interactions.").styled { s -> s.withColor(0x808080).withItalic(false) }
            )
            set(DataComponentTypes.LORE, LoreComponent(loreLines))
        }

        player.inventory.insertStack(inspectStick)
    }



    fun registerEntityClickEvent() {
        val playerLastInspectTime = ConcurrentHashMap<UUID, Long>()

        UseEntityCallback.EVENT.register { player, world, hand, entity, _ ->
            if (player is ServerPlayerEntity && entity is PokemonEntity && hand == Hand.MAIN_HAND) {
                val itemInHand = player.getStackInHand(hand)
                // Check CUSTOM_MODEL_DATA instead of NBT
                val modelData = itemInHand.getOrDefault(DataComponentTypes.CUSTOM_MODEL_DATA, 0)
                // If modelData == 2 means this is our Pokemon Inspect Wand
                if (itemInHand.item == Items.STICK && modelData == 2) {
                    val currentTime = System.currentTimeMillis()
                    val playerId = player.uuid

                    if (currentTime - (playerLastInspectTime[playerId] ?: 0) > 1000) {
                        playerLastInspectTime[playerId] = currentTime
                        inspectPokemon(player, entity)
                        return@register ActionResult.SUCCESS
                    }
                }
            }
            ActionResult.PASS
        }
    }


    private fun inspectPokemon(player: ServerPlayerEntity, pokemonEntity: PokemonEntity) {
        val pokemon = pokemonEntity.pokemon

        // UUID information and UUID Manager count
        val uuid = pokemonEntity.uuid
        val trackedUUIDInfo = SpawnerUUIDManager.getPokemonInfo(uuid)
        val totalUUIDsInManager = SpawnerUUIDManager.pokemonUUIDMap.size
        val spawnerInfo = SpawnerUUIDManager.getPokemonInfo(uuid)
        val isFromSpawner = spawnerInfo != null

        val speciesName = pokemon.species.name
        val form = pokemon.form.name ?: "Default"
        val isShiny = pokemon.shiny
        val catchRate = pokemon.species.catchRate
        val friendship = pokemon.friendship
        val state = pokemon.state.name
        val owner = pokemon.getOwnerPlayer()?.name?.string ?: "None"
        val experience = pokemon.experience
        val evolutions = pokemon.species.evolutions.joinToString(", ") { evolution ->
            evolution.result.species.toString()
        }
        val tradeable = pokemon.tradeable
        val gender = pokemon.gender.name
        val caughtBall = pokemon.caughtBall.name
        val currentHealth = pokemon.currentHealth
        val maxHealth = pokemon.hp

        // Additional attributes
        val allAccessibleMoves = pokemon.allAccessibleMoves.joinToString(", ") { it.displayName.toString() }
        val lastFlowerFed = pokemon.lastFlowerFed.toString() ?: "None"
        val originalTrainer = pokemon.originalTrainer?.toString() ?: "None"
        val scaleModifier = pokemon.scaleModifier

        // Entity-specific attributes
        val despawnCounter = pokemonEntity.despawnCounter
        val blocksTraveled = pokemonEntity.blocksTraveled
        val ticksLived = pokemonEntity.ticksLived
        val width = pokemonEntity.width
        val height = pokemonEntity.height
        val isBattling = pokemonEntity.isBattling
        val isBusy = pokemonEntity.isBusy
        val movementSpeed = pokemonEntity.movementSpeed

        // IVs, EVs, and Abilities
        val ivs = pokemon.ivs
        val evs = pokemon.evs
        val abilities = listOf(pokemon.ability) ?: emptyList()

        // Prepare the message to display
        val message = Text.literal("You clicked on Pokémon: ")
            .append(Text.literal(speciesName).styled { it.withColor(0x00FF00) })
            .append(Text.literal("\nUUID: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(uuid.toString()).styled { it.withColor(0xFFAA00) })

        // Add UUID Manager Information
        message.append(Text.literal("\nTracked UUIDs in Manager: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(totalUUIDsInManager.toString()).styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nForm: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(form).styled { it.withColor(0xFFFF55) })

        message.append(Text.literal("\nShiny: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(if (isShiny) "Yes" else "No").styled { it.withColor(if (isShiny) 0x55FF55 else 0xFF5555) })

        message.append(Text.literal("\nFriendship: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$friendship").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nState: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(state).styled { it.withColor(0xFFAA00) })


        message.append(Text.literal("\nOwner: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(owner).styled { it.withColor(0xFFAA00) })

        message.append(Text.literal("\nExperience: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$experience").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nEvolutions: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(evolutions.ifEmpty { "None" }).styled { it.withColor(0xFFFF55) })

        message.append(Text.literal("\nTradeable: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(if (tradeable) "Yes" else "No").styled { it.withColor(if (tradeable) 0x55FF55 else 0xFF5555) })

        message.append(Text.literal("\nGender: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(gender).styled { it.withColor(0xFFFF55) })

        message.append(Text.literal("\nCaught Ball: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(caughtBall.toString()).styled { it.withColor(0xFFAA00) })

        message.append(Text.literal("\nHealth: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$currentHealth/$maxHealth").styled { it.withColor(0xFF5555) })

        message.append(Text.literal("\nCatch Rate: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$catchRate").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nAll Accessible Moves: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(allAccessibleMoves).styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nLast Flower Fed: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(lastFlowerFed).styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nOriginal Trainer: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(originalTrainer).styled { it.withColor(0xFFAA00) })

        message.append(Text.literal("\nScale Modifier: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$scaleModifier").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nIVs: ").styled { it.withColor(0xAAAAAA) })
        Stats.PERMANENT.forEach { stat ->
            message.append(
                Text.literal("\n  ${stat.showdownId}: ").styled { it.withColor(0xAAAAAA) }
            ).append(
                Text.literal("${ivs[stat]}").styled { it.withColor(0x55FF55) }
            )
        }

        message.append(Text.literal("\nEVs: ").styled { it.withColor(0xAAAAAA) })
        Stats.PERMANENT.forEach { stat ->
            message.append(
                Text.literal("\n  ${stat.showdownId}: ").styled { it.withColor(0xAAAAAA) }
            ).append(
                Text.literal("${evs.get(stat)}").styled { it.withColor(0x55FF55) }
            )
        }

        message.append(Text.literal("\nAbilities: ").styled { it.withColor(0xAAAAAA) })
        abilities.forEach { ability ->
            message.append(
                Text.literal("\n  ${ability.displayName}").styled { it.withColor(0xFFAA00) }
            )
        }

        message.append(Text.literal("\nDespawn Counter: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$despawnCounter").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nBlocks Traveled: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$blocksTraveled").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nTicks Lived: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$ticksLived").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nWidth: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$width").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nHeight: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$height").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nIs Battling: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(if (isBattling) "Yes" else "No").styled { it.withColor(if (isBattling) 0x55FF55 else 0xFF5555) })

        message.append(Text.literal("\nIs Busy: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(if (isBusy) "Yes" else "No").styled { it.withColor(if (isBusy) 0x55FF55 else 0xFF5555) })

        message.append(Text.literal("\nMovement Speed: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$movementSpeed").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nSpawner Origin: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(if (isFromSpawner) "Yes" else "No").styled { it.withColor(if (isFromSpawner) 0x55FF55 else 0xFF5555) })

        // Send the formatted message to the player
        player.sendMessage(message, false)
    }

    private fun createSpawnerNbt(): NbtCompound {
        val nbt = NbtCompound()
        nbt.putString("CustomSpawner", "true")

        val displayTag = NbtCompound()
        displayTag.putString("Name", "{\"text\":\"Custom Cobble Spawner\",\"color\":\"gold\",\"italic\":false}")

        val loreList = NbtList().apply {
            add(NbtString.of("{\"text\":\"A special spawner.\",\"color\":\"gray\",\"italic\":true}"))
            add(NbtString.of("{\"text\":\"Used to spawn cobble-based entities.\",\"color\":\"dark_gray\",\"italic\":false}"))
        }

        displayTag.put("Lore", loreList)
        nbt.put("display", displayTag)

        if (nbt.contains("BlockEntityTag")) {
            nbt.remove("BlockEntityTag")
        }

        return nbt
    }

    fun toggleSpawnerVisibility(server: MinecraftServer, spawnerPos: BlockPos): Boolean {
        val spawnerData = ConfigManager.getSpawner(spawnerPos)
        if (spawnerData == null) {
            logger.error("Spawner at position $spawnerPos not found.")
            return false
        }

        spawnerData.visible = !spawnerData.visible

        val registryKey = parseDimension(spawnerData.dimension)
        val world = server.getWorld(registryKey) ?: run {
            logger.error("World '${spawnerData.dimension}' not found.")
            return false
        }

        return try {
            if (spawnerData.visible) {
                world.setBlockState(spawnerPos, Blocks.SPAWNER.defaultState)
                logDebug("Spawner at $spawnerPos is now visible.")
            } else {
                world.setBlockState(spawnerPos, Blocks.AIR.defaultState)
                logDebug("Spawner at $spawnerPos is now invisible.")
            }
            ConfigManager.saveSpawnerData()
            true
        } catch (e: Exception) {
            logger.error("Error toggling visibility for spawner at $spawnerPos: ${e.message}")
            false
        }
    }

    private fun executeSpawnCustom(context: CommandContext<ServerCommandSource>, pokemonName: String, formName: String?): Int {
        val source = context.source
        val world = source.world
        val pos = Vec3d(source.position.x, source.position.y, source.position.z)

        val species = PokemonSpecies.getByName(pokemonName)
        if (species == null) {
            source.sendError(Text.literal("Species '$pokemonName' not found."))
            return 0
        }

        val propertiesStringBuilder = StringBuilder(pokemonName)
        if (formName != null) {
            val form = species.forms.find { it.name?.lowercase() == formName }
            if (form == null) {
                source.sendError(Text.literal("Form '$formName' not found for species '$pokemonName'."))
                return 0
            }
            if (form.aspects.isNotEmpty()) {
                for (aspect in form.aspects) {
                    propertiesStringBuilder.append(" ").append("$aspect=true")
                }
            } else {
                propertiesStringBuilder.append(" form=${form.formOnlyShowdownId()}")
            }
        }

        val properties = PokemonProperties.parse(propertiesStringBuilder.toString())

        val pokemonEntity = properties.createEntity(world)
        pokemonEntity.refreshPositionAndAngles(pos.x, pos.y, pos.z, pokemonEntity.yaw, pokemonEntity.pitch)
        pokemonEntity.dataTracker.set(PokemonEntity.SPAWN_DIRECTION, pokemonEntity.random.nextFloat() * 360F)

        return if (world.spawnEntity(pokemonEntity)) {
            val displayName = pokemonEntity.displayName?.string
            source.sendFeedback(Supplier { Text.literal("Spawned $displayName!") }, true)
            1
        } else {
            source.sendError(Text.literal("Failed to spawn $pokemonName"))
            0
        }
    }

    fun hasPermission(player: ServerPlayerEntity, permission: String, level: Int): Boolean {
        return try {
            Permissions.check(player, permission, level)
        } catch (e: NoClassDefFoundError) {
            player.hasPermissionLevel(level)
        }
    }

}
