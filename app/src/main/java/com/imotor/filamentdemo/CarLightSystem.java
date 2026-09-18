package com.imotor.filamentdemo;

import android.util.Log;

import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.LightManager;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.Scene;

/**
 * 车灯系统：
 * - 车头：左右两颗 FOCUSED_SPOT 前照灯，支持开关与光轴/位置实时微调；
 * - 车尾：红色自发光灯带（emissive quad + Bloom），支持开关。
 *
 * @author Yan.Liangliang
 * @date 2025/9/18
 */
public final class CarLightSystem {
    private static final String TAG = CarLightSystem.class.getSimpleName();

    // ── 车头灯几何（实测自 cartoon_sports_car.glb，+X = 车头、+Y 向上、+Z 朝左）──────
    // 车鼻最前端 x≈2.13；前灯带 x≈1.86、y 0.62~0.87、横向横跨 z≈±0.87。
    // 注意：模型自带 6.4m×6.4m 底座（y=0），会把 asset 包围盒撑大到 x≈±3.19，
    // 因此不能从包围盒推算灯位，只能按上面实测值放灯。
    private static final float HEADLIGHT_X = 2.40f;     // 灯位：车鼻前约 0.25m，光锥不会先吃到车身
    private static final float HEADLIGHT_Y = 1.05f;     // 略高于灯带，减少贴地近场刺眼区
    private static final float HEADLIGHT_Z_L = 0.62f;   // 左灯（模型 +Z 侧）
    private static final float HEADLIGHT_Z_R = -0.62f;  // 右灯
    private static final float BEAM_RANGE = 7.0f;       // 光斑落点距灯的水平距离（决定光轴下压俯角）
    private static final float LIGHT_INTENSITY = 3_000_000f;  // cd，真机看效果后调整
    private static final float CONE_INNER_DEG = 6f;     // 内锥（亮心）
    private static final float CONE_OUTER_DEG = 14f;    // 外锥（光斑边缘）
    // 左右灯各向外偏约 2.6°，让两束光在远处分开成两个独立光斑，而不是中间糊成一团
    private static final float BEAM_TOE_Z = 0.045f;

    // ── 车尾灯几何（lamp 透镜条在 x≈-1.8~-1.5、y≈0.6、z≈±0.88）──────────────
    private static final float REAR_LIGHT_X = -1.83f;   // 尾灯面稍靠车外，避免埋进车身
    private static final float REAR_LIGHT_Y = 0.60f;
    private static final float REAR_LIGHT_WIDTH = 1.50f;  // 沿 Z 的灯带宽度
    private static final float REAR_LIGHT_HEIGHT = 0.16f; // 沿 Y 的灯带高度
    private static final float REAR_LIGHT_INTENSITY = 16f;
    // 两颗红色点光：给尾灯加"光溢出"，让灯带与车身/地面融为一体，减少贴纸感
    private static final float REAR_GLOW_X = -1.95f;
    private static final float REAR_GLOW_Y = 0.60f;
    private static final float REAR_GLOW_Z = 0.55f;
    private static final float REAR_GLOW_INTENSITY = 26_000f;  // 流明
    private static final float REAR_GLOW_FALLOFF = 2.2f;       // 衰减半径（米）

    /**
     * 前照灯 entity（左右两颗），默认不加入场景（关灯状态）
     */
    private final int[] mFrontLightEntities = {-1, -1};
    private boolean mFrontLightOn = false;

    /**
     * 尾灯自发光灯带 entity，默认不加入场景（关灯状态）
     */
    private final java.util.List<Integer> mRearLightEntities = new java.util.ArrayList<>();
    /**
     * 尾灯红色点光 entity（左右各一颗），默认不加入场景
     */
    private final int[] mRearGlowLightEntities = {-1, -1};
    private boolean mRearLightOn = false;
    private final EmissiveQuadFactory mQuadFactory;

    /**
     * 当前光轴方向（两颗灯共用，可实时微调）
     */
    private float mLightDirX;
    private float mLightDirY;
    private float mLightDirZ;

    /**
     * 当前两颗灯的位置（运行时可调整）
     */
    private final float[][] mLightPos = {{0f, 0f, 0f}, {0f, 0f, 0f}};

    private final Engine mEngine;
    private final Scene mScene;
    private final float mGroundY;

