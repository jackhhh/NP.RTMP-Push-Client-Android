package com.bitnp.nplive.livertmppush;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.alex.livertmppushsdk.FdkAacEncode;
import com.alex.livertmppushsdk.RtmpSessionManager;
import com.alex.livertmppushsdk.SWVideoEncoder;

import android.app.Activity;
import android.content.Context;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.hardware.SensorEventListener;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.content.Intent;
import android.view.KeyEvent;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.widget.TextView;

public class MainActivity extends Activity {
	private final static int ID_RTMP_PUSH_START = 100;
	private final static int ID_RTMP_PUSH_EXIT = 101;
	private int WIDTH_DEF = 480;
	private int HEIGHT_DEF = 640;
	private final int FRAMERATE_DEF = 20;
	private final int BITRATE_DEF = 800 * 1000;

	private final int SAMPLE_RATE_DEF = 22050;
	private final int CHANNEL_NUMBER_DEF = 2;

	private final String LOG_TAG = "MainActivity";
	private final boolean DEBUG_ENABLE = false;

	private String _rtmpUrl = "rtmp://send.xxxxxx.com:1935/livestream";
	private String _CameraChoosed = null;

	PowerManager.WakeLock _wakeLock;
	private DataOutputStream _outputStream = null;

	private AudioRecord _AudioRecorder = null;
	private byte[] _RecorderBuffer = null;
	private FdkAacEncode _fdkaacEnc = null;
	private int _fdkaacHandle = 0;

	public SurfaceView _mSurfaceView = null;
	private Camera _mCamera = null;
	private boolean _bIsFront = true;
	private SWVideoEncoder _swEncH264 = null;
	private int _iDegrees = 0;

	private int _iRecorderBufferSize = 0;

	private TextView _PushingText;

	private boolean _bStartFlag = false;

	private int _iCameraCodecType = android.graphics.ImageFormat.NV21;

	private byte[] _yuvNV21 = new byte[WIDTH_DEF * HEIGHT_DEF * 3 / 2];
	private byte[] _yuvEdit = new byte[WIDTH_DEF * HEIGHT_DEF * 3 / 2];

	private RtmpSessionManager _rtmpSessionMgr = null;

	private Queue<byte[]> _YUVQueue = new LinkedList<byte[]>();
	private Lock _yuvQueueLock = new ReentrantLock();

	private Thread _h264EncoderThread = null;

