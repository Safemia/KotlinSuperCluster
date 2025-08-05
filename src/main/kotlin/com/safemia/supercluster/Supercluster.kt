package com.safemia.supercluster

import kotlin.math.*

data class SuperclusterOptions(
    val minZoom: Int = 0,
    val maxZoom: Int = 16,
    val minPoints: Int = 2,
    val radius: Int = 40,
    val extent: Int = 512,
    val nodeSize: Int = 64,
    val log: Boolean = false,
    val generateId: Boolean = false,
    val reduce: ((MutableMap<String, Any>, Map<String, Any>) -> Unit)? = null,
    val map: (Map<String, Any>) -> Map<String, Any> = { it }
)

class Supercluster(private val options: SuperclusterOptions = SuperclusterOptions()) {
    private val trees = arrayOfNulls<KDBush>(options.maxZoom + 2)
    private val stride = if (options.reduce != null) 7 else 6
    private val clusterProps = mutableListOf<Map<String, Any>>()
    private lateinit var points: List<Feature>

    companion object {
        private const val OFFSET_ZOOM = 2
        private const val OFFSET_ID = 3
        private const val OFFSET_PARENT = 4
        private const val OFFSET_NUM = 5
        private const val OFFSET_PROP = 6
    }

    fun load(points: List<Feature>): Supercluster {
        val startTime = if (options.log) System.currentTimeMillis() else 0L
        
        if (options.log) {
            val prepareTime = System.currentTimeMillis()
            println("prepare ${points.size} points")
        }

        this.points = points
        val data = mutableListOf<Float>()

        for (i in points.indices) {
            val p = points[i]
            val coords = p.geometry.coordinates
            val lng = coords[0]
            val lat = coords[1]
            
            val x = lngX(lng).toFloat()
            val y = latY(lat).toFloat()
            
            data.addAll(listOf(
                x, y,
                Float.POSITIVE_INFINITY,
                i.toFloat(),
                -1f,
                1f
            ))
            
            if (options.reduce != null) {
                data.add(0f)
            }
        }

        var tree = createTree(data.toFloatArray())
        trees[options.maxZoom + 1] = tree

        if (options.log) {
            println("prepare ${points.size} points: ${System.currentTimeMillis() - startTime}ms")
        }

        for (z in options.maxZoom downTo options.minZoom) {
            val clusterTime = if (options.log) System.currentTimeMillis() else 0L
            
            tree = createTree(cluster(tree, z))
            trees[z] = tree
            
            if (options.log) {
                println("z$z: ${tree.numItems} clusters in ${System.currentTimeMillis() - clusterTime}ms")
            }
        }

        if (options.log) {
            println("total time: ${System.currentTimeMillis() - startTime}ms")
        }

        return this
    }

    fun getClusters(bbox: List<Double>, zoom: Int): List<Feature> {
        var minLng = ((bbox[0] + 180) % 360 + 360) % 360 - 180
        val minLat = maxOf(-90.0, minOf(90.0, bbox[1]))
        var maxLng = if (bbox[2] == 180.0) 180.0 else ((bbox[2] + 180) % 360 + 360) % 360 - 180
        val maxLat = maxOf(-90.0, minOf(90.0, bbox[3]))

        if (bbox[2] - bbox[0] >= 360) {
            minLng = -180.0
            maxLng = 180.0
        } else if (minLng > maxLng) {
            val easternHem = getClusters(listOf(minLng, minLat, 180.0, maxLat), zoom)
            val westernHem = getClusters(listOf(-180.0, minLat, maxLng, maxLat), zoom)
            return easternHem + westernHem
        }

        val tree = trees[limitZoom(zoom)]!!
        val ids = tree.range(
            lngX(minLng).toFloat(),
            latY(maxLat).toFloat(), 
            lngX(maxLng).toFloat(),
            latY(minLat).toFloat()
        )
        
        val data = tree.data!!
        val clusters = mutableListOf<Feature>()
        
        for (id in ids) {
            val k = stride * id
            if (data[k + OFFSET_NUM] > 1) {
                clusters.add(getClusterJSON(data, k))
            } else {
                clusters.add(points[data[k + OFFSET_ID].toInt()])
            }
        }
        
        return clusters
    }

    fun getChildren(clusterId: Int): List<Feature> {
        val originId = getOriginId(clusterId)
        val originZoom = getOriginZoom(clusterId)
        val errorMsg = "No cluster with the specified id."

        val tree = trees[originZoom] ?: throw IllegalArgumentException(errorMsg)
        val data = tree.data!!
        
        if (originId * stride >= data.size) throw IllegalArgumentException(errorMsg)

        val r = options.radius.toFloat() / (options.extent * (1 shl (originZoom - 1)))
        val x = data[originId * stride]
        val y = data[originId * stride + 1]
        val ids = tree.within(x, y, r)
        val children = mutableListOf<Feature>()
        
        for (id in ids) {
            val k = id * stride
            if (data[k + OFFSET_PARENT].toInt() == clusterId) {
                if (data[k + OFFSET_NUM] > 1) {
                    children.add(getClusterJSON(data, k))
                } else {
                    children.add(points[data[k + OFFSET_ID].toInt()])
                }
            }
        }

        if (children.isEmpty()) throw IllegalArgumentException(errorMsg)
        return children
    }

