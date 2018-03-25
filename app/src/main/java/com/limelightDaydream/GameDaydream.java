package com.limelightDaydream;


import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.View.OnGenericMotionListener;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import com.google.vr.ndk.base.AndroidCompat;
import com.google.vr.ndk.base.DaydreamApi;
import com.google.vr.ndk.base.GvrLayout;
import com.google.vr.sdk.controller.Controller;
import com.google.vr.sdk.controller.Controller.ConnectionStates;
import com.google.vr.sdk.controller.ControllerManager;
import com.google.vr.sdk.controller.ControllerManager.ApiStatus;
import com.limelight.LimeLog;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelightDaydream.binding.PlatformBinding;
import com.limelightDaydream.binding.input.ControllerHandler;
import com.limelightDaydream.binding.input.KeyboardTranslator;
import com.limelightDaydream.binding.input.TouchContext;
import com.limelightDaydream.binding.input.capture.InputCaptureManager;
import com.limelightDaydream.binding.input.capture.InputCaptureProvider;
import com.limelightDaydream.binding.input.driver.UsbDriverService;
import com.limelightDaydream.binding.input.evdev.EvdevListener;
import com.limelightDaydream.binding.input.virtual_controller.VirtualController;
import com.limelightDaydream.binding.video.CrashListener;
import com.limelightDaydream.binding.video.MediaCodecDecoderRenderer;
import com.limelightDaydream.binding.video.MediaCodecHelper;
import com.limelightDaydream.preferences.GlPreferences;
import com.limelightDaydream.preferences.PreferenceConfiguration;
import com.limelightDaydream.ui.GameGestures;
import com.limelightDaydream.utils.Dialog;
import com.limelightDaydream.utils.SpinnerDialog;
import com.limelightDaydream.utils.UiHelper;
import com.limelightDaydream.vr.VideoSceneRenderer;


