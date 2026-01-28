package net.sf.l2j.gameserver.geoengine.geodata

import net.sf.l2j.gameserver.model.World

object GeoStructure {
    const val CELL_FLAG_NONE: Byte = 0x00
    const val CELL_FLAG_E: Byte = 0x01
    const val CELL_FLAG_W: Byte = 0x02
    const val CELL_FLAG_S: Byte = 0x04
    const val CELL_FLAG_N: Byte = 0x08
    const val CELL_FLAG_ALL: Byte = 0x0F

    const val CELL_SIZE = 16
    const val CELL_HEIGHT = 8
    const val CELL_IGNORE_HEIGHT = CELL_HEIGHT * 6

    const val TYPE_FLAT_L2J_L2OFF: Byte = 0
    const val TYPE_COMPLEX_L2J: Byte = 1
    const val TYPE_COMPLEX_L2OFF: Byte = 0x40
    const val TYPE_MULTILAYER_L2J: Byte = 2

    const val BLOCK_CELLS_X = 8
    const val BLOCK_CELLS_Y = 8
    const val BLOCK_CELLS = BLOCK_CELLS_X * BLOCK_CELLS_Y

    const val REGION_BLOCKS_X = 256
    const val REGION_BLOCKS_Y = 256
    const val REGION_BLOCKS = REGION_BLOCKS_X * REGION_BLOCKS_Y

    const val REGION_CELLS_X = REGION_BLOCKS_X * BLOCK_CELLS_X
    const val REGION_CELLS_Y = REGION_BLOCKS_Y * BLOCK_CELLS_Y

    const val GEO_REGIONS_X = (World.TILE_X_MAX - World.TILE_X_MIN + 1)
    const val GEO_REGIONS_Y = (World.TILE_Y_MAX - World.TILE_Y_MIN + 1)

    const val GEO_BLOCKS_X = GEO_REGIONS_X * REGION_BLOCKS_X
    const val GEO_BLOCKS_Y = GEO_REGIONS_Y * REGION_BLOCKS_Y

    const val GEO_CELLS_X = GEO_BLOCKS_X * BLOCK_CELLS_X
    const val GEO_CELLS_Y = GEO_BLOCKS_Y * BLOCK_CELLS_Y
}