    fun getLeaves(clusterId: Int, limit: Int = 10, offset: Int = 0): List<Feature> {
        val leaves = mutableListOf<Feature>()
        appendLeaves(leaves, clusterId, limit, offset, 0)
        return leaves
    }

    fun getTile(z: Int, x: Int, y: Int): Tile? {
        val tree = trees[limitZoom(z)]!!
        val z2 = 1 shl z
        val extent = options.extent
        val radius = options.radius
        val p = radius.toFloat() / extent
        val top = (y - p) / z2
        val bottom = (y + 1 + p) / z2

        val tile = mutableListOf<TileFeature>()

        addTileFeatures(
            tree.range((x - p) / z2, top.toFloat(), (x + 1 + p) / z2, bottom.toFloat()),
            tree.data!!, x, y, z2, tile
        )

        if (x == 0) {
            addTileFeatures(
                tree.range((1 - p / z2).toFloat(), top.toFloat(), 1f, bottom.toFloat()),
                tree.data!!, z2, y, z2, tile
            )
        }
        if (x == z2 - 1) {
            addTileFeatures(
                tree.range(0f, top.toFloat(), (p / z2).toFloat(), bottom.toFloat()),
                tree.data!!, -1, y, z2, tile
            )
        }

        return if (tile.isNotEmpty()) Tile(tile) else null
    }

    fun getClusterExpansionZoom(clusterId: Int): Int {
        var expansionZoom = getOriginZoom(clusterId) - 1
        var currentClusterId = clusterId
        
        while (expansionZoom <= options.maxZoom) {
            val children = getChildren(currentClusterId)
            expansionZoom++
            if (children.size != 1) break
            val clusterIdProp = children[0].properties["cluster_id"]
            if (clusterIdProp is Int) {
                currentClusterId = clusterIdProp
            } else break
        }
        return expansionZoom
    }

    private fun appendLeaves(result: MutableList<Feature>, clusterId: Int, limit: Int, offset: Int, skipped: Int): Int {
        val children = getChildren(clusterId)
        var currentSkipped = skipped

        for (child in children) {
            val props = child.properties
            val cluster = props["cluster"] as? Boolean ?: false
            
            if (cluster) {
                val pointCount = props["point_count"] as? Int ?: 0
                if (currentSkipped + pointCount <= offset) {
                    currentSkipped += pointCount
                } else {
                    val childClusterId = props["cluster_id"] as? Int ?: continue
                    currentSkipped = appendLeaves(result, childClusterId, limit, offset, currentSkipped)
                }
            } else if (currentSkipped < offset) {
                currentSkipped++
            } else {
                result.add(child)
            }
            if (result.size == limit) break
        }

        return currentSkipped
    }

    private fun createTree(data: FloatArray): KDBush {
        val tree = KDBush(data.size / stride, options.nodeSize)
        for (i in data.indices step stride) {
            tree.add(data[i], data[i + 1])
        }
        tree.finish()
        tree.data = data
        return tree
    }

    private fun addTileFeatures(ids: List<Int>, data: FloatArray, x: Int, y: Int, z2: Int, tile: MutableList<TileFeature>) {
        val extent = options.extent
        
        for (i in ids) {
            val k = i * stride
            val isCluster = data[k + OFFSET_NUM] > 1

            val tags: Map<String, Any>
            val px: Float
            val py: Float

            if (isCluster) {
                tags = getClusterProperties(data, k)
                px = data[k]
                py = data[k + 1]
            } else {
                val p = points[data[k + OFFSET_ID].toInt()]
                tags = p.properties
                val coords = p.geometry.coordinates
                px = lngX(coords[0]).toFloat()
                py = latY(coords[1]).toFloat()
            }

            val f = TileFeature(
                type = 1,
                geometry = listOf(listOf(
                    round(extent * (px * z2 - x)).toInt(),
                    round(extent * (py * z2 - y)).toInt()
                )),
                tags = tags,
                id = if (isCluster || options.generateId) {
                    data[k + OFFSET_ID].toInt()
                } else {
                    points[data[k + OFFSET_ID].toInt()].id
                }
            )

            tile.add(f)
        }
    }

    private fun limitZoom(z: Int): Int {
        return maxOf(options.minZoom, minOf(floor(z.toDouble()).toInt(), options.maxZoom + 1))
    }

