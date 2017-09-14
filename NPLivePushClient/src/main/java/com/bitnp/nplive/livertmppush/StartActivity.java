package com.bitnp.nplive.livertmppush;

import android.os.Bundle;
import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.content.Intent;
import android.widget.RadioButton;
import android.widget.RadioGroup;

public class StartActivity extends Activity {
    public static final String RTMPURL_MESSAGE = "com.bitnp.nplive.livertmppush.rtmpurl";
    public static final String CAMERA_CHOOSED_MESSAGE = "com.bitnp.nplive.livertmppush.CameraChoosed";

    private Button _startRtmpPushButton = null;
    private EditText _rtmpUrlEditText = null;
    private EditText _rtmpStreamText = null;
    private RadioGroup _CameraChooseGroup;

    private View.OnClickListener _startRtmpPushOnClickedEvent = new View.OnClickListener() {
        @Override
        public void onClick(View arg0) {
            Intent i = new Intent(StartActivity.this, MainActivity.class);
            String rtmpUrl = _rtmpUrlEditText.getText().toString() + "/" + _rtmpStreamText.getText().toString();
            String CameraChoosed = null;

            _CameraChooseGroup = (RadioGroup) findViewById(R.id.CameraChooseGroup_id);
            int selectedCamera = _CameraChooseGroup.getCheckedRadioButtonId();
            if(selectedCamera == R.id.radioButtonFrontCamera)
                CameraChoosed = "FRONT";
            else if(selectedCamera == R.id.radioButtonBackCamera)
                CameraChoosed = "BACK";


            i.putExtra(StartActivity.RTMPURL_MESSAGE, rtmpUrl);
            i.putExtra(StartActivity.CAMERA_CHOOSED_MESSAGE, CameraChoosed);
            StartActivity.this.startActivity(i);
        }
    };

    private void InitUI(){
        _rtmpUrlEditText = (EditText)findViewById(R.id.rtmpUrleditText);
        _rtmpStreamText = (EditText)findViewById(R.id.rtmpStreamText);
        _startRtmpPushButton = (Button)findViewById(R.id.startRtmpButton);

        _rtmpUrlEditText.setText("rtmp://send.xxxxxx.com:1935/send");
        _rtmpStreamText.setText("xxxlivestream");

        _startRtmpPushButton.setOnClickListener(_startRtmpPushOnClickedEvent);
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start);

        InitUI();
    }

}
