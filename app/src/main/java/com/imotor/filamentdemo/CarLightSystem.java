package com.imotor.filamentdemo;

import android.util.Log;

import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.LightManager;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Scene;
import com.google.android.filament.gltfio.FilamentAsset;

import java.util.ArrayList;
import java.util.List;

/**
 * 车灯系统：
 * - 车头：左右两颗 FOCUSED_SPOT 前照灯，支持开关与光轴/位置实时微调；
 * - 车尾：驱动模型侧拆出的 {@code CARRERA_4096_TAILLIGHTS} 材质自发光（真实灯罩几何），
 *   并叠加两颗红色点光制造光溢出，独立开关。
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

    // ── 车尾灯（由 tools/split_taillights.py 拆出的独立材质）────────────────────
    /**
     * 尾灯材质名（模型侧拆分生成）
     */
    private static final String TAIL_MATERIAL_NAME = "CARRERA_4096_TAILLIGHTS";
    /**
     * 尾灯开启时的自发光强度（emissiveFactor 已由模型设为红色）
     */
    private static final float TAIL_EMISSIVE_STRENGTH = 18f;
    /**
     * 转向灯材质名（模型侧拆分生成）
     */
    private static final String TURN_MATERIAL_NAME = "CARRERA_4096_TURNSIGNALS";
    /**
     * 转向灯点亮时的自发光强度（emissiveFactor 已由模型设为琥珀色）
     */
    private static final float TURN_EMISSIVE_STRENGTH = 24f;
    /**
     * 转向灯闪烁半周期（纳秒）：约 0.4s 亮 / 0.4s 灭
     */
    private static final long TURN_BLINK_INTERVAL_NS = 400_000_000L;
    // 两颗红色点光：给尾灯加"光溢出"，让灯罩与车身/地面融为一体
    // 对齐中央尾灯条带（x≈-1.81、y≈0.59、z∈[-0.51,0.51]）
    private static final float REAR_GLOW_X = -1.90f;
    private static final float REAR_GLOW_Y = 0.59f;
    private static final float REAR_GLOW_Z = 0.32f;
    private static final float REAR_GLOW_INTENSITY = 26_000f;  // 流明
    private static final float REAR_GLOW_FALLOFF = 2.2f;       // 衰减半径（米）

    /**
     * 前照灯 entity（左右两颗），默认不加入场景（关灯状态）
     */
    private final int[] mFrontLightEntities = {-1, -1};
    private boolean mFrontLightOn = false;

    /**
     * 模型尾部灯罩的材质实例（emissiveStrength 控制开关）
     */
    private final List<MaterialInstance> mTailMaterialInstances = new ArrayList<>();
    /**
     * 模型转向灯的材质实例
     */
    private final List<MaterialInstance> mTurnMaterialInstances = new ArrayList<>();
    /**
     * 尾灯红色点光 entity（左右各一颗），默认不加入场景
     */
    private final int[] mRearGlowLightEntities = {-1, -1};
    private boolean mRearLightOn = false;

    // ── 转向灯闪烁状态 ────────────────────────────────────────────────────
    private boolean mTurnSignalOn = false;
    private long mTurnLastToggleNs = -1;
    private boolean mTurnPhaseOn = false;

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

    public CarLightSystem(Engine engine, Scene scene, float groundY) {
        mEngine = engine;
        mScene = scene;
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
     * 初始化尾灯：定位模型侧拆出的 {@code CARRERA_4096_TAILLIGHTS} 材质实例
     * （默认 emissiveStrength=0 关灯），并创建两颗红色点光做光溢出。
     *
     * @param asset 拆分后的车模资源（由 tools/split_taillights.py 生成）
     */
    public void setupRearLights(FilamentAsset asset) {
        if (asset != null) {
            RenderableManager rm = mEngine.getRenderableManager();
            for (int entity : asset.getRenderableEntities()) {
                int ri = rm.getInstance(entity);
                if (ri == 0) {
                    continue;
                }
                for (int prim = 0; prim < rm.getPrimitiveCount(ri); prim++) {
                    MaterialInstance mi = rm.getMaterialInstanceAt(ri, prim);
                    if (mi == null || mi.getMaterial() == null) {
                        continue;
                    }
                    String name = mi.getName();
                    if (name == null) {
                        continue;
                    }
                    if (name.contains(TAIL_MATERIAL_NAME)) {
                        // 自发光颜色由模型设为红色，这里只控制强度开关
                        if (mi.getMaterial().hasParameter("emissiveFactor")) {
                            mi.setParameter("emissiveFactor", 1.0f, 0.05f, 0.03f);
                        }
                        mi.setParameter("emissiveStrength", 0f);
                        mTailMaterialInstances.add(mi);
                    } else if (name.contains(TURN_MATERIAL_NAME)) {
                        // 转向灯：琥珀色，默认灭，由 update() 控制闪烁
                        if (mi.getMaterial().hasParameter("emissiveFactor")) {
                            mi.setParameter("emissiveFactor", 1.0f, 0.45f, 0.0f);
                        }
                        mi.setParameter("emissiveStrength", 0f);
                        mTurnMaterialInstances.add(mi);
                    }
                }
            }
        }
        if (mTailMaterialInstances.isEmpty()) {
            Log.w(TAG, "setupRearLights: 未找到 " + TAIL_MATERIAL_NAME
                    + " 材质，尾灯开关不可用（模型是否已拆分？）");
        } else {
            Log.d(TAG, "setupRearLights: 尾灯材质实例 " + mTailMaterialInstances.size() + " 个");
        }
        Log.d(TAG, "setupRearLights: 转向灯材质实例 " + mTurnMaterialInstances.size() + " 个");

        // 两颗红色点光，制造灯罩向车身/地面的光溢出
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
    }

    /**
     * 开启或关闭车尾灯：切换尾灯材质 emissiveStrength + 红色点光。
     */
    public void setRearLightEnabled(boolean enabled) {
        if (mTailMaterialInstances.isEmpty() && mRearGlowLightEntities[0] == -1) {
            return;
        }
        Log.d(TAG, "setRearLightEnabled: " + enabled);
        if (enabled && !mRearLightOn) {
            for (MaterialInstance mi : mTailMaterialInstances) {
                mi.setParameter("emissiveStrength", TAIL_EMISSIVE_STRENGTH);
            }
            for (int e : mRearGlowLightEntities) {
                if (e != -1) {
                    mScene.addEntity(e);
                }
            }
            mRearLightOn = true;
        } else if (!enabled && mRearLightOn) {
            for (MaterialInstance mi : mTailMaterialInstances) {
                mi.setParameter("emissiveStrength", 0f);
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
     * 开启/关闭转向灯（开启后由 update() 按周期闪烁）。
     */
    public void setTurnSignalEnabled(boolean enabled) {
        if (mTurnMaterialInstances.isEmpty()) {
            Log.w(TAG, "setTurnSignalEnabled: 无转向灯材质实例");
            return;
        }
        Log.d(TAG, "setTurnSignalEnabled: " + enabled);
        mTurnSignalOn = enabled;
        if (enabled) {
            // 立即点亮，并重置闪烁计时
            mTurnLastToggleNs = -1;
            mTurnPhaseOn = true;
            applyTurn(true);
        } else {
            applyTurn(false);
            mTurnLastToggleNs = -1;
            mTurnPhaseOn = false;
        }
    }

    public boolean isTurnSignalOn() {
        return mTurnSignalOn;
    }

    /**
     * 每帧驱动转向灯闪烁（仅在开启时）。
     */
    public void update(long frameTimeNanos) {
        if (!mTurnSignalOn || mTurnMaterialInstances.isEmpty()) {
            return;
        }
        if (mTurnLastToggleNs < 0) {
            mTurnLastToggleNs = frameTimeNanos;
            mTurnPhaseOn = true;
            applyTurn(true);
            return;
        }
        if (frameTimeNanos - mTurnLastToggleNs >= TURN_BLINK_INTERVAL_NS) {
            mTurnLastToggleNs = frameTimeNanos;
            mTurnPhaseOn = !mTurnPhaseOn;
            applyTurn(mTurnPhaseOn);
        }
    }

    private void applyTurn(boolean on) {
        float strength = on ? TURN_EMISSIVE_STRENGTH : 0f;
        for (MaterialInstance mi : mTurnMaterialInstances) {
            mi.setParameter("emissiveStrength", strength);
        }
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
