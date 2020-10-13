package com.wgf.classification.tm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nbsp.materialfilepicker.MaterialFilePicker
import com.nbsp.materialfilepicker.ui.FilePickerActivity
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), View.OnClickListener {

    val TAG = MainActivity::class.simpleName

    var modelpath: Button? = null
    var labelpath: Button? = null
    var start_button: LinearLayout? = null
    var Select_tflite: Button? = null
    var Select_label: Button? = null
    private var tflitePath = ""
    private var labelPath = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, ">> onCreate()")

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modelpath = findViewById(R.id.tflite)
        labelpath = findViewById(R.id.labels)
        start_button = findViewById(R.id.start)

        Select_tflite = findViewById(R.id.tflite)
        Select_label = findViewById(R.id.labels)


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }

        modelpath?.setOnClickListener(this)
        labelpath?.setOnClickListener(this)
        start_button?.setOnClickListener(this)

        //TODO - 1) 어플 시작될 때 토스트 띄우기

    }

    //TODO - 2) onStart() 함수 만들기

    //TODO - 3) onResume() 함수 만들

    override fun onClick(view: View?) {
        var id = view?.id

        when(id) {
            //
            R.id.tflite -> {
                MaterialFilePicker()
                        .withActivity(this)
                        .withRequestCode(1)
                        .withFilter(Pattern.compile(".*\\.tflite$")) // Filtering files and directories by file name using regexp
                        .start()
            }
            R.id.labels -> {
                MaterialFilePicker()
                        .withActivity(this)
                        .withRequestCode(2)
                        .withFilter(Pattern.compile(".*\\.txt$")) // Filtering files and directories by file name using regexp
                        .start()
            }
            R.id.start -> {
                if (labelPath == "") {
                    Toast.makeText(this, "Label File not selected", Toast.LENGTH_SHORT).show()
                }
                if (tflitePath == "") {
                    Toast.makeText(this, ".tflite File not selected", Toast.LENGTH_SHORT).show()
                }
                if (!(labelPath == "" || tflitePath == "")) {
                    // TODO 4) 현재 Acitivty(MainActivity)에서 ClassifierActivity로 화면 전환하는 코드 넣기!
                    // nextIntent 변수
                    val nextIntent = Intent(this, ClassifierActivity::class.java)
                    nextIntent.putExtra("TflitePath", tflitePath)
                    nextIntent.putExtra("LabelPath", labelPath)
                    startActivity(nextIntent)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            tflitePath = data!!.getStringExtra(FilePickerActivity.RESULT_FILE_PATH)
            Toast.makeText(this, tflitePath, Toast.LENGTH_SHORT).show()

            // Do anything with file
            if (tflitePath != "") {
                Select_tflite!!.background = ContextCompat.getDrawable(this, R.color.done)
            }
        }

        if (requestCode == 2 && resultCode == Activity.RESULT_OK) {
            labelPath = data!!.getStringExtra(FilePickerActivity.RESULT_FILE_PATH)
            Toast.makeText(this, labelPath, Toast.LENGTH_SHORT).show()

            // Do anything with file
            if (labelPath != "") {
                Select_label!!.background = ContextCompat.getDrawable(this, R.color.done)
            }
        }
    }

    //TODO 5) onStop() 함수 만들기

    //TODO 6) onDestory() 함수 만들기
}