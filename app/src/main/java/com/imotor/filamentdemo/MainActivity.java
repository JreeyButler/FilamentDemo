package com.imotor.filamentdemo;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * @author Yan.Liangliang
 * @date 2025/6/10 下午2:39
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private DoorController mDoorController;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onCreate: ");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
    }

    private void initView() {
        FilamentView2 view = findViewById(R.id.filament_view);
        mDoorController = view.getDoorController();

        Button openLeftDoor = findViewById(R.id.open_left_door);
        Button closeLeftDoor = findViewById(R.id.close_left_door);
        Button openRightDoor = findViewById(R.id.open_right_door);
        Button closeRightDoor = findViewById(R.id.close_right_door);

        openLeftDoor.setOnClickListener(doorListener);
        closeLeftDoor.setOnClickListener(doorListener);
        openRightDoor.setOnClickListener(doorListener);
        closeRightDoor.setOnClickListener(doorListener);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy: ");
        super.onDestroy();
    }

    private final View.OnClickListener doorListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final int id = v.getId();
            if (id == R.id.open_left_door) {
                mDoorController.openDoor(DoorController.FRONT_LEFT_DOOR);
            } else if (id == R.id.close_left_door) {
                mDoorController.closeDoor(DoorController.FRONT_LEFT_DOOR);
            } else if (id == R.id.open_right_door) {
                mDoorController.openDoor(DoorController.FRONT_RIGHT_DOOR);
            } else if (id == R.id.close_right_door) {
                mDoorController.closeDoor(DoorController.FRONT_RIGHT_DOOR);
            }
        }
    };
}
