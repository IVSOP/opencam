package dev.opencam.obs;

import android.annotation.SuppressLint;
import android.media.*;
import java.io.IOException;
import java.nio.ByteBuffer;

final class AudioStream {
    @SuppressLint("MissingPermission") // CameraService checks the microphone permission before entering.
    static void run(StreamServer.Peer peer) throws Exception {
        final int rate = 48000;
        AudioRecord recorder = null;
        MediaCodec encoder = null;
        try {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException("48 kHz audio input is unavailable");
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 8192));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Cannot open microphone");
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Microphone did not start");
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long samples = 0, baseUs = System.nanoTime() / 1000, lastInput = System.nanoTime();
            byte[] pcm = new byte[2048];
            boolean sentConfig = false;
            while (!peer.closed() && !Thread.currentThread().isInterrupted()) {
                int input = encoder.dequeueInputBuffer(10000);
                if (input >= 0) {
                    ByteBuffer b = encoder.getInputBuffer(input);
                    b.clear();
                    int n = recorder.read(pcm, 0, Math.min(pcm.length, b.remaining()) & ~1, AudioRecord.READ_NON_BLOCKING);
                    if (n < 0) throw new IOException("Microphone read failed: " + n);
                    b.put(pcm, 0, n);
                    encoder.queueInputBuffer(input, 0, n, baseUs + samples * 1_000_000 / rate, 0);
                    samples += n / 2;
                    if (n > 0) lastInput = System.nanoTime();
                }
                int output;
                while ((output = encoder.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        ByteBuffer config = encoder.getOutputFormat().getByteBuffer("csd-0");
                        if (!sentConfig && config != null) {
                            peer.send(Protocol.CONFIG_PTS, VideoStream.copy(config)); sentConfig = true;
                        }
                    } else if (output >= 0) {
                        try {
                            ByteBuffer b = encoder.getOutputBuffer(output);
                            b.position(info.offset); b.limit(info.offset + info.size);
                            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (!sentConfig) throw new IOException("AAC encoder omitted AudioSpecificConfig");
                                // Receiver discards the first access unit after the config to initialize AAC.
                                peer.send(info.presentationTimeUs, VideoStream.copy(b));
                            } else if (info.size > 0 && !sentConfig) {
                                peer.send(Protocol.CONFIG_PTS, VideoStream.copy(b)); sentConfig = true;
                            }
                        } finally { encoder.releaseOutputBuffer(output, false); }
                    }
                }
                if (System.nanoTime() - lastInput > 5_000_000_000L) throw new IOException("Microphone stopped delivering samples");
                Thread.sleep(5);
            }
        } finally {
            if (recorder != null) { try { recorder.stop(); } catch (IllegalStateException ignored) {} recorder.release(); }
            if (encoder != null) { try { encoder.stop(); } catch (IllegalStateException ignored) {} encoder.release(); }
        }
    }
}
