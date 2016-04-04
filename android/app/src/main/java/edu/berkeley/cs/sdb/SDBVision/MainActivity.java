/*
 * Based on code from http://blog.csdn.net/torvalbill/article/details/40378539
 */
package edu.berkeley.cs.sdb.SDBVision;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import edu.berkeley.cs.sdb.bosswave.BosswaveClient;
import okhttp3.OkHttpClient;

public class MainActivity extends Activity {
    private static final String LOG_TAG = "SDBVision";
    private static final String IMAGE_POST_URL = "http://castle.cs.berkeley.edu:50021/";
    private static final String BW_ROUTER_URL = "castle.cs.berkeley.edu";
    private static final int BW_ROUTER_PORT = 50026;
    private static final String CONTROL_TOPIC = "castle.bw2.io/michael/0/bwlifx/hsb-light.v1/slot/hsb";

    // hard coded base64 format of the private key, such safe
    private static final byte[] mKey = Base64.decode("Mqmvj8G02K4EncGpRkp3DFSy+rnNZNq2KPjz/t6FUHVLCDYSe/Bp4UapPeFjV6WGJm/KT6bddc8Mrr3vJZV+c7YCCEFVKemy7D4UAwiuUoex8k6fGAUhS2FpZmVpIENoZW4gPGthaWZlaUBiZXJrZWxleS5lZHU+BgNZdXAAhbDzkz/4amI9XxkhzwldzPQ6+Z2DkaTF9pjsp8tTxY1jrper6UziaO+Gs6skX3ICiwBI7A/71/7bVbGaAqOiDw==", Base64.DEFAULT);

    private Context mContext;
    private AutoFitTextureView mTextureView;
    private TextView mTextView;
    private Button mOnButton;
    private Button mOffButton;
    private Button mCaptureButton;

    // ID of the current CameraDevice
    private String mCameraId;
    // A CameraCaptureSession for camera preview.
    private CameraCaptureSession mCaptureSession;
    // A reference to the opened CameraDevice.
    private CameraDevice mCameraDevice;
    // The android.util.Size of camera preview.
    private Size mPreviewSize;
    // The CameraCharacteristics for the currently configured camera device.
    private CameraCharacteristics mCharacteristics;
    // An additional thread for running tasks that shouldn't block the UI.
    private HandlerThread mBackgroundThread;
    // A Handler for running tasks in the background.
    private Handler mBackgroundHandler;
    // An ImageReader that handles still image capture.
    private AutoFitImageReader mImageReader;
    // CaptureRequest.Builder for the camera preview
    private CaptureRequest.Builder mPreviewRequestBuilder;
    // CaptureRequest generated by mPreviewRequestBuilder
    private CaptureRequest mPreviewRequest;
    // A semaphore to prevent the app from exiting before closing the camera.
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    // The HTTP Client used for transmitting image
    private OkHttpClient mHttpClient;
    // The Bosswave Client used for sending control command
    private BosswaveClient mBosswaveClient;
    // The current recognized object name
    private String mTarget;

    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            Log.i(LOG_TAG, "onSurfaceTextureAvailable, width=" + width + ",height=" + height);
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private class onCameraOpenedRunnable implements Runnable {
        private final CameraDevice mCamera;

        public onCameraOpenedRunnable(CameraDevice camera) {
            mCamera = camera;
        }

        @Override
        public void run() {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = mCamera;
            createCameraPreviewSession();
        }
    }

    private class onCameraOpenFailedRunnable implements Runnable {
        private final CameraDevice mCamera;

        public onCameraOpenFailedRunnable(CameraDevice camera) {
            mCamera = camera;
        }

