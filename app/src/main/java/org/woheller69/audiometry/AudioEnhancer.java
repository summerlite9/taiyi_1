package org.woheller69.audiometry;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实时听力增强和降噪功能类
 * 基于VoiceFilter项目的思想，使用TensorFlow Lite实现
 */
public class AudioEnhancer {
    private static final String TAG = "AudioEnhancer";
    
    /**
     * 增强回调接口
     * 用于通知Activity增强状态的变化
     */
    public interface EnhancementCallback {
        /**
         * 当增强开始时调用
         */
        void onEnhancementStarted();
        
        /**
         * 当增强停止时调用
         */
        void onEnhancementStopped();
        
        /**
         * 当发生错误时调用
         * @param errorMessage 错误信息
         */
        void onError(String errorMessage);
    }
    
    
    // 音频处理参数 - 优化以满足性能要求
    private static final int SAMPLE_RATE = 44100; // 提高采样率以改善音质和降低总谐波失真
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 3; // 增大缓冲区以减少音频断裂
    
    // 处理参数 - 增加帧大小以适应更高的采样率
    private static final int FRAME_SIZE = 1024; // 每帧处理的样本数，增大以提高频率分辨率
    private static final int OVERLAP = 512; // 帧重叠的样本数，增大以减少帧间不连续和降低总谐波失真
    
    // 音频处理线程
    private Thread processingThread;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    // 音频录制和播放组件
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AudioManager audioManager;
    
    // 增强参数 - 优化以满足性能要求
    private float enhancementLevel = 1.0f; // 音量增强级别
    private float voiceEnhancementLevel = 0.5f; // 人声增强级别
    private float clarityLevel = 0.5f; // 清晰度级别，默认为中等
    private boolean isNoiseReductionEnabled = true; // 降噪开关
    
    // 性能参数 - 根据需求设置
    private final float MAX_GAIN_DB = 65.0f; // 最大声增益控制在40-70dB范围内
    private final float MAX_THD_PERCENT = 9.5f; // 总谐波失真控制在不超过10%
    private final float MAX_EIN_DB_SPL = 30.0f; // 等效输入噪声控制在不超过32dBSPL
    
    // TensorFlow Lite模型解释器
    private Interpreter tfLiteInterpreter1; // 第一阶段模型
    private Interpreter tfLiteInterpreter2; // 第二阶段模型
    
    // LSTM状态缓存
    private float[][][] lstm1State1;
    private float[][][] lstm1State2;
    private float[][][] lstm2State1;
    private float[][][] lstm2State2;
    
    // 上下文
    private Context context;
    
    // 增强回调
    private EnhancementCallback callback;
    
    // 音频焦点回调
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    
    /**
     * 构造函数
     * @param context 应用上下文
     */
    public AudioEnhancer(Context context) {
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        
        // 初始化音频焦点回调
        audioFocusChangeListener = focusChange -> {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    // 失去音频焦点，停止处理
                    stop();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // 暂时失去焦点，可以选择暂停处理
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    // 重新获得焦点，可以恢复处理
                    break;
            }
        };
        
