package com.example.listentomyguitarlivemergemode

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

class AudioService : Service() {


    companion object {
        @Volatile
        private var isRunning = false
        private var isRecording = false
        private var audioThread: Thread? = null

        private var recordingOutputStream: OutputStream? = null
        private var currentWavUri: Uri? = null
        private var recordedDataLen: Long = 0
        val byteArray = ByteArray(2)




        private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
        private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
        private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        var instance: AudioService? = null
        @Volatile
        var guitarVolume: Float = 1.0f
        @Volatile
        var backingVolume: Float = 1.0f
        @Volatile
        var bpm = 120
        @Volatile
        var isMetronomeOn = false
        @Volatile
        var useLimiter = false
        @Volatile
        var threshold = 28000
        @Volatile
        var ceiling = 32767
        @Volatile
        var ratio: Float = 0.2F
        @Volatile
        var seekBarCutoff: Int = 30
        @Volatile
        var useLowpass = false
        @Volatile
        var recordJustGuitar: Boolean = false
        @Volatile
        var cachedBackingTrackSamples: ShortArray? = null
        private var backingTrackIndex = 0
        private val sampleRate = 44100

        fun loadBackingTrackIntoMemory(context: Context, uri: Uri) {
            // Use android.media.MediaExtractor to decode the entire file into a ShortArray buffer once
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    format = extractor.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        trackIndex = i
                        break
                    }
                }
                if (trackIndex >= 0 && format != null) {
                    extractor.selectTrack(trackIndex)

                    // Force target sample rate to match pipeline (44100) to prevent speed mismatch
                    format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)

                    val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                    codec.configure(format, null, null, 0)
                    codec.start()

                    val byteList = mutableListOf<Byte>()
                    val bufferInfo = MediaCodec.BufferInfo()
                    var isEOS = false

                    while (!isEOS) {
                        val inId = codec.dequeueInputBuffer(10000)
                        if (inId >= 0) {
                            val inBuf = codec.getInputBuffer(inId)
                            if (inBuf != null) {
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    isEOS = true
                                } else {
                                    codec.queueInputBuffer(inId, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val outId = codec.dequeueOutputBuffer(bufferInfo, 10000)
                        if (outId >= 0) {
                            val outBuf = codec.getOutputBuffer(outId)
                            if (outBuf != null && bufferInfo.size > 0) {
                                val chunk = ByteArray(bufferInfo.size)
                                outBuf.get(chunk)
                                byteList.addAll(chunk.toList())
                                outBuf.clear()
                            }
                            codec.releaseOutputBuffer(outId, false)
                        }
                    }
                    codec.stop()
                    codec.release()
                    extractor.release()

                    // Convert raw PCM bytes to shorts
                    val shorts = ShortArray(byteList.size / 2)
                    java.nio.ByteBuffer.wrap(byteList.toByteArray())
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(shorts)


                    // Find out how many channels the file actually has from the track format
                    val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

// Downmix to mono if it's stereo so it fits your mono pipeline cleanly
                    cachedBackingTrackSamples = if (channelCount == 2) {
                        ShortArray(shorts.size / 2).also { mono ->
                            for (i in mono.indices) {
                                val left = shorts[i * 2].toInt()
                                val right = shorts[i * 2 + 1].toInt()
                                mono[i] = ((left + right) / 2).toShort()
                            }
                        }
                    } else {
                        shorts
                    }
                    backingTrackIndex = 0

                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        fun applyLimiter(sample: Int): Int {
            val absValue = kotlin.math.abs(sample)
            if (absValue <= threshold) {
                return sample
            }
            // Simple compression curve above the threshold
            val excess = absValue - threshold
            val compressedExcess = excess * ratio // Ratio adjustment above threshold
            val limited = threshold + compressedExcess.toInt()
            return if (sample < 0) -limited.coerceAtMost(ceiling) else limited.coerceAtMost(ceiling)
        }
        private fun findUsbAudioDevice(audioManager: AudioManager): AudioDeviceInfo? {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            for (device in devices) {
                if (device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    return device
                }
            }
            return null
        }
        fun createWavOutputStream(context: Context, fileName: String): Pair<Uri?, OutputStream?> {
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/ListenToMyGuitarLive")
            }

            val resolver = context.contentResolver
            val audioCollection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val uri: Uri? = resolver.insert(audioCollection, contentValues)
            val outputStream: OutputStream? = uri?.let { resolver.openOutputStream(it) }

            return Pair(uri, outputStream)
        }
        fun setBMP(bpm2: Int){
            bpm = bpm2
        }
        private var lowPassPrevSample = 0.0F
        fun applyLowPass(sample: Int): Int {
            val dt = 1.0f / sampleRate
            val rc = 1.0f / (2.0f * Math.PI.toFloat() * seekBarCutoff)
            val alpha = dt / (rc + dt)

            lowPassPrevSample += alpha * (sample - lowPassPrevSample)
            return lowPassPrevSample.toInt()
        }
        fun setMetronomeBoolean(isItOn: Boolean){
            isMetronomeOn = isItOn;
        }
        fun unloadBackingTrackIntoMemory() {
            cachedBackingTrackSamples = null
            backingTrackIndex = 0
        }


    }


    fun startAudioPipeline() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        isRunning = true




        audioThread = Thread {


            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toInt() ?: 192

            // Keep buffer size tight to minimize delay (latency)
            val bufferSize = framesPerBuffer * 2 * 2 // frames * channels * bytes per sample
            // 1. Setup AudioRecord for USB Guitar Input
            val audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(audioFormat)
                        .setChannelMask(channelConfigIn)
                        .build())
                .setBufferSizeInBytes(bufferSize * 4)
                .build()

            val usbDevice = findUsbAudioDevice(audioManager)
            if(usbDevice == null) {
                return@Thread
            }else{
                audioRecord.setPreferredDevice(usbDevice) // Pulls from your guitar interface
            }

            // 2. Setup AudioTrack for Local Output
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(audioFormat)
                        .setChannelMask(channelConfigOut)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // Force playback output to built-in speaker or regular headset instead of USB out
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var selectedOutput: AudioDeviceInfo? = null
            for (device in outputDevices) {
                if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_AUX_LINE ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    selectedOutput = device
                    break
                }
            }

