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
 * 瀹炴椂鍚姏澧炲己鍜岄檷鍣姛鑳界被
 * 鍩轰簬VoiceFilter椤圭洰鐨勬€濇兂锛屼娇鐢═ensorFlow Lite瀹炵幇
 */
public class AudioEnhancer {
    private static final String TAG = "AudioEnhancer";
    
    // 闊抽閰嶇疆鍙傛暟 - 鎻愰珮閲囨牱鐜囦互鑾峰緱鏇村ソ鐨勯煶璐?    private static final int SAMPLE_RATE = 44100; // 44.1kHz 鎻愪緵鏇村ソ鐨勯煶璐?    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 3; // 澧炲ぇ缂撳啿鍖轰互鍑忓皯闊抽鏂
    
    // 澶勭悊鍙傛暟 - 澧炲姞甯уぇ灏忎互閫傚簲鏇撮珮鐨勯噰鏍风巼
    private static final int FRAME_SIZE = 1024; // 姣忓抚澶勭悊鐨勬牱鏈暟锛屽澶т互閫傚簲44.1kHz閲囨牱鐜?    private static final int OVERLAP = 512; // 甯ч噸鍙犵殑鏍锋湰鏁帮紝澧炲ぇ浠ユ彁渚涙洿骞虫粦鐨勮繃娓?    
    // 闊抽澶勭悊绾跨▼
    private Thread processingThread;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    // 闊抽褰曞埗鍜屾挱鏀剧粍浠?    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    
    // TensorFlow Lite瑙ｉ噴鍣?- DTLN鏄弻闃舵妯″瀷锛岄渶瑕佷袱涓В閲婂櫒
    private Interpreter tfLiteInterpreter1; // 绗竴闃舵妯″瀷 - 棰戝煙澶勭悊
    private Interpreter tfLiteInterpreter2; // 绗簩闃舵妯″瀷 - 鏃跺煙澶勭悊
    
    // DTLN妯″瀷鐘舵€?- 鐢ㄤ簬淇濇寔LSTM鐘舵€佺殑杩炵画鎬?    private float[][][] lstm1State1;
    private float[][][] lstm1State2;
    private float[][][] lstm2State1;
    private float[][][] lstm2State2;
    
    // 涓婁笅鏂?    private final Context context;
    
    // 鍚姏澧炲己鍙傛暟
    private float enhancementLevel = 1.0f; // 澧炲己绾у埆锛岄粯璁や负1.0锛堟甯革級
    private boolean isNoiseReductionEnabled = true; // 闄嶅櫔寮€鍏?    private float voiceEnhancementLevel = 0.4f; // 浜哄０澧炲己绾у埆锛岄粯璁や负0.4
    private float clarityLevel = 0.5f; // 娓呮櫚搴︾骇鍒紝榛樿涓?.5
    
    // 鍥炶皟鎺ュ彛
    public interface EnhancementCallback {
        void onEnhancementStarted();
        void onEnhancementStopped();
        void onError(String errorMessage);
    }
    
    private EnhancementCallback callback;
    
    /**
     * 鏋勯€犲嚱鏁?     * @param context 搴旂敤涓婁笅鏂?     */
    public AudioEnhancer(Context context) {
        this.context = context;
    }
    
