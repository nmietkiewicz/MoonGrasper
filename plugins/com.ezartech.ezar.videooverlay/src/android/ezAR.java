/**
 * Copyright 2016, ezAR Technologies
 * http://ezartech.com
 *
 * By @wayne_parrott, @vridosh, @kwparrott
 *
 * Licensed under a modified MIT license. 
 * Please see LICENSE or http://ezartech.com/ezarstartupkit-license for more information
 *
 */
package com.ezartech.ezar.videooverlay;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.webkit.WebView;
import android.widget.FrameLayout;



/**
 * Implements the ezAR VideoOverlay api for Android.
 */
public class ezAR extends CordovaPlugin {
	private static final String TAG = "ezAR";

	//event type code
	static final int UNDEFINED = -1;
	static final int STARTED = 0;
	static final int STOPPED = 1;

	private CallbackContext callbackContext;

	private Activity activity;
	private FrameLayout cordovaViewContainer;
	private View webViewView;
	private TextureView cameraView;

	private Camera camera = null;
	private int cameraId = -1;
	private CameraDirection cameraDirection;
	private SizePair previewSizePair = null;
	private double currentZoom;
	private boolean isPreviewing = false;
	private boolean isPaused = false;

	private boolean supportSnapshot;

	protected final static String[] permissions = {Manifest.permission.CAMERA};
	public final static int PERMISSION_DENIED_ERROR = 20;
	public final static int CAMERA_SEC = 0;

	private View.OnLayoutChangeListener layoutChangeListener =
			new View.OnLayoutChangeListener() {
				public void onLayoutChange (View v, int left, int top, int right, int bottom,
									         int oldLeft, int oldTop, int oldRight, int oldBottom) {
					Log.d(TAG,"layout change: " + left + ":" + top + ":" + right + ":" + bottom);
					if (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom) {
						//do nothing
						return;
					}

					if (isPreviewing) {
						updateCordovaViewContainerSize();
					}
				}
			};

	/**
	 * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
	 * {@link TextureView}.
	 */
	private TextureView.SurfaceTextureListener mSurfaceTextureListener =
			new TextureView.SurfaceTextureListener() {

				@Override
				public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
													  int width, int height) {
					if (isPreviewing) { //only called from onResume
						isPreviewing = false; //must set isPreviewing false before calling startPreview else NOP occurs
						startPreview(cameraDirection, currentZoom, null);
					}
				}

