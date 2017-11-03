package doext.implement;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.json.JSONObject;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.opengl.GLES10;
import android.os.Handler;
import android.util.Log;
import core.DoServiceContainer;
import core.helper.DoIOHelper;
import core.helper.DoTextHelper;
import core.helper.DoUIModuleHelper;
import core.interfaces.DoIPageView;
import core.interfaces.DoIScriptEngine;
import core.interfaces.DoIUIModuleView;
import core.object.DoInvokeResult;
import core.object.DoUIModule;
import doext.define.do_ImageCropView1_IMethod;
import doext.define.do_ImageCropView1_MAbstract;
import doext.implement.do_ImageCropView1.CropImageView;
import doext.implement.do_ImageCropView1.CropUtil;
import doext.implement.do_ImageCropView1.HighlightView;
import doext.implement.do_ImageCropView1.ImageViewTouchBase;
import doext.implement.do_ImageCropView1.RotateBitmap;

/**
 * 自定义扩展UIView组件实现类，此类必须继承相应VIEW类，并实现DoIUIModuleView,do_ImageCropView1_IMethod接口
 * ； #如何调用组件自定义事件？可以通过如下方法触发事件：
 * this.model.getEventCenter().fireEvent(_messageName, jsonResult);
 * 参数解释：@_messageName字符串事件名称，@jsonResult传递事件参数对象； 获取DoInvokeResult对象方式new
 * DoInvokeResult(this.model.getUniqueKey());
 */
public class do_ImageCropView1_View extends CropImageView implements DoIUIModuleView, do_ImageCropView1_IMethod {

	private static final int SIZE_DEFAULT = 2048;
	private static final int SIZE_LIMIT = 4096;

	private final Handler handler = new Handler();

	private int aspectX = 1;
	private int aspectY = 1;

	// Output image
	private int maxX;
	private int maxY;
	private int exifRotation;
	private boolean saveAsPng;

	private Uri sourceUri;

	private int sampleSize;
	private RotateBitmap rotateBitmap;
	private HighlightView cropView;

	private Context mContext;
	private DoIPageView mPageView;

	/**
	 * 每个UIview都会引用一个具体的model实例；
	 */
	private do_ImageCropView1_MAbstract model;

	public do_ImageCropView1_View(Context context) {
		super(context);
		this.mContext = context;
	}

	/**
	 * 初始化加载view准备,_doUIModule是对应当前UIView的model实例
	 */
	@Override
	public void loadView(DoUIModule _doUIModule) throws Exception {
		this.model = (do_ImageCropView1_MAbstract) _doUIModule;

		if (this.mContext instanceof DoIPageView) {
			mPageView = (DoIPageView) this.mContext;
		}

		this.setRecycler(new ImageViewTouchBase.Recycler() {
			@Override
			public void recycle(Bitmap b) {
				b.recycle();
				System.gc();
			}
		});
	}

	/**
	 * 动态修改属性值时会被调用，方法返回值为true表示赋值有效，并执行onPropertiesChanged，否则不进行赋值；
	 * 
	 * @_changedValues<key,value>属性集（key名称、value值）；
	 */
	@Override
	public boolean onPropertiesChanging(Map<String, String> _changedValues) {
		return true;
	}

