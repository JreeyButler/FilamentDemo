package com.imotor.filamentdemo;

import com.google.android.filament.Box;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.IndexBuffer;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Scene;
import com.google.android.filament.TransformManager;
import com.google.android.filament.VertexBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * @author Yan.Liangliang
 * @date 2025/6/25 上午11:25
 */
public class GroundFactory {

    /**
     * 圆盘圆周分段数，越大越圆滑。
     */
    private static final int DISC_SEGMENTS = 128;

    /**
     * 圆盘网格（顶点/索引缓冲 + 半径）。
     */
    private static final class DiscMesh {
        final VertexBuffer vertexBuffer;
        final IndexBuffer indexBuffer;
        final float radius;

        DiscMesh(VertexBuffer vertexBuffer, IndexBuffer indexBuffer, float radius) {
            this.vertexBuffer = vertexBuffer;
            this.indexBuffer = indexBuffer;
            this.radius = radius;
        }
    }

    /**
     * 以竖直法线（+Y）构造一个圆盘网格：中心点 + 圆周顶点 + 首尾重合接缝点。
     */
    private static DiscMesh buildDisc(Engine engine, float radius) {
        int segs = DISC_SEGMENTS;
        int ringCount = segs + 1;          // 含与起点重合的接缝点
        int vertexCount = ringCount + 1;   // 再加中心点（索引 0）

        float[] vertices = new float[vertexCount * 3];
        short[] tbn = new short[vertexCount * 4];
        // 中心点
        vertices[0] = 0f;
        vertices[1] = 0f;
        vertices[2] = 0f;
        // 圆周点（从中心开始写 tbn，法线朝上）
        for (int i = 0; i < vertexCount; i++) {
            int vi = i * 3;
            if (i > 0) {
                double angle = 2.0 * Math.PI * (i - 1) / segs;
                vertices[vi] = (float) (Math.cos(angle) * radius);
                vertices[vi + 1] = 0f;
                vertices[vi + 2] = (float) (Math.sin(angle) * radius);
            }
            int ti = i * 4;
            tbn[ti] = 32767;
            tbn[ti + 1] = 0;
            tbn[ti + 2] = 32767;
            tbn[ti + 3] = 32767;
        }

        // 三角扇：中心 → 相邻两个圆周点（逆时针，法线朝上）
        short[] indices = new short[segs * 3];
        for (int i = 0; i < segs; i++) {
            indices[i * 3] = 0;
            indices[i * 3 + 1] = (short) (i + 2);
            indices[i * 3 + 2] = (short) (i + 1);
        }

        VertexBuffer vertexBuffer = new VertexBuffer.Builder()
                .bufferCount(2)
                .vertexCount(vertexCount)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                        VertexBuffer.AttributeType.FLOAT3, 0, 3 * 4)
                .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1,
                        VertexBuffer.AttributeType.SHORT4, 0, 4 * 2)
                .normalized(VertexBuffer.VertexAttribute.TANGENTS)
                .build(engine);

        ByteBuffer vb = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder());
        vb.asFloatBuffer().put(vertices);
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

        return new DiscMesh(vertexBuffer, indexBuffer, radius);
    }

    /**
     * 创建透明阴影接收地面（groundShadow.filamat），用于显示车身投影。
     */
    public static int createGroundPlane(
            Engine engine,
            Scene scene,
            MaterialInstance shadowMaterialInstance,
            float boundingExtentX,
            float boundingExtentY,
            float boundingExtentZ,
            float minY) {

        float extentX = 10.0f * boundingExtentX;
        float extentZ = 10.0f * boundingExtentZ;

        DiscMesh mesh = buildDisc(engine, Math.max(extentX, extentZ));

        int groundEntity = EntityManager.get().create();
        new RenderableManager.Builder(1)
                .boundingBox(new Box(new float[]{0, 0, 0}, new float[]{mesh.radius, 1e-4f, mesh.radius}))
                .material(0, shadowMaterialInstance)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vertexBuffer, mesh.indexBuffer)
                .culling(false)
                .receiveShadows(true)
                .castShadows(false)
                .build(engine, groundEntity);

        setGroundTransform(engine, groundEntity, minY);
        scene.addEntity(groundEntity);
        return groundEntity;
    }

    /**
     * 创建可接受动态光照的不透明 lit 地面（lit.filamat），用于显示车灯光斑。
     * 放在阴影层下方 0.001 单位，避免 Z-fighting。
     */
    public static int createLitGroundPlane(
            Engine engine,
            Scene scene,
            MaterialInstance litMaterialInstance,
            float boundingExtentX,
            float boundingExtentZ,
            float minY) {

        float extentX = 10.0f * boundingExtentX;
        float extentZ = 10.0f * boundingExtentZ;

        DiscMesh mesh = buildDisc(engine, Math.max(extentX, extentZ));

        int groundEntity = EntityManager.get().create();
        new RenderableManager.Builder(1)
                .boundingBox(new Box(new float[]{0, 0, 0}, new float[]{mesh.radius, 1e-4f, mesh.radius}))
                .material(0, litMaterialInstance)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vertexBuffer, mesh.indexBuffer)
                .culling(false)
                .receiveShadows(true)   // 接收阴影（车灯投影）
                .castShadows(false)
                .build(engine, groundEntity);

        // 与地面齐平，不再需要偏移
        setGroundTransform(engine, groundEntity, minY);
        scene.addEntity(groundEntity);
        return groundEntity;
    }

    private static void setGroundTransform(Engine engine, int entity, float y) {
        TransformManager tcm = engine.getTransformManager();
        int ti = tcm.getInstance(entity);
        float[] transform = new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, y, 0, 1
        };
        tcm.setTransform(ti, transform);
    }
}