				@Override
				public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
														int width, int height) {
				}

				@Override
				public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
					return true;
				}

				@Override
				public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
				}

			};



	@Override
	public void initialize(final CordovaInterface cordova, final CordovaWebView cvWebView) {
		super.initialize(cordova, cvWebView);

		webViewView = cvWebView.getView();

		activity = cordova.getActivity();
		activity.runOnUiThread(new Runnable() {
			@Override
			public void run() {

				//configure webview
				webViewView.setKeepScreenOn(true);
				webViewView.setLayerType(WebView.LAYER_TYPE_SOFTWARE, null);
				webViewView.setBackgroundColor(Color.BLACK);

				//temporarily remove webview from view stack
				((ViewGroup) webViewView.getParent()).removeView(webViewView);

				cordovaViewContainer = new FrameLayout(activity);
				cordovaViewContainer.setBackgroundColor(Color.BLACK);
				activity.setContentView(cordovaViewContainer,
						new ViewGroup.LayoutParams(
								LayoutParams.MATCH_PARENT,
								LayoutParams.MATCH_PARENT));

				//create & add videoOverlay to view stack

				cameraView = new TextureView(activity);
				cameraView.setBackgroundColor(Color.BLACK);
				cameraView.setSurfaceTextureListener(mSurfaceTextureListener);
				cordovaViewContainer.addView(cameraView,
						new ViewGroup.LayoutParams(
								LayoutParams.MATCH_PARENT,
								LayoutParams.MATCH_PARENT));


				//add webview on top of videoOverlay
				cordovaViewContainer.addView(webViewView,
						new ViewGroup.LayoutParams(
								LayoutParams.MATCH_PARENT,
								LayoutParams.MATCH_PARENT));

				((FrameLayout)cordovaViewContainer.getParent()).setBackgroundColor(Color.BLACK);
				((FrameLayout)cordovaViewContainer.getParent()).addOnLayoutChangeListener(layoutChangeListener);
			}
		});
	}


	@Override
	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
		Log.d(TAG, action + " " + args.length());

		if (action.equals("init")) {
			this.init(callbackContext);
			return true;
		} else if (action.equals("startCamera")) {
			this.startPreview(
					args.getString(0),
					getDoubleOrNull(args, 1),
					callbackContext);

			return true;
		} else if (action.equals("stopCamera")) {
			this.stopPreview(callbackContext);

			return true;
		} else if (action.equals("setZoom")) {
			this.setZoom(getDoubleOrNull(args, 0), callbackContext);

			return true;
		}

		return false;
	}

	private void init(final CallbackContext callbackContext) {
		this.callbackContext = callbackContext;

		supportSnapshot = getSnapshotPlugin() != null;

		if (!PermissionHelper.hasPermission(this, permissions[0])) {
			PermissionHelper.requestPermission(this, CAMERA_SEC, Manifest.permission.CAMERA);
			return;
		}

		JSONObject jsonObject = new JSONObject();
		try {
			Display display = activity.getWindowManager().getDefaultDisplay();
			DisplayMetrics m = new DisplayMetrics();
			display.getMetrics(m);

			jsonObject.put("displayWidth", m.widthPixels);
			jsonObject.put("displayHeight", m.heightPixels);

			int mNumberOfCameras = Camera.getNumberOfCameras();
			Log.d(TAG, "Cameras:" + mNumberOfCameras);

			// Find the ID of the back-facing ("default") camera
			Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
			for (int i = 0; i < mNumberOfCameras; i++) {
				Camera.getCameraInfo(i, cameraInfo);

				Parameters parameters;
				Camera open = null;
				try {
					open = Camera.open(i);
					parameters = open.getParameters();
				} finally {
					if (open != null) {
						open.release();
					}
				}

				Log.d(TAG, "Camera facing:" + cameraInfo.facing);

				CameraDirection type = null;
				for (CameraDirection f : CameraDirection.values()) {
					if (f.getDirection() == cameraInfo.facing) {
						type = f;
					}
				}

				if (type != null) {
					double zoom = 0;
					double maxZoom = 0;
					if (parameters.isZoomSupported()) {
						maxZoom = (parameters.getMaxZoom() + 1) / 10.0;
						zoom = Math.min(parameters.getZoom() / 10.0 + 1, maxZoom);
					}

					JSONObject jsonCamera = new JSONObject();
					jsonCamera.put("id", i);
					jsonCamera.put("position", type.toString());
					jsonCamera.put("zoom", zoom);
					jsonCamera.put("maxZoom", maxZoom);
					jsonObject.put(type.toString(), jsonCamera);
				}
			}
		} catch (JSONException e) {
			Log.e(TAG, "Can't set exception", e);
		}

		callbackContext.success(jsonObject);
	}

	@Override
	public void onRequestPermissionResult(int requestCode, String[] permissions,
										  int[] grantResults) throws JSONException {
		for (int r : grantResults) {
			if (r == PackageManager.PERMISSION_DENIED) {
				this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, PERMISSION_DENIED_ERROR));
				return;
			}
		}
		switch (requestCode) {
			case CAMERA_SEC:
				init(this.callbackContext);
				break;
		}
	}

	private void startPreview(final String cameraDirName,
							  final double zoom,
							  final CallbackContext callbackContext) {

		CameraDirection cameraDir = CameraDirection.valueOf(cameraDirName);

		Log.d(TAG, "startPreview called " + cameraDir +
				" " + zoom +
				" " + "isShown " + cameraView.isShown() +
				" " + cameraView.getWidth() + ", " + cameraView.getHeight());

		startPreview(cameraDir, zoom, callbackContext);
	}


	private void startPreview(final CameraDirection cameraDir,
							  final double zoom,
							  final CallbackContext callbackContext) {

		if (activity == null || activity.isFinishing()) {
			return;
		}

		if (isPreviewing) {
			if (cameraId != getCameraId(cameraDir)) {
				stopPreview(null);
			}
		}

		cameraId = getCameraId(cameraDir);
		cameraDirection = cameraDir;

		if (cameraId != UNDEFINED) {
			camera = Camera.open(cameraId);
		}

		if (camera == null) {
			if (callbackContext != null) callbackContext.error("No camera available");
			return;
		}

		initCamera(camera);

		cordova.getActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					isPreviewing = true;
					updateCameraDisplayOrientation();

					//configure scaled CVG size & preview matrix
					updateCordovaViewContainerSize();

					camera.startPreview();
					webViewView.setBackgroundColor(Color.TRANSPARENT);
					setZoom(zoom, null);

					sendFlashlightEvent(STARTED, cameraDirection, cameraId, camera);

					if (callbackContext != null) {
						callbackContext.success();
					}

				} catch (Exception e) {
					Log.e(TAG, "Error during preview create", e);
					if (callbackContext != null) callbackContext.error(TAG + ": " + e.getMessage());
				}

			}

		});
	}

	private void stopPreview(final CallbackContext callbackContext) {
		Log.d(TAG, "stopPreview called");

		if (!isPreviewing) { //do nothing if not currently previewing
			if (callbackContext != null) {
				callbackContext.success();
			}
			return;
		}

		try {
			camera.stopPreview();
			camera.setPreviewDisplay(null);
			sendFlashlightEvent(STOPPED, cameraDirection, cameraId, null);
			camera.release();
			cordova.getActivity().runOnUiThread(new Runnable() {
				@Override
				public void run() {
					webViewView.setBackgroundColor(Color.BLACK);
					resetCordovaViewContainerSize();
				}
			});

		} catch (IOException e) {
			e.printStackTrace();
		}

		cameraId = UNDEFINED;
		camera = null;
		isPreviewing = false;

		if (callbackContext != null) {
			callbackContext.success();
		}
	}

	private void setZoom(final double newZoom, final CallbackContext callbackContext) {
		try {
			Parameters parameters = camera.getParameters();
			if (!parameters.isZoomSupported()) {
				//do nothing
				if (callbackContext != null) {
					callbackContext.success();
				}
				return;
			}

			int maxZoom = parameters.getMaxZoom();
			double normalizedNewZoom = Math.max(1.0, newZoom);
			float scale = (float)(10-1) / (float)maxZoom;
			int androidZoom = (int) Math.round((normalizedNewZoom - 1) / scale);
			androidZoom = Math.min(androidZoom, maxZoom);

			parameters.setZoom(androidZoom);
			camera.setParameters(parameters);
			currentZoom = normalizedNewZoom;

			if (callbackContext != null) {
				callbackContext.success();
			}
		} catch (Throwable e) {
			if (callbackContext != null) {
				callbackContext.error(e.getMessage());
			}
		}
	}

	private void initCamera(Camera camera) {
		Camera.Parameters cameraParameters = camera.getParameters();

		List<String> focusModes = cameraParameters.getSupportedFocusModes();
		if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
			cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
		}

