package com.imotor.filamentdemo;


import com.google.android.filament.*;

import java.nio.*;

/**
 * @author Yan.Liangliang
 * @date 2025/6/25 上午11:25
 */
public class GroundFactory {

    public static int createGroundPlane(Engine engine, Scene scene, MaterialInstance material, float size) {
        // 顶点（X, Y, Z, U, V）
        float half = size / 2f;
        float[] vertices = {
                -half, 0, -half, 0, 1,  // 左下
                half, 0, -half, 1, 1,  // 右下
                half, 0, half, 1, 0,  // 右上
                -half, 0, half, 0, 0   // 左上
        };

        // 索引：两个三角形
        short[] indices = {
                0, 1, 2,
                2, 3, 0
        };

        // VertexBuffer
        VertexBuffer vertexBuffer = new VertexBuffer.Builder()
                .vertexCount(4)
                .bufferCount(1)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 5 * 4)
                .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 3 * 4, 5 * 4)
                .normalized(VertexBuffer.VertexAttribute.UV0)
                .build(engine);

        ByteBuffer vb = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder());
        vb.asFloatBuffer().put(vertices);
        vertexBuffer.setBufferAt(engine, 0, vb);

        // IndexBuffer
        IndexBuffer indexBuffer = new IndexBuffer.Builder()
                .indexCount(indices.length)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine);

        ByteBuffer ib = ByteBuffer.allocateDirect(indices.length * 2).order(ByteOrder.nativeOrder());
        ib.asShortBuffer().put(indices);
        indexBuffer.setBuffer(engine, ib);

        // 创建地面实体
        int groundEntity = EntityManager.get().create();

        // 创建渲染组件
        new RenderableManager.Builder(1)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
                .material(0, material)
                .receiveShadows(true)
                .castShadows(false)
                .culling(false)
                .build(engine, groundEntity);
        float[] identityMatrix = {
                1, 0, 0, 0,   // 第一列
                0, 1, 0, 0,   // 第二列
                0, 0, 1, 0,   // 第三列
                0, 0, 0, 1    // 第四列
        };
        // 放在 Y=0 地面位置
        engine.getTransformManager().setTransform(
                engine.getTransformManager().getInstance(groundEntity),
                identityMatrix
        );

        // 添加到场景
        scene.addEntity(groundEntity);

        return groundEntity;
    }

    public static int createGroundPlane(
            Engine engine,
            Scene scene,
            Material shadowMaterial,
            float boundingExtentX,
            float boundingExtentY,
            float boundingExtentZ,
            float minY) {
        EntityManager em = EntityManager.get();

        // 1. 定义 plane 大小
        float extentX = 10.0f * boundingExtentX;
        float extentZ = 10.0f * boundingExtentZ;

        // 2. 顶点坐标 (x, y, z)
        float[] vertices = {
                -extentX, 0, -extentZ,
                -extentX, 0, extentZ,
                extentX, 0, extentZ,
                extentX, 0, -extentZ
        };

        // 3. packed TBN (tangent frame)
        short[] tbn = {
                32767, 0, 32767, 32767, // approximate packed TBN for all 4 verts
                32767, 0, 32767, 32767,
                32767, 0, 32767, 32767,
                32767, 0, 32767, 32767
        };

        // 4. Index buffer
        short[] indices = {
                0, 1, 2,
                2, 3, 0
        };

        // 5. Vertex buffer
        VertexBuffer vertexBuffer = new VertexBuffer.Builder()
                .bufferCount(2)
                .vertexCount(4)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                        VertexBuffer.AttributeType.FLOAT3, 0, 3 * 4)
                .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1,
                        VertexBuffer.AttributeType.SHORT4, 0, 4 * 2)
                .normalized(VertexBuffer.VertexAttribute.TANGENTS)
                .build(engine);

        ByteBuffer vb = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder());
        vb.asFloatBuffer().put(vertices);
        vertexBuffer.setBufferAt(engine, 0, vb);

        ByteBuffer tbnBuf = ByteBuffer.allocateDirect(tbn.length * 2)
                .order(ByteOrder.nativeOrder());
        tbnBuf.asShortBuffer().put(tbn);
        vertexBuffer.setBufferAt(engine, 1, tbnBuf);

        // 6. Index buffer
        IndexBuffer indexBuffer = new IndexBuffer.Builder()
                .indexCount(indices.length)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine);

        ByteBuffer ib = ByteBuffer.allocateDirect(indices.length * 2)
                .order(ByteOrder.nativeOrder());
        ib.asShortBuffer().put(indices);
        indexBuffer.setBuffer(engine, ib);

        // 7. Create Entity
        int groundEntity = em.create();

        new RenderableManager.Builder(1)
                .boundingBox(new Box(
                        new float[]{0, 0, 0},
                        new float[]{extentX, 1e-4f, extentZ})
                )
                .material(0, shadowMaterial.getDefaultInstance())
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES,
                        vertexBuffer, indexBuffer)
                .culling(false)
                .receiveShadows(true)
                .castShadows(false)
                .build(engine, groundEntity);

        // 8. Transform: Y = minY
        TransformManager tcm = engine.getTransformManager();
        int ti = tcm.getInstance(groundEntity);
        float[] transform = new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, minY, -4, 1
        };
        tcm.setTransform(ti, transform);

        // 9. Add to scene
        scene.addEntity(groundEntity);

        return groundEntity;
    }
}