            if (selectedOutput == null) {
                for (device in outputDevices) {
                    if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        selectedOutput = device
                        break
                    }
                }
            }
            if (selectedOutput != null) {
                audioTrack.setPreferredDevice(selectedOutput)
            }

            audioRecord.startRecording()

// For Android 11 (API 30) and below:
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true

// For Android 12 (API 31) and above:
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speakerDevice = audioManager.availableCommunicationDevices.find {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                }
            }

            audioTrack.play()



            val buffer = ByteArray(bufferSize)

            // Metronome state tracking
            var sampleCounter: Long = 0
            val clickFrequency = 1000.0 // 1 kHz click
            val clickDurationSamples = (sampleRate * 0.03).toInt() // 30ms click
            var clickSampleRemaining = 0





            try {
                while (isRunning) {
                    val readSize = audioRecord.read(buffer, 0, buffer.size)
                    if (readSize > 0) {
                        val samplesRead = readSize / 2
                        val shortBuffer = ShortArray(samplesRead)

                        java.nio.ByteBuffer.wrap(buffer, 0, readSize)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shortBuffer)

                        val samplesPerBeat = (sampleRate * 60.0 / bpm).toLong()
                        val backingSamples = cachedBackingTrackSamples
                        for (i in 0 until samplesRead) {
                            sampleCounter++
                            if (sampleCounter >= samplesPerBeat) {
                                sampleCounter = 0
                                clickSampleRemaining = clickDurationSamples
                            }

                            // 1. Process live guitar input from USB
                            val guitarSample = (shortBuffer[i].toInt() * guitarVolume).toInt()

                            // 2. Fetch backing track sample (incremented only ONCE here)
                            var backingSample = 0
                            if (backingSamples != null && backingSamples.isNotEmpty()) {
                                backingSample = (backingSamples[backingTrackIndex].toInt() * backingVolume).toInt()
                                backingTrackIndex = (backingTrackIndex + 1) % backingSamples.size
                            }

                            // 3. Process metronome click if active
                            var metronomeSample = 0
                            if (isMetronomeOn && clickSampleRemaining > 0) {
                                val progress = 1.0 - (clickSampleRemaining.toDouble() / clickDurationSamples.toDouble())
                                metronomeSample = (sin(2.0 * Math.PI * clickFrequency * (clickDurationSamples - clickSampleRemaining) / sampleRate) * 5000.0 * progress).toInt()
                                clickSampleRemaining--
                            } else if (!isMetronomeOn) {
                                clickSampleRemaining = 0
                            }

                            // 4. Mix guitar, backing track, and metronome together for live monitoring output
                            var mixedSample = guitarSample + backingSample + metronomeSample

                            if(useLowpass){
                                mixedSample = applyLowPass(mixedSample)
                            }
                            // 5. Apply limiter if toggled on
                            if (useLimiter) {
                                //android.util.Log.d("AudioService", "Limiter is active and running and letting you know (temp test)!")
                                mixedSample = applyLimiter(mixedSample)
                            }
                            shortBuffer[i] = mixedSample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                            // 5. Store mixed guitar + backing track into recording stream (excluding metronome)

                            if (isRecording) {
                                var recordedSample = if (recordJustGuitar) {
                                    guitarSample
                                } else {
                                    guitarSample + backingSample
                                }

                                if (useLowpass) {
                                    recordedSample = applyLowPass(recordedSample)
                                }
                                if (useLimiter) {
                                    recordedSample = applyLimiter(recordedSample)
                                }

                                val recordedSampleShort = recordedSample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                                // Write directly using bit shifts instead of allocating a ByteBuffer every frame
                                byteArray[0] = recordedSampleShort.toByte()
                                byteArray[1] = (recordedSampleShort.toInt() ushr 8).toByte()

                                recordingOutputStream?.write(byteArray, 0, 2)
                                recordedDataLen += 2
                            }
                        }

                        // Convert back to byte array for live playback monitoring
                        val outBytes = ByteArray(readSize)
                        val byteBuffer = java.nio.ByteBuffer.wrap(outBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        for (s in shortBuffer) {
                            byteBuffer.putShort(s)
                        }

                        audioTrack.write(outBytes, 0, readSize)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    audioRecord.stop()
                    audioRecord.release()
                    audioTrack.stop()
                    audioTrack.release()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }.apply { start() }
    }

    fun stopAudioPipeline() {
        isRunning = false
        audioThread?.join(500)
        audioThread = null
    }


    fun stopRecordingSession() {
        isRecording = false
        try {
            recordingOutputStream?.flush()
            recordingOutputStream?.close()
            recordingOutputStream = null

            // Write final WAV header with correct file sizes via MediaStore Uri
            currentWavUri?.let { uri ->
                writeWavHeaderToUri(uri, sampleRate, 1, 16, recordedDataLen)

            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        startAudioPipeline()
    }
    override fun onDestroy() {
        super.onDestroy()
        stopAudioPipeline()
        instance = null
        isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        if (!isRunning) {
            isRunning = true
        }
        return START_STICKY
    }



    private fun writeWavHeaderToUri(uri: Uri, sampleRate: Int, channels: Int, bitRate: Int, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitRate / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // SubChunk1Size (16 for PCM)
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat (1 for PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitRate / 8).toByte() // BlockAlign
        header[33] = 0
        header[34] = bitRate.toByte() // BitsPerSample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { fos ->
                fos.write(header)
            }
        }
    }

    fun startRecordingSession() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Guitar_$timeStamp.wav"

        try {
            val (uri, stream) = createWavOutputStream(this, fileName)
            currentWavUri = uri
            recordingOutputStream = stream

            // Leave a 44-byte placeholder for the WAV header
            recordingOutputStream?.write(ByteArray(44))
            recordedDataLen = 0
            isRecording = true
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
        }
    }

    private fun createNotification(): Notification {
        val channelId = "guitar_live_channel"
        val channel = NotificationChannel(channelId, "Live Guitar Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Listen To My Guitar Live")
            .setContentText("Audio monitoring active in background")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null



}