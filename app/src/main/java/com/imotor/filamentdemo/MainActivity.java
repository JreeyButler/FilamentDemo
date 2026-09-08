package com.imotor.filamentdemo;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * @author Yan.Liangliang
 * @date 2025/6/10 下午2:39
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private DoorController mDoorController;
    private FilamentView2 mFilamentView;
    private TextView mTvLightDir;
    private TextView mTvLightPos;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onCreate: ");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
    }

    private void initView() {
        mFilamentView = findViewById(R.id.filament_view);
        mDoorController = mFilamentView.getDoorController();

        // ── 车门 ──────────────────────────────────────────────────────────────
        ToggleButton toggleLeftDoor = findViewById(R.id.toggle_left_door);
        ToggleButton toggleRightDoor = findViewById(R.id.toggle_right_door);
        toggleLeftDoor.setOnCheckedChangeListener((b, isChecked) -> {
            if (isChecked) mDoorController.openDoor(DoorController.FRONT_LEFT_DOOR);
            else mDoorController.closeDoor(DoorController.FRONT_LEFT_DOOR);
        });
        toggleRightDoor.setOnCheckedChangeListener((b, isChecked) -> {
            if (isChecked) mDoorController.openDoor(DoorController.FRONT_RIGHT_DOOR);
            else mDoorController.closeDoor(DoorController.FRONT_RIGHT_DOOR);
        });

        // ── 车灯开关 ──────────────────────────────────────────────────────────
        ToggleButton toggleFrontLight = findViewById(R.id.toggle_front_light);
        toggleFrontLight.setOnCheckedChangeListener((b, isChecked) ->
                mFilamentView.setFrontLightEnabled(isChecked));

        // ── 光源方向调节面板 ───────────────────────────────────────────────────
        mTvLightDir = findViewById(R.id.tv_light_dir);
        View lightDirPanel = findViewById(R.id.light_dir_panel);
        View lightPosPanel = findViewById(R.id.light_pos_panel);

        findViewById(R.id.btn_toggle_light_dir_panel).setOnClickListener(v -> {
            boolean show = lightDirPanel.getVisibility() != View.VISIBLE;
            lightDirPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            lightPosPanel.setVisibility(View.GONE); // 互斥
            if (show) updateDirLabel();
        });

        findViewById(R.id.btn_dir_x_inc).setOnClickListener(v -> adjustDir( 0.1f, 0,     0));
        findViewById(R.id.btn_dir_x_dec).setOnClickListener(v -> adjustDir(-0.1f, 0,     0));
        findViewById(R.id.btn_dir_y_inc).setOnClickListener(v -> adjustDir(0,  0.1f,     0));
        findViewById(R.id.btn_dir_y_dec).setOnClickListener(v -> adjustDir(0, -0.1f,     0));
        findViewById(R.id.btn_dir_z_inc).setOnClickListener(v -> adjustDir(0,     0,  0.1f));
        findViewById(R.id.btn_dir_z_dec).setOnClickListener(v -> adjustDir(0,     0, -0.1f));
        findViewById(R.id.btn_dir_reset).setOnClickListener(v -> {
            mFilamentView.resetLightDirection();
            updateDirLabel();
        });

        // ── 光源位置调节面板 ───────────────────────────────────────────────────
        mTvLightPos = findViewById(R.id.tv_light_pos);

        findViewById(R.id.btn_toggle_light_pos_panel).setOnClickListener(v -> {
            boolean show = lightPosPanel.getVisibility() != View.VISIBLE;
            lightPosPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            lightDirPanel.setVisibility(View.GONE); // 互斥
            if (show) updatePosLabel();
        });

        findViewById(R.id.btn_pos_x_inc).setOnClickListener(v -> adjustPos( 0.1f, 0,     0));
        findViewById(R.id.btn_pos_x_dec).setOnClickListener(v -> adjustPos(-0.1f, 0,     0));
        findViewById(R.id.btn_pos_y_inc).setOnClickListener(v -> adjustPos(0,  0.1f,     0));
        findViewById(R.id.btn_pos_y_dec).setOnClickListener(v -> adjustPos(0, -0.1f,     0));
        findViewById(R.id.btn_pos_z_inc).setOnClickListener(v -> adjustPos(0,     0,  0.1f));
        findViewById(R.id.btn_pos_z_dec).setOnClickListener(v -> adjustPos(0,     0, -0.1f));
        findViewById(R.id.btn_pos_reset).setOnClickListener(v -> {
            mFilamentView.resetLightPosition();
            updatePosLabel();
        });
    }

    // ── 方向面板辅助 ──────────────────────────────────────────────────────────

    private void adjustDir(float dx, float dy, float dz) {
        mFilamentView.adjustLightDirection(dx, dy, dz);
        updateDirLabel();
    }

    private void updateDirLabel() {
        float[] d = mFilamentView.getLightDirection();
        mTvLightDir.setText(String.format("dir: (%.1f, %.1f, %.1f)", d[0], d[1], d[2]));
    }

    // ── 位置面板辅助 ──────────────────────────────────────────────────────────

    private void adjustPos(float dx, float dy, float dz) {
        mFilamentView.adjustLightPosition(dx, dy, dz);
        updatePosLabel();
    }

    private void updatePosLabel() {
        float[] p = mFilamentView.getLightPosition();
        mTvLightPos.setText(String.format("pos: (%.1f, %.1f, %.1f)", p[0], p[1], p[2]));
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy: ");
        super.onDestroy();
    }
}
