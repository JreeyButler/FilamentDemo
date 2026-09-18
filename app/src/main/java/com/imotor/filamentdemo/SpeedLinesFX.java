package com.imotor.filamentdemo;

import android.opengl.Matrix;
import android.util.Log;

import com.google.android.filament.Engine;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.Scene;
import com.google.android.filament.TransformManager;

import java.util.Random;

/**
 * 速度光束特效（彩色光条围绕车身平行滑动，复刻 su7 speedup 光束）。
 * 负责：光束生成、随车速 1:1 后退/回绕、强度渐变、可见数量随速度分级（低速 5 根 → 满速 60 根）。
 *
 * @author Yan.Liangliang
 * @date 2025/9/18
 */
public final class SpeedLinesFX {
    private static final String TAG = SpeedLinesFX.class.getSimpleName();

    /**
     * 光束数量
     */
    private static final int SPEED_BEAM_COUNT = 60;
    /**
     * 光束到车身轴线的距离范围（米），需大于开门半径
     */
    private static final float BEAM_RADIUS_MIN = 3.4f;
    private static final float BEAM_RADIUS_MAX = 6.0f;
    /**
     * 光束长度/厚度范围（米）
     */
    private static final float BEAM_MIN_LEN = 2.5f;
    private static final float BEAM_MAX_LEN = 7.0f;
    private static final float BEAM_THICKNESS = 0.05f;
    /**
     * 光束 X 活动范围：车头 +X，光束向 -X 退，出界回绕
     */
    private static final float BEAM_X_MIN = -9.0f;
    private static final float BEAM_X_RANGE = 18.0f;
    /**
     * 自发光强度范围
     */
    private static final float BEAM_INTENSITY_MIN = 8f;
    private static final float BEAM_INTENSITY_MAX = 26f;

    /**
     * 可见光束数下限：低速时最少显示 5 根，满速显示全部 60 根
     */
    private static final int BEAM_MIN_COUNT = 5;

    private final int[] mBeamEntities = new int[SPEED_BEAM_COUNT * 2];
    /** 每根光束当前 X 位置 */
    private final float[] mBeamX = new float[SPEED_BEAM_COUNT];
    /** 每根光束参数：y, z, 基准强度 */
    private final float[][] mBeamPlacement = new float[SPEED_BEAM_COUNT][3];
    private final MaterialInstance[] mBeamInstances = new MaterialInstance[SPEED_BEAM_COUNT];
    private boolean mBeamsBuilt = false;
    private float mBeamFade = 0f;
    private long mBeamLastFrameNs = -1;
    /**
     * 每根光束的显示优先序号（随机洗牌）：rank < activeCount 者在场景中。
     * 低速显示的光束即序号最小的那几根，稳定不闪烁。
     */
    private final int[] mBeamRank = new int[SPEED_BEAM_COUNT];
    /** 每根光束当前是否在场景中 */
    private final boolean[] mBeamVisible = new boolean[SPEED_BEAM_COUNT];
    /** 当前在场景中的光束根数 */
    private int mVisibleBeamCount = 0;

    private final Engine mEngine;
    private final Scene mScene;
    private final EmissiveQuadFactory mQuadFactory;
    private final float mGroundY;

    public SpeedLinesFX(Engine engine, Scene scene, EmissiveQuadFactory quadFactory, float groundY) {
        mEngine = engine;
        mScene = scene;
        mQuadFactory = quadFactory;
        mGroundY = groundY;
    }

    public boolean isBuilt() {
        return mBeamsBuilt;
    }

    /**
     * 一次性生成光束：绕车身圆柱随机分布的细长彩色发光条（局部原点在束中心，
     * 顶点只含形状，位置由运行时平移矩阵控制）。
     */
    public void build() {
        if (!mQuadFactory.isReady()) {
            Log.w(TAG, "build: emissive 材质不可用，速度光束跳过");
            return;
        }
        Random rnd = new Random(20260918L);
        for (int i = 0; i < SPEED_BEAM_COUNT; i++) {
            MaterialInstance inst = mQuadFactory.getMaterial().createInstance();
            inst.setDoubleSided(true);
            // 随机彩光（HSV 均匀取样色相）
            float[] rgb = hsvToRgb(rnd.nextFloat(), 0.75f, 1f);
            inst.setParameter("glowColor", rgb[0], rgb[1], rgb[2]);
            float baseIntensity = BEAM_INTENSITY_MIN
                    + rnd.nextFloat() * (BEAM_INTENSITY_MAX - BEAM_INTENSITY_MIN);
            inst.setParameter("intensity", 0f); // 初始隐藏

            float len = BEAM_MIN_LEN + rnd.nextFloat() * (BEAM_MAX_LEN - BEAM_MIN_LEN);
            float angle = rnd.nextFloat() * 2f * (float) Math.PI;
            float radius = BEAM_RADIUS_MIN + rnd.nextFloat() * (BEAM_RADIUS_MAX - BEAM_RADIUS_MIN);
            float y = mGroundY + 2.0f + radius * (float) Math.sin(angle);
            y = Math.max(mGroundY + 0.25f, Math.min(mGroundY + 4.5f, y));
            float z = radius * (float) Math.cos(angle);
            mBeamPlacement[i] = new float[]{y, z, baseIntensity};
            mBeamX[i] = BEAM_X_MIN + rnd.nextFloat() * BEAM_X_RANGE;

            mBeamInstances[i] = inst;
            // 每根光条 = 正交双片十字（竖直面片 FACING_Z + 水平面片 ORIENT_DOWN），
            // 保证绕车任意视角都有一片正对视线；两片共享同一材质实例
            mBeamEntities[i * 2] = mQuadFactory.createQuad(mEngine, mScene, inst,
                    0f, y, z, len, BEAM_THICKNESS, EmissiveQuadFactory.ORIENT_FACING_Z);
            mBeamEntities[i * 2 + 1] = mQuadFactory.createQuad(mEngine, mScene, inst,
                    0f, y, z, len, BEAM_THICKNESS, EmissiveQuadFactory.ORIENT_DOWN);
        }
        mBeamsBuilt = true;
        Log.d(TAG, "build: 创建 " + SPEED_BEAM_COUNT + " 根速度光束");

        // 显示优先序号：随机洗牌，低速时显示的 5 根分布随机且稳定
        Random shuffle = new Random(1024L);
        for (int i = 0; i < SPEED_BEAM_COUNT; i++) {
            mBeamRank[i] = i;
        }
        for (int i = SPEED_BEAM_COUNT - 1; i > 0; i--) {
            int j = shuffle.nextInt(i + 1);
            int tmp = mBeamRank[i];
            mBeamRank[i] = mBeamRank[j];
            mBeamRank[j] = tmp;
        }

        // 光束只在行驶时入场景渲染（停止时整组移除，避免黑色条挡住背景）
        mScene.removeEntities(mBeamEntities);
        mVisibleBeamCount = 0;
    }

