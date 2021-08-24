package de.c1710.filemojicompat_ui.helpers

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import de.c1710.filemojicompat_ui.structures.EmojiPackList
import java.io.*
import java.security.MessageDigest
import kotlin.concurrent.thread

const val PICK_EMOJI = "de.c1710.filemojicompat_PICK_CUSTOM_EMOJI"

// https://developer.android.com/training/basics/intents/result
class CustomEmojiHandler(
    private val registry: ActivityResultRegistry,
    private val list: EmojiPackList,
    private val context: Context
): DefaultLifecycleObserver {
    private lateinit var getContent: ActivityResultLauncher<Array<String>>
    private var callback: CustomEmojiCallback? = null

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        getContent = registry.register(PICK_EMOJI, owner, ActivityResultContracts.OpenDocument()) {
            if (it != null) {
                storeCustomEmoji(it, callback)
            }
        }
    }

    fun pickCustomEmoji(callback: CustomEmojiCallback?) {
        this.callback = callback
        getContent.launch(arrayOf("font/ttf", "font/otf"))
    }

    @Throws(FileNotFoundException::class)
    private fun storeCustomEmoji(source: Uri, callback: CustomEmojiCallback?) {
        if (source.scheme in arrayOf(
                ContentResolver.SCHEME_FILE,
                ContentResolver.SCHEME_ANDROID_RESOURCE,
                ContentResolver.SCHEME_CONTENT
            )) {
                // As loading a custom emoji pack is not something that is done over and over,
                // creating a new Thread for it is not a big problem
                thread {
                    Log.d("FilemojiCompat", "storeCustomEmoji: Loading emoji pack from file")
                    val stream: InputStream? = context.contentResolver.openInputStream(source)
                    if (stream != null) {
                        val file = storeAndHashPack(stream)
                        EmojiPreference.setCustom(context, file)
                        callback?.onLoaded(file)
                    } else {
                        Log.e("FilemojiCompat", "storeCustomEmoji: Empty stream for %s".format(source.toString()))
                        callback?.onFailed(FileNotFoundException(source.toString()))
                    }
                }
        } else {
            Log.e("FilemojiCompat", "storeCustomEmoji: Unsupported scheme for %s: %s".format(source.toString(), source.scheme))
        }
    }

    private fun storeAndHashPack(stream: InputStream): String {
        // While processing, we will compute a hash; the content is written to a temporary file
        val outputFile = createTempFile()
        val writer = FileOutputStream(outputFile)

        // According to https://stackoverflow.com/a/11221907/5070653, 32K buffer size at least
        // was good in the past
        val buffer = ByteArray(0x8000)

        // We don't actually need security here, but runtime performance shouldn't be impacted
        val digest = MessageDigest.getInstance("SHA-256")

        var bytesRead = stream.read(buffer)

        while (bytesRead != -1) {
            digest.update(buffer, 0, bytesRead)
            writer.write(buffer, 0, bytesRead)

            bytesRead = stream.read(buffer)
        }

        val hash = digest.digest()

        // Now, check whether we already have a file with that hash
        val file = createFileFromHash(hash)
        if (!file.exists()) {
            // Store it
            // This is safe; both files are in the same directory, which is accessible to our app
            outputFile.renameTo(file)
        } else {
            Log.i("FilemojiCompat", "storeAndHashPack: Emoji Pack already exists: %s".format(file.toString()))
        }
        // https://www.baeldung.com/kotlin/byte-arrays-to-hex-strings
        return hashToString(hash)
    }

    private fun createTempFile(): File {
        return File(list.emojiStorage, System.currentTimeMillis().toString() + ".ttf")
    }

    private fun createFileFromHash(hash: ByteArray): File {
        return File(list.emojiStorage, hashToString(hash) + ".ttf")
    }

    private fun hashToString(hash: ByteArray): String {
        return hash.joinToString(separator = "") { "%02x".format(it) }
    }
}