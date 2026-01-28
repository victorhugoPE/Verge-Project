package net.sf.l2j.gameserver.geoengine.geodata

interface IGeoObject {
    val geoX: Int
    val geoY: Int
    val geoZ: Int
    val height: Int
    val objectGeoData: Array<ByteArray>
}

interface IBlockDynamic {
    fun addGeoObject(obj: IGeoObject)
    fun removeGeoObject(obj: IGeoObject)
}