    public CarLightSystem(Engine engine, Scene scene,
                          EmissiveQuadFactory quadFactory, float groundY) {
        mEngine = engine;
        mScene = scene;
        mQuadFactory = quadFactory;
        mGroundY = groundY;
    }

    /**
     * 创建左右两颗 FOCUSED_SPOT 前照灯并初始化光轴/位置。
     */
    public void setup() {
        float[] baseZ = {HEADLIGHT_Z_L, HEADLIGHT_Z_R};
        for (int i = 0; i < mFrontLightEntities.length; i++) {
            int entity = EntityManager.get().create();
            mFrontLightEntities[i] = entity;
            new LightManager.Builder(LightManager.Type.FOCUSED_SPOT)
                    // 暖白光
                    .color(1.0f, 0.96f, 0.88f)
                    .intensity(LIGHT_INTENSITY)
                    .castShadows(true)
                    .spotLightCone(CONE_INNER_DEG, CONE_OUTER_DEG)
                    .falloff(30f)
                    .build(mEngine, entity);
            mLightPos[i][0] = HEADLIGHT_X;
            mLightPos[i][1] = HEADLIGHT_Y;
            mLightPos[i][2] = baseZ[i];
        }

        // 光轴朝车前方下压，落点在地面上
        updateBeamDirectionInternal();
        applyLightPosition();
        applyLightDirection();
        Log.d(TAG, "setup: 已创建左右两颗 FOCUSED_SPOT 前照灯");
    }

    /**
     * 创建车尾红色自发光灯带（默认关灯，entity 先从场景移除）。
     * 车模自带的尾灯透镜（material CARRERA_4096_lamps）几乎不自发光，
     * 这里用 emissive quad 叠一条可控的尾灯带，经 Bloom 呈现红色辉光。
     */
    public void setupRearLights() {
        if (mQuadFactory == null || !mQuadFactory.isReady()) {
            Log.w(TAG, "setupRearLights: emissive 材质不可用，尾灯跳过");
            return;
        }
        MaterialInstance bar = mQuadFactory.getMaterial().createInstance();
        bar.setDoubleSided(true);
        bar.setParameter("glowColor", 1.0f, 0.06f, 0.04f);
        bar.setParameter("intensity", REAR_LIGHT_INTENSITY);
        int entity = mQuadFactory.createQuad(mEngine, mScene, bar,
                REAR_LIGHT_X, REAR_LIGHT_Y, 0f,
                REAR_LIGHT_WIDTH, REAR_LIGHT_HEIGHT,
                EmissiveQuadFactory.ORIENT_FACING_X);
        mRearLightEntities.add(entity);
        // 关灯：先从场景移除，开灯时再加回
        mScene.removeEntity(entity);

        // 两颗红色点光，制造灯带向车身/地面的光溢出
        float[] baseZ = {REAR_GLOW_Z, -REAR_GLOW_Z};
        for (int i = 0; i < mRearGlowLightEntities.length; i++) {
            int glow = EntityManager.get().create();
            mRearGlowLightEntities[i] = glow;
            new LightManager.Builder(LightManager.Type.POINT)
                    .color(1.0f, 0.08f, 0.05f)
                    .intensity(REAR_GLOW_INTENSITY)
                    .falloff(REAR_GLOW_FALLOFF)
                    .position(REAR_GLOW_X, REAR_GLOW_Y, baseZ[i])
                    .castShadows(false)
                    .build(mEngine, glow);
            mScene.removeEntity(glow);
        }
        Log.d(TAG, "setupRearLights: 已创建尾灯灯带 + 红色点光");
    }

    /**
     * 开启或关闭车尾灯
     */
    public void setRearLightEnabled(boolean enabled) {
        if (mRearLightEntities.isEmpty()) return;
        Log.d(TAG, "setRearLightEnabled: " + enabled);
        if (enabled && !mRearLightOn) {
            for (int e : mRearLightEntities) {
                mScene.addEntity(e);
            }
            for (int e : mRearGlowLightEntities) {
                if (e != -1) {
                    mScene.addEntity(e);
                }
            }
            mRearLightOn = true;
        } else if (!enabled && mRearLightOn) {
            for (int e : mRearLightEntities) {
                mScene.removeEntity(e);
            }
            for (int e : mRearGlowLightEntities) {
                if (e != -1) {
                    mScene.removeEntity(e);
                }
            }
            mRearLightOn = false;
        }
    }

