/* Copyright 2022 Lin Yi. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================
*/

/** 本应用主要对 Tensorflow Lite Pose Estimation 示例项目的 MainActivity.kt
 *  文件进行了重写，示例项目中其余文件除了包名调整外基本无改动，原版权归
 *  The Tensorflow Authors 所有 */

package lyi.linyi.posemon

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Process
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lyi.linyi.posemon.camera.CameraSource
import lyi.linyi.posemon.data.Camera
import lyi.linyi.posemon.data.Device
import lyi.linyi.posemon.ml.ModelType
import lyi.linyi.posemon.ml.MoveNet
import lyi.linyi.posemon.ml.PoseClassifier

class MainActivity : AppCompatActivity() {
    companion object {
        private const val FRAGMENT_DIALOG = "dialog"
    }

    /** 为视频画面创建一个 SurfaceView */
    private lateinit var surfaceView: SurfaceView

    /** 修改默认计算设备：CPU、GPU、NNAPI（AI加速器） */
    private var device = Device.CPU
    /** 修改默认摄像头：FRONT、BACK */
    private var selectedCamera = Camera.BACK

    /** 定义几个计数器 */
    private var standardplankCounter = 0
    private var standardsquatCounter = 0
    private var shallowsquatCounter = 0
    private var forerakesquatCounter = 0
    private var highheadplankCounter = 0
    private var highhiplankCounter = 0
    private var standCounter = 0
    private var elseCounter = 0
    private var missingCounter = 0

    /** 定义一个弹窗显示器 */
    private lateinit var dialogLayout: View

    /** 定义一个历史姿态寄存器 */
    private var poseRegister = "standard"

    /** 设置一个用来显示 Debug 信息的 TextView */
    private lateinit var tvDebug: TextView

    /** 设置一个用来显示当前坐姿状态的 ImageView */
    private lateinit var ivStatus: ImageView

    private lateinit var tvFPS: TextView
    private lateinit var tvScore: TextView
    private lateinit var spnDevice: Spinner
    private lateinit var spnCamera: Spinner
    private lateinit var spnPoseSelected: Spinner // 选择识别姿势