        @Override
        public void run() {
            mCameraOpenCloseLock.release();
            mCamera.close();
            mCameraDevice = null;
        }
    }

    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            Log.i(LOG_TAG, "CameraDevice onOpened");
            runOnUiThread(new onCameraOpenedRunnable(camera));
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.i(LOG_TAG, "CameraDevice onDisconnected");
            runOnUiThread(new onCameraOpenFailedRunnable(camera));
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.i(LOG_TAG, "CameraDevice onError");
            runOnUiThread(new onCameraOpenFailedRunnable(camera));
        }
    };

    private class HttpPostImageRunnable implements Runnable {
        private final byte[] mImageData;
        private final int mWidth;
        private final int mHeight;

        public HttpPostImageRunnable(byte[] imageData, int width, int height) {
            mImageData = imageData;
            mWidth = width;
            mHeight = height;
        }

        @Override
        public void run() {
            try {
                new HttpPostImageTask(mHttpClient, IMAGE_POST_URL, mImageData, mWidth, mHeight, mRecognitionListener).execute();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private final AutoFitImageReader.OnImageAvailableListener mOnImageAvailableListener = new AutoFitImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(byte[] image, int width, int height) {
            setUIEnabled(false, false, false);
            // AsyncTask task instance must be created and executed on the UI thread
            runOnUiThread(new HttpPostImageRunnable(image, width, height));
        }
    };

    private CameraCaptureSession.StateCallback mSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            // The camera is already closed
            if (mCameraDevice == null) {
                return;
            }

            // When the session is ready, we start displaying the preview.
            mCaptureSession = session;
            try {
                setup3AControlsLocked();
                mPreviewRequest = mPreviewRequestBuilder.build();
                mCaptureSession.setRepeatingRequest(mPreviewRequest, null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            showToast("onConfigureFailed", Toast.LENGTH_LONG);
        }
    };

    private final View.OnClickListener mOnButtonOnClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            setUIEnabled(false, false, false);
            String topic = CONTROL_TOPIC;// + mTarget + "/1";
            MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
            try {
                packer.packMapHeader(4);
                packer.packString("hue");
                packer.packDouble(0.2);
                packer.packString("saturation");
                packer.packDouble(0.5);
                packer.packString("brightness");
                packer.packDouble(0.7);
                packer.packString("state");
                packer.packBoolean(true);
                packer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            new BosswavePublishTask(mBosswaveClient, topic, packer.toByteArray(), mBwPublishTaskListener).execute();
        }
    };

    private final View.OnClickListener mOffButtonOnClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            setUIEnabled(false, false, false);
            String topic = CONTROL_TOPIC;// + mTarget + "/0";
            MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
            try {
                packer.packMapHeader(4);
                packer.packString("hue");
                packer.packDouble(0.8);
                packer.packString("saturation");
                packer.packDouble(0.5);
                packer.packString("brightness");
                packer.packDouble(0.7);
                packer.packString("state");
                packer.packBoolean(true);
                packer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            new BosswavePublishTask(mBosswaveClient, topic, packer.toByteArray(), mBwPublishTaskListener).execute();

        }
    };

    private final View.OnClickListener mCaptureButtonOnClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            mImageReader.requestCapture();
        }
    };

    private HttpPostImageTask.Listener mRecognitionListener = new HttpPostImageTask.Listener() {
        @Override
        public void onResponse(String response) {
            if (response != null && !response.trim().equals("None")) {
                showToast(response + " recognized", Toast.LENGTH_SHORT);
                mTarget = response.trim();
                mTextView.setText(response);
                setUIEnabled(true, true, true);
            } else {
                showToast("Nothing recognized", Toast.LENGTH_SHORT);
                mTarget = null;
                mTextView.setText(getString(R.string.none));
                setUIEnabled(false, false, true);
            }
        }
    };

    private BosswaveInitTask.Listener mBwInitTaskListener = new BosswaveInitTask.Listener() {
        @Override
        public void onResponse() {
            setUIEnabled(false, false, true);
        }
    };

    private BosswavePublishTask.Listener mBwPublishTaskListener = new BosswavePublishTask.Listener() {
        @Override
        public void onResponse(String response) {
            showToast("Control command sent: " + response, Toast.LENGTH_SHORT);
            setUIEnabled(true, true, true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mContext = this;

        mTextureView = (AutoFitTextureView) findViewById(R.id.texture);
        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        mTextView = (TextView) findViewById(R.id.text);
        mOnButton = (Button) findViewById(R.id.on);
        mOnButton.setOnClickListener(mOnButtonOnClickListener);
        mOffButton = (Button) findViewById(R.id.off);
        mOffButton.setOnClickListener(mOffButtonOnClickListener);
        mCaptureButton = (Button) findViewById(R.id.capture);
        mCaptureButton.setOnClickListener(mCaptureButtonOnClickListener);

        setUIEnabled(false, false, false);

        mHttpClient = new OkHttpClient();
        mBosswaveClient = new BosswaveClient(BW_ROUTER_URL, BW_ROUTER_PORT);
        new BosswaveInitTask(mBosswaveClient, mKey, mBwInitTaskListener).execute();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    /**
     * Sets up member variables related to camera.
     */
    private void setUpCameraOutputs(int surfaceWidth, int surfaceHeight) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // For still image matching, we use 640x480 if available
                // the preview image will be cropped around center by Android to fit targetImageSize
                // TODO: Android doesn't seem to crop the image for me, I have to build an ImageReader that resizes or crops images
                Size targetImageSize = new Size(640, 480);
                List<Size> imageSizes = Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888));
                if (!imageSizes.contains(targetImageSize)) {
                    throw new RuntimeException("640x480 size is not supported");
                }

                int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
                int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                mImageReader = new AutoFitImageReader(this, sensorOrientation, targetImageSize.getWidth(), targetImageSize.getHeight(), ImageFormat.YUV_420_888, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

                // Find out if we need to swap dimension to get the preview size relative to sensor coordinate.
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (sensorOrientation == 90 || sensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (sensorOrientation == 0 || sensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(LOG_TAG, "Display rotation is invalid: " + displayRotation);
                }

                Point displaySize = new Point();
                getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedSurfaceWidth = surfaceWidth;
                int rotatedSurfaceHeight = surfaceHeight;
                int maxSurfaceWidth = displaySize.x;
                int maxSurfaceHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedSurfaceWidth = surfaceHeight;
                    rotatedSurfaceHeight = surfaceWidth;
                    maxSurfaceWidth = displaySize.y;
                    maxSurfaceHeight = displaySize.x;
                }

                // the preview size has to have the same aspect ratio as the camera sensor, otherwise the image will be skewed
                Rect sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                Size sensorAspectRatioSize = new Size(sensorRect.width(), sensorRect.height());

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                // Another purpose is to makesure the preview aspect ratio is the same as the sensor aspect ratio
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedSurfaceWidth, rotatedSurfaceHeight, maxSurfaceWidth, maxSurfaceHeight, sensorAspectRatioSize);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                mCharacteristics = characteristics;
                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Opens the camera specified by mCameraId.
     */
    private void openCamera(int surfaceWidth, int surfaceHeight) {
        setUpCameraOutputs(surfaceWidth, surfaceHeight);
        configureTransform(surfaceWidth, surfaceHeight);
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current CameraDevice.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its Handler.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its Handler.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            List<Surface> surfaces = Arrays.asList(surface, mImageReader.getSurface());
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());
            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(surfaces, mSessionStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configure the given CaptureRequest.Builder to use auto-focus, auto-exposure, and
     * auto-white-balance controls if available.
     * Call this only with mCameraStateLock held.
     */
    private void setup3AControlsLocked() {
        // Enable auto-magical 3A run by camera device
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

        Float minFocusDist = mCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

        // If MINIMUM_FOCUS_DISTANCE is 0, lens is fixed-focus and we need to skip the AF run.
        boolean noAFRun = (minFocusDist == null || minFocusDist == 0);

        if (!noAFRun) {
            // If there is a "continuous picture" mode available, use it, otherwise default to AUTO.
            if (contains(mCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } else {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            }
        }

        // If there is an auto-magical flash control mode available, use it, otherwise default to
        // the "on" mode, which is guaranteed to always be available.
        if (contains(mCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES), CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        } else {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        }

        // If there is an auto-magical white balance control mode available, use it.
        if (contains(mCharacteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            // Allow AWB to run auto-magically if this device supports this
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }
    }

    /**
     * Configures the necessary android.graphics.Matrix transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param surfaceWidth  The width of `mTextureView`
     * @param surfaceHeight The height of `mTextureView`
     */
    private void configureTransform(int surfaceWidth, int surfaceHeight) {
        if (mTextureView == null || mPreviewSize == null) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF surfaceRect = new RectF(0, 0, surfaceWidth, surfaceHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = surfaceRect.centerX();
        float centerY = surfaceRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(surfaceRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) surfaceHeight / mPreviewSize.getHeight(), (float) surfaceWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices       The list of sizes that the camera supports for the intended output
     *                      class
     * @param surfaceWidth  The width of the texture view relative to sensor coordinate
     * @param surfaceHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth      The maximum width that can be chosen
     * @param maxHeight     The maximum height that can be chosen
     * @param aspectRatio   The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int surfaceWidth, int surfaceHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight && option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= surfaceWidth && option.getHeight() >= surfaceHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(LOG_TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    /**
     * Shows a Toast on the UI thread.
     *
     * @param text     The message to show
     * @param duration How long to display the message. Either LENGTH_SHORT or LENGTH_LONG
     */
    private void showToast(final String text, final int duration) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, duration).show();
            }
        });
    }

    /**
     * Enables or disables click events for all buttons.
     *
     * @param on      true to make the On button clickable, false otherwise
     * @param off     true to make the Off button clickable, false otherwise
     * @param capture true to make the Capture button clickable, false otherwise
     */
    private void setUIEnabled(final boolean on, final boolean off, final boolean capture) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mOnButton.setEnabled(on);
                mOffButton.setEnabled(off);
                mCaptureButton.setEnabled(capture);
            }
        });
    }

    /**
     * Return true if the given array contains the given integer.
     *
     * @param modes array to check.
     * @param mode  integer to get for.
     * @return true if the array contains the given integer, otherwise false.
     */
    private static boolean contains(int[] modes, int mode) {
        if (modes == null) {
            return false;
        }
        for (int i : modes) {
            if (i == mode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }
}