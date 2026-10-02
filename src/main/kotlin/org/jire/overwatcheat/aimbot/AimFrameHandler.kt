package org.jire.overwatcheat.aimbot

import org.bytedeco.javacv.Frame
import org.jire.overwatcheat.framegrab.FrameHandler
import java.nio.ByteBuffer
import kotlin.math.max

class AimFrameHandler(val colorMatcher: AimColorMatcher) : FrameHandler {

    private val topCropRatio = 0.12
    // 💡 优化 1：稍微加大合并距离，防止一个敌人被拆成多个色块导致乱抢
    private val clusterDistanceThreshold = 35
    private val minClusterSize = 10

    // 🎯 核心变更：目标锁定主权
    private var lockedTargetID = -1
    private var lastLockedX = -1
    private var lastLockedY = -1
    private var lockedMemoryFrames = 0

    class Cluster(val id: Int, startX: Int, startY: Int) {
        var xLow = startX
        var xHigh = startX
        var yLow = startY
        var yHigh = startY

        fun add(x: Int, y: Int) {
            if (x < xLow) xLow = x
            if (x > xHigh) xHigh = x
            if (y < yLow) yLow = y
            if (y > yHigh) yHigh = y
        }

        fun isClose(x: Int, y: Int, threshold: Int): Boolean {
            val dx = if (x < xLow) xLow - x else if (x > xHigh) x - xHigh else 0
            val dy = if (y < yLow) yLow - y else if (y > yHigh) y - yHigh else 0
            return dx <= threshold && dy <= threshold
        }

        val centerX: Int get() = xLow + (xHigh - xLow) / 2
        val centerY: Int get() = yLow + (yHigh - yLow) / 2
        val width: Int get() = xHigh - xLow
        val height: Int get() = yHigh - yLow
    }

    override fun handle(frame: Frame) {
        val frameWidth = frame.imageWidth
        val frameHeight = frame.imageHeight
        val screenCenterX = frameWidth / 2
        val screenCenterY = frameHeight / 2

        for (image in frame.image) {
            val data = image as ByteBuffer
            val clusters = mutableListOf<Cluster>()
            var clusterIdCounter = 0

            // 1. 扫描与聚类
            for (x in 0 until frameWidth) {
                for (y in 0 until frameHeight) {
                    val dataIndex = (frameWidth * y + x) * 3
                    if (!colorMatches(data, dataIndex)) continue

                    var cluster = clusters.find { it.isClose(x, y, clusterDistanceThreshold) }
                    if (cluster != null) {
                        cluster.add(x, y)
                    } else {
                        clusters.add(Cluster(clusterIdCounter++, x, y))
                    }
                }
            }

            // 2. 目标锁定逻辑
            var bestCluster: Cluster? = null

            // 💡 优化 2：主权优先。如果之前锁定了目标，先在当前簇里找“继承者”
            if (lastLockedX != -1 && lastLockedY != -1) {
                bestCluster = clusters.filter {
                    it.width >= minClusterSize && it.width <= it.height * 1.5
                }.minByOrNull {
                    val dx = it.centerX - lastLockedX
                    val dy = it.centerY - lastLockedY
                    dx * dx + dy * dy
                }?.takeIf {
                    val dx = it.centerX - lastLockedX
                    val dy = it.centerY - lastLockedY
                    dx * dx + dy * dy < 3600 // 必须在上次位置的 60 像素内
                }
            }

            // 如果没找到继承者，再按距离准星最近重新寻找
            if (bestCluster == null) {
                bestCluster = clusters.filter {
                    it.width >= minClusterSize && it.width <= it.height * 1.5
                }.minByOrNull {
                    val dx = it.centerX - screenCenterX
                    val dy = it.centerY - screenCenterY
                    dx * dx + dy * dy
                }
            }

            // 3. 更新记忆与下发
            if (bestCluster != null) {
                lastLockedX = bestCluster.centerX
                lastLockedY = bestCluster.centerY
                lockedMemoryFrames = 15 // 增加记忆时长

                val totalHeight = bestCluster.yHigh - bestCluster.yLow
                val finalYLow = bestCluster.yLow + (totalHeight * topCropRatio).toInt()

                AimBotState.aimData = (bestCluster.xLow.toLong() shl 48) or
                        (bestCluster.xHigh.toLong() shl 32) or
                        (finalYLow.toLong() shl 16) or
                        bestCluster.yHigh.toLong()
            } else {
                if (lockedMemoryFrames > 0) {
                    lockedMemoryFrames--
                } else {
                    lastLockedX = -1
                    lastLockedY = -1
                    AimBotState.aimData = 0L
                }
            }
        }
    }

    private fun pixelRGB(data: ByteBuffer, dataIndex: Int): Int {
        val blue = data[dataIndex].toInt() and 0xFF
        val green = data[dataIndex + 1].toInt() and 0xFF
        val red = data[dataIndex + 2].toInt() and 0xFF
        return (red shl 16) or (green shl 8) or blue
    }

    private fun colorMatches(data: ByteBuffer, dataIndex: Int) = colorMatcher.matchSet.contains(pixelRGB(data, dataIndex))
}