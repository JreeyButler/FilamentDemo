package com.imotor.filamentdemo;

import android.annotation.SuppressLint;
import android.content.Context;

import android.opengl.Matrix;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceView;

import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Filament;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.LightManager;
import com.google.android.filament.Scene;
import com.google.android.filament.Skybox;
import com.google.android.filament.TransformManager;
import com.google.android.filament.View;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.gltfio.Animator;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.Gltfio;
import com.google.android.filament.utils.AutomationEngine;
import com.google.android.filament.utils.Float3;
import com.google.android.filament.utils.KTX1Loader;
import com.google.android.filament.utils.Manipulator;
import com.google.android.filament.utils.ModelViewer;
import com.google.android.filament.utils.Utils;

import java.io.IOException;
import java.io.InputStream;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Yan.Liangliang
 * @date 2025/6/11 下午3:35
 */
public class FilamentView2 extends SurfaceView {
    private static final String TAG = FilamentView2.class.getSimpleName();
    private static final boolean LOOP_ANIMATION = false;
    private ModelViewer mModelViewer;
    private Choreographer choreographer;
    private Engine mEngine;
    private Animator mAnimator;
    private final AutomationEngine automationEngine = new AutomationEngine();
    private final FrameCallback mFrameScheduler = new FrameCallback();
    /**
     * 左门序号
     */
    private static final int[] LEFT_DOOR_ENTITIES = {38, 39, 40};
    private static final int[] RIGHT_DOOR_ENTITIES = {54, 55, 56};
    private Map<Integer, Map<Integer, float[]>> mMatrixMap;

    static {
        Filament.init();
        Gltfio.init();
        Utils.INSTANCE.init();
    }

    public FilamentView2(Context context) {
        this(context, null);
    }

