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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实时听力增强和降噪功能类
 * 基于VoiceFilter项目的思想，使用TensorFlow Lite实现
 */
public class AudioEnhancer {
    private static final String TAG = "AudioEnhancer";
    
    // 音频配置参数 - 提高采样率以获得更好的音质
    private static final int SAMPLE_RATE = 44100; // 44.1kHz 提供更好的音质
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 3; // 增大缓冲区以减少音频断裂
    
    // 处理参数 - 增加帧大小以适应更高的采样率
    private static final int FRAME_SIZE = 1024; // 每帧处理的样本数，增大以适应44.1kHz采样率
    private static final int OVERLAP = 512; // 帧重叠的样本数，增大以提供更平滑的过渡
    
    // 音频处理线程
    private Thread processingThread;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    // 音频录制和播放组件
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    
    // TensorFlow Lite解释器 - DTLN是双阶段模型，需要两个解释器
    private Interpreter tfLiteInterpreter1; // 第一阶段模型 - 频域处理
    private Interpreter tfLiteInterpreter2; // 第二阶段模型 - 时域处理
    
    // DTLN模型状态 - 用于保持LSTM状态的连续性
    private float[][][] lstm1State1;
    private float[][][] lstm1State2;
    private float[][][] lstm2State1;
    private float[][][] lstm2State2;
    
    // 上下文
    private final Context context;
    
    // 听力增强参数
    private float enhancementLevel = 1.0f; // 增强级别，默认为1.0（正常）
    private boolean isNoiseReductionEnabled = true; // 降噪开关
    private float voiceEnhancementLevel = 0.4f; // 人声增强级别，默认为0.4
    private float clarityLevel = 0.5f; // 清晰度级别，默认为0.5
    
    // 回调接口
    public interface EnhancementCallback {
        void onEnhancementStarted();
        void onEnhancementStopped();
        void onError(String errorMessage);
    }
    
    private EnhancementCallback callback;
    
    /**
     * 构造函数
     * @param context 应用上下文
     */
    public AudioEnhancer(Context context) {
        this.context = context;
    }
    
    /**
     * 设置回调
     * @param callback 回调接口
     */
    public void setCallback(EnhancementCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 设置听力增强级别
     * @param level 增强级别 (0.0-2.0)
     */
    public void setEnhancementLevel(float level) {
        if (level < 0.0f) level = 0.0f;
        if (level > 2.0f) level = 2.0f;
        this.enhancementLevel = level;
    }
    
    /**
     * 设置降噪开关
     * @param enabled 是否启用降噪
     */
    public void setNoiseReductionEnabled(boolean enabled) {
        this.isNoiseReductionEnabled = enabled;
    }
    
    /**
     * 设置人声增强级别
     * @param level 人声增强级别 (0.0-1.0)
     */
    public void setVoiceEnhancementLevel(float level) {
        if (level < 0.0f) level = 0.0f;
        if (level > 1.0f) level = 1.0f;
        this.voiceEnhancementLevel = level;
    }
    
    /**
     * 设置清晰度级别
     * @param level 清晰度级别 (0.0-1.0)
     */
    public void setClarityLevel(float level) {
        if (level < 0.0f) level = 0.0f;
        if (level > 1.0f) level = 1.0f;
        this.clarityLevel = level;
    }
    
    /**
     * 初始化音频增强器
     * @return 是否初始化成功
     */
    public boolean initialize() {
        try {
            // 初始化AudioRecord
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE);
            
            // 初始化AudioTrack（使用优化的Builder API配置）
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) // 使用辅助功能类型以获得更高优先级
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setFlags(android.media.AudioAttributes.FLAG_LOW_LATENCY) // 添加低延迟标志
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(BUFFER_SIZE)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) // 使用低延迟性能模式
                    .build();
            
