package com.imotor.filamentdemo;

import android.util.Log;

import com.google.android.filament.Engine;
import com.google.android.filament.SwapChain;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.ResourceLoader;
import com.google.android.filament.utils.Manipulator;
import com.google.android.filament.utils.ModelViewer;

import java.lang.reflect.Field;

/**
 * 渲染管线：绕开 ModelViewer.render 内部"相机同步与绘制焊死"的流程，
 * 自管渲染每帧：异步资源推进/就绪实体入场景 → manipulator 姿态 +
 * 速度抖动 → camera.lookAt → beginFrame/render/endFrame。
 * swapChain / resourceLoader 为 ModelViewer 私有成员，经由反射读取；
 * 反射失败时退化为无抖动的标准渲染。
 *
 * @author Yan.Liangliang
 * @date 2025/9/18
 */
public final class RenderPipeline {
    private static final String TAG = RenderPipeline.class.getSimpleName();

    /**
     * 满幅抖动时的最大眼点偏移（米），真机校准：偏移减半保留质感
     */
    private static final float SHAKE_MAX_OFFSET = 0.011f;

    private final ModelViewer mModelViewer;
    private final Engine mEngine;
    private final Manipulator mManipulator;

    /**
     * 反射缓存的 swapChain 字段（Kotlin private var），避免每帧重复 getDeclaredField
     */
    private Field mSwapChainField;
    private boolean mSwapChainFieldFailed = false;

    private Field mResourceLoaderField;
    private boolean mResourceLoaderFieldFailed = false;

    /**
     * 抖动强度（0..1），随速度渐显渐隐（帧回调单线程访问）
     */
    private float mShakeLevel = 0f;
    private long mShakeLastFrameNs = -1;

    public RenderPipeline(ModelViewer modelViewer, Engine engine, Manipulator manipulator) {
        mModelViewer = modelViewer;
        mEngine = engine;
        mManipulator = manipulator;
    }

    /**
     * 每帧驱动：抖动强度随速度渐变 + 渲染一帧
     *
     * @param frameTimeNanos 当前帧时间
     * @param currentSpeed   当前行驶速度（m/s），决定抖动幅度
     */
    public void update(long frameTimeNanos, float currentSpeed) {
        updateShake(frameTimeNanos, currentSpeed);
        renderFrame(frameTimeNanos, currentSpeed);
    }

    /**
     * 复刻 ModelViewer.render 的流程，但在相机姿态同步处插入抖动：
     * 异步资源推进/就绪实体入场景 → manipulator.getLookAt → 叠加速度抖动 →
     * camera.lookAt → beginFrame/render/endFrame。
     * 反射失败时退化：无抖动但仍按此流程渲染。
     */
    private void renderFrame(long frameTimeNanos, float currentSpeed) {
        // 复刻 ModelViewer 内部的异步纹理推进 + 就绪实体入场景（漏掉会导致车模不渲染）
        syncModelLoading();

        com.google.android.filament.Camera camera = mModelViewer.getCamera();
        float[] eye = new float[3];
        float[] target = new float[3];
        float[] up = new float[3];
        mManipulator.getLookAt(eye, target, up);

        // 相机抖动：幅度随速度渐显（复刻 su7 rush 的晃动感）
        if (mShakeLevel > 0.001f) {
            float t = frameTimeNanos * 1e-9f;
            float speedRatio = currentSpeed / DriveSystem.MAX_SPEED;
            float amp = SHAKE_MAX_OFFSET * mShakeLevel * (0.35f + 0.65f * speedRatio);
            eye[0] += shakeWave(t * 9.1f, 0.7f) * amp;
            eye[1] += shakeWave(t * 7.3f, 1.9f) * amp * 0.6f;
            eye[2] += shakeWave(t * 8.2f, 3.1f) * amp;
        }
        camera.lookAt(eye[0], eye[1], eye[2],
                target[0], target[1], target[2], up[0], up[1], up[2]);

        com.google.android.filament.Renderer renderer = mModelViewer.getRenderer();
        SwapChain swapChain = fetchSwapChain();
        if (swapChain == null) {
            if (mSwapChainFieldFailed) {
                // 反射退化：回退到无抖动的标准渲染流程
                mModelViewer.render(frameTimeNanos);
            }
            return; // surface 尚未就绪
        }
        if (renderer.beginFrame(swapChain, frameTimeNanos)) {
            renderer.render(mModelViewer.getView());
            renderer.endFrame();
        }
    }

