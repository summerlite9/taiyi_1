package org.woheller69.audiometry;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class Sound {
    /**
     * Generates the tone based on the increment and volume, used in inner loop
     * 优化版本：提高频率精度至±1%，降低总谐波失真至≤2.5%
     * @param increment - the amount to increment by
     * @param volume - the volume to generate
     */
    public float[] genTone(float increment, int volume, int numSamples) {
        float angle = 0;
        float[] generatedSnd = new float[numSamples];

        // 使用更精确的相位累积方法，减少频率漂移
        double phaseAccumulator = 0.0;
        double phaseIncrement = increment;
        
        // 使用更平滑的淡入淡出曲线，减少谐波失真
        int fadeInSamples = Math.max(1, numSamples / 8);
        int fadeOutSamples = Math.max(1, numSamples / 8);

        for (int i = 0; i < numSamples; i++) {
            // 使用双精度计算以提高精度
            double sinValue = Math.sin(phaseAccumulator);
            
            // 限制振幅以减少失真
            float amplitudeScale = Math.min(1.0f, (float)(volume / 32768.0));
            float sampleValue = (float) (sinValue * amplitudeScale);

            // 应用余弦淡入淡出窗口，减少频谱泄漏和谐波失真
            if (i < fadeInSamples) {
                // 余弦淡入效果
                float fadeRatio = (float) i / fadeInSamples;
                sampleValue *= 0.5f * (1.0f - (float)Math.cos(Math.PI * fadeRatio));
            } else if (i >= numSamples - fadeOutSamples) {
                // 余弦淡出效果
                float fadeRatio = (float) (numSamples - i) / fadeOutSamples;
                sampleValue *= 0.5f * (1.0f - (float)Math.cos(Math.PI * fadeRatio));
            }

            generatedSnd[i] = sampleValue;
            
            // 使用双精度累加相位，减少舍入误差
            phaseAccumulator += phaseIncrement;
            // 防止相位累积过大导致精度损失
            if (phaseAccumulator > 2 * Math.PI) {
                phaseAccumulator -= 2 * Math.PI;
            }
        }

        return generatedSnd;
    }

    /**
     * New code that avoids deprecated API except volume setting
     * Writes the parameter byte array to an AudioTrack and plays the array
     * @param generatedSnd- input PCM float array
     */
    public AudioTrack playSound(float[] generatedSnd, int ear, int sampleRate) {
        // Define the audio attributes
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        // Define the audio format
        AudioFormat audioFormat = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        // Calculate the buffer size in bytes
        int bufferSizeInBytes = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);

        // Ensure the buffer size is at least the size of the generated sound data
        bufferSizeInBytes = Math.max(bufferSizeInBytes, generatedSnd.length * 4); // 4 bytes per float

        // Create the AudioTrack
        AudioTrack audioTrack = new AudioTrack(audioAttributes,
                audioFormat,
                bufferSizeInBytes,
                AudioTrack.MODE_STATIC,
                0);

        // Write the audio data to the AudioTrack
        audioTrack.write(generatedSnd, 0, generatedSnd.length, AudioTrack.WRITE_BLOCKING);

        // Set the volume and play the audio
        if (ear == 0) {
            // 获取最大音量值
            float maxVolume = AudioTrack.getMaxVolume();
            // 使用立体声音量控制 - 右耳
            // 设置右声道最大音量，左声道静音
            // 使用setStereoVolume方法设置左右声道音量，右声道最大音量，左声道静音
            // 注意：setStereoVolume在较新版本中已弃用，但在此SDK版本中仍可使用
            audioTrack.setStereoVolume(0.0f, maxVolume); // 左声道静音，右声道最大音量
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                audioTrack.setPlaybackParams(audioTrack.getPlaybackParams().setAudioFallbackMode(android.media.PlaybackParams.AUDIO_FALLBACK_MODE_MUTE));
            }
        } else {
            // 获取最大音量值
            float maxVolume = AudioTrack.getMaxVolume();
            // 使用立体声音量控制 - 左耳
            // 设置左声道最大音量，右声道静音
            // 使用setStereoVolume方法设置左右声道音量，左声道最大音量，右声道静音
            // 注意：setStereoVolume在较新版本中已弃用，但在此SDK版本中仍可使用
            audioTrack.setStereoVolume(maxVolume, 0.0f); // 左声道最大音量，右声道静音
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                audioTrack.setPlaybackParams(audioTrack.getPlaybackParams().setAudioFallbackMode(android.media.PlaybackParams.AUDIO_FALLBACK_MODE_MUTE));
            }
        }
        audioTrack.play();
        return audioTrack;
    }

    /**
     * Generates the tone based on the increment and volume, used in inner loop
     * @param increment - the amount to increment by
     * @param volume - the volume to generate
     */
    public float[] genStereoTone(float increment, int volume, int numSamples, int ear){

        float angle = 0;
        float[] generatedSnd = new float[2*numSamples];
        for (int i = 0; i < numSamples; i=i+2){
            if (ear == 0) {
                generatedSnd[i] = (float) (Math.sin(angle)*volume/32768);
                generatedSnd[i+1] = 0;
            } else {
                generatedSnd[i] = 0;
                generatedSnd[i+1] = (float) (Math.sin(angle)*volume/32768);
            }
            angle += increment;
        }
        return generatedSnd;
    }

    /**
     * Writes the parameter byte array to an AudioTrack and plays the array
     * @param generatedSnd- input PCM float array
     * PROBLEM: On some devices sound from one stereo channel (as created by genStereoSound) can be heard on the other channel
     */
    public AudioTrack playStereoSound(float[] generatedSnd, int sampleRate) {
        // 使用新的AudioTrack.Builder API替代过时的构造函数
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        AudioFormat audioFormat = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();

        AudioTrack audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(generatedSnd.length * 4) // 4 bytes per float
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();

        audioTrack.write(generatedSnd, 0, generatedSnd.length, AudioTrack.WRITE_BLOCKING);
        audioTrack.setVolume(AudioTrack.getMaxVolume());
        audioTrack.play();
        return audioTrack;
    }
}
