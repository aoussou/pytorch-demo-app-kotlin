package com.talisol.speechrecognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import com.talisol.speechrecognition.ui.theme.SpeechRecognitionTheme
import org.pytorch.LiteModuleLoader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.os.HandlerThread

import org.pytorch.Module
import org.pytorch.IValue

import org.pytorch.Tensor
import java.nio.FloatBuffer
import android.media.AudioRecord

import android.media.MediaRecorder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
class MainActivity : ComponentActivity() {

    private var permissionToRecordAccepted = false
    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        if (!permissionToRecordAccepted) finish()
    }

    private val TAG = MainActivity::class.java.name


    private var module: Module? = null
    protected val myScope = CoroutineScope(Dispatchers.IO)
//    private val mTextView: TextView? = null
//    private val mButton: Button? = null

    private val REQUEST_RECORD_AUDIO = 13
    private val AUDIO_LEN_IN_SECOND = 6
    private val SAMPLE_RATE = 16000
    private val RECORDING_LENGTH = SAMPLE_RATE * AUDIO_LEN_IN_SECOND

    private val LOG_TAG = MainActivity::class.java.simpleName

    private var mStart = 1

//    private val mTimerHandler: Handler? = null
//
//    private val mRunnable: Runnable = object : Runnable {
//        override fun run() {
//            mTimerHandler.postDelayed(this, 1000)
//            runOnUiThread {
//                mButton.setText(
//                    String.format(
//                        "Listening - %ds left",
//                        AUDIO_LEN_IN_SECOND - mStart
//                    )
//                )
//                mStart += 1
//            }
//        }
//    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        module = LiteModuleLoader.load(assetFilePath(applicationContext, "wav2vec2.ptl"))

        setContent {
            SpeechRecognitionTheme {

                var output by remember {
                    mutableStateOf("waiting for result")
                }

                // A surface container using the 'background' color from the theme
                Surface(color = MaterialTheme.colors.background) {

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Button(
                            onClick = {output = startSpeechRecognition()}
                        ){
                            Text(text="START RECORDING")
                        }

                        Text(text = output)

                    }

                }
            }
        }
    }

    fun startSpeechRecognition(): String {


        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val record = if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.

            return "bad1"

        }else{
            AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Audio Record can't initialize!")
            return "bad2"
        }
        record.startRecording()


        var shortsRead: Long = 0
        var recordingOffset = 0
        val audioBuffer = ShortArray(bufferSize / 2)
        val recordingBuffer = ShortArray(RECORDING_LENGTH)

        while (shortsRead < RECORDING_LENGTH) {
            val numberOfShort = record.read(audioBuffer, 0, audioBuffer.size)
            shortsRead += numberOfShort.toLong()
            System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, numberOfShort)
            recordingOffset += numberOfShort
        }

        record.stop()
        record.release()

        val floatInputBuffer = FloatArray(RECORDING_LENGTH)

        for (i in 0 until RECORDING_LENGTH) {
            floatInputBuffer[i] = recordingBuffer[i] / Short.MAX_VALUE.toFloat()
        }

        val result = recognize(floatInputBuffer)

        return result ?: "bad3"

    }

    private fun assetFilePath(context: Context, assetName: String): String? {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        try {
            context.assets.open(assetName).use { `is` ->
                FileOutputStream(file).use { os ->
                    val buffer = ByteArray(4 * 1024)
                    var read: Int
                    while (`is`.read(buffer).also { read = it } != -1) {
                        os.write(buffer, 0, read)
                    }
                    os.flush()
                }
                return file.absolutePath
            }
        } catch (e: IOException) {
            Log.e(TAG, assetName + ": " + e.getLocalizedMessage())
        }
        return null
    }


    private fun recognize(floatInputBuffer: FloatArray): String? {
        if (module == null) {
            module = LiteModuleLoader.load(assetFilePath(applicationContext, "wav2vec2.ptl"))
        }
        val wav2vecinput = DoubleArray(RECORDING_LENGTH)
        for (n in 0 until RECORDING_LENGTH) wav2vecinput[n] = floatInputBuffer[n].toDouble()
        val inTensorBuffer: FloatBuffer = Tensor.allocateFloatBuffer(RECORDING_LENGTH)
        for (`val` in wav2vecinput) inTensorBuffer.put(`val`.toFloat())
        val inTensor: Tensor =
            Tensor.fromBlob(inTensorBuffer, longArrayOf(1, RECORDING_LENGTH.toLong()))
        return module!!.forward(IValue.from(inTensor)).toStr()
    }




}


