package com.anilowa.kitku_watcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import android.graphics.Canvas
import androidx.core.graphics.createBitmap

private val REQUIRED_PERMISSIONS = arrayOf(
	Manifest.permission.CAMERA,
	Manifest.permission.RECORD_AUDIO
)

private var cameraDevice: CameraDevice? = null
private var previewSession: CameraCaptureSession? = null
private lateinit var previewRequestBuilder: CaptureRequest.Builder
private var backgroundThread: HandlerThread? = null
private var backgroundHandler: Handler? = null

@Volatile
private var latestJpeg: ByteArray? = null

class MainActivity : ComponentActivity() {
	private val permissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions()
	) { permissions ->
		val allGranted = permissions.entries.all { it.value }

		if (allGranted) {
			Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
			startCamera()
		} else {
			Toast.makeText(this, "Permissions denied :(", Toast.LENGTH_SHORT).show()
			finish()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContentView(R.layout.activity_main)

		if (!allPermissionsGranted()) {
			permissionLauncher.launch(REQUIRED_PERMISSIONS)
		} else {
			startCamera()
		}
		val server = MjpegServer { latestJpeg }
		server.start()
	}

	private fun allPermissionsGranted(): Boolean {
		return REQUIRED_PERMISSIONS.all {
			ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
		}
	}

	private fun startCamera() {
		val textureView = findViewById<TextureView>(R.id.textureView)

		if (textureView.isAvailable) {
			openCamera()
		} else {
			textureView.surfaceTextureListener = surfaceTextureListener
		}

		val handler = Handler(Looper.getMainLooper())
		val jpegRunnable = object : Runnable {
			override fun run() {
				val bitmap = textureView.bitmap ?: return

				// Use ARGB_8888 which is the default and provides full color info including alpha channel
				val argbBitmap = createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
				val canvas = Canvas(argbBitmap)
				canvas.drawBitmap(bitmap, 0f, 0f, null)

				val stream = ByteArrayOutputStream()
				argbBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
				latestJpeg = stream.toByteArray()
				stream.close()
				handler.postDelayed(this, 100)
			}
		}
		handler.post(jpegRunnable)

	}


	val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
		override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
			openCamera()
		}

		override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
		override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
		override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
	}

	private fun openCamera() {
		startBackgroundThread()
		val manager = getSystemService(CAMERA_SERVICE) as CameraManager
		val cameraId = manager.cameraIdList[0]

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
				override fun onOpened(camera: CameraDevice) {
					cameraDevice = camera
					startPreview(findViewById<TextureView>(R.id.textureView))
				}

				override fun onDisconnected(camera: CameraDevice) {
					camera.close()
					cameraDevice = null
				}

				override fun onError(camera: CameraDevice, error: Int) {
					camera.close()
					cameraDevice = null
				}
			}, backgroundHandler)
		}
	}
	@Suppress("DEPRECATION")
	private fun startPreview(textureView: TextureView) {
		val texture = textureView.surfaceTexture ?: return
		texture.setDefaultBufferSize(1920, 1080)

		val surface = Surface(texture)
		previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
		previewRequestBuilder.addTarget(surface)

		cameraDevice!!.createCaptureSession(listOf(surface),
			object : CameraCaptureSession.StateCallback() {
				override fun onConfigured(session: CameraCaptureSession) {
					previewSession = session
					previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
					previewSession!!.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
				}

				override fun onConfigureFailed(session: CameraCaptureSession) {
					Toast.makeText(this@MainActivity, "Failed to configure camera.", Toast.LENGTH_SHORT).show()
				}
			}, backgroundHandler)
	}

	private fun startBackgroundThread() {
		backgroundThread = HandlerThread("CameraBackground").also { it.start() }
		backgroundHandler = Handler(backgroundThread!!.looper)
	}

	private fun stopBackgroundThread() {
		backgroundThread?.quitSafely()
		backgroundThread?.join()
		backgroundThread = null
		backgroundHandler = null
	}

	override fun onPause() {
		super.onPause()
		cameraDevice?.close()
		stopBackgroundThread()
	}
}

class MjpegServer(private val frameProvider: () -> ByteArray?) : NanoHTTPD(8080) {
	private val boundary = "--frame"  // Boundary string, should be consistent

	override fun serve(session: IHTTPSession): Response {
		val input = PipedInputStream()
		val output = PipedOutputStream(input)

		Thread {
			try {
				while (true) {
					val jpeg = frameProvider() ?: continue

					// Properly format the boundary and headers
					val header = """
                        $boundary
                        Content-Type: image/jpeg
                        Content-Length: ${jpeg.size}
                        
                    """.trimIndent().replace("\n", "\r\n").toByteArray()

					output.write(header)
					output.write(jpeg)
					output.write("\r\n".toByteArray())  // Ensure proper termination
					output.flush()

					Thread.sleep(100)
				}
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}.start()

		return newChunkedResponse(
			Response.Status.OK,
			"multipart/x-mixed-replace; boundary=$boundary",  // Correct boundary here
			input
		)
	}
}
