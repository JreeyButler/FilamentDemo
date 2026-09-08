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

        float[] vertices = {
                -extentX, 0, -extentZ,
                -extentX, 0,  extentZ,
                 extentX, 0,  extentZ,
                 extentX, 0, -extentZ
        };

        // packed TBN (tangent frame)，法线朝上
        short[] tbn = {
                32767, 0, 32767, 32767,
                32767, 0, 32767, 32767,
                32767, 0, 32767, 32767,
                32767, 0, 32767, 32767
        };

        short[] indices = { 0, 1, 2, 2, 3, 0 };

        VertexBuffer vertexBuffer = new VertexBuffer.Builder()
                .bufferCount(2)
                .vertexCount(4)
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

        int groundEntity = EntityManager.get().create();
        new RenderableManager.Builder(1)
                .boundingBox(new Box(new float[]{0, 0, 0}, new float[]{extentX, 1e-4f, extentZ}))
                .material(0, shadowMaterialInstance)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
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

        float[] vertices = {
                -extentX, 0, -extentZ,
                -extentX, 0,  extentZ,
                 extentX, 0,  extentZ,
                 extentX, 0, -extentZ
        };

        // 法线朝上的 packed TBN
        short[] tbn = {
                32767, 0, 32767, 32767,
                32767, 0, 32767, 32767,
                32767, 0, 32767, 32767,
                32767, 0, 32767, 32767
        };

        short[] indices = { 0, 1, 2, 2, 3, 0 };

        VertexBuffer vertexBuffer = new VertexBuffer.Builder()
                .bufferCount(2)
                .vertexCount(4)
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

        int groundEntity = EntityManager.get().create();
        new RenderableManager.Builder(1)
                .boundingBox(new Box(new float[]{0, 0, 0}, new float[]{extentX, 1e-4f, extentZ}))
                .material(0, litMaterialInstance)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
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
                0, y,  0, 1
        };
        tcm.setTransform(ti, transform);
    }
}
