package com.imotor.filamentdemo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceView;

import com.google.android.filament.Box;
import com.google.android.filament.Engine;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.Material;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Scene;
import com.google.android.filament.Skybox;
import com.google.android.filament.TransformManager;
import com.google.android.filament.View;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.utils.AutomationEngine;
import com.google.android.filament.utils.Float3;
import com.google.android.filament.utils.KTX1Loader;
import com.google.android.filament.utils.Manipulator;
import com.google.android.filament.utils.ModelViewer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 展厅车模渲染入口：负责装配各子系统与帧调度。
 * 具体职责拆分到独立模块：
 * - {@link ShowroomFx}      开场渐亮动画 + 主光/轮廓光/灯条
 * - {@link DriveSystem}     行驶（速度状态 + 轮子自转）
 * - {@link SpeedLinesFX}    速度光束隧道
 * - {@link CarLightSystem}  车头灯
 * - {@link RenderPipeline}  自管渲染（绕开 ModelViewer 相机同步焊死的绘制流程）
 * - {@link CameraGestureListener}  相机手势
 * 本类负责：装配、渲染帧调度（含省电三段式）、公共 API 转发。
 *
 * @author Yan.Liangliang
 * @date 2025/6/11 下午3:35
 */
public class FilamentView2 extends SurfaceView {
    private static final String TAG = FilamentView2.class.getSimpleName();
    private static final boolean LOOP_ANIMATION = false;
    private ModelViewer mModelViewer;
    private Choreographer choreographer;
    private Engine mEngine;
    private com.google.android.filament.gltfio.Animator mAnimator;
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
    /**
     * lit 材质，供地面和调试辅助共用
     */
    private Material mLitMaterial;
    /**
     * 调试辅助，生产环境设为 false
     */
    private static final boolean DEBUG_SHOW_AXES = false;
    private DebugHelper mDebugHelper;

    // ── 子模块 ────────────────────────────────────────────────────────────
    private EmissiveQuadFactory mQuadFactory;
    private ShowroomFx mShowroomFx;
    private DriveSystem mDrive;
    private SpeedLinesFX mSpeedLines;
    private CarLightSystem mCarLights;
    private RenderPipeline mRenderPipeline;
    /**
     * 最近一次光束可见根数（省电调度判定用）
     */
    private int mLastVisibleBeams = 0;

    static {
        com.google.android.filament.Filament.init();
        com.google.android.filament.gltfio.Gltfio.init();
        com.google.android.filament.utils.Utils.INSTANCE.init();
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
        mRenderPipeline = new RenderPipeline(mModelViewer, mEngine, manipulator);
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
        // 收紧动态分辨率的降档下限：默认低端机会一路缩到 ~0.3x 造成"糊成一片"；
        // 宁可帧率略降（特效可再拆分关闭），保持画面基本清晰
        options.minScale = 0.7f;
        options.maxScale = 1.0f;
        view.setDynamicResolutionOptions(options);

        // ── 展厅级画面设置（ACES/Bloom/SSR/抗锯齿）──
        applyShowRoomLook(view);

        // 加载模型
        loadModel();
        // 车漆清漆层
        applyCarClearCoat();

        getDefaultMatrix();

        // 收集轮子实体（行驶功能）
        mDrive = new DriveSystem(mEngine);
        mDrive.collectWheels(mModelViewer.getAsset());

        addGround(mEngine, mModelViewer.getScene());
        // 发光四边形工厂（灯条/速度光束共用）
        mQuadFactory = EmissiveQuadFactory.create(getContext(), mEngine);
        // 车灯在模型包围盒计算完成后（addGround 内已读取包围盒）再创建
        mCarLights = new CarLightSystem(mEngine, mModelViewer.getScene(), mGroundY);
        mCarLights.setup();

        // ── 展厅氛围：主光/轮廓光 + 自发光灯条 ──
        mShowroomFx = new ShowroomFx(mEngine, mModelViewer.getScene(), view, mQuadFactory, mGroundY);
        mShowroomFx.setupLights();
        mShowroomFx.setupLightBars();
        // IBL 装入展厅模块（intro 需要），并挂上暗场初值
        loadIBL();

        // 速度光束
        mSpeedLines = new SpeedLinesFX(mEngine, mModelViewer.getScene(), mQuadFactory, mGroundY);
        mSpeedLines.build();

        // 调试标记：黄色=灯位，橙色=地面落点（光轴延长线），绿/红=车鼻/车尾（默认关闭）
        setupDebugAxesIfEnabled();

        new Thread(this::showModelInfo).start();

        Choreographer.getInstance().postFrameCallback(mFrameScheduler);
    }

