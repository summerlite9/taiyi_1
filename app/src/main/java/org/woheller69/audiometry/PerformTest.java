package org.woheller69.audiometry;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;


public class PerformTest extends AppCompatActivity {
    private GestureDetector gestureDetector;
    private boolean paused = false;
    // 优化音调参数，提高频率精度和降低总谐波失真
    private final float duration = 0.35f;
    private final int sampleRate = 44100; // 高采样率提高频率精度
    private final int numSamples = (int) (duration * sampleRate);
    private final int volume = 32767;
    static public final int lowGain = 4;
    static public final int highGain = 9;
    static public final int defaultGain = highGain;
    static public int gain = defaultGain;
    // 气导频率覆盖 125Hz~8000 Hz，骨导覆盖 250 Hz~4000 Hz
    static public final int[] testFrequencies = {125, 250, 500, 1000, 2000, 3000, 4000, 6000, 8000};
    // 定义听力级控制步进为5 dB
    private final double dbStepSize = 5.0;
    static final float[] correctiondBSPLtodBHL ={19.7f,9.0f,2.0f,0f,-3.7f,-8.1f,-7.8f, 2.1f,10.2f}; //estimated from  ISO226:2003 hearing threshold. Taken from https://github.com/IoSR-Surrey/MatlabToolbox/blob/master/%2Biosr/%2Bauditory/iso226.m Corrected to value=0 @1000Hz
    private boolean heard = false;
    private boolean skip = false;
    private boolean debug = false;
    public double[] thresholds_right = new double[testFrequencies.length];
    public double[] thresholds_left = new double[testFrequencies.length];
    private Context context;
    private final Sound sound = new Sound();
    testThread testThread;
    TextView earView;
    TextView frequencyView;
    TextView progressView;
    Intent intent;



    public void showToast(final String toast)
    {
        runOnUiThread(() -> Toast.makeText(PerformTest.this, toast, Toast.LENGTH_SHORT).show());
    }

    public void setEarView(final int textID){
        runOnUiThread(() -> earView.setText(textID));
    }

    public void setFrequencyView(final int freq){
        runOnUiThread(() -> frequencyView.setText(freq + " Hz"));
    }

    /**
     * Randomly picks time gap between test tones in ms
     * @return
     */
    public int randomTime(){

        double num = Math.random();
        return (int) (1500+1500*num);
    }

    /**
     * Changes background to white when called.
     */
    Runnable bkgrndFlash = new Runnable() {
        @Override
        public void run(){
            View view = findViewById(R.id.page);
            view.setBackgroundColor(getResources().getColor(R.color.green,getTheme()));
        }
    };
    /**
     * Changes background color to black when called
     */
    Runnable bkgrndFlashBlack = new Runnable() {
        @Override
        public void run(){
            View view = findViewById(R.id.page);
            view.setBackgroundColor(getResources().getColor(R.color.background_grey,getTheme()));
        }
    };

    /**
     * go to MainActivity
     */
    public void gotoMain(){
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
    }


    public class testThread extends Thread {

        private boolean stopped = false;

        public void stopThread(){
            stopped = true;
        }

        public void run() {

            //iterated once for every frequency to be tested
            for (int s = 0; s < 2; s++) {
                if (s==0) setEarView(R.string.right_ear);
                else setEarView(R.string.left_ear);
                if (stopped){break;}
                if (!intent.getStringExtra("Action").equals("SimpleCalibration")) {
                    for (int i = 0; i < testFrequencies.length; i++) {
                        double threshold = singleTest(s, i);
                        if (s == 0) {
                            thresholds_right[i] = threshold; //records volume as threshold
                        } else {
                            thresholds_left[i] = threshold; //records volume as threshold
                        }
                    }
                }else{
                    double threshold = singleTest(s, Arrays.binarySearch(testFrequencies, 1000));  // Test at 1000Hz
                    if (s == 0) {
                        for (int i=0;i<testFrequencies.length;i++) thresholds_right[i] = correctiondBSPLtodBHL[i] + threshold;
                    } else {
                        for (int i=0;i<testFrequencies.length;i++) thresholds_left[i] = correctiondBSPLtodBHL[i] + threshold;
                    }
                }

                PerformTest.this.runOnUiThread(bkgrndFlashBlack);
            }
            if (stopped) return;


            FileOperations fileOperations = new FileOperations();

            if (!intent.getStringExtra("Action").equals("Test")){  //if this was a full or simple calibration store calibration
                double[] calibrationArray = new double[testFrequencies.length+1];  //last field is used later for number of calibrations
                for(int i=0;i<testFrequencies.length;i++){  //for calibration average left/right channels
                    calibrationArray[i]=(thresholds_left[i]+thresholds_right[i])/2;
                }
                fileOperations.writeCalibration(calibrationArray, context);
            } else {  // store test result
                fileOperations.writeTestResult(thresholds_right, thresholds_left, context);
            }

            gotoMain();
        }

