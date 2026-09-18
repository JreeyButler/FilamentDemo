package com.imotor.filamentdemo;

import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.Locale;

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
    /**
     * 车衣索引：0 = 原漆，1..n = assets/skins/ 下自动发现的贴图
     */
    private int mSkinIndex = 0;
    private java.util.List<String> mSkinPaths = new java.util.ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onCreate: ");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyImmersive();
        initView();
    }

    /**
     * 全屏沉浸：隐藏状态栏与导航栏，内容延伸到屏幕边缘；
     * 下滑临时呼出系统栏（transient），几秒后自动收起
     */
    private void applyImmersive() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 从其他应用/对话框返回后重新进入沉浸
        if (hasFocus) {
            applyImmersive();
        }
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

        ToggleButton toggleRearLight = findViewById(R.id.toggle_rear_light);
        toggleRearLight.setOnCheckedChangeListener((b, isChecked) ->
                mFilamentView.setRearLightEnabled(isChecked));

        ToggleButton toggleTurnSignal = findViewById(R.id.toggle_turn_signal);
        toggleTurnSignal.setOnCheckedChangeListener((b, isChecked) ->
                mFilamentView.setTurnSignalEnabled(isChecked));

        // ── 刹车：按住深红高亮，松开恢复 ───────────────────────────────────────
        Button btnBrake = findViewById(R.id.btn_brake);
        btnBrake.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mFilamentView.setBrakeEnabled(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mFilamentView.setBrakeEnabled(false);
                    break;
                default:
                    break;
            }
            return false;
        });

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

        findViewById(R.id.btn_dir_x_inc).setOnClickListener(v -> adjustDir(0.1f, 0, 0));
        findViewById(R.id.btn_dir_x_dec).setOnClickListener(v -> adjustDir(-0.1f, 0, 0));
        findViewById(R.id.btn_dir_y_inc).setOnClickListener(v -> adjustDir(0, 0.1f, 0));
        findViewById(R.id.btn_dir_y_dec).setOnClickListener(v -> adjustDir(0, -0.1f, 0));
        findViewById(R.id.btn_dir_z_inc).setOnClickListener(v -> adjustDir(0, 0, 0.1f));
        findViewById(R.id.btn_dir_z_dec).setOnClickListener(v -> adjustDir(0, 0, -0.1f));
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

        findViewById(R.id.btn_pos_x_inc).setOnClickListener(v -> adjustPos(0.1f, 0, 0));
        findViewById(R.id.btn_pos_x_dec).setOnClickListener(v -> adjustPos(-0.1f, 0, 0));
        findViewById(R.id.btn_pos_y_inc).setOnClickListener(v -> adjustPos(0, 0.1f, 0));
        findViewById(R.id.btn_pos_y_dec).setOnClickListener(v -> adjustPos(0, -0.1f, 0));
        findViewById(R.id.btn_pos_z_inc).setOnClickListener(v -> adjustPos(0, 0, 0.1f));
        findViewById(R.id.btn_pos_z_dec).setOnClickListener(v -> adjustPos(0, 0, -0.1f));
        findViewById(R.id.btn_pos_reset).setOnClickListener(v -> {
            mFilamentView.resetLightPosition();
            updatePosLabel();
        });

        // ── 车衣切换：原漆 → 自动发现的 skins/* → 原漆 ──────────────────────
        mSkinPaths = CarSkinSystem.discoverSkins(this);
        Log.d(TAG, "initView: 发现车衣 " + mSkinPaths.size() + " 套: " + mSkinPaths);
        Button btnSkin = findViewById(R.id.btn_skin);
        if (mSkinPaths.isEmpty()) {
            btnSkin.setEnabled(false);
        }
        btnSkin.setOnClickListener(v -> {
            mSkinIndex = (mSkinIndex + 1) % (mSkinPaths.size() + 1);
            if (mSkinIndex == 0) {
                mFilamentView.resetCarSkin();
                btnSkin.setText(R.string.skin_switch);
            } else {
                String path = mSkinPaths.get(mSkinIndex - 1);
                mFilamentView.applyCarSkin(path);
                btnSkin.setText(CarSkinSystem.skinDisplayName(path));
            }
        });

        // ── 行驶控制：油门 / 刹车（均为按住式）──────────────────────────────
        TextView tvSpeed = findViewById(R.id.tv_speed);
        mFilamentView.setSpeedListener(kmh ->
                tvSpeed.setText(String.format(Locale.US, "%d km/h", kmh)));

        Button btnThrottle = findViewById(R.id.btn_throttle);
        btnThrottle.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mFilamentView.setThrottleEnabled(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mFilamentView.setThrottleEnabled(false);
                    break;
                default:
                    break;
            }
            return false;
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
