package com.imotor.filamentdemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.filament.Engine;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Texture;
import com.google.android.filament.TextureSampler;
import com.google.android.filament.android.TextureHelper;
import com.google.android.filament.gltfio.FilamentAsset;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 车衣（车模贴图）换装系统：把外观车漆材质的 baseColorMap 换成自定义贴图。
 * <p>
 * 采用「复制材质实例 + 换图」的非破坏性方式：为每个外观图元 duplicate 一份原材质实例，
 * 设置新的 baseColorMap 后用 {@link RenderableManager#setMaterialInstanceAt} 换上去；
 * 还原时设回原实例即可，内饰/玻璃/原漆不受影响。
 * <p>
 * 车模的 base color 是一张 UV 图集，因此车衣贴图必须按该图集的 UV 布局制作，
 * 不能直接使用任意照片。内置示例见 assets/skins/。
 *
 * @author Yan.Liangliang
 * @date 2026/9/18
 */
public final class CarSkinSystem {
    private static final String TAG = CarSkinSystem.class.getSimpleName();

    /**
     * 外观车漆材质名匹配关键字（glb 中为 CARRERA_4096）
     */
    private static final String PAINT_KEYWORD = "CARRERA_4096";
    /**
     * 需要排除的非车漆材质关键字（内饰/玻璃/后视镜/大灯/车灯）
     */
    private static final String[] EXCLUDE_KEYWORDS = {
            "MATTE", "GLASS", "MIRROR", "HEADLIGHT", "LAMP"
    };

    /**
     * 内置车衣资源路径（null 项表示原漆）
     */
    public static final String[] BUILTIN_SKIN_PATHS = {
            "skins/skin_sakura.jpg",
            "skins/skin_cyber.jpg"
    };
    /**
     * 内置车衣显示名
     */
    public static final String[] BUILTIN_SKIN_NAMES = {"樱花", "霓虹"};

    private final Engine mEngine;
    private final Context mContext;
    private final RenderableManager mRenderableManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * 外观车漆图元记录
     */
    private static final class Entry {
        final int renderable;
        final int primitive;
        final MaterialInstance original;

        Entry(int renderable, int primitive, MaterialInstance original) {
            this.renderable = renderable;
            this.primitive = primitive;
            this.original = original;
        }
    }

    private final List<Entry> mEntries = new ArrayList<>();
    /**
     * 当前换上的车衣材质实例（切换/还原时销毁）
     */
    private final List<MaterialInstance> mActiveSkinInstances = new ArrayList<>();
    private Texture mActiveTexture;
    private FilamentAsset mAsset;
    private boolean mCollected = false;
    private volatile boolean mBusy = false;

    public CarSkinSystem(Engine engine, Context context) {
        mEngine = engine;
        mContext = context.getApplicationContext();
        mRenderableManager = engine.getRenderableManager();
    }

    /**
     * 收集外观车漆图元。asset 刚创建时部分 renderable 组件可能尚未就绪，
     * 因此允许重复调用：命中后置位 mCollected，未命中则保持可重试。
     */
    public void collect(FilamentAsset asset) {
        if (asset == null) {
            return;
        }
        mAsset = asset;
        mEntries.clear();

        StringBuilder names = new StringBuilder();
        scan(asset, true, names);

        if (mEntries.isEmpty()) {
            // 名称匹配失败时的兜底：所有带 baseColorMap 的材质（排除明确非车漆者）。
            // 内置车衣是按 UV 图集制作的，非车漆区域的像素保持原样，因此不会破坏内饰/玻璃。
            Log.w(TAG, "collect: 名称匹配 0 个，回退到全部 baseColorMap 材质; 材质实例名=" + names);
            scan(asset, false, null);
        } else {
            Log.d(TAG, "collect: 外观车漆图元 " + mEntries.size() + " 个; 材质实例名=" + names);
        }
        mCollected = !mEntries.isEmpty();
        if (mEntries.isEmpty()) {
            Log.e(TAG, "collect: 仍未找到可替换材质");
        }
    }

    private void scan(FilamentAsset asset, boolean strict, StringBuilder nameLog) {
        for (int entity : asset.getRenderableEntities()) {
            int ri = mRenderableManager.getInstance(entity);
            if (ri == 0) {
                continue;
            }
            int primCount = mRenderableManager.getPrimitiveCount(ri);
            for (int prim = 0; prim < primCount; prim++) {
                MaterialInstance mi = mRenderableManager.getMaterialInstanceAt(ri, prim);
                if (mi == null || mi.getMaterial() == null) {
                    continue;
                }
                String name = safeName(mi);
                if (nameLog != null && nameLog.indexOf("[" + name + "]") < 0) {
                    nameLog.append('[').append(name).append(']');
                }
                if (!mi.getMaterial().hasParameter("baseColorMap")) {
                    continue;
                }
                if (strict) {
                    if (!isBodyPaintName(name)) {
                        continue;
                    }
                } else if (name != null && isExcludedName(name)) {
                    continue;
                }
                mEntries.add(new Entry(ri, prim, mi));
            }
        }
    }

    private String safeName(MaterialInstance mi) {
        try {
            String n = mi.getName();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        } catch (Throwable ignored) {
            // 忽略，回退到材质名
        }
        return mi.getMaterial().getName();
    }

    private boolean isBodyPaintName(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.contains(PAINT_KEYWORD) && !isExcludedName(upper);
    }

    private boolean isExcludedName(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        for (String ex : EXCLUDE_KEYWORDS) {
            if (upper.contains(ex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 应用自定义车衣贴图（assets 内路径）；传 null 表示还原原漆。
     * 解码在后台线程完成，纹理上传与材质切换回到主线程（= 渲染线程）。
     */
    public void applySkin(String assetPath) {
        if (assetPath == null) {
            mMainHandler.post(this::resetSkin);
            return;
        }
        if (mBusy) {
            Log.w(TAG, "applySkin: 上一次换装尚未完成，忽略");
            return;
        }
        mBusy = true;
        new Thread(() -> {
            Bitmap bitmap = decodeBitmap(assetPath);
            mMainHandler.post(() -> {
                mBusy = false;
                if (bitmap == null) {
                    return;
                }
                uploadAndApply(bitmap);
                // 注意：不能立即 bitmap.recycle()，纹理上传在 GPU 端是异步的，
                // 提前释放像素内存会导致采样到无效数据（黑图/花图），交给 GC 回收即可。
            });
        }, "car-skin-load").start();
    }

    /**
     * 还原为原漆。
     */
    public void resetSkin() {
        if (!mActiveSkinInstances.isEmpty()) {
            for (Entry e : mEntries) {
                mRenderableManager.setMaterialInstanceAt(e.renderable, e.primitive, e.original);
            }
        }
        releaseActive();
    }

    private Bitmap decodeBitmap(String assetPath) {
        try (InputStream in = mContext.getAssets().open(assetPath)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(in, null, options);
        } catch (Exception e) {
            Log.e(TAG, "decodeBitmap: 读取失败 " + assetPath, e);
            return null;
        }
    }

    private void uploadAndApply(Bitmap bitmap) {
        // 先还原再换新，避免残留旧材质/纹理
        resetSkin();
        // 用户交互后（renderable 必已就绪）重新收集，避免 init 时机过早拿到空列表
        if (mAsset != null) {
            collect(mAsset);
        }
        if (mEntries.isEmpty()) {
            Log.w(TAG, "uploadAndApply: 无可替换图元");
            return;
        }

        int maxDim = Math.max(bitmap.getWidth(), bitmap.getHeight());
        int levels = 1;
        for (int d = maxDim; d > 1; d >>= 1) {
            levels++;
        }

        Texture texture = new Texture.Builder()
                .width(bitmap.getWidth())
                .height(bitmap.getHeight())
                .levels(levels)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.SRGB8_A8)
                .build(mEngine);
        TextureHelper.setBitmap(mEngine, texture, 0, bitmap);
        texture.generateMipmaps(mEngine);

        TextureSampler sampler = new TextureSampler(
                TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.CLAMP_TO_EDGE);

        Log.d(TAG, "uploadAndApply: 替换 " + mEntries.size() + " 个图元, 首个材质="
                + safeName(mEntries.get(0).original));
        for (Entry e : mEntries) {
            MaterialInstance skin = MaterialInstance.duplicate(e.original, "car_skin");
            skin.setParameter("baseColorMap", texture, sampler);
            if (skin.getMaterial().hasParameter("baseColorIndex")) {
                skin.setParameter("baseColorIndex", 0);
            }
            mRenderableManager.setMaterialInstanceAt(e.renderable, e.primitive, skin);
            mActiveSkinInstances.add(skin);
        }
        mActiveTexture = texture;
        Log.d(TAG, "uploadAndApply: 车衣已应用 " + bitmap.getWidth() + "x" + bitmap.getHeight());
    }

    private void releaseActive() {
        for (MaterialInstance mi : mActiveSkinInstances) {
            mEngine.destroyMaterialInstance(mi);
        }
        mActiveSkinInstances.clear();
        if (mActiveTexture != null) {
            mEngine.destroyTexture(mActiveTexture);
            mActiveTexture = null;
        }
    }

    /**
     * View 销毁时释放纹理/材质实例。
     */
    public void destroy() {
        resetSkin();
        mEntries.clear();
        mCollected = false;
    }
}
