/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wgf.classification.tm

import android.Manifest
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.hardware.Camera.PreviewCallback
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image.Plane
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.*
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.WindowManager
import android.widget.*
import android.widget.AdapterView.OnItemSelectedListener
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.wgf.classification.tm.env.ImageUtils
import com.wgf.classification.tm.env.Logger
import com.wgf.classification.tm.tflite.Classifier
import com.wgf.classification.tm.tflite.Classifier.Recognition

abstract class CameraActivity : AppCompatActivity(), OnImageAvailableListener, PreviewCallback, View.OnClickListener, OnItemSelectedListener {

    val TAG = CameraActivity::class.simpleName

    @JvmField
    protected var previewWidth = 0
    @JvmField
    protected var previewHeight = 0
    protected var luminanceStride = 0

    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null
    private var mUseCamera2API = false
    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null
    private var bottomSheetLayout: LinearLayout? = null
    private var gestureLayout: LinearLayout? = null
    private var sheetBehavior: BottomSheetBehavior<*>? = null
    private var plusImageView: ImageView? = null
    private var minusImageView: ImageView? = null
    private var modelSpinner: Spinner? = null
    private var deviceSpinner: Spinner? = null
    private var threadsTextView: TextView? = null
    private var model = Classifier.Model.FLOAT
    private var device = Classifier.Device.CPU
    private var numThreads = -1

    var recognitionTextView: TextView? = null
    var recognition1TextView: TextView? = null
    var recognition2TextView: TextView? = null
    var recognitionValueTextView: TextView? = null
    var recognition1ValueTextView: TextView? = null
    var recognition2ValueTextView: TextView? = null
    var frameValueTextView: TextView? = null
    var cropValueTextView: TextView? = null
    var cameraResolutionTextView: TextView? = null
    var rotationTextView: TextView? = null
    var inferenceTimeTextView: TextView? = null
    var bottomSheetArrowImageView: ImageView? = null
    var cameraSwitchBtn: ImageView? = null