    /**
     * 车灯光轴校准辅助标记（保留自原 addCarLight，生产环境关闭）
     */
    private void setupDebugAxesIfEnabled() {
        if (!DEBUG_SHOW_AXES || mLitMaterial == null) {
            return;
        }
        Scene scene = mModelViewer.getScene();
        mDebugHelper = new DebugHelper(mEngine, scene, mLitMaterial);
        mDebugHelper.addAxes(0, 0, 0, 6f);
        Log.d(TAG, "setupDebugAxesIfEnabled: 已创建调试辅助");
    }

    // ── 展厅级画面设置 ────────────────────────────────────────────────────

    /**
     * SSR 反射开关（部分低端机性能吃紧，可整体关掉）
     */
    private static final boolean SHOWROOM_SSR_ENABLED = true;

    /**
     * 展厅级调校：ACES 色调映射、Bloom 辉光、SSR 屏幕空间反射、MSAA 抗锯齿。
     * Bloom 的开场渐亮由 ShowroomFx 接管。
     */
    private void applyShowRoomLook(View view) {
        // ACES：高光有自然滚降，暗部更扎实，整体更"电影感"
        view.setToneMapping(View.ToneMapping.ACES);

        // SSR：地面（低 roughness 材质）反射车身
        if (SHOWROOM_SSR_ENABLED) {
            View.ScreenSpaceReflectionsOptions ssr = new View.ScreenSpaceReflectionsOptions();
            ssr.enabled = true;
            ssr.maxDistance = 4.0f;
            ssr.thickness = 0.1f;
            ssr.bias = 0.02f;
            ssr.stride = 2.0f;
            view.setScreenSpaceReflectionsOptions(ssr);
        }

        // MSAA 抗锯齿，边缘更干净
        View.MultiSampleAntiAliasingOptions msaa = new View.MultiSampleAntiAliasingOptions();
        msaa.enabled = true;
        view.setMultiSampleAntiAliasingOptions(msaa);
    }

