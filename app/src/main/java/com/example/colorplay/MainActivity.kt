package com.example.colorplay

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.Image
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.VideoCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture.withOutput
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.PermissionChecker
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import com.example.colorplay.databinding.ActivityMainBinding
import java.lang.Exception
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


typealias LumaListener=(luma:Double) -> Unit


class MainActivity : AppCompatActivity() {

    private var photoCapture:ImageCapture?=null

    private var videoCapture:androidx.camera.video.VideoCapture<Recorder>?=null

    private var recording:Recording?=null

    private lateinit var executor:ExecutorService
    companion object{
        private  const val TAG="Main Activity"
        private const val FILE_NAME_FORMAT="yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS=20
        private val REQUIRED_PERMISSIONS= mutableListOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO)
            .apply {
                if(Build.VERSION.SDK_INT<=Build.VERSION_CODES.P){
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private var binding_main_activity:ActivityMainBinding?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding_main_activity= ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding_main_activity!!.root)
        if(allPermissionsGranted()){
            startCamera()
        }else{
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        binding_main_activity!!.buttonCapturePhoto.setOnClickListener { capturePhoto() }

        binding_main_activity!!.buttonCaptureVideo.setOnClickListener { captureVideo() }

        executor=Executors.newSingleThreadExecutor()
    }

    private  fun allPermissionsGranted()= REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext,it)==PackageManager.PERMISSION_GRANTED
    }


    private fun startCamera(){

        val cameraProviderFuture=ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
              val cameraProvider:ProcessCameraProvider=cameraProviderFuture.get()

            val preview=Preview.Builder().build().also {
                it.setSurfaceProvider(binding_main_activity!!.viewFinder.surfaceProvider)
            }
            val recorder= Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST,FallbackStrategy.higherQualityOrLowerThan(
                Quality.SD))).
            build()
            videoCapture=androidx.camera.video.VideoCapture.withOutput(recorder)
            photoCapture=ImageCapture.Builder().build()
            val imageAnalyser=ImageAnalysis.Builder().build().also {
                it.setAnalyzer(executor,LuminosityAnalyzer{
                    luma->
                    Log.d(TAG,"Average luminiousity: $luma")
                })
            }
            val selected_camera=CameraSelector.DEFAULT_BACK_CAMERA
            try {

                cameraProvider.unbindAll()

                // FOR CAPTURING IMAGES ONLY UNCOMMENT THIS AND COMMENT LINE ON LINE NUMBER 105
//                cameraProvider.bindToLifecycle(this,selected_camera,preview,photoCapture,imageAnalyser)

                //for capturing videos
                cameraProvider.bindToLifecycle(this,selected_camera,preview,videoCapture)

            }catch (e:Exception){
                Log.e(TAG,"BINDING FAILED",e)
            }

        },ContextCompat.getMainExecutor(this))

    }

    private fun capturePhoto(){

        val photo_capture=photoCapture?:return

        val name= SimpleDateFormat(FILE_NAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val content_values=ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name)
                put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg")
                if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P){
                    put(MediaStore.Images.Media.RELATIVE_PATH,"pictures/CameraX-Image")
                }

        }
        val output_options=ImageCapture.OutputFileOptions.Builder(contentResolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,content_values)
            .build()

        photo_capture.takePicture(output_options,ContextCompat.getMainExecutor(this),object :ImageCapture.OnImageSavedCallback{
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val message="photo captured succeed:${outputFileResults.savedUri}"
                Toast.makeText(baseContext,message,Toast.LENGTH_SHORT).show()
            }

            override fun onError(exception: ImageCaptureException) {
               Log.e(TAG,"photo capture failed",exception)
            }
        })




    }
    private fun captureVideo(){
        val video_capture=videoCapture ?:return
        binding_main_activity!!.buttonCaptureVideo.isEnabled=false
        val cur_recording=this.recording
        if(cur_recording!=null){
            cur_recording.stop()
            recording=null
            return
        }
        val name=SimpleDateFormat(FILE_NAME_FORMAT,Locale.US).format(System.currentTimeMillis())
        val content_values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P){
                put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/CameraX-Video")
            }
        }

        val media_store_output_options=MediaStoreOutputOptions
            .Builder(contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(content_values)
            .build()

        recording=video_capture
                .output
            .prepareRecording(this,media_store_output_options)
            .apply {
                if(PermissionChecker.checkSelfPermission(this@MainActivity,Manifest.permission.RECORD_AUDIO)
            ==PermissionChecker.PERMISSION_GRANTED){
                withAudioEnabled()
                }
             }.start(ContextCompat.getMainExecutor(this)){ record_event->
                 when(record_event){
                     is VideoRecordEvent.Start->{
                         binding_main_activity!!.buttonCaptureVideo.apply {
                             text=context.getString(R.string.stop_video_text)
                             isEnabled=true
                         }
                     }
                     is VideoRecordEvent.Finalize->{

                         if(!record_event.hasError()){
                             val message="video capture succeed :${record_event.outputResults.outputUri}"
                             Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
                             Log.d(TAG,message)
                         }else{
                             recording?.close()
                             recording=null
                             Log.d(TAG,"video recording failed with eror :${record_event.error}")
                         }
                         binding_main_activity!!.buttonCaptureVideo.apply{
                             text=context.getString(R.string.start_recording_text)
                             isEnabled=true
                         }

                     }

                 }
            }

    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private class LuminosityAnalyzer(private val listener:LumaListener):ImageAnalysis.Analyzer{
        override fun analyze(image: ImageProxy) {
         val buffer=image.planes[0].buffer
            val data=buffer.toByteArray()
            val pixels=data.map { it.toInt() and 0xff }
            val luma =pixels.average()
            listener(luma)
            image.close()
        }

        private fun ByteBuffer.toByteArray():ByteArray{
            rewind()
            val data=ByteArray(remaining())
            get(data)
            return data
        }

    }



}