import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri

class BackingTrackPlayer private constructor(context: Context) {
    private val appContext = context.applicationContext
    private var mediaPlayer: MediaPlayer? = null
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun play(fileUri: Uri) {
        mediaPlayer?.release()

        mediaPlayer = MediaPlayer().apply {
            setDataSource(appContext, fileUri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // FIX 1: Assign the device DIRECTLY to the media player instance before starting it
            val targetOutput = getPreferredOutputDevice()
            if (targetOutput != null) {
                // 1. CRITICAL: Register a routing listener.
                // Without this, Android frequently ignores setPreferredDevice().
                addOnRoutingChangedListener({ routing ->
                    // Can leave this empty, its presence forces Android to apply the override
                }, null)

                // 2. Find and apply your preferred output hardware
                val success = setPreferredDevice(targetOutput)
                // Optional debug log to make sure the device was accepted
                android.util.Log.d("GuitarApp", "Routing applied to ${targetOutput.productName}: $success")
            }
            isLooping = true
            prepare()

            start()
        }
    }

    // FIX 2: Restructure device finding logic to prioritize phone speakers if headphones aren't plugged in
    private fun getPreferredOutputDevice(): AudioDeviceInfo? {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Loop 1: Look for external things you'd want to jam to (Wired Headphones/Bluetooth)
        for (device in outputDevices) {
            if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_AUX_LINE ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                return device
            }
        }

        // Loop 2: Fallback to the built-in speaker so it won't go to the USB guitar rig's headphone out
        for (device in outputDevices) {
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                return device
            }
        }

        return null
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    companion object {
        @Volatile
        private var INSTANCE: BackingTrackPlayer? = null

        fun getInstance(context: Context): BackingTrackPlayer {
            return INSTANCE ?: synchronized(this) {
                val instance = BackingTrackPlayer(context)
                INSTANCE = instance
                instance
            }
        }
    }
}
