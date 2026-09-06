package com.example.listentomyguitarlive

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
    @Volatile
    private var bpm = 120

    @Volatile
    private var isMetronomeOn = false
    private var isRunning = false
    private var isRecording = false
    private var audioThread: Thread? = null

    private var recordingOutputStream: OutputStream? = null
    private var currentWavUri: Uri? = null
    private var recordedDataLen: Long = 0

    private val sampleRate = 44100
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    companion object {
        var instance: AudioService? = null

        @Volatile
        var bpm = 120
        @Volatile
        var isMetronomeOn = false
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

    fun setBMP(bpm: Int){
        this.bpm = bpm
    }

    fun setMetronomeBoolean(isItOn: Boolean){
        isMetronomeOn = isItOn;
    }

    private fun createNotification(): Notification {
        val channelId = "guitar_live_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Live Guitar Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Listen To My Guitar Live")
            .setContentText("Audio monitoring active in background")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            if (usbDevice != null) {
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

                        // Convert bytes to shorts for mixing metronome
                        java.nio.ByteBuffer.wrap(buffer, 0, readSize)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shortBuffer)

                        val samplesPerBeat = (sampleRate * 60.0 / bpm).toLong()

                        for (i in 0 until samplesRead) {
                            sampleCounter++
                            if (sampleCounter >= samplesPerBeat) {
                                sampleCounter = 0
                                clickSampleRemaining = clickDurationSamples
                            }

                            var sampleVal = shortBuffer[i].toInt()

                            // Mix metronome click if active
                            if (isMetronomeOn && clickSampleRemaining > 0) {
                                val progress = 1.0 - (clickSampleRemaining.toDouble() / clickDurationSamples.toDouble())
                                val sineWave = (sin(2.0 * Math.PI * clickFrequency * (clickDurationSamples - clickSampleRemaining) / sampleRate) * 5000.0 * progress).toInt()
                                sampleVal += sineWave
                                clickSampleRemaining--
                            } else if (!isMetronomeOn) {
                                clickSampleRemaining = 0 // Reset click if turned off mid-bar
                            }

                            // Clamp values to 16-bit signed range
                            shortBuffer[i] = sampleVal.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }


                        // ➡️ WRITE RAW, CLEAN GUITAR TO RECORDING STREAM (NO METRONOME)
                        if (isRecording) {
                            recordingOutputStream?.write(buffer, 0, readSize)
                            recordedDataLen += readSize
                        }

                        // Convert back to byte array for playback monitoring with metronome
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
    private fun findUsbAudioDevice(audioManager: AudioManager): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return device
            }
        }
        return null
    }
}