    private fun cluster(tree: KDBush, zoom: Int): FloatArray {
        val radius = options.radius
        val extent = options.extent
        val reduce = options.reduce
        val minPoints = options.minPoints
        
        val r = radius.toFloat() / (extent * (1 shl zoom))
        val data = tree.data!!
        val nextData = mutableListOf<Float>()

        var i = 0
        while (i < data.size) {
            if (data[i + OFFSET_ZOOM] <= zoom) {
                i += stride
                continue
            }
            data[i + OFFSET_ZOOM] = zoom.toFloat()

            val x = data[i]
            val y = data[i + 1]
            val neighborIds = tree.within(x, y, r)

            val numPointsOrigin = data[i + OFFSET_NUM].toInt()
            var numPoints = numPointsOrigin

            for (neighborId in neighborIds) {
                val k = neighborId * stride
                if (data[k + OFFSET_ZOOM] > zoom) {
                    numPoints += data[k + OFFSET_NUM].toInt()
                }
            }

            if (numPoints > numPointsOrigin && numPoints >= minPoints) {
                var wx = x * numPointsOrigin
                var wy = y * numPointsOrigin

                var clusterProperties: MutableMap<String, Any>? = null
                var clusterPropIndex = -1

                val id = ((i / stride) shl 5) + (zoom + 1) + points.size

                for (neighborId in neighborIds) {
                    val k = neighborId * stride

                    if (data[k + OFFSET_ZOOM] <= zoom) continue
                    data[k + OFFSET_ZOOM] = zoom.toFloat()

                    val numPoints2 = data[k + OFFSET_NUM].toInt()
                    wx += data[k] * numPoints2
                    wy += data[k + 1] * numPoints2

                    data[k + OFFSET_PARENT] = id.toFloat()

                    if (reduce != null) {
                        if (clusterProperties == null) {
                            clusterProperties = map(data, i, true).toMutableMap()
                            clusterPropIndex = clusterProps.size
                            clusterProps.add(clusterProperties)
                        }
                        reduce(clusterProperties, map(data, k))
                    }
                }

                data[i + OFFSET_PARENT] = id.toFloat()
                nextData.addAll(listOf(
                    wx / numPoints, wy / numPoints, Float.POSITIVE_INFINITY,
                    id.toFloat(), -1f, numPoints.toFloat()
                ))
                if (reduce != null) nextData.add(clusterPropIndex.toFloat())

            } else {
                for (j in 0 until stride) {
                    nextData.add(data[i + j])
                }

                if (numPoints > 1) {
                    for (neighborId in neighborIds) {
                        val k = neighborId * stride
                        if (data[k + OFFSET_ZOOM] <= zoom) continue
                        data[k + OFFSET_ZOOM] = zoom.toFloat()
                        for (j in 0 until stride) {
                            nextData.add(data[k + j])
                        }
                    }
                }
            }
            i += stride
        }

        return nextData.toFloatArray()
    }

    private fun getOriginId(clusterId: Int): Int {
        return (clusterId - points.size) shr 5
    }

    private fun getOriginZoom(clusterId: Int): Int {
        return (clusterId - points.size) % 32
    }

    private fun map(data: FloatArray, i: Int, clone: Boolean = false): Map<String, Any> {
        if (data[i + OFFSET_NUM] > 1) {
            val props = if (options.reduce != null) {
                clusterProps[data[i + OFFSET_PROP].toInt()]
            } else {
                emptyMap()
            }
            return if (clone) props.toMap() else props
        }
        val original = points[data[i + OFFSET_ID].toInt()].properties
        val result = options.map(original)
        return if (clone && result === original) result.toMap() else result
    }

    private fun getClusterJSON(data: FloatArray, i: Int): Feature {
        return Feature(
            type = "Feature",
            id = data[i + OFFSET_ID].toInt(),
            properties = getClusterProperties(data, i),
            geometry = Geometry(
                type = "Point",
                coordinates = listOf(xLng(data[i].toDouble()), yLat(data[i + 1].toDouble()))
            )
        )
    }

    private fun getClusterProperties(data: FloatArray, i: Int): Map<String, Any> {
        val count = data[i + OFFSET_NUM].toInt()
        val abbrev = when {
            count >= 10000 -> "${round(count / 1000.0).toInt()}k"
            count >= 1000 -> "${(round(count / 100.0) / 10)}k"
            else -> count.toString()
        }
        
        val properties: MutableMap<String, Any> = if (options.reduce != null) {
            val propIndex = data[i + OFFSET_PROP].toInt()
            if (propIndex == -1) mutableMapOf() else clusterProps[propIndex].toMutableMap()
        } else {
            mutableMapOf()
        }

        properties["cluster"] = true
        properties["cluster_id"] = data[i + OFFSET_ID].toInt()
        properties["point_count"] = count
        properties["point_count_abbreviated"] = abbrev

        return properties
    }

    private fun lngX(lng: Double): Double = lng / 360 + 0.5

    private fun latY(lat: Double): Double {
        val sin = sin(lat * PI / 180)
        val y = 0.5 - 0.25 * ln((1 + sin) / (1 - sin)) / PI
        return when {
            y < 0 -> 0.0
            y > 1 -> 1.0
            else -> y
        }
    }

    private fun xLng(x: Double): Double = (x - 0.5) * 360

    private fun yLat(y: Double): Double {
        val y2 = (180 - y * 360) * PI / 180
        return 360 * atan(exp(y2)) / PI - 90
    }
}