package net.sf.l2j.gameserver.geoengine.geodata

import java.nio.ByteBuffer

open class BlockComplex : ABlock {
    
    protected var _buffer: ByteArray

    protected constructor() {
        _buffer = ByteArray(0)
    }

    constructor(bb: ByteBuffer) {
        _buffer = ByteArray(GeoStructure.BLOCK_CELLS * 3)
        
        for (i in 0 until GeoStructure.BLOCK_CELLS) {
            // Lendo o short original do arquivo (assinatura L2OFF/L2J)
            val data = bb.short.toInt()
            
            // Extrai NSWE (últimos 4 bits)
            val nswe = (data and 0x000F).toByte()
            
            // Extrai Altura (primeiros bits, shiftado)
            // CORREÇÃO CRÍTICA: Usamos 'shr' em Int preservando o sinal corretamente
            // L2J Standard: (short)((data & 0xFFF0) >> 1)
            val height = (data and 0xFFF0).toShort().toInt() shr 1
            
            // Armazena no buffer interno (3 bytes por célula)
            _buffer[i * 3] = nswe
            _buffer[i * 3 + 1] = (height and 0x00FF).toByte() // Low Byte
            _buffer[i * 3 + 2] = (height shr 8).toByte()     // High Byte
        }
    }

    override fun hasGeoPos(): Boolean = true

    override fun getHeightNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Short {
        val index = ((geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)) * 3
        return getShort(index + 1)
    }

    override fun getNsweNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Byte {
        val index = ((geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)) * 3
        return _buffer[index]
    }

    override fun getIndexNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int {
        return ((geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)) * 3
    }

    override fun getIndexAbove(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int {
        val index = ((geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)) * 3
        val height = getShort(index + 1)
        return if (height > worldZ) index else -1
    }

    override fun getIndexBelow(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int {
        val index = ((geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)) * 3
        val height = getShort(index + 1)
        return if (height < worldZ) index else -1
    }

    override fun getHeight(index: Int, ignore: IGeoObject?): Short {
        return getShort(index + 1)
    }

    override fun getNswe(index: Int, ignore: IGeoObject?): Byte {
        return _buffer[index]
    }

    private fun getShort(index: Int): Short {
        // Reconstrói o short a partir dos 2 bytes armazenados
        val low = _buffer[index].toInt() and 0xFF
        val high = _buffer[index + 1].toInt()
        return ((high shl 8) or low).toShort()
    }
}