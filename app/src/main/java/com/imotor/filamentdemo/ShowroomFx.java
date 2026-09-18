package com.imotor.filamentdemo;

import android.util.Log;

import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.LightManager;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.Scene;
import com.google.android.filament.View;

/**
 * 展厅氛围模块：开场"灯光渐亮"动画、主光/轮廓光、发光灯条。
 *
 * @author Yan.Liangliang
 * @date 2025/9/18
 */
public final class ShowroomFx {
    private static final String TAG = ShowroomFx.class.getSimpleName();

    // ── 开场"灯光渐亮"动画 ────────────────────────────────────────────────

    private static final float INTRO_DURATION_S = 3.0f;
    /**
     * 开场结束时各光源的目标强度
     */
    private static final float INTRO_KEY_LIGHT = 120_000f;
    private static final float INTRO_RIM_LIGHT = 60_000f;
    private static final float INTRO_IBL = 30_000f;
    private static final float INTRO_BLOOM_STRENGTH = 0.28f;

    /**
     * 行驶氛围：满速时环境光压暗到的比例（光束为 unlit，不受影响，因而更突出）。
     */
    private static final float DRIVE_IBL_MIN_SCALE = 0.15f;
    /**
     * 变暗曲线的软拐点（m/s），与速度光束同款非线性手感：
     * 低速段灯光变暗快，高速段趋缓。15 m/s ≈ 54 km/h，~100 km/h 时已接近最暗。
     */
    private static final float DRIVE_DIM_KNEE = 15f;

    private boolean mIntroPlaying = true;
    private long mIntroStartTimeNs = -1;
    private IndirectLight mIndirectLight;
    private final View.BloomOptions mBloomOptions = new View.BloomOptions();
    private LightManager mLights;

    private final Engine mEngine;
    private final Scene mScene;
    private final View mView;
    private final float mGroundY;
    /**
     * 灯条与速度光束共用的发光四边形工厂
     */
    private final EmissiveQuadFactory mQuadFactory;

    // ── 展厅主光 / 轮廓光 ─────────────────────────────────────────────────

    private final int[] mShowroomLightEntities = {-1, -1};

    /**
     * 灯条 entity 列表（两条顶灯带；尾部柔光板已移除，避免遮挡车尾视野）
     */
    private final int[] mLightBarEntities = new int[2];
    /**
     * 灯条材质实例，便于行驶时按速度调低发光强度
     */
    private final MaterialInstance[] mLightBarInstances = new MaterialInstance[2];
    /**
     * 灯条基础发光强度（行驶氛围在它基础上按比例缩放）
     */
    private static final float LIGHT_BAR_BASE_INTENSITY = 18f;

    public ShowroomFx(Engine engine, Scene scene, View view,
                      EmissiveQuadFactory quadFactory, float groundY) {
        mEngine = engine;
        mScene = scene;
        mView = view;
        mQuadFactory = quadFactory;
        mGroundY = groundY;
    }

    /**
     * 展厅布光：主光（暖白顶光，制造明暗层次）+ 冷色轮廓光（勾出车身边缘）。
     * 初期强度 0，由 updateIntro 渐亮。
     */
    public void setupLights() {
        // 主光：从左前上方压下，车身左前亮、右后暗，形成对比
        int key = EntityManager.get().create();
        mShowroomLightEntities[0] = key;
        new LightManager.Builder(LightManager.Type.SUN)
                .color(1.0f, 0.97f, 0.92f)
                .intensity(INTRO_KEY_LIGHT)
                .direction(-0.45f, -0.8f, -0.4f)
                .castShadows(true)
                .build(mEngine, key);
        mLights = mEngine.getLightManager();
        mLights.setIntensity(mLights.getInstance(key), 0f);
        mScene.addEntity(key);

        // 轮廓光：冷蓝色从右后方逆着打，勾亮车身边缘
        int rim = EntityManager.get().create();
        mShowroomLightEntities[1] = rim;
        new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(0.65f, 0.8f, 1.0f)
                .intensity(INTRO_RIM_LIGHT)
                .direction(0.35f, -0.25f, 0.9f)
                .build(mEngine, rim);
        mLights.setIntensity(mLights.getInstance(rim), 0f);
        mScene.addEntity(rim);
    }

