package org.jire.overwatcheat.aimbot

import net.openhft.affinity.AffinityLock
import org.jire.overwatcheat.FastRandom
import org.jire.overwatcheat.Keyboard
import org.jire.overwatcheat.Mouse
import org.jire.overwatcheat.aimbot.AimBotState.aimData
import org.jire.overwatcheat.nativelib.User32Panama
import org.jire.overwatcheat.settings.Settings
import org.jire.overwatcheat.util.FastAbs
import org.jire.overwatcheat.util.PreciseSleeper
import java.awt.Robot
import java.awt.event.InputEvent
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureNanoTime

class AimBotThread(
    val captureWidth: Int, val captureHeight: Int,
    val captureCenterX: Int, val captureCenterY: Int,
    val preciseSleeper: PreciseSleeper,
    val cpuThreadAffinityIndex: Int,
    val aimMode: AimMode // 核心：判断是平滑还是瞬间甩枪
) : Thread("Aim Bot") {

    private val mouseId get() = Settings.mouseId
    private val flickPauseNanos get() = TimeUnit.MILLISECONDS.toNanos(Settings.flickPause)
    private val aimDurationNanos get() = (Settings.aimDurationMillis * 1_000_000).toLong()

    val random = FastRandom()
    private var robot: Robot? = null

    private var currentSpeedX = 0f
    private var currentSpeedY = 0f
    private var fractionalMoveX = 0f
    private var fractionalMoveY = 0f

    init {
        try {
            robot = Robot()
        } catch (e: Exception) {}
    }

    private fun isKeyPressed(vk: Int): Boolean {
        if (vk <= 0) return true // 如果没设热键，默认就是"已按下"
        return try {
            val user32Class = Class.forName("com.sun.jna.platform.win32.User32")
            val instance = user32Class.getField("INSTANCE").get(null)
            val method = user32Class.getMethod("GetAsyncKeyState", Int::class.javaPrimitiveType)
            val state = method.invoke(instance, vk) as Short
            (state.toInt() and 0x8000) != 0
        } catch (e: Throwable) {
            true // 报错说明环境有问题，为了不影响你用，直接返回 true
        }
    }

    override fun run() {
        priority = MAX_PRIORITY - 1
        val tlr = ThreadLocalRandom.current()
        var wasPressed = false
        val affinityLock: AffinityLock? =
            if (cpuThreadAffinityIndex >= 0
                && System.getProperty("os.arch") != "aarch64"
                && Runtime.getRuntime().availableProcessors() > 1
            ) AffinityLock.acquireLock(cpuThreadAffinityIndex)
            else null

        try {
            while (true) {
                val elapsed = measureNanoTime {
                    val aimKeyPressed = Keyboard.keyPressed(Settings.aimKey)
                    val autoShootKeyPressed = isKeyPressed(Settings.autoShootKey)
                    val currentAimData = aimData

                    // 目标消失或没按键时重置状态
                    if (currentAimData == 0L || (!aimKeyPressed && !autoShootKeyPressed)) {
                        currentSpeedX = 0f
                        currentSpeedY = 0f
                        fractionalMoveX = 0f
                        fractionalMoveY = 0f
                        if (!aimKeyPressed && !autoShootKeyPressed) aimData = 0L
                        return@measureNanoTime
                    }

                    wasPressed = aimKeyPressed
                    useAimData(currentAimData, aimKeyPressed, autoShootKeyPressed)
                }

                val sleepTimeMultiplier = max(Settings.aimDurationMultiplierMax, (Settings.aimDurationMultiplierBase + tlr.nextFloat()))
                val sleepTime = (aimDurationNanos * sleepTimeMultiplier).toLong() - elapsed
                if (sleepTime > 100_000) preciseSleeper.preciseSleep(sleepTime)
            }
        } finally {
            affinityLock?.release()
        }
    }

    private fun useAimData(currentAimData: Long, aimKeyPressed: Boolean, autoShootKeyPressed: Boolean) {
        val dX = calculateDelta(currentAimData, 48, Settings.aimMinTargetWidth, Settings.aimOffsetX, captureCenterX)
        val dY = calculateDelta(currentAimData, 16, Settings.aimMinTargetHeight, Settings.aimOffsetY, captureCenterY)

        val validTarget = (dX != Int.MAX_VALUE && dY != Int.MAX_VALUE)
        if (!validTarget) return

        // 1. 如果按了瞄准键（左/右键），执行位移
        if (aimKeyPressed) {
            performAim(dX, dY)
        }

        // 2. 如果开启了自动开火且按了热键（或没设热键），执行开火
        if (autoShootKeyPressed) {
            handleAutoShoot(dX, dY)
        }
    }

    private fun extractAimData(aimData: Long, shiftBits: Int) = (aimData ushr shiftBits) and 0xFFFFL
    private fun calculateOffset(size: Long, offset: Float) = (size / 2.0f * offset).toLong()
    private fun calculateAim(base: Long, offset: Long) = (base + offset).toInt()

    private fun calculateDelta(aimData: Long, shiftBitsBase: Int, minimumSize: Int, offset: Float, deltaSubtrahend: Int): Int {
        val low = extractAimData(aimData, shiftBitsBase)
        val high = extractAimData(aimData, shiftBitsBase - 16)
        val size = high - low
        if (size < minimumSize) return Int.MAX_VALUE

        val deltaOffset = calculateOffset(size, offset)
        val aimPos = calculateAim(low, deltaOffset)
        return aimPos - deltaSubtrahend
    }

    private fun performAim(dX: Int, dY: Int) {
        val targetSpeedX = dX.toFloat() / Settings.sensitivity
        val targetSpeedY = dY.toFloat() / Settings.sensitivity

        // 🎯 核心修复：如果是“瞬间甩枪”模式，忽略所有平滑，直接锁死 1.0！
        var activeSmoothing = if (aimMode.flicks) 1.0f else Settings.aimSmoothingFactor

        // 如果是平滑模式，靠近目标时才逐渐增强磁力
        if (!aimMode.flicks) {
            val distSq = dX * dX + dY * dY
            if (distSq < 1600) {
                activeSmoothing = max(activeSmoothing, 0.8f)
            }
        }

        currentSpeedX += (targetSpeedX - currentSpeedX) * activeSmoothing
        currentSpeedY += (targetSpeedY - currentSpeedY) * activeSmoothing

        fractionalMoveX += currentSpeedX
        fractionalMoveY += currentSpeedY

        val outX = fractionalMoveX.toInt()
        val outY = fractionalMoveY.toInt()

        fractionalMoveX -= outX
        fractionalMoveY -= outY

        val maxMove = Settings.aimMaxMovePixels
        val boundedX = max(-maxMove, min(maxMove, outX))
        val boundedY = max(-maxMove, min(maxMove, outY))

        if (boundedX != 0 || boundedY != 0) {
            Mouse.move(boundedX, boundedY, mouseId)
        }
    }

    private fun handleAutoShoot(distanceX: Int, distanceY: Int) {
        // 💡 优化判定：如果觉得自动开火不灵敏，请在 UI 调大 flick_shoot_pixels
        val isCentered = FastAbs(distanceX) <= Settings.flickPixels && FastAbs(distanceY) <= Settings.flickPixels

        if (Settings.autoShoot && isCentered) {
            val triggerKey = Settings.autoShootTriggerKey

            // 执行开火动作
            if (triggerKey == 1) {
                Mouse.click(mouseId)
            } else if (triggerKey == 2) {
                try {
                    robot?.mousePress(InputEvent.BUTTON3_DOWN_MASK)
                    Thread.sleep(15)
                    robot?.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
                } catch (e: Exception) {}
            } else {
                val scanCode = User32Panama.MapVirtualKeyA(triggerKey)
                Keyboard.pressKey(scanCode, Settings.keyboardId)
                Thread.sleep(15)
                Keyboard.releaseKey(scanCode, Settings.keyboardId)
            }

            aimData = 0L // 射击后清空目标，防止连续射击或“黏人”
            PreciseSleeper.YIELD.preciseSleep(flickPauseNanos)
        }
    }
}