//		camera.enableShutterSound(true);  //requires api 17

		previewSizePair = selectSizePair(
				cameraParameters.getPreferredPreviewSizeForVideo(),
				cameraParameters.getSupportedPreviewSizes(),
				cameraParameters.getSupportedPictureSizes(),
				cameraView.getWidth(),
				cameraView.getHeight());

		Log.d(TAG, "preview size: " + previewSizePair.previewSize.width + ":" + previewSizePair.previewSize.height);

		cameraParameters.setPreviewSize(previewSizePair.previewSize.width, previewSizePair.previewSize.height);
		Camera.Size picSize = previewSizePair.pictureSize != null ? previewSizePair.pictureSize : previewSizePair.previewSize;
		cameraParameters.setPictureSize(picSize.width,picSize.height);

		Log.d(TAG, "picture size: " + picSize.width + ":" + picSize.height);

		camera.setParameters(cameraParameters);

		try {
			if (cameraView.getSurfaceTexture() != null) {
				camera.setPreviewTexture(cameraView.getSurfaceTexture());
			}
		} catch (IOException e) {
			Log.e(TAG, "Unable to attach preview to camera!", e);
		}
	}


	@Override
	public void onPause(boolean multitasking) {
		super.onPause((multitasking));
		if (isPreviewing) {
			int camId = cameraId;
			CameraDirection camDir = cameraDirection;
			stopPreview(null);

			//reset state so it can be restored onResume
			isPreviewing = true;
			this.cameraId = camId;
			this.cameraDirection = camDir;
		}

		isPaused = true;
	}


	@Override
	public void onResume(boolean multitasking) {
		super.onResume(multitasking);

		//must wait until surfaceTexture is available for use by camera before starting preview
		//see onSurfaceTextureAvailable

		isPaused = false;
	}

	//only call from UI thread
	private void updateCordovaViewContainerSize() {

		cordova.getActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				FrameLayout.LayoutParams paramsX = (FrameLayout.LayoutParams) cordovaViewContainer.getLayoutParams();
				Log.d(TAG,"updateCordovaViewContainer PRE invalidate: " + paramsX.width + ":" + paramsX.height);

				Size sz = getDefaultWebViewSize();
				int previewWidth = previewSizePair.previewSize.width;
				int previewHeight = previewSizePair.previewSize.height;

				if (isPortraitOrientation()) {
					previewWidth = previewSizePair.previewSize.height;
					previewHeight = previewSizePair.previewSize.width;
				}

				float scale = Math.min((float) sz.width / (float) previewWidth, (float) sz.height / (float) previewHeight);
				float dx = Math.abs(sz.width - previewWidth * scale) / 2f;
				float dy = Math.abs(sz.height - previewHeight * scale) / 2f;

				Log.d(TAG, "computeTransform, scale: " + scale);

				int cvcWidth = (int)(previewWidth * scale);
				int cvcHt = (int)(previewHeight * scale);

				Log.d(TAG, "updateCordovaViewContainer cvs size: " + cvcWidth + ":" + cvcHt);

				FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cordovaViewContainer.getLayoutParams();
				params.width = cvcWidth;
				params.height = cvcHt;
				params.gravity = Gravity.CENTER;
				cordovaViewContainer.setLayoutParams(params);

				View v = cordova.getActivity().findViewById(android.R.id.content);
				v.requestLayout();

				updateCameraDisplayOrientation();

				paramsX = (FrameLayout.LayoutParams) cordovaViewContainer.getLayoutParams();
				Log.d(TAG, "updateCordovaViewContainer POST invalidate: " + paramsX.width + ":" + paramsX.height);
			}
		});
	}

	private void resetCordovaViewContainerSize() {
		cordova.getActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Size sz = getDefaultWebViewSize();
				int cvcWidth = sz.width;
				int cvcHt =  sz.height;

				FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)cordovaViewContainer.getLayoutParams();
				params.width = cvcWidth;
				params.height = cvcHt;
				params.gravity = Gravity.FILL;
				cordovaViewContainer.setLayoutParams(params);

				View v = cordova.getActivity().findViewById(android.R.id.content);
				v.requestLayout();
			}
		});
	}

	private int getCameraId(CameraDirection cameraDir) {

		// Find number of cameras available
		int numberOfCameras = Camera.getNumberOfCameras();
		Log.d(TAG, "Cameras:" + numberOfCameras);

		// Find ID of the back-facing ("default") camera
		Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
		int cameraIdToOpen = UNDEFINED;
		for (int i = 0; i < numberOfCameras; i++) {
			Camera.getCameraInfo(i, cameraInfo);

			Log.d(TAG, "Camera facing:" + cameraInfo.facing);

			if (cameraInfo.facing == cameraDir.getDirection()) {
				cameraIdToOpen = i;
				break;
			}
		}

		if (cameraIdToOpen == UNDEFINED) {
			cameraIdToOpen = numberOfCameras - 1;
		}

		return cameraIdToOpen;
	}


	public void updateCameraDisplayOrientation() {
		int result = getRoatationAngle(cameraId);
		camera.setDisplayOrientation(result);

//moved to snapshot plugin
//		Camera.Parameters params = camera.getParameters();
//		//params.setRotation(result);
//		if (cameraDirection == CameraDirection.FRONT) {
//			result -= 90;
//		}
//		params.setRotation(result % 360);
//		camera.setParameters(params);

		Log.i(TAG, "updateCameraDeviceOrientation: " + result);
	}

	/**
	 * Get Rotation Angle
	 *
	 * @param cameraId probably front cam
	 * @return angel to rotate
	 */
	public int getRoatationAngle(int cameraId) {
		Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
		Camera.getCameraInfo(cameraId, info);
		int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
		int degrees = 0;
		switch (rotation) {
			case Surface.ROTATION_0:
				degrees = 0;
				break;
			case Surface.ROTATION_90:
				degrees = 90;
				break;
			case Surface.ROTATION_180:
				degrees = 180;
				break;
			case Surface.ROTATION_270:
				degrees = 270;
				break;
		}
		int result;
		if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
			result = (info.orientation + degrees) % 360;
			result = (360 - result) % 360; // compensate the mirror
		} else { // back-facing
			result = (info.orientation - degrees + 360) % 360;
		}

		return result;
	}

	private boolean isPortraitOrientation() {
		Display display = activity.getWindowManager().getDefaultDisplay();
		boolean isPortrait = display.getRotation() == Surface.ROTATION_0 || display.getRotation() == Surface.ROTATION_180;
		return isPortrait;
	}


	/**
	 * Selects the most suitable preview and picture size, given the desired width and height.
	 * <p/>
	 * Even though we may only need the preview size, it's necessary to find both the preview
	 * size and the picture size of the camera together, because these need to have the same aspect
	 * ratio.  On some hardware, if you would only set the preview size, you will get a distorted
	 * image.
	 *
	 * @param preferredVideoSize  the best preview size
	 * @param desiredWidth  the desired width of the camera preview frames
	 * @param desiredHeight the desired height of the camera preview frames
	 * @return the selected preview and picture size pair
	 */
	//code influenced by https://github.com/googlesamples/android-vision/blob/master/visionSamples/barcode-reader/app/src/main/java/com/google/android/gms/samples/vision/barcodereader/ui/camera/CameraSource.java
	private static SizePair selectSizePair(Camera.Size preferredVideoSize,
										   List<android.hardware.Camera.Size> supportedPreviewSizes,
										   List<android.hardware.Camera.Size> supportedPictureSizes,
										   int desiredWidth, int desiredHeight) {
		List<SizePair> validPreviewSizes =
				generateValidPreviewSizeList(supportedPreviewSizes,supportedPictureSizes);

		// The method for selecting the best size is to minimize the sum of the differences between
		// the desired values and the actual values for width and height.  This is certainly not the
		// only way to select the best size, but it provides a decent tradeoff between using the
		// closest aspect ratio vs. using the closest pixel area.
		SizePair selectedPair = null;
		int minDiff = Integer.MAX_VALUE;
		for (SizePair sizePair : validPreviewSizes) {

			if (preferredVideoSize != null && preferredVideoSize.equals(sizePair.previewSize)) {
				return sizePair;
			}

			if (supportedPictureSizes != null && sizePair.pictureSize == null) {
				//req'd picture size not avail for this previewSize; skip it
				continue;
			}

			//find largest previewSize w/ area < desired area
			Camera.Size size = sizePair.previewSize;
			int diff = (desiredWidth + desiredHeight) - (size.width + size.height);
			if (0 <= diff && diff < minDiff) {
				selectedPair = sizePair;
				minDiff = diff;
			}
		}

		return selectedPair;
	}


	/**
	 * If the absolute difference between a preview size aspect ratio and a picture size aspect
	 * ratio is less than this tolerance, they are considered to be the same aspect ratio.
	 */
	private static final float ASPECT_RATIO_TOLERANCE = 0.01f;

	/**
	 * Stores a preview size and a corresponding same-aspect-ratio picture size.  To avoid distorted
	 * preview images on some devices, the picture size must be set to a size that is the same
	 * aspect ratio as the preview size or the preview may end up being distorted.  If the picture
	 * size is null, then there is no picture size with the same aspect ratio as the preview size.
	 * https://github.com/googlesamples/android-vision/blob/master/visionSamples/barcode-reader/app/src/main/java/com/google/android/gms/samples/vision/barcodereader/ui/camera/CameraSource.java
	 */
	private static class SizePair {

		public Camera.Size previewSize;
		public Camera.Size pictureSize;
		public float previewAspectRatio;

		public SizePair(Camera.Size previewSize,
						Camera.Size pictureSize) {
			this.previewSize = previewSize;
			this.pictureSize = pictureSize;
			this.previewAspectRatio = this.previewSize.width / this.previewSize.height;
		}


	}

	/**
	 * Generates a list of acceptable preview sizes.  Preview sizes are not acceptable if there is
	 * not a corresponding picture size of the same aspect ratio.  If there is a corresponding
	 * picture size of the same aspect ratio, the picture size is paired up with the preview size.
	 * <p/>
	 * This is necessary because even if we don't use still pictures, the still picture size must be
	 * set to a size that is the same aspect ratio as the preview size we choose.  Otherwise, the
	 * preview images may be distorted on some devices.
	 */
	private static List<SizePair> generateValidPreviewSizeList(
			List<android.hardware.Camera.Size> supportedPreviewSizes,
			List<android.hardware.Camera.Size> supportedPictureSizes) {

		List<SizePair> validPreviewSizes = new ArrayList<SizePair>();
//		for (android.hardware.Camera.Size previewSize : supportedPreviewSizes) {
////			Log.v(TAG, "PV:  " + previewSize.width + ":" + previewSize.height);
//
//			//if no supported picture sizes then leave this loop
//			if (supportedPictureSizes == null) break;
//
//			float previewAspectRatio = (float) previewSize.width / (float) previewSize.height;
//			Camera.Size bestPictureSize = null;
//			float  bestScale = Float.MAX_VALUE;
//
//			// By looping through the picture sizes in order, we favor the higher resolutions.
//			// We choose the highest resolution in order to support taking the full resolution
//			// picture later.
//			for (Camera.Size pictureSize : supportedPictureSizes) {
////				Log.v(TAG, "PIC:  " + pictureSize.width + ":" + pictureSize.height);
//
//				float pictureAspectRatio = (float) pictureSize.width / (float) pictureSize.height;
//				if (Math.abs(previewAspectRatio - pictureAspectRatio) < ASPECT_RATIO_TOLERANCE) {
//
//					float scale = (float) pictureSize.width / (float) previewSize.width;
//					if (1.0f <= scale && scale <= 1.5f && (bestPictureSize == null || scale < bestScale)) {
//						bestScale = scale;
//						bestPictureSize = pictureSize;
//						//break;
//						if (bestScale == 1.0f) break;
//					} else if (scale < 1.0f && bestScale > 1.5f) {
//						bestScale = scale;
//						bestPictureSize = pictureSize;
//						break;
//					}
//				}
//			}
//			if (bestPictureSize != null) {
//				validPreviewSizes.add(new SizePair(previewSize, bestPictureSize));
//			}
//		}

		// If there are no picture sizes with the same aspect ratio as any preview sizes, allow all
		// of the preview sizes and hope that the camera can handle it.  Probably unlikely, but we
		// still account for it.
		if (validPreviewSizes.size() == 0) {
			Log.d(TAG, "No preview sizes have a corresponding same-aspect-ratio picture size");
			for (Camera.Size previewSize : supportedPreviewSizes) {
				Log.d(TAG, "PV:  " + previewSize.width + ":" + previewSize.height);
				// The null picture size will let us know that we shouldn't set a picture size.
				validPreviewSizes.add(new SizePair(previewSize, null));
			}
		}

		return validPreviewSizes;
	}

	private Size getDefaultWebViewSize() {
		FrameLayout cvcParent = (FrameLayout)cordovaViewContainer.getParent();
		Size sz = new Size(cvcParent.getWidth(),cvcParent.getHeight());
		return sz;
	}


	private static class Size {
		public int width;
		public int height;

		public Size(int width, int height) {
			this.width = width;
			this.height = height;
		}
	}



	private static int getIntOrNull(JSONArray args, int i) {
		if (args.isNull(i)) {
			return Integer.MIN_VALUE;
		}

		try {
			return args.getInt(i);
		} catch (JSONException e) {
			Log.e(TAG, "Can't get double", e);
			throw new RuntimeException(e);
		}
	}

	private static double getDoubleOrNull(JSONArray args, int i) {
		if (args.isNull(i)) {
			return Double.NaN;
		}

		try {
			return args.getDouble(i);
		} catch (JSONException e) {
			Log.e(TAG, "Can't get double", e);
			throw new RuntimeException(e);
		}
	}


  //------------- used by Flashlight plugin --------------------
  //HACK using IPC between videoOverlayPlugin and flashlight plugin
  //TODO: refactor to use events and listener pattern

	public Camera getActiveCamera() {
		return camera;
	}

	public Integer getActiveCameraId() {
		return Integer.valueOf(cameraId);
	}

	public Camera getBackCamera() {
		Camera camera = null;
		if (cameraDirection == CameraDirection.BACK) {
			camera = this.camera;
		}
		return camera;
	}

	public Camera getFrontCamera() {
		Camera camera = null;
		if (cameraDirection == CameraDirection.FRONT) {
			camera = this.camera;
		}
		return camera;
	}

	public TextureView getCameraView() {
		return cameraView;
	}

	//reflectively access VideoOverlay plugin to get camera in same direction as lightLoc
	private void sendFlashlightEvent(int state, CameraDirection cameraDirection, int cameraId, Camera camera) {

		CordovaPlugin flashlightPlugin = getFlashlightPlugin();
		if (flashlightPlugin == null) {
			return;
		}

		Method method = null;

		try {
			if (state == STARTED) {
				method = flashlightPlugin.getClass().getMethod("videoOverlayStarted", int.class, int.class, Camera.class );
			} else {
				method = flashlightPlugin.getClass().getMethod("videoOverlayStopped", int.class, int.class, Camera.class );
			}
		} catch (SecurityException e) {
			//e.printStackTrace();
		} catch (NoSuchMethodException e) {
			//e.printStackTrace();
		}

		try {
			if (method == null) return;

			method.invoke(flashlightPlugin, cameraDirection.ordinal(), cameraId, camera);

		} catch (IllegalArgumentException e) { // exception handling omitted for brevity
			//e.printStackTrace();
		} catch (IllegalAccessException e) { // exception handling omitted for brevity
			//e.printStackTrace();
		} catch (InvocationTargetException e) { // exception handling omitted for brevity
			//e.printStackTrace();
		}
	}

//	public Float getPictureScale() {
//
//		if (previewSizePair.pictureSize == null) return Float.MAX_VALUE;
//
//		float scale = previewSizePair.pictureSize.width / previewSizePair.previewSize.width;
//
//		return Float.valueOf(scale);
//	}

	private CordovaPlugin getFlashlightPlugin() {
		String pluginName = "flashlight";
		return getPlugin(pluginName);
	}

	private CordovaPlugin getSnapshotPlugin() {
		String pluginName = "snapshot";
		return getPlugin(pluginName);
	}

	private CordovaPlugin getPlugin(String pluginName) {
		CordovaPlugin plugin = webView.getPluginManager().getPlugin(pluginName);
		return plugin;
	}
}