            // 请求音频焦点以提高音频质量
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            android.media.AudioFocusRequest focusRequest = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        // 处理音频焦点变化
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            // 如果失去焦点，可以考虑暂停处理
                        }
                    })
                    .build();
            audioManager.requestAudioFocus(focusRequest);
            
            // 初始化TensorFlow Lite解释器 - DTLN双阶段模型
            try {
                // 配置TensorFlow Lite解释器选项
                Interpreter.Options options = new Interpreter.Options();
                options.setNumThreads(4); // 使用多线程提高性能
                options.setUseNNAPI(true); // 尝试使用神经网络API加速
                
                // 加载第一阶段模型（频域处理）
                tfLiteInterpreter1 = new Interpreter(loadModelFile("audio_enhancer.tflite"), options);
                // 加载第二阶段模型（时域处理）
                tfLiteInterpreter2 = new Interpreter(loadModelFile("audio_enhancer_2.tflite"), options);
                
                // 初始化LSTM状态 - 根据DTLN模型的LSTM层配置
                // 第一阶段模型的LSTM状态
                lstm1State1 = new float[1][2][128]; // [batch_size, num_directions, hidden_size]
                lstm1State2 = new float[1][2][128]; // [batch_size, num_directions, hidden_size]
                
                // 第二阶段模型的LSTM状态
                lstm2State1 = new float[1][2][128]; // [batch_size, num_directions, hidden_size]
                lstm2State2 = new float[1][2][128]; // [batch_size, num_directions, hidden_size]
                
                Log.i(TAG, "DTLN TensorFlow Lite模型加载成功，使用优化配置");
            } catch (IOException e) {
                Log.e(TAG, "DTLN TensorFlow Lite模型加载失败: " + e.getMessage());
                // 模型加载失败时，我们仍然可以使用高级信号处理方法
            } catch (Exception e) {
                // 捕获其他可能的异常，如NNAPI不可用
                Log.e(TAG, "TensorFlow Lite初始化异常: " + e.getMessage());
                try {
                    // 如果NNAPI失败，尝试使用CPU后端
                    Interpreter.Options fallbackOptions = new Interpreter.Options();
                    fallbackOptions.setNumThreads(2);
                    fallbackOptions.setUseNNAPI(false);
                    
                    tfLiteInterpreter1 = new Interpreter(loadModelFile("audio_enhancer.tflite"), fallbackOptions);
                    tfLiteInterpreter2 = new Interpreter(loadModelFile("audio_enhancer_2.tflite"), fallbackOptions);
                    
                    Log.i(TAG, "TensorFlow Lite模型使用CPU后端加载成功");
                } catch (Exception e2) {
                    Log.e(TAG, "TensorFlow Lite CPU后端初始化失败: " + e2.getMessage());
                    tfLiteInterpreter1 = null;
                    tfLiteInterpreter2 = null;
                }
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "初始化失败: " + e.getMessage());
            if (callback != null) {
                callback.onError("初始化失败: " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * 开始音频增强处理
     */
    public void start() {
        if (isProcessing.get()) {
            return; // 已经在处理中
        }
        
        if (audioRecord == null || audioTrack == null) {
            if (!initialize()) {
                return;
            }
        }
        
        isProcessing.set(true);
        
        try {
            audioRecord.startRecording();
            audioTrack.play();
            
            processingThread = new Thread(this::processAudio);
            processingThread.start();
            
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> 
                        callback.onEnhancementStarted());
            }
        } catch (Exception e) {
            Log.e(TAG, "启动失败: " + e.getMessage());
            stop();
            if (callback != null) {
                callback.onError("启动失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 停止音频增强处理
     */
    public void stop() {
        isProcessing.set(false);
        
        if (processingThread != null) {
            try {
                processingThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "停止处理线程时出错: " + e.getMessage());
            }
            processingThread = null;
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                Log.e(TAG, "停止录音时出错: " + e.getMessage());
            }
        }
        
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (Exception e) {
                Log.e(TAG, "停止播放时出错: " + e.getMessage());
            }
        }
        
        if (callback != null) {
            new Handler(Looper.getMainLooper()).post(() -> 
                    callback.onEnhancementStopped());
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
      // 释放TensorFlow Lite解释器
        if (tfLiteInterpreter1 != null) {
            tfLiteInterpreter1.close();
            tfLiteInterpreter1 = null;
        }
        
        if (tfLiteInterpreter2 != null) {
            tfLiteInterpreter2.close();
            tfLiteInterpreter2 = null;
        }
        
        // 清除LSTM状态
        lstm1State1 = null;
        lstm1State2 = null;
        lstm2State1 = null;
        lstm2State2 = null;
    }
    
    /**
     * 音频处理主循环
     */
    private void processAudio() {
        // 创建缓冲区
        float[] inputBuffer = new float[BUFFER_SIZE / 4]; // 4字节每个float
        float[] outputBuffer = new float[BUFFER_SIZE / 4];
        
        // 创建处理帧缓冲区
        float[] processingFrame = new float[FRAME_SIZE];
        float[] outputFrame = new float[FRAME_SIZE];
        
        // 创建重叠-相加法所需的历史缓冲区
        float[] prevFrame = new float[OVERLAP];
        
        int frameIndex = 0;
        
        while (isProcessing.get()) {
            // 读取音频数据
            int bytesRead = audioRecord.read(inputBuffer, 0, inputBuffer.length, AudioRecord.READ_BLOCKING);
            
            if (bytesRead <= 0) {
                continue;
            }
            
            // 处理每一帧
            for (int i = 0; i < bytesRead - FRAME_SIZE; i += (FRAME_SIZE - OVERLAP)) {
                // 复制当前帧到处理缓冲区
                System.arraycopy(inputBuffer, i, processingFrame, 0, FRAME_SIZE);
                
                // 处理当前帧
                processFrame(processingFrame, outputFrame);
                
                // 重叠-相加法：将前一帧的重叠部分与当前帧的开始部分混合
                for (int j = 0; j < OVERLAP; j++) {
                    outputFrame[j] = (outputFrame[j] + prevFrame[j]) * 0.5f;
                }
                
                // 保存当前帧的结尾部分用于下一次重叠
                System.arraycopy(outputFrame, FRAME_SIZE - OVERLAP, prevFrame, 0, OVERLAP);
                
                // 复制处理后的帧到输出缓冲区
                System.arraycopy(outputFrame, 0, outputBuffer, frameIndex, FRAME_SIZE - OVERLAP);
                frameIndex += (FRAME_SIZE - OVERLAP);
                
                // 如果输出缓冲区已满，写入AudioTrack并重置索引
                if (frameIndex >= outputBuffer.length - FRAME_SIZE) {
                    audioTrack.write(outputBuffer, 0, frameIndex, AudioTrack.WRITE_BLOCKING);
                    frameIndex = 0;
                }
            }
        }
    }
    
    /**
     * 处理单个音频帧
     * @param input 输入帧
     * @param output 输出帧
     */
    private void processFrame(float[] input, float[] output) {
        // 首先应用基本的音量增强
        for (int i = 0; i < input.length; i++) {
            output[i] = input[i] * enhancementLevel;
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
            
            // 5. 动态压缩 - 增强弱信号，压缩强信号
            float compressionThreshold = 0.3f;
            float compressionRatio = 0.6f;
            
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
    private void postProcessWithClarity(float[] audio) {
        if (clarityLevel <= 0.1f) return; // 清晰度很低时不处理
        
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
        
        // 根据清晰度参数增强高频和中频成分
        float highBoost = clarityLevel * 0.5f;
        float midBoost = clarityLevel * 0.3f;
        
        // 混合原始信号和增强的频率成分
        for (int i = 0; i < audio.length; i++) {
            audio[i] = audio[i] + (highFreq[i] * highBoost) + (midFreq[i] * midBoost);
        }
        
        // 如果清晰度很高，应用轻微的谐波生成以增强高频细节
        if (clarityLevel > 0.7f) {
            for (int i = 0; i < audio.length - 1; i++) {
                // 计算相邻样本的差值，作为高频细节的估计
                float detail = (audio[i+1] - audio[i]) * 0.5f;
                // 添加到原始信号
                audio[i] += detail * (clarityLevel - 0.7f) * 0.5f;
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
