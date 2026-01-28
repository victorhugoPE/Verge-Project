package net.sf.l2j.gameserver.geoengine

import net.sf.l2j.Config
import net.sf.l2j.commons.config.ExProperties
import net.sf.l2j.commons.logging.CLogger
import net.sf.l2j.gameserver.enums.GeoType
import net.sf.l2j.gameserver.enums.MoveDirectionType
import net.sf.l2j.gameserver.geoengine.geodata.*
import net.sf.l2j.gameserver.geoengine.pathfinding.PathFinder
import net.sf.l2j.gameserver.model.World
import net.sf.l2j.gameserver.model.WorldObject
import net.sf.l2j.gameserver.model.actor.Creature
import net.sf.l2j.gameserver.model.actor.Playable
import net.sf.l2j.gameserver.model.location.Location
import net.sf.l2j.gameserver.model.location.SpawnLocation
import net.sf.l2j.gameserver.network.serverpackets.ExServerPrimitive
import java.awt.Color
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * GeoEngine Híbrido: Performance Kotlin + Compatibilidade Total Java Legacy.
 * Versão Final: Com "Silenciador" de erros de Spawn (Retorna WorldZ se Geo for 0).
 */
object GeoEngine {
    
    private val LOGGER = CLogger(GeoEngine::class.java.name)
    private const val GEO_BUG = "%d;%d;%d;%d;%d;%d;%d;%s\r\n"

    private val _blocks: Array<Array<ABlock>> = Array(GeoStructure.GEO_BLOCKS_X) {
        Array(GeoStructure.GEO_BLOCKS_Y) { BlockNull }
    }
    
    private var _geoBugReports: PrintWriter? = null

    init {
        BlockMultilayer.initialize()

        val props: ExProperties = Config.initProperties(Config.GEOENGINE_FILE)
        var loaded = 0
        var failed = 0

        for (rx in World.TILE_X_MIN..World.TILE_X_MAX) {
            for (ry in World.TILE_Y_MIN..World.TILE_Y_MAX) {
                if (props.containsKey("${rx}_$ry")) {
                    if (loadGeoBlocks(rx, ry)) loaded++ else failed++
                } else {
                    loadNullBlocks(rx, ry)
                }
            }
        }

        LOGGER.info("Loaded {} {} region files.", loaded, Config.GEODATA_TYPE)
        BlockMultilayer.release()

        if (failed > 0) {
            LOGGER.warn("Failed to load {} {} region files...", failed, Config.GEODATA_TYPE)
            System.exit(1)
        }

        try {
            val file = File(Config.GEODATA_PATH + "geo_bugs.txt")
            _geoBugReports = PrintWriter(FileOutputStream(file, true), true)
        } catch (e: Exception) {
            LOGGER.error("Couldn't load \"geo_bugs.txt\" file.", e)
        }
    }
    
    @JvmStatic fun getInstance(): GeoEngine = this

