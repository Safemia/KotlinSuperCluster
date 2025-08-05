package com.safemia.supercluster

data class Point(
    val coordinates: List<Double>
) {
    val longitude: Double get() = coordinates[0]
    val latitude: Double get() = coordinates[1]
}

data class Geometry(
    val type: String,
    val coordinates: List<Double>
) {
    fun toPoint(): Point = Point(coordinates)
}

data class Feature(
    val type: String = "Feature",
    val id: Any? = null,
    val properties: Map<String, Any> = emptyMap(),
    val geometry: Geometry
)

data class Tile(
    val features: List<TileFeature>
)

data class TileFeature(
    val type: Int,
    val geometry: List<List<Int>>,
    val tags: Map<String, Any>,
    val id: Any? = null
)