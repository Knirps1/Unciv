package com.unciv.logic.map.mapgenerator

import com.unciv.logic.map.HexMath
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapType
import com.unciv.logic.map.Perlin
import com.unciv.logic.map.TileInfo
import com.unciv.logic.map.TileMap
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.TerrainType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MapLandmassGenerator(val ruleset: Ruleset, val randomness: MapGenerationRandomness) {
    //region _Fields
    private val landTerrainName = getInitializationTerrain(ruleset, TerrainType.Land)
    private val waterTerrainName: String = try {
        getInitializationTerrain(ruleset, TerrainType.Water)
    } catch (_: Exception) {
        landTerrainName
    }
    private val landOnlyMod: Boolean = waterTerrainName == landTerrainName
    private var waterThreshold = 0.0
    //endregion

    companion object {
        // this is called from TileMap constructors as well
        internal fun getInitializationTerrain(ruleset: Ruleset, type: TerrainType) =
            ruleset.terrains.values.firstOrNull { it.type == type }?.name
                ?: throw Exception("Cannot create map - no $type terrains found!")
    }

    fun generateLand(tileMap: TileMap) {
        // This is to accommodate land-only mods
        if (landOnlyMod) {
            for (tile in tileMap.values)
                tile.baseTerrain = landTerrainName
            return
        }

        waterThreshold = tileMap.mapParameters.waterThreshold.toDouble()

        when (tileMap.mapParameters.type) {
            MapType.pangaea -> createPangaea(tileMap)
            MapType.innerSea -> createInnerSea(tileMap)
            MapType.continentAndIslands -> createContinentAndIslands(tileMap)
            MapType.twoContinents -> createTwoContinents(tileMap)
            MapType.threeContinents -> createThreeContinents(tileMap)
            MapType.fourCorners -> createFourCorners(tileMap)
            MapType.archipelago -> createArchipelago(tileMap)
            MapType.default -> createPerlin(tileMap)
        }

        if (tileMap.mapParameters.shape === MapShape.flatEarth) {
            generateFlatEarthExtraWater(tileMap)
        }
    }

    private fun generateFlatEarthExtraWater(tileMap: TileMap) {
        for (tile in tileMap.values) {
            val isCenterTile = tile.latitude == 0f && tile.longitude == 0f
            val isEdgeTile = tile.neighbors.count() < 6

            if (!isCenterTile && !isEdgeTile) continue

            /*
            Flat Earth needs a 3 tile wide water perimeter and a 4 tile radius water center.
            This helps map generators to not place important things there which would be destroyed
            when the ice walls are placed there.
            */
            tile.baseTerrain = waterTerrainName
            for (neighbor in tile.neighbors) {
                neighbor.baseTerrain = waterTerrainName
                for (neighbor2 in neighbor.neighbors) {
                    neighbor2.baseTerrain = waterTerrainName
                    if (!isCenterTile) continue
                    for (neighbor3 in neighbor2.neighbors) {
                        neighbor3.baseTerrain = waterTerrainName
                    }
                }
            }
        }
    }

    private fun spawnLandOrWater(tile: TileInfo, elevation: Double) {
        tile.baseTerrain = if (elevation < waterThreshold) waterTerrainName else landTerrainName
    }

    private fun createPerlin(tileMap: TileMap) {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            val elevation = randomness.getPerlinNoise(tile, elevationSeed)
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createArchipelago(tileMap: TileMap) {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        waterThreshold += 0.25
        for (tile in tileMap.values) {
            val elevation = getRidgedPerlinNoise(tile, elevationSeed)
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createPangaea(tileMap: TileMap) {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            var elevation = randomness.getPerlinNoise(tile, elevationSeed)
            elevation = elevation*(3/4f) + getEllipticContinent(tile, tileMap) / 4
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createInnerSea(tileMap: TileMap) {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            var elevation = randomness.getPerlinNoise(tile, elevationSeed)
            elevation -= getEllipticContinent(tile, tileMap, 0.6) * 0.3
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createContinentAndIslands(tileMap: TileMap) {
        val isNorth = randomness.RNG.nextDouble() < 0.5
        val isLatitude =
                if (tileMap.mapParameters.shape === MapShape.hexagonal || tileMap.mapParameters.shape === MapShape.flatEarth) randomness.RNG.nextDouble() > 0.5f
                else if (tileMap.mapParameters.mapSize.height > tileMap.mapParameters.mapSize.width) true
                else if (tileMap.mapParameters.mapSize.width > tileMap.mapParameters.mapSize.height) false
                else randomness.RNG.nextDouble() > 0.5f

        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            var elevation = randomness.getPerlinNoise(tile, elevationSeed)
            elevation = (elevation + getContinentAndIslandsTransform(tile, tileMap, isNorth, isLatitude)) / 2.0
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createTwoContinents(tileMap: TileMap) {
        val isLatitude =
                if (tileMap.mapParameters.shape === MapShape.hexagonal || tileMap.mapParameters.shape === MapShape.flatEarth) randomness.RNG.nextDouble() > 0.5f
                else if (tileMap.mapParameters.mapSize.height > tileMap.mapParameters.mapSize.width) true
                else if (tileMap.mapParameters.mapSize.width > tileMap.mapParameters.mapSize.height) false
                else randomness.RNG.nextDouble() > 0.5f

        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            var elevation = randomness.getPerlinNoise(tile, elevationSeed)
            elevation = (elevation + getTwoContinentsTransform(tile, tileMap, isLatitude)) / 2.0
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createThreeContinents(tileMap: TileMap) {
        val isNorth = randomness.RNG.nextDouble() < 0.5
        // On flat earth maps we can randomly do East or West instead of North or South
        val isEastWest = tileMap.mapParameters.shape === MapShape.flatEarth && randomness.RNG.nextDouble() > 0.5

        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            var elevation = randomness.getPerlinNoise(tile, elevationSeed)
            elevation = (elevation + getThreeContinentsTransform(tile, tileMap, isNorth, isEastWest)) / 2.0
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createFourCorners(tileMap: TileMap) {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            var elevation = randomness.getPerlinNoise(tile, elevationSeed)
            elevation = elevation/2 + getFourCornersTransform(tile, tileMap)/2
            spawnLandOrWater(tile, elevation)
        }
    }

    /**
     * Create an elevation map that favors a central elliptic continent spanning over 85% - 95% of
     * the map size.
     */
    private fun getEllipticContinent(tileInfo: TileInfo, tileMap: TileMap, percentOfMap: Double = 0.85): Double {
        val randomScale = randomness.RNG.nextDouble()
        val ratio = percentOfMap + 0.1 * randomness.RNG.nextDouble()

        val a = ratio * tileMap.maxLongitude
        val b = ratio * tileMap.maxLatitude
        val x = tileInfo.longitude
        val y = tileInfo.latitude

        val distanceFactor = x * x / (a * a) + y * y / (b * b) + (x+y).pow(2) / (a+b).pow(2)

        return min(0.3, 1.0 - (5.0 * distanceFactor * distanceFactor + randomScale) / 3.0)
    }

    private fun getContinentAndIslandsTransform(tileInfo: TileInfo, tileMap: TileMap, isNorth: Boolean, isLatitude: Boolean): Double {
        // The idea here is to create a water area separating the two land areas.
        // So what we do it create a line of water in the middle - where latitude or longitude is close to 0.
        val randomScale = randomness.RNG.nextDouble()
        var longitudeFactor = abs(tileInfo.longitude) / tileMap.maxLongitude
        var latitudeFactor = abs(tileInfo.latitude) / tileMap.maxLatitude

        // We then pick one side to become islands instead of a continent
        if (isLatitude) {
            if (isNorth && tileInfo.latitude < 0 || !isNorth && tileInfo.latitude > 0)
                latitudeFactor = 0.2f
        } else {
            // In longitude mode North represents West
            if (isNorth && tileInfo.longitude < 0 || !isNorth && tileInfo.longitude > 0)
                longitudeFactor = 0.2f
        }

        var factor = if (isLatitude) latitudeFactor else longitudeFactor

        // If this is a world wrap, we want it to be separated on both sides -
        // so we make the actual strip of water thinner, but we put it both in the middle of the map and on the edges of the map
        if (tileMap.mapParameters.worldWrap)
            factor = min(factor,
                (tileMap.maxLongitude - abs(tileInfo.longitude)) / tileMap.maxLongitude) * 1.1f

        // there's nothing magical about this, it's just what we got from playing around with a lot of different options -
        //   the numbers can be changed if you find that something else creates better looking continents
        return min(0.2, -1.0 + (5.0 * factor.pow(0.6f) + randomScale) / 3.0)
    }

    private fun getTwoContinentsTransform(tileInfo: TileInfo, tileMap: TileMap, isLatitude: Boolean): Double {
        // The idea here is to create a water area separating the two land areas.
        // So what we do it create a line of water in the middle - where latitude or longitude is close to 0.
        val randomScale = randomness.RNG.nextDouble()
        val latitudeFactor = abs(tileInfo.latitude) / tileMap.maxLatitude
        val longitudeFactor = abs(tileInfo.longitude) / tileMap.maxLongitude

        var factor = if (isLatitude) latitudeFactor else longitudeFactor

        // If this is a world wrap, we want it to be separated on both sides -
        // so we make the actual strip of water thinner, but we put it both in the middle of the map and on the edges of the map
        if (tileMap.mapParameters.worldWrap)
            factor = min(longitudeFactor,
                (tileMap.maxLongitude - abs(tileInfo.longitude)) / tileMap.maxLongitude) * 1.5f

        // there's nothing magical about this, it's just what we got from playing around with a lot of different options -
        //   the numbers can be changed if you find that something else creates better looking continents
        return min(0.2, -1.0 + (5.0 * factor.pow(0.6f) + randomScale) / 3.0)
    }

    private fun getThreeContinentsTransform(tileInfo: TileInfo, tileMap: TileMap, isNorth: Boolean, isEastWest: Boolean): Double {
        // The idea here is to create a water area separating the three land areas.
        // So what we do it create a line of water in the middle - where latitude or longitude is close to 0.
        val randomScale = randomness.RNG.nextDouble()
        var longitudeFactor = abs(tileInfo.longitude) / tileMap.maxLongitude
        var latitudeFactor = abs(tileInfo.latitude) / tileMap.maxLatitude

        // 3rd continent should use only half the map width, or if flat earth, only a third
        val sizeReductionFactor = if (tileMap.mapParameters.shape === MapShape.flatEarth) 3f else 2f

        // We then pick one side to be merged into one centered continent instead of two cornered.
        if (isEastWest) {
            // In EastWest mode North represents West
            if (isNorth && tileInfo.longitude < 0 || !isNorth && tileInfo.longitude > 0)
                latitudeFactor = max(0f, tileMap.maxLatitude - abs(tileInfo.latitude * sizeReductionFactor)) / tileMap.maxLatitude
        } else {
            if (isNorth && tileInfo.latitude < 0 || !isNorth && tileInfo.latitude > 0)
                longitudeFactor = max(0f, tileMap.maxLongitude - abs(tileInfo.longitude * sizeReductionFactor)) / tileMap.maxLongitude
        }

        var factor = min(longitudeFactor, latitudeFactor)

        // If this is a world wrap, we want it to be separated on both sides -
        // so we make the actual strip of water thinner, but we put it both in the middle of the map and on the edges of the map
        if (tileMap.mapParameters.worldWrap) {
            factor = min(
                factor,
                (tileMap.maxLongitude - abs(tileInfo.longitude)) / tileMap.maxLongitude
            ) * 1.5f
        }

        // there's nothing magical about this, it's just what we got from playing around with a lot of different options -
        //   the numbers can be changed if you find that something else creates better looking continents
        return min(0.2, -1.0 + (5.0 * factor.pow(0.5f) + randomScale) / 3.0)
    }

    private fun getFourCornersTransform(tileInfo: TileInfo, tileMap: TileMap): Double {
        // The idea here is to create a water area separating the four land areas.
        // So what we do it create a line of water in the middle - where latitude or longitude is close to 0.
        val randomScale = randomness.RNG.nextDouble()
        val longitudeFactor = abs(tileInfo.longitude) / tileMap.maxLongitude
        val latitudeFactor = abs(tileInfo.latitude) / tileMap.maxLatitude

        var factor = min(longitudeFactor, latitudeFactor)

        // If this is a world wrap, we want it to be separated on both sides -
        // so we make the actual strip of water thinner, but we put it both in the middle of the map and on the edges of the map
        if (tileMap.mapParameters.worldWrap) {
            factor = min(
                factor,
                (tileMap.maxLongitude - abs(tileInfo.longitude)) / tileMap.maxLongitude
            ) * 1.5f
        }

        val shouldBeWater = 1-factor

        // there's nothing magical about this, it's just what we got from playing around with a lot of different options -
        //   the numbers can be changed if you find that something else creates better looking continents
        return 1.0 - (5.0 * shouldBeWater*shouldBeWater + randomScale) / 3.0
    }

    /**
     * Generates ridged perlin noise. As for parameters see [MapGenerationRandomness.getPerlinNoise]
     */
    private fun getRidgedPerlinNoise(tile: TileInfo, seed: Double,
                                     nOctaves: Int = 10,
                                     persistence: Double = 0.5,
                                     lacunarity: Double = 2.0,
                                     scale: Double = 15.0): Double {
        val worldCoords = HexMath.hex2WorldCoords(tile.position)
        return Perlin.ridgedNoise3d(worldCoords.x.toDouble(), worldCoords.y.toDouble(), seed, nOctaves, persistence, lacunarity, scale)
    }
}