    /**
     * 展厅灯条：两根车顶上方长条灯带（原尾部冷白柔光板会挡住车尾，已移除），
     * 模拟 su7-replica 的 StartRoom 灯带氛围。
     */
    public void setupLightBars() {
        if (!mQuadFactory.isReady()) {
            Log.w(TAG, "setupLightBars: emissive 材质不可用，灯条跳过");
            return;
        }
        float barY = mGroundY + EmissiveQuadFactory.LIGHT_BAR_HEIGHT;

        // 顶灯带①（车左侧上方，暖白）
        MaterialInstance bar1 = mQuadFactory.getMaterial().createInstance();
        bar1.setDoubleSided(true);
        bar1.setParameter("glowColor", 1.0f, 0.96f, 0.92f);
        bar1.setParameter("intensity", LIGHT_BAR_BASE_INTENSITY);
        mLightBarInstances[0] = bar1;
        mLightBarEntities[0] = mQuadFactory.createQuad(mEngine, mScene, bar1,
                0f, barY, 2.6f, 14f, 0.35f, EmissiveQuadFactory.ORIENT_DOWN);

        // 顶灯带②（车右侧上方，暖白）
        MaterialInstance bar2 = mQuadFactory.getMaterial().createInstance();
        bar2.setDoubleSided(true);
        bar2.setParameter("glowColor", 1.0f, 0.96f, 0.92f);
        bar2.setParameter("intensity", LIGHT_BAR_BASE_INTENSITY);
        mLightBarInstances[1] = bar2;
        mLightBarEntities[1] = mQuadFactory.createQuad(mEngine, mScene, bar2,
                0f, barY, -2.6f, 14f, 0.35f, EmissiveQuadFactory.ORIENT_DOWN);
    }

    /**
     * hero 化入场：画面从全黑开始，主光/轮廓光/环境光/Bloom 在 3 秒内渐亮。
     */
    public void updateIntro(long frameTimeNanos) {
        if (mIntroStartTimeNs < 0) {
            mIntroStartTimeNs = frameTimeNanos;
        }
        float t = (frameTimeNanos - mIntroStartTimeNs) / 1_000_000_000f;
        float p = Math.min(t / INTRO_DURATION_S, 1f);
        // power3.out 缓动，前快后慢
        float e = 1f - (1f - p) * (1f - p) * (1f - p);

        if (mLights != null && mShowroomLightEntities[0] != -1) {
            mLights.setIntensity(mLights.getInstance(mShowroomLightEntities[0]), INTRO_KEY_LIGHT * e);
            mLights.setIntensity(mLights.getInstance(mShowroomLightEntities[1]), INTRO_RIM_LIGHT * e);
        }
        if (mIndirectLight != null) {
            mIndirectLight.setIntensity(INTRO_IBL * e);
        }
        mBloomOptions.enabled = true;
        mBloomOptions.strength = INTRO_BLOOM_STRENGTH * e;
        mBloomOptions.threshold = false;
        mView.setBloomOptions(mBloomOptions);

        if (p >= 1f) {
            mIntroPlaying = false;
            Log.d(TAG, "updateIntro: 开场灯光渐亮动画结束");
        }
    }

    public boolean isIntroPlaying() {
        return mIntroPlaying;
    }

    /**
     * 行驶氛围：速度越高环境光/主光/顶灯条越暗，制造"越跑越暗、光束越亮眼"的氛围。
     * 变暗采用与速度光束同款的非线性饱和曲线（低速变化快、高速趋缓）。
     * 速度光束为 unlit 自发光，不会被压暗。
     * 开场渐亮动画期间由 updateIntro 接管，此方法不生效。
     *
     * @param currentSpeed 当前车速（m/s）
     */
    public void updateDriveMood(float currentSpeed) {
        if (mIntroPlaying) {
            return;
        }
        // 非线性变暗程度：0（静止）→ 1（高速趋近饱和）
        float dim = 1f - (float) Math.exp(-Math.max(0f, currentSpeed) / DRIVE_DIM_KNEE);
        float scale = 1f - (1f - DRIVE_IBL_MIN_SCALE) * dim;

        // 环境光
        if (mIndirectLight != null) {
            mIndirectLight.setIntensity(INTRO_IBL * scale);
        }
        // 主光随速度压暗
        if (mLights != null && mShowroomLightEntities[0] != -1) {
            mLights.setIntensity(mLights.getInstance(mShowroomLightEntities[0]), INTRO_KEY_LIGHT * scale);
        }
        // 顶灯条发光强度随速度调低
        for (MaterialInstance bar : mLightBarInstances) {
            if (bar != null) {
                bar.setParameter("intensity", LIGHT_BAR_BASE_INTENSITY * scale);
            }
        }
    }

    public void setIndirectLight(IndirectLight light) {
        mIndirectLight = light;
    }
}
