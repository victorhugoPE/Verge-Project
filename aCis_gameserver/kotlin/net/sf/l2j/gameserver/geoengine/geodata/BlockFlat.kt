package net.sf.l2j.gameserver.geoengine.geodata

import net.sf.l2j.gameserver.enums.GeoType
import java.nio.ByteBuffer

open class BlockFlat(bb: ByteBuffer, type: GeoType) : ABlock() {
    protected val _height: Short
    protected val _nswe: Byte

    init {
        // L2OFF e L2J: Primeiro Short é o Tipo (já lido no GeoEngine), próximo é a Altura.
        _height = bb.short
        _nswe = GeoStructure.CELL_FLAG_ALL
        
        // Se for L2OFF, existem 2 bytes de "padding" ou dummy data que precisamos pular.
        if (type == GeoType.L2OFF) {
            bb.short 
        }
    }

    override fun hasGeoPos() = true
    override fun getHeightNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?) = _height
    override fun getNsweNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?) = _nswe
    override fun getIndexNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?) = 0
    override fun getIndexAbove(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?) = if (_height > worldZ) 0 else -1
    override fun getIndexBelow(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?) = if (_height < worldZ) 0 else -1
    override fun getHeight(index: Int, ignore: IGeoObject?) = _height
    override fun getNswe(index: Int, ignore: IGeoObject?) = _nswe
    
    fun getRawHeight() = _height
    fun getRawNswe() = _nswe
}