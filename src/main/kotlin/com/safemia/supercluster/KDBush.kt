package com.safemia.supercluster

import kotlin.math.*

class KDBush(numItems: Int, nodeSize: Int = 64) {
    private val nodeSize = maxOf(2, minOf(65535, nodeSize))
    val numItems = numItems
    private val ids = IntArray(numItems)
    private val coords = FloatArray(numItems * 2)
    private var numAdded = 0
    private var numNodes = 0
    
    var data: FloatArray? = null
        set(value) {
            field = value
        }

    fun add(x: Float, y: Float): Int {
        val index = numAdded++
        ids[index] = index
        coords[2 * index] = x
        coords[2 * index + 1] = y
        return index
    }

    fun finish() {
        if (numAdded != numItems) {
            throw IllegalStateException("Added ${numAdded} items out of expected ${numItems}")
        }

        if (numItems <= nodeSize + 1) return

        val width = ceil(log2(numItems.toDouble())).toInt()
        val minX = coords[0]
        val minY = coords[1]
        val maxX = coords[0]
        val maxY = coords[1]

        // Calculate bounds
        var actualMinX = minX
        var actualMinY = minY
        var actualMaxX = maxX
        var actualMaxY = maxY
        
        for (i in 0 until numItems) {
            val x = coords[2 * i]
            val y = coords[2 * i + 1]
            if (x < actualMinX) actualMinX = x
            if (y < actualMinY) actualMinY = y
            if (x > actualMaxX) actualMaxX = x
            if (y > actualMaxY) actualMaxY = y
        }

        val width2 = actualMaxX - actualMinX
        val height2 = actualMaxY - actualMinY
        val hilbertMax = (1 shl width) - 1

        // Map item centers to Hilbert coordinate
        val hilbertValues = IntArray(numItems)
        for (i in 0 until numItems) {
            val x = floor(hilbertMax * (coords[2 * i] - actualMinX) / width2).toInt()
            val y = floor(hilbertMax * (coords[2 * i + 1] - actualMinY) / height2).toInt()
            hilbertValues[i] = hilbert(x, y)
        }

        // Sort items by their Hilbert value
        sort(hilbertValues, coords, ids, 0, numItems - 1)

        // Generate nodes
        numNodes = 0
        buildIndex(0, numItems - 1, 0)
    }

    fun range(minX: Float, minY: Float, maxX: Float, maxY: Float): List<Int> {
        val result = mutableListOf<Int>()
        if (numItems <= nodeSize + 1) {
            for (i in 0 until numItems) {
                val x = coords[2 * i]
                val y = coords[2 * i + 1]
                if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                    result.add(ids[i])
                }
            }
        } else {
            rangeRecursive(0, minX, minY, maxX, maxY, result)
        }
        return result
    }

    fun within(qx: Float, qy: Float, r: Float): List<Int> {
        val result = mutableListOf<Int>()
        val r2 = r * r
        
        if (numItems <= nodeSize + 1) {
            for (i in 0 until numItems) {
                val x = coords[2 * i]
                val y = coords[2 * i + 1]
                val dx = x - qx
                val dy = y - qy
                if (dx * dx + dy * dy <= r2) {
                    result.add(ids[i])
                }
            }
        } else {
            withinRecursive(0, qx, qy, r2, result)
        }
        return result
    }

    private fun buildIndex(left: Int, right: Int, depth: Int): Int {
        if (right - left <= nodeSize) return depth

        val m = (left + right) shr 1
        
        val leftChild = buildIndex(left, m, depth + 1)
        val rightChild = buildIndex(m + 1, right, depth + 1)
        
        return maxOf(leftChild, rightChild)
    }

    private fun rangeRecursive(nodeIndex: Int, minX: Float, minY: Float, maxX: Float, maxY: Float, result: MutableList<Int>) {
        val left = nodeIndex * nodeSize
        val right = minOf(left + nodeSize, numItems)
        
        for (i in left until right) {
            val x = coords[2 * i]
            val y = coords[2 * i + 1]
            if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                result.add(ids[i])
            }
        }
    }

    private fun withinRecursive(nodeIndex: Int, qx: Float, qy: Float, r2: Float, result: MutableList<Int>) {
        val left = nodeIndex * nodeSize
        val right = minOf(left + nodeSize, numItems)
        
        for (i in left until right) {
            val x = coords[2 * i]
            val y = coords[2 * i + 1]
            val dx = x - qx
            val dy = y - qy
            if (dx * dx + dy * dy <= r2) {
                result.add(ids[i])
            }
        }
    }

    private fun hilbert(x: Int, y: Int): Int {
        var a = x xor y
        var b = 0xFFFF xor a
        var c = 0xFFFF xor (x or y)
        var d = x and (y xor 0xFFFF)

        var A = a or (b shr 1)
        var B = (a shr 1) xor a
        var C = ((c shr 1) xor (b and (d shr 1))) xor c
        var D = ((a and (c shr 1)) xor (d shr 1)) xor d

        a = A; b = B; c = C; d = D
        A = (a and (a shr 2)) xor (b and (b shr 2))
        B = (a and (b shr 2)) xor (b and ((a xor b) shr 2))
        C = ((c xor (c shr 2)) xor (d xor (d shr 2))) and 0x3333
        D = ((a xor (c shr 2)) xor (b xor (d shr 2))) and 0x3333

        a = A; b = B; c = C; d = D
        A = (a and (a shr 4)) xor (b and (b shr 4))
        B = (a and (b shr 4)) xor (b and ((a xor b) shr 4))
        C = ((c xor (c shr 4)) xor (d xor (d shr 4))) and 0x0F0F
        D = ((a xor (c shr 4)) xor (b xor (d shr 4))) and 0x0F0F

        a = A; b = B; c = C; d = D
        C = ((c xor (c shr 8)) xor (d xor (d shr 8))) and 0x00FF
        D = ((a xor (c shr 8)) xor (b xor (d shr 8))) and 0x00FF

        a = A; b = B; c = C; d = D
        
        return ((d and 0xFF) shl 8) or (c and 0xFF)
    }

    private fun sort(values: IntArray, coords: FloatArray, ids: IntArray, left: Int, right: Int) {
        if (left >= right) return

        val m = (left + right) shr 1
        val pivot = values[m]

        var i = left - 1
        var j = right + 1

        while (i < j) {
            do { i++ } while (values[i] < pivot)
            do { j-- } while (values[j] > pivot)
            if (i < j) {
                swap(values, coords, ids, i, j)
            }
        }

        sort(values, coords, ids, left, j)
        sort(values, coords, ids, j + 1, right)
    }

    private fun swap(values: IntArray, coords: FloatArray, ids: IntArray, i: Int, j: Int) {
        val tempValue = values[i]
        values[i] = values[j]
        values[j] = tempValue

        val tempId = ids[i]
        ids[i] = ids[j]
        ids[j] = tempId

        val tempX = coords[2 * i]
        val tempY = coords[2 * i + 1]
        coords[2 * i] = coords[2 * j]
        coords[2 * i + 1] = coords[2 * j + 1]
        coords[2 * j] = tempX
        coords[2 * j + 1] = tempY
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)
}