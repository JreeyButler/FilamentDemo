package com.imotor.filamentdemo;

import android.annotation.SuppressLint;
import android.content.Context;

import android.content.res.AssetManager;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View.OnTouchListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.filament.Box;
import com.google.android.filament.Engine;
import com.google.android.filament.Entity;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Filament;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.LightManager;
import com.google.android.filament.Material;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.RenderableManager;
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
    /**
     * 地面 Y 坐标，用于限制相机不低于地面
     */
    private float mGroundY = 0f;
    /**
     * 相机操控器，用于读取当前相机位置
     */
    private Manipulator mManipulator;

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
                .groundPlane(0, 0, 1, 0)
                .build(Manipulator.Mode.ORBIT);
        mManipulator = manipulator;
        mModelViewer = new ModelViewer(this, mEngine, helper, manipulator);
        // 自定义触摸处理，拦截会导致相机穿地的手势
        setOnTouchListener(new CameraGestureListener());

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

        addCarLight();
        addGround(mEngine, mModelViewer.getScene());

        new Thread(this::showModelInfo).start();

        Choreographer.getInstance().postFrameCallback(mFrameScheduler);
    }

    private void addGround(Engine engine, Scene scene) {
        // 加载地面阴影材质
        AssetManager assetManager = getContext().getAssets();
        ByteBuffer matBuffer;
        try (InputStream in = assetManager.open("groundShadow.filamat")) {
            byte[] bytes = new byte[in.available()];
            int length = in.read(bytes);
            matBuffer = ByteBuffer.allocateDirect(length);
            matBuffer.put(bytes, 0, length);
            matBuffer.rewind();
        } catch (IOException e) {
            Log.e(TAG, "addGround: 加载材质失败", e);
            return;
        }
        Log.d(TAG, "addGround: 材质加载成功，大小=" + matBuffer.remaining());
        Material shadowMaterial;
        try {
            shadowMaterial = new Material.Builder()
                    .payload(matBuffer, matBuffer.remaining())
                    .build(engine);
        } catch (Exception e) {
            Log.e(TAG, "addGround: 创建材质失败，跳过地面创建", e);
            return;
        }
        MaterialInstance shadowInstance = shadowMaterial.getDefaultInstance();
        AutomationEngine automationEngine = new AutomationEngine();
        AutomationEngine.ViewerOptions options = automationEngine.getViewerOptions();
        // strength 是材质自定义参数，控制阴影强度
        shadowInstance.setParameter("strength", options.groundShadowStrength);

        // 读取模型包围盒，计算轮子底部 Y 坐标，使地面刚好贴合轮子
        float groundY = 0f;
        FilamentAsset asset = mModelViewer.getAsset();
        if (asset != null) {
            Box boundingBox = asset.getBoundingBox();
            float[] center = boundingBox.getCenter();
            float[] halfExtent = boundingBox.getHalfExtent();
            groundY = center[1] - halfExtent[1];
            mGroundY = groundY;
        } else {
            Log.e(TAG, "addGround: 模型资源为 null，无法获取包围盒");
        }

        int groundEntity = GroundFactory.createGroundPlane(engine, scene, shadowInstance, 10, 10, 10, groundY);
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
                .color(1.0f, 0.95f, 0.8f)
                .intensity(150_000.0f)
                .castShadows(true)
                // 内外锥角（度）
                .spotLightCone(5.0f, 25.0f)
                // 指向前下方
                .direction(3F, 0f, 0)
                .position(3F, 0, 0)
                .falloff(20F)
                .build(engine, spotLight);

        setEntityPosition(spotLight, engine, -0.8f, 0.5f, 2.0f);

        setEntityDirection(spotLight, engine, 0f, 0f, 1f);

        scene.addEntity(spotLight);
    }

    private void setEntityPosition(@Entity int entity, Engine engine, float x, float y, float z) {
        TransformManager tm = engine.getTransformManager();
        int inst = tm.getInstance(entity);
        if (inst != 0) {
            float[] matrix = new float[16];
            Matrix.setIdentityM(matrix, 0);
            Matrix.translateM(matrix, 0, x, y, z);
            tm.setTransform(inst, matrix);
        }
    }

    private void setEntityDirection(@Entity int entity, Engine engine, float dx, float dy, float dz) {
        TransformManager tm = engine.getTransformManager();
        int inst = tm.getInstance(entity);
        if (inst != 0) {
            float[] rot = new float[16];
            Matrix.setLookAtM(rot, 0, 0, 0, 0, dx, dy, dz, 0, 1, 0);
            tm.setTransform(inst, rot);
        }
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
            light.setIntensity(50_000F);
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
     * 自定义相机手势处理器。
     * 复刻 Filament GestureDetector 的逻辑，并在 ORBIT 手势的 grabUpdate 前
     * 检查相机高度，阻止相机下压到地面以下。
     */
    @SuppressLint("ClickableViewAccessibility")
    private class CameraGestureListener implements OnTouchListener {

        private Gesture mCurrentGesture = Gesture.NONE;
        // 上一次触摸坐标（Filament 坐标系：Y 轴朝上）
        private float mPrevX0, mPrevY0, mPrevX1, mPrevY1;
        private int mPrevCount;
        // 用于手势识别的候选事件计数
        private int mOrbitCount, mPanCount, mZoomCount;
        private float mTentativeX, mTentativeY;
        private float mTentativeSep;

        private static final int CONFIDENCE_COUNT = 2;
        private static final float PAN_CONFIDENCE_DIST = 4f;
        private static final float ZOOM_CONFIDENCE_DIST = 10f;
        private static final float ZOOM_SPEED = 1f / 10f;
        // 相机最低高度限制：地面 Y + 此偏移量
        private static final float CAMERA_MIN_HEIGHT_OFFSET = 1.0f;

        @Override
        public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
            int height = getHeight();
            // 转换为 Filament 坐标系（Y 轴朝上）
            float x0 = event.getPointerCount() >= 1 ? event.getX(0) : 0;
            float y0 = event.getPointerCount() >= 1 ? height - event.getY(0) : 0;
            float x1 = event.getPointerCount() >= 2 ? event.getX(1) : x0;
            float y1 = event.getPointerCount() >= 2 ? height - event.getY(1) : y0;
            int count = Math.min(event.getPointerCount(), 2);
            float midX = (x0 + x1) / 2f;
            float midY = (y0 + y1) / 2f;
            float sep = (float) Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0));

            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    // 指针数变化时重置手势
                    if (mCurrentGesture != Gesture.NONE) {
                        mManipulator.grabEnd();
                        mCurrentGesture = Gesture.NONE;
                    }
                    mOrbitCount = 0;
                    mPanCount = 0;
                    mZoomCount = 0;
                    mPrevX0 = x0;
                    mPrevY0 = y0;
                    mPrevX1 = x1;
                    mPrevY1 = y1;
                    mPrevCount = count;
                    break;

                case android.view.MotionEvent.ACTION_MOVE:
                    // 指针数不匹配时取消手势
                    if ((count != 1 && mCurrentGesture == Gesture.ORBIT) ||
                            (count != 2 && mCurrentGesture == Gesture.PAN) ||
                            (count != 2 && mCurrentGesture == Gesture.ZOOM)) {
                        mManipulator.grabEnd();
                        mCurrentGesture = Gesture.NONE;
                        break;
                    }

                    if (mCurrentGesture == Gesture.ZOOM) {
                        float prevSep = (float) Math.sqrt(
                                (mPrevX1 - mPrevX0) * (mPrevX1 - mPrevX0) +
                                        (mPrevY1 - mPrevY0) * (mPrevY1 - mPrevY0));
                        mManipulator.scroll((int) midX, (int) midY, (prevSep - sep) * ZOOM_SPEED);
                        mPrevX0 = x0;
                        mPrevY0 = y0;
                        mPrevX1 = x1;
                        mPrevY1 = y1;
                        break;
                    }

                    if (mCurrentGesture == Gesture.ORBIT || mCurrentGesture == Gesture.PAN) {
                        // ORBIT 手势：检查是否会导致相机穿地
                        if (mCurrentGesture == Gesture.ORBIT) {
                            float[] pos = mModelViewer.getCamera().getPosition(new float[3]);
                            float dy = y0 - mPrevY0;
                            if (pos[1] <= mGroundY + CAMERA_MIN_HEIGHT_OFFSET && dy > 0) {
                                // 相机已在地面限制高度，且手指向上滑（下压相机），拦截
                                // 重置 grabBegin 防止 Manipulator 内部积累偏移导致松手后大幅跳动
                                mManipulator.grabEnd();
                                mManipulator.grabBegin((int) x0, (int) y0, false);
                                mPrevX0 = x0;
                                mPrevY0 = y0;
                                break;
                            }
                        }
                        mManipulator.grabUpdate((int) midX, (int) midY);
                        mPrevX0 = x0;
                        mPrevY0 = y0;
                        mPrevX1 = x1;
                        mPrevY1 = y1;
                        break;
                    }

                    // 手势识别阶段
                    if (count == 1) {
                        mOrbitCount++;
                        mTentativeX = x0;
                        mTentativeY = y0;
                    }
                    if (count == 2) {
                        mPanCount++;
                        mZoomCount++;
                        mTentativeX = midX;
                        mTentativeY = midY;
                        mTentativeSep = sep;
                    }

                    if (mOrbitCount >= CONFIDENCE_COUNT) {
                        mManipulator.grabBegin((int) x0, (int) y0, false);
                        mCurrentGesture = Gesture.ORBIT;
                    } else if (mZoomCount >= CONFIDENCE_COUNT &&
                            Math.abs(sep - mTentativeSep) > ZOOM_CONFIDENCE_DIST) {
                        mCurrentGesture = Gesture.ZOOM;
                        mPrevX0 = x0;
                        mPrevY0 = y0;
                        mPrevX1 = x1;
                        mPrevY1 = y1;
                    } else if (mPanCount >= CONFIDENCE_COUNT) {
                        float dx = midX - mTentativeX;
                        float dy = midY - mTentativeY;
                        if ((float) Math.sqrt(dx * dx + dy * dy) > PAN_CONFIDENCE_DIST) {
                            mManipulator.grabBegin((int) midX, (int) midY, true);
                            mCurrentGesture = Gesture.PAN;
                        }
                    }
                    break;

                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    mManipulator.grabEnd();
                    mCurrentGesture = Gesture.NONE;
                    mOrbitCount = 0;
                    mPanCount = 0;
                    mZoomCount = 0;
                    break;
            }
            return true;
        }

        /**
         * 判断相机是否已经接近地面限制
         */
        private boolean isCameraAtGroundLimit() {
            if (mManipulator == null) return false;
            float[] eye = new float[3];
            float[] target = new float[3];
            float[] up = new float[3];
            mManipulator.getLookAt(eye, target, up);
            return eye[1] <= mGroundY + 0.3f;
        }
    }

    private enum Gesture {NONE, ORBIT, PAN, ZOOM}

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
        private final HashMap<Integer, Boolean> doorOpenStatus = new HashMap<>(16);

        @Override
        public void openDoor(int doorIndex) {
            Log.d(TAG, "openDoor: " + doorIndex);
            if (isOpened(doorIndex)) {
                return;
            }
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
            doorOpenStatus.put(doorIndex, true);
        }

        @Override
        public void closeDoor(int doorIndex) {
            Log.d(TAG, "closeDoor: " + doorIndex);
            if (isOpened(doorIndex)) {
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
                doorOpenStatus.put(doorIndex, false);
            }
        }

        public boolean isOpened(int index) {
            Boolean b = doorOpenStatus.get(index);
            return b != null && b;
        }
    };
}
