package net.sf.l2j.gameserver.geoengine.geodata

// Singleton para representar blocos vazios/inválidos
object BlockNull : ABlock() {
    override fun hasGeoPos() = false
    override fun getHeightNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?) = worldZ.toShort()
    override fun getNsweNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?) = GeoStructure.CELL_FLAG_ALL
    override fun getIndexNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?) = 0
    override fun getIndexAbove(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?) = 0
    override fun getIndexBelow(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?) = 0
    override fun getHeight(index: Int, ignore: IGeoObject?) = 0.toShort()
    override fun getNswe(index: Int, ignore: IGeoObject?) = GeoStructure.CELL_FLAG_ALL
}