	/**
	 * 属性赋值成功后被调用，可以根据组件定义相关属性值修改UIView可视化操作；
	 * 
	 * @_changedValues<key,value>属性集（key名称、value值）；
	 */
	@Override
	public void onPropertiesChanged(Map<String, String> _changedValues) {
		DoUIModuleHelper.handleBasicViewProperChanged(this.model, _changedValues);
		if (_changedValues.containsKey("source")) {
			String source = _changedValues.get("source");
			try {
				if (source != null && !"".equals(source)) {
					String path = DoIOHelper.getLocalFileFullPath(this.model.getCurrentPage().getCurrentApp(), source);
					if (DoIOHelper.isAssets(path)) {
//						rotateBitmap = new RotateBitmap(DoImageHandleHelper.resizeScaleImage(DoIOHelper.readAllBytes(path), 500, 500), 0);
						//copy到sdcard
						String _targetFile = this.model.getCurrentPage().getCurrentApp().getDataFS().getRootPath() + File.separator + DoIOHelper.getFileName(path);
						copyAssetToSdcard(path, _targetFile);
						path = _targetFile;
					}
					sourceUri = Uri.fromFile(new File(path));
					exifRotation = CropUtil.getExifRotation(CropUtil.getFromMediaUri(mContext, mContext.getContentResolver(), sourceUri));
					InputStream is = null;
					try {
						sampleSize = calculateBitmapSampleSize(sourceUri);
						is = mContext.getContentResolver().openInputStream(sourceUri);
						BitmapFactory.Options option = new BitmapFactory.Options();
						option.inSampleSize = sampleSize;
						rotateBitmap = new RotateBitmap(BitmapFactory.decodeStream(is, null, option), exifRotation);
					} catch (IOException e) {
						Log.e("do_ImageCropView1", "Error reading image: " + e.getMessage(), e);
					} catch (OutOfMemoryError e) {
						Log.e("do_ImageCropView1", "OOM reading image: " + e.getMessage(), e);
					} finally {
						CropUtil.closeSilently(is);
					}

					if (rotateBitmap == null) {
						return;
					}

					startCrop();
				}
			} catch (Exception e) {
				DoServiceContainer.getLogEngine().writeError("do_ImageCropView1 source \n\t", e);
			}
		}
	}

	public static void copyAssetToSdcard(String _srcFile, String _targetFile) throws IOException {
		Context _context = DoServiceContainer.getPageViewFactory().getAppContext();
		InputStream in = _context.getAssets().open(DoIOHelper.getAssetsRelPath(_srcFile));
		BufferedInputStream bis = new BufferedInputStream(in);

		OutputStream out = new FileOutputStream(_targetFile);
		// Transfer bytes from in to out
		byte[] buf = new byte[4096];
		int len;
		while ((len = in.read(buf)) > 0) {
			out.write(buf, 0, len);
		}

		out.close();
		bis.close();
	}

	/**
	 * 同步方法，JS脚本调用该组件对象方法时会被调用，可以根据_methodName调用相应的接口实现方法；
	 * 
	 * @_methodName 方法名称
	 * @_dictParas 参数（K,V），获取参数值使用API提供DoJsonHelper类；
	 * @_scriptEngine 当前Page JS上下文环境对象
	 * @_invokeResult 用于返回方法结果对象
	 */
	@Override
	public boolean invokeSyncMethod(String _methodName, JSONObject _dictParas, DoIScriptEngine _scriptEngine, DoInvokeResult _invokeResult) throws Exception {
		return false;
	}

	/**
	 * 异步方法（通常都处理些耗时操作，避免UI线程阻塞），JS脚本调用该组件对象方法时会被调用， 可以根据_methodName调用相应的接口实现方法；
	 * 
	 * @throws Exception
	 * 
	 * @_methodName 方法名称
	 * @_dictParas 参数（K,V），获取参数值使用API提供DoJsonHelper类；
	 * @_scriptEngine 当前page JS上下文环境
	 * @_callbackFuncName 回调函数名 #如何执行异步方法回调？可以通过如下方法：
	 *                    _scriptEngine.callback(_callbackFuncName,
	 *                    _invokeResult);
	 *                    参数解释：@_callbackFuncName回调函数名，@_invokeResult传递回调函数参数对象；
	 *                    获取DoInvokeResult对象方式new
	 *                    DoInvokeResult(this.model.getUniqueKey());
	 */
	@Override
	public boolean invokeAsyncMethod(String _methodName, JSONObject _dictParas, DoIScriptEngine _scriptEngine, String _callbackFuncName) throws Exception {
		if ("crop".equals(_methodName)) {
			this.crop(_dictParas, _scriptEngine, _callbackFuncName);
			return true;
		}
		return false;
	}

	/**
	 * 释放资源处理，前端JS脚本调用closePage或执行removeui时会被调用；
	 */
	@Override
	public void onDispose() {
		if (rotateBitmap != null) {
			rotateBitmap.recycle();
		}
	}

	/**
	 * 重绘组件，构造组件时由系统框架自动调用；
	 * 或者由前端JS脚本调用组件onRedraw方法时被调用（注：通常是需要动态改变组件（X、Y、Width、Height）属性时手动调用）
	 */
	@Override
	public void onRedraw() {
		this.setLayoutParams(DoUIModuleHelper.getLayoutParams(this.model));
	}

