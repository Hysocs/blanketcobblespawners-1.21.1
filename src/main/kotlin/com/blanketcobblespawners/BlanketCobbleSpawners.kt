// File: BlanketCobbleSpawners.kt
package com.blanketcobblespawners

import com.blanketcobblespawners.utils.*
import com.blanketcobblespawners.utils.ConfigManager.logDebug
import com.blanketcobblespawners.utils.ParticleUtils.activeVisualizations
import com.blanketcobblespawners.utils.ParticleUtils.visualizationInterval
import com.blanketcobblespawners.utils.ParticleUtils.visualizeSpawnerPositions
import com.blanketcobblespawners.utils.gui.SpawnerPokemonSelectionGui
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.IVs
import com.cobblemon.mod.common.pokemon.Pokemon
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.block.Blocks
import net.minecraft.block.SlabBlock
import net.minecraft.block.StairsBlock
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.screen.ScreenHandlerType
import net.minecraft.screen.SimpleNamedScreenHandlerFactory
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.Identifier
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

object BlanketCobbleSpawners : ModInitializer {

	private val logger = LoggerFactory.getLogger("blanketcobblespawners")
	val random = Random.create()
	private val battleTracker = BattleTracker()
	private val catchingTracker = CatchingTracker()
	val spawnerValidPositions = ConcurrentHashMap<BlockPos, List<BlockPos>>()

	override fun onInitialize() {
		logger.info("Initializing BlanketCobbleSpawners")
		ConfigManager.loadSpawnerData()
		CommandRegistrar.registerCommands()
		CommandRegistrar.registerEntityClickEvent()
		battleTracker.registerEvents()
		catchingTracker.registerEvents()

		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			for (spawnerData in ConfigManager.spawners.values) {
				val randomOffset = random.nextBetween(0, 5).toLong()
				val firstWorld = server.overworld
				val lastSpawnTick = ConfigManager.getLastSpawnTick(spawnerData.spawnerPos)
				ConfigManager.updateLastSpawnTick(
					spawnerData.spawnerPos,
					firstWorld.time + randomOffset + lastSpawnTick
				)
			}
		}

		ServerLifecycleEvents.SERVER_STOPPING.register { server ->
			for (spawnerData in ConfigManager.spawners.values) {
				if (ConfigManager.configData.globalConfig.cullSpawnerPokemonOnServerStop) {
					val registryKey = parseDimension(spawnerData.dimension)
					val serverWorld = server.getWorld(registryKey)
					serverWorld?.let {
						SpawnerUUIDManager.getUUIDsForSpawner(spawnerData.spawnerPos).forEach { uuid ->
							val entity = serverWorld.getEntity(uuid)
							if (entity is PokemonEntity) {
								entity.discard()
								SpawnerUUIDManager.removePokemon(uuid)
								logDebug("Despawned Pokémon with UUID $uuid from spawner at ${spawnerData.spawnerPos}")
							}
						}
					}
				}
			}
		}

		ServerTickEvents.END_SERVER_TICK.register { server ->
			for (spawnerData in ConfigManager.spawners.values) {
				val registryKey = parseDimension(spawnerData.dimension)
				val serverWorld = server.getWorld(registryKey)
				if (serverWorld == null) {
					logger.error("World '$registryKey' not found for spawner at ${spawnerData.spawnerPos}")
					continue
				}
				val currentTick = serverWorld.time
				val lastSpawnTick = ConfigManager.getLastSpawnTick(spawnerData.spawnerPos)

				// Cleanup stale Pokémon entries for the spawner
				SpawnerUUIDManager.cleanupStaleEntriesForSpawner(serverWorld, spawnerData.spawnerPos)

				// Check if enough time has passed since last spawn
				if (currentTick - lastSpawnTick > spawnerData.spawnTimerTicks) {
					val currentCount = SpawnerUUIDManager.getUUIDsForSpawner(spawnerData.spawnerPos).size
					if (currentCount < spawnerData.spawnLimit) {
						logDebug("Spawning Pokémon at spawner '${spawnerData.spawnerName}'.")
						spawnPokemon(serverWorld, spawnerData)
						ConfigManager.updateLastSpawnTick(spawnerData.spawnerPos, currentTick)
					} else {
						logDebug("Spawn limit reached for spawner '${spawnerData.spawnerName}'. No spawn.")
						// Update lastSpawnTick even when spawn limit is reached
						ConfigManager.updateLastSpawnTick(spawnerData.spawnerPos, currentTick)
					}
				}

				// Visualization updates
				val playersToRemove = mutableListOf<UUID>()
				activeVisualizations.forEach { (playerUUID, pair) ->
					val player = server.playerManager.getPlayer(playerUUID) ?: run {
						playersToRemove.add(playerUUID)
						return@forEach
					}
					val (spawnerPos, lastTick) = pair
					val spawnerlocationData = ConfigManager.spawners[spawnerPos] ?: run {
						playersToRemove.add(playerUUID)
						return@forEach
					}
					if (currentTick - lastTick >= visualizationInterval) {
						visualizeSpawnerPositions(player, spawnerlocationData)
						activeVisualizations[playerUUID] = spawnerPos to currentTick
					}
				}
				playersToRemove.forEach { activeVisualizations.remove(it) }
			}
		}

