package com.imotor.filamentdemo;

import android.opengl.Matrix;
import android.util.Log;

import com.google.android.filament.Engine;
import com.google.android.filament.TransformManager;

/**
 * 行驶系统（原地展厅式：车身不动，轮子按速度自转）。
 * 负责：油门/刹车状态 → 速度（指数平滑：油门加速、松油门缓降、刹车快降，刹车优先）、
 * 轮子实体收集与逐帧自转。
 *
 * @author Yan.Liangliang
 * @date 2025/9/18
 */
public final class DriveSystem {
    private static final String TAG = DriveSystem.class.getSimpleName();

    /**
     * 轮半径（米）。实测轮心高度 y≈0.329，即轮半径。
     */
    private static final float WHEEL_RADIUS = 0.33f;
    /**
     * 最大速度 m/s：200 km/h ≈ 55.6 m/s
     */
    public static final float MAX_SPEED = 200f / 3.6f;
    /**
     * 轮子转向符号：真机校准结果为 -1（否则看上去是开倒车）。
     */
    private static final float SPIN_SIGN = -1f;
    /**
     * 油门加速平滑系数（每秒），越大提速越快。
     */
    private static final float ACCEL_K = 0.25f;
    /**
     * 刹车减速平滑系数（每秒），明显大于松油门，刹车更"急"。
     */
    private static final float BRAKE_K = 2.5f;
    /**
     * 松开油门后的滑行减速平滑系数（每秒），较小 → 减速慢。
     */
    private static final float COAST_K = 0.15f;
    /**
     * 单帧 dt 上限（秒），防止切后台回来后角度跳变。
     */
    public static final float MAX_FRAME_DT = 0.1f;

    /**
     * 轮子实体（node_brakes1/2/003/004，各含 rim+tyre 子树，转它 = 整轮转）
     */
    private final int[] mWheelEntities = new int[4];
    /**
     * 轮子基准局部变换（纯平移，旋转前基准）
     */
    private final float[][] mWheelBaseTransforms = new float[4][16];
    private int mWheelCount = 0;
    /**
     * 油门是否按住（true=加速到最高速）
     */
    private volatile boolean mThrottleHeld = false;
    /**
     * 刹车是否按住（true=快速减速到 0），优先级高于油门
     */
    private volatile boolean mBrakeHeld = false;
    /**
     * 当前速度（m/s），每帧向目标速度指数平滑
     */
    private float mCurrentSpeed = 0f;
    /**
     * 轮子累计转角（度）
     */
    private float mTotalWheelAngleDeg = 0f;
    private long mLastFrameTimeNs = -1;
    /**
     * 上一帧是否实际应用了轮转（用于速度为 0 时跳过无效 setTransform）
     */
    private boolean mLastAngleApplied = false;

    private final Engine mEngine;

    public DriveSystem(Engine engine) {
        mEngine = engine;
    }

    /**
     * 按名称收集 4 个轮子节点，并缓存其基准局部变换。
     * 车模轮子节点：node_brakes1 / node_brakes2 / node_brakes003 / node_brakes004，
     * 各自包含 rim + tyre 子树；局部变换为纯平移（旋转=单位阵），
     * 因此轮轴即节点局部 X 轴（左右轮连线方向）。
     */
    public void collectWheels(com.google.android.filament.gltfio.FilamentAsset asset) {
        if (asset == null) {
            Log.w(TAG, "collectWheels: 模型资源为 null，行驶功能不可用");
            return;
        }
        TransformManager tm = mEngine.getTransformManager();
        for (int entity : asset.getEntities()) {
            String name = asset.getName(entity);
            if (name == null || mWheelCount >= mWheelEntities.length) {
                continue;
            }
            if (name.contains("brakes")) {
                mWheelEntities[mWheelCount] = entity;
                tm.getTransform(tm.getInstance(entity), mWheelBaseTransforms[mWheelCount]);
                mWheelCount++;
            }
        }
        if (mWheelCount == 0) {
            Log.w(TAG, "collectWheels: 未找到轮子节点（brakes），行驶功能不可用");
        } else {
            Log.d(TAG, "collectWheels: 找到 " + mWheelCount + " 个轮子");
        }
    }

    /**
     * 油门：按住加速。松开会缓慢滑行减速。
     */
    public void setThrottle(boolean held) {
        mThrottleHeld = held;
    }

    /**
     * 刹车：按住快速减速（优先于油门）。
     */
    public void setBrake(boolean held) {
        mBrakeHeld = held;
    }

    public boolean isThrottleOn() {
        return mThrottleHeld;
    }

    public boolean isBrakeOn() {
        return mBrakeHeld;
    }

    public boolean isDrivingRequested() {
        return mThrottleHeld || mBrakeHeld;
    }

    public float getCurrentSpeed() {
        return mCurrentSpeed;
    }

    /**
     * 每帧驱动：速度平滑逼近目标 → 累计轮转角 → 更新 4 个轮子变换。
     * 在 FrameCallback 中渲染前调用。
     */
    public void update(long frameTimeNanos) {
        if (mWheelCount == 0) {
            return;
        }
        float dt = 0f;
        if (mLastFrameTimeNs > 0) {
            dt = (frameTimeNanos - mLastFrameTimeNs) / 1_000_000_000f;
        }
        mLastFrameTimeNs = frameTimeNanos;
        if (dt <= 0f) {
            return;
        }
        dt = Math.min(dt, MAX_FRAME_DT);

        // 优先级：刹车 > 油门 > 滑行
        float target;
        float k;
        if (mBrakeHeld) {
            target = 0f;
            k = BRAKE_K;          // 刹车：减速快
        } else if (mThrottleHeld) {
            target = MAX_SPEED;
            k = ACCEL_K;          // 油门：加速
        } else {
            target = 0f;
            k = COAST_K;          // 松开油门：减速慢
        }
        float blend = 1f - (float) Math.exp(-k * dt);
        mCurrentSpeed += (target - mCurrentSpeed) * blend;
        if (target == 0f && mCurrentSpeed < 0.005f) {
            mCurrentSpeed = 0f;
        }
        // 已完全停止且轮子已停在当前角度：跳过无效更新
        if (mCurrentSpeed == 0f && mLastAngleApplied) {
            return;
        }

        // dθ = v / r（弧度）→ 度
        mTotalWheelAngleDeg += mCurrentSpeed / WHEEL_RADIUS * dt * SPIN_SIGN
                * (float) (180.0 / Math.PI);

        TransformManager tm = mEngine.getTransformManager();
        float[] rot = new float[16];
        Matrix.setIdentityM(rot, 0);
        Matrix.rotateM(rot, 0, mTotalWheelAngleDeg, 1f, 0f, 0f);
        for (int i = 0; i < mWheelCount; i++) {
            float[] local = new float[16];
            // local = base * rotX(angle)：绕轮心（局部原点）绕局部 X 轴（轮轴）自转
            Matrix.multiplyMM(local, 0, mWheelBaseTransforms[i], 0, rot, 0);
            tm.setTransform(tm.getInstance(mWheelEntities[i]), local);
        }
        mLastAngleApplied = true;
    }
}