	/**
	 * 获取当前model实例
	 */
	@Override
	public DoUIModule getModel() {
		return model;
	}

	/**
	 * 裁剪图片；
	 * 
	 * @throws Exception
	 * 
	 * @_dictParas 参数（K,V），可以通过此对象提供相关方法来获取参数值（Key：为参数名称）；
	 * @_scriptEngine 当前Page JS上下文环境对象
	 * @_callbackFuncName 回调函数名
	 */
	@Override
	public void crop(JSONObject _dictParas, final DoIScriptEngine _scriptEngine, final String _callbackFuncName) throws Exception {
		if (sourceUri == null) {
			throw new Exception("source属性不能为空！");
		}

		if (cropView == null) {
			return;
		}

		Bitmap croppedImage = null;
		Rect r = cropView.getScaledCropRect(sampleSize);
		int width = r.width();
		int height = r.height();

		int outWidth = width;
		int outHeight = height;
		if (maxX > 0 && maxY > 0 && (width > maxX || height > maxY)) {
			float ratio = (float) width / (float) height;
			if ((float) maxX / (float) maxY > ratio) {
				outHeight = maxY;
				outWidth = (int) ((float) maxY * ratio + .5f);
			} else {
				outWidth = maxX;
				outHeight = (int) ((float) maxX / ratio + .5f);
			}
		}

		final DoInvokeResult _invokeResult = new DoInvokeResult(model.getUniqueKey());
		try {
			croppedImage = decodeRegionCrop(r, outWidth, outHeight);
		} catch (IllegalArgumentException e) {
			DoServiceContainer.getLogEngine().writeError("do_ImageCropView1 crop", e);
			_invokeResult.setException(e);
		}

		final Bitmap bmp = croppedImage;
		if (croppedImage != null) {
			handler.post(new Runnable() {
				public void run() {
					saveImage(bmp, _scriptEngine, _callbackFuncName, _invokeResult);
				}
			});
		} else {
			_invokeResult.setResultText(null);
			_scriptEngine.callback(_callbackFuncName, _invokeResult);
		}
	}

	private int calculateBitmapSampleSize(Uri bitmapUri) throws IOException {
		InputStream is = null;
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		try {
			is = mContext.getContentResolver().openInputStream(bitmapUri);
			BitmapFactory.decodeStream(is, null, options); // Just get image size
		} finally {
			CropUtil.closeSilently(is);
		}

		int maxSize = getMaxImageSize();
		int sampleSize = 1;
		while (options.outHeight / sampleSize > maxSize || options.outWidth / sampleSize > maxSize) {
			sampleSize = sampleSize << 1;
		}
		return sampleSize;
	}

	private int getMaxImageSize() {
		int textureLimit = getMaxTextureSize();
		if (textureLimit == 0) {
			return SIZE_DEFAULT;
		} else {
			return Math.min(textureLimit, SIZE_LIMIT);
		}
	}

	private int getMaxTextureSize() {
		// The OpenGL texture size is the maximum size that can be drawn in an ImageView
		int[] maxSize = new int[1];
		GLES10.glGetIntegerv(GLES10.GL_MAX_TEXTURE_SIZE, maxSize, 0);
		return maxSize[0];
	}

