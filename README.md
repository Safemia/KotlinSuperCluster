# Supercluster for Kotlin

A very fast Kotlin library for geospatial point clustering.

This is a Kotlin port of the original [JavaScript Supercluster library](https://github.com/mapbox/supercluster) by Vladimir Agafonkin.

## Usage

```kotlin
import com.safemia.supercluster.*

// Create some GeoJSON points
val points = listOf(
    Feature(
        geometry = Geometry("Point", listOf(-122.4194, 37.7749)),
        properties = mapOf("name" to "San Francisco")
    ),
    Feature(
        geometry = Geometry("Point", listOf(-74.0059, 40.7128)),
        properties = mapOf("name" to "New York")
    )
)

// Create index with options
val index = Supercluster(SuperclusterOptions(radius = 40, maxZoom = 16))
index.load(points)

// Get clusters for a bounding box at zoom level 2
val clusters = index.getClusters(listOf(-180.0, -85.0, 180.0, 85.0), 2)
```

## Installation

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.safemia:supercluster:1.0.0")
}
```

### Gradle (Groovy)

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.safemia:supercluster:1.0.0'
}
```

## API

### SuperclusterOptions

| Option     | Type     | Default | Description                                                       |
|------------|----------|---------|-------------------------------------------------------------------|
| minZoom    | Int      | 0       | Minimum zoom level at which clusters are generated.               |
| maxZoom    | Int      | 16      | Maximum zoom level at which clusters are generated.               |
| minPoints  | Int      | 2       | Minimum number of points to form a cluster.                       |
| radius     | Int      | 40      | Cluster radius, in pixels.                                        |
| extent     | Int      | 512     | (Tiles) Tile extent. Radius is calculated relative to this value. |
| nodeSize   | Int      | 64      | Size of the KD-tree leaf node. Affects performance.               |
| log        | Boolean  | false   | Whether timing info should be logged.                             |
| generateId | Boolean  | false   | Whether to generate ids for input features in vector tiles.       |

### Methods

#### `load(points: List<Feature>): Supercluster`

Loads a list of GeoJSON Feature objects. Each feature's `geometry` must be a GeoJSON Point. Once loaded, the index is immutable.

#### `getClusters(bbox: List<Double>, zoom: Int): List<Feature>`

For the given `bbox` list (`[westLng, southLat, eastLng, northLat]`) and integer `zoom`, returns a list of clusters and points as GeoJSON Feature objects.

#### `getTile(z: Int, x: Int, y: Int): Tile?`

For a given zoom and x/y coordinates, returns a tile object with cluster/point features, or null if the tile is empty.

#### `getChildren(clusterId: Int): List<Feature>`

Returns the children of a cluster (on the next zoom level) given its id (`cluster_id` value from feature properties).

#### `getLeaves(clusterId: Int, limit: Int = 10, offset: Int = 0): List<Feature>`

Returns all the points of a cluster (given its `cluster_id`), with pagination support:
`limit` is the number of points to return, and `offset` is the amount of points to skip (for pagination).

#### `getClusterExpansionZoom(clusterId: Int): Int`

Returns the zoom on which the cluster expands into several children (useful for "click to zoom" feature) given the cluster's `cluster_id`.

### Property map/reduce options

Supercluster supports property aggregation with the following two options:

- `map`: a function that returns cluster properties corresponding to a single point.
- `reduce`: a reduce function that merges properties of two clusters into one.

Example of setting up a `sum` cluster property that accumulates the sum of `population` property values:

```kotlin
val index = Supercluster(SuperclusterOptions(
    map = { props -> mapOf("sum" to (props["population"] as? Int ?: 0)) },
    reduce = { accumulated, props -> 
        val currentSum = accumulated["sum"] as? Int ?: 0
        val addSum = props["sum"] as? Int ?: 0
        accumulated["sum"] = currentSum + addSum
    }
))
```

## Building

```bash
cd kotlin
./gradlew build
```

## Testing

```bash
cd kotlin  
./gradlew test
```

## License

ISC License (same as the original JavaScript library)