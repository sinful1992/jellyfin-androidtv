package org.jellyfin.androidtv.integration.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.error
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.AndroidVersion
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException

class ImageProvider : ContentProvider() {
	private val imageLoader by inject<ImageLoader>()

	override fun onCreate(): Boolean = true

	override fun getType(uri: Uri) = null
	override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
	override fun insert(uri: Uri, values: ContentValues?) = null
	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

	override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
		val src = requireNotNull(uri.getQueryParameter("src")).toUri()
		val context = requireNotNull(context)

		val (read, write) = ParcelFileDescriptor.createPipe()
		val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(write)

		// The launcher asks for every picture in its rows at once, right when the viewer comes back to it,
		// and so right before they open the app. Decoding and encoding used to run in the image loader's
		// target callback, on the main thread, and writing to the pipe blocks until the launcher reads it:
		// the app's main thread was busy for 5-10 s, and the app could not open until it was done
		// (task 171). Now it all runs here, off the main thread, two pictures at a time.
		serveImage(
			scope = encodeScope,
			load = {
				imageLoader.execute(ImageRequest.Builder(context).apply {
					data(src)
					size(MAX_SIZE)
					precision(Precision.INEXACT)
					allowHardware(false)
					error(R.drawable.placeholder_icon)
				}.build()).image?.asDrawable(context.resources)
			},
			write = { drawable -> writeDrawable(drawable, outputStream) },
			close = { outputStream.close() },
		)

		return read
	}

	private fun writeDrawable(
		drawable: Drawable,
		outputStream: ParcelFileDescriptor.AutoCloseOutputStream
	) {
		@Suppress("DEPRECATION")
		val format = when {
			AndroidVersion.isAtLeastR -> Bitmap.CompressFormat.WEBP_LOSSY
			else -> Bitmap.CompressFormat.WEBP
		}

		try {
			outputStream.use {
				drawable.toBitmap().compress(format, COMPRESSION_QUALITY, outputStream)
			}
		} catch (_: IOException) {
			// Ignore IOException as this is commonly thrown when the load request is cancelled
		}
	}

	companion object {
		private const val COMPRESSION_QUALITY = 95

		/**
		 * The tallest picture the launcher gets, in pixels. The launcher's rows are 1080p at most, and a
		 * card there is well under half the screen high. Asked of the server with [getUrl]'s maxHeight;
		 * [MAX_SIZE] keeps the bundled placeholder and any other source to the same bound.
		 */
		const val MAX_HEIGHT = 480
		private val MAX_SIZE = Size(Dimension.Undefined, Dimension(MAX_HEIGHT))

		/** Encoding pictures for the launcher must never take every core while the app is starting. */
		private val encodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))

		/**
		 * Get a [Uri] that uses the [ImageProvider] to load an image. The input should be a valid
		 * Jellyfin image URL created using the SDK.
		 */
		fun getImageUri(src: String): Uri = Uri.Builder()
			.scheme("content")
			.authority("${BuildConfig.APPLICATION_ID}.integration.provider.ImageProvider")
			.appendQueryParameter("src", src)
			.appendQueryParameter("v", BuildConfig.VERSION_NAME)
			.build()
	}
}

/**
 * Loads and writes one picture for [ImageProvider.openFile] on [scope], never on the caller's thread, so
 * openFile returns at once. Whatever happens, the pipe is closed: a picture that fails to load or write
 * ends the launcher's read instead of leaving it waiting, and never crashes the app.
 */
internal fun <T : Any> serveImage(
	scope: CoroutineScope,
	load: suspend () -> T?,
	write: (T) -> Unit,
	close: () -> Unit,
): Job = scope.launch {
	try {
		val image = load()
		if (image != null) write(image)
	} catch (err: Exception) {
		if (err is CancellationException) throw err
		Timber.w(err, "Launcher picture failed")
	} finally {
		runCatching { close() }
	}
}
