package net.sf.l2j.gameserver.geoengine.geodata

import net.sf.l2j.gameserver.enums.GeoType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * BlockMultilayer OTIMIZADO e CORRIGIDO.
 * Correção: Leitura de bits da altura agora preserva o sinal corretamente.
 */
open class BlockMultilayer : ABlock {
    
    protected var _buffer: ByteArray

    companion object {
        private const val MAX_LAYERS = Byte.MAX_VALUE.toInt()
        private var _temp: ByteBuffer? = null

        fun initialize() {
            _temp = ByteBuffer.allocate(GeoStructure.BLOCK_CELLS * MAX_LAYERS * 3)
            _temp?.order(ByteOrder.LITTLE_ENDIAN)
        }

        fun release() {
            _temp = null
        }
    }

    protected constructor() {
        _buffer = ByteArray(0)
    }

    constructor(bb: ByteBuffer, type: GeoType) {
        val temp = _temp ?: throw IllegalStateException("BlockMultilayer temp buffer not initialized")
        temp.clear()
        
        for (cell in 0 until GeoStructure.BLOCK_CELLS) {
            val layers = if (type != GeoType.L2OFF) bb.get().toInt() else bb.short.toInt()
            
            if (layers <= 0 || layers > MAX_LAYERS) {
                // Em caso de erro no arquivo, logamos mas não crashamos, assumimos flat
                temp.put(1.toByte())
                temp.put(GeoStructure.CELL_FLAG_ALL)
                temp.putShort(0)
                continue
            }
            
            temp.put(layers.toByte())
            
            for (layer in 0 until layers) {
                val data = bb.short.toInt()
                
                // NSWE: Últimos 4 bits
                temp.put((data and 0x000F).toByte())
                
                // ALTURA: Primeiros 12 bits. 
                // CORREÇÃO: Usar toShort().toInt() para preservar o sinal negativo corretamente antes do shift
                val height = (data and 0xFFF0).toShort().toInt() shr 1
                temp.putShort(height.toShort())
            }
        }
        
        _buffer = ByteArray(temp.position())
        temp.flip()
        temp.get(_buffer)
    }

    override fun hasGeoPos(): Boolean = true

    override fun getHeightNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Short {
        val index = getIndexNearest(geoX, geoY, worldZ, ignore)
        return getShort(index + 1)
    }

    override fun getNsweNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Byte {
        val index = getIndexNearest(geoX, geoY, worldZ, ignore)
        return _buffer[index]
    }

    override fun getIndexNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int {
        var index = 0
        val cellsToSkip = (geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)
        
        for (i in 0 until cellsToSkip) {
            index += (_buffer[index].toInt() and 0xFF) * 3 + 1
        }

        var layers = _buffer[index++].toInt() and 0xFF
        var limit = Int.MAX_VALUE
        var bestIndex = index 

        while (layers-- > 0) {
            val height = getShort(index + 1)
            val distance = abs(height - worldZ)
            
            if (distance > limit) break
            
            limit = distance
            bestIndex = index
            index += 3 
        }
        
        return bestIndex
    }

    override fun getIndexAbove(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int {
        var index = 0
        val cellsToSkip = (geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)
        
        for (i in 0 until cellsToSkip) {
            index += (_buffer[index].toInt() and 0xFF) * 3 + 1
        }

        var layers = _buffer[index++].toInt() and 0xFF
        index += (layers - 1) * 3

        while (layers-- > 0) {
            val height = getShort(index + 1)
            if (height > worldZ) return index
            index -= 3
        }
        return -1
    }

    override fun getIndexBelow(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int {
        var index = 0
        val cellsToSkip = (geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)
        
        for (i in 0 until cellsToSkip) {
            index += (_buffer[index].toInt() and 0xFF) * 3 + 1
        }

        var layers = _buffer[index++].toInt() and 0xFF
        
        while (layers-- > 0) {
            val height = getShort(index + 1)
            if (height < worldZ) return index
            index += 3
        }
        return -1
    }

    override fun getHeight(index: Int, ignore: IGeoObject?): Short {
        return getShort(index + 1)
    }

    override fun getNswe(index: Int, ignore: IGeoObject?): Byte {
        return _buffer[index]
    }

    private fun getShort(index: Int): Short {
        val low = _buffer[index].toInt() and 0xFF
        val high = _buffer[index + 1].toInt() shl 8
        return (low or high).toShort()
    }
}