package com.imotor.filamentdemo;

import android.content.Context;
import android.util.Log;

import com.google.android.filament.Box;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.IndexBuffer;
import com.google.android.filament.Material;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Scene;
import com.google.android.filament.VertexBuffer;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 自发光（unlit）发光四边形工厂：加载 emissive.filamat 材质，
 * 生成任意朝向的高亮 quad —— 展厅灯条与速度光束共用。
 *
 * @author Yan.Liangliang
 * @date 2025/9/18
 */
public final class EmissiveQuadFactory {
    private static final String TAG = EmissiveQuadFactory.class.getSimpleName();

    /**
     * 水平面，朝下
     */
    public static final int ORIENT_DOWN = 0;
    /**
     * 竖平面，法线朝 +X
     */
    public static final int ORIENT_FACING_X = 1;
    /**
     * 竖平面，法线朝 +Z（X/Y 展开，速度光束用）
     */
    public static final int ORIENT_FACING_Z = 2;

    private static final String MATERIAL_PATH = "emissive.filamat";
    /**
     * 顶灯带相对地面的高度（米）。
     * 抬到常规取景画幅之上，让用户只看到辉光而不是灯条本体。
     */
    public static final float LIGHT_BAR_HEIGHT = 6.0f;

    /**
     * 自发光材质（emissive.filamat）
     */
    private Material mEmissiveMaterial;

    private EmissiveQuadFactory() {
    }

    public static EmissiveQuadFactory create(Context context, Engine engine) {
        EmissiveQuadFactory factory = new EmissiveQuadFactory();
        try (InputStream in = context.getAssets().open(MATERIAL_PATH)) {
            byte[] bytes = new byte[in.available()];
            int length = in.read(bytes);
            ByteBuffer buf = ByteBuffer.allocateDirect(length);
            buf.put(bytes, 0, length);
            buf.rewind();
            factory.mEmissiveMaterial = new Material.Builder().payload(buf, buf.remaining()).build(engine);
        } catch (Exception e) {
            Log.e(TAG, "create: emissive 材质加载失败", e);
            // 返回 isReady=false 的空工厂（调用方以 isReady 判定降级，不再崩溃）
        }
        return factory;
    }

    public boolean isReady() {
        return mEmissiveMaterial != null;
    }

    public Material getMaterial() {
        return mEmissiveMaterial;
    }

    /**
     * 创建一块自发光四边形（双面渲染）。
     *
     * @param instance 材质实例（可与多块几何共享）
     * @param cx/cy/cz 中心点
     * @param w        第一轴尺寸
     * @param h        第二轴尺寸
     * @param orient   ORIENT_DOWN、ORIENT_FACING_X 或 ORIENT_FACING_Z
     * @return 实体 id（几何围绕局部原点记录时平移矩阵接管位置）
     */
    public int createQuad(Engine engine, Scene scene, MaterialInstance instance,
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
}
