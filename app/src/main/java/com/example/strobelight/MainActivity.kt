package com.example.strobelight

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var torchOn = false

    @Volatile private var running = false
    private var strobeThread: Thread? = null

    @Volatile private var freqHz = 10.0
    @Volatile private var dutyPercent = 50

    private var screenFlashEnabled = false
    @Volatile private var screenOn = false

    private lateinit var flashView: View
    private lateinit var btnStart: Button
    private lateinit var tvFreq: TextView
    private lateinit var tvDuty: TextView
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = findFlashCameraId()
        buildUi()
        updateLabels()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    private fun findFlashCameraId(): String? = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) { null }

    private fun buildUi() {
        flashView = View(this)
        flashView.setBackgroundColor(Color.BLACK)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 64)
            setBackgroundColor(0xAA000000.toInt())
        }

        val title = TextView(this).apply {
            text = "频闪手电筒  Strobe Light"
            textSize = 22f
            setTextColor(Color.WHITE)
        }

        tvFreq = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        tvDuty = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }

        val sbFreq = SeekBar(this).apply {
            max = 100
            progress = 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    freqHz = p.toDouble(); updateLabels()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val sbDuty = SeekBar(this).apply {
            max = 100
            progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    dutyPercent = p; updateLabels()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val cbScreen = CheckBox(this).apply {
            text = "屏幕频闪（相机不够快时开启）"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c ->
                screenFlashEnabled = c
                if (!c) flashScreen(false)
            }
        }

        btnStart = Button(this).apply {
            text = "开始"
            setOnClickListener { toggle() }
        }

        panel.addView(title)
        panel.addView(tvFreq)
        panel.addView(sbFreq)
        panel.addView(tvDuty)
        panel.addView(sbDuty)
        panel.addView(cbScreen)
        panel.addView(btnStart)

        val root = FrameLayout(this)
        root.addView(flashView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun updateLabels() {
        tvFreq.text = if (freqHz <= 0.0) "频率: 0 Hz（常亮模式）" else "频率: ${freqHz.toInt()} Hz"
        tvDuty.text = "占空比（亮/暗）: $dutyPercent%"
    }

    private fun toggle() {
        if (running) stopStrobe() else startStrobe()
    }

    private fun startStrobe() {
        if (cameraId == null) {
            toast("此设备未检测到闪光灯")
            return
        }
        acquireWakeLock()
        running = true
        btnStart.text = "停止"

        if (freqHz <= 0.0) {          // 常亮模式：占空比>0 即亮
            setTorch(dutyPercent > 0)
            return
        }
        if (dutyPercent <= 0) {       // 亮度 0%：保持关闭
            setTorch(false)
            return
        }
        strobeThread = Thread(strobeLoop).apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun stopStrobe() {
        running = false
        strobeThread?.interrupt()
        strobeThread = null
        setTorch(false)
        flashScreen(false)
        releaseWakeLock()
        btnStart.text = "开始"
    }

    private val strobeLoop = Runnable {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        while (running) {
            val periodMs = 1000.0 / max(freqHz, 0.1)
            val onMs = periodMs * dutyPercent / 100.0
            val offMs = periodMs - onMs

            setTorch(true); flashScreen(true)
            preciseSleep(onMs)
            if (!running) break
            setTorch(false); flashScreen(false)
            preciseSleep(offMs)
        }
    }

    /** 毫秒级睡眠，最后 1.5ms 自旋，减少定时抖动 */
    private fun preciseSleep(ms: Double) {
        if (ms <= 0.0) return
        val end = System.nanoTime() + (ms * 1_000_000.0).toLong()
        var remaining = end - System.nanoTime()
        while (remaining > 1_500_000L && running) {
            try { Thread.sleep(remaining / 1_000_000L) }
            catch (e: InterruptedException) { return }
            remaining = end - System.nanoTime()
        }
        while (System.nanoTime() < end && running) { /* spin */ }
    }

    private fun setTorch(on: Boolean) {
        if (torchOn == on) return
        val id = cameraId ?: return
        try {
            cameraManager.setTorchMode(id, on)
            torchOn = on
        } catch (e: Exception) { /* 部分设备高频调用会抛异常，忽略 */ }
    }

    private fun flashScreen(on: Boolean) {
        val target = on && screenFlashEnabled
        if (screenOn == target) return
        screenOn = target
        runOnUiThread { flashView.setBackgroundColor(if (target) Color.WHITE else Color.BLACK) }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "strobe:wakelock").apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.acquire(10 * 60 * 1000L) // 10 分钟保险
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        stopStrobe() // 切后台自动停止，安全
    }
}