    public boolean isRearLightOn() {
        return mRearLightOn;
    }

    /**
     * 依据灯位与地面落点重新计算光轴方向（下压俯角 = atan(灯高 / BEAM_RANGE)）
     */
    private void updateBeamDirectionInternal() {
        float dx = BEAM_RANGE;
        float dy = mGroundY - HEADLIGHT_Y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        mLightDirX = dx / len;
        mLightDirY = dy / len;
        mLightDirZ = 0f;
    }

    /**
     * 开启或关闭车头前照灯
     *
     * @param enabled true = 开灯，false = 关灯
     */
    public void setFrontLightEnabled(boolean enabled) {
        if (mFrontLightEntities[0] == -1) return;
        Log.d(TAG, "setFrontLightEnabled: " + enabled + ", entities="
                + mFrontLightEntities[0] + "," + mFrontLightEntities[1]);
        if (enabled && !mFrontLightOn) {
            for (int e : mFrontLightEntities) {
                mScene.addEntity(e);
            }
            mFrontLightOn = true;
        } else if (!enabled && mFrontLightOn) {
            for (int e : mFrontLightEntities) {
                mScene.removeEntity(e);
            }
            mFrontLightOn = false;
        }
    }

    public boolean isFrontLightOn() {
        return mFrontLightOn;
    }

    public float[] getLightDirection() {
        return new float[]{mLightDirX, mLightDirY, mLightDirZ};
    }

    /**
     * 动态调整光轴方向分量（正/负步进），调整后立即生效。
     */
    public void adjustLightDirection(float dx, float dy, float dz) {
        mLightDirX += dx;
        mLightDirY += dy;
        mLightDirZ += dz;
        applyLightDirection();
    }

    public void resetLightDirection() {
        updateBeamDirectionInternal();
        applyLightDirection();
    }

    private void applyLightDirection() {
        if (mFrontLightEntities[0] == -1) return;
        LightManager lm = mEngine.getLightManager();
        for (int i = 0; i < mFrontLightEntities.length; i++) {
            // 左灯(i=0, +Z 侧)向外(+Z)偏，右灯向 -Z 偏，形成外展的两束光
            float toe = i == 0 ? BEAM_TOE_Z : -BEAM_TOE_Z;
            lm.setDirection(lm.getInstance(mFrontLightEntities[i]),
                    mLightDirX, mLightDirY, mLightDirZ + toe);
        }
        Log.d(TAG, String.format("applyLightDirection: (%.2f, %.2f, %.2f)",
                mLightDirX, mLightDirY, mLightDirZ));
    }

    public float[] getLightPosition() {
        return new float[]{mLightPos[0][0], mLightPos[0][1], mLightPos[0][2]};
    }

    /**
     * 动态调整光源位置（delta 为步进值，左右灯同步），调整后立即生效。
     */
    public void adjustLightPosition(float dx, float dy, float dz) {
        for (float[] p : mLightPos) {
            p[0] += dx;
            p[1] += dy;
            p[2] += dz;
        }
        applyLightPosition();
    }

    public void resetLightPosition() {
        mLightPos[0][0] = HEADLIGHT_X;
        mLightPos[0][1] = HEADLIGHT_Y;
        mLightPos[0][2] = HEADLIGHT_Z_L;
        mLightPos[1][0] = HEADLIGHT_X;
        mLightPos[1][1] = HEADLIGHT_Y;
        mLightPos[1][2] = HEADLIGHT_Z_R;
        applyLightPosition();
    }

    private void applyLightPosition() {
        if (mFrontLightEntities[0] == -1) return;
        LightManager lm = mEngine.getLightManager();
        for (int i = 0; i < mFrontLightEntities.length; i++) {
            float[] p = mLightPos[i];
            lm.setPosition(lm.getInstance(mFrontLightEntities[i]), p[0], p[1], p[2]);
        }
        Log.d(TAG, String.format("applyLightPosition: (%.2f, %.2f, %.2f)",
                mLightPos[0][0], mLightPos[0][1], mLightPos[0][2]));
    }
}
