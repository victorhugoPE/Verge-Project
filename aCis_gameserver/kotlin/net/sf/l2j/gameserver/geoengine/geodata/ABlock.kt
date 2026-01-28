package net.sf.l2j.gameserver.geoengine.geodata

abstract class ABlock {
    abstract fun hasGeoPos(): Boolean
    abstract fun getHeightNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Short
    abstract fun getNsweNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Byte
    abstract fun getIndexNearest(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int
    abstract fun getIndexAbove(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int
    abstract fun getIndexBelow(geoX: Int, geoY: Int, worldZ: Int, ignore: IGeoObject?): Int
    abstract fun getHeight(index: Int, ignore: IGeoObject?): Short
    abstract fun getNswe(index: Int, ignore: IGeoObject?): Byte
}