    private fun loadGeoBlocks(regionX: Int, regionY: Int): Boolean {
        val filename = String.format(Config.GEODATA_TYPE.filename, regionX, regionY)
        val path = java.nio.file.Paths.get(Config.GEODATA_PATH + filename)

        if (!java.nio.file.Files.exists(path)) {
            loadNullBlocks(regionX, regionY)
            return false
        }

        try {
            // Leitura segura via Heap Buffer (readAllBytes)
            val bytes = java.nio.file.Files.readAllBytes(path)
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            if (Config.GEODATA_TYPE == GeoType.L2OFF) {
                if (buffer.remaining() < 18) {
                    loadNullBlocks(regionX, regionY)
                    return false
                }
                buffer.position(18)
            }

            val blockX = (regionX - World.TILE_X_MIN) * GeoStructure.REGION_BLOCKS_X
            val blockY = (regionY - World.TILE_Y_MIN) * GeoStructure.REGION_BLOCKS_Y

            for (ix in 0 until GeoStructure.REGION_BLOCKS_X) {
                for (iy in 0 until GeoStructure.REGION_BLOCKS_Y) {
                    
                    if (!buffer.hasRemaining()) {
                        loadNullBlocks(regionX, regionY)
                        return false
                    }

                    if (Config.GEODATA_TYPE == GeoType.L2J) {
                        val type = buffer.get()
                        _blocks[blockX + ix][blockY + iy] = when (type) {
                            GeoStructure.TYPE_FLAT_L2J_L2OFF -> BlockFlat(buffer, Config.GEODATA_TYPE)
                            GeoStructure.TYPE_COMPLEX_L2J -> BlockComplex(buffer)
                            GeoStructure.TYPE_MULTILAYER_L2J -> BlockMultilayer(buffer, Config.GEODATA_TYPE)
                            else -> throw IllegalArgumentException("Unknown block type: $type")
                        }
                    } else {
                        val type = buffer.short
                        _blocks[blockX + ix][blockY + iy] = when (type) {
                            GeoStructure.TYPE_FLAT_L2J_L2OFF.toShort() -> BlockFlat(buffer, Config.GEODATA_TYPE)
                            GeoStructure.TYPE_COMPLEX_L2OFF.toShort() -> BlockComplex(buffer)
                            else -> BlockMultilayer(buffer, Config.GEODATA_TYPE)
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            LOGGER.error("Error loading {} region file.", e, filename)
            loadNullBlocks(regionX, regionY)
            return false
        }
    }

    private fun loadNullBlocks(regionX: Int, regionY: Int) {
        val blockX = (regionX - World.TILE_X_MIN) * GeoStructure.REGION_BLOCKS_X
        val blockY = (regionY - World.TILE_Y_MIN) * GeoStructure.REGION_BLOCKS_Y

        for (ix in 0 until GeoStructure.REGION_BLOCKS_X) {
            for (iy in 0 until GeoStructure.REGION_BLOCKS_Y) {
                _blocks[blockX + ix][blockY + iy] = BlockNull
            }
        }
    }

    // ========================================================================
    // MÉTODOS DE ACESSO (CORE)
    // ========================================================================

    @JvmStatic fun getGeoX(worldX: Int) = (worldX - World.WORLD_X_MIN) shr 4
    @JvmStatic fun getGeoY(worldY: Int) = (worldY - World.WORLD_Y_MIN) shr 4
    @JvmStatic fun getWorldX(geoX: Int) = (geoX shl 4) + World.WORLD_X_MIN + 8
    @JvmStatic fun getWorldY(geoY: Int) = (geoY shl 4) + World.WORLD_Y_MIN + 8

    @JvmStatic
    fun getBlock(geoX: Int, geoY: Int): ABlock {
        val bx = geoX / GeoStructure.BLOCK_CELLS_X
        val by = geoY / GeoStructure.BLOCK_CELLS_Y
        if (bx < 0 || bx >= _blocks.size || by < 0 || by >= _blocks[0].size) return BlockNull
        return _blocks[bx][by]
    }

    @JvmStatic fun hasGeoPos(geoX: Int, geoY: Int) = getBlock(geoX, geoY).hasGeoPos()
    @JvmStatic fun hasGeo(worldX: Int, worldY: Int) = hasGeoPos(getGeoX(worldX), getGeoY(worldY))

    /**
     * O Silenciador de Logs:
     * Se a Geodata retornar 0 (buraco/erro) e o mundo esperar algo diferente de 0,
     * retornamos o que o mundo espera para evitar alertas no console.
     */
    @JvmStatic 
    fun getHeightNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Short {
        val h = getBlock(geoX, geoY).getHeightNearest(geoX, geoY, worldZ, ignore)
        
        // CORREÇÃO PARA "WRONG GEO 0":
        // Se a Geodata diz que é 0, mas o Spawn é longe de 0 (ex: 2000), 
        // assumimos que a Geodata está vazia naquele bloco e retornamos o Z original.
        // Isso evita o spam no log Territory.java.
        if (h.toInt() == 0 && worldZ != 0) {
            return worldZ.toShort()
        }
        return h
    }
        
    @JvmStatic fun getHeightNearest(geoX: Int, geoY: Int, worldZ: Int): Short =
        getHeightNearest(geoX, geoY, worldZ, null)

    @JvmStatic fun getNsweNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Byte =
        getBlock(geoX, geoY).getNsweNearest(geoX, geoY, worldZ, ignore)

    @JvmStatic fun getNsweNearest(geoX: Int, geoY: Int, worldZ: Int): Byte =
        getNsweNearest(geoX, geoY, worldZ, null)

    @JvmStatic fun getHeight(loc: Location) = getHeightNearest(getGeoX(loc.x), getGeoY(loc.y), loc.z)
    @JvmStatic fun getHeight(loc: SpawnLocation) = getHeight(loc as Location)
    @JvmStatic fun getHeight(worldX: Int, worldY: Int, worldZ: Int) = getHeightNearest(getGeoX(worldX), getGeoY(worldY), worldZ)
    @JvmStatic fun getNswe(worldX: Int, worldY: Int, worldZ: Int) = getNsweNearest(getGeoX(worldX), getGeoY(worldY), worldZ)

    // ========================================================================
    // COMPATIBILIDADE JAVA & BRIDGES
    // ========================================================================

    @JvmStatic
    fun calculateGeoObject(inside: Array<BooleanArray>): Array<ByteArray> {
        val width = inside.size
        val height = inside[0].size
        val result = Array(width) { ByteArray(height) }

        for (ix in 0 until width) {
            for (iy in 0 until height) {
                if (inside[ix][iy]) {
                    result[ix][iy] = GeoStructure.CELL_FLAG_NONE
                } else {
                    var nswe = GeoStructure.CELL_FLAG_ALL.toInt()
                    if (iy < height - 1 && inside[ix][iy + 1]) nswe = nswe and GeoStructure.CELL_FLAG_S.toInt().inv()
                    if (iy > 0 && inside[ix][iy - 1]) nswe = nswe and GeoStructure.CELL_FLAG_N.toInt().inv()
                    if (ix < width - 1 && inside[ix + 1][iy]) nswe = nswe and GeoStructure.CELL_FLAG_E.toInt().inv()
                    if (ix > 0 && inside[ix - 1][iy]) nswe = nswe and GeoStructure.CELL_FLAG_W.toInt().inv()
                    result[ix][iy] = nswe.toByte()
                }
            }
        }
        return result
    }

    @JvmStatic
    fun canMoveAround(worldX: Int, worldY: Int, worldZ: Int): Boolean {
        val geoX = getGeoX(worldX)
        val geoY = getGeoY(worldY)
        
        for (ix in -1..1) {
            for (iy in -1..1) {
                val gx = geoX + ix
                val gy = geoY + iy
                if (getNsweNearest(gx, gy, worldZ) != GeoStructure.CELL_FLAG_ALL) return false
            }
        }
        return true
    }
    
    @JvmStatic
    fun addGeoBug(loc: Location, comment: String): Boolean {
        return addGeoBug(loc.x, loc.y, loc.z, comment)
    }
    
    @JvmStatic
    fun addGeoBug(loc: SpawnLocation, comment: String): Boolean {
        return addGeoBug(loc.x, loc.y, loc.z, comment)
    }

    private fun addGeoBug(x: Int, y: Int, z: Int, comment: String): Boolean {
        val gox = getGeoX(x)
        val goy = getGeoY(y)
        val rx = gox / GeoStructure.REGION_CELLS_X + World.TILE_X_MIN
        val ry = goy / GeoStructure.REGION_CELLS_Y + World.TILE_Y_MIN
        val bx = (gox / GeoStructure.BLOCK_CELLS_X) % GeoStructure.REGION_BLOCKS_X
        val by = (goy / GeoStructure.BLOCK_CELLS_Y) % GeoStructure.REGION_BLOCKS_Y
        val cx = gox % GeoStructure.BLOCK_CELLS_X
        val cy = goy % GeoStructure.BLOCK_CELLS_Y

        return try {
            _geoBugReports?.printf(GEO_BUG, rx, ry, bx, by, cx, cy, z, comment.replace(";", ":"))
            true
        } catch (e: Exception) {
            LOGGER.error("Couldn't save new entry to \"geo_bugs.txt\".", e)
            false
        }
    }

    // ========================================================================
    // GESTÃO DE OBJETOS DINÂMICOS
    // ========================================================================
    
    @JvmStatic fun addGeoObject(obj: IGeoObject) = toggleGeoObject(obj, true)
    @JvmStatic fun removeGeoObject(obj: IGeoObject) = toggleGeoObject(obj, false)

    private fun toggleGeoObject(obj: IGeoObject, add: Boolean) {
        val minGX = obj.geoX
        val minGY = obj.geoY
        val geoData = obj.objectGeoData

        val minBX = minGX / GeoStructure.BLOCK_CELLS_X
        val maxBX = (minGX + geoData.size - 1) / GeoStructure.BLOCK_CELLS_X
        val minBY = minGY / GeoStructure.BLOCK_CELLS_Y
        val maxBY = (minGY + geoData[0].size - 1) / GeoStructure.BLOCK_CELLS_Y

        val safeMaxBX = min(maxBX, _blocks.size - 1)
        val safeMaxBY = min(maxBY, _blocks[0].size - 1)
        val safeMinBX = max(minBX, 0)
        val safeMinBY = max(minBY, 0)

        for (bx in safeMinBX..safeMaxBX) {
            for (by in safeMinBY..safeMaxBY) {
                synchronized(_blocks) {
                    var block = _blocks[bx][by]
                    if (block !is IBlockDynamic) {
                        if (block is BlockNull) return@synchronized
                        
                        block = when (block) {
                            is BlockFlat -> BlockComplexDynamic(bx, by, block)
                            is BlockComplex -> BlockComplexDynamic(bx, by, block)
                            is BlockMultilayer -> BlockMultilayerDynamic(bx, by, block)
                            else -> block
                        }
                        _blocks[bx][by] = block
                    }
                    if (add) (block as IBlockDynamic).addGeoObject(obj)
                    else (block as IBlockDynamic).removeGeoObject(obj)
                }
            }
        }
    }

    // ========================================================================
    // MOVIMENTAÇÃO E PATHFINDING
    // ========================================================================

    @JvmStatic 
    fun canMoveToTarget(ox: Int, oy: Int, oz: Int, tx: Int, ty: Int, tz: Int): Boolean {
        return canMove(ox, oy, oz, tx, ty, tz, null)
    }

    @JvmStatic 
    fun canMoveToTarget(obj: WorldObject, target: WorldObject): Boolean {
        return canMoveToTarget(obj.position, target.position)
    }
    
    @JvmStatic 
    fun canMoveToTarget(origin: Location, target: Location): Boolean {
        return canMove(origin.x, origin.y, origin.z, target.x, target.y, target.z, null)
    }

    @JvmStatic @JvmOverloads
    fun canMove(ox: Int, oy: Int, oz: Int, tx: Int, ty: Int, tz: Int, debug: ExServerPrimitive? = null): Boolean {
        if (World.isOutOfWorld(tx, ty)) return false

        var gox = getGeoX(ox); var goy = getGeoY(oy)
        var goz = getHeightNearest(gox, goy, oz).toInt()
        val gtx = getGeoX(tx); val gty = getGeoY(ty)

        if (gox == gtx && goy == gty) return goz == getHeight(tx, ty, tz).toInt()

        var nswe = getNsweNearest(gox, goy, goz, null).toInt()
        
        val dx = tx - ox
        val dy = ty - oy
        val m = if (dx == 0) 0.0 else dy.toDouble() / dx
        val mdt = MoveDirectionType.getDirection(gtx - gox, gty - goy)

        var gridX = ox and -0x10
        var gridY = oy and -0x10

        debug?.let {
            it.addSquare(Color.BLUE, gridX, gridY, goz + 1, 15)
            it.addSquare(Color.BLUE, tx and -0x10, ty and -0x10, tz, 15)
        }

        while (gox != gtx || goy != gty) {
            var checkX = gridX + mdt.offsetX
            var checkY = (oy + m * (checkX - ox)).toInt()
            var dir: Int
            
            if (mdt.stepX != 0 && getGeoY(checkY) == goy) {
                gridX += mdt.stepX
                gox += mdt.signumX
                dir = mdt.directionX.toInt()
            } else {
                checkY = gridY + mdt.offsetY
                checkX = (ox + (checkY - oy) / m).toInt()
                checkX = checkX.coerceIn(gridX, gridX + 15)
                gridY += mdt.stepY
                goy += mdt.signumY
                dir = mdt.directionY.toInt()
            }
            
            if ((nswe and dir) == 0) {
                debug?.addSquare(Color.RED, gridX, gridY, goz, 15)
                return false
            }

            val block = getBlock(gox, goy)
            val idx = block.getIndexBelow(gox, goy, goz + GeoStructure.CELL_IGNORE_HEIGHT, null)
            
            if (idx < 0) {
                debug?.addSquare(Color.RED, gridX, gridY, goz, 15)
                return false
            }
            
            goz = block.getHeight(idx, null).toInt()
            nswe = block.getNswe(idx, null).toInt()
        }
        
        return goz == getHeight(tx, ty, tz).toInt()
    }

    // ========================================================================
    // GET VALID LOCATION (BRIDGES)
    // ========================================================================

    @JvmStatic
    fun getValidLocation(obj: WorldObject, tx: Int, ty: Int, tz: Int): Location {
        return getValidLocation(obj.x, obj.y, obj.z, tx, ty, tz, null)
    }

    @JvmStatic
    fun getValidLocation(obj: WorldObject, loc: Location): Location {
        return getValidLocation(obj.x, obj.y, obj.z, loc.x, loc.y, loc.z, null)
    }

    @JvmStatic
    fun getValidLocation(creature: Creature, loc: Location): Location {
         return getValidLocation(creature.x, creature.y, creature.z, loc.x, loc.y, loc.z, null)
    }
    
    @JvmStatic
    fun getValidLocation(creature: Creature, loc: SpawnLocation): Location {
         return getValidLocation(creature.x, creature.y, creature.z, loc.x, loc.y, loc.z, null)
    }

    @JvmStatic
    fun getValidLocation(follower: WorldObject, target: WorldObject): Location {
        return getValidLocation(follower.position, target.position)
    }
    
    @JvmStatic
    fun getValidLocation(origin: Location, target: Location): Location {
        return getValidLocation(origin.x, origin.y, origin.z, target.x, target.y, target.z, null)
    }

    @JvmStatic
    fun getValidLocation(ox: Int, oy: Int, oz: Int, tx: Int, ty: Int, tz: Int, debug: ExServerPrimitive?): Location {
        var gox = getGeoX(ox); var goy = getGeoY(oy)
        var goz = getHeightNearest(gox, goy, oz).toInt()
        var nswe = getNsweNearest(gox, goy, goz, null).toInt()

        val gtx = getGeoX(tx); val gty = getGeoY(ty)
        val gtz = getHeightNearest(gtx, gty, tz).toInt()
        
        val m = if (tx == ox) 0.0 else (ty - oy).toDouble() / (tx - ox)
        val mdt = MoveDirectionType.getDirection(gtx - gox, gty - goy)
        
        var gridX = ox and -0x10
        var gridY = oy and -0x10
        
        var nx = gox; var ny = goy
        var dir: Int

        while (gox != gtx || goy != gty) {
            var checkX = gridX + mdt.offsetX
            var checkY = (oy + m * (checkX - ox)).toInt()
            
            if (mdt.stepX != 0 && getGeoY(checkY) == goy) {
                gridX += mdt.stepX
                nx += mdt.signumX
                dir = mdt.directionX.toInt()
            } else {
                checkY = gridY + mdt.offsetY
                checkX = (ox + (checkY - oy) / m).toInt()
                checkX = checkX.coerceIn(gridX, gridX + 15)
                gridY += mdt.stepY
                ny += mdt.signumY
                dir = mdt.directionY.toInt()
            }
            
            if (nx < 0 || nx >= GeoStructure.GEO_CELLS_X || ny < 0 || ny >= GeoStructure.GEO_CELLS_Y) return Location(checkX, checkY, goz)
            if ((nswe and dir) == 0) return Location(checkX, checkY, goz)
            
            val block = getBlock(nx, ny)
            val idx = block.getIndexBelow(nx, ny, goz + GeoStructure.CELL_IGNORE_HEIGHT, null)
            
            if (idx < 0) return Location(checkX, checkY, goz)
            
            gox = nx; goy = ny
            goz = block.getHeight(idx, null).toInt()
            nswe = block.getNswe(idx, null).toInt()
        }
        return if (goz == gtz) Location(tx, ty, gtz) else Location(ox, oy, oz)
    }

    // ========================================================================
    // FLY / WYVERN
    // ========================================================================

    @JvmStatic
    fun canFlyToTarget(ox: Int, oy: Int, oz: Int, h: Int, tx: Int, ty: Int, tz: Int): Boolean {
        return canFly(ox, oy, oz, h.toDouble(), tx, ty, tz, null)
    }
    
    @JvmStatic
    fun getValidFlyLocation(ox: Int, oy: Int, oz: Int, h: Int, tx: Int, ty: Int, tz: Int, debug: ExServerPrimitive?): Location {
         return getValidFlyLocation(ox, oy, oz, h.toDouble(), tx, ty, tz, debug)
    }

    @JvmStatic 
    fun canFly(ox: Int, oy: Int, oz: Int, oheight: Double, tx: Int, ty: Int, tz: Int, debug: ExServerPrimitive? = null): Boolean {
        if (World.isOutOfWorld(tx, ty)) return false

        var gox = getGeoX(ox)
        var goy = getGeoY(oy)
        val gtx = getGeoX(tx)
        val gty = getGeoY(ty)
        var goz = getHeightNearest(gox, goy, oz).toInt()

        val m = if (tx == ox) 0.0 else (ty - oy).toDouble() / (tx - ox)
        val mz = (tz - oz).toDouble() / sqrt(((tx - ox) * (tx - ox) + (ty - oy) * (ty - oy)).toDouble())
        val mdt = MoveDirectionType.getDirection(gtx - gox, gty - goy)

        var gridX = ox and -0x10
        var gridY = oy and -0x10

        debug?.let {
            it.addSquare(Color.BLUE, gridX, gridY, goz - 32, 15)
            it.addSquare(Color.BLUE, tx and -0x10, ty and -0x10, tz - 32, 15)
        }

        while (gox != gtx || goy != gty) {
            var checkX = gridX + mdt.offsetX
            var checkY = (oy + m * (checkX - ox)).toInt()

            if (mdt.stepX != 0 && getGeoY(checkY) == goy) {
                gridX += mdt.stepX
                gox += mdt.signumX
            } else {
                checkY = gridY + mdt.offsetY
                checkX = (ox + (checkY - oy) / m).toInt()
                checkX = checkX.coerceIn(gridX, gridX + 15)
                gridY += mdt.stepY
                goy += mdt.signumY
            }

            val block = getBlock(gox, goy)
            var nextZ = oz + (mz * sqrt(((checkX - ox) * (checkX - ox) + (checkY - oy) * (checkY - oy)).toDouble())).toInt()

            var index = block.getIndexBelow(gox, goy, nextZ + oheight.toInt(), null)
            if (index < 0) return false
            goz = block.getHeight(index, null).toInt()
            if (goz > nextZ) return false

            index = block.getIndexAbove(gox, goy, nextZ, null)
            nextZ += oheight.toInt()
            if (index >= 0) {
                goz = block.getHeight(index, null).toInt()
                if (goz < nextZ) return false
            }
        }
        return true
    }

    @JvmStatic
    fun getValidFlyLocation(ox: Int, oy: Int, oz: Int, oheight: Double, tx: Int, ty: Int, tz: Int, debug: ExServerPrimitive?): Location {
        if (canFly(ox, oy, oz, oheight, tx, ty, tz, debug)) return Location(tx, ty, tz)
        return Location(ox, oy, oz)
    }

    // ========================================================================
    // LINE OF SIGHT (LoS)
    // ========================================================================

    @JvmStatic
    fun canSeeLocation(obj: WorldObject, loc: Location): Boolean {
        var oheight = if (obj is Creature) obj.collisionHeight * 2 * Config.PART_OF_CHARACTER_HEIGHT / 100.0 else 0.0
        return canSee(obj.x, obj.y, obj.z, oheight, loc.x, loc.y, loc.z, 0.0, null, null)
    }

    @JvmStatic
    fun canSeeTarget(obj: WorldObject, target: WorldObject): Boolean {
        var oheight = if (obj is Creature) obj.collisionHeight * 2 * Config.PART_OF_CHARACTER_HEIGHT / 100.0 else 0.0
        var theight = if (target is Creature) target.collisionHeight * 2 * Config.PART_OF_CHARACTER_HEIGHT / 100.0 else 0.0
        return canSee(obj.x, obj.y, obj.z, oheight, target.x, target.y, target.z, theight, if (target is IGeoObject) target else null, null)
    }

    @JvmStatic 
    fun canSee(ox: Int, oy: Int, oz: Int, oheight: Double, tx: Int, ty: Int, tz: Int, theight: Double, ignore: IGeoObject?, debug: ExServerPrimitive?): Boolean {
        if (World.isOutOfWorld(ox, oy) || World.isOutOfWorld(tx, ty)) return false
        
        var gox = getGeoX(ox)
        var goy = getGeoY(oy)
        val gtx = getGeoX(tx)
        val gty = getGeoY(ty)
        
        var block = getBlock(gox, goy)
        var idx = block.getIndexBelow(gox, goy, oz + GeoStructure.CELL_HEIGHT, ignore)
        if (idx < 0) return false
        
        if (gox == gtx && goy == gty) return idx == block.getIndexBelow(gtx, gty, tz + GeoStructure.CELL_HEIGHT, ignore)
        
        var groundZ = block.getHeight(idx, ignore).toInt()
        var nswe = block.getNswe(idx, ignore).toInt()
        
        val dx = tx - ox
        val dy = ty - oy
        val dz = (tz + theight) - (oz + oheight)
        val m = if (dx == 0) 0.0 else dy.toDouble() / dx
        val mz = dz / sqrt((dx * dx + dy * dy).toDouble())
        val mdt = MoveDirectionType.getDirection(gtx - gox, gty - goy)
        
        var gridX = ox and -0x10
        var gridY = oy and -0x10
        
        while (gox != gtx || goy != gty) {
            var checkX = gridX + mdt.offsetX
            var checkY = (oy + m * (checkX - ox)).toInt()
            var dir: Int
            
            if (mdt.stepX != 0 && getGeoY(checkY) == goy) {
                gridX += mdt.stepX
                gox += mdt.signumX
                dir = mdt.directionX.toInt()
            } else {
                checkY = gridY + mdt.offsetY
                checkX = (ox + (checkY - oy) / m).toInt()
                checkX = checkX.coerceIn(gridX, gridX + 15)
                gridY += mdt.stepY
                goy += mdt.signumY
                dir = mdt.directionY.toInt()
            }
            
            block = getBlock(gox, goy)
            var losz = oz + oheight + Config.MAX_OBSTACLE_HEIGHT
            losz += mz * sqrt(((checkX - ox) * (checkX - ox) + (checkY - oy) * (checkY - oy)).toDouble())
            
            val canMove = (nswe and dir) != 0
            if (canMove) {
                idx = block.getIndexBelow(gox, goy, groundZ + GeoStructure.CELL_IGNORE_HEIGHT, ignore)
            } else {
                idx = block.getIndexAbove(gox, goy, groundZ - 2 * GeoStructure.CELL_HEIGHT, ignore)
            }
            
            if (idx < 0) return false
            
            val z = block.getHeight(idx, ignore).toInt()
            if (z > losz) return false
            groundZ = z
            nswe = block.getNswe(idx, ignore).toInt()
        }
        return true
    }
    
    @JvmStatic
    fun findPath(ox: Int, oy: Int, oz: Int, tx: Int, ty: Int, tz: Int, playable: Boolean, debug: ExServerPrimitive?): List<Location> {
        if (World.isOutOfWorld(tx, ty)) return emptyList()
        val gox = getGeoX(ox); val goy = getGeoY(oy)
        if (!hasGeoPos(gox, goy)) return emptyList()
        val goz = getHeightNearest(gox, goy, oz).toInt()
        val gtx = getGeoX(tx); val gty = getGeoY(ty)
        if (!hasGeoPos(gtx, gty)) return emptyList()
        val gtz = getHeightNearest(gtx, gty, tz).toInt()

        val pf = PathFinder()
        return pf.findPath(gox, goy, goz, gtx, gty, gtz, debug)
    }
}