	private Runnable _h264Runnable = new Runnable() {
		@Override
		public void run() {
			while (!_h264EncoderThread.interrupted() && _bStartFlag) {
				int iSize = _YUVQueue.size();
				if (iSize > 0) {
					_yuvQueueLock.lock();
					byte[] yuvData = _YUVQueue.poll();
					if (iSize > 9) {
						Log.i(LOG_TAG, "###YUV Queue len=" + _YUVQueue.size() + ", YUV length=" + yuvData.length);
					}

					_yuvQueueLock.unlock();
					if (yuvData == null) {
						continue;
					}

					if (_bIsFront) {
						_yuvEdit = _swEncH264.YUV420pRotate270(yuvData, HEIGHT_DEF, WIDTH_DEF);
					} else {
						_yuvEdit = _swEncH264.YUV420pRotate90(yuvData, HEIGHT_DEF, WIDTH_DEF);
					}
					byte[] h264Data = _swEncH264.EncoderH264(_yuvEdit);
					if (h264Data != null) {
						_rtmpSessionMgr.InsertVideoData(h264Data);
						if (DEBUG_ENABLE) {
							try {
								_outputStream.write(h264Data);
								int iH264Len = h264Data.length;
								//Log.i(LOG_TAG, "Encode H264 len="+iH264Len);
							} catch (IOException e1) {
								e1.printStackTrace();
							}
						}
					}
				}
				try {
					Thread.sleep(1);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			_YUVQueue.clear();
		}
	};


	private Runnable _aacEncoderRunnable = new Runnable() {
		@Override
		public void run() {
			DataOutputStream outputStream = null;
			if (DEBUG_ENABLE) {
				File saveDir = Environment.getExternalStorageDirectory();
				String strFilename = saveDir + "/aaa.aac";
				try {
					outputStream = new DataOutputStream(new FileOutputStream(strFilename));
				} catch (FileNotFoundException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
			}

			long lSleepTime = SAMPLE_RATE_DEF * 16 * 2 / _RecorderBuffer.length;

			while (!_AacEncoderThread.interrupted() && _bStartFlag) {
				int iPCMLen = _AudioRecorder.read(_RecorderBuffer, 0, _RecorderBuffer.length); // Fill buffer
				if ((iPCMLen != _AudioRecorder.ERROR_BAD_VALUE) && (iPCMLen != 0)) {
					if (_fdkaacHandle != 0) {
						byte[] aacBuffer = _fdkaacEnc.FdkAacEncode(_fdkaacHandle, _RecorderBuffer);
						if (aacBuffer != null) {
							long lLen = aacBuffer.length;

							_rtmpSessionMgr.InsertAudioData(aacBuffer);
							//Log.i(LOG_TAG, "fdk aac length="+lLen+" from pcm="+iPCMLen);
							if (DEBUG_ENABLE) {
								try {
									outputStream.write(aacBuffer);
								} catch (IOException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
							}
						}
					}
				} else {
					Log.i(LOG_TAG, "######fail to get PCM data");
				}
				try {
					Thread.sleep(lSleepTime / 10);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
            Log.i(LOG_TAG, "AAC Encoder Thread ended ......");
		}
	};
	private Thread _AacEncoderThread = null;

	private int getDispalyRotation() {
		int i = getWindowManager().getDefaultDisplay().getRotation();
		switch (i) {
			case Surface.ROTATION_0:
				return 0;
			case Surface.ROTATION_90:
				return 90;
			case Surface.ROTATION_180:
				return 180;
			case Surface.ROTATION_270:
				return 270;
		}
		return 0;
	}

	private int getDisplayOritation(int degrees, int cameraId) {
		Camera.CameraInfo info = new Camera.CameraInfo();
		Camera.getCameraInfo(cameraId, info);
		int result = 0;
		if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
			result = (info.orientation + degrees) % 360;
			result = (360 - result) % 360;
		} else {
			result = (info.orientation - degrees + 360) % 360;
		}
		return result;
	}

	private Camera.PreviewCallback _previewCallback = new Camera.PreviewCallback() {

		@Override
		public void onPreviewFrame(byte[] YUV, Camera currentCamera) {
			if (!_bStartFlag) {
				return;
			}

			boolean bBackCameraFlag = true;

			byte[] yuv420 = null;

			if (_iCameraCodecType == android.graphics.ImageFormat.YV12) {
				yuv420 = new byte[YUV.length];
				_swEncH264.swapYV12toI420_Ex(YUV, yuv420, HEIGHT_DEF, WIDTH_DEF);
			} else if (_iCameraCodecType == android.graphics.ImageFormat.NV21) {
				yuv420 = _swEncH264.swapNV21toI420(YUV, HEIGHT_DEF, WIDTH_DEF);
			}

			if (yuv420 == null) {
				return;
			}
			if (!_bStartFlag) {
				return;
			}
			_yuvQueueLock.lock();
			if (_YUVQueue.size() > 1) {
				_YUVQueue.clear();
			}
			_YUVQueue.offer(yuv420);
			_yuvQueueLock.unlock();
		}
	};

	public void InitCamera() {
		Camera.Parameters p = _mCamera.getParameters();

		_mCamera.cancelAutoFocus();//只有加上了这一句，才会自动对焦。

		List<Integer> PreviewFormats = p.getSupportedPreviewFormats();

		Log.i(LOG_TAG, "Listing all supported preview formats");
		Integer iNV21Flag = 0;
		Integer iYV12Flag = 0;
		for (Integer yuvFormat : PreviewFormats) {
			Log.i(LOG_TAG, "preview formats:" + yuvFormat);
			if (yuvFormat == android.graphics.ImageFormat.YV12) {
				iYV12Flag = android.graphics.ImageFormat.YV12;
			}
			if (yuvFormat == android.graphics.ImageFormat.NV21) {
				iNV21Flag = android.graphics.ImageFormat.NV21;
			}
		}

		if (iNV21Flag != 0) {
			_iCameraCodecType = iNV21Flag;
		} else if (iYV12Flag != 0) {
			_iCameraCodecType = iYV12Flag;
		}

		p.setPreviewSize(HEIGHT_DEF, WIDTH_DEF);
		Log.d("Size","HEIGHT_DEF:" + HEIGHT_DEF);
		Log.d("Size","WIDTH_DEF:" + WIDTH_DEF);
		p.setPreviewFormat(_iCameraCodecType);
		p.setPreviewFrameRate(FRAMERATE_DEF);

		_mCamera.setDisplayOrientation(_iDegrees);
		p.setRotation(_iDegrees);
		_mCamera.setPreviewCallback(_previewCallback);

		Log.d("camera", "" + p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO));

		if(_CameraChoosed.equals("BACK"))
			p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE); //前置摄像头无效

		Log.d("camera", p.getFocusMode());
		_mCamera.setParameters(p);
		try {
			_mCamera.setPreviewDisplay(_mSurfaceView.getHolder());
		} catch (Exception e) {
			return;
		}

		_mCamera.startPreview();

	}

	private final class SurceCallBack implements SurfaceHolder.Callback {
		@Override
		public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

			_mCamera.autoFocus(new Camera.AutoFocusCallback() {
				@Override
				public void onAutoFocus(boolean success, Camera camera) {
					if (success) {
						InitCamera();
						camera.cancelAutoFocus();//只有加上了这一句，才会自动对焦。
						Log.d("camera", " " + success);
					} else {
						Log.d("camera", "failed");
					}
				}
			});
		}

		@Override
		public void surfaceCreated(SurfaceHolder holder) {
			_iDegrees = getDisplayOritation(getDispalyRotation(), 0);

			InitCamera();
		}

		@Override
		public void surfaceDestroyed(SurfaceHolder holder) {

		}
	}

	private void Start() {
		if (DEBUG_ENABLE) {
			File saveDir = Environment.getExternalStorageDirectory();
			String strFilename = saveDir + "/aaa.h264";
			try {
				_outputStream = new DataOutputStream(new FileOutputStream(strFilename));
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
		}

		_rtmpSessionMgr = new RtmpSessionManager();
		_rtmpSessionMgr.Start(_rtmpUrl);

		int iFormat = _iCameraCodecType;
		_swEncH264 = new SWVideoEncoder(WIDTH_DEF, HEIGHT_DEF, FRAMERATE_DEF, BITRATE_DEF);
		_swEncH264.start(iFormat);

		_bStartFlag = true;

		_h264EncoderThread = new Thread(_h264Runnable);
		_h264EncoderThread.setPriority(Thread.MAX_PRIORITY);
		_h264EncoderThread.start();

		_AudioRecorder.startRecording();
		_AacEncoderThread = new Thread(_aacEncoderRunnable);
		_AacEncoderThread.setPriority(Thread.MAX_PRIORITY);
		_AacEncoderThread.start();
	}

	private void Stop() {
		_bStartFlag = false;

		_AacEncoderThread.interrupt();
		_h264EncoderThread.interrupt();

		_AudioRecorder.stop();
		_swEncH264.stop();

		_rtmpSessionMgr.Stop();

		_yuvQueueLock.lock();
		_YUVQueue.clear();
        _yuvQueueLock.unlock();

		if (DEBUG_ENABLE) {
			if (_outputStream != null) {
				try {
					_outputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private void InitAudioRecord() {
		_iRecorderBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_DEF,
				AudioFormat.CHANNEL_CONFIGURATION_STEREO,
				AudioFormat.ENCODING_PCM_16BIT);
		_AudioRecorder = new AudioRecord(AudioSource.MIC,
				SAMPLE_RATE_DEF, AudioFormat.CHANNEL_CONFIGURATION_STEREO,
				AudioFormat.ENCODING_PCM_16BIT, _iRecorderBufferSize);
		_RecorderBuffer = new byte[_iRecorderBufferSize];

		_fdkaacEnc = new FdkAacEncode();
		_fdkaacHandle = _fdkaacEnc.FdkAacInit(SAMPLE_RATE_DEF, CHANNEL_NUMBER_DEF);
	}

	public Handler mHandler = new Handler() {
		public void handleMessage(android.os.Message msg) {
			Bundle b = msg.getData();
			int ret;
			switch (msg.what) {
				case ID_RTMP_PUSH_START: {
					Start();
					break;
				}
			}
		}
	};

	private void RtmpStartMessage() {
		Message msg = new Message();
		msg.what = ID_RTMP_PUSH_START;
		Bundle b = new Bundle();
		b.putInt("ret", 0);
		msg.setData(b);
		mHandler.sendMessage(msg);
	}

	private void InitAll() {
		//根据选择的radio open不同的相机
		if(_CameraChoosed.equals("FRONT"))
			_mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
		else if(_CameraChoosed.equals("BACK"))
			_mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);

		WindowManager wm = this.getWindowManager();

		int width = wm.getDefaultDisplay().getWidth();
		int height = wm.getDefaultDisplay().getHeight();

		Camera.Parameters p = _mCamera.getParameters();
		float mindiff = 100f;
		float x_d_y =  width / height;
		Size best = null;
		List<Size> PreviewSizeList = p.getSupportedPreviewSizes();
		for(Size s : PreviewSizeList){
			float tmp = Math.abs(((float)s.height / (float)s.width) - x_d_y);
			if(tmp < mindiff){
				mindiff = tmp;
				best = s;
			}
		}
		HEIGHT_DEF = best.width;
		WIDTH_DEF = best.height;


		int iNewHeight = (int) (width * HEIGHT_DEF / WIDTH_DEF);
		Log.d("Size", "height:" + height + " width:" + width  + " WIDTH_DEF:" + WIDTH_DEF + " HEIGHT_DEF:" + HEIGHT_DEF);

		RelativeLayout rCameraLayout = (RelativeLayout) findViewById(R.id.cameraRelative);
		RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,
				RelativeLayout.LayoutParams.MATCH_PARENT);
		int iPos = height - iNewHeight;
		layoutParams.setMargins(0, 0, 0, iPos);

		_mSurfaceView = (SurfaceView) this.findViewById(R.id.surfaceViewEx);
		_mSurfaceView.getHolder().setFixedSize(HEIGHT_DEF, WIDTH_DEF);
		_mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		_mSurfaceView.getHolder().setKeepScreenOn(true);
		_mSurfaceView.getHolder().addCallback(new SurceCallBack());
		_mSurfaceView.setLayoutParams(layoutParams);

		InitAudioRecord();

		_PushingText = (TextView) findViewById(R.id.PushingText);
		_PushingText.setText("正在推流 当前分辨率:" + HEIGHT_DEF + "×" + WIDTH_DEF);

		RtmpStartMessage();//开始推流
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);
		this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

		Intent intent = getIntent();
		_rtmpUrl = intent.getStringExtra(StartActivity.RTMPURL_MESSAGE);
		_CameraChoosed = intent.getStringExtra(StartActivity.CAMERA_CHOOSED_MESSAGE);

		InitAll();

		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		_wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "My Tag");

	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		//getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	protected void onDestroy() {
		super.onDestroy();
        Log.i(LOG_TAG, "MainActivity onDestroy...");
	}

	protected void onResume() {
		super.onResume();
		_wakeLock.acquire();
	}

	protected void onPause() {
		super.onPause();
		_wakeLock.release();
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			// 创建退出对话框
			AlertDialog isExit = new AlertDialog.Builder(this).create();
			// 设置对话框标题
			isExit.setTitle("NPLive Push Client");
			// 设置对话框消息
			isExit.setMessage("是否退出推流？");
			// 添加选择按钮并注册监听
			isExit.setButton("确定", listener);
			isExit.setButton2("取消", listener);
			// 显示对话框
			isExit.show();

		}

		return false;

	}

	/**
	 * 监听对话框里面的button点击事件
	 */
	DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
		public void onClick(DialogInterface dialog, int which) {
			switch (which) {
				case AlertDialog.BUTTON_POSITIVE: {// "确认"按钮退出程序
					if (_mCamera != null) {
						try {
							_mCamera.setPreviewCallback(null);
							_mCamera.setPreviewDisplay(null);
						} catch (IOException e) {
							e.printStackTrace();
						}
						_mCamera.stopPreview();
						_mCamera.release();
						_mCamera = null;
					}
					if (_bStartFlag) {
						Stop();
					}

					MainActivity.this.finish();

					break;
				}
				case AlertDialog.BUTTON_NEGATIVE:// "取消"第二个按钮取消对话框
					break;
				default:
					break;
			}
		}
	};
}
