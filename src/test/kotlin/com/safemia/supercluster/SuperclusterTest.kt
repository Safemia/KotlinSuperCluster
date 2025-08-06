package com.safemia.supercluster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class SuperclusterTest {

    private fun createTestPoints(): List<Feature> {
        return listOf(
            Feature(
                geometry = Geometry("Point", listOf(-122.4194, 37.7749)),
                properties = mapOf("name" to "San Francisco")
            ),
            Feature(
                geometry = Geometry("Point", listOf(-122.4294, 37.7849)),
                properties = mapOf("name" to "Near SF")
            ),
            Feature(
                geometry = Geometry("Point", listOf(-74.0059, 40.7128)),
                properties = mapOf("name" to "New York")
            ),
            Feature(
                geometry = Geometry("Point", listOf(-74.0159, 40.7228)),
                properties = mapOf("name" to "Near NYC")
            )
        )
    }

    @Test
    fun testBasicClustering() {
        val points = createTestPoints()
        val index = Supercluster(SuperclusterOptions(radius = 40, maxZoom = 16))
        index.load(points)

        val clusters = index.getClusters(listOf(-180.0, -85.0, 180.0, 85.0), 2.0)
        assertTrue(clusters.isNotEmpty(), "Should return clusters")
        println("Found ${clusters.size} clusters at zoom level 2")
    }

    @Test
    fun testSpecificBoundingBox() {
        val points = createTestPoints()
        val index = Supercluster(SuperclusterOptions(radius = 40, maxZoom = 16))
        index.load(points)

        // Query around San Francisco area
        val sfClusters = index.getClusters(listOf(-123.0, 37.0, -122.0, 38.0), 10.0)
        assertTrue(sfClusters.isNotEmpty(), "Should find points near SF")
        println("Found ${sfClusters.size} points/clusters near SF")
        
        // Query around New York area  
        val nycClusters = index.getClusters(listOf(-75.0, 40.0, -73.0, 41.0), 10.0)
        assertTrue(nycClusters.isNotEmpty(), "Should find points near NYC")
        println("Found ${nycClusters.size} points/clusters near NYC")
    }

    @Test
    fun testClusterProperties() {
        val points = createTestPoints()
        val index = Supercluster(SuperclusterOptions(radius = 200, maxZoom = 16))
        index.load(points)

        val clusters = index.getClusters(listOf(-180.0, -85.0, 180.0, 85.0), 2.0)
        
        for (cluster in clusters) {
            val isCluster = cluster.properties["cluster"] as? Boolean ?: false
            if (isCluster) {
                val pointCount = cluster.properties["point_count"] as? Int
                assertNotNull(pointCount, "Cluster should have point_count")
                assertTrue(pointCount > 1, "Cluster should contain multiple points")
                
                val clusterId = cluster.properties["cluster_id"] as? Int
                assertNotNull(clusterId, "Cluster should have cluster_id")
                
                println("Cluster with $pointCount points")
            } else {
                val name = cluster.properties["name"] as? String
                assertNotNull(name, "Point should have name property")
                println("Individual point: $name")
            }
        }
    }

    @Test
    fun testGetChildren() {
        val points = createTestPoints()
        val index = Supercluster(SuperclusterOptions(radius = 200, maxZoom = 16))
        index.load(points)

        val clusters = index.getClusters(listOf(-180.0, -85.0, 180.0, 85.0), 2.0)
        
        for (cluster in clusters) {
            val isCluster = cluster.properties["cluster"] as? Boolean ?: false
            if (isCluster) {
                val clusterId = cluster.properties["cluster_id"] as? Int
                if (clusterId != null) {
                    val children = index.getChildren(clusterId)
                    assertTrue(children.isNotEmpty(), "Cluster should have children")
                    println("Cluster $clusterId has ${children.size} children")
                }
            }
        }
    }

    @Test
    fun testGetLeaves() {
        val points = createTestPoints()
        val index = Supercluster(SuperclusterOptions(radius = 200, maxZoom = 16))
        index.load(points)

        val clusters = index.getClusters(listOf(-180.0, -85.0, 180.0, 85.0), 2.0)
        
        for (cluster in clusters) {
            val isCluster = cluster.properties["cluster"] as? Boolean ?: false
            if (isCluster) {
                val clusterId = cluster.properties["cluster_id"] as? Int
                if (clusterId != null) {
                    val leaves = index.getLeaves(clusterId, 10, 0)
                    assertTrue(leaves.isNotEmpty(), "Cluster should have leaves")
                    println("Cluster $clusterId has ${leaves.size} leaves")
                }
            }
        }
    }

    @Test
    fun testTileGeneration() {
        val points = createTestPoints()
        val index = Supercluster(SuperclusterOptions(radius = 40, maxZoom = 16))
        index.load(points)

        val tile = index.getTile(2.0, 0, 1)
        if (tile != null) {
            assertTrue(tile.features.isNotEmpty(), "Tile should contain features")
            println("Tile contains ${tile.features.size} features")
        }
    }

    @Test
    fun testDoubleZoomParameter() {
        val points = createTestPoints()
        val index = Supercluster(SuperclusterOptions(radius = 40, maxZoom = 16))
        index.load(points)

        // Test with fractional zoom level
        val clusters25 = index.getClusters(listOf(-180.0, -85.0, 180.0, 85.0), 2.5)
        val clusters2 = index.getClusters(listOf(-180.0, -85.0, 180.0, 85.0), 2.0)
        
        // Both should work and return the same results (since zoom is floored internally)
        assertTrue(clusters25.isNotEmpty(), "Should return clusters for fractional zoom")
        assertEquals(clusters2.size, clusters25.size, "Fractional zoom should be floored to integer")
        
        println("Found ${clusters25.size} clusters at zoom level 2.5")
    }

    @Test
    fun testMapReduceOptions() {
        val points = listOf(
            Feature(
                geometry = Geometry("Point", listOf(-122.4194, 37.7749)),
                properties = mapOf("name" to "SF", "population" to 883305)
            ),
            Feature(
                geometry = Geometry("Point", listOf(-122.4294, 37.7849)),
                properties = mapOf("name" to "Near SF", "population" to 50000)
            )
        )

        val index = Supercluster(SuperclusterOptions(
            radius = 200,
            maxZoom = 16,
            map = { props -> mapOf("sum" to (props["population"] as? Int ?: 0)) },
            reduce = { accumulated, props -> 
                val currentSum = accumulated["sum"] as? Int ?: 0
                val addSum = props["sum"] as? Int ?: 0
                accumulated["sum"] = currentSum + addSum
            }
        ))
        index.load(points)

        val clusters = index.getClusters(listOf(-180.0, -85.0, 180.0, 85.0), 2.0)
        
        for (cluster in clusters) {
            val isCluster = cluster.properties["cluster"] as? Boolean ?: false
            if (isCluster) {
                val sum = cluster.properties["sum"] as? Int
                assertNotNull(sum, "Cluster should have aggregated sum")
                println("Cluster population sum: $sum")
            }
        }
    }
}