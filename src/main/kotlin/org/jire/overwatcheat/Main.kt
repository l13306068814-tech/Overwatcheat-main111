/*
 * Free, open-source undetected color cheat for Overwatch!
 * Copyright (C) 2017  Thomas G. Nappo
 */

package org.jire.overwatcheat

import net.openhft.chronicle.core.Jvm
import org.bytedeco.javacv.FFmpegLogCallback
// 💡 关键修复：导入 aimbot 文件夹下的所有类（包含 AimBotThread, AimFrameHandler, ToggleUIThread 等）
import org.jire.overwatcheat.aimbot.*
import org.jire.overwatcheat.framegrab.FrameGrabber
import org.jire.overwatcheat.framegrab.FrameGrabberThread
import org.jire.overwatcheat.framegrab.FrameHandler
import org.jire.overwatcheat.nativelib.Kernel32
import org.jire.overwatcheat.settings.Settings
import org.jire.overwatcheat.util.PreciseSleeper
import java.io.File
import kotlin.concurrent.thread

object Main {

    init {
        Jvm.init()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Kernel32.SetPriorityClass(Kernel32.GetCurrentProcess(), Kernel32.HIGH_PRIORITY_CLASS)
        FFmpegLogCallback.set()

        val aimColorMatcher = AimColorMatcher()
        aimColorMatcher.initializeMatchSet()

        // 热重载监控线程
        thread(isDaemon = true, name = "ConfigWatcher") {
            val configFile = File("overwatcheat.cfg")
            var lastModified = configFile.lastModified()

            while (true) {
                Thread.sleep(500)
                val currentModified = configFile.lastModified()

                if (currentModified > lastModified) {
                    lastModified = currentModified
                    try {
                        Settings.load(configFile)
                        aimColorMatcher.initializeMatchSet()
                    } catch (e: Exception) {}
                }
            }
        }

        // 读取 UI 参数
        val captureWidth = Settings.boxWidth
        val captureHeight = Settings.boxHeight

        val captureOffsetX = (Screen.WIDTH - captureWidth) / 2
        val captureOffsetY = (Screen.HEIGHT - captureHeight) / 2

        val captureCenterX = captureWidth / 2
        val captureCenterY = captureHeight / 2

        val frameHandler: FrameHandler = AimFrameHandler(aimColorMatcher)

        val frameGrabber = FrameGrabber(
            Settings.windowTitleSearch,
            Settings.fps,
            captureWidth,
            captureHeight,
            captureOffsetX,
            captureOffsetY
        )

        val frameGrabberThread = FrameGrabberThread(frameGrabber, frameHandler)

        // ✅ 修复：现在编译器可以正确识别 ToggleUIThread 了
        val toggleUIThread = ToggleUIThread(Settings.keyboardId, *Settings.toggleKeyCodes)

        val preciseSleeper = PreciseSleeper[Settings.aimPreciseSleeperType] ?: PreciseSleeper.YIELD
        val aimMode = AimMode[Settings.aimMode] ?: AimMode.TRACKING

        // ✅ 修复：现在编译器可以正确识别 AimBotThread 了
        val aimBotThread = AimBotThread(
            captureWidth, captureHeight,
            captureCenterX, captureCenterY,
            preciseSleeper,
            Settings.aimCpuThreadAffinityIndex,
            aimMode
        )

        frameGrabberThread.start()
        toggleUIThread.start()
        aimBotThread.start()
    }
}