    public FilamentView2(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FilamentView2(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void init() {
        mEngine = Engine.create();
        choreographer = Choreographer.getInstance();
        UiHelper helper = new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK);
        Manipulator manipulator = new Manipulator.Builder()
                // 相机瞄准的点
                .targetPosition(0, 0, 0)
                // 初始点
                .orbitHomePosition(0, 0, 10)
                .zoomSpeed(0.1f)
                .viewport(getWidth(), getHeight())
                .build(Manipulator.Mode.ORBIT);
        mModelViewer = new ModelViewer(this, mEngine, helper, manipulator);
        // 触摸移动相机视角
        setOnTouchListener((v, event) -> {
            mModelViewer.onTouchEvent(event);
            return true;
        });

        View view = mModelViewer.getView();
        view.setShadowingEnabled(true);
        View.RenderQuality quality = view.getRenderQuality();
        quality.hdrColorBuffer = View.QualityLevel.MEDIUM;
        view.setRenderQuality(quality);

        View.DynamicResolutionOptions options = view.getDynamicResolutionOptions();
        options.enabled = true;
        options.quality = View.QualityLevel.MEDIUM;
        view.setDynamicResolutionOptions(options);

        // 加载模型
        loadModel();
        // 环境光
        loadIBL();

        getDefaultMatrix();

//        addCarLight();

        new Thread(this::showModelInfo).start();

        Choreographer.getInstance().postFrameCallback(mFrameScheduler);
    }

    private void getDefaultMatrix() {
        mMatrixMap = new HashMap<>(2);
        TransformManager tm = mModelViewer.getEngine().getTransformManager();
        HashMap<Integer, float[]> hashMap = new HashMap<>();
        for (int entity : LEFT_DOOR_ENTITIES) {
            float[] currentTransform = new float[16];
            int instance = tm.getInstance(entity);
            tm.getTransform(instance, currentTransform);
            hashMap.put(instance, currentTransform);
        }
        mMatrixMap.put(DoorController.FRONT_LEFT_DOOR, hashMap);
        hashMap = new HashMap<>();
        for (int entity : RIGHT_DOOR_ENTITIES) {
            int instance = tm.getInstance(entity);
            float[] transform = new float[16];
            tm.getTransform(instance, transform);
            hashMap.put(instance, transform);
        }
        mMatrixMap.put(DoorController.FRONT_RIGHT_DOOR, hashMap);
    }

    private void showModelInfo() {
        FilamentAsset asset = mModelViewer.getAsset();
        if (asset == null) {
            return;
        }
        int[] entities = asset.getEntities();
        for (int entity : entities) {
            String name = asset.getName(entity);
            Log.d(TAG, "showModelInfo: " + name);
            if (name.contains("door")) {
                Log.d(TAG, "showModelInfo: entity = " + entity);
            }
        }

        mAnimator = mModelViewer.getAnimator();
        int animationCount = mAnimator == null ? 0 : mAnimator.getAnimationCount();
        Log.d(TAG, "init: count = " + animationCount);
        for (int i = 0; i < animationCount; i++) {
            String name = mAnimator.getAnimationName(i);
            Log.d(TAG, "init: " + name);
        }
    }

    private void addCarLight() {
        int spotLight = EntityManager.get().create();

        Engine engine = mModelViewer.getEngine();
        Scene scene = mModelViewer.getScene();

        new LightManager.Builder(LightManager.Type.FOCUSED_SPOT)
                // 白
                .color(1.0f, 1.0f, 1f)
                .intensity(150_000.0f)
                .castShadows(true)
                // 内外锥角（度）
                .spotLightCone(5.0f, 25.0f)
                // 指向前下方
                .direction(3F, 0f, 0)
                .position(3F, 0, 0)
                .build(engine, spotLight);

//        // 设置位置
//        TransformManager tm = engine.getTransformManager();
//        int ti = tm.getInstance(spotLight);
//
//        float[] matrix = new float[16];
//        Matrix.setIdentityM(matrix, 0);
//
//        // 比如车头偏上位置
//        Matrix.translateM(matrix, 0, -10, 10, 10);

//        tm.setTransform(ti, matrix);

        scene.addEntity(spotLight);
    }

    private void loadIBL() {
        Engine engine = mModelViewer.getEngine();
        Scene scene = mModelViewer.getScene();
        Context context = getContext();
        if (mEngine == null) {
            return;
        }

        try (InputStream iblIs = context.getAssets().open("neutral/neutral_ibl.ktx");
             InputStream skyboxIs = context.getAssets().open("neutral/env_skybox.ktx")) {
            KTX1Loader iblLoader = KTX1Loader.INSTANCE;
            KTX1Loader.Options options = new KTX1Loader.Options();

            // 设置环境光
            byte[] buffer = new byte[iblIs.available()];
            int length = iblIs.read(buffer);
            IndirectLight light = iblLoader.createIndirectLight(engine, ByteBuffer.wrap(buffer, 0, length), options);
            light.setIntensity(30_000F);
            scene.setIndirectLight(light);

            // 设置天空盒
            buffer = new byte[skyboxIs.available()];
            length = skyboxIs.read(buffer);
            Skybox skybox = iblLoader.createSkybox(engine, ByteBuffer.wrap(buffer, 0, length), options);
            scene.setSkybox(skybox);

            // 设置纯色的天空盒
//            Skybox skybox1 = new Skybox.Builder().color(0.5f, 0.5f, 0.5f, 0.5f).build(mEngine);
//            scene.setSkybox(skybox1);
        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    /**
     * 模型加载
     */
    private void loadModel() {
        // red_car.glb cartoon_sports_car.glb
        try (InputStream is = getContext().getAssets().open("models/cartoon_sports_car.glb")) {
            byte[] bytes = new byte[is.available()];
            int length = is.read(bytes);
            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length);
            mModelViewer.loadModelGlb(buffer);
            updateRootTransform();
        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    private void updateRootTransform() {
        if (automationEngine.getViewerOptions().autoInstancingEnabled) {
            mModelViewer.transformToUnitCube(new Float3(0, 0, 10f));
        } else {
            mModelViewer.clearRootTransform();
        }
    }

    public DoorController getDoorController() {
        return doorController;
    }

    /**
     * 渲染回调
     */
    private final class FrameCallback implements Choreographer.FrameCallback {
        private Long startTime;

        @Override
        public void doFrame(long frameTimeNanos) {

            if (mAnimator != null && LOOP_ANIMATION) {
                // 循环播放模型的动画
                startTime = startTime == null ? frameTimeNanos : startTime;
                float duration = (frameTimeNanos - startTime) / 1_000_000_000F;
                mAnimator.applyAnimation(0, duration);
                mAnimator.updateBoneMatrices();
            }

            choreographer.postFrameCallback(this);
            mModelViewer.render(frameTimeNanos);
        }
    }

    private final DoorController doorController = new DoorController() {


        @Override
        public void openDoor(int doorIndex) {
            Log.d(TAG, "openDoor: " + doorIndex);
            TransformManager manager = mModelViewer.getEngine().getTransformManager();
            int[] entities = doorIndex == FRONT_LEFT_DOOR ? LEFT_DOOR_ENTITIES : RIGHT_DOOR_ENTITIES;
            for (int entity : entities) {
                int instance = manager.getInstance(entity);

                float[] currentTransform = new float[16];
                manager.getTransform(instance, currentTransform);

                // 创建并初始化旋转矩阵
                float[] rotationMatrix = new float[16];
                Matrix.setIdentityM(rotationMatrix, 0);

                // 定义旋转参数
                float angleDeg = doorIndex == FRONT_LEFT_DOOR ? -35f : 35;
                // Y轴旋转
                Matrix.rotateM(rotationMatrix, 0, angleDeg, 0, 1, 0);

                // 定义铰链偏移量，此处为X轴的局部坐标值
                float hingeX = doorIndex == FRONT_LEFT_DOOR ? 0.05f : -0.05f;
                // 平移到铰链位置
                Matrix.translateM(currentTransform, 0, -hingeX, 0, 0);
                // 矩阵合并，即将旋转矩阵与源矩阵合并
                Matrix.multiplyMM(currentTransform, 0, currentTransform, 0, rotationMatrix, 0);
                // 平移回去
                Matrix.translateM(currentTransform, 0, hingeX, 0, 0);

                manager.setTransform(instance, currentTransform);
            }
        }

        @Override
        public void closeDoor(int doorIndex) {
            Log.d(TAG, "closeDoor: " + doorIndex);
            TransformManager manager = mModelViewer.getEngine().getTransformManager();
            Map<Integer, float[]> sourceMap = mMatrixMap.get(doorIndex);
            int[] entities = doorIndex == FRONT_LEFT_DOOR ? LEFT_DOOR_ENTITIES : RIGHT_DOOR_ENTITIES;
            for (int entity : entities) {
                int instance = manager.getInstance(entity);
                float[] transform = sourceMap == null ? null : sourceMap.get(instance);
                if (transform != null) {
                    manager.setTransform(instance, transform);
                }
            }
        }
    };
}
