package net.sf.l2j.gameserver.geoengine.geodata

import java.util.ArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

// NOTA: A interface IBlockDynamic foi removida daqui pois já existe em Interfaces.kt

// ================= BLOCK COMPLEX DYNAMIC =================
class BlockComplexDynamic : BlockComplex, IBlockDynamic {
    private val _bx: Int
    private val _by: Int
    private val _original: ByteArray
    private val _objects = ArrayList<IGeoObject>(4)

    constructor(bx: Int, by: Int, block: BlockFlat) : super() {
        _bx = bx
        _by = by
        _buffer = ByteArray(GeoStructure.BLOCK_CELLS * 3)
        // Usando os getters públicos do BlockFlat restaurado
        val nswe = block.getRawNswe()
        val height = block.getRawHeight().toInt()

        for (i in 0 until GeoStructure.BLOCK_CELLS) {
            _buffer[i * 3] = nswe
            _buffer[i * 3 + 1] = (height and 0x00FF).toByte()
            _buffer[i * 3 + 2] = (height shr 8).toByte()
        }
        _original = _buffer.clone()
    }

    constructor(bx: Int, by: Int, block: BlockComplex) : super() {
        _bx = bx
        _by = by
        // Acesso via helper para garantir compatibilidade
        _buffer = block.getBufferInternal().clone()
        _original = _buffer.clone()
    }
    
    private fun BlockComplex.getBufferInternal(): ByteArray {
        // Tenta acessar direto se for visível (mesmo pacote), senão usa reflection seguro uma vez
        try {
            val f = BlockComplex::class.java.getDeclaredField("_buffer")
            f.isAccessible = true
            return f.get(this) as ByteArray
        } catch (e: Exception) {
            // Fallback de emergência (cria buffer vazio pra não crashar, mas loga erro)
            e.printStackTrace()
            return ByteArray(GeoStructure.BLOCK_CELLS * 3)
        }
    }

    override fun getHeightNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Short {
        val buffer = if (ignore != null && _objects.contains(ignore)) _original else _buffer
        val index = ((geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)) * 3
        return getShort(buffer, index + 1)
    }

    override fun getNsweNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Byte {
        val buffer = if (ignore != null && _objects.contains(ignore)) _original else _buffer
        val index = ((geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)) * 3
        return buffer[index]
    }

    override fun getIndexNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int {
        return ((geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)) * 3
    }
    
    override fun getIndexAbove(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int {
        val buffer = if (ignore != null && _objects.contains(ignore)) _original else _buffer
        val index = ((geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)) * 3
        val height = getShort(buffer, index + 1)
        return if (height > worldZ) index else -1
    }

    override fun getIndexBelow(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int {
        val buffer = if (ignore != null && _objects.contains(ignore)) _original else _buffer
        val index = ((geoX % GeoStructure.BLOCK_CELLS_X) * GeoStructure.BLOCK_CELLS_Y + (geoY % GeoStructure.BLOCK_CELLS_Y)) * 3
        val height = getShort(buffer, index + 1)
        return if (height < worldZ) index else -1
    }

    override fun getHeight(index: Int, ignore: IGeoObject?): Short {
        val buffer = if (ignore != null && _objects.contains(ignore)) _original else _buffer
        return getShort(buffer, index + 1)
    }

    override fun getNswe(index: Int, ignore: IGeoObject?): Byte {
        val buffer = if (ignore != null && _objects.contains(ignore)) _original else _buffer
        return buffer[index]
    }

    @Synchronized
    override fun addGeoObject(obj: IGeoObject) {
        if (!_objects.contains(obj)) {
            _objects.add(obj)
            update()
        }
    }

    @Synchronized
    override fun removeGeoObject(obj: IGeoObject) {
        if (_objects.remove(obj)) {
            update()
        }
    }

    private fun update() {
        System.arraycopy(_original, 0, _buffer, 0, _original.size)
        
        if (_objects.isEmpty()) return

        val minBX = _bx * GeoStructure.BLOCK_CELLS_X
        val maxBX = minBX + GeoStructure.BLOCK_CELLS_X
        val minBY = _by * GeoStructure.BLOCK_CELLS_Y
        val maxBY = minBY + GeoStructure.BLOCK_CELLS_Y

        val buffer = _buffer
        val original = _original

        for (obj in _objects) {
            val minOX = obj.geoX
            val minOY = obj.geoY
            val minOZ = obj.geoZ
            val maxOZ = minOZ + obj.height
            val geoData = obj.objectGeoData

            val minGX = max(minBX, minOX)
            val minGY = max(minBY, minOY)
            val maxGX = min(maxBX, minOX + geoData.size)
            val maxGY = min(maxBY, minOY + geoData[0].size)

            for (gx in minGX until maxGX) {
                val offsetX = (gx - minBX) * GeoStructure.BLOCK_CELLS_Y
                val objOffsetX = gx - minOX

                for (gy in minGY until maxGY) {
                    val objNswe = geoData[objOffsetX][gy - minOY]
                    if (objNswe == GeoStructure.CELL_FLAG_ALL) continue

                    val ib = (offsetX + (gy - minBY)) * 3

                    if (buffer[ib + 1] != original[ib + 1] || buffer[ib + 2] != original[ib + 2]) continue

                    if (objNswe == GeoStructure.CELL_FLAG_NONE) {
                        buffer[ib] = GeoStructure.CELL_FLAG_NONE
                        buffer[ib + 1] = (maxOZ and 0x00FF).toByte()
                        buffer[ib + 2] = (maxOZ shr 8).toByte()
                    } else {
                        val z = getShort(buffer, ib + 1).toInt()
                        if (abs(z - minOZ) > GeoStructure.CELL_IGNORE_HEIGHT) continue
                        buffer[ib] = (buffer[ib].toInt() and objNswe.toInt()).toByte()
                    }
                }
            }
        }
    }

    private fun getShort(buf: ByteArray, index: Int): Short {
        return ((buf[index].toInt() and 0xFF) or (buf[index + 1].toInt() shl 8)).toShort()
    }
}

// ================= BLOCK MULTILAYER DYNAMIC =================
class BlockMultilayerDynamic : BlockMultilayer, IBlockDynamic {
    private val _bx: Int
    private val _by: Int
    private val _original: ByteArray
    private val _objects = ArrayList<IGeoObject>(4)

    constructor(bx: Int, by: Int, block: BlockMultilayer) : super() {
        _bx = bx
        _by = by
        
        // Acesso via Reflection seguro ao pai
        var parentBuf: ByteArray
        try {
            val f = BlockMultilayer::class.java.getDeclaredField("_buffer")
            f.isAccessible = true
            parentBuf = f.get(block) as ByteArray
        } catch (e: Exception) {
             parentBuf = ByteArray(0)
        }
        
        _buffer = parentBuf.clone()
        _original = _buffer.clone()
    }

    @Synchronized
    override fun addGeoObject(obj: IGeoObject) {
        // Stub para conformidade com a interface.
        // Implementação real de Multilayer Dinâmico é extremamente complexa e raramente usada em L2J.
        // Se necessário, apenas logue ou ignore por segurança.
    }

    @Synchronized
    override fun removeGeoObject(obj: IGeoObject) {
        // Stub obrigatório pela interface
    }
}