		registerCallbacks()
	}

	private fun registerCallbacks() {
		registerUseBlockCallback()
		registerBlockBreakCallback()
	}

	private fun registerUseBlockCallback() {
		UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
			if (player is ServerPlayerEntity && hand == Hand.MAIN_HAND && hitResult is BlockHitResult) {
				val blockPos = hitResult.blockPos
				val blockState = world.getBlockState(blockPos)
				val itemInHand = player.getStackInHand(hand)

				// Log the item in hand and block state for debugging
				logDebug("UseBlockCallback triggered at $blockPos with block ${blockState.block} and item ${itemInHand.item}")

				// Check if the player is holding a custom spawner with CustomModelData = 1
				val modelData = itemInHand.get(DataComponentTypes.CUSTOM_MODEL_DATA)
				if (itemInHand.item == Items.SPAWNER && modelData != null && modelData.value == 16666) {
					val blockPosToPlace = hitResult.blockPos.offset(hitResult.side)
					val blockAtPlacement = world.getBlockState(blockPosToPlace)

					// Check if placement location is valid
					if (blockAtPlacement.isAir || blockAtPlacement.block.defaultState.isReplaceable) {
						logDebug("Attempting to place custom spawner at $blockPosToPlace")
						placeCustomSpawner(player, world, blockPosToPlace, itemInHand)
						return@register ActionResult.SUCCESS
					} else {
						logDebug("Cannot place custom spawner at $blockPosToPlace: block state is not replaceable")
					}
				}
			}
			ActionResult.PASS
		}

		UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
			if (player is ServerPlayerEntity && hand == Hand.MAIN_HAND && hitResult is BlockHitResult) {
				val blockPos = hitResult.blockPos
				val blockState = world.getBlockState(blockPos)

				// Check if the block is a spawner and exists in the ConfigManager
				if (blockState.block == Blocks.SPAWNER && ConfigManager.spawners.containsKey(blockPos)) {
					// Check player permission for editing spawners
					if (CommandRegistrar.hasPermission(player, "BlanketCobbleSpawners.Edit", 2)) {
						// Open the spawner GUI
						SpawnerPokemonSelectionGui.openSpawnerGui(player, blockPos)
						return@register ActionResult.SUCCESS
					} else {
						// Inform the player they lack permissions
						player.sendMessage(Text.literal("You don't have permission to manage this spawner."), false)
					}
				}
			}
			ActionResult.PASS
		}
	}





	private fun registerBlockBreakCallback() {
		PlayerBlockBreakEvents.BEFORE.register { world, player, blockPos, blockState, _ ->
			val serverPlayer = player as? ServerPlayerEntity ?: return@register true

			if (world is ServerWorld && blockState.block == Blocks.SPAWNER && ConfigManager.spawners.containsKey(blockPos)) {
				if (!CommandRegistrar.hasPermission(serverPlayer, "BlanketCobbleSpawners.break", 2)) {
					serverPlayer.sendMessage(Text.literal("You don't have permission to remove this spawner."), false)
					return@register false
				}

				if (ConfigManager.removeSpawner(blockPos)) {
					SpawnerUUIDManager.clearPokemonForSpawner(blockPos)
					spawnerValidPositions.remove(blockPos)
					serverPlayer.sendMessage(Text.literal("Custom spawner removed at $blockPos."), false)
					logDebug("Custom spawner removed at $blockPos.")
				}
			} else if (world is ServerWorld) {
				invalidatePositionsIfWithinRadius(world, blockPos)
			}
			true
		}
	}

	private fun invalidatePositionsIfWithinRadius(world: World, changedBlockPos: BlockPos) {
		for (spawnerPos in ConfigManager.spawners.keys) {
			val spawnerData = ConfigManager.spawners[spawnerPos] ?: continue
			val distanceSquared = spawnerPos.getSquaredDistance(changedBlockPos)
			val maxDistanceSquared = (spawnerData.spawnRadius.width * spawnerData.spawnRadius.width).toDouble()
			if (distanceSquared <= maxDistanceSquared) {
				spawnerValidPositions.remove(spawnerPos)
				logDebug("Invalidated cached spawn positions for spawner at $spawnerPos due to block change at $changedBlockPos")
			}
		}
	}

	private fun placeCustomSpawner(
		player: ServerPlayerEntity,
		world: World,
		pos: BlockPos,
		itemInHand: ItemStack
	) {
		if (!CommandRegistrar.hasPermission(player, "BlanketCobbleSpawners.Place", 2)) {
			player.sendMessage(Text.literal("You don't have permission to place a custom spawner."), false)
			return
		}

		if (ConfigManager.spawners.containsKey(pos)) {
			player.sendMessage(Text.literal("A spawner already exists at this location!"), false)
			return
		}

		// Log block position and item model data for debugging
		logDebug("Placing custom spawner at $pos with model data: ${itemInHand.getOrDefault(DataComponentTypes.CUSTOM_MODEL_DATA, 0)}")

		val blockState = world.getBlockState(pos)
		if (blockState.block == Blocks.WATER || blockState.block == Blocks.LAVA) {
			world.setBlockState(pos, Blocks.AIR.defaultState)
		}
		world.setBlockState(pos, Blocks.SPAWNER.defaultState)

		val spawnerName = "spawner_${ConfigManager.spawners.size + 1}"
		val dimensionString = "${world.registryKey.value.namespace}:${world.registryKey.value.path}"

		ConfigManager.spawners[pos] = SpawnerData(
			spawnerPos = pos,
			spawnerName = spawnerName,
			dimension = dimensionString
		)

		// Log spawner addition
		logDebug("Added custom spawner to ConfigManager: $spawnerName at $pos")

		spawnerValidPositions.remove(pos)
		ConfigManager.saveSpawnerData()
		player.sendMessage(Text.literal("Custom spawner '$spawnerName' placed at $pos!"), false)

		if (!player.abilities.creativeMode) {
			itemInHand.decrement(1)
		}
	}

	private fun applyCustomIVs(pokemon: Pokemon, ivSettings: IVSettings) {
		if (ivSettings.allowCustomIvs) {
			// Ensure IV settings are valid
			if (!validateIVSettings(ivSettings)) {
				logger.warn("Invalid IV settings for Pokémon '${pokemon.species.name}'. Skipping custom IV application.")
				return
			}

			// Set each IV individually within the specified ranges
			pokemon.setIV(Stats.HP, random.nextBetween(ivSettings.minIVHp, ivSettings.maxIVHp + 1))
			pokemon.setIV(Stats.ATTACK, random.nextBetween(ivSettings.minIVAttack, ivSettings.maxIVAttack + 1))
			pokemon.setIV(Stats.DEFENCE, random.nextBetween(ivSettings.minIVDefense, ivSettings.maxIVDefense + 1))
			pokemon.setIV(Stats.SPECIAL_ATTACK, random.nextBetween(ivSettings.minIVSpecialAttack, ivSettings.maxIVSpecialAttack + 1))
			pokemon.setIV(Stats.SPECIAL_DEFENCE, random.nextBetween(ivSettings.minIVSpecialDefense, ivSettings.maxIVSpecialDefense + 1))
			pokemon.setIV(Stats.SPEED, random.nextBetween(ivSettings.minIVSpeed, ivSettings.maxIVSpeed + 1))

			logDebug("Custom IVs applied to Pokémon '${pokemon.species.name}': ${pokemon.ivs}")
		}
	}

	private fun validateIVSettings(ivSettings: IVSettings): Boolean {
		return ivSettings.minIVHp <= ivSettings.maxIVHp &&
				ivSettings.minIVAttack <= ivSettings.maxIVAttack &&
				ivSettings.minIVDefense <= ivSettings.maxIVDefense &&
				ivSettings.minIVSpecialAttack <= ivSettings.maxIVSpecialAttack &&
				ivSettings.minIVSpecialDefense <= ivSettings.maxIVSpecialDefense &&
				ivSettings.minIVSpeed <= ivSettings.maxIVSpeed
	}


	private fun spawnPokemon(serverWorld: ServerWorld, spawnerData: SpawnerData) {
		if (SpawnerPokemonSelectionGui.isSpawnerGuiOpen(spawnerData.spawnerPos)) {
			logDebug("GUI is open for spawner at ${spawnerData.spawnerPos}. Skipping spawn.")
			return
		}

		val allValidPositions = spawnerValidPositions.getOrPut(spawnerData.spawnerPos) {
			val positions = computeValidSpawnPositions(serverWorld, spawnerData)
			positions.ifEmpty {
				val retryPositions = computeValidSpawnPositions(serverWorld, spawnerData)
				retryPositions.ifEmpty {
					logger.error("No valid spawn positions found for spawner at ${spawnerData.spawnerPos} after two attempts.")
					emptyList()
				}
			}
		}

		// ADDED
		if (allValidPositions.isEmpty()) {
			logDebug("No suitable spawn position found for spawner at ${spawnerData.spawnerPos}. Skipping spawn.")
			ConfigManager.updateLastSpawnTick(spawnerData.spawnerPos, serverWorld.time) // ADDED
			return
		}

		val eligiblePokemon = spawnerData.selectedPokemon.filter { entry ->
			val condition = checkBasicSpawnConditions(serverWorld, entry)
			if (condition != null) {
				logDebug("Spawn conditions not met for Pokémon '${entry.pokemonName}' at spawner '${spawnerData.spawnerName}': $condition. Skipping spawn.")
				false
			} else {
				true
			}
		}

		// ADDED
		if (eligiblePokemon.isEmpty()) {
			logDebug("No eligible Pokémon to spawn for spawner '${spawnerData.spawnerName}'.")
			ConfigManager.updateLastSpawnTick(spawnerData.spawnerPos, serverWorld.time) // ADDED
			return
		}

		val totalWeight = eligiblePokemon.sumOf { it.spawnChance }
		if (totalWeight <= 0) {
			logger.warn("Total spawn chance is zero or negative for spawner at ${spawnerData.spawnerPos}. Skipping spawn.")
			return
		}

		val currentSpawned = SpawnerUUIDManager.getUUIDsForSpawner(spawnerData.spawnerPos).size
		val maxSpawnable = spawnerData.spawnLimit - currentSpawned
		if (maxSpawnable <= 0) {
			logDebug("Spawn limit reached for spawner '${spawnerData.spawnerName}'. No Pokémon will be spawned.")
			return
		}

		val spawnAmount = min(spawnerData.spawnAmountPerSpawn, maxSpawnable)
		logDebug("Attempting to spawn $spawnAmount Pokémon(s) for spawner at ${spawnerData.spawnerPos}")
		val maxAttemptsPerSpawn = 5
		var totalAttempts = 0
		var spawnedCount = 0

		while (spawnedCount < spawnAmount && totalAttempts < spawnAmount * maxAttemptsPerSpawn) {
			val randomValue = random.nextDouble() * totalWeight
			var cumulativeWeight = 0.0
			var selectedPokemon: PokemonSpawnEntry? = null

			for (pokemonEntry in eligiblePokemon) {
				cumulativeWeight += pokemonEntry.spawnChance
				if (randomValue <= cumulativeWeight) {
					selectedPokemon = pokemonEntry
					break
				}
			}

			if (selectedPokemon == null) {
				logger.warn("No Pokémon selected for spawning at spawner at ${spawnerData.spawnerPos}")
				totalAttempts++
				continue
			}

			val validPositions = filterSpawnPositionsByLocation(
				serverWorld,
				allValidPositions,
				selectedPokemon.spawnSettings.spawnLocation
			)

			if (validPositions.isEmpty()) {
				logDebug("No valid positions found for spawn location type ${selectedPokemon.spawnSettings.spawnLocation}")
				totalAttempts++
				continue
			}

			val index = random.nextInt(validPositions.size)
			val spawnPos = validPositions[index]

			if (!serverWorld.isChunkLoaded(spawnPos)) {
				logDebug("Chunk not loaded at spawn position $spawnPos. Skipping spawn.")
				totalAttempts++
				continue
			}

			val entry = selectedPokemon
			val sanitizedPokemonName = entry.pokemonName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
			val species = PokemonSpecies.getByName(sanitizedPokemonName)

			if (species == null) {
				logger.warn("Species '$sanitizedPokemonName' not found for spawner at ${spawnerData.spawnerPos}")
				totalAttempts++
				continue
			}

			val level = entry.minLevel + random.nextInt(entry.maxLevel - entry.minLevel + 1)
			val isShiny = random.nextDouble() * 100 <= entry.shinyChance

			val propertiesStringBuilder = StringBuilder(sanitizedPokemonName)
			propertiesStringBuilder.append(" level=$level")
			if (isShiny) {
				propertiesStringBuilder.append(" shiny=true")
			}

			if (
				!entry.formName.isNullOrEmpty() &&
				!entry.formName.equals("normal", ignoreCase = true) &&
				!entry.formName.equals("default", ignoreCase = true)
			) {
				val normalizedEntryFormName = entry.formName!!
					.lowercase()
					.replace(Regex("[^a-z0-9]"), "")
				val availableForms = species.forms
				val matchedForm = availableForms.find { form ->
					val normalizedFormId = form.formOnlyShowdownId()
						.lowercase()
						.replace(Regex("[^a-z0-9]"), "")
					normalizedFormId == normalizedEntryFormName
				} ?: run {
					logger.warn("Form '${entry.formName}' not found for species '${species.name}'. Defaulting to normal form.")
					null
				}
				if (matchedForm != null) {
					if (matchedForm.aspects.isNotEmpty()) {
						for (aspect in matchedForm.aspects) {
							propertiesStringBuilder.append(" ").append("$aspect=true")
						}
					} else {
						propertiesStringBuilder.append(" form=${matchedForm.formOnlyShowdownId()}")
					}
				}
			}

			val properties = com.cobblemon.mod.common.api.pokemon.PokemonProperties.parse(propertiesStringBuilder.toString())
			val pokemonEntity = properties.createEntity(serverWorld)
			val pokemon = pokemonEntity.pokemon

			// Apply custom IVs if allowed
			applyCustomIVs(pokemon, selectedPokemon.ivSettings)

			// Apply custom size if enabled in spawn settings
			if (entry.sizeSettings.allowCustomSize) {
				val randomSize = random.nextFloat() * (entry.sizeSettings.maxSize - entry.sizeSettings.minSize) +
						entry.sizeSettings.minSize
				pokemon.scaleModifier = randomSize
			}

			pokemonEntity.refreshPositionAndAngles(
				spawnPos.x + 0.5,
				spawnPos.y.toDouble(),
				spawnPos.z + 0.5,
				pokemonEntity.yaw,
				pokemonEntity.pitch
			)

			if (serverWorld.spawnEntity(pokemonEntity)) {
				SpawnerUUIDManager.addPokemon(pokemonEntity.uuid, spawnerData.spawnerPos, entry.pokemonName)
				logDebug("Pokémon '${species.name}' spawned with UUID ${pokemonEntity.uuid}")
				spawnedCount++
			} else {
				logger.warn("Failed to spawn Pokémon '${species.name}' at position $spawnPos")
			}
			totalAttempts++
		}

		if (spawnedCount > 0) {
			logDebug("Spawned $spawnedCount Pokémon(s) for spawner at ${spawnerData.spawnerPos}")
		} else {
			logDebug("No Pokémon were spawned for spawner at ${spawnerData.spawnerPos}")
		}
	}


	private fun checkBasicSpawnConditions(world: ServerWorld, entry: PokemonSpawnEntry): String? {
		// Time check
		when (entry.spawnSettings.spawnTime.uppercase()) {
			"DAY" -> {
				val timeOfDay = world.timeOfDay % 24000
				if (timeOfDay < 0 || timeOfDay > 12000) {
					return "Not daytime"
				}
			}
			"NIGHT" -> {
				val timeOfDay = world.timeOfDay % 24000
				if (timeOfDay >= 0 && timeOfDay <= 12000) {
					return "Not nighttime"
				}
			}
			"ALL" -> {} // No time restriction
			else -> logger.warn("Invalid spawn time ${entry.spawnSettings.spawnTime} for ${entry.pokemonName}")
		}

		// Weather check
		when (entry.spawnSettings.spawnWeather.uppercase()) {
			"CLEAR" -> {
				if (world.isRaining) {
					return "Not clear weather"
				}
			}
			"RAIN" -> {
				if (!world.isRaining || world.isThundering) {
					return "Not raining"
				}
			}
			"THUNDER" -> {
				if (!world.isThundering) {
					return "Not thundering"
				}
			}
			"ALL" -> {} // No weather restriction
			else -> logger.warn("Invalid weather condition ${entry.spawnSettings.spawnWeather} for ${entry.pokemonName}")
		}

		return null // All conditions met
	}

	fun computeValidSpawnPositions(serverWorld: ServerWorld, spawnerData: SpawnerData): List<BlockPos> {
		val validPositions = mutableListOf<BlockPos>()
		val spawnRadiusWidth = spawnerData.spawnRadius.width
		val spawnRadiusHeight = spawnerData.spawnRadius.height
		for (offsetX in -spawnRadiusWidth..spawnRadiusWidth) {
			for (offsetY in -spawnRadiusHeight..spawnRadiusHeight) {
				for (offsetZ in -spawnRadiusWidth..spawnRadiusWidth) {
					val potentialPos = spawnerData.spawnerPos.add(offsetX, offsetY, offsetZ)
					if (isPositionSafeForSpawn(serverWorld, potentialPos)) {
						validPositions.add(potentialPos)
					}
				}
			}
		}
		logDebug("Computed ${validPositions.size} valid spawn positions for spawner at ${spawnerData.spawnerPos}")
		return validPositions
	}

	private fun filterSpawnPositionsByLocation(world: ServerWorld, positions: List<BlockPos>, spawnLocation: String): List<BlockPos> {
		return when (spawnLocation.uppercase()) {
			"SURFACE" -> positions.filter { hasDirectSkyAccess(world, it) }
			"UNDERGROUND" -> positions.filter { !hasDirectSkyAccess(world, it) && !isUnderWater(world, it) }
			"WATER" -> positions.filter { isUnderWater(world, it) }
			else -> positions // "ALL" case - no filtering needed
		}
	}
	private fun isUnderWater(world: ServerWorld, pos: BlockPos): Boolean {
		val blockState = world.getBlockState(pos)
		return blockState.block == Blocks.WATER
	}

	private fun hasDirectSkyAccess(world: ServerWorld, pos: BlockPos): Boolean {
		var currentPos = pos.up()
		while (currentPos.y < world.topY) {
			val state = world.getBlockState(currentPos)
			// Skip if the block is leaves
			if (!state.isAir && !state.block.toString().lowercase().contains("leaves")) {
				return false
			}
			currentPos = currentPos.up()
		}
		return true
	}
	private fun isPositionSafeForSpawn(world: World, spawnPos: BlockPos): Boolean {
		val blockBelowPos = spawnPos.down()
		val blockBelowState = world.getBlockState(blockBelowPos)
		val blockBelow = blockBelowState.block
		val collisionShape = blockBelowState.getCollisionShape(world, blockBelowPos)
		if (collisionShape.isEmpty) {
			return false
		}
		val boundingBox = collisionShape.boundingBox
		val maxY = boundingBox.maxY
		val isSolidEnough = maxY >= 0.9
		if (!blockBelowState.isSideSolidFullSquare(world, blockBelowPos, Direction.UP) &&
			blockBelow !is SlabBlock && blockBelow !is StairsBlock && !isSolidEnough
		) {
			return false
		}
		val blockAtPos = world.getBlockState(spawnPos)
		if (!blockAtPos.isAir && !blockAtPos.getCollisionShape(world, spawnPos).isEmpty) {
			return false
		}
		val blockAbovePos = world.getBlockState(spawnPos.up())
		if (!blockAbovePos.isAir && !blockAbovePos.getCollisionShape(world, spawnPos.up()).isEmpty) {
			return false
		}
		return true
	}

	fun parseDimension(dimensionString: String): RegistryKey<World> {
		val parts = dimensionString.split(":")
		if (parts.size != 2) {
			logger.warn("Invalid dimension format: $dimensionString. Expected 'namespace:path'")
			// Instead of using a constructor, use 'of' or 'tryParse':
			return RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"))
		}
		val namespace = parts[0]
		val path = parts[1]
		val dimensionId = Identifier.of(namespace, path) // Use 'of' here instead of constructor
		return RegistryKey.of(RegistryKeys.WORLD, dimensionId)
	}


}
