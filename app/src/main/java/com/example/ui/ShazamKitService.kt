package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ShazamMatchStatus {
    object Idle : ShazamMatchStatus()
    object RequestingPermission : ShazamMatchStatus()
    object Listening : ShazamMatchStatus()
    data class Success(val title: String, val artist: String, val album: String?, val coverUrl: String?) : ShazamMatchStatus()
    data class NoMatch(val reason: String) : ShazamMatchStatus()
    data class Error(val message: String) : ShazamMatchStatus()
}

class ShazamKitService(private val context: Context) {

    companion object {
        private const val TAG = "ShazamKitService"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val _status = MutableStateFlow<ShazamMatchStatus>(ShazamMatchStatus.Idle)
    val status = _status.asStateFlow()

    private var recognitionJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    // ==========================================
    // INYECTAR TOKEN DE DESARROLLADOR DE APPLE AQUÍ
    // ==========================================
    private val APPLE_DEVELOPER_TOKEN = "INGRESA_TU_APPLE_DEVELOPER_TOKEN_DE_SHAZAM_AQUI"

    /**
     * Inicia el proceso de reconocimiento de voz / música usando el micrófono del móvil
     * y cargando la sesión de streaming de ShazamKit.
     */
    @SuppressLint("MissingPermission")
    fun startRecognition(scope: CoroutineScope) {
        if (isRecording) {
            stopRecognition()
        }

        _status.value = ShazamMatchStatus.Listening
        isRecording = true

        recognitionJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                _status.value = ShazamMatchStatus.Error("Error al calcular el tamaño de buffer para grabación de Audio.")
                isRecording = false
                return@launch
            }

            try {
                // 1. Configuración de captura de audio del micrófono usando AudioRecord
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBufferSize * 2
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    _status.value = ShazamMatchStatus.Error("No se pudo iniciar el AudioRecord. ¿Permisos de micrófono activos?")
                    isRecording = false
                    return@launch
                }

                // 2. Inicialización simulada y lista para inyectar Token de ShazamKit (Apple Developer Program)
                // Aquí se inicializa la StreamingSession original de ShazamKit:
                /*
                val tokenProvider = com.shazam.shazamkit.DeveloperTokenProvider { APPLE_DEVELOPER_TOKEN }
                val shazamKit = com.shazam.shazamkit.ShazamKit.create(tokenProvider)
                val shazamSession = shazamKit.createStreamingSession(
                    com.shazam.shazamkit.Catalog.shazamKitCatalog()
                )

                shazamSession.listener = object : com.shazam.shazamkit.StreamingSession.Listener {
                    override fun onMatch(matchResult: com.shazam.shazamkit.MatchResult.Match) {
                        val track = matchResult.track
                        _status.value = ShazamMatchStatus.Success(
                            title = track.title ?: "Desconocido",
                            artist = track.artist ?: "Desconocido",
                            album = track.album,
                            coverUrl = track.artworkUrl?.toString()
                        )
                        stopRecognition()
                    }

                    override fun onNoMatch(noMatchResult: com.shazam.shazamkit.MatchResult.NoMatch) {
                        _status.value = ShazamMatchStatus.NoMatch("No se encontró coincidencia.");
                        stopRecognition()
                    }

                    override fun onError(shazamKitError: com.shazam.shazamkit.ShazamKitError) {
                        _status.value = ShazamMatchStatus.Error(shazamKitError.message ?: "Error de coincidencia en ShazamKit.")
                        stopRecognition()
                    }
                }
                */

                audioRecord?.startRecording()
                Log.d(TAG, "AudioRecord iniciado correctamente. Transmitiendo audio para ShazamKit...")

                val audioBuffer = ShortArray(minBufferSize)
                var detectionTimeMs = 0L

                // Simulación funcional de detección progresiva mientras capturamos PCM real de micrófono
                while (isRecording && coroutineContext.isActive) {
                    val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readBytes > 0) {
                        // En producción con SDK real, aquí inyectamos los bytes a la sesión de ShazamKit:
                        /*
                        val format = com.shazam.shazamkit.AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelType(com.shazam.shazamkit.AudioFormat.ChannelType.MONO)
                            .build()
                        shazamSession.match(com.shazam.shazamkit.AudioRecording(format, audioBuffer))
                        */

                        // Para simular el reconocimiento estético y de pruebas con fines ilustrativos:
                        delay(200)
                        detectionTimeMs += 200L

                        // Si pasan 5 segundos de escucha y no han inyectado un token real, avisar amistosamente
                        if (detectionTimeMs >= 5000L && APPLE_DEVELOPER_TOKEN == "INGRESA_TU_APPLE_DEVELOPER_TOKEN_DE_SHAZAM_AQUI") {
                            _status.value = ShazamMatchStatus.Success(
                                title = "Desinstalar - Estética Activa",
                                artist = "Inyecta tu Apple Developer Token en ShazamKitService",
                                album = "Shazam Catalog",
                                coverUrl = null
                            )
                            stopRecognition()
                        }
                    } else {
                        delay(50)
                    }
                }

            } catch (e: Exception) {
                _status.value = ShazamMatchStatus.Error("Error en sesión: ${e.message}")
                stopRecognition()
            }
        }
    }

    /**
     * Detiene la grabación del micrófono y la sesión de Shazam
     */
    fun stopRecognition() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando grabador de audio: ${e.message}")
        } finally {
            audioRecord = null
        }
        recognitionJob?.cancel()
        recognitionJob = null
        if (_status.value == ShazamMatchStatus.Listening) {
            _status.value = ShazamMatchStatus.Idle
        }
    }
}
