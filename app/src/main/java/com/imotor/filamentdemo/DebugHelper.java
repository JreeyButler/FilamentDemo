package com.imotor.filamentdemo;

import com.google.android.filament.Box;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.IndexBuffer;
import com.google.android.filament.Material;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Scene;
import com.google.android.filament.TransformManager;
import com.google.android.filament.VertexBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 调试辅助工具：在场景中绘制坐标轴和位置标记点，方便定位光源等对象的世界坐标。
 * <p>
 * 使用方式：
 * DebugHelper debug = new DebugHelper(engine, scene, litMaterial);
 * debug.addAxes(0, 0, 0, 5f);                    // 在原点画长度为 5 的坐标轴
 * debug.addMarker(lightX, lightY, lightZ, 0.2f); // 在光源位置画黄色标记球（立方体）
 * // 不需要时调用 debug.removeAll() 清除
 */
public class DebugHelper {

    private final Engine mEngine;
    private final Scene mScene;
    private final Material mMaterial;
    private final List<Integer> mEntities = new ArrayList<>();

    public DebugHelper(Engine engine, Scene scene, Material litMaterial) {
        mEngine = engine;
        mScene = scene;
        mMaterial = litMaterial;
    }

    /**
     * 在指定原点绘制 XYZ 坐标轴。
     * X 轴 = 红色，Y 轴 = 绿色，Z 轴 = 蓝色。
     *
     * @param ox     原点 X
     * @param oy     原点 Y
     * @param oz     原点 Z
     * @param length 轴长度
     */
    public void addAxes(float ox, float oy, float oz, float length) {
        float r = 0.05f; // 轴的粗细（半径）

        // X 轴（红）：沿 X 方向延伸
        addBox(ox + length / 2, oy, oz,
                length, r, r,
                1f, 0f, 0f);

        // Y 轴（绿）：沿 Y 方向延伸
        addBox(ox, oy + length / 2, oz,
                r, length, r,
                0f, 1f, 0f);

        // Z 轴（蓝）：沿 Z 方向延伸
        addBox(ox, oy, oz + length / 2,
                r, r, length,
                0f, 0f, 1f);
    }

    /**
     * 在指定位置绘制一个彩色标记立方体（默认黄色）。
     *
     * @param x    位置 X
     * @param y    位置 Y
     * @param z    位置 Z
     * @param size 立方体边长
     */
    public void addMarker(float x, float y, float z, float size) {
        addMarker(x, y, z, size, 1f, 1f, 0f); // 默认黄色
    }

    /**
     * 在指定位置绘制一个彩色标记立方体。
     */
    public void addMarker(float x, float y, float z, float size,
                          float r, float g, float b) {
        addBox(x, y, z, size, size, size, r, g, b);
    }

    /**
     * 在指定位置添加一个扁平圆盘形标记（贴在地面上方），用于标注光束落点。
     */
    public void addGroundDisc(float cx, float cz, float size, float r, float g, float b) {
        addBox(cx, 0.03f, cz, size, 0.06f, size, r, g, b);
    }

    /**
     * 移除所有调试对象。
     */
    public void removeAll() {
        for (int entity : mEntities) {
            mScene.removeEntity(entity);
            mEngine.destroyEntity(entity);
        }
        mEntities.clear();
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────────

    /**
     * 创建一个轴对齐的长方体并加入场景。
     * center (cx, cy, cz)，尺寸 (sx, sy, sz)，颜色 (r, g, b)。
     */
    private void addBox(float cx, float cy, float cz,
                        float sx, float sy, float sz,
                        float r, float g, float b) {
        float hx = sx / 2f, hy = sy / 2f, hz = sz / 2f;

        // 8 个顶点，每个顶点 3 个 float（POSITION）
        float[] pos = {
                -hx, -hy, -hz, hx, -hy, -hz, hx, hy, -hz, -hx, hy, -hz, // 后面
                -hx, -hy, hz, hx, -hy, hz, hx, hy, hz, -hx, hy, hz  // 前面
        };

        // 法线朝外的 packed TBN（SHORT4 normalized），简化为全部朝上
        short[] tbn = new short[8 * 4];
        for (int i = 0; i < 8; i++) {
            tbn[i * 4] = 32767;
            tbn[i * 4 + 1] = 0;
            tbn[i * 4 + 2] = 32767;
            tbn[i * 4 + 3] = 32767;
        }

        // 12 个三角形，36 个索引
        short[] idx = {
                0, 1, 2, 2, 3, 0,  // 后
                4, 5, 6, 6, 7, 4,  // 前
                0, 4, 7, 7, 3, 0,  // 左
                1, 5, 6, 6, 2, 1,  // 右
                3, 2, 6, 6, 7, 3,  // 上
                0, 1, 5, 5, 4, 0   // 下
        };

        VertexBuffer vb = new VertexBuffer.Builder()
                .bufferCount(2)
                .vertexCount(8)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                        VertexBuffer.AttributeType.FLOAT3, 0, 3 * 4)
                .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1,
                        VertexBuffer.AttributeType.SHORT4, 0, 4 * 2)
                .normalized(VertexBuffer.VertexAttribute.TANGENTS)
                .build(mEngine);

        ByteBuffer posBuf = ByteBuffer.allocateDirect(pos.length * 4).order(ByteOrder.nativeOrder());
        posBuf.asFloatBuffer().put(pos);
        vb.setBufferAt(mEngine, 0, posBuf);

        ByteBuffer tbnBuf = ByteBuffer.allocateDirect(tbn.length * 2).order(ByteOrder.nativeOrder());
        tbnBuf.asShortBuffer().put(tbn);
        vb.setBufferAt(mEngine, 1, tbnBuf);

        IndexBuffer ib = new IndexBuffer.Builder()
                .indexCount(idx.length)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(mEngine);
        ByteBuffer idxBuf = ByteBuffer.allocateDirect(idx.length * 2).order(ByteOrder.nativeOrder());
        idxBuf.asShortBuffer().put(idx);
        ib.setBuffer(mEngine, idxBuf);

        MaterialInstance mi = mMaterial.createInstance();
        mi.setParameter("baseColor", r, g, b);
        mi.setParameter("roughness", 0.8f);
        mi.setParameter("metallic", 0.0f);

        int entity = EntityManager.get().create();
        new RenderableManager.Builder(1)
                .boundingBox(new Box(new float[]{0, 0, 0}, new float[]{hx, hy, hz}))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
                .material(0, mi)
                .culling(false)
                .receiveShadows(false)
                .castShadows(false)
                .build(mEngine, entity);

        // 平移到目标位置
        TransformManager tcm = mEngine.getTransformManager();
        int ti = tcm.getInstance(entity);
        float[] transform = {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                cx, cy, cz, 1
        };
        tcm.setTransform(ti, transform);

        mScene.addEntity(entity);
        mEntities.add(entity);
    }
}