    var mCameraId:String = ""
    var mCameraFacing:Boolean = false // false = back camera

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, ">> onCreate()")

        super.onCreate(null)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_camera)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayShowTitleEnabled(false)

        if (hasPermission()) {
            setFragment()
        } else {
            requestPermission()
        }

        threadsTextView = findViewById(R.id.threads)
        plusImageView = findViewById(R.id.plus)
        minusImageView = findViewById(R.id.minus)
        modelSpinner = findViewById(R.id.model_spinner)
        deviceSpinner = findViewById(R.id.device_spinner)
        bottomSheetLayout = findViewById(R.id.bottom_sheet_layout)
        gestureLayout = findViewById(R.id.gesture_layout)
        sheetBehavior = BottomSheetBehavior.from(bottomSheetLayout)
        bottomSheetArrowImageView = findViewById(R.id.bottom_sheet_arrow)

        val vto = gestureLayout!!.getViewTreeObserver()

        vto.addOnGlobalLayoutListener(
                object : OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
                            gestureLayout!!.getViewTreeObserver().removeGlobalOnLayoutListener(this)
                        } else {
                            gestureLayout!!.getViewTreeObserver().removeOnGlobalLayoutListener(this)
                        }
                        //                int width = bottomSheetLayout.getMeasuredWidth();
                        val height = gestureLayout!!.getMeasuredHeight()
                        sheetBehavior!!.setPeekHeight(height)
                    }
                })

        sheetBehavior!!.setHideable(false)
        sheetBehavior!!.setBottomSheetCallback(
                object : BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        when (newState) {
                            BottomSheetBehavior.STATE_HIDDEN -> {
                            }
                            BottomSheetBehavior.STATE_EXPANDED -> {
                                bottomSheetArrowImageView!!.setImageResource(R.drawable.icn_chevron_down)
                            }
                            BottomSheetBehavior.STATE_COLLAPSED -> {
                                bottomSheetArrowImageView!!.setImageResource(R.drawable.icn_chevron_up)
                            }
                            BottomSheetBehavior.STATE_DRAGGING -> {
                            }
                            BottomSheetBehavior.STATE_SETTLING -> bottomSheetArrowImageView!!.setImageResource(R.drawable.icn_chevron_up)
                        }
                    }

                    override fun onSlide(bottomSheet: View, slideOffset: Float) {}
                })

        recognitionTextView = findViewById(R.id.detected_item)
        recognitionValueTextView = findViewById(R.id.detected_item_value)
        recognition1TextView = findViewById(R.id.detected_item1)
        recognition1ValueTextView = findViewById(R.id.detected_item1_value)
        recognition2TextView = findViewById(R.id.detected_item2)
        recognition2ValueTextView = findViewById(R.id.detected_item2_value)
        frameValueTextView = findViewById(R.id.frame_info)
        cropValueTextView = findViewById(R.id.crop_info)
        cameraResolutionTextView = findViewById(R.id.view_info)
        rotationTextView = findViewById(R.id.rotation_info)
        inferenceTimeTextView = findViewById(R.id.inference_info)
        cameraSwitchBtn = findViewById(R.id.btn_camera_switch)

        modelSpinner!!.setOnItemSelectedListener(this)
        deviceSpinner!!.setOnItemSelectedListener(this)
        plusImageView!!.setOnClickListener(this)
        minusImageView!!.setOnClickListener(this)
        cameraSwitchBtn!!.setOnClickListener(this)

        model = Classifier.Model.valueOf(modelSpinner!!.getSelectedItem().toString().toUpperCase())
        device = Classifier.Device.valueOf(deviceSpinner!!.getSelectedItem().toString())
        numThreads = threadsTextView!!.getText().toString().trim { it <= ' ' }.toInt()
    }

    protected fun getRgbBytes(): IntArray? {
        imageConverter!!.run()
        return rgbBytes
    }

    protected val luminance: ByteArray?
        protected get() = yuvBytes[0]

    /** Callback for android.hardware.Camera API  */
    override fun onPreviewFrame(bytes: ByteArray, camera: Camera) {
        if (isProcessingFrame) {
            LOGGER.w("Dropping frame!")
            return
        }
        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgbBytes == null) {
                val previewSize = camera.parameters.previewSize
                previewHeight = previewSize.height
                previewWidth = previewSize.width
                rgbBytes = IntArray(previewWidth * previewHeight)
                onPreviewSizeChosen(Size(previewSize.width, previewSize.height), 90)
            }
        } catch (e: Exception) {
            LOGGER.e(e, "Exception!")
            return
        }
        isProcessingFrame = true
        yuvBytes[0] = bytes
        luminanceStride = previewWidth
        imageConverter = Runnable { ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes) }
        postInferenceCallback = Runnable {
            camera.addCallbackBuffer(bytes)
            isProcessingFrame = false
        }
        processImage()
    }

    /** Callback for Camera2 API  */
    override fun onImageAvailable(reader: ImageReader) {
        // We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }
        try {
            val image = reader.acquireLatestImage() ?: return
            if (isProcessingFrame) {
                image.close()
                return
            }
            isProcessingFrame = true
            Trace.beginSection("imageAvailable")
            val planes = image.planes
            fillBytes(planes, yuvBytes)
            luminanceStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride
            imageConverter = object : Runnable {
                override fun run() {
                    ImageUtils.convertYUV420ToARGB8888(
                            yuvBytes[0],
                            yuvBytes[1],
                            yuvBytes[2],
                            previewWidth,
                            previewHeight,
                            luminanceStride,
                            uvRowStride,
                            uvPixelStride,
                            rgbBytes)
                }
            }
            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }
            processImage()
        } catch (e: Exception) {
            LOGGER.e(e, "Exception!")
            Trace.endSection()
            return
        }
        Trace.endSection()
    }

    @Synchronized
    public override fun onStart() {
        Log.d(TAG, ">> onStart()")
        super.onStart()
    }

    @Synchronized
    public override fun onResume() {
        Log.d(TAG, ">> onResume()")
        super.onResume()

        handlerThread = HandlerThread("inference")
        handlerThread!!.start()
        handler = Handler(handlerThread!!.looper)
    }

    @Synchronized
    public override fun onPause() {
        LOGGER.d("onPause $this")
        handlerThread!!.quitSafely()
        try {
            handlerThread!!.join()
            handlerThread = null
            handler = null
        } catch (e: InterruptedException) {
            LOGGER.e(e, "Exception!")
        }
        super.onPause()
    }

    @Synchronized
    public override fun onStop() {
        Log.d(TAG, ">> onStop()")
        super.onStop()
    }

    @Synchronized
    public override fun onDestroy() {
        Log.d(TAG, ">> onDestroy()")
        super.onDestroy()
    }

    @Synchronized
    protected fun runInBackground(r: Runnable?) {
        if (handler != null) {
            handler!!.post(r)
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                setFragment()
            } else {
                requestPermission()
            }
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
                Toast.makeText(
                        this@CameraActivity,
                        "Camera permission is required for this demo",
                        Toast.LENGTH_LONG)
                        .show()
            }
            requestPermissions(arrayOf(PERMISSION_CAMERA), PERMISSIONS_REQUEST)
        }
    }

    // Returns true if the device supports the required hardware level, or better.
    private fun isHardwareLevelSupported(
            characteristics: CameraCharacteristics, requiredLevel: Int): Boolean {
        val deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        return if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            requiredLevel == deviceLevel
        } else requiredLevel <= deviceLevel
        // deviceLevel is not LEGACY, can use numerical sort
    }

    private fun chooseCamera(): String? {
        Log.d(TAG, ">> chooseCamera()")

        /*
        * CameraId
        *   public static final int LENS_FACING_FRONT = 0;
        *   public static final int LENS_FACING_BACK = 1;
            public static final int LENS_FACING_EXTERNAL = 2;
        * */
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                Log.d(TAG, ">> chooseCamera() - for")
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera_switch in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?: continue

                // Fallback to camera1 API for internal cameras that don't have full support.
                // This should help with legacy situations where using the camera2 API causes
                // distorted or otherwise broken previews.
                mUseCamera2API = (facing == CameraCharacteristics.LENS_FACING_FRONT ||
                        isHardwareLevelSupported(characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))

                Log.d(TAG, ">> Camera API lv2?: $mUseCamera2API")

                Log.d(TAG, ">> 111 chooseCamera(), cameraId = $cameraId")

                return cameraId
            }
        } catch (e: CameraAccessException) {
            LOGGER.e(e, "Not allowed to access camera_switch")
        }
        return null
    }

    protected fun setFragment() {
        Log.d(TAG, ">> setFragment()")

        mCameraId = chooseCamera().toString()
        Log.d(TAG, ">> setFragment(), mCameraId = $mCameraId")

        val fragment: Fragment
        if (mUseCamera2API) {
            Log.d(TAG, ">> setFragment() -  useCamera2API")

            val camera2Fragment = CameraConnectionFragment.newInstance(
                    CameraConnectionFragment.ConnectionCallback { size, rotation ->
                        previewHeight = size.height
                        previewWidth = size.width
                        onPreviewSizeChosen(size, rotation)
                    },
                    this,
                    layoutId,
                    desiredPreviewFrameSize)

            camera2Fragment.setCamera(mCameraId)

            fragment = camera2Fragment
        } else {
            Log.d(TAG, ">> setFragment() -  No useCamera2API")
            fragment = LegacyCameraConnectionFragment(this, layoutId, desiredPreviewFrameSize)
        }
        fragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }

    protected fun fillBytes(planes: Array<Plane>, yuvBytes: Array<ByteArray?>) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity())
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer[yuvBytes[i]]
        }
    }

    protected fun readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback!!.run()
        }
    }

    protected val screenOrientation: Int
        protected get() = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }

    @UiThread
    protected fun showResultsInBottomSheet(results: List<Recognition?>?) {
        if (results != null && results.size >= 2) {
            val recognition = results[0]
            if (recognition != null) {
                if (recognition.title != null) recognitionTextView!!.text = recognition.title.substring(2)
                if (recognition.confidence != null) recognitionValueTextView!!.text = String.format("%.2f", 100 * recognition.confidence) + "%"
            }
            val recognition1 = results[1]
            if (recognition1 != null) {
                if (recognition1.title != null) recognition1TextView!!.text = recognition1.title.substring(2)
                if (recognition1.confidence != null) recognition1ValueTextView!!.text = String.format("%.2f", 100 * recognition1.confidence) + "%"
            }
            if (results.size > 2) {
                val recognition2 = results[2]
                if (recognition2 != null) {
                    if (recognition2.title != null) recognition2TextView!!.text = recognition2.title.substring(2)
                    if (recognition2.confidence != null) recognition2ValueTextView!!.text = String.format("%.2f", 100 * recognition2.confidence) + "%"
                }
            }
        }
    }

    protected fun showFrameInfo(frameInfo: String?) {
        frameValueTextView!!.text = frameInfo
    }

    protected fun showCropInfo(cropInfo: String?) {
        cropValueTextView!!.text = cropInfo
    }

    protected fun showCameraResolution(cameraInfo: String?) {
        cameraResolutionTextView!!.text = cameraInfo
    }

    protected fun showRotationInfo(rotation: String?) {
        rotationTextView!!.text = rotation
    }

    protected fun showInference(inferenceTime: String?) {
        inferenceTimeTextView!!.text = inferenceTime
    }

    protected fun getModel(): Classifier.Model {
        return model
    }

    private fun setModel(model: Classifier.Model) {
        Log.d(TAG, ">> setModel()")

        if (this.model != model) {
            LOGGER.d("Updating  model: $model")
            this.model = model
            onInferenceConfigurationChanged()
        }
    }

    protected fun getDevice(): Classifier.Device {
        return device
    }

    private fun setDevice(device: Classifier.Device) {
        if (this.device != device) {
            LOGGER.d("Updating  device: $device")
            this.device = device
            val threadsEnabled = device == Classifier.Device.CPU
            plusImageView!!.isEnabled = threadsEnabled
            minusImageView!!.isEnabled = threadsEnabled
            threadsTextView!!.text = if (threadsEnabled) numThreads.toString() else "N/A"
            onInferenceConfigurationChanged()
        }
    }

    protected fun getNumThreads(): Int {
        return numThreads
    }

    private fun setNumThreads(numThreads: Int) {
        if (this.numThreads != numThreads) {
            LOGGER.d("Updating  numThreads: $numThreads")
            this.numThreads = numThreads
            onInferenceConfigurationChanged()
        }
    }

    protected abstract fun processImage()
    protected abstract fun onPreviewSizeChosen(size: Size?, rotation: Int)
    protected abstract val layoutId: Int
    protected abstract val desiredPreviewFrameSize: Size?
    protected abstract fun onInferenceConfigurationChanged()

    override fun onClick(v: View) {

        if (v.id == R.id.plus) {
            val threads = threadsTextView!!.text.toString().trim { it <= ' ' }
            var numThreads = threads.toInt()
            if (numThreads >= 9) return
            setNumThreads(++numThreads)
            threadsTextView!!.text = numThreads.toString()

        } else if (v.id == R.id.minus) {
            val threads = threadsTextView!!.text.toString().trim { it <= ' ' }
            var numThreads = threads.toInt()
            if (numThreads == 1) {
                return
            }
            setNumThreads(--numThreads)
            threadsTextView!!.text = numThreads.toString()

            //카메라 스위치 버튼 클릭 했을 때 코드
        } else if(v.id == R.id.btn_camera_switch) {
            //camera switch button
            // If currently on FRONT camera (0 = CameraCharacteristics.LENS_FACING_FRONT)
            if(mCameraId.equals(CameraCharacteristics.LENS_FACING_BACK)) {
                Log.d(TAG, ">> onClick, LENS_FACING_BACK mCameraId = $mCameraId")
                mCameraId = "0"
            } else if(mCameraId.equals(CameraCharacteristics.LENS_FACING_FRONT)){
                Log.d(TAG, ">> onClick,  LENS_FACING_FRONT mCameraId = $mCameraId")
                mCameraId = "1"
            }

            val camera2Fragment = CameraConnectionFragment.newInstance(
                    CameraConnectionFragment.ConnectionCallback { size, rotation ->
                        previewHeight = size.height
                        previewWidth = size.width
                        onPreviewSizeChosen(size, rotation)
                    },
                    this,
                    layoutId,
                    desiredPreviewFrameSize)

            Log.d(TAG, ">> onClick, mCameraId = $mCameraId")

            camera2Fragment.setCamera(mCameraId)

            val fragment: Fragment
            fragment = camera2Fragment

            fragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
        }
    }

    override fun onItemSelected(parent: AdapterView<*>, view: View, pos: Int, id: Long) {
        Log.d(TAG, ">> onItemSelected()")

        if (parent === modelSpinner) {
            setModel(Classifier.Model.valueOf(parent.getItemAtPosition(pos).toString().toUpperCase()))
        } else if (parent === deviceSpinner) {
            setDevice(Classifier.Device.valueOf(parent.getItemAtPosition(pos).toString()))
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
        // Do nothing.
    }

    companion object {
        private val LOGGER = Logger()
        private const val PERMISSIONS_REQUEST = 1
        private const val PERMISSION_CAMERA = Manifest.permission.CAMERA
    }
}