	private void startCrop() {
		if (mPageView == null) {
			return;
		}
		this.setImageRotateBitmapResetBase(rotateBitmap, true);
		CropUtil.startBackgroundJob(mPageView, null, "Please wait…", new Runnable() {
			public void run() {
				final CountDownLatch latch = new CountDownLatch(1);
				handler.post(new Runnable() {
					public void run() {
						if (getScale() == 1F) {
							center();
						}
						latch.countDown();
					}
				});
				try {
					latch.await();
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				new Cropper().crop();
			}
		}, handler);
	}

	private class Cropper {
		private void makeDefault() {
			if (rotateBitmap == null) {
				return;
			}
			//清除四条线
			clearHighlightViews();

			HighlightView hv = new HighlightView(do_ImageCropView1_View.this);
			final int width = rotateBitmap.getWidth();
			final int height = rotateBitmap.getHeight();

			Rect imageRect = new Rect(0, 0, width, height);

			// Make the default size about 4/5 of the width or height
			int cropWidth = Math.min(width, height) * 4 / 5;
			int cropHeight = cropWidth;

			if (aspectX != 0 && aspectY != 0) {
				if (aspectX > aspectY) {
					cropHeight = cropWidth * aspectY / aspectX;
				} else {
					cropWidth = cropHeight * aspectX / aspectY;
				}
			}

			int x = (width - cropWidth) / 2;
			int y = (height - cropHeight) / 2;

			RectF cropRect = new RectF(x, y, x + cropWidth, y + cropHeight);
			hv.setup(do_ImageCropView1_View.this.getUnrotatedMatrix(), imageRect, cropRect, aspectX != 0 && aspectY != 0);
			do_ImageCropView1_View.this.add(hv);
		}

		public void crop() {
			handler.post(new Runnable() {
				public void run() {
					makeDefault();
					do_ImageCropView1_View.this.invalidate();
					if (do_ImageCropView1_View.this.highlightViews.size() == 1) {
						cropView = do_ImageCropView1_View.this.highlightViews.get(0);
						cropView.setFocus(true);
					}
				}
			});
		}
	}

	private void saveImage(final Bitmap croppedImage, final DoIScriptEngine _scriptEngine, final String _callbackFuncName, final DoInvokeResult _invokeResult) {
		if (mPageView == null || croppedImage == null) {
			return;
		}
		CropUtil.startBackgroundJob(mPageView, null, "Saving picture…", new Runnable() {
			public void run() {

				ByteArrayOutputStream _photoData = new ByteArrayOutputStream();
				croppedImage.compress(saveAsPng ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 90, _photoData);

				String _fileName = DoTextHelper.getTimestampStr() + ".png.do";
				String _fileFullName = _scriptEngine.getCurrentApp().getDataFS().getRootPath() + "/temp/do_ImageCropView1/" + _fileName;
				String _url = "data://temp/do_ImageCropView1/" + _fileName;
				try {
					DoIOHelper.writeAllBytes(_fileFullName, _photoData.toByteArray());
				} catch (IOException e) {
					_url = null;
					_invokeResult.setException(e);
					DoServiceContainer.getLogEngine().writeError("do_ImageCropView1 crop sava fial", e);
				} finally {
					_invokeResult.setResultText(_url);
					_scriptEngine.callback(_callbackFuncName, _invokeResult);
				}

			}
		}, handler);
	}

	private Bitmap decodeRegionCrop(Rect rect, int outWidth, int outHeight) {
		InputStream is = null;
		Bitmap croppedImage = null;
		try {
			is = mContext.getContentResolver().openInputStream(sourceUri);
			BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(is, false);
			final int width = decoder.getWidth();
			final int height = decoder.getHeight();

			if (exifRotation != 0) {
				// Adjust crop area to account for image rotation
				Matrix matrix = new Matrix();
				matrix.setRotate(-exifRotation);

				RectF adjusted = new RectF();
				matrix.mapRect(adjusted, new RectF(rect));

				// Adjust to account for origin at 0,0
				adjusted.offset(adjusted.left < 0 ? width : 0, adjusted.top < 0 ? height : 0);
				rect = new Rect((int) adjusted.left, (int) adjusted.top, (int) adjusted.right, (int) adjusted.bottom);
			}

			try {
				croppedImage = decoder.decodeRegion(rect, new BitmapFactory.Options());
				if (croppedImage != null && (rect.width() > outWidth || rect.height() > outHeight)) {
					Matrix matrix = new Matrix();
					matrix.postScale((float) outWidth / rect.width(), (float) outHeight / rect.height());
					croppedImage = Bitmap.createBitmap(croppedImage, 0, 0, croppedImage.getWidth(), croppedImage.getHeight(), matrix, true);
				}
			} catch (IllegalArgumentException e) {
				// Rethrow with some extra information
				throw new IllegalArgumentException("Rectangle " + rect + " is outside of the image (" + width + "," + height + "," + exifRotation + ")", e);
			}

		} catch (IOException e) {
			Log.e("do_ImageCropView1", "Error cropping image: " + e.getMessage(), e);
		} catch (OutOfMemoryError e) {
			Log.e("do_ImageCropView1", "OOM cropping image: " + e.getMessage(), e);
		} finally {
			CropUtil.closeSilently(is);
		}
		return croppedImage;
	}
}