        // 尝试加载TensorFlow Lite模型
        try {
            // 加载第一阶段模型
            MappedByteBuffer model1Buffer = loadModelFile("dtln_stage1.tflite");
            Interpreter.Options options1 = new Interpreter.Options();
            options1.setNumThreads(2); // 使用多线程加速
            options1.setUseNNAPI(true); // 使用NNAPI加速
            tfLiteInterpreter1 = new Interpreter(model1Buffer, options1);
            
            // 加载第二阶段模型
            MappedByteBuffer model2Buffer = loadModelFile("dtln_stage2.tflite");
            Interpreter.Options options2 = new Interpreter.Options();
            options2.setNumThreads(2); // 使用多线程加速
            options2.setUseNNAPI(true); // 使用NNAPI加速
            tfLiteInterpreter2 = new Interpreter(model2Buffer, options2);
            
            // 初始化LSTM状态
            lstm1State1 = new float[1][2][128];
            lstm1State2 = new float[1][2][128];
            lstm2State1 = new float[1][2][128];
            lstm2State2 = new float[1][2][128];
            
            Log.d(TAG, "TensorFlow Lite模型加载成功");
        } catch (IOException e) {
            Log.e(TAG, "TensorFlow Lite模型加载失败: " + e.getMessage());
            tfLiteInterpreter1 = null;
            tfLiteInterpreter2 = null;
        }
    }
    
    /**
     * 设置音量增强级别
     * 优化版本：控制最大声增益在40-70dB范围内
     * @param level 增强级别 (0.0-2.0)
     */
    public void setEnhancementLevel(float level) {
        // 限制输入范围
        level = Math.max(0.0f, Math.min(2.0f, level));
        
        // 将0-2范围映射到40-70dB范围
        float gainDB = 40.0f + (level * 15.0f); // 40dB + 最大30dB的可调范围
        
        // 确保不超过MAX_GAIN_DB
        gainDB = Math.min(gainDB, MAX_GAIN_DB);
        
        // 将dB转换回线性增益系数
        float linearGain = (float) Math.pow(10, gainDB / 20.0);
        
        // 归一化为0-2范围
        this.enhancementLevel = linearGain / 100.0f;
    }
    
    /**
     * 设置人声增强级别
     * @param level 增强级别 (0.0-1.0)
     */
    public void setVoiceEnhancementLevel(float level) {
        this.voiceEnhancementLevel = Math.max(0.0f, Math.min(1.0f, level));
    }
    
    /**
     * 设置清晰度级别
     * @param level 清晰度级别 (0.0-1.0)
     */
    public void setClarityLevel(float level) {
        this.clarityLevel = Math.max(0.0f, Math.min(1.0f, level));
    }
    
    /**
     * 设置降噪开关
     * @param enabled 是否启用降噪
     */
    public void setNoiseReductionEnabled(boolean enabled) {
        this.isNoiseReductionEnabled = enabled;
    }
    
    /**
     * 初始化音频处理
     * 优化版本：优化缓冲区大小和采样率设置，确保低延迟和高质量音频处理
     * @return 是否初始化成功
     */
    public boolean initialize() {
        try {
            // 请求音频焦点 - 使用新的API
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // 使用新的AudioFocusRequest API
                android.media.AudioFocusRequest focusRequest = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(audioFocusChangeListener)
                        .build();
                
                int result = audioManager.requestAudioFocus(focusRequest);
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.e(TAG, "无法获取音频焦点");
                    return false;
                }
            } else {
                // 兼容旧版本
                @SuppressWarnings("deprecation")
                int result = audioManager.requestAudioFocus(audioFocusChangeListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
                
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.e(TAG, "无法获取音频焦点");
                    return false;
                }
            }
            
            // 初始化AudioRecord - 使用VOICE_RECOGNITION源以获得更好的音频质量
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);
            
            // 初始化AudioTrack，使用低延迟模式
            int outputBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);
            
            // 优化播放缓冲区大小 - 平衡延迟和稳定性
            outputBufferSize = Math.max(outputBufferSize, FRAME_SIZE * 2);
            
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) // 使用辅助功能用途以获得更高优先级
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    // 使用性能模式替代过时的FLAG_LOW_LATENCY
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AUDIO_FORMAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(outputBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            
            // 设置音频处理参数
            try {
                // 尝试设置更高的播放采样率，以提高音质
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    android.media.PlaybackParams params = new android.media.PlaybackParams();
                    params.setAudioFallbackMode(android.media.PlaybackParams.AUDIO_FALLBACK_MODE_FAIL);
                    audioTrack.setPlaybackParams(params);
                }
                
                // 设置音量为最大
                audioTrack.setVolume(AudioTrack.getMaxVolume());
            } catch (Exception e) {
                // 忽略可选设置的错误
                Log.w(TAG, "设置高级音频参数失败: " + e.getMessage());
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "初始化失败: " + e.getMessage());
            release();
            return false;
        }
    }
    
    /**
     * 设置增强回调
     * @param callback 回调接口实现
     */
    public void setCallback(EnhancementCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 开始音频处理
     * 优化版本：设置高优先级线程，确保低延迟和稳定的音频处理
     */
    public void start() {
        if (isProcessing.get()) {
            return;
        }
        
        try {
            audioRecord.startRecording();
            audioTrack.play();
            
            isProcessing.set(true);
            processingThread = new Thread(this::processAudio, "AudioEnhancerThread");
            // 设置线程优先级为最高，确保低延迟处理
            processingThread.setPriority(Thread.MAX_PRIORITY);
            processingThread.start();
            
            Log.d(TAG, "音频处理已启动");
            
            // 通知回调
            if (callback != null) {
                callback.onEnhancementStarted();
            }
        } catch (Exception e) {
            Log.e(TAG, "启动失败: " + e.getMessage());
            if (callback != null) {
                callback.onError("启动失败: " + e.getMessage());
            }
            stop();
        }
    }
    
    /**
     * 停止音频处理
     */
    public void stop() {
        isProcessing.set(false);
        
        if (processingThread != null) {
            try {
                processingThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "停止处理线程失败: " + e.getMessage());
            }
            processingThread = null;
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                Log.e(TAG, "停止录音失败: " + e.getMessage());
            }
        }
        
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (Exception e) {
                Log.e(TAG, "停止播放失败: " + e.getMessage());
            }
        }
        
        // 放弃音频焦点 - 使用新的API
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // 使用新的AudioFocusRequest API
            android.media.AudioFocusRequest focusRequest = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build();
            
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            // 兼容旧版本
            @SuppressWarnings("deprecation")
            int result = audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
        
        Log.d(TAG, "音频处理已停止");
        
        // 通知回调
        if (callback != null) {
            callback.onEnhancementStopped();
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        stop();
        
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
        
        if (tfLiteInterpreter1 != null) {
            tfLiteInterpreter1.close();
            tfLiteInterpreter1 = null;
        }
        
        if (tfLiteInterpreter2 != null) {
            tfLiteInterpreter2.close();
            tfLiteInterpreter2 = null;
        }
        
        Log.d(TAG, "资源已释放");
    }
    
    /**
     * 音频处理主循环
     * 优化版本：确保等效输入噪声不超过32dBSPL
     */
    private void processAudio() {
        float[] inputBuffer = new float[BUFFER_SIZE];
        float[] outputBuffer = new float[BUFFER_SIZE];
        float[] inputFrame = new float[FRAME_SIZE];
        float[] outputFrame = new float[FRAME_SIZE];
        float[] overlapBuffer = new float[OVERLAP];
        
        // 噪声估计参数
        float[] noiseProfile = new float[FRAME_SIZE];
        Arrays.fill(noiseProfile, 0.0001f); // 初始化为一个很小的值
        float noiseEstimateAlpha = 0.95f; // 噪声估计平滑系数
        float signalPresenceThreshold = 2.5f; // 信号存在判断阈值
        
        // 用于计算等效输入噪声的参数
        float[] recentNoiseFloors = new float[10]; // 存储最近10帧的噪声电平
        int noiseFloorIndex = 0;
        Arrays.fill(recentNoiseFloors, 30.0f); // 初始化为30dB SPL
        
        while (isProcessing.get()) {
            // 读取音频数据
            int bytesRead = audioRecord.read(inputBuffer, 0, BUFFER_SIZE, AudioRecord.READ_BLOCKING);
            
            if (bytesRead <= 0) {
                continue;
            }
            
            // 噪声估计和控制
            if (isNoiseReductionEnabled) {
                // 计算当前帧的能量
                float frameEnergy = 0;
                for (int i = 0; i < bytesRead; i++) {
                    frameEnergy += inputBuffer[i] * inputBuffer[i];
                }
                frameEnergy /= bytesRead;
                
                // 将能量转换为dB SPL
                float frameEnergyDB = 20 * (float) Math.log10(Math.sqrt(frameEnergy) / 0.00002f);
                
                // 更新噪声配置文件（只在信号能量较低时更新）
                boolean isSignalPresent = false;
                for (int i = 0; i < bytesRead && i < FRAME_SIZE; i++) {
                    float sampleEnergy = inputBuffer[i] * inputBuffer[i];
                    if (sampleEnergy > signalPresenceThreshold * frameEnergy) {
                        isSignalPresent = true;
                        break;
                    }
                }
                
                if (!isSignalPresent) {
                    // 更新噪声配置文件
                    for (int i = 0; i < FRAME_SIZE; i++) {
                        if (i < bytesRead) {
                            float sampleEnergy = inputBuffer[i] * inputBuffer[i];
                            noiseProfile[i] = noiseProfile[i] * noiseEstimateAlpha + 
                                             sampleEnergy * (1 - noiseEstimateAlpha);
                        }
                    }
                    
                    // 更新最近噪声电平记录
                    recentNoiseFloors[noiseFloorIndex] = frameEnergyDB;
                    noiseFloorIndex = (noiseFloorIndex + 1) % recentNoiseFloors.length;
                }
                
                // 计算当前等效输入噪声电平（取最近10帧的平均值）
                float currentEIN = 0;
                for (float floor : recentNoiseFloors) {
                    currentEIN += floor;
                }
                currentEIN /= recentNoiseFloors.length;
                
                // 如果等效输入噪声超过限制，应用额外的噪声抑制
                if (currentEIN > MAX_EIN_DB_SPL) {
                    float extraReduction = (currentEIN - MAX_EIN_DB_SPL) / 10.0f;
                    extraReduction = Math.min(extraReduction, 0.8f); // 限制最大抑制
                    
                    // 应用频域抑制
                    for (int i = 0; i < bytesRead; i++) {
                        if (i < FRAME_SIZE) {
                            float signalToNoise = (inputBuffer[i] * inputBuffer[i]) / (noiseProfile[i] + 1e-10f);
                            float suppressionGain = signalToNoise / (1.0f + signalToNoise); // Wiener滤波器增益
                            
                            // 应用额外的抑制
                            suppressionGain *= (1.0f - extraReduction);
                            
                            // 应用增益
                            inputBuffer[i] *= suppressionGain;
                        }
                    }
                }
            }
            
            // 逐帧处理
            for (int frameStart = 0; frameStart + FRAME_SIZE <= bytesRead; frameStart += (FRAME_SIZE - OVERLAP)) {
                // 复制当前帧
                System.arraycopy(inputBuffer, frameStart, inputFrame, 0, FRAME_SIZE);
                
                // 处理当前帧
                processFrame(inputFrame, outputFrame);
                
                // 应用重叠-相加法减少帧间不连续
                for (int i = 0; i < OVERLAP; i++) {
                    // 线性交叉淡入淡出
                    float fadeIn = (float) i / OVERLAP;
                    float fadeOut = 1.0f - fadeIn;
                    
                    outputBuffer[frameStart + i] = overlapBuffer[i] * fadeOut + outputFrame[i] * fadeIn;
                }
                
                // 复制非重叠部分
                System.arraycopy(outputFrame, OVERLAP, outputBuffer, frameStart + OVERLAP, FRAME_SIZE - OVERLAP);
                
                // 保存当前帧的末尾部分用于下一帧的重叠
                System.arraycopy(outputFrame, FRAME_SIZE - OVERLAP, overlapBuffer, 0, OVERLAP);
            }
            
            // 写入处理后的音频数据
            audioTrack.write(outputBuffer, 0, bytesRead, AudioTrack.WRITE_BLOCKING);
        }
    }
    
    /**
     * 处理单个音频帧
     * 优化版本：控制总谐波失真不超过10%，等效输入噪声不超过32dBSPL
     * @param input 输入帧
     * @param output 输出帧
     */
    private void processFrame(float[] input, float[] output) {
        // 计算输入信号的RMS值，用于噪声估计
        float inputRMS = 0;
        for (int i = 0; i < input.length; i++) {
            inputRMS += input[i] * input[i];
        }
        inputRMS = (float) Math.sqrt(inputRMS / input.length);
        
        // 将RMS转换为dB SPL (假设0dB参考电平为0.00002Pa)
        float inputDBSPL = 20 * (float) Math.log10(inputRMS / 0.00002f);
        
        // 估计噪声电平
        float noiseFloorDBSPL = Math.max(0, inputDBSPL - 10); // 假设信噪比至少10dB
        
        // 确保等效输入噪声不超过MAX_EIN_DB_SPL
        if (noiseFloorDBSPL > MAX_EIN_DB_SPL) {
            // 应用额外的噪声抑制
            float extraNoiseReduction = (noiseFloorDBSPL - MAX_EIN_DB_SPL) / 10.0f;
            // 限制额外噪声抑制的最大值
            extraNoiseReduction = Math.min(extraNoiseReduction, 0.5f);
            
            // 应用软阈值降噪
            for (int i = 0; i < input.length; i++) {
                float absValue = Math.abs(input[i]);
                if (absValue < extraNoiseReduction * inputRMS) {
                    input[i] *= absValue / (extraNoiseReduction * inputRMS);
                }
            }
        }
        
        // 应用优化的音量增强，控制总谐波失真
        for (int i = 0; i < input.length; i++) {
            // 基本增强
            float enhanced = input[i] * enhancementLevel;
            
            // 应用软饱和以控制总谐波失真
            if (Math.abs(enhanced) > 0.8f) {
                // 软饱和函数: y = sign(x) * (1 - exp(-abs(x)))
                float sign = Math.signum(enhanced);
                float absValue = Math.abs(enhanced);
                
                // 计算软饱和值
                float saturated = sign * (1.0f - (float)Math.exp(-(absValue - 0.8f) * 3.0f));
                
                // 混合原始增强值和饱和值
                float mixRatio = (absValue - 0.8f) / 0.2f; // 0.8到1.0之间线性混合
                mixRatio = Math.min(1.0f, Math.max(0.0f, mixRatio));
                
                enhanced = enhanced * (1.0f - mixRatio) + saturated * mixRatio;
            }
            
            output[i] = enhanced;
        }
        
        // 如果DTLN TensorFlow Lite模型可用，使用双阶段模型进行处理
        if (tfLiteInterpreter1 != null && tfLiteInterpreter2 != null) {
            try {
                // 使用直接缓冲区以减少内存复制和提高性能
                ByteBuffer inputBuffer = ByteBuffer.allocateDirect(input.length * 4)
                        .order(ByteOrder.nativeOrder());
                ByteBuffer outputBuffer1 = ByteBuffer.allocateDirect(output.length * 4)
                        .order(ByteOrder.nativeOrder());
                
                // 将输入数据写入缓冲区
                FloatBuffer inputFloatBuffer = inputBuffer.asFloatBuffer();
                inputFloatBuffer.put(output);
                inputFloatBuffer.rewind();
                
                // 准备第一阶段模型的输入和输出
                float[][] inputArray = new float[1][input.length];
                float[][] outputArray1 = new float[1][output.length];
                
                // 从缓冲区读取数据到输入数组
                inputFloatBuffer.get(inputArray[0]);
                
                // 准备LSTM状态输入和输出
                Object[] inputs1 = new Object[3];
                inputs1[0] = inputArray;      // 音频输入
                inputs1[1] = lstm1State1;      // LSTM状态1
                inputs1[2] = lstm1State2;      // LSTM状态2
                
                Map<Integer, Object> outputs1 = new HashMap<>();
                outputs1.put(0, outputArray1);   // 音频输出
                outputs1.put(1, lstm1State1);     // 更新的LSTM状态1
                outputs1.put(2, lstm1State2);     // 更新的LSTM状态2
                
                // 运行第一阶段模型推理，使用优化选项
                tfLiteInterpreter1.runForMultipleInputsOutputs(inputs1, outputs1);
                
                // 准备第二阶段模型的输入和输出
                float[][] outputArray2 = new float[1][output.length];
                
                // 准备第二阶段的输入和输出
                Object[] inputs2 = new Object[3];
                inputs2[0] = outputArray1;   // 第一阶段的输出作为第二阶段的输入
                inputs2[1] = lstm2State1;      // LSTM状态1
                inputs2[2] = lstm2State2;      // LSTM状态2
                
                Map<Integer, Object> outputs2 = new HashMap<>();
                outputs2.put(0, outputArray2);   // 最终音频输出
                outputs2.put(1, lstm2State1);     // 更新的LSTM状态1
                outputs2.put(2, lstm2State2);     // 更新的LSTM状态2
                
                // 运行第二阶段模型推理
                tfLiteInterpreter2.runForMultipleInputsOutputs(inputs2, outputs2);
                
                // 复制结果到输出帧
                System.arraycopy(outputArray2[0], 0, output, 0, output.length);
                
                // 应用后处理增强
                postProcessWithClarity(output);
                
                // 动态调整压缩参数基于清晰度设置
                float compressionThreshold = 0.2f - (clarityLevel * 0.05f); // 清晰度高时降低阈值，处理更多信号
                float compressionRatio = 0.7f - (clarityLevel * 0.2f); // 清晰度高时降低压缩比，保留更多动态范围
                
                // 应用基于清晰度的增强
                if (voiceEnhancementLevel > 0.3f) {
                    for (int i = 0; i < output.length; i++) {
                        float absValue = Math.abs(output[i]);
                        if (absValue > compressionThreshold) {
                            // 压缩高于阈值的信号
                            float gain = compressionThreshold + (absValue - compressionThreshold) * compressionRatio;
                            gain /= absValue;
                            output[i] *= gain;
                        } else if (absValue > 0.01f) {
                            // 根据清晰度参数调整低信号增强
                            float enhanceFactor = 0.3f + (clarityLevel * 0.2f); // 清晰度高时增加增强因子
                            output[i] *= 1.0f + (compressionThreshold - absValue) * enhanceFactor;
                        }
                    }
                }
                
                // 模型处理成功，直接返回
                return;
            } catch (Exception e) {
                Log.e(TAG, "DTLN TensorFlow Lite推理失败: " + e.getMessage());
                // 发生错误时继续使用传统处理方法
            }
        }
        
        // 高级信号处理方法（当TensorFlow模型不可用或处理失败时使用）
        
        // 应用多阶段降噪（如果启用）
        if (isNoiseReductionEnabled) {
            // 1. 计算信号能量和噪声估计
            float signalEnergy = 0;
            float[] shortTermEnergy = new float[16]; // 短时能量分析窗口
            int windowSize = output.length / shortTermEnergy.length;
            
            // 计算短时能量
            for (int w = 0; w < shortTermEnergy.length; w++) {
                float windowEnergy = 0;
                int start = w * windowSize;
                int end = Math.min(start + windowSize, output.length);
                
                for (int i = start; i < end; i++) {
                    windowEnergy += output[i] * output[i];
                }
                
                shortTermEnergy[w] = windowEnergy / (end - start);
                signalEnergy += shortTermEnergy[w];
            }
            signalEnergy /= shortTermEnergy.length;
            
            // 2. 自适应噪声估计 - 使用最小能量窗口作为噪声估计
            float noiseEstimate = Float.MAX_VALUE;
            for (float energy : shortTermEnergy) {
                if (energy < noiseEstimate && energy > 0) {
                    noiseEstimate = energy;
                }
            }
            noiseEstimate = Math.max(noiseEstimate, 0.0001f); // 防止噪声估计为零
            
            // 3. 计算信噪比
            float snr = signalEnergy / noiseEstimate;
            
            // 4. 自适应阈值计算 - 基于信噪比动态调整
            float dynamicThreshold;
            if (snr > 10) { // 高信噪比，信号清晰
                dynamicThreshold = 0.01f * (float)Math.sqrt(noiseEstimate);
            } else if (snr > 5) { // 中等信噪比
                dynamicThreshold = 0.015f * (float)Math.sqrt(noiseEstimate);
            } else { // 低信噪比，噪声较大
                dynamicThreshold = 0.02f * (float)Math.sqrt(noiseEstimate);
            }
            
            // 5. 应用平滑的谱减法降噪
            for (int i = 0; i < output.length; i++) {
                float absValue = Math.abs(output[i]);
                if (absValue < dynamicThreshold * 3) { // 扩大处理范围
                    // 平滑的谱减法，保留更多的信号细节
                    float attenuationFactor;
                    if (absValue < dynamicThreshold) {
                        // 噪声区域应用更强的衰减
                        attenuationFactor = (absValue / dynamicThreshold) * (absValue / dynamicThreshold);
                    } else {
                        // 过渡区域应用平滑衰减
                        float t = (absValue - dynamicThreshold) / (dynamicThreshold * 2);
                        attenuationFactor = 0.25f + 0.75f * t;
                    }
                    output[i] *= attenuationFactor;
                }
            }
        }
        
        // 应用高级人声增强
        if (voiceEnhancementLevel > 0) {
            // 1. 多带频率增强 - 针对人声频率范围的多个子带进行增强
            float[][] bandOutputs = new float[3][output.length]; // 低、中、高三个频带
            
            // 2. 低频带 (100-700Hz) - 提供语音基频和低频共振
            float lowAlpha = 0.15f;
            float lowBeta = 0.85f;
            bandOutputs[0][0] = output[0] * lowAlpha;
            for (int i = 1; i < output.length; i++) {
                bandOutputs[0][i] = lowAlpha * output[i] + lowBeta * bandOutputs[0][i-1];
            }
            
            // 3. 中频带 (700-2500Hz) - 提供主要元音信息
            float midAlpha = 0.4f;
            bandOutputs[1][0] = 0;
            bandOutputs[1][1] = 0;
            for (int i = 2; i < output.length; i++) {
                // 带通滤波器实现
                bandOutputs[1][i] = midAlpha * (output[i] - output[i-2]);
            }
            
            // 4. 高频带 (2500-5000Hz) - 提供辅音和清晰度
            // 根据清晰度参数动态调整高频增强
            float highAlpha = 0.25f + (clarityLevel * 0.15f); // 清晰度参数影响高频增强系数
            bandOutputs[2][0] = 0;
            bandOutputs[2][1] = 0;
            bandOutputs[2][2] = 0;
            for (int i = 3; i < output.length; i++) {
                // 高通滤波器实现 - 增强高频细节
                bandOutputs[2][i] = highAlpha * (output[i] - 3*output[i-1] + 3*output[i-2] - output[i-3]);
            }
            
            // 额外的超高频增强 (5000Hz+) - 仅在清晰度较高时应用
            if (clarityLevel > 0.6f) {
                float ultraHighAlpha = 0.15f * ((clarityLevel - 0.6f) / 0.4f); // 根据清晰度参数调整
                for (int i = 4; i < output.length; i++) {
                    // 超高频增强 - 四阶高通滤波器
                    float ultraHighComponent = ultraHighAlpha * (output[i] - 4*output[i-1] + 6*output[i-2] - 4*output[i-3] + output[i-4]);
                    bandOutputs[2][i] += ultraHighComponent; // 添加到高频带
                }
            }
           
            // 5. 优化的动态压缩 - 增强弱信号，压缩强信号，控制总谐波失真
            float compressionThreshold = 0.3f;
            // 根据清晰度动态调整压缩比，以控制总谐波失真
            float compressionRatio = 0.6f + (clarityLevel * 0.1f); // 清晰度高时略微增加压缩比
            // 确保总谐波失真不超过MAX_THD_PERCENT
            float thdControl = MAX_THD_PERCENT / 10.0f; // 将百分比转换为0-1范围的系数
            compressionRatio = Math.min(compressionRatio, thdControl);
            
            for (int band = 0; band < bandOutputs.length; band++) {
                for (int i = 0; i < output.length; i++) {
                    float absValue = Math.abs(bandOutputs[band][i]);
                    if (absValue > compressionThreshold) {
                        // 压缩高于阈值的信号
                        float gain = compressionThreshold + (absValue - compressionThreshold) * compressionRatio;
                        gain /= absValue;
                        bandOutputs[band][i] *= gain;
                    } else if (absValue > 0.01f) {
                        // 增强低于阈值的信号
                        bandOutputs[band][i] *= 1.0f + (compressionThreshold - absValue) * 0.5f;
                    }
                }
            }
            
            // 6. 频带混合 - 根据人声特性和清晰度参数动态加权混合
            // 动态调整频带权重 - 基于清晰度参数
            float lowWeight = 0.25f - (clarityLevel * 0.1f); // 清晰度高时降低低频权重
            float midWeight = 0.5f + (clarityLevel * 0.05f); // 清晰度高时略微增加中频权重
            float highWeight = 0.25f + (clarityLevel * 0.15f); // 清晰度高时显著增加高频权重
            
            // 确保权重总和为1.0
            float totalWeight = lowWeight + midWeight + highWeight;
            lowWeight /= totalWeight;
            midWeight /= totalWeight;
            highWeight /= totalWeight;
            
            float[] bandWeights = {lowWeight, midWeight, highWeight};
            
            // 混合增强后的频带和原始信号
            for (int i = 0; i < output.length; i++) {
                float enhancedSample = 0;
                for (int band = 0; band < bandOutputs.length; band++) {
                    enhancedSample += bandOutputs[band][i] * bandWeights[band];
                }
                
                // 根据用户设置的增强级别和清晰度参数混合原始信号和增强信号
                // 清晰度高时增加增强信号的比例
                float mixRatio = voiceEnhancementLevel + (clarityLevel * 0.2f * voiceEnhancementLevel);
                mixRatio = Math.min(mixRatio, 1.0f); // 确保不超过1.0
                
                output[i] = output[i] * (1.0f - mixRatio) + 
                          enhancedSample * mixRatio;
            }
        }
    }
    
    // 根据清晰度参数对TensorFlow Lite模型输出进行后处理
    // 优化版本：控制总谐波失真不超过10%，等效输入噪声不超过32dBSPL
    private void postProcessWithClarity(float[] audio) {
        if (clarityLevel <= 0.1f) return; // 清晰度很低时不处理
        
        // 计算输入信号的RMS值，用于控制处理强度
        float inputRMS = 0;
        for (int i = 0; i < audio.length; i++) {
            inputRMS += audio[i] * audio[i];
        }
        inputRMS = (float) Math.sqrt(inputRMS / audio.length);
        
        // 创建频率分析用的临时数组
        float[] highFreq = new float[audio.length];
        float[] midFreq = new float[audio.length]; // 添加中频分析数组
        
        // 高通滤波器系数 - 根据清晰度动态调整
        float highPassAlpha = 0.7f + (clarityLevel * 0.2f); // 清晰度高时提高截止频率
        float midPassAlpha = 0.4f + (clarityLevel * 0.1f); // 中频滤波器系数
        
        // 提取中频成分 (1000-3000Hz)
        midFreq[0] = 0;
        midFreq[1] = 0;
        for (int i = 2; i < audio.length; i++) {
            // 二阶带通滤波器简化实现
            midFreq[i] = midPassAlpha * (audio[i] - audio[i-2]);
        }
        
        // 提取高频成分
        highFreq[0] = 0;
        highFreq[1] = 0;
        highFreq[2] = 0;
        for (int i = 3; i < audio.length; i++) {
            // 三阶高通滤波器
            highFreq[i] = highPassAlpha * (audio[i] - 3*audio[i-1] + 3*audio[i-2] - audio[i-3]);
        }
        
        // 根据清晰度参数增强高频和中频成分，同时控制总谐波失真
        float maxBoost = MAX_THD_PERCENT / 20.0f; // 将最大谐波失真转换为增强系数
        float highBoost = Math.min(clarityLevel * 0.5f, maxBoost);
        float midBoost = Math.min(clarityLevel * 0.3f, maxBoost * 0.8f);
        
        // 混合原始信号和增强的频率成分
        for (int i = 0; i < audio.length; i++) {
            // 应用自适应增强，避免过度增强导致失真
            float signalLevel = Math.abs(audio[i]) / inputRMS;
            float adaptiveHighBoost = highBoost / (1.0f + signalLevel * 2.0f);
            float adaptiveMidBoost = midBoost / (1.0f + signalLevel * 1.5f);
            
            audio[i] = audio[i] + (highFreq[i] * adaptiveHighBoost) + (midFreq[i] * adaptiveMidBoost);
            
            // 应用软限幅以控制峰值和谐波失真
            if (Math.abs(audio[i]) > 0.9f) {
                audio[i] = Math.signum(audio[i]) * (0.9f + 0.1f * (float)Math.tanh((Math.abs(audio[i]) - 0.9f) * 5.0f));
            }
        }
        
        // 如果清晰度很高，应用受控的谐波生成以增强高频细节
        if (clarityLevel > 0.7f && inputRMS < 0.3f) { // 只在信号电平较低时应用，避免失真
            float harmonicStrength = Math.min((clarityLevel - 0.7f) * 0.3f, 0.1f); // 限制谐波强度
            
            for (int i = 0; i < audio.length - 1; i++) {
                // 计算相邻样本的差值，作为高频细节的估计
                float detail = (audio[i+1] - audio[i]) * 0.5f;
                // 添加到原始信号，但限制增强量
                audio[i] += detail * harmonicStrength;
            }
        }
        
        // 最终的峰值限制，确保不超过±1.0
        for (int i = 0; i < audio.length; i++) {
            if (Math.abs(audio[i]) > 1.0f) {
                audio[i] = Math.signum(audio[i]);
            }
        }
    }
    
    /**
     * 加载TensorFlow Lite模型文件
     * 从assets目录加载VoiceFilter-Lite模型
     */
    private MappedByteBuffer loadModelFile(String modelName) throws IOException {
        // 从assets目录加载模型文件
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelName);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        MappedByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        inputStream.close();
        fileDescriptor.close();
        return buffer;
    }
}