    /**
     * 给 glb 车漆材质追加清漆层（clearcoat）。
     * 仅对具备 clearCoat 参数的材质实例生效；不含该参数的实例会静默告警并跳过。
     */
    private void applyCarClearCoat() {
        FilamentAsset asset = mModelViewer.getAsset();
        if (asset == null) {
            return;
        }
        Engine engine = mModelViewer.getEngine();
        RenderableManager rm = engine.getRenderableManager();
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
                // hasParameter 为 false 时直接跳过：setParameter 对未知参数会触发 native panic
                if (!mi.getMaterial().hasParameter("clearCoat")
                        || !mi.getMaterial().hasParameter("clearCoatRoughness")) {
                    continue;
                }
                mi.setParameter("clearCoat", 1.0f);
                mi.setParameter("clearCoatRoughness", 0.08f);
            }
        }
    }

    private void addGround(Engine engine, Scene scene) {
        AssetManager assetManager = getContext().getAssets();

        // 读取模型包围盒，计算轮子底部 Y 坐标
        float groundY = 0f;
        float[] halfExtent = {10f, 1f, 10f}; // 默认值
        FilamentAsset asset = mModelViewer.getAsset();
        if (asset != null) {
            Box boundingBox = asset.getBoundingBox();
            float[] center = boundingBox.getCenter();
            halfExtent = boundingBox.getHalfExtent();
            groundY = center[1] - halfExtent[1];
            mGroundY = groundY;
        } else {
            Log.e(TAG, "addGround: 模型资源为 null，无法获取包围盒");
        }

        // ── 1. lit 不透明地面（接受动态光照，显示车灯光斑）────────────────
        try (InputStream in = assetManager.open("lit.filamat")) {
            byte[] bytes = new byte[in.available()];
            int length = in.read(bytes);
            ByteBuffer buf = ByteBuffer.allocateDirect(length);
            buf.put(bytes, 0, length);
            buf.rewind();
            Material litMaterial = new Material.Builder().payload(buf, buf.remaining()).build(engine);
            mLitMaterial = litMaterial; // 保存供调试辅助使用
            MaterialInstance litInstance = litMaterial.getDefaultInstance();
            // 展厅深色地板：暗色哑光基色 + 低 roughness，主反射交给 SSR（映出车身）
            // metallic 设 0，避免中性 IBL 把地面照成亮银色
            litInstance.setParameter("baseColor", 0.03f, 0.032f, 0.042f);
            litInstance.setParameter("roughness", 0.30f);
            litInstance.setParameter("metallic", 0.0f);
            GroundFactory.createLitGroundPlane(engine, mModelViewer.getScene(), litInstance,
                    halfExtent[0], halfExtent[2], groundY);
            Log.d(TAG, "addGround: lit 地面创建成功");
        } catch (Exception e) {
            Log.e(TAG, "addGround: lit 地面创建失败", e);
        }
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

    private void loadIBL() {
        Engine engine = mModelViewer.getEngine();
        Scene scene = mModelViewer.getScene();
        Context context = getContext();
        if (mEngine == null) {
            return;
        }

        try (InputStream iblIs = context.getAssets().open("neutral/neutral_ibl.ktx")) {
            KTX1Loader iblLoader = KTX1Loader.INSTANCE;
            KTX1Loader.Options options = new KTX1Loader.Options();

            // 设置环境光
            byte[] buffer = new byte[iblIs.available()];
            int length = iblIs.read(buffer);
            IndirectLight light = iblLoader.createIndirectLight(engine, ByteBuffer.wrap(buffer, 0, length), options);
            // 环境光：目标 30k lux，初始 0 由入场动画渐亮
            if (mShowroomFx != null) {
                mShowroomFx.setIndirectLight(light);
            }
            light.setIntensity(0f);
            scene.setIndirectLight(light);

            // 天空盒：暗房纯色（深黑微带蓝灰），突出灯条与车身
            Skybox skybox = new Skybox.Builder()
                    .color(0.012f, 0.014f, 0.02f, 1f)
                    .build(mEngine);
            scene.setSkybox(skybox);
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

    // ── 公共 API（转发到子模块 + 唤醒省电调度）────────────────────────────

    public void setTargetSpeed(float speed) {
        mDrive.setTargetSpeed(speed);
        wakeUp();
    }

    /**
     * 以指定速度开始行驶（等效 setTargetSpeed，语义化入口）
     */
    public void startDriving(float speed) {
        setTargetSpeed(speed);
    }

    /**
     * 停止行驶（目标速度归零，轮子渐停）
     */
    public void stopDriving() {
        setTargetSpeed(0f);
    }

    /**
     * 开启或关闭车头前照灯
     */
    public void setFrontLightEnabled(boolean enabled) {
        mCarLights.setFrontLightEnabled(enabled);
        wakeUp();
    }

    public boolean isFrontLightOn() {
        return mCarLights.isFrontLightOn();
    }

    public float[] getLightDirection() {
        return mCarLights.getLightDirection();
    }

    public void adjustLightDirection(float dx, float dy, float dz) {
        mCarLights.adjustLightDirection(dx, dy, dz);
    }

    public void resetLightDirection() {
        mCarLights.resetLightDirection();
    }

    public float[] getLightPosition() {
        return mCarLights.getLightPosition();
    }

    public void adjustLightPosition(float dx, float dy, float dz) {
        mCarLights.adjustLightPosition(dx, dy, dz);
    }

    public void resetLightPosition() {
        mCarLights.resetLightPosition();
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
        // 传给 Manipulator 的虚拟坐标（用于阻尼累积）
        private float mManipX, mManipY;
        private float mManipMidX, mManipMidY;

        private static final int CONFIDENCE_COUNT = 2;
        private static final float PAN_CONFIDENCE_DIST = 4f;
        private static final float ZOOM_CONFIDENCE_DIST = 10f;
        private static final float ZOOM_SPEED = 1f / 20f;      // 降低缩放灵敏度（原 1/10）
        // 相机最低高度限制：地面 Y + 此偏移量
        private static final float CAMERA_MIN_HEIGHT_OFFSET = 1.0f;
        // 旋转/平移阻尼系数：0=完全不动，1=无阻尼，值越小滑动越慢
        private static final float ORBIT_DAMPING = 0.25f;
        private static final float PAN_DAMPING = 0.25f;

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
                    // 手势开始：保持满帧渲染（深睡时同时恢复调度）
                    mTouchActive = true;
                    wakeUp();
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
                        if (mCurrentGesture == Gesture.ORBIT) {
                            // ORBIT 手势：检查是否会导致相机穿地
                            float[] pos = mModelViewer.getCamera().getPosition(new float[3]);
                            float dy = y0 - mPrevY0;
                            if (pos[1] <= mGroundY + CAMERA_MIN_HEIGHT_OFFSET && dy > 0) {
                                // 相机已在地面限制高度，且手指向上滑（下压相机），拦截
                                // 重置 grabBegin 防止 Manipulator 内部积累偏移导致松手后大幅跳动
                                mManipulator.grabEnd();
                                mManipulator.grabBegin((int) x0, (int) y0, false);
                                mManipX = x0;
                                mManipY = y0;
                                mPrevX0 = x0;
                                mPrevY0 = y0;
                                break;
                            }
                            // 应用旋转阻尼：只传入实际位移的一部分，使旋转更平滑缓慢
                            mManipX += (x0 - mPrevX0) * ORBIT_DAMPING;
                            mManipY += (y0 - mPrevY0) * ORBIT_DAMPING;
                            mManipulator.grabUpdate((int) mManipX, (int) mManipY);
                        } else {
                            // PAN 手势：应用平移阻尼
                            float prevMidX = (mPrevX0 + mPrevX1) / 2f;
                            float prevMidY = (mPrevY0 + mPrevY1) / 2f;
                            mManipMidX += (midX - prevMidX) * PAN_DAMPING;
                            mManipMidY += (midY - prevMidY) * PAN_DAMPING;
                            mManipulator.grabUpdate((int) mManipMidX, (int) mManipMidY);
                        }
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
                        mManipX = x0;
                        mManipY = y0;
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
                            mManipMidX = midX;
                            mManipMidY = midY;
                            mCurrentGesture = Gesture.PAN;
                        }
                    }
                    break;

                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    // 手势结束：保持一段满帧余量，覆盖操纵器阻尼收尾再降频
                    mTouchActive = false;
                    mInteractHoldUntilMs = SystemClock.uptimeMillis() + INTERACT_HOLD_MS;
                    mManipulator.grabEnd();
                    mCurrentGesture = Gesture.NONE;
                    mOrbitCount = 0;
                    mPanCount = 0;
                    mZoomCount = 0;
                    break;
            }
            return true;
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
            if (mShowroomFx.isIntroPlaying()) {
                mShowroomFx.updateIntro(frameTimeNanos);
            }

            if (mAnimator != null && LOOP_ANIMATION) {
                // 循环播放模型的动画
                startTime = startTime == null ? frameTimeNanos : startTime;
                float duration = (frameTimeNanos - startTime) / 1_000_000_000F;
                mAnimator.applyAnimation(0, duration);
                mAnimator.updateBoneMatrices();
            }

            // 行驶驱动（轮子自转）
            mDrive.update(frameTimeNanos);

            // 行驶氛围：随速度非线性压暗环境光/主光/灯条，突出自发光光束
            mShowroomFx.updateDriveMood(mDrive.getCurrentSpeed());

            // 速度光束 + 相机抖动
            mLastVisibleBeams = mSpeedLines.update(frameTimeNanos, mDrive.getCurrentSpeed());
            mRenderPipeline.update(frameTimeNanos, mDrive.getCurrentSpeed());

            renderFrameScheduled();
        }

        /**
         * 渲染按忙碌/静止三段式调度
         */
        private void renderFrameScheduled() {
            // 三段式调度省电：
            // 1) 场景忙碌（行驶/动画/手势/光束）→ 满帧
            // 2) 静止冷却期 → 10fps 低频渲染
            // 3) 长时间静止 → 停止调度渲染，屏幕保留最后一帧，唤醒后恢复
            if (isSceneBusy()) {
                mIdleSinceMs = 0;
                choreographer.postFrameCallback(this);
            } else {
                long now = SystemClock.uptimeMillis();
                if (mIdleSinceMs == 0) {
                    mIdleSinceMs = now;
                }
                if (now - mIdleSinceMs < LONG_IDLE_THRESHOLD_MS) {
                    choreographer.postFrameCallbackDelayed(this, IDLE_RENDER_INTERVAL_MS);
                }
                // 完全静止：不再投递回调（深睡），有交互/参数变化时经 wakeUp() 恢复
            }
        }

    }

    /**
     * 静止时的渲染间隔（毫秒）：约 10fps 足以维持静态画面的响应感
     */
    private static final long IDLE_RENDER_INTERVAL_MS = 100;
    /**
     * 静止冷却期（毫秒）：进入静止后先按 10fps 渲染这段时长，随后彻底停止调度
     */
    private static final long LONG_IDLE_THRESHOLD_MS = 3000;
    /**
     * 交互停手后保持满帧的余量（毫秒）：覆盖相机手势惯性/阻尼的收尾
     */
    private static final long INTERACT_HOLD_MS = 400;
    /**
     * 进入静止的时刻（SystemClock.uptimeMillis），0 表示当前忙碌；仅帧回调线程访问
     */
    private long mIdleSinceMs = 0;
    /**
     * 手势是否进行中（由触摸监听更新）
     */
    private boolean mTouchActive = false;
    /**
     * 最近一次触摸抬起的截止时刻（SystemClock.uptimeMillis）
     */
    private long mInteractHoldUntilMs = 0;

    /**
     * 深睡唤醒：投递一次新的渲染循环（触摸/参数变化时调用）；
     * 交互余量时间内保持满帧，之后视场景忙碌状态回落
     */
    private void wakeUp() {
        mIdleSinceMs = 0;
        mInteractHoldUntilMs = SystemClock.uptimeMillis() + INTERACT_HOLD_MS;
        choreographer.removeFrameCallback(mFrameScheduler);
        choreographer.postFrameCallback(mFrameScheduler);
    }

    /**
     * 场景是否处于必须在满帧驱动的状态：
     * 开场动画、行驶（轮转/光束/抖动）、模型循环动画、用户手势（含阻尼余量）。
     * 车门/车灯等瞬时变化由空闲低频循环的下一次渲染自然呈现。
     */
    private boolean isSceneBusy() {
        if (mShowroomFx.isIntroPlaying() || mTouchActive) {
            return true;
        }
        if (mDrive.isDrivingRequested() || mDrive.getCurrentSpeed() > 0.01f
                || mLastVisibleBeams > 0) {
            return true;
        }
        if (SystemClock.uptimeMillis() < mInteractHoldUntilMs) {
            return true;
        }
        return false;
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
            wakeUp();
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
                wakeUp();
            }
        }

        public boolean isOpened(int index) {
            Boolean b = doorOpenStatus.get(index);
            return b != null && b;
        }
    };
}
