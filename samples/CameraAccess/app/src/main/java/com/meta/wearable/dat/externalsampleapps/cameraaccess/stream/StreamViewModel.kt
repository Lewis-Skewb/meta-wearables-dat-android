/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> ARGB conversion)

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.session.Session
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.skewbclimate.apptest.network.httpClient
import com.skewbclimate.apptest.network.await
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject


@SuppressLint("AutoCloseableUse")
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    private val SESSION_TERMINAL_STATES = setOf(StreamSessionState.CLOSED)
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: Session? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var stream: Stream? = null

  // Presentation queue for buffering frames after color conversion
  private var presentationQueue: PresentationQueue? = null

  public data class S3Response(val url: String, val key: String)
  

  fun startStream() {
    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    sessionStateJob?.cancel()
    presentationQueue?.stop()
    presentationQueue = null

    // Initialize presentation queue - frames are presented based on timestamp, not arrival time
    // Uses IntArray pooling for efficiency - cheaper than Bitmap.copy()
    val queue =
        PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
              // This is called from the presentation thread at regular intervals
              // when a frame's presentation time has arrived
              _uiState.update {
                it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)
              }
            },
        )
    presentationQueue = queue
    queue.start()
    if (session == null) {
      Wearables.createSession(deviceSelector)
          .onSuccess { createdSession ->
            session = createdSession
            session?.start()
          }
          .onFailure { error, _ -> Log.e(TAG, "Failed to create session: ${error.description}") }
      if (session == null) return
    }
    startStreamInternal()
  }

  private fun startStreamInternal() {
    Log.d(TAG, "startStreamInternal() - collecting session state")
    sessionStateJob =
        viewModelScope.launch {
          session?.state?.collect { currentState ->
            if (currentState == DeviceSessionState.STARTED) {
              videoJob?.cancel()
              stateJob?.cancel()
              errorJob?.cancel()
              stream?.stop()
              stream = null
              session
                  ?.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24))
                  ?.onSuccess { addedStream ->
                    stream = addedStream
                    videoJob =
                        viewModelScope.launch {
                          Log.d(TAG, "Collecting video frames from stream")
                          stream?.videoStream?.collect { handleVideoFrame(it) }
                          Log.d(TAG, "Video stream collection ended")
                        }
                    stateJob =
                        viewModelScope.launch {
                          stream?.state?.collect { currentState ->
                            val prevState = _uiState.value.streamSessionState
                            Log.d(TAG, "Stream state changed: $prevState -> $currentState")
                            _uiState.update { it.copy(streamSessionState = currentState) }

                            val wasActive = prevState !in SESSION_TERMINAL_STATES
                            val isTerminated = currentState in SESSION_TERMINAL_STATES
                            if (wasActive && isTerminated) {
                              Log.d(TAG, "Terminal state reached, navigating back")
                              stopStream()
                              wearablesViewModel.navigateToDeviceSelection()
                            }
                          }
                        }
                    errorJob =
                        viewModelScope.launch {
                          stream?.errorStream?.collect { error ->
                            Log.d(
                                TAG,
                                "Stream error received: $error (description: ${error.description})",
                            )
                            if (error == StreamError.HINGE_CLOSED) {
                              Log.d(
                                  TAG,
                                  "HINGE_CLOSED detected, stopping stream and navigating back",
                              )
                              stopStream()
                              wearablesViewModel.navigateToDeviceSelection()
                            }
                          }
                        }
                    stream?.start()
                  }
                  ?.onFailure { error, _ ->
                    Log.e(TAG, "Failed to add stream to session: ${error.description}")
                  }
            }
          }
        }
  }

  fun stopStream() {
    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    presentationQueue?.stop()
    presentationQueue = null
    _uiState.update { INITIAL_STATE }
    stream?.stop()
    stream = null
    session?.stop()
    session = null
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        stream
            ?.capturePhoto()
            ?.onSuccess { photoData ->
              Log.d(TAG, "Photo capture successful")
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false) }
            }
            ?.onFailure { error, _ ->
              Log.e(TAG, "Photo capture failed: ${error.description}")
              _uiState.update { it.copy(isCapturing = false) }
            }
      }
    } else {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
      )
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false, geminiResponse = null, lastS3Key = null) }
  }

  suspend fun uploadPhoto(bitmap: Bitmap) : String {
    _uiState.update { it.copy(isUploading = true, geminiResponse = null, lastS3Key = null) }
    return try {
      val geminiResponse = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val imagesFolder = File(context.cacheDir, "images")
        imagesFolder.mkdirs()
        val file = File(imagesFolder, "shared_image.png")
        FileOutputStream(file).use { stream ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        }

        val uuid = "019df751-5dec-77f0-91df-934de367f154"

        //Send UUID as part of request to S3 Lambda API, expect a signed URL response to upload image to.
        Log.i("API Debugging", "Generating S3 URL")
        val s3Response = generateS3Url(uuid)
        if (s3Response.url.isEmpty()) {
          throw IOException("Failed to generate S3 URL")
        }
        
        _uiState.update { it.copy(lastS3Key = s3Response.key) }
        
        Log.i("API Debugging", "S3 URL: " + s3Response.url)
        Log.i("API Debugging", "S3 URL key: " + s3Response.key)

        //Upload image to signed S3 URL
        Log.i("API Debugging", "Uploading image file to S3 bucket...")
        val s3UploadSuccess = sendToS3(s3Response.url, file)
        Log.i("API Debugging", "Checking for success: " + s3UploadSuccess)
        if (!s3UploadSuccess) {
          throw IOException("Image upload to S3 failed")
        }

        //Send UUID and s3Response.key for file to Gemini Lambda API, expect string description of image as a response
        Log.i("API Debugging", "Image uploaded to S3 successfully, querying Gemini for description...")
        queryGemini(s3Response.key, uuid)
      }
      Log.i("API Debugging", "Gemini response: " + geminiResponse)
      _uiState.update { it.copy(isUploading = false, geminiResponse = geminiResponse) }
      geminiResponse
    } catch (e: Exception) {
      Log.e("API Debugging", "Failed to share photo", e)
      val errorMsg = if (e is IOException) e.message ?: "Network error" else "Something went wrong. Please try again."
      _uiState.update { it.copy(isUploading = false, geminiResponse = "Error: $errorMsg") }
      ""
    }
  }

  suspend fun retryGemini() {
    val s3Key = _uiState.value.lastS3Key ?: return
    val uuid = "019df751-5dec-77f0-91df-934de367f154"
    
    _uiState.update { it.copy(isUploading = true, geminiResponse = null) }
    
    try {
      val description = withContext(Dispatchers.IO) {
        queryGemini(s3Key, uuid)
      }
      _uiState.update { it.copy(isUploading = false, geminiResponse = description) }
    } catch (e: Exception) {
      Log.e("API Debugging", "Failed to retry Gemini", e)
      val errorMsg = if (e is IOException) e.message ?: "Network error" else "Something went wrong. Please try again."
      _uiState.update { it.copy(isUploading = false, geminiResponse = "Error: $errorMsg") }
    }
  }

  suspend fun generateS3Url(uuid: String): S3Response {
    //Take UUID, build JSON object with "password", "extension" and content type
      val jsonBody = JSONObject().apply {
          put("password", uuid)
          put("extension", "png")
          put("contentType", "image/png")
      }.toString()

    //Send POST to S3 Lambda API with JSON body, expect signed URL in response
    val request = Request.Builder()
        .url("https://go8xx7xqf2.execute-api.eu-west-2.amazonaws.com/prod/skewb-climate-generate-signed-s3-put-url-test")
        .post(jsonBody.toRequestBody("application/json".toMediaType()))
        .build()

    val response = httpClient.newCall(request).await()
    if (!response.isSuccessful) {
      throw IOException("Failed to generate S3 URL: ${response.code}")
    }

    //Parse json response into object containing uploadUrl and key
    val body = response.body?.string()
    if (body == null) {
        throw IOException("Response body is null")
    }

    val json = JSONObject(body)

    val url = json.getString("uploadUrl")
    val key = json.getString("key")

    val s3Response = S3Response(
        url = url,
        key = key
    )

    return s3Response
  }

  suspend fun sendToS3(s3Url: String, file: File) : Boolean {
    //Upload file to S3 via a PUT call
    val fileRequestBody = file.asRequestBody("image/png".toMediaType())
    val request = Request.Builder().url(s3Url).put(fileRequestBody).build()

    val uploadResponse = httpClient.newCall(request).await()

    if (!uploadResponse.isSuccessful) {
      throw IOException("Failed to upload image to S3: ${uploadResponse.code}")
    }

    //if successful, return true
    return true
  }

  suspend fun queryGemini(s3Path: String, uuid: String) : String {
    val lambdaUrl = "https://go8xx7xqf2.execute-api.eu-west-2.amazonaws.com/prod/skewb-climate-gemini-test-lambda"

    //Build JSON body with s3Path and UUID
    val jsonBody = JSONObject().apply {
        put("imagePath", s3Path)
        put("password", uuid)
        put("mimeType", "image/png")
    }.toString()

    Log.i("API Debugging", "Querying Gemini with body: " + jsonBody)

    //Send json to Lambda as get
    val request = Request.Builder()
        .url(lambdaUrl)
        .post(jsonBody.toRequestBody("application/json".toMediaType()))
        .build()
    Log.i("API Debugging", "Sending request to Gemini Lambda...")
    val response = httpClient.newCall(request).await()
    Log.i("API Debugging", "Received response from Gemini Lambda")
    Log.i("API Debugging", "Response: " + response.toString())

    //Check for successful response
    Log.i("API Debugging", "Checking if response was valid...")
    if (!response.isSuccessful) {
      Log.e("API Debugging", "Gemini Lambda returned error: " + response.code)
      if (response.code == 504) {
        throw IOException("Call timed out, please try again...")
      }
      throw IOException("Something went wrong while querying Gemini, please try again and if the issue persists, contact Lewis.")
    }

    //Take "description" field from JSON response and return as string
    Log.i("API Debugging", "Extracting response from API result...")
    val body = response.body?.string()
    if (body == null) {
        Log.i("API Debugging", "Response body is null")
        throw IOException("Response body is null")
    }
    Log.i("API Debugging", "Parsing response to JSON...")
    val json = JSONObject(body)
    Log.i("API Debugging", "JSON: " + json)

    Log.i("API Debugging", "Check to see if message value exists, signifying a time-out...")
    if (!json.isNull("message")) {
      Log.i("API Debugging", "Gemini call timed out.")
      return "Gemini endpoint call timed out (60 seconds). Please try again."
    }

    Log.i("API Debugging", "Pull Gemini response from description value...")
    if (json.isNull("description")) {
      Log.i("API Debugging", "Unexpected missing 'description' field for Gemini Response, throwing error.")
      return "Gemini endpoint did not return a valid response, please contact Lewis to investigate."
    }
    val geminiResponse = json.getString("description")
    if (geminiResponse == null || geminiResponse.isEmpty()) {
      Log.i("API Debugging", "Unexpected error occurred when accessing Gemini Response.")
      return "No description returned, something has gone wrong. Check Gemini Lambda Logs."
    }
    Log.i("API Debugging", "Gemini Response: " + geminiResponse)

    Log.i("API Debugging", "Returning Gemini Response...")
    return geminiResponse
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    // VideoFrame contains raw I420 video data in a ByteBuffer
    // Use optimized YuvToBitmapConverter for direct I420 to ARGB conversion
    val bitmap =
        YuvToBitmapConverter.convert(
            videoFrame.buffer,
            videoFrame.width,
            videoFrame.height,
        )
    if (bitmap != null) {
      presentationQueue?.enqueue(
          bitmap,
          videoFrame.presentationTimeUs,
      )
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
    }
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    session?.stop()
    session = null
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