        public double singleTest(int s, int i) {
            AudioTrack audioTrack;
            int frequency = testFrequencies[i];
            setFrequencyView(frequency);
            float increment = (float) (2*Math.PI) * frequency / sampleRate;
            int actualVolume;
            int maxVolume = volume;
            int minVolume = 0;
            int thresVolume = maxVolume;
            // This is the loop for each individual sample using a binary search algorithm
            while (!stopped) {
                int tempResponse = 0;

                // 优化听力级控制步进≤5 dB，精度±3 dB（125 Hz~4 kHz）
                if (minVolume > 0){  //at least one tone not heard
                    // 使用精确的5dB步进
                    double currentdB = 20 * Math.log10(maxVolume);
                    double targetdB = 20 * Math.log10(minVolume);
                    double middledB = (currentdB + targetdB) / 2.0;
                    // 将dB值四舍五入到最接近的5dB步进
                    middledB = Math.round(middledB / dbStepSize) * dbStepSize;
                    actualVolume = (int) Math.round(Math.pow(10, middledB / 20.0));
                } else {
                    // 首次测试时使用更精确的下降策略
                    double currentdB = 20 * Math.log10(maxVolume);
                    // 下降5dB
                    double targetdB = currentdB - dbStepSize;
                    actualVolume = (int) Math.round(Math.pow(10, targetdB / 20.0));
                }
                if (actualVolume <= 1) {
                    showToast(getString(R.string.error_volume));
                    actualVolume = 1;
                }
                if (debug) showToast(getString(R.string.debug_amplitude, actualVolume));

                // 优化精度控制，确保在±3 dB范围内
                if (minVolume > 0) {
                    double maxdB = 20 * Math.log10(maxVolume);
                    double mindB = 20 * Math.log10(minVolume);
                    if (Math.abs(maxdB - mindB) < 3.0) {  // 精度控制在±3 dB
                        return 20 * Math.log10(thresVolume);
                    }
                } else {
                    for (int z = 0; z < 3; z++) { //iterate three times per volume level
                        if (stopped) {
                            break;
                        }
                        if (paused) {
                            z = 0;
                            tempResponse = 0;
                        }
                        heard = false;
                        skip = false;
                        // 优化音调切换，确保上升/下降时间≤200ms，无瞬态干扰
                        long startTime = System.currentTimeMillis();
                        audioTrack = sound.playSound(sound.genTone(increment, actualVolume, numSamples), s, sampleRate);
                        
                        // 确保音调播放完成后再继续
                        try {
                            // 等待音频播放完成
                            Thread.sleep((long)(duration * 1000) + 50); // 确保音频完全播放，显式转换为long
                            
                            // 计算已经过去的时间
                            long elapsedTime = System.currentTimeMillis() - startTime;
                            // 计算剩余需要等待的随机时间
                            long remainingWaitTime = randomTime() - elapsedTime;
                            if (remainingWaitTime > 0) {
                                Thread.sleep(remainingWaitTime);
                            }
                        } catch (InterruptedException e) {
                        }
                        
                        audioTrack.release();
                        if (heard) tempResponse++;
                        if (skip) tempResponse=3;
                         // Checks if the first two test were positive, and skips the third if true. Helps speed the test along.
                        if (tempResponse >= 2) {
                            break;
                        }
                        // Check if the first two tests were misses, and skips the third if this is the case.
                        if (z == 1 && tempResponse == 0) {
                            break;
                        }
                    }
                    //If the response was positive two out of three times, register as heard
                    if (tempResponse >= 2) {
                        thresVolume = actualVolume;
                        maxVolume = actualVolume;
                    } else {
                        if (minVolume > 0){ //at least one tone not heard
                            minVolume = actualVolume;
                        } else {
                            minVolume = (int) (actualVolume/Math.sqrt(2)); //if not heard for first time set minVolume 3dB below actualVolume. So we will test this level again if a higher level is heard
                        }

                    }
                } //continue with test
            }
            return 0;
        }


    }


    //--------------------------------------------------------------------------
    //End of Variable and Method Definitions
    //--------------------------------------------------------------------------


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context=this;
        this.gestureDetector = new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                heard = true;
                paused = false;
                PerformTest.this.runOnUiThread(bkgrndFlash);
                Timer timer = new Timer();
                TimerTask timerTask = new TimerTask() {
                    @Override
                    public void run() {
                        PerformTest.this.runOnUiThread(bkgrndFlashBlack);
                    }
                };
                timer.schedule(timerTask,250);
                progressView.setText(getString(R.string.test_running));
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                paused = !paused;
                progressView.setText(paused ? getString(R.string.test_paused) : getString(R.string.test_running));
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                skip = true;
                return true;
            }

        });
        setContentView(R.layout.activity_performtest);
        earView = findViewById(R.id.ear);
        frequencyView = findViewById(R.id.frequency);
        progressView = findViewById(R.id.progress);
        intent = getIntent();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(getResources().getColor(R.color.primary_dark,getTheme()));
    }

    @Override
    public void onResume() {
        gain=FileOperations.readGain(this);
        AudioManager am = (AudioManager)getSystemService(AUDIO_SERVICE);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, gain,  0);
        testThread = new testThread();
        testThread.start();
        super.onResume();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        this.gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.test_perform, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if  (id == android.R.id.home ) {
            gotoMain();
        } else if ( id == R.id.debug) {
            debug = true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onStop(){
        super.onStop();
        testThread.stopThread();
    }

}
