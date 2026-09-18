package com.imotor.filamentdemo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.SystemClock;

import android.content.res.AssetManager;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceView;

import com.google.android.filament.Box;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.IndexBuffer;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.Filament;
import com.google.android.filament.LightManager;
import com.google.android.filament.Material;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Scene;
import com.google.android.filament.Skybox;
import com.google.android.filament.SwapChain;
import com.google.android.filament.TransformManager;
import com.google.android.filament.VertexBuffer;
import com.google.android.filament.View;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.gltfio.Animator;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.Gltfio;
import com.google.android.filament.gltfio.ResourceLoader;
import com.google.android.filament.utils.AutomationEngine;
import com.google.android.filament.utils.Float3;
import com.google.android.filament.utils.KTX1Loader;
import com.google.android.filament.utils.Manipulator;
import com.google.android.filament.utils.ModelViewer;
import com.google.android.filament.utils.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    /**
     * 前照灯 entity（左右两颗），默认不加入场景（关灯状态）
     */
    private final int[] mFrontLightEntities = {-1, -1};
    private boolean mFrontLightOn = false;
    /**
     * lit 材质，供地面和调试辅助共用
     */
    private Material mLitMaterial;
    /**
     * 调试辅助，生产环境设为 false
     */
    private static final boolean DEBUG_SHOW_AXES = false;
    private DebugHelper mDebugHelper;

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
        // 环境光
        loadIBL();

        getDefaultMatrix();

        // 收集轮子实体（行驶功能）
        collectWheels();

        addGround(mEngine, mModelViewer.getScene());
        // 车灯在模型包围盒计算完成后（addGround 内已读取包围盒）再创建
        addCarLight();

        // ── 展厅氛围：主光/轮廓光 + 自发光灯条 + 速度光束 ──
        addShowroomLights();
        addLightBars();
        buildSpeedLines();

        new Thread(this::showModelInfo).start();

        Choreographer.getInstance().postFrameCallback(mFrameScheduler);
    }

    // ── 展厅级画面设置 ────────────────────────────────────────────────────

    /**
     * SSR 反射开关（部分低端机性能吃紧，可整体关掉）
     */
    private static final boolean SHOWROOM_SSR_ENABLED = true;

    /**
     * 展厅级调校：ACES 色调映射、Bloom 辉光、SSR 屏幕空间反射、MSAA 抗锯齿。
     */
    private void applyShowRoomLook(View view) {
        // ACES：高光有自然滚降，暗部更扎实，整体更"电影感"
        view.setToneMapping(View.ToneMapping.ACES);

        // 内置 Bloom：灯条、车灯、漆面高光形成辉光，是"炫酷感"的来源之一
        // 初始强度为 0，由开场动画渐亮到 INTRO_BLOOM_STRENGTH
        mBloomOptions.enabled = true;
        mBloomOptions.strength = 0f;
        mBloomOptions.threshold = false;
        view.setBloomOptions(mBloomOptions);

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

    // ── 开场"灯光渐亮"动画 ────────────────────────────────────────────────

    private static final float INTRO_DURATION_S = 3.0f;
    /**
     * 开场结束时各光源的目标强度
     */
    private static final float INTRO_KEY_LIGHT = 120_000f;
    private static final float INTRO_RIM_LIGHT = 60_000f;
    private static final float INTRO_IBL = 30_000f;
    private static final float INTRO_BLOOM_STRENGTH = 0.28f;

    private boolean mIntroPlaying = true;
    private long mIntroStartTimeNs = -1;
    private IndirectLight mIndirectLight;
    private final View.BloomOptions mBloomOptions = new View.BloomOptions();
    private LightManager mLights;

    /**
     * hero 化入场：画面从全黑开始，主光/轮廓光/环境光/Bloom 在 3 秒内渐亮。
     */
    private void updateIntro(long frameTimeNanos, Engine engine, View view) {
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
        view.setBloomOptions(mBloomOptions);

        if (p >= 1f) {
            mIntroPlaying = false;
            Log.d(TAG, "updateIntro: 开场灯光渐亮动画结束");
        }
    }

    // ── 展厅主光 / 轮廓光 ─────────────────────────────────────────────────

    private final int[] mShowroomLightEntities = {-1, -1};

    /**
     * 展厅布光：主光（暖白顶光，制造明暗层次）+ 冷色轮廓光（勾出车身边缘）。
     */
    private void addShowroomLights() {
        Engine engine = mModelViewer.getEngine();
        Scene scene = mModelViewer.getScene();

        // 主光：从左前上方压下，车身左前亮、右后暗，形成对比
        // 初期强度 0，入场动画里渐亮
        int key = EntityManager.get().create();
        mShowroomLightEntities[0] = key;
        new LightManager.Builder(LightManager.Type.SUN)
                .color(1.0f, 0.97f, 0.92f)
                .intensity(INTRO_KEY_LIGHT)
                .direction(-0.45f, -0.8f, -0.4f)
                .castShadows(true)
                .build(engine, key);
        mLights = engine.getLightManager();
        mLights.setIntensity(mLights.getInstance(key), 0f);
        scene.addEntity(key);

        // 轮廓光：冷蓝色从右后方逆着打，勾亮车身边缘
        int rim = EntityManager.get().create();
        mShowroomLightEntities[1] = rim;
        new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(0.65f, 0.8f, 1.0f)
                .intensity(INTRO_RIM_LIGHT)
                .direction(0.35f, -0.25f, 0.9f)
                .build(engine, rim);
        mLights.setIntensity(mLights.getInstance(rim), 0f);
        scene.addEntity(rim);
    }

    // ── 自发光灯条（unlit 高亮几何 + Bloom 发光） ─────────────────────────

    /**
     * 自发光材质（emissive.filamat）
     */
    private Material mEmissiveMaterial;
    /**
     * 灯条 entity 列表（两条顶灯带；尾部柔光板已移除，避免遮挡车尾视野）
     */
    private final int[] mLightBarEntities = new int[2];

    /**
     * 展厅灯条：两根车顶上方长条灯带（原尾部冷白柔光板会挡住车尾，已移除），
     * 模拟 su7-replica 的 StartRoom 灯带氛围。
     */
    private void addLightBars() {
        Engine engine = mModelViewer.getEngine();
        Scene scene = mModelViewer.getScene();

        try (InputStream in = getContext().getAssets().open("emissive.filamat")) {
            byte[] bytes = new byte[in.available()];
            int length = in.read(bytes);
            ByteBuffer buf = ByteBuffer.allocateDirect(length);
            buf.put(bytes, 0, length);
            buf.rewind();
            mEmissiveMaterial = new Material.Builder().payload(buf, buf.remaining()).build(engine);
        } catch (Exception e) {
            Log.e(TAG, "addLightBars: emissive 材质加载失败", e);
            return;
        }

        float barY = mGroundY + 3.2f;

        // 顶灯带①（车左侧上方，暖白）
        MaterialInstance bar1 = mEmissiveMaterial.createInstance();
        bar1.setDoubleSided(true);
        bar1.setParameter("glowColor", 1.0f, 0.96f, 0.92f);
        bar1.setParameter("intensity", 18f);
        mLightBarEntities[0] = createEmissiveQuad(engine, scene, bar1,
                0f, barY, 2.6f, 14f, 0.35f, ORIENT_DOWN);

        // 顶灯带②（车右侧上方，暖白）
        MaterialInstance bar2 = mEmissiveMaterial.createInstance();
        bar2.setDoubleSided(true);
        bar2.setParameter("glowColor", 1.0f, 0.96f, 0.92f);
        bar2.setParameter("intensity", 18f);
        mLightBarEntities[1] = createEmissiveQuad(engine, scene, bar2,
                0f, barY, -2.6f, 14f, 0.35f, ORIENT_DOWN);
    }

    private static final int ORIENT_DOWN = 0;       // 水平面，朝下
    private static final int ORIENT_FACING_X = 1;   // 竖平面，法线朝 +X
    private static final int ORIENT_FACING_Z = 2;   // 竖平面，法线朝 +Z（X/Y 展开，速度光束用）

    /**
     * 创建一块自发光四边形（双面渲染）。
     *
     * @param cx/cy/cz 中心点
     * @param w        第一轴尺寸
     * @param h        第二轴尺寸
     * @param orient   ORIENT_DOWN（XY 内容沿 X/Z 展开）、
     *                 ORIENT_FACING_X（沿 Z/Y 展开）或 ORIENT_FACING_Z（沿 X/Y 展开）
     */
    private int createEmissiveQuad(Engine engine, Scene scene, MaterialInstance instance,
                                   float cx, float cy, float cz,
                                   float w, float h, int orient) {
        float hw = w / 2f, hh = h / 2f;
        float[] v;
        if (orient == ORIENT_DOWN) {
            v = new float[]{
                    cx - hw, cy, cz - hh,
                    cx - hw, cy, cz + hh,
                    cx + hw, cy, cz + hh,
                    cx + hw, cy, cz - hh
            };
        } else if (orient == ORIENT_FACING_Z) {
            v = new float[]{
                    cx - hw, cy - hh, cz,
                    cx + hw, cy - hh, cz,
                    cx + hw, cy + hh, cz,
                    cx - hw, cy + hh, cz
            };
        } else {
            v = new float[]{
                    cx, cy - hh, cz - hw,
                    cx, cy - hh, cz + hw,
                    cx, cy + hh, cz + hw,
                    cx, cy + hh, cz - hw
            };
        }
        short[] indices = {0, 1, 2, 2, 3, 0};

        VertexBuffer vertexBuffer = new VertexBuffer.Builder()
                .bufferCount(2)
                .vertexCount(4)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                        VertexBuffer.AttributeType.FLOAT3, 0, 3 * 4)
                .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1,
                        VertexBuffer.AttributeType.SHORT4, 0, 4 * 2)
                .normalized(VertexBuffer.VertexAttribute.TANGENTS)
                .build(engine);

        // unlit 材质不参与光照，TBN 恒定即可
        short[] tbn = {32767, 0, 32767, 32767, 32767, 0, 32767, 32767,
                32767, 0, 32767, 32767, 32767, 0, 32767, 32767};

        ByteBuffer vb = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder());
        vb.asFloatBuffer().put(v);
        vertexBuffer.setBufferAt(engine, 0, vb);

        ByteBuffer tbnBuf = ByteBuffer.allocateDirect(tbn.length * 2).order(ByteOrder.nativeOrder());
        tbnBuf.asShortBuffer().put(tbn);
        vertexBuffer.setBufferAt(engine, 1, tbnBuf);

        IndexBuffer indexBuffer = new IndexBuffer.Builder()
                .indexCount(indices.length)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine);
        ByteBuffer ib = ByteBuffer.allocateDirect(indices.length * 2).order(ByteOrder.nativeOrder());
        ib.asShortBuffer().put(indices);
        indexBuffer.setBuffer(engine, ib);

        int entity = EntityManager.get().create();
        new RenderableManager.Builder(1)
                .boundingBox(new Box(new float[]{cx, cy, cz}, new float[]{hw, Math.max(hh, 1e-3f), hw}))
                .material(0, instance)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
                .culling(false)     // 双面可见，从任何角度都能看到灯条
                .receiveShadows(false)
                .castShadows(false)
                .build(engine, entity);

        scene.addEntity(entity);
        return entity;
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
            GroundFactory.createLitGroundPlane(engine, scene, litInstance,
                    halfExtent[0], halfExtent[2], groundY);
            Log.d(TAG, "addGround: lit 地面创建成功");
        } catch (Exception e) {
            Log.e(TAG, "addGround: lit 地面创建失败", e);
        }

        // ── 透明阴影层已移除，仅保留 lit 不透明地面 ────────────────
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

    // ── 行驶（原地展厅式：车身不动，轮子按速度自转）────────────────────────

    /**
     * 轮半径（米）。实测轮心高度 y≈0.329，即轮半径。
     */
    private static final float WHEEL_RADIUS = 0.33f;
    /**
     * 最大速度 m/s（与 UI SeekBar 量程一致）
     */
    public static final float MAX_SPEED = 10f;
    /**
     * 轮子转向符号：真机校准结果为 -1（否则看上去是开倒车）。
     */
    private static final float SPIN_SIGN = -1f;
    /**
     * 速度平滑系数（每秒），越大加减速越快。复刻 su7-replica power2.out 的渐变手感。
     */
    private static final float SPEED_SMOOTH_K = 2.0f;
    /**
     * 单帧 dt 上限（秒），防止切后台回来后角度跳变。
     */
    private static final float MAX_FRAME_DT = 0.1f;

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
     * 目标速度（m/s），由 UI 设定
     */
    private volatile float mTargetSpeed = 0f;
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
     * 按名称收集 4 个轮子节点，并缓存其基准局部变换。
     * 车模轮子节点：node_brakes1 / node_brakes2 / node_brakes003 / node_brakes004，
     * 各自包含 rim + tyre 子树；局部变换为纯平移（旋转=单位阵），
     * 因此轮轴即节点局部 X 轴（左右轮连线方向）。
     */
    private void collectWheels() {
        FilamentAsset asset = mModelViewer.getAsset();
        if (asset == null) {
            Log.w(TAG, "collectWheels: 模型资源为 null，行驶功能不可用");
            return;
        }
        TransformManager tm = mModelViewer.getEngine().getTransformManager();
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
     * 设定目标速度（m/s），自动钳制到 [0, MAX_SPEED]
     */
    public void setTargetSpeed(float speed) {
        mTargetSpeed = Math.max(0f, Math.min(MAX_SPEED, speed));
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
     * 每帧驱动：速度平滑逼近目标 → 累计轮转角 → 更新 4 个轮子变换。
     * 在 FrameCallback 中渲染前调用。
     */
    private void updateDriving(long frameTimeNanos) {
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

        // 指数平滑：加减速渐变
        float blend = 1f - (float) Math.exp(-SPEED_SMOOTH_K * dt);
        mCurrentSpeed += (mTargetSpeed - mCurrentSpeed) * blend;
        if (mTargetSpeed == 0f && mCurrentSpeed < 0.005f) {
            mCurrentSpeed = 0f;
        }
        // 已完全停止且轮子已停在当前角度：跳过无效更新
        if (mCurrentSpeed == 0f && mLastAngleApplied) {
            return;
        }

        // dθ = v / r（弧度）→ 度
        mTotalWheelAngleDeg += mCurrentSpeed / WHEEL_RADIUS * dt * SPIN_SIGN
                * (float) (180.0 / Math.PI);

        TransformManager tm = mModelViewer.getEngine().getTransformManager();
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

    /**
     * 上一帧是否实际应用了轮转（用于速度为 0 时跳过无效 setTransform）
     */
    private boolean mLastAngleApplied = false;

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

    private void addCarLight() {
        Engine engine = mModelViewer.getEngine();

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
                    .build(engine, entity);
            mLightPos[i][0] = HEADLIGHT_X;
            mLightPos[i][1] = HEADLIGHT_Y;
            mLightPos[i][2] = baseZ[i];
        }

        // 光轴朝车前方下压，落点在地面上（灯位与俯角是否合适以调试标记为准）
        updateBeamDirection();
        applyLightPosition();
        applyLightDirection();

        // 调试标记：黄色=灯位，橙色=地面落点（光轴延长线），绿/红=车鼻/车尾
        if (DEBUG_SHOW_AXES && mLitMaterial != null) {
            Scene scene = mModelViewer.getScene();
            mDebugHelper = new DebugHelper(engine, scene, mLitMaterial);
            mDebugHelper.addAxes(0, 0, 0, 6f);
            for (int i = 0; i < mFrontLightEntities.length; i++) {
                mDebugHelper.addMarker(mLightPos[i][0], mLightPos[i][1], mLightPos[i][2], 0.22f);
                mDebugHelper.addMarker(mLightPos[i][0] + BEAM_RANGE, mGroundY + 0.03f,
                        mLightPos[i][2], 0.18f, 1f, 0.5f, 0f);
                // 洋红圆盘：按含外展角的光轴算出的预测光斑落点（辅助校准）
                float toe = i == 0 ? BEAM_TOE_Z : -BEAM_TOE_Z;
                float hx = mLightDirX;
                float hz = mLightDirZ + toe;
                float hl = (float) Math.sqrt(hx * hx + hz * hz);
                mDebugHelper.addGroundDisc(
                        mLightPos[i][0] + hx / hl * BEAM_RANGE,
                        mLightPos[i][2] + hz / hl * BEAM_RANGE,
                        1.2f, 1f, 0f, 1f);
            }
            mDebugHelper.addMarker(2.13f, 0.6f, 0f, 0.12f, 0f, 1f, 0f);   // 车鼻参考
            mDebugHelper.addMarker(-1.98f, 0.6f, 0f, 0.12f, 1f, 0f, 0f);  // 车尾参考
            Log.d(TAG, "addCarLight: 已创建左右两颗 FOCUSED_SPOT 前照灯");
        }
    }

    /**
     * 依据灯位与地面落点重新计算光轴方向（下压俯角 = atan(灯高 / BEAM_RANGE)）
     */
    private void updateBeamDirection() {
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
        Scene scene = mModelViewer.getScene();
        if (enabled && !mFrontLightOn) {
            for (int e : mFrontLightEntities) {
                scene.addEntity(e);
            }
            mFrontLightOn = true;
        } else if (!enabled && mFrontLightOn) {
            for (int e : mFrontLightEntities) {
                scene.removeEntity(e);
            }
            mFrontLightOn = false;
        }
        wakeUp();
    }

    public boolean isFrontLightOn() {
        return mFrontLightOn;
    }

    /**
     * 当前光轴方向（两颗灯共用，可实时微调）
     */
    private float mLightDirX;
    private float mLightDirY;
    private float mLightDirZ;

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
        updateBeamDirection();
        applyLightDirection();
    }

    private void applyLightDirection() {
        if (mFrontLightEntities[0] == -1) return;
        LightManager lm = mModelViewer.getEngine().getLightManager();
        for (int i = 0; i < mFrontLightEntities.length; i++) {
            // 左灯(i=0, +Z 侧)向外(+Z)偏，右灯向 -Z 偏，形成外展的两束光
            float toe = i == 0 ? BEAM_TOE_Z : -BEAM_TOE_Z;
            lm.setDirection(lm.getInstance(mFrontLightEntities[i]),
                    mLightDirX, mLightDirY, mLightDirZ + toe);
        }
        Log.d(TAG, String.format("applyLightDirection: (%.2f, %.2f, %.2f)",
                mLightDirX, mLightDirY, mLightDirZ));
    }

    // ── 光源位置调整（左右灯同步位移） ───────────────────────────────────────

    /** 当前两颗灯的位置（运行时可调整） */
    private final float[][] mLightPos = {{0f, 0f, 0f}, {0f, 0f, 0f}};

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
        LightManager lm = mModelViewer.getEngine().getLightManager();
        for (int i = 0; i < mFrontLightEntities.length; i++) {
            float[] p = mLightPos[i];
            lm.setPosition(lm.getInstance(mFrontLightEntities[i]), p[0], p[1], p[2]);
        }
        Log.d(TAG, String.format("applyLightPosition: (%.2f, %.2f, %.2f)",
                mLightPos[0][0], mLightPos[0][1], mLightPos[0][2]));
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
            mIndirectLight = light;
            light.setIntensity(0f);
            scene.setIndirectLight(light);

            // 天空盒：暗房纯色（深黑微带蓝灰），突出灯条与车身
            // （原 env_skybox.ktx 灰色环境与展厅氛围冲突，保留备用）
            Skybox skybox = new Skybox.Builder()
                    .color(0.012f, 0.014f, 0.02f, 1f)
                    .build(engine);
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
            if (mIntroPlaying) {
                updateIntro(frameTimeNanos, mEngine, mModelViewer.getView());
            }

            if (mAnimator != null && LOOP_ANIMATION) {
                // 循环播放模型的动画
                startTime = startTime == null ? frameTimeNanos : startTime;
                float duration = (frameTimeNanos - startTime) / 1_000_000_000F;
                mAnimator.applyAnimation(0, duration);
                mAnimator.updateBoneMatrices();
            }

            // 行驶驱动（轮子自转）
            updateDriving(frameTimeNanos);

            // 速度光束 + 相机抖动
            updateSpeedLines(frameTimeNanos);
            updateShake(frameTimeNanos);

            renderFrame(frameTimeNanos);
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
     * 进入静止的时刻（SystemClock.uptimeMillis），0 表示当前忙碌；仅帧回调线程访问
     */
    private long mIdleSinceMs = 0;
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
     * 交互停手后保持满帧的余量（毫秒）：覆盖相机手势惯性/阻尼的收尾
     */
    private static final long INTERACT_HOLD_MS = 400;
    /**
     * 手势是否进行中（由触摸监听更新）
     */
    private boolean mTouchActive = false;
    /**
     * 最近一次触摸抬起的截止时刻（SystemClock.uptimeMillis）
     */
    private long mInteractHoldUntilMs = 0;

    /**
     * 场景是否处于必须在满帧驱动的状态：
     * 开场动画、行驶（轮转/光束/抖动）、模型循环动画、用户手势（含阻尼余量）。
     * 车门/车灯等瞬时变化由空闲低频循环的下一次渲染自然呈现。
     */
    private boolean isSceneBusy() {
        if (mIntroPlaying || mTouchActive) {
            return true;
        }
        if (mTargetSpeed > 0f || mCurrentSpeed > 0.01f || mBeamsInScene) {
            return true;
        }
        if (SystemClock.uptimeMillis() < mInteractHoldUntilMs) {
            return true;
        }
        return false;
    }

    // ── 自管渲染（ModelViewer.render 内部相机同步与绘制焊死，无钩子插抖动）─────

    /**
     * 反射缓存的 swapChain 字段（Kotlin private var），避免每帧重复 getDeclaredField
     */
    private Field mSwapChainField;
    private boolean mSwapChainFieldFailed = false;

    /**
     * 复刻 ModelViewer.render 的流程，但在相机姿态同步处插入抖动：
     * 异步资源推进/就绪实体入场景 → manipulator.getLookAt → 叠加速度抖动 →
     * camera.lookAt → beginFrame/render/endFrame。
     * 反射失败时退化：无抖动但仍按此流程渲染。
     */
    private void renderFrame(long frameTimeNanos) {
        // 复刻 ModelViewer 内部的异步纹理推进 + 就绪实体入场景（漏掉会导致车模不渲染）
        syncModelLoading();

        com.google.android.filament.Camera camera = mModelViewer.getCamera();
        float[] eye = new float[3];
        float[] target = new float[3];
        float[] up = new float[3];
        mManipulator.getLookAt(eye, target, up);

        // 相机抖动：幅度随速度渐显（满幅 0.055m，复刻 su7 rush 的晃动感）
        if (mShakeLevel > 0.001f) {
            float t = frameTimeNanos * 1e-9f;
            float speedRatio = mCurrentSpeed / MAX_SPEED;
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
        RenderableManager rcm = mEngine.getRenderableManager();
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

    private Field mResourceLoaderField;
    private boolean mResourceLoaderFieldFailed = false;

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
     * 满幅抖动时的最大眼点偏移（米），真机校准：偏移减半保留质感
     */
    private static final float SHAKE_MAX_OFFSET = 0.011f;
    /**
     * 抖动强度（0..1），随速度渐显渐隐（帧回调单线程访问）
     */
    private float mShakeLevel = 0f;
    private long mShakeLastFrameNs = -1;

    /**
     * 抖动强度向目标渐变：速度 > 0.05 时满幅，否则归零
     */
    private void updateShake(long frameTimeNanos) {
        float dt = 0f;
        if (mShakeLastFrameNs > 0) {
            dt = (frameTimeNanos - mShakeLastFrameNs) / 1_000_000_000f;
        }
        mShakeLastFrameNs = frameTimeNanos;
        if (dt <= 0f) {
            return;
        }
        dt = Math.min(dt, MAX_FRAME_DT);
        float target = mCurrentSpeed > 0.05f ? 1f : 0f;
        mShakeLevel += (target - mShakeLevel) * (1f - (float) Math.exp(-4f * dt));
        if (target == 0f && mShakeLevel < 0.002f) {
            mShakeLevel = 0f;
        }
    }

    // ── 速度光束（彩色光条围绕车身向后滑动，复刻 su7 speedup 光束）─────────

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

    private final int[] mBeamEntities = new int[SPEED_BEAM_COUNT * 2];
    /** 每根光束当前 X 位置 */
    private final float[] mBeamX = new float[SPEED_BEAM_COUNT];
    /** 每根光束参数：y, z, 基准强度 */
    private final float[][] mBeamPlacement = new float[SPEED_BEAM_COUNT][3];
    private MaterialInstance[] mBeamInstances;
    private boolean mBeamsBuilt = false;
    private float mBeamFade = 0f;
    private boolean mBeamsInScene = false;
    private long mBeamLastFrameNs = -1;

    /**
     * 一次性生成光束：绕车身圆柱随机分布的细长彩色发光条（局部原点在束中心，
     * 顶点只含形状，位置由运行时平移矩阵控制）。
     */
    private void buildSpeedLines() {
        Engine engine = mModelViewer.getEngine();
        Scene scene = mModelViewer.getScene();
        if (mEmissiveMaterial == null) {
            Log.w(TAG, "buildSpeedLines: emissive 材质不可用，速度光束跳过");
            return;
        }
        java.util.Random rnd = new java.util.Random(20260918L);
        mBeamInstances = new MaterialInstance[SPEED_BEAM_COUNT];
        for (int i = 0; i < SPEED_BEAM_COUNT; i++) {
            MaterialInstance inst = mEmissiveMaterial.createInstance();
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
            mBeamEntities[i * 2] = createEmissiveQuad(engine, scene, inst,
                    0f, y, z, len, BEAM_THICKNESS, ORIENT_FACING_Z);
            mBeamEntities[i * 2 + 1] = createEmissiveQuad(engine, scene, inst,
                    0f, y, z, len, BEAM_THICKNESS, ORIENT_DOWN);
        }
        mBeamsBuilt = true;
        Log.d(TAG, "buildSpeedLines: 创建 " + SPEED_BEAM_COUNT + " 根速度光束");

        // 光束只在行驶时入场景渲染（停止时整组移除，避免黑色条挡住背景）
        scene.removeEntities(mBeamEntities);
        mBeamsInScene = false;
    }

    /**
     * 每帧驱动光束：随车速 1:1 向 -X 后退、出界回绕；显示强度随速度渐显渐隐。
     */
    private void updateSpeedLines(long frameTimeNanos) {
        if (!mBeamsBuilt) {
            return;
        }
        float dt = 0f;
        if (mBeamLastFrameNs > 0) {
            dt = (frameTimeNanos - mBeamLastFrameNs) / 1_000_000_000f;
        }
        mBeamLastFrameNs = frameTimeNanos;
        if (dt <= 0f) {
            return;
        }
        dt = Math.min(dt, MAX_FRAME_DT);

        // 显示强度渐变：淡入较缓、淡出加速（平方加快尾段淡出）
        float target = mCurrentSpeed > 0.05f ? 1f : 0f;
        float fadeRate = target > 0f ? 3.5f : 6.5f;
        mBeamFade += (target - mBeamFade) * (1f - (float) Math.exp(-fadeRate * dt));
        if (target == 0f && mBeamFade < 0.02f) {
            mBeamFade = 0f;
        }
        float move = mCurrentSpeed * dt; // 1:1 与滚动轮速同步

        // 场景进出光束：开始行驶才渲染；完全停下（fade 归零）即整组移除
        if (!mBeamsInScene && mBeamFade > 0f) {
            mModelViewer.getScene().addEntities(mBeamEntities);
            mBeamsInScene = true;
        } else if (mBeamsInScene && mBeamFade == 0f && target == 0f) {
            mModelViewer.getScene().removeEntities(mBeamEntities);
            mBeamsInScene = false;
            return;
        }
        if (!mBeamsInScene && mBeamFade == 0f) {
            return;
        }

        TransformManager tm = mModelViewer.getEngine().getTransformManager();
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