    /**
     * 每帧驱动光束：随车速 1:1 向 -X 后退、出界回绕；显示强度随速度渐显渐隐；
     * 可见根数随速度分级（rank 管理，逐根增删场景）。
     *
     * @return 当前在场景中的光束根数（供省电调度判定忙碌）
     */
    public int update(long frameTimeNanos, float currentSpeed) {
        if (!mBeamsBuilt) {
            return 0;
        }
        float dt = 0f;
        if (mBeamLastFrameNs > 0) {
            dt = (frameTimeNanos - mBeamLastFrameNs) / 1_000_000_000f;
        }
        mBeamLastFrameNs = frameTimeNanos;
        if (dt <= 0f) {
            return mVisibleBeamCount;
        }
        dt = Math.min(dt, DriveSystem.MAX_FRAME_DT);

        // 显示强度渐变：淡入较缓、淡出加速（平方加快尾段淡出）
        float target = currentSpeed > 0.05f ? 1f : 0f;
        float fadeRate = target > 0f ? 3.5f : 6.5f;
        mBeamFade += (target - mBeamFade) * (1f - (float) Math.exp(-fadeRate * dt));
        if (target == 0f && mBeamFade < 0.02f) {
            mBeamFade = 0f;
        }
        float move = currentSpeed * dt; // 1:1 与滚动轮速同步

        // 可见根数随速度：低速 5 根 → 高速满编 60 根（count = 5 + 55×speedRatio）；
        // 完全停下（fade 归零）后全部移出场景，避免黑色不透明条遮挡背景
        float speedRatio = Math.min(1f, currentSpeed / DriveSystem.MAX_SPEED);
        int desiredCount = mBeamFade <= 0f
                ? 0
                : (int) Math.round(BEAM_MIN_COUNT
                        + (SPEED_BEAM_COUNT - BEAM_MIN_COUNT) * speedRatio);
        for (int i = 0; i < SPEED_BEAM_COUNT; i++) {
            boolean shouldBeVisible = mBeamRank[i] < desiredCount;
            if (shouldBeVisible && !mBeamVisible[i]) {
                mScene.addEntity(mBeamEntities[i * 2]);
                mScene.addEntity(mBeamEntities[i * 2 + 1]);
                mBeamVisible[i] = true;
                mVisibleBeamCount++;
            } else if (!shouldBeVisible && mBeamVisible[i]) {
                mScene.removeEntity(mBeamEntities[i * 2]);
                mScene.removeEntity(mBeamEntities[i * 2 + 1]);
                mBeamVisible[i] = false;
                mVisibleBeamCount--;
            }
        }
        // 全部不可见且静止：跳过后续更新
        if (mVisibleBeamCount == 0 && mBeamFade <= 0f && target == 0f) {
            return 0;
        }

        TransformManager tm = mEngine.getTransformManager();
        float fadeSq = mBeamFade * mBeamFade;
        float[] m = new float[16];
        for (int i = 0; i < SPEED_BEAM_COUNT; i++) {
            mBeamX[i] -= move;
            if (mBeamX[i] < BEAM_X_MIN) {
                mBeamX[i] += BEAM_X_RANGE;
            }
            // local = T(beamX)：平移到当前后退位置（顶点围绕局部原点）
            Matrix.setIdentityM(m, 0);
            m[12] = mBeamX[i];
            tm.setTransform(tm.getInstance(mBeamEntities[i * 2]), m);
            tm.setTransform(tm.getInstance(mBeamEntities[i * 2 + 1]), m);
            mBeamInstances[i].setParameter("intensity",
                    mBeamPlacement[i][2] * fadeSq);
        }
        return mVisibleBeamCount;
    }

    /**
     * 简易 HSV → RGB
     */
    private static float[] hsvToRgb(float h, float s, float v) {
        int i = (int) (h * 6f);
        float f = h * 6f - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        switch (i % 6) {
            case 0: return new float[]{v, t, p};
            case 1: return new float[]{q, v, p};
            case 2: return new float[]{p, v, t};
            case 3: return new float[]{p, q, v};
            case 4: return new float[]{t, p, v};
            default: return new float[]{v, p, q};
        }
    }
}