    /**
     * 璁剧疆鍥炶皟
     * @param callback 鍥炶皟鎺ュ彛
     */
    public void setCallback(EnhancementCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 璁剧疆鍚姏澧炲己绾у埆
     * @param level 澧炲己绾у埆 (0.0-2.0)
     */
    public void setEnhancementLevel(float level) {
        if (level < 0.0f) level = 0.0f;
        if (level > 2.0f) level = 2.0f;
        this.enhancementLevel = level;
    }
    
    /**
     * 璁剧疆闄嶅櫔寮€鍏?     * @param enabled 鏄惁鍚敤闄嶅櫔
     */
    public void setNoiseReductionEnabled(boolean enabled) {
        this.isNoiseReductionEnabled = enabled;
    }
    
    /**
     * 璁剧疆浜哄０澧炲己绾у埆
     * @param level 浜哄０澧炲己绾у埆 (0.0-1.0)
     */
    public void setVoiceEnhancementLevel(float level) {
        if (level < 0.0f) level = 0.0f;
        if (level > 1.0f) level = 1.0f;
        this.voiceEnhancementLevel = level;
    }
    
    /**
     * 璁剧疆娓呮櫚搴︾骇鍒?     * @param level 娓呮櫚搴︾骇鍒?(0.0-1.0)
     */
    public void setClarityLevel(float level) {
        if (level < 0.0f) level = 0.0f;
        if (level > 1.0f) level = 1.0f;
        this.clarityLevel = level;
    }
    
    /**
     * 鍒濆鍖栭煶棰戝寮哄櫒
     * @return 鏄惁鍒濆鍖栨垚鍔?     */
    public boolean initialize() {
        try {
            // 鍒濆鍖朅udioRecord
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE);
            
            // 鍒濆鍖朅udioTrack锛堜娇鐢ㄤ紭鍖栫殑Builder API閰嶇疆锛?            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) // 浣跨敤杈呭姪鍔熻兘绫诲瀷浠ヨ幏寰楁洿楂樹紭鍏堢骇
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setFlags(android.media.AudioAttributes.FLAG_LOW_LATENCY) // 娣诲姞浣庡欢杩熸爣蹇?                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(BUFFER_SIZE)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) // 浣跨敤浣庡欢杩熸€ц兘妯″紡
                    .build();
            
            // 璇锋眰闊抽鐒︾偣浠ユ彁楂橀煶棰戣川閲?            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            android.media.AudioFocusRequest focusRequest = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        // 澶勭悊闊抽鐒︾偣鍙樺寲
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            // 濡傛灉澶卞幓鐒︾偣锛屽彲浠ヨ€冭檻鏆傚仠澶勭悊
                        }
                    })
                    .build();
            audioManager.requestAudioFocus(focusRequest);
            
            // 鍒濆鍖朤ensorFlow Lite瑙ｉ噴鍣?- DTLN鍙岄樁娈垫ā鍨?            try {
                // 閰嶇疆TensorFlow Lite瑙ｉ噴鍣ㄩ€夐」
                Interpreter.Options options = new Interpreter.Options();
                options.setNumThreads(4); // 浣跨敤澶氱嚎绋嬫彁楂樻€ц兘
                options.setUseNNAPI(true); // 灏濊瘯浣跨敤绁炵粡缃戠粶API鍔犻€?                
                // 鍔犺浇绗竴闃舵妯″瀷锛堥鍩熷鐞嗭級
                tfLiteInterpreter1 = new Interpreter(loadModelFile("audio_enhancer.tflite"), options);
                // 鍔犺浇绗簩闃舵妯″瀷锛堟椂鍩熷鐞嗭級
                tfLiteInterpreter2 = new Interpreter(loadModelFile("audio_enhancer_2.tflite"), options);
                
                // 鍒濆鍖朙STM鐘舵€?- 鏍规嵁DTLN妯″瀷鐨凩STM灞傞厤缃?                // 绗竴闃舵妯″瀷鐨凩STM鐘舵€?                lstm1State1 = new float[1][2][128]; // [batch_size, num_directions, hidden_size]
                lstm1State2 = new float[1][2][128]; // [batch_size, num_directions, hidden_size]
                
                // 绗簩闃舵妯″瀷鐨凩STM鐘舵€?                lstm2State1 = new float[1][2][128]; // [batch_size, num_directions, hidden_size]
                lstm2State2 = new float[1][2][128]; // [batch_size, num_directions, hidden_size]
                
                Log.i(TAG, "DTLN TensorFlow Lite妯″瀷鍔犺浇鎴愬姛锛屼娇鐢ㄤ紭鍖栭厤缃?);
            } catch (IOException e) {
                Log.e(TAG, "DTLN TensorFlow Lite妯″瀷鍔犺浇澶辫触: " + e.getMessage());
                // 妯″瀷鍔犺浇澶辫触鏃讹紝鎴戜滑浠嶇劧鍙互浣跨敤楂樼骇淇″彿澶勭悊鏂规硶
            } catch (Exception e) {
                // 鎹曡幏鍏朵粬鍙兘鐨勫紓甯革紝濡侼NAPI涓嶅彲鐢?                Log.e(TAG, "TensorFlow Lite鍒濆鍖栧紓甯? " + e.getMessage());
                try {
                    // 濡傛灉NNAPI澶辫触锛屽皾璇曚娇鐢–PU鍚庣
                    Interpreter.Options fallbackOptions = new Interpreter.Options();
                    fallbackOptions.setNumThreads(2);
                    fallbackOptions.setUseNNAPI(false);
                    
                    tfLiteInterpreter1 = new Interpreter(loadModelFile("audio_enhancer.tflite"), fallbackOptions);
                    tfLiteInterpreter2 = new Interpreter(loadModelFile("audio_enhancer_2.tflite"), fallbackOptions);
                    
                    Log.i(TAG, "TensorFlow Lite妯″瀷浣跨敤CPU鍚庣鍔犺浇鎴愬姛");
                } catch (Exception e2) {
                    Log.e(TAG, "TensorFlow Lite CPU鍚庣鍒濆鍖栧け璐? " + e2.getMessage());
                    tfLiteInterpreter1 = null;
                    tfLiteInterpreter2 = null;
                }
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "鍒濆鍖栧け璐? " + e.getMessage());
            if (callback != null) {
                callback.onError("鍒濆鍖栧け璐? " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * 寮€濮嬮煶棰戝寮哄鐞?     */
    public void start() {
        if (isProcessing.get()) {
            return; // 宸茬粡鍦ㄥ鐞嗕腑
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
            Log.e(TAG, "鍚姩澶辫触: " + e.getMessage());
            stop();
            if (callback != null) {
                callback.onError("鍚姩澶辫触: " + e.getMessage());
            }
        }
    }
    
    /**
     * 鍋滄闊抽澧炲己澶勭悊
     */
    public void stop() {
        isProcessing.set(false);
        
        if (processingThread != null) {
            try {
                processingThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "鍋滄澶勭悊绾跨▼鏃跺嚭閿? " + e.getMessage());
            }
            processingThread = null;
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                Log.e(TAG, "鍋滄褰曢煶鏃跺嚭閿? " + e.getMessage());
            }
        }
        
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (Exception e) {
                Log.e(TAG, "鍋滄鎾斁鏃跺嚭閿? " + e.getMessage());
            }
        }
        
        if (callback != null) {
            new Handler(Looper.getMainLooper()).post(() -> 
                    callback.onEnhancementStopped());
        }
    }
    
    /**
     * 閲婃斁璧勬簮
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
      // 閲婃斁TensorFlow Lite瑙ｉ噴鍣?        if (tfLiteInterpreter1 != null) {
            tfLiteInterpreter1.close();
            tfLiteInterpreter1 = null;
        }
        
        if (tfLiteInterpreter2 != null) {
            tfLiteInterpreter2.close();
            tfLiteInterpreter2 = null;
        }
        
        // 娓呴櫎LSTM鐘舵€?        lstm1State1 = null;
        lstm1State2 = null;
        lstm2State1 = null;
        lstm2State2 = null;
    }
    
    /**
     * 闊抽澶勭悊涓诲惊鐜?     */
    private void processAudio() {
        // 鍒涘缓缂撳啿鍖?        float[] inputBuffer = new float[BUFFER_SIZE / 4]; // 4瀛楄妭姣忎釜float
        float[] outputBuffer = new float[BUFFER_SIZE / 4];
        
        // 鍒涘缓澶勭悊甯х紦鍐插尯
        float[] processingFrame = new float[FRAME_SIZE];
        float[] outputFrame = new float[FRAME_SIZE];
        
        // 鍒涘缓閲嶅彔-鐩稿姞娉曟墍闇€鐨勫巻鍙茬紦鍐插尯
        float[] prevFrame = new float[OVERLAP];
        
        int frameIndex = 0;
        
        while (isProcessing.get()) {
            // 璇诲彇闊抽鏁版嵁
            int bytesRead = audioRecord.read(inputBuffer, 0, inputBuffer.length, AudioRecord.READ_BLOCKING);
            
            if (bytesRead <= 0) {
                continue;
            }
            
            // 澶勭悊姣忎竴甯?            for (int i = 0; i < bytesRead - FRAME_SIZE; i += (FRAME_SIZE - OVERLAP)) {
                // 澶嶅埗褰撳墠甯у埌澶勭悊缂撳啿鍖?                System.arraycopy(inputBuffer, i, processingFrame, 0, FRAME_SIZE);
                
                // 澶勭悊褰撳墠甯?                processFrame(processingFrame, outputFrame);
                
                // 閲嶅彔-鐩稿姞娉曪細灏嗗墠涓€甯х殑閲嶅彔閮ㄥ垎涓庡綋鍓嶅抚鐨勫紑濮嬮儴鍒嗘贩鍚?                for (int j = 0; j < OVERLAP; j++) {
                    outputFrame[j] = (outputFrame[j] + prevFrame[j]) * 0.5f;
                }
                
                // 淇濆瓨褰撳墠甯х殑缁撳熬閮ㄥ垎鐢ㄤ簬涓嬩竴娆￠噸鍙?                System.arraycopy(outputFrame, FRAME_SIZE - OVERLAP, prevFrame, 0, OVERLAP);
                
                // 澶嶅埗澶勭悊鍚庣殑甯у埌杈撳嚭缂撳啿鍖?                System.arraycopy(outputFrame, 0, outputBuffer, frameIndex, FRAME_SIZE - OVERLAP);
                frameIndex += (FRAME_SIZE - OVERLAP);
                
                // 濡傛灉杈撳嚭缂撳啿鍖哄凡婊★紝鍐欏叆AudioTrack骞堕噸缃储寮?                if (frameIndex >= outputBuffer.length - FRAME_SIZE) {
                    audioTrack.write(outputBuffer, 0, frameIndex, AudioTrack.WRITE_BLOCKING);
                    frameIndex = 0;
                }
            }
        }
    }
    
    /**
     * 澶勭悊鍗曚釜闊抽甯?     * @param input 杈撳叆甯?     * @param output 杈撳嚭甯?     */
    private void processFrame(float[] input, float[] output) {
        // 棣栧厛搴旂敤鍩烘湰鐨勯煶閲忓寮?        for (int i = 0; i < input.length; i++) {
            output[i] = input[i] * enhancementLevel;
        }
        
        // 濡傛灉DTLN TensorFlow Lite妯″瀷鍙敤锛屼娇鐢ㄥ弻闃舵妯″瀷杩涜澶勭悊
        if (tfLiteInterpreter1 != null && tfLiteInterpreter2 != null) {
            try {
                // 浣跨敤鐩存帴缂撳啿鍖轰互鍑忓皯鍐呭瓨澶嶅埗鍜屾彁楂樻€ц兘
                ByteBuffer inputBuffer = ByteBuffer.allocateDirect(input.length * 4)
                        .order(ByteOrder.nativeOrder());
                ByteBuffer outputBuffer1 = ByteBuffer.allocateDirect(output.length * 4)
                        .order(ByteOrder.nativeOrder());
                
                // 灏嗚緭鍏ユ暟鎹啓鍏ョ紦鍐插尯
                FloatBuffer inputFloatBuffer = inputBuffer.asFloatBuffer();
                inputFloatBuffer.put(output);
                inputFloatBuffer.rewind();
                
                // 鍑嗗绗竴闃舵妯″瀷鐨勮緭鍏ュ拰杈撳嚭
                float[][] inputArray = new float[1][input.length];
                float[][] outputArray1 = new float[1][output.length];
                
                // 浠庣紦鍐插尯璇诲彇鏁版嵁鍒拌緭鍏ユ暟缁?                inputFloatBuffer.get(inputArray[0]);
                
                // 鍑嗗LSTM鐘舵€佽緭鍏ュ拰杈撳嚭
                Object[] inputs1 = new Object[3];
                inputs1[0] = inputArray;      // 闊抽杈撳叆
                inputs1[1] = lstm1State1;      // LSTM鐘舵€?
                inputs1[2] = lstm1State2;      // LSTM鐘舵€?
                
                Map<Integer, Object> outputs1 = new HashMap<>();
                outputs1.put(0, outputArray1);   // 闊抽杈撳嚭
                outputs1.put(1, lstm1State1);     // 鏇存柊鐨凩STM鐘舵€?
                outputs1.put(2, lstm1State2);     // 鏇存柊鐨凩STM鐘舵€?
                
                // 杩愯绗竴闃舵妯″瀷鎺ㄧ悊锛屼娇鐢ㄤ紭鍖栭€夐」
                tfLiteInterpreter1.runForMultipleInputsOutputs(inputs1, outputs1);
                
                // 鍑嗗绗簩闃舵妯″瀷鐨勮緭鍏ュ拰杈撳嚭
                float[][] outputArray2 = new float[1][output.length];
                
                // 鍑嗗绗簩闃舵鐨勮緭鍏ュ拰杈撳嚭
                Object[] inputs2 = new Object[3];
                inputs2[0] = outputArray1;   // 绗竴闃舵鐨勮緭鍑轰綔涓虹浜岄樁娈电殑杈撳叆
                inputs2[1] = lstm2State1;      // LSTM鐘舵€?
                inputs2[2] = lstm2State2;      // LSTM鐘舵€?
                
                Map<Integer, Object> outputs2 = new HashMap<>();
                outputs2.put(0, outputArray2);   // 鏈€缁堥煶棰戣緭鍑?                outputs2.put(1, lstm2State1);     // 鏇存柊鐨凩STM鐘舵€?
                outputs2.put(2, lstm2State2);     // 鏇存柊鐨凩STM鐘舵€?
                
                // 杩愯绗簩闃舵妯″瀷鎺ㄧ悊
                tfLiteInterpreter2.runForMultipleInputsOutputs(inputs2, outputs2);
                
                // 澶嶅埗缁撴灉鍒拌緭鍑哄抚
                System.arraycopy(outputArray2[0], 0, output, 0, output.length);
                
                // 搴旂敤鍚庡鐞嗗寮?                postProcessWithClarity(output);
                
                // 鍔ㄦ€佽皟鏁村帇缂╁弬鏁板熀浜庢竻鏅板害璁剧疆
                float compressionThreshold = 0.2f - (clarityLevel * 0.05f); // 娓呮櫚搴﹂珮鏃堕檷浣庨槇鍊硷紝澶勭悊鏇村淇″彿
                float compressionRatio = 0.7f - (clarityLevel * 0.2f); // 娓呮櫚搴﹂珮鏃堕檷浣庡帇缂╂瘮锛屼繚鐣欐洿澶氬姩鎬佽寖鍥?                
                // 搴旂敤鍩轰簬娓呮櫚搴︾殑澧炲己
                if (voiceEnhancementLevel > 0.3f) {
                    for (int i = 0; i < output.length; i++) {
                        float absValue = Math.abs(output[i]);
                        if (absValue > compressionThreshold) {
                            // 鍘嬬缉楂樹簬闃堝€肩殑淇″彿
                            float gain = compressionThreshold + (absValue - compressionThreshold) * compressionRatio;
                            gain /= absValue;
                            output[i] *= gain;
                        } else if (absValue > 0.01f) {
                            // 鏍规嵁娓呮櫚搴﹀弬鏁拌皟鏁翠綆淇″彿澧炲己
                            float enhanceFactor = 0.3f + (clarityLevel * 0.2f); // 娓呮櫚搴﹂珮鏃跺鍔犲寮哄洜瀛?                            output[i] *= 1.0f + (compressionThreshold - absValue) * enhanceFactor;
                        }
                    }
                }
                
                // 妯″瀷澶勭悊鎴愬姛锛岀洿鎺ヨ繑鍥?                return;
            } catch (Exception e) {
                Log.e(TAG, "DTLN TensorFlow Lite鎺ㄧ悊澶辫触: " + e.getMessage());
                // 鍙戠敓閿欒鏃剁户缁娇鐢ㄤ紶缁熷鐞嗘柟娉?            }
        }
        
        // 楂樼骇淇″彿澶勭悊鏂规硶锛堝綋TensorFlow妯″瀷涓嶅彲鐢ㄦ垨澶勭悊澶辫触鏃朵娇鐢級
        
        // 搴旂敤澶氶樁娈甸檷鍣紙濡傛灉鍚敤锛?        if (isNoiseReductionEnabled) {
            // 1. 璁＄畻淇″彿鑳介噺鍜屽櫔澹颁及璁?            float signalEnergy = 0;
            float[] shortTermEnergy = new float[16]; // 鐭椂鑳介噺鍒嗘瀽绐楀彛
            int windowSize = output.length / shortTermEnergy.length;
            
            // 璁＄畻鐭椂鑳介噺
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
            
            // 2. 鑷€傚簲鍣０浼拌 - 浣跨敤鏈€灏忚兘閲忕獥鍙ｄ綔涓哄櫔澹颁及璁?            float noiseEstimate = Float.MAX_VALUE;
            for (float energy : shortTermEnergy) {
                if (energy < noiseEstimate && energy > 0) {
                    noiseEstimate = energy;
                }
            }
            noiseEstimate = Math.max(noiseEstimate, 0.0001f); // 闃叉鍣０浼拌涓洪浂
            
            // 3. 璁＄畻淇″櫔姣?            float snr = signalEnergy / noiseEstimate;
            
            // 4. 鑷€傚簲闃堝€艰绠?- 鍩轰簬淇″櫔姣斿姩鎬佽皟鏁?            float dynamicThreshold;
            if (snr > 10) { // 楂樹俊鍣瘮锛屼俊鍙锋竻鏅?                dynamicThreshold = 0.01f * (float)Math.sqrt(noiseEstimate);
            } else if (snr > 5) { // 涓瓑淇″櫔姣?                dynamicThreshold = 0.015f * (float)Math.sqrt(noiseEstimate);
            } else { // 浣庝俊鍣瘮锛屽櫔澹拌緝澶?                dynamicThreshold = 0.02f * (float)Math.sqrt(noiseEstimate);
            }
            
            // 5. 搴旂敤骞虫粦鐨勮氨鍑忔硶闄嶅櫔
            for (int i = 0; i < output.length; i++) {
                float absValue = Math.abs(output[i]);
                if (absValue < dynamicThreshold * 3) { // 鎵╁ぇ澶勭悊鑼冨洿
                    // 骞虫粦鐨勮氨鍑忔硶锛屼繚鐣欐洿澶氱殑淇″彿缁嗚妭
                    float attenuationFactor;
                    if (absValue < dynamicThreshold) {
                        // 鍣０鍖哄煙搴旂敤鏇村己鐨勮“鍑?                        attenuationFactor = (absValue / dynamicThreshold) * (absValue / dynamicThreshold);
                    } else {
                        // 杩囨浮鍖哄煙搴旂敤骞虫粦琛板噺
                        float t = (absValue - dynamicThreshold) / (dynamicThreshold * 2);
                        attenuationFactor = 0.25f + 0.75f * t;
                    }
                    output[i] *= attenuationFactor;
                }
            }
        }
        
        // 搴旂敤楂樼骇浜哄０澧炲己
        if (voiceEnhancementLevel > 0) {
            // 1. 澶氬甫棰戠巼澧炲己 - 閽堝浜哄０棰戠巼鑼冨洿鐨勫涓瓙甯﹁繘琛屽寮?            float[][] bandOutputs = new float[3][output.length]; // 浣庛€佷腑銆侀珮涓変釜棰戝甫
            
            // 2. 浣庨甯?(100-700Hz) - 鎻愪緵璇煶鍩洪鍜屼綆棰戝叡鎸?            float lowAlpha = 0.15f;
            float lowBeta = 0.85f;
            bandOutputs[0][0] = output[0] * lowAlpha;
            for (int i = 1; i < output.length; i++) {
                bandOutputs[0][i] = lowAlpha * output[i] + lowBeta * bandOutputs[0][i-1];
            }
            
            // 3. 涓甯?(700-2500Hz) - 鎻愪緵涓昏鍏冮煶淇℃伅
            float midAlpha = 0.4f;
            bandOutputs[1][0] = 0;
            bandOutputs[1][1] = 0;
            for (int i = 2; i < output.length; i++) {
                // 甯﹂€氭护娉㈠櫒瀹炵幇
                bandOutputs[1][i] = midAlpha * (output[i] - output[i-2]);
            }
            
            // 4. 楂橀甯?(2500-5000Hz) - 鎻愪緵杈呴煶鍜屾竻鏅板害
             // 鏍规嵁娓呮櫚搴﹀弬鏁板姩鎬佽皟鏁撮珮棰戝寮?             float highAlpha = 0.25f + (clarityLevel * 0.15f); // 娓呮櫚搴﹀弬鏁板奖鍝嶉珮棰戝寮虹郴鏁?             bandOutputs[2][0] = 0;
             bandOutputs[2][1] = 0;
             bandOutputs[2][2] = 0;
             for (int i = 3; i < output.length; i++) {
                 // 楂橀€氭护娉㈠櫒瀹炵幇 - 澧炲己楂橀缁嗚妭
                 bandOutputs[2][i] = highAlpha * (output[i] - 3*output[i-1] + 3*output[i-2] - output[i-3]);
             }
             
             // 棰濆鐨勮秴楂橀澧炲己 (5000Hz+) - 浠呭湪娓呮櫚搴﹁緝楂樻椂搴旂敤
             if (clarityLevel > 0.6f) {
                 float ultraHighAlpha = 0.15f * ((clarityLevel - 0.6f) / 0.4f); // 鏍规嵁娓呮櫚搴﹀弬鏁拌皟鏁?                 for (int i = 4; i < output.length; i++) {
                     // 瓒呴珮棰戝寮?- 鍥涢樁楂橀€氭护娉㈠櫒
                     float ultraHighComponent = ultraHighAlpha * (output[i] - 4*output[i-1] + 6*output[i-2] - 4*output[i-3] + output[i-4]);
                     bandOutputs[2][i] += ultraHighComponent; // 娣诲姞鍒伴珮棰戝甫
                 }
             }
            
            // 5. 鍔ㄦ€佸帇缂?- 澧炲己寮变俊鍙凤紝鍘嬬缉寮轰俊鍙?            float compressionThreshold = 0.3f;
            float compressionRatio = 0.6f;
            
            for (int band = 0; band < bandOutputs.length; band++) {
                for (int i = 0; i < output.length; i++) {
                    float absValue = Math.abs(bandOutputs[band][i]);
                    if (absValue > compressionThreshold) {
                        // 鍘嬬缉楂樹簬闃堝€肩殑淇″彿
                        float gain = compressionThreshold + (absValue - compressionThreshold) * compressionRatio;
                        gain /= absValue;
                        bandOutputs[band][i] *= gain;
                    } else if (absValue > 0.01f) {
                        // 澧炲己浣庝簬闃堝€肩殑淇″彿
                        bandOutputs[band][i] *= 1.0f + (compressionThreshold - absValue) * 0.5f;
                    }
                }
            }
            
            // 6. 棰戝甫娣峰悎 - 鏍规嵁浜哄０鐗规€у拰娓呮櫚搴﹀弬鏁板姩鎬佸姞鏉冩贩鍚?            // 鍔ㄦ€佽皟鏁撮甯︽潈閲?- 鍩轰簬娓呮櫚搴﹀弬鏁?            float lowWeight = 0.25f - (clarityLevel * 0.1f); // 娓呮櫚搴﹂珮鏃堕檷浣庝綆棰戞潈閲?            float midWeight = 0.5f + (clarityLevel * 0.05f); // 娓呮櫚搴﹂珮鏃剁暐寰鍔犱腑棰戞潈閲?            float highWeight = 0.25f + (clarityLevel * 0.15f); // 娓呮櫚搴﹂珮鏃舵樉钁楀鍔犻珮棰戞潈閲?            
            // 纭繚鏉冮噸鎬诲拰涓?.0
            float totalWeight = lowWeight + midWeight + highWeight;
            lowWeight /= totalWeight;
            midWeight /= totalWeight;
            highWeight /= totalWeight;
            
            float[] bandWeights = {lowWeight, midWeight, highWeight};
            
            // 娣峰悎澧炲己鍚庣殑棰戝甫鍜屽師濮嬩俊鍙?            for (int i = 0; i < output.length; i++) {
                float enhancedSample = 0;
                for (int band = 0; band < bandOutputs.length; band++) {
                    enhancedSample += bandOutputs[band][i] * bandWeights[band];
                }
                
                // 鏍规嵁鐢ㄦ埛璁剧疆鐨勫寮虹骇鍒拰娓呮櫚搴﹀弬鏁版贩鍚堝師濮嬩俊鍙峰拰澧炲己淇″彿
                // 娓呮櫚搴﹂珮鏃跺鍔犲寮轰俊鍙风殑姣斾緥
                float mixRatio = voiceEnhancementLevel + (clarityLevel * 0.2f * voiceEnhancementLevel);
                mixRatio = Math.min(mixRatio, 1.0f); // 纭繚涓嶈秴杩?.0
                
                output[i] = output[i] * (1.0f - mixRatio) + 
                          enhancedSample * mixRatio;
            }
        }
    }
    
    // 鏍规嵁娓呮櫚搴﹀弬鏁板TensorFlow Lite妯″瀷杈撳嚭杩涜鍚庡鐞?    private void postProcessWithClarity(float[] audio) {
        if (clarityLevel <= 0.1f) return; // 娓呮櫚搴﹀緢浣庢椂涓嶅鐞?        
        // 鍒涘缓棰戠巼鍒嗘瀽鐢ㄧ殑涓存椂鏁扮粍
        float[] highFreq = new float[audio.length];
        float[] midFreq = new float[audio.length]; // 娣诲姞涓鍒嗘瀽鏁扮粍
        
        // 楂橀€氭护娉㈠櫒绯绘暟 - 鏍规嵁娓呮櫚搴﹀姩鎬佽皟鏁?        float highPassAlpha = 0.7f + (clarityLevel * 0.2f); // 娓呮櫚搴﹂珮鏃舵彁楂樻埅姝㈤鐜?        float midPassAlpha = 0.4f + (clarityLevel * 0.1f); // 涓婊ゆ尝鍣ㄧ郴鏁?        
        // 鎻愬彇涓鎴愬垎 (1000-3000Hz)
        midFreq[0] = 0;
        midFreq[1] = 0;
        for (int i = 2; i < audio.length; i++) {
            // 浜岄樁甯﹂€氭护娉㈠櫒绠€鍖栧疄鐜?            midFreq[i] = midPassAlpha * (audio[i] - audio[i-2]);
        }
        
        // 鎻愬彇楂橀鎴愬垎
        highFreq[0] = 0;
        highFreq[1] = 0;
        highFreq[2] = 0;
        for (int i = 3; i < audio.length; i++) {
            // 涓夐樁楂橀€氭护娉㈠櫒
            highFreq[i] = highPassAlpha * (audio[i] - 3*audio[i-1] + 3*audio[i-2] - audio[i-3]);
        }
        
        // 鏍规嵁娓呮櫚搴﹀弬鏁板寮洪珮棰戝拰涓鎴愬垎
        float highBoost = clarityLevel * 0.5f;
        float midBoost = clarityLevel * 0.3f;
        
        // 娣峰悎鍘熷淇″彿鍜屽寮虹殑棰戠巼鎴愬垎
        for (int i = 0; i < audio.length; i++) {
            audio[i] = audio[i] + (highFreq[i] * highBoost) + (midFreq[i] * midBoost);
        }
        
        // 濡傛灉娓呮櫚搴﹀緢楂橈紝搴旂敤杞诲井鐨勮皭娉㈢敓鎴愪互澧炲己楂橀缁嗚妭
        if (clarityLevel > 0.7f) {
            for (int i = 0; i < audio.length - 1; i++) {
                // 璁＄畻鐩搁偦鏍锋湰鐨勫樊鍊硷紝浣滀负楂橀缁嗚妭鐨勪及璁?                float detail = (audio[i+1] - audio[i]) * 0.5f;
                // 娣诲姞鍒板師濮嬩俊鍙?                audio[i] += detail * (clarityLevel - 0.7f) * 0.5f;
            }
        }
    }
    
    /**
     * 鍔犺浇TensorFlow Lite妯″瀷鏂囦欢
     * 浠巃ssets鐩綍鍔犺浇VoiceFilter-Lite妯″瀷
     */
    private MappedByteBuffer loadModelFile(String modelName) throws IOException {
        // 浠巃ssets鐩綍鍔犺浇妯″瀷鏂囦欢
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