    private var cameraSource: CameraSource? = null
    private var isClassifyPose = true
    private var poseSelected = "squat"
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                /** 得到用户相机授权后，程序开始运行 */
                openCamera()
            } else {
                /** 提示用户“未获得相机权限，应用无法运行” */
                ErrorDialog.newInstance(getString(R.string.tfe_pe_request_permission))
                    .show(supportFragmentManager, FRAGMENT_DIALOG)
            }
        }

    private var changeDeviceListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            changeDevice(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
            /** 如果用户未选择运算设备，使用默认设备进行计算 */
        }
    }

    private var changeCameraListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p0: AdapterView<*>?, view: View?, direction: Int, id: Long) {
            changeCamera(direction)
        }

        override fun onNothingSelected(p0: AdapterView<*>?) {
            /** 如果用户未选择摄像头，使用默认摄像头进行拍摄 */
        }
    }

    private var changePoseListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p0: AdapterView<*>?, view: View?, pose: Int, id: Long) {
            changePoseSelected(pose)
        }

        override fun onNothingSelected(p0: AdapterView<*>?) {
            /** 如果用户未选择识别姿势，使用默认姿势进行识别 */
        }
    }
    private var handler = Handler() // 用于更新 UI
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        handler = object : Handler(mainLooper) {
            override fun handleMessage(msg: Message) {
                super.handleMessage(msg)
                when (msg.what) {
                    100 -> ivStatus.setImageResource(R.drawable.plank_standard_suspect)
                    101 -> ivStatus.setImageResource(R.drawable.plank_standard_confirm)
                    102 -> ivStatus.setImageResource(R.drawable.plank_highhead_suspect)
                    103 -> ivStatus.setImageResource(R.drawable.plank_highhead_confirm)
                    104 -> ivStatus.setImageResource(R.drawable.plank_highhip_suspect)
                    105 -> ivStatus.setImageResource(R.drawable.plank_highhip_confirm)
                    200 -> ivStatus.setImageResource(R.drawable.squat_standard_suspect)
                    201 -> ivStatus.setImageResource(R.drawable.squat_standard_confirm)
                    202 -> ivStatus.setImageResource(R.drawable.squat_forerake_suspect)
                    203 -> ivStatus.setImageResource(R.drawable.squat_forerake_confirm)
                    204 -> ivStatus.setImageResource(R.drawable.squat_shallow_suspect)
                    205 -> ivStatus.setImageResource(R.drawable.squat_shallow_confirm)
                    50 -> ivStatus.setImageResource(R.drawable.stand)
                    51 -> ivStatus.setImageResource(R.drawable.no_target)
                    52 -> ivStatus.setImageResource(R.drawable.else_confirm)


                }
            }
        }

        /** 程序运行时保持屏幕常亮 */
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tvScore = findViewById(R.id.tvScore)

        /** 用来显示 Debug 信息 */
        tvDebug = findViewById(R.id.tvDebug)

        /** 用来显示当前坐姿状态 */
        ivStatus = findViewById(R.id.ivStatus)

        tvFPS = findViewById(R.id.tvFps)
        spnDevice = findViewById(R.id.spnDevice)
        spnCamera = findViewById(R.id.spnCamera)
        spnPoseSelected = findViewById(R.id.spnPoseSelected)
        surfaceView = findViewById(R.id.surfaceView)
        initSpinner()
        if (!isCameraPermissionGranted()) {
            requestPermission()
        }

        /**在第一次运行此app时发出弹窗，提示每个动作的最佳角度*/
        dialogLayout = findViewById(R.id.dialogLayout)

        val closeButton: Button = findViewById(R.id.closeButton)
        closeButton.setOnClickListener {
            hideDialogWithAnimation()
        }

        // 延迟一秒后显示弹窗
        handler.postDelayed({
            showDialogWithAnimation()
        }, 2000)
    }

    private fun showDialogWithAnimation() {
        val fadeInAnimation = AlphaAnimation(0f, 1f)
        fadeInAnimation.duration = 500
        dialogLayout.startAnimation(fadeInAnimation)
        dialogLayout.visibility = View.VISIBLE
    }

    private fun hideDialogWithAnimation() {
        val fadeOutAnimation = AlphaAnimation(1f, 0f)
        fadeOutAnimation.duration = 200
        fadeOutAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}

            override fun onAnimationEnd(animation: Animation) {
                dialogLayout.visibility = View.GONE
            }

            override fun onAnimationRepeat(animation: Animation) {}
        })
        dialogLayout.startAnimation(fadeOutAnimation)
    }


    override fun onDestroy() {
        super.onDestroy()

        // 移除延迟执行的任务，以避免内存泄漏
        handler.removeCallbacksAndMessages(null)
    }

    private fun showDialog() {
        dialogLayout.visibility = View.VISIBLE
    }

    private fun hideDialog() {
        dialogLayout.visibility = View.GONE
    }

    override fun onStart() {
        super.onStart()
        openCamera()
    }

    override fun onResume() {
        cameraSource?.resume()
        super.onResume()
    }

    override fun onPause() {
        cameraSource?.close()
        cameraSource = null
        super.onPause()
    }

    /** 检查相机权限是否有授权 */
    private fun isCameraPermissionGranted(): Boolean {
        return checkPermission(
            Manifest.permission.CAMERA,
            Process.myPid(),
            Process.myUid()
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openCamera() {
        /** 音频播放 */
//        val crosslegPlayer = MediaPlayer.create(this, R.raw.crossleg)
//        val forwardheadPlayer = MediaPlayer.create(this, R.raw.forwardhead)
//        val standardPlayer = MediaPlayer.create(this, R.raw.standard)
//        var crosslegPlayerFlag = true
//        var forwardheadPlayerFlag = true
//        var standardPlayerFlag = true


        if (isCameraPermissionGranted()) {
            if (cameraSource == null) {
                cameraSource =
                    CameraSource(surfaceView, selectedCamera, object : CameraSource.CameraSourceListener {
                        override fun onFPSListener(fps: Int) {

                            /** 解释一下，tfe_pe_tv 的意思：tensorflow example、pose estimation、text view */
                            tvFPS.text = getString(R.string.tfe_pe_tv_fps, fps)
                        }

                        /** 对检测结果进行处理 */
                        override fun onDetectedInfo(
                            personScore: Float?,
                            poseLabels: List<Pair<String, Float>>?
                        ) {
                            tvScore.text = getString(R.string.tfe_pe_tv_score, personScore ?: 0f)

                            /** 分析目标姿态，给出提示 */
                            if (poseLabels != null && personScore != null && personScore > 0.5 ) { //置信分数阈值
                                missingCounter = 0
                                val presortedLabels = poseLabels.sortedByDescending { it.second }

                                // 根据选择姿势移除其他labels
                                if (poseSelected.equals("squat")){
                                    val labelsToRemove = listOf("plank_standard", "plank_HighHead", "plank_HighHip")

                                    val sortedLabels = presortedLabels.filterNot { labelsToRemove.contains(it.first) }

                                    when (sortedLabels[0].first) {
                                        "squat_standard" -> {
                                            elseCounter = 0
                                            standardplankCounter = 0
                                            highheadplankCounter = 0
                                            missingCounter = 0
                                            highhiplankCounter = 0
                                            shallowsquatCounter = 0
                                            forerakesquatCounter = 0
                                            standCounter = 0

                                            if (poseRegister == "squat_standard") {
                                                standardsquatCounter++
                                            }
                                            poseRegister = "squat_standard"

                                            /** 显示当前姿势状态：标准深蹲 */
                                            if (standardsquatCounter > 30) {

                                                /** 播放提示音 */
//                                            if (crosslegPlayerFlag) {
//                                                crosslegPlayer.start()
//                                            }
//                                            standardPlayerFlag = true
//                                            crosslegPlayerFlag = false
//                                            forwardheadPlayerFlag = true
                                                //ivStatus.setImageResource(R.drawable.squat_standard_confirm)
                                                handler!!.sendEmptyMessage(201)
                                            } else if (standardsquatCounter > 15) {
                                                // ivStatus.setImageResource(R.drawable.squat_standard_suspect)
                                                handler!!.sendEmptyMessage(200)
                                            }

                                            /** 显示 Debug 信息 */
                                            tvDebug.text = getString(R.string.tfe_pe_tv_debug, "${sortedLabels[0].first} $standardsquatCounter")
                                        }
                                        "squat_shallow" -> {
                                            elseCounter = 0
                                            standardsquatCounter = 0
                                            standardplankCounter = 0
                                            missingCounter = 0
                                            highheadplankCounter = 0
                                            highhiplankCounter = 0
                                            forerakesquatCounter = 0
                                            standCounter = 0

                                            if (poseRegister == "squat_Shallow") {
                                                shallowsquatCounter++
                                            }
                                            poseRegister = "squat_Shallow"

                                            /** 显示当前姿势状态：深蹲过浅 */
                                            if (shallowsquatCounter > 30) {
                                                handler!!.sendEmptyMessage(205)
                                            }
                                            else if (shallowsquatCounter > 15) {
                                                handler!!.sendEmptyMessage(204)
                                            }
                                            tvDebug.text = getString(R.string.tfe_pe_tv_debug, "${sortedLabels[0].first} $shallowsquatCounter")
                                        }
                                        "squat_forerake" -> {
                                            elseCounter = 0
                                            standardsquatCounter = 0
                                            standardplankCounter = 0
                                            missingCounter = 0
                                            highheadplankCounter = 0
                                            highhiplankCounter = 0
                                            shallowsquatCounter = 0
                                            standCounter = 0

                                            if (poseRegister == "squat_forerake") {
                                                forerakesquatCounter++
                                            }
                                            poseRegister = "squat_forerake"

                                            /** 显示当前姿势状态：深蹲前倾 */
                                            if (forerakesquatCounter > 30) {
                                                handler!!.sendEmptyMessage(203)
                                            }
                                            else if (forerakesquatCounter > 15) {
                                                handler!!.sendEmptyMessage(202)
                                            }
                                            tvDebug.text = getString(R.string.tfe_pe_tv_debug, "${sortedLabels[0].first} $forerakesquatCounter")
                                        }
                                        "stand"->{
                                            elseCounter = 0
                                            standardsquatCounter = 0
                                            standardplankCounter = 0
                                            missingCounter = 0
                                            highheadplankCounter = 0
                                            highhiplankCounter = 0
                                            shallowsquatCounter = 0
                                            forerakesquatCounter = 0

                                            if (poseRegister == "stand") {
                                                standCounter++
                                            }
                                            poseRegister = "stand"

                                            /** 显示当前姿势状态：站立*/
                                            if (standCounter > 30) {
                                                handler!!.sendEmptyMessage(50)
                                            }
                                            tvDebug.text = getString(R.string.tfe_pe_tv_debug, "${sortedLabels[0].first} $standCounter")
                                        }
//                                        else -> {
//                                            standardplankCounter = 0
//                                            standardsquatCounter = 0
//                                            highheadplankCounter = 0
//                                            highhiplankCounter = 0
//                                            missingCounter = 0
//                                            shallowsquatCounter = 0
//                                            forerakesquatCounter = 0
//                                            standCounter = 0
//
//
//                                            if (poseRegister == "else") {
//                                                elseCounter++
//                                            }
//                                            poseRegister = "else"
//
//                                            /** 显示当前姿势状态：其他*/
//                                            if (elseCounter > 30) {
//
//                                                /** 播放提示音：坐姿标准 */
////                                            if (standardPlayerFlag) {
////                                                standardPlayer.start()
////                                            }
////                                            standardPlayerFlag = false
////                                            crosslegPlayerFlag = true
////                                            forwardheadPlayerFlag = true
//
//                                                //ivStatus.setImageResource(R.drawable.standardstanding)
//                                                handler!!.sendEmptyMessage(52)
//                                            }
//
//                                            /** 显示 Debug 信息 */
//                                            tvDebug.text = getString(R.string.tfe_pe_tv_debug, "${sortedLabels[0].first} $elseCounter")
//                                        }
                                    }


                                }
                                else if (poseSelected.equals("plank")){
                                    val labelsToRemove = listOf("squat_standard", "squat_shallow", "squat_forerake")
                                    val sortedLabels = presortedLabels.filterNot { labelsToRemove.contains(it.first) }

                                    when (sortedLabels[0].first) {
                                        "plank_standard" -> {
                                            standardsquatCounter = 0
                                            elseCounter = 0
                                            highheadplankCounter = 0
                                            highhiplankCounter = 0
                                            shallowsquatCounter = 0
                                            forerakesquatCounter = 0
                                            standCounter = 0
                                            missingCounter = 0

                                            if (poseRegister == "plank_standard") {
                                                standardplankCounter++
                                            }
                                            poseRegister = "plank_standard"

                                            /** 显示当前姿势状态：标准平板支撑 */
                                            if (standardplankCounter > 30) {

                                                /** 播放提示音 */
//                                            if (forwardheadPlayerFlag) {
//                                                forwardheadPlayer.start()
//                                            }
//                                            standardPlayerFlag = true
//                                            crosslegPlayerFlag = true
//                                            forwardheadPlayerFlag = false

                                                //ivStatus.setImageResource(R.drawable.plank_standard_confirm)
                                                handler!!.sendEmptyMessage(101)
                                            } else if (standardplankCounter > 15) {
                                                //ivStatus.setImageResource(R.drawable.plank_standard_suspect)\
                                                handler!!.sendEmptyMessage(100)
                                            }

                                            /** 显示 Debug 信息 */
                                            tvDebug.text = getString(R.string.tfe_pe_tv_debug, "${sortedLabels[0].first} $standardplankCounter")
                                        }
                                        "plank_HighHead" -> {
                                            elseCounter = 0
                                            standardsquatCounter = 0
                                            standardplankCounter = 0
                                            missingCounter = 0
                                            highhiplankCounter = 0
                                            shallowsquatCounter = 0
                                            forerakesquatCounter = 0
                                            standCounter = 0

                                            if (poseRegister == "plank_HighHead") {
                                                highheadplankCounter++
                                            }
                                            poseRegister = "plank_HighHead"

                                            /** 显示当前姿势状态：平板支撑头部过高 */
                                            if (highheadplankCounter > 30) {

                                                /** 播放提示音 */
//                                            if (crosslegPlayerFlag) {
//                                                crosslegPlayer.start()
//                                            }
//                                            standardPlayerFlag = true
//                                            crosslegPlayerFlag = false
//                                            forwardheadPlayerFlag = true
                                                //ivStatus.setImageResource(R.drawable.plank_highhead_confirm)
                                                handler!!.sendEmptyMessage(103)
                                            } else if (highheadplankCounter > 15) {
                                                //ivStatus.setImageResource(R.drawable.plank_highhead_suspect)
                                                handler!!.sendEmptyMessage(102)
                                            }

                                            /** 显示 Debug 信息 */
                                            tvDebug.text = getString(R.string.tfe_pe_tv_debug, "${sortedLabels[0].first} $highheadplankCounter")
                                        }
                                        "plank_HighHip" -> {
                                            elseCounter = 0
                                            standardsquatCounter = 0
                                            standardplankCounter = 0
                                            missingCounter = 0
                                            highheadplankCounter = 0
                                            shallowsquatCounter = 0
                                            forerakesquatCounter = 0
                                            standCounter = 0

                                            if (poseRegister == "plank_HighHip") {
                                                highhiplankCounter++
                                            }
                                            poseRegister = "plank_HighHip"

                                            /** 显示当前姿势状态：平板支撑臀部过高 */
                                            if (highhiplankCounter > 30) {


                                                /** 播放提示音 */
//                                            if (crosslegPlayerFlag) {
//                                                crosslegPlayer.start()
//                                            }
//                                            standardPlayerFlag = true
//                                            crosslegPlayerFlag = false
//                                            forwardheadPlayerFlag = true
                                                //ivStatus.setImageResource(R.drawable.plank_highhead_confirm)
                                                handler!!.sendEmptyMessage(105)
                                            } else if (highhiplankCounter > 15) {
                                                //ivStatus.setImageResource(R.drawable.plank_highhead_suspect)
                                                handler!!.sendEmptyMessage(104)
                                            }

                                            /** 显示 Debug 信息 */
                                            tvDebug.text = getString(R.string.tfe_pe_tv_debug, "${sortedLabels[0].first} $highhiplankCounter")
                                        }
                                        "stand"->{
                                            elseCounter = 0
                                            standardsquatCounter = 0
                                            standardplankCounter = 0
                                            missingCounter = 0
                                            highheadplankCounter = 0
                                            highhiplankCounter = 0
                                            shallowsquatCounter = 0
                                            forerakesquatCounter = 0

                                            if (poseRegister == "stand") {
                                                standCounter++
                                            }
                                            poseRegister = "stand"

                                            /** 显示当前姿势状态：站立*/
                                            if (standCounter > 30) {
                                                handler!!.sendEmptyMessage(50)
                                            }
                                            tvDebug.text = getString(R.string.tfe_pe_tv_debug, "${sortedLabels[0].first} $standCounter")
                                        }
//                                        else -> {
//                                            standardplankCounter = 0
//                                            standardsquatCounter = 0
//                                            highheadplankCounter = 0
//                                            highhiplankCounter = 0
//                                            missingCounter = 0
//                                            shallowsquatCounter = 0
//                                            forerakesquatCounter = 0
//                                            standCounter = 0
//
//
//                                            if (poseRegister == "else") {
//                                                elseCounter++
//                                            }
//                                            poseRegister = "else"
//
//                                            /** 显示当前姿势状态：其他*/
//                                            if (elseCounter > 30) {
//
//                                                /** 播放提示音：坐姿标准 */
////                                            if (standardPlayerFlag) {
////                                                standardPlayer.start()
////                                            }
////                                            standardPlayerFlag = false
////                                            crosslegPlayerFlag = true
////                                            forwardheadPlayerFlag = true
//
//                                                //ivStatus.setImageResource(R.drawable.standardstanding)
//                                                handler!!.sendEmptyMessage(52)
//                                            }
//
//                                            /** 显示 Debug 信息 */
//                                            tvDebug.text = getString(R.string.tfe_pe_tv_debug, "${sortedLabels[0].first} $elseCounter")
//                                        }
                                    }


                                }


                            }
                            else {
                                forerakesquatCounter = 0
                                shallowsquatCounter = 0
                                elseCounter = 0
                                standCounter = 0
                                standardplankCounter = 0
                                standardsquatCounter = 0
                                highheadplankCounter = 0
                                highhiplankCounter = 0
                                missingCounter++

                                if (missingCounter > 30) {
                                    //ivStatus.setImageResource(R.drawable.no_target)
                                    handler!!.sendEmptyMessage(51)
                                }
                                /** 显示 Debug 信息 */
                                tvDebug.text = getString(R.string.tfe_pe_tv_debug, "missing $missingCounter")
                            }
                        }
                    }).apply {
                        prepareCamera()
                    }
                isPoseClassifier()
                lifecycleScope.launch(Dispatchers.Main) {
                    cameraSource?.initCamera()
                }
            }
            createPoseEstimator()
        }
    }

    private fun isPoseClassifier() {
        cameraSource?.setClassifier(if (isClassifyPose) PoseClassifier.create(this) else null)
    }

    /** 初始化运算设备选项菜单（CPU、GPU、NNAPI） */
    private fun initSpinner() {
        ArrayAdapter.createFromResource(
            this,
            R.array.tfe_pe_device_name, android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            spnDevice.adapter = adapter
            spnDevice.onItemSelectedListener = changeDeviceListener
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.tfe_pe_camera_name, android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            spnCamera.adapter = adapter
            spnCamera.onItemSelectedListener = changeCameraListener
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.tfe_pe_select_action, android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spnPoseSelected.adapter = adapter
            spnPoseSelected.onItemSelectedListener = changePoseListener
        }

    }

    /** 在程序运行过程中切换运算设备 */
    private fun changeDevice(position: Int) {
        val targetDevice = when (position) {
            0 -> Device.CPU
            1 -> Device.GPU
            else -> Device.NNAPI
        }
        if (device == targetDevice) return
        device = targetDevice
        createPoseEstimator()
    }

    /** 在程序运行过程中切换摄像头 */
    private fun changeCamera(direaction: Int) {
        val targetCamera = when (direaction) {
            0 -> Camera.BACK
            else -> Camera.FRONT
        }
        if (selectedCamera == targetCamera) return
        selectedCamera = targetCamera

        cameraSource?.close()
        cameraSource = null
        openCamera()
    }

    /** 在运行过程中切换识别姿势 */
    private fun changePoseSelected(pose: Int) {
        if (pose == 0){
            poseSelected = "squat"
        }else if (pose == 1){
            poseSelected = "plank"
        }

    }

    private fun createPoseEstimator() {
        val poseDetector = MoveNet.create(this, device, ModelType.Thunder)
        poseDetector.let { detector ->
            cameraSource?.setDetector(detector)
        }
    }

    private fun requestPermission() {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) -> {
                openCamera()
            }
            else -> {
                requestPermissionLauncher.launch(
                    Manifest.permission.CAMERA
                )
            }
        }
    }

    /** 显示报错信息 */
    class ErrorDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            AlertDialog.Builder(activity)
                .setMessage(requireArguments().getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    // pass
                }
                .create()

        companion object {

            @JvmStatic
            private val ARG_MESSAGE = "message"

            @JvmStatic
            fun newInstance(message: String): ErrorDialog = ErrorDialog().apply {
                arguments = Bundle().apply { putString(ARG_MESSAGE, message) }
            }
        }
    }
}