public class GameDaydream extends Activity implements
    OnGenericMotionListener, NvConnectionListener, EvdevListener, GameGestures
{
    private com.google.vr.sdk.controller.ControllerManager controllerManager;
    private Controller controller;

    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;
    private int lastButtonState = 0;

    // Only 2 touches are supported
    private final TouchContext[] touchContextMap = new TouchContext[2];
    private long threeFingerDownTime = 0;

    private static final int REFERENCE_HORIZ_RES = 1280;
    private static final int REFERENCE_VERT_RES = 720;

    private static final int THREE_FINGER_TAP_THRESHOLD = 300;

    private ControllerHandler controllerHandler;
    private VirtualController virtualController;

    private PreferenceConfiguration prefConfig;
    private SharedPreferences tombstonePrefs;

    private NvConnection conn;
    private SpinnerDialog spinner;
    private boolean displayedFailureDialog = false;
    private boolean connecting = false;
    private boolean connected = false;

    private InputCaptureProvider inputCaptureProvider;
    private int modifierFlags = 0;
    private boolean grabbedInput = true;
    private boolean grabComboDown = false;


    private MediaCodecDecoderRenderer decoderRenderer;
    private boolean reportedCrash;

    private WifiManager.WifiLock wifiLock;

    private GvrLayout gvrLayout = null;
    private GLSurfaceView surfaceView = null;
    private VideoSceneRenderer renderer = null;
    private Boolean hasFirstFrame = false;

    public static final String EXTRA_HOST = "Host";
    public static final String EXTRA_APP_NAME = "AppName";
    public static final String EXTRA_APP_ID = "AppId";
    public static final String EXTRA_UNIQUEID = "UniqueId";
    public static final String EXTRA_STREAMING_REMOTE = "Remote";
    public static final String EXTRA_APP_HDR = "HDR";

    // Transform a quad that fills the clip box at Z=0 to a 16:9 screen at Z=-98. Note that the matrix
    // is column-major, so the translation is on the last line in this representation.

    private final float[] videoTransform = {
            1.6f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.9f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, -1.8f, 1.0f
    };


    // Runnable to refresh the viewer profile when gvrLayout is resumed.
    // This is done on the GL thread because refreshViewerProfile isn't thread-safe.
    private final Runnable refreshViewerProfileRunnable =
            new Runnable() {
                @Override
                public void run() {
                    gvrLayout.getGvrApi().refreshViewerProfile();
                }
            };

    private boolean connectedToUsbDriverService = false;



    private DaydreamApi api;
    @Override
    protected void onCreate(Bundle savedInstanceState) {



        super.onCreate(savedInstanceState);

        UiHelper.setLocale(this);




        // We don't want a title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Full-screen and don't let the display go off
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // If we're going to use immersive mode, we want to have
        // the entire screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        }

        // We specified userLandscape in the manifest which isn't supported until 4.3,
        // so we must fall back at runtime to sensorLandscape.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        // Listen for UI visibility events
        //getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(this);

        // Change volume button behavior
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Inflate the content
        //setContentView(R.layout.activity_game);

        // Start the spinner
        //spinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.conn_establishing_title),
        //        getResources().getString(R.string.conn_establishing_msg), true);

        // Read the stream preferences
        prefConfig = PreferenceConfiguration.readPreferences(this);
        tombstonePrefs = GameDaydream.this.getSharedPreferences("DecoderTombstone", 0);


        // Listen for events on the game surface
        //streamView = findViewById(R.id.surfaceView);
        //streamView.setOnGenericMotionListener(this);
        //streamView.setOnTouchListener(this);

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this);

        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The view must be focusable for pointer capture to work.
            streamView.setFocusable(true);
            streamView.setDefaultFocusHighlightEnabled(false);
            streamView.setOnCapturedPointerListener(new View.OnCapturedPointerListener() {
                @Override
                public boolean onCapturedPointer(View view, MotionEvent motionEvent) {
                    return handleMotionEvent(motionEvent);
                }
            });
        }*/

        // Warn the user if they're on a metered connection
        ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr.isActiveNetworkMetered()) {
            displayTransientMessage(getResources().getString(R.string.conn_metered));
        }

        // Make sure Wi-Fi is fully powered up
        WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Limelight");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();

        String host = getIntent().getStringExtra(EXTRA_HOST);
        String appName = GameDaydream.this.getIntent().getStringExtra(EXTRA_APP_NAME);
        int appId = GameDaydream.this.getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        String uniqueId = GameDaydream.this.getIntent().getStringExtra(EXTRA_UNIQUEID);
        boolean remote = GameDaydream.this.getIntent().getBooleanExtra(EXTRA_STREAMING_REMOTE, false);
        boolean willStreamHdr = GameDaydream.this.getIntent().getBooleanExtra(EXTRA_APP_HDR, false);

        /*if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish();
            return;
        }*/

        // Initialize the MediaCodec helper before creating the decoder
        GlPreferences glPrefs = GlPreferences.readPreferences(this);

        MediaCodecHelper.initialize(this, glPrefs.glRenderer);

        prefConfig = PreferenceConfiguration.readPreferences(this);

        // Check if the user has enabled HDR
        if (prefConfig.enableHdr) {
            // Check if the app supports it
            if (!willStreamHdr) {
                //Toast.makeText(this, "This game does not support HDR10", Toast.LENGTH_SHORT).show();
            }
            // It does, so start our HDR checklist
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // We already know the app supports HDR if willStreamHdr is set.
                Display display = getWindowManager().getDefaultDisplay();
                Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();

                // We must now ensure our display is compatible with HDR10
                boolean foundHdr10 = false;
                for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                    if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {

                        foundHdr10 = true;
                    }
                }

                if (!foundHdr10) {
                    // Nope, no HDR for us :(
                    willStreamHdr = false;
                    //Toast.makeText(this, "Display does not support HDR10", Toast.LENGTH_LONG).show();
                }
            }
            else {
                //Toast.makeText(this, "HDR requires Android 7.0 or later", Toast.LENGTH_LONG).show();
                willStreamHdr = false;
            }
        }
        else {
            willStreamHdr = false;
        }

        decoderRenderer = new MediaCodecDecoderRenderer(prefConfig,
                new CrashListener() {
                    @Override
                    public void notifyCrash(Exception e) {
                        // The MediaCodec instance is going down due to a crash
                        // let's tell the user something when they open the app again

                        // We must use commit because the app will crash when we return from this function
                        tombstonePrefs.edit().putInt("CrashCount", tombstonePrefs.getInt("CrashCount", 0) + 1).commit();
                        reportedCrash = true;
                    }
                },
                tombstonePrefs.getInt("CrashCount", 0),
                connMgr.isActiveNetworkMetered(),
                willStreamHdr,
                glPrefs.glRenderer
                );

        // Don't stream HDR if the decoder can't support it
        if (willStreamHdr && !decoderRenderer.isHevcMain10Hdr10Supported()) {
            willStreamHdr = false;
            //Toast.makeText(this, "Decoder does not support HEVC Main10HDR10", Toast.LENGTH_LONG).show();
        }

        // Display a message to the user if H.265 was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FORCE_H265_ON && !decoderRenderer.isHevcSupported()) {
            //Toast.makeText(this, "No H.265 decoder found.\nFalling back to H.264.", Toast.LENGTH_LONG).show();
        }

        int gamepadMask = ControllerHandler.getAttachedControllerMask(this);
        if (!prefConfig.multiController && gamepadMask != 0) {
            // If any gamepads are present in non-MC mode, set only gamepad 1.
            gamepadMask = 1;
        }
        if (prefConfig.onscreenController) {
            // If we're using OSC, always set at least gamepad 1.
            gamepadMask |= 1;
        }

        // Set to the optimal mode for streaming
        //float displayRefreshRate = prepareDisplayForRendering();
        //LimeLog.info("Display refresh rate: "+displayRefreshRate);

        // HACK: Despite many efforts to ensure low latency consistent frame
        // delivery, the best non-lossy mechanism is to buffer 1 extra frame
        // in the output pipeline. Android does some buffering on its end
        // in SurfaceFlinger and it's difficult (impossible?) to inspect
        // the precise state of the buffer queue to the screen after we
        // release a frame for rendering.
        //
        // Since buffering a frame adds latency and we are primarily a
        // latency-optimized client, rather than one designed for picture-perfect
        // accuracy, we will synthetically induce a negative pressure on the display
        // output pipeline by driving the decoder input pipeline under the speed
        // that the display can refresh. This ensures a constant negative pressure
        // to keep latency down but does induce a periodic frame loss. However, this
        // periodic frame loss is *way* less than what we'd already get in Marshmallow's
        // display pipeline where frames are dropped outside of our control if they land
        // on the same V-sync.
        //
        // Hopefully, we can get rid of this once someone comes up with a better way
        // to track the state of the pipeline and time frames.
        /*int roundedRefreshRate = Math.round(displayRefreshRate);
        if (!prefConfig.disableFrameDrop && prefConfig.fps >= roundedRefreshRate) {
            if (roundedRefreshRate <= 49) {
                // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
                decoderRenderer.enableLegacyFrameDropRendering();
                LimeLog.info("Bogus refresh rate: "+roundedRefreshRate);
            }
            // HACK: Avoid crashing on some MTK devices
            else if (roundedRefreshRate == 50 && decoderRenderer.is49FpsBlacklisted()) {
                // Use the old rendering strategy on these broken devices
                decoderRenderer.enableLegacyFrameDropRendering();
            }
            else {
                prefConfig.fps = roundedRefreshRate - 1;
                LimeLog.info("Adjusting FPS target for screen to "+prefConfig.fps);
            }
        }*/

        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(prefConfig.width, prefConfig.height)
                .setRefreshRate(prefConfig.fps)
                .setApp(new NvApp(appName, appId, willStreamHdr))
                .setBitrate(prefConfig.bitrate)
                .setEnableSops(prefConfig.enableSops)
                .enableLocalAudioPlayback(prefConfig.playHostAudio)
                .setMaxPacketSize((remote || prefConfig.width <= 1920) ? 1024 : 1292)
                .setRemote(remote)
                .setHevcBitratePercentageMultiplier(75)
                .setHevcSupported(decoderRenderer.isHevcSupported())
                .setEnableHdr(willStreamHdr)
                .setAttachedGamepadMask(gamepadMask)
                .setClientRefreshRateX100((int)(60.000004 * 100))
                .setAudioConfiguration(prefConfig.enable51Surround ?
                        MoonBridge.AUDIO_CONFIGURATION_51_SURROUND :
                        MoonBridge.AUDIO_CONFIGURATION_STEREO)
                .build();

        setImmersiveSticky();
        getWindow()
                .getDecorView()
                .setOnSystemUiVisibilityChangeListener(
                        new View.OnSystemUiVisibilityChangeListener() {
                            @Override
                            public void onSystemUiVisibilityChange(int visibility) {
                                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                                    setImmersiveSticky();
                                }
                            }
                        });

        // Start the ControllerManager and acquire a Controller object which represents a single
        // physical controller. Bind our listener to the ControllerManager and Controller.
        EventListener listener = new EventListener();
        controllerManager = new ControllerManager(this, listener);
        controller = controllerManager.getController();
        controller.setEventListener(listener);

        AndroidCompat.setSustainedPerformanceMode(this, true);
        AndroidCompat.setVrModeEnabled(this, true);

        gvrLayout = new GvrLayout(this);
        surfaceView = new GLSurfaceView(this);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(5, 6, 5, 0, 0, 0);



        //for controller input
        surfaceView.setOnGenericMotionListener(this);

        gvrLayout.setPresentationView(surfaceView);
        gvrLayout.setKeepScreenOn(true);

        renderer =  new VideoSceneRenderer(this, gvrLayout.getGvrApi(),null);


        //Initialize the ExternalSurfaceListener to receive video Surface callbacks.
        hasFirstFrame = false;

        GvrLayout.ExternalSurfaceListener videoSurfaceListener =
                new GvrLayout.ExternalSurfaceListener(){
                @Override
                public void onSurfaceAvailable( Surface surface) {
                //Set the surface for Moonlight's stream to output video frames to. Video
                //playback is started when the Surface is set.
                if (!connected && !connecting) {
                    connecting = true;

                    decoderRenderer.setRenderTarget(surface);
                    conn.start( PlatformBinding.getAudioRenderer(),decoderRenderer,GameDaydream.this);
                }
            }
            @Override
            public void onFrameAvailable() {
                //If this is the first frame, signal to remove the loading splash screen,
                //and draw alpha 0 in the color buffer where the video will be drawn by the
                //GvrApi.
                if (!hasFirstFrame) {
                    final Runnable hideSystemUi = new Runnable() {
                        @Override
                        public void run() {
                            renderer.setHasVideoPlaybackStarted(true);
                        }
                    };
                    surfaceView.queueEvent(hideSystemUi);
                    hasFirstFrame = true;
                }
            }
        };

        // Initialize the connection
        conn = new NvConnection(host, uniqueId, config, PlatformBinding.getCryptoProvider(this));
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);

        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(controllerHandler, null);




        // Use sustained performance mode on N+ to ensure consistent
        // CPU availability
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            getWindow().setSustainedPerformanceMode(true);
        }

        ServiceConnection usbDriverServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                UsbDriverService.UsbDriverBinder binder = (UsbDriverService.UsbDriverBinder) iBinder;
                binder.setListener(controllerHandler);
                connectedToUsbDriverService = true;
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                connectedToUsbDriverService = false;
            }
        };

        if (prefConfig.usbDriver) {
            // Start the USB driver
            bindService(new Intent(this, UsbDriverService.class),
                    usbDriverServiceConnection, Service.BIND_AUTO_CREATE);
        }

        if (!decoderRenderer.isAvcSupported()) {
            if (spinner != null) {
                spinner.dismiss();
                spinner = null;
            }

            // If we can't find an AVC decoder, we can't proceed
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
                    "This device or ROM doesn't support hardware accelerated H.264 playback.", true);
            return;
        }

        // The connection will be started when the surface gets created
        //streamView.getHolder().addCallback(this);

        Handler handler = new Handler(Looper.getMainLooper());
        /*val isSurfaceEnabled = gvrLayout.enableAsyncReprojectionVideoSurface(videoSurfaceListener,Handler(Looper.getMainLooper()),false)*/
        boolean isSurfaceEnabled = gvrLayout.enableAsyncReprojectionVideoSurface(videoSurfaceListener,handler,false);


        boolean isAsyncReprojectionEnabled = gvrLayout.setAsyncReprojectionEnabled(true);

        if (!isSurfaceEnabled || !isAsyncReprojectionEnabled) {
            //The device doesn't support this API, video won't play.
            Log.e("moonlightVR",
                    "UnsupportedException: Async Reprojection not supported. or Async Reprojection Video Surface not enabled.");
        } else {
            //The default value puts the viewport behind the eye, so it's invisible. Set the transform
            //now to ensure the video is visible when rendering starts.
            renderer.setVideoTransform(videoTransform);


            //The ExternalSurface buffer the GvrApi should reference when drawing the video buffer. This
            //must be called after enabling the Async Reprojection video surface.
            renderer.setVideoSurfaceId(gvrLayout.getAsyncReprojectionVideoSurfaceId());
        }

        //Set the renderer and start the app's GL thread.
        surfaceView.setRenderer(renderer);
        setContentView(gvrLayout);

    }




    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setImmersiveSticky();
        }

    }



    @SuppressLint("InlinedApi")
    private final Runnable hideSystemUi = new Runnable() {
            @Override
            public void run() {
                // In multi-window mode on N+, we need to drop our layout flags or we'll
                // be drawing underneath the system UI.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode()) {
                    GameDaydream.this.getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                }
                // Use immersive mode on 4.4+ or standard low profile on previous builds
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    GameDaydream.this.getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
                else {
                    GameDaydream.this.getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LOW_PROFILE);
                }
            }
    };

    private void hideSystemUi(int delay) {
        Handler h = getWindow().getDecorView().getHandler();
        if (h != null) {
            h.removeCallbacks(hideSystemUi);
            h.postDelayed(hideSystemUi, delay);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.N)
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);

        // In multi-window, we don't want to use the full-screen layout
        // flag. It will cause us to collide with the system UI.
        // This function will also be called for PiP so we can cover
        // that case here too.
        if (isInMultiWindowMode) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // Disable performance optimizations for foreground
            getWindow().setSustainedPerformanceMode(false);
            decoderRenderer.notifyVideoBackground();
        }
        else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // Enable performance optimizations for foreground
            getWindow().setSustainedPerformanceMode(true);
            decoderRenderer.notifyVideoForeground();
        }

        // Correct the system UI visibility flags
        hideSystemUi(50);
    }



    @Override
    protected void onDestroy() {

        gvrLayout.shutdown();
        if (controllerHandler != null) {
            InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
            inputManager.unregisterInputDeviceListener(controllerHandler);
        }

        wifiLock.release();

        if (connectedToUsbDriverService) {
            // Unbind from the discovery service
            //unbindService(usbDriverServiceConnection);
        }

        // Destroy the capture provider
        inputCaptureProvider.destroy();
        super.onDestroy();
    }

    @Override
    protected void onStop() {

        controllerManager.stop();
        if (controllerHandler != null) {

            InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
            inputManager.unregisterInputDeviceListener(controllerHandler);
        }


        if (conn != null) {
            int videoFormat = decoderRenderer.getActiveVideoFormat();

            displayedFailureDialog = true;
            stopConnection();

            int averageEndToEndLat = decoderRenderer.getAverageEndToEndLatency();
            int averageDecoderLat = decoderRenderer.getAverageDecoderLatency();
            String message = null;
            if (averageEndToEndLat > 0) {
                message = getResources().getString(R.string.conn_client_latency)+" "+averageEndToEndLat+" ms";
                if (averageDecoderLat > 0) {
                    message += " ("+getResources().getString(R.string.conn_client_latency_hw)+" "+averageDecoderLat+" ms)";
                }
            }
            else if (averageDecoderLat > 0) {
                message = getResources().getString(R.string.conn_hardware_latency)+" "+averageDecoderLat+" ms";
            }

            // Add the video codec to the post-stream toast
            if (message != null) {
                if (videoFormat == MoonBridge.VIDEO_FORMAT_H265_MAIN10) {
                    message += " [H.265 HDR]";
                }
                else if (videoFormat == MoonBridge.VIDEO_FORMAT_H265) {
                    message += " [H.265]";
                }
                else if (videoFormat == MoonBridge.VIDEO_FORMAT_H264) {
                    message += " [H.264]";
                }
            }

            if (message != null) {
                //Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }

            // Clear the tombstone count if we terminated normally
            if (!reportedCrash && tombstonePrefs.getInt("CrashCount", 0) != 0) {
                tombstonePrefs.edit()
                        .putInt("CrashCount", 0)
                        .putInt("LastNotifiedCrashCount", 0)
                        .apply();
            }
        }

        gvrLayout.onPause();
        super.onStop();
    }

    private final Runnable toggleGrab = new Runnable() {
        @Override
        public void run() {
            if (grabbedInput) {
                inputCaptureProvider.disableCapture();
            }
            else {
                inputCaptureProvider.enableCapture();
            }

            grabbedInput = !grabbedInput;
        }
    };

    // Returns true if the key stroke was consumed
    private boolean handleSpecialKeys(int androidKeyCode, boolean down) {
        int modifierMask = 0;

        if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
            androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_CTRL;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
                 androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_SHIFT;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT ||
                 androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_ALT;
        }

        if (down) {
            this.modifierFlags |= modifierMask;
        }
        else {
            this.modifierFlags &= ~modifierMask;
        }

        // Check if Ctrl+Shift+Z is pressed
        if (androidKeyCode == KeyEvent.KEYCODE_Z &&
            (modifierFlags & (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_SHIFT)) ==
                (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_SHIFT))
        {
            if (down) {
                // Now that we've pressed the magic combo
                // we'll wait for one of the keys to come up
                grabComboDown = true;
            }
            else {
                // Toggle the grab if Z comes up
                Handler h = getWindow().getDecorView().getHandler();
                if (h != null) {
                    h.postDelayed(toggleGrab, 250);
                }

                grabComboDown = false;
            }

            return true;
        }
        // Toggle the grab if control or shift comes up
        else if (grabComboDown) {
            Handler h = getWindow().getDecorView().getHandler();
            if (h != null) {
                h.postDelayed(toggleGrab, 250);
            }

            grabComboDown = false;
            return true;
        }

        // Not a special combo
        return false;
    }

    private static byte getModifierState(KeyEvent event) {
        byte modifier = 0;
        if (event.isShiftPressed()) {
            modifier |= KeyboardPacket.MODIFIER_SHIFT;
        }
        if (event.isCtrlPressed()) {
            modifier |= KeyboardPacket.MODIFIER_CTRL;
        }
        if (event.isAltPressed()) {
            modifier |= KeyboardPacket.MODIFIER_ALT;
        }
        return modifier;
    }

    private byte getModifierState() {
        return (byte) modifierFlags;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return super.onKeyDown(keyCode, event);
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click.
        if (event.getSource() == InputDevice.SOURCE_MOUSE && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            return true;
        }

        boolean handled = false;

        boolean detectedGamepad = event.getDevice() != null && ((event.getDevice().getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (event.getDevice().getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);
        if (detectedGamepad || (event.getDevice() == null ||
                event.getDevice().getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC
        )) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonDown(event);
        }

        if (!handled) {
            // Try the keyboard handler
            short translated = KeyboardTranslator.translate(event.getKeyCode());
            if (translated == 0) {
                return super.onKeyDown(keyCode, event);
            }

            // Let this method take duplicate key down events
            if (handleSpecialKeys(keyCode, true)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return super.onKeyDown(keyCode, event);
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, getModifierState(event));
        }

        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return super.onKeyUp(keyCode, event);
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click.
        if (event.getSource() == InputDevice.SOURCE_MOUSE && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            return true;
        }

        boolean handled = false;
        boolean detectedGamepad = event.getDevice() != null && ((event.getDevice().getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (event.getDevice().getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);
        if (detectedGamepad || (event.getDevice() == null ||
                event.getDevice().getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC
        )) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonUp(event);
        }

        if (!handled) {
            // Try the keyboard handler
            short translated = KeyboardTranslator.translate(event.getKeyCode());
            if (translated == 0) {
                return super.onKeyUp(keyCode, event);
            }

            if (handleSpecialKeys(keyCode, false)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return super.onKeyUp(keyCode, event);
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, getModifierState(event));
        }

        return true;
    }

    private TouchContext getTouchContext(int actionIndex)
    {
        if (actionIndex < touchContextMap.length) {
            return touchContextMap[actionIndex];
        }
        else {
            return null;
        }
    }

    @Override
    public void showKeyboard() {

        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
    }

    // Returns true if the event was consumed
    private boolean handleMotionEvent(MotionEvent event) {
        // Pass through keyboard input if we're not grabbing
        if (!grabbedInput) {
            return false;
        }

        if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            if (controllerHandler.handleMotionEvent(event)) {
                return true;
            }
        }
        else if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                  event.getSource() == InputDevice.SOURCE_MOUSE_RELATIVE)
        {
            // This case is for mice
            if (event.getSource() == InputDevice.SOURCE_MOUSE ||
                    (event.getPointerCount() >= 1 &&
                            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE))
            {
                int changedButtons = event.getButtonState() ^ lastButtonState;

                // Ignore mouse input if we're not capturing from our input source
                if (!inputCaptureProvider.isCapturingActive()) {
                    return false;
                }

                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    // Send the vertical scroll packet
                    byte vScrollClicks = (byte) event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    conn.sendMouseScroll(vScrollClicks);
                }

                if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((event.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                }

                if ((changedButtons & MotionEvent.BUTTON_SECONDARY) != 0) {
                    if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                    }
                }

                if ((changedButtons & MotionEvent.BUTTON_TERTIARY) != 0) {
                    if ((event.getButtonState() & MotionEvent.BUTTON_TERTIARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                }

                // Get relative axis values if we can
                if (inputCaptureProvider.eventHasRelativeMouseAxes(event)) {
                    // Send the deltas straight from the motion event
                    conn.sendMouseMove((short) inputCaptureProvider.getRelativeAxisX(event),
                            (short) inputCaptureProvider.getRelativeAxisY(event));

                    // We have to also update the position Android thinks the cursor is at
                    // in order to avoid jumping when we stop moving or click.
                    lastMouseX = (int)event.getX();
                    lastMouseY = (int)event.getY();
                }
                else {
                    // First process the history
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        updateMousePosition((int)event.getHistoricalX(i), (int)event.getHistoricalY(i));
                    }

                    // Now process the current values
                    updateMousePosition((int)event.getX(), (int)event.getY());
                }

                lastButtonState = event.getButtonState();
            }
            // This case is for touch-based input devices
            else
            {
                if (virtualController != null &&
                        virtualController.getControllerMode() == VirtualController.ControllerMode.Configuration) {
                    // Ignore presses when the virtual controller is in configuration mode
                    return true;
                }

                int actionIndex = event.getActionIndex();

                int eventX = (int)event.getX(actionIndex);
                int eventY = (int)event.getY(actionIndex);

                // Special handling for 3 finger gesture
                if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN &&
                        event.getPointerCount() == 3) {
                    // Three fingers down
                    threeFingerDownTime = SystemClock.uptimeMillis();

                    // Cancel the first and second touches to avoid
                    // erroneous events
                    for (TouchContext aTouchContext : touchContextMap) {
                        aTouchContext.cancelTouch();
                    }

                    return true;
                }

                TouchContext context = getTouchContext(actionIndex);
                if (context == null) {
                    return false;
                }

                switch (event.getActionMasked())
                {
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_DOWN:
                    context.touchDownEvent(eventX, eventY);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                    if (event.getPointerCount() == 1) {
                        // All fingers up
                        if (SystemClock.uptimeMillis() - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                            // This is a 3 finger tap to bring up the keyboard
                            showKeyboard();
                            return true;
                        }
                    }
                    context.touchUpEvent(eventX, eventY);
                    if (actionIndex == 0 && event.getPointerCount() > 1 && !context.isCancelled()) {
                        // The original secondary touch now becomes primary
                        context.touchDownEvent((int)event.getX(1), (int)event.getY(1));
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    // ACTION_MOVE is special because it always has actionIndex == 0
                    // We'll call the move handlers for all indexes manually

                    // First process the historical events
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        for (TouchContext aTouchContextMap : touchContextMap) {
                            if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                            {
                                aTouchContextMap.touchMoveEvent(
                                        (int)event.getHistoricalX(aTouchContextMap.getActionIndex(), i),
                                        (int)event.getHistoricalY(aTouchContextMap.getActionIndex(), i));
                            }
                        }
                    }

                    // Now process the current values
                    for (TouchContext aTouchContextMap : touchContextMap) {
                        if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                        {
                            aTouchContextMap.touchMoveEvent(
                                    (int)event.getX(aTouchContextMap.getActionIndex()),
                                    (int)event.getY(aTouchContextMap.getActionIndex()));
                        }
                    }
                    break;
                default:
                    return false;
                }
            }

            // Handled a known source
            return true;
        }

        // Unknown class
        return false;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return handleMotionEvent(event) || super.onGenericMotionEvent(event);

    }

    private void updateMousePosition(int eventX, int eventY) {
        // Send a mouse move if we already have a mouse location
        // and the mouse coordinates change
        if (lastMouseX != Integer.MIN_VALUE &&
            lastMouseY != Integer.MIN_VALUE &&
            !(lastMouseX == eventX && lastMouseY == eventY))
        {
            int deltaX = eventX - lastMouseX;
            int deltaY = eventY - lastMouseY;

            // Scale the deltas if the device resolution is different
            // than the stream resolution
            deltaX = (int)Math.round((double)deltaX * (REFERENCE_HORIZ_RES / (double)surfaceView.getWidth()));
            deltaY = (int)Math.round((double)deltaY * (REFERENCE_VERT_RES / (double)surfaceView.getHeight()));

            conn.sendMouseMove((short)deltaX, (short)deltaY);
        }

        // Update pointer location for delta calculation next time
        lastMouseX = eventX;
        lastMouseY = eventY;
    }

    @Override
    public boolean onGenericMotion(View v, MotionEvent event) {
        return handleMotionEvent(event);
    }

    /*@SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return handleMotionEvent(event);
    }*/

    @Override
    public void stageStarting(String stage) {
        /*if (spinner != null) {
            spinner.setMessage(getResources().getString(R.string.conn_starting)+" "+stage);
        }*/
    }

    @Override
    public void stageComplete(String stage) {
    }

    private void stopConnection() {
        if (connecting || connected) {
            connecting = connected = false;

            // Stop may take a few hundred ms to do some network I/O to tell
            // the server we're going away and clean up. Let it run in a separate
            // thread to keep things smooth for the UI. Inside moonlight-common,
            // we prevent another thread from starting a connection before and
            // during the process of stopping this one.
            new Thread() {
                public void run() {
                    conn.stop();
                }
            }.start();
        }
    }

    @Override
    public void stageFailed(String stage, long errorCode) {
        // Enable cursor visibility again
        inputCaptureProvider.disableCapture();

        if (!displayedFailureDialog) {
            displayedFailureDialog = true;
            LimeLog.severe(stage+" failed: "+errorCode);


            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
                    getResources().getString(R.string.conn_error_msg)+" "+stage, true);
        }
    }

    @Override
    public void connectionTerminated(long errorCode) {
        // Enable cursor visibility again
        inputCaptureProvider.disableCapture();

        if (!displayedFailureDialog) {
            displayedFailureDialog = true;
            LimeLog.severe("Connection terminated: "+errorCode);
            stopConnection();

            Dialog.displayDialog(this, getResources().getString(R.string.conn_terminated_title),
                    getResources().getString(R.string.conn_terminated_msg), true);
        }
    }

    @Override
    public void connectionStarted() {
        connecting = false;
        connected = true;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Hide the mouse cursor now. Doing it before
                // dismissing the spinner seems to be undone
                // when the spinner gets displayed.
                inputCaptureProvider.enableCapture();
            }
        });
    }


    @Override
    public void onStart() {
        super.onStart();
        controllerManager.start();
        hasFirstFrame = false;
        final Runnable hideSystemUi = new Runnable() {
            @Override
            public void run() {
                renderer.setHasVideoPlaybackStarted(true);
            }
        };
        surfaceView.queueEvent(hideSystemUi);
        //Resume the gvrLayout here. This will start the render thread and trigger a
        //new async reprojection video Surface to become available.
        gvrLayout.onResume();
        //Refresh the viewer profile in case the viewer params were changed.
        surfaceView.queueEvent(refreshViewerProfileRunnable);
    }

    @Override
    public void displayMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                //Toast.makeText(GameDaydream.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void displayTransientMessage(final String message) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    //Toast.makeText(GameDaydream.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /*@Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!connected && !connecting) {
            connecting = true;

            decoderRenderer.setRenderTarget(holder);
            conn.start(PlatformBinding.getAudioRenderer(), decoderRenderer, GameDaydream.this);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Let the decoder know immediately that the surface is gone
        decoderRenderer.prepareForStop();

        if (connected) {
            stopConnection();
        }
    }*/

    @Override
    public void mouseMove(int deltaX, int deltaY) {
        conn.sendMouseMove((short) deltaX, (short) deltaY);
    }

    @Override
    public void mouseButtonEvent(int buttonId, boolean down) {
        byte buttonIndex;

        switch (buttonId)
        {
        case EvdevListener.BUTTON_LEFT:
            buttonIndex = MouseButtonPacket.BUTTON_LEFT;
            break;
        case EvdevListener.BUTTON_MIDDLE:
            buttonIndex = MouseButtonPacket.BUTTON_MIDDLE;
            break;
        case EvdevListener.BUTTON_RIGHT:
            buttonIndex = MouseButtonPacket.BUTTON_RIGHT;
            break;
        default:
            LimeLog.warning("Unhandled button: "+buttonId);
            return;
        }

        if (down) {
            conn.sendMouseButtonDown(buttonIndex);
        }
        else {
            conn.sendMouseButtonUp(buttonIndex);
        }
    }

    @Override
    public void mouseScroll(byte amount) {
        conn.sendMouseScroll(amount);
    }

    @Override
    public void keyboardEvent(boolean buttonDown, short keyCode) {
        short keyMap = KeyboardTranslator.translate(keyCode);
        if (keyMap != 0) {
            // handleSpecialKeys() takes the Android keycode
            if (handleSpecialKeys(keyCode, buttonDown)) {
                return;
            }

            if (buttonDown) {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, getModifierState());
            }
            else {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, getModifierState());
            }
        }
    }

    private void setImmersiveSticky() {
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /*@Override
    public void onSystemUiVisibilityChange(int visibility) {
        // Don't do anything if we're not connected
        if (!connected) {
            return;
        }

        // This flag is set for all devices
        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            hideSystemUi(2000);
        }
        // This flag is only set on 4.4+
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                 (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            hideSystemUi(2000);
        }
        // This flag is only set before 4.4+
        else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT &&
                 (visibility & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0) {
            hideSystemUi(2000);
        }
    }*/
    private class EventListener extends Controller.EventListener
            implements ControllerManager.EventListener, Runnable {

        // The status of the overall controller API. This is primarily used for error handling since
        // it rarely changes.
        private String apiStatus;
        float lastY =  0;
        float deltaY = 0;
        float aux = 0;
        final float limitY= -1.2274516f;
        boolean noTouch = false;

        // The state of a specific Controller connection.
        private int controllerState = ConnectionStates.DISCONNECTED;

        @Override
        public void onApiStatusChanged(int state) {
            apiStatus = ApiStatus.toString(state);
            LimeLog.info("chacho apiStatus: " + apiStatus);


        }

        @Override
        public void onConnectionStateChanged(int state) {
            controllerState = state;
            LimeLog.info("chacho controllerState: " + getString(controllerState));
        }

        @Override
        public void onRecentered() {
            // In a real GVR application, this would have implicitly called recenterHeadTracker().
            // Most apps don't care about this, but apps that want to implement custom behavior when a
            // recentering occurs should use this callback.
            //controllerOrientationView.resetYaw();
            LimeLog.info("chacho [Recentered]");
        }

        @Override
        public void onUpdate() {




            controller.update();
            if (controller.isTouching) {
                if (noTouch == true){ //acabamos de tocar, guardamos la posicion inicial
                    noTouch = false;
                }else{
                    deltaY =  controller.touch.y - lastY;
                    aux = videoTransform[14] + deltaY;
                    if (limitY > aux) {
                        videoTransform[14] = aux;
                        renderer.setVideoTransform(videoTransform);
                    }
                }
                lastY = controller.touch.y;
                LimeLog.info("chacho videoTransform[14]= " + videoTransform[14]);

            } else {

                lastY = 0;
                noTouch = true;


            }
        }

        // Update the various TextViews in the UI thread.
        @Override
        public void run() {
            LimeLog.info("chacho pijo!!!");
            controller.update();

            LimeLog.info("chacho run Controller Orientation: " + controller.orientation);



            float[] angles = new float[3];
            controller.orientation.toYawPitchRollDegrees(angles);


            if (controller.isTouching) {
                LimeLog.info("chacho pasamos 1");
                LimeLog.info(String.format("chacho Touch x,y =  [%4.2f, %4.2f]", controller.touch.x, controller.touch.y));
            } else {
                LimeLog.info("chacho pasamos 2");
                LimeLog.info(String.format("chacho [ NO TOUCH ]"));

            }
        }
    }
}
