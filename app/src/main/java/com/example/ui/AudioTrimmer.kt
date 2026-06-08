package com.example.ui

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.data.repository.AudioPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

object AudioTrimmer {
    private const val TAG = "AudioTrimmer"

    /**
     * Losslessly trims an audio file from startMs to endMs using MediaExtractor and MediaMuxer.
     * Executes asynchronously on Dispatchers.IO.
     *
     * @param inputPath Path to the source audio file.
     * @param outputPath Path where the trimmed file should be saved.
     * @param startMs Start time in milliseconds.
     * @param endMs End time in milliseconds.
     * @return True if successful, false otherwise.
     */
    suspend fun trimAudio(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val originalFile = File(inputPath)
        if (!originalFile.exists()) {
            Log.e(TAG, "Input file does not exist: $inputPath")
            return@withContext false
        }

        // Try standard Android MediaExtractor/MediaMuxer first
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            Log.d(TAG, "Trimming starting. Input: $inputPath, Output: $outputPath, range: $startMs ms - $endMs ms")
            extractor.setDataSource(inputPath)
            
            val trackCount = extractor.trackCount
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            
            for (i in 0 until trackCount) {
                val trFormat = extractor.getTrackFormat(i)
                val mime = trFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trFormat
                    break
                }
            }
            
            if (audioTrackIndex == -1 || format == null) {
                Log.e(TAG, "No audio track found in original file.")
                throw Exception("No audio track found")
            }
            
            extractor.selectTrack(audioTrackIndex)
            
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            
            val outputFile = File(outputPath)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            
            // Try MPEG_4 container format (commonly robust for general audio types)
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(format)
            muxer.start()
            
            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                1024 * 1024
            }
            val dstBuf = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            
            var baseTimeUs: Long = -1L
            
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(dstBuf, 0)
                
                if (bufferInfo.size < 0) {
                    Log.d(TAG, "Extractor completed input stream.")
                    break
                }
                
                val presentationTimeUs = extractor.sampleTime
                if (presentationTimeUs > endUs) {
                    Log.d(TAG, "Reached end trimmed time bounds ($endUs us). Break.")
                    break
                }
                
                if (presentationTimeUs < startUs) {
                    extractor.advance()
                    continue
                }
                
                if (baseTimeUs == -1L) {
                    baseTimeUs = presentationTimeUs
                }
                
                bufferInfo.presentationTimeUs = presentationTimeUs - baseTimeUs
                bufferInfo.flags = extractor.sampleFlags
                
                muxer.writeSampleData(muxerTrackIndex, dstBuf, bufferInfo)
                extractor.advance()
            }
            
            muxer.stop()
            Log.d(TAG, "Audio trimming finished successfully.")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Standard MediaMuxer trim failed: ${e.message}. Falling back to byte-copy alternative...", e)
            trimAudioAlternative(inputPath, outputPath, startMs, endMs)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private suspend fun trimAudioAlternative(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(inputPath)
            if (!file.exists()) return@withContext false
            val totalBytes = file.length()
            if (totalBytes <= 0) return@withContext false
            
            var totalDurationMs = AudioPlayerManager.duration.value
            val track = AudioPlayerManager.currentTrack.value
            if (track != null && track.playlistId == -2) {
                totalDurationMs = AudioPlayerManager.radioElapsedTimeSec.value * 1000L
            }
            
            if (totalDurationMs <= 0) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(inputPath)
                    val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    totalDurationMs = durStr?.toLongOrNull() ?: 0L
                    retriever.release()
                } catch (_: Exception) {}
            }
            
            if (totalDurationMs <= 0) {
                Log.e(TAG, "Failed to determine duration of track for fallback byte trim.")
                return@withContext false
            }
            
            val startPercent = startMs.toFloat() / totalDurationMs.toFloat()
            val endPercent = endMs.toFloat() / totalDurationMs.toFloat()
            
            val startByte = (totalBytes * startPercent.coerceIn(0f, 1f)).toLong()
            val endByte = (totalBytes * endPercent.coerceIn(0f, 1f)).toLong()
            val bytesToCopy = endByte - startByte
            if (bytesToCopy <= 0) return@withContext false
            
            Log.d(TAG, "Alternative trimming starting. Total bytes: $totalBytes, Copying range of bytes: $startByte to $endByte ($bytesToCopy bytes)")
            
            val outputFile = File(outputPath)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            
            java.io.RandomAccessFile(file, "r").use { raf ->
                java.io.FileOutputStream(outputFile).use { fos ->
                    raf.seek(startByte)
                    val buffer = ByteArray(65536)
                    var bytesRemaining = bytesToCopy
                    while (bytesRemaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read == -1) break
                        fos.write(buffer, 0, read)
                        bytesRemaining -= read
                    }
                }
            }
            Log.d(TAG, "Alternative fallback trimming completed successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in alternative byte-range copy: ${e.message}", e)
            false
        }
    }
}