    /**
     * 反射获取 ModelViewer 内部的 resourceLoader（构造时创建，可安全缓存）
     */
    private ResourceLoader fetchResourceLoader() {
        try {
            if (mResourceLoaderField == null) {
                if (mResourceLoaderFieldFailed) {
                    return null;
                }
                mResourceLoaderField = ModelViewer.class.getDeclaredField("resourceLoader");
                mResourceLoaderField.setAccessible(true);
            }
            Object o = mResourceLoaderField.get(mModelViewer);
            return o instanceof ResourceLoader ? (ResourceLoader) o : null;
        } catch (Throwable t) {
            Log.w(TAG, "fetchResourceLoader: 反射失败", t);
            mResourceLoaderFieldFailed = true;
            return null;
        }
    }

    /**
     * 复刻 ModelViewer.render 前半段：推进异步资源加载 + 把就绪 renderable 批量入场景。
     * （glb 经 asyncBeginLoad 异步加载，若漏掉此步车模实体永远不会进场景）
     */
    private void syncModelLoading() {
        FilamentAsset asset = mModelViewer.getAsset();
        if (asset == null) {
            return;
        }
        ResourceLoader loader = fetchResourceLoader();
        if (loader != null) {
            loader.asyncUpdateLoad();
        }
        int[] ready = new int[128];
        int count;
        com.google.android.filament.RenderableManager rcm = mEngine.getRenderableManager();
        while ((count = asset.popRenderables(ready)) > 0) {
            for (int i = 0; i < count; i++) {
                int ri = rcm.getInstance(ready[i]);
                if (ri != 0) {
                    rcm.setScreenSpaceContactShadows(ri, true);
                }
            }
            mModelViewer.getScene().addEntities(java.util.Arrays.copyOf(ready, count));
        }
        // 与 ModelViewer 一致，每帧追加灯光实体（重复 add 幂等）
        mModelViewer.getScene().addEntities(asset.getLightEntities());
    }

    /**
     * 反射获取 ModelViewer 内部的 swapChain（surface 生命周期中可能重建，每次帧读取）
     */
    private SwapChain fetchSwapChain() {
        try {
            if (mSwapChainField == null) {
                if (mSwapChainFieldFailed) {
                    return null;
                }
                mSwapChainField = ModelViewer.class.getDeclaredField("swapChain");
                mSwapChainField.setAccessible(true);
            }
            Object o = mSwapChainField.get(mModelViewer);
            return o instanceof SwapChain ? (SwapChain) o : null;
        } catch (Throwable t) {
            Log.w(TAG, "fetchSwapChain: 反射失败，相机抖动渲染将退化", t);
            mSwapChainFieldFailed = true;
            return null;
        }
    }

    /**
     * 抖动波形：多频正弦叠加近似平滑噪声（不可预测也不突兀）
     */
    private static float shakeWave(float t, float seed) {
        return (float) (Math.sin(t + seed)
                * 0.5 + Math.sin(t * 1.9 + seed * 2.1) * 0.3
                + Math.sin(t * 3.1 + seed * 4.7) * 0.2);
    }

    /**
     * 抖动强度向目标渐变：速度 > 0.05 时满幅，否则归零
     */
    private void updateShake(long frameTimeNanos, float currentSpeed) {
        float dt = 0f;
        if (mShakeLastFrameNs > 0) {
            dt = (frameTimeNanos - mShakeLastFrameNs) / 1_000_000_000f;
        }
        mShakeLastFrameNs = frameTimeNanos;
        if (dt <= 0f) {
            return;
        }
        dt = Math.min(dt, DriveSystem.MAX_FRAME_DT);
        float target = currentSpeed > 0.05f ? 1f : 0f;
        mShakeLevel += (target - mShakeLevel) * (1f - (float) Math.exp(-4f * dt));
        if (target == 0f && mShakeLevel < 0.002f) {
            mShakeLevel = 0f;
        }
    }
}
