package lyi.linyi.posemon;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class Test extends Activity {
    private Handler handler;
    private ImageView ivStatus;
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //创建handler对象
        handler = new Handler(getMainLooper()){
            @Override
            //重写接受消息的回掉方法
            public void handleMessage(@NonNull Message msg) {
                super.handleMessage(msg);
                //根据不同消息类型进行不同显示
                switch (msg.what){
                    case 100:
                        ivStatus.setImageResource(R.drawable.squat_standard_confirm);
                        break;
                    case 200:
                        ivStatus.setImageResource(R.drawable.squat_standard_suspect);
                        break;

                }
            }
        };

        //在子线城中发送消息的方法
        handler.sendEmptyMessage(100);//100就是自定义的消息类型
    }
}
