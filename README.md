# FilamentDemo

基于 Google [Filament](https://github.com/google/filament) 实时渲染引擎实现的 Android 端 3D 汽车展示 Demo。
应用加载 `glb` 格式的车辆模型，并提供 IBL 环境光、车灯聚光灯、地面阴影、相机轨道控制以及车门开合等交互功能，适合作为 Filament + glTF 在 Android 平台上的入门参考工程。

## 功能特性

- **glTF 模型加载**：通过 `gltfio-android` 加载 `assets/models/` 下的 `.glb` 模型（默认加载 `cartoon_sports_car.glb`）。
- **IBL 环境光与天空盒**：使用 `KTX1Loader` 加载 `assets/neutral/` 下的 `neutral_ibl.ktx` 与 `env_skybox.ktx`，提供真实的全局光照与天空盒。
- **聚光车灯**：使用 `LightManager` 添加 `FOCUSED_SPOT` 聚光灯，模拟车头灯效果，并启用阴影投射。
- **地面阴影**：通过自定义材质 `groundShadow.mat`（编译为 `groundShadow.filamat`）+ 自建地面 Mesh（[GroundFactory](file:///d:/workspace/AndroidStudioProjects/FilamentDemo/app/src/main/java/com/imotor/filamentdemo/GroundFactory.java)）实现仅接收阴影、不渲染颜色的地面。
- **相机轨道控制**：基于 Filament `Manipulator` 的 ORBIT 模式，并自定义 [CameraGestureListener](file:///d:/workspace/AndroidStudioProjects/FilamentDemo/app/src/main/java/com/imotor/filamentdemo/FilamentView2.java)，避免相机下压穿透地面。
- **车门开合交互**：通过 [DoorController](file:///d:/workspace/AndroidStudioProjects/FilamentDemo/app/src/main/java/com/imotor/filamentdemo/DoorController.java) 接口，对左右前门 entity 进行铰链平移 + Y 轴旋转，实现一键开/关门。

## 项目结构

```
FilamentDemo/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── models/                       # glTF/GLB 模型
│   │   │   │   ├── cartoon_sports_car.glb
│   │   │   │   └── executive_sedan.glb
│   │   │   ├── neutral/                      # IBL 环境贴图与天空盒
│   │   │   │   ├── neutral_ibl.ktx
│   │   │   │   ├── env_skybox.ktx
│   │   │   │   └── neutral_skybox.ktx
│   │   │   ├── groundShadow.filamat          # 地面阴影材质（已编译）
│   │   │   └── lit.mat                       # 通用 lit 材质源
│   │   ├── java/com/imotor/filamentdemo/
│   │   │   ├── MainActivity.java             # 入口 Activity，绑定车门按钮
│   │   │   ├── FilamentView2.java            # 自定义 SurfaceView，封装 Filament 渲染
│   │   │   ├── DoorController.java           # 车门控制接口
│   │   │   └── GroundFactory.java            # 地面 Mesh 构造工具
│   │   ├── res/layout/activity_main.xml      # UI 布局（FilamentView2 + 控制按钮）
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── groundShadow.mat                          # 地面阴影材质源（需用 matc 编译）
├── gradle/libs.versions.toml                 # 依赖版本目录
├── build.gradle
└── settings.gradle
```

## 环境要求

| 项目 | 版本 |
| --- | --- |
| Android Gradle Plugin | 8.5.1 |
| compileSdk / targetSdk | 34 |
| minSdk | 28 |
| Java | 11 |
| Filament | 1.57.1（`filament-android` / `gltfio-android` / `filament-utils-android`） |
| ABI | `armeabi-v7a`（如需 64 位，请在 `app/build.gradle` 的 `ndk.abiFilters` 中加入 `arm64-v8a`） |

## 构建与运行

1. 使用 Android Studio（Giraffe / Koala 及以上版本，兼容 AGP 8.5.x）打开项目根目录。
2. 等待 Gradle 同步完成，确认 [libs.versions.toml](file:///d:/workspace/AndroidStudioProjects/FilamentDemo/gradle/libs.versions.toml) 中的 Filament 依赖被成功下载。
3. 连接真机或启动模拟器（建议使用支持 OpenGL ES 3.0+ 的真机），运行 `app` 模块即可。

命令行构建：

```bash
./gradlew :app:assembleDebug
```

> 注意：默认仅打包 `armeabi-v7a` ABI，若设备为 64 位且未安装兼容层，请在 `defaultConfig.ndk` 中追加 `'arm64-v8a'` 后重新构建。

## 使用说明

进入应用后：

- **拖拽**：单指拖动旋转模型（ORBIT），地面以下方向会被相机限位逻辑拦截。
- **双指缩放**：双指张合进行 Zoom。
- **双指拖动**：双指同向滑动进行平移（PAN）。
- **`open/close left door`**：开/关左前门。
- **`open/close right door`**：开/关右前门。

## 关键实现说明

### 1. Filament 初始化

[FilamentView2](file:///d:/workspace/AndroidStudioProjects/FilamentDemo/app/src/main/java/com/imotor/filamentdemo/FilamentView2.java) 在静态块中调用：

```java
Filament.init();
Gltfio.init();
Utils.INSTANCE.init();
```

并通过 `ModelViewer` + `UiHelper` + `Manipulator` 完成 SurfaceView 的渲染绑定。

### 2. 模型加载与归一化

通过 `mModelViewer.loadModelGlb(buffer)` 加载 `assets/models/cartoon_sports_car.glb`；
使用 `transformToUnitCube` 或 `clearRootTransform`（根据 `automationEngine.getViewerOptions().autoInstancingEnabled`）控制根节点 Transform。

### 3. 车门动画

[FilamentView2](file:///d:/workspace/AndroidStudioProjects/FilamentDemo/app/src/main/java/com/imotor/filamentdemo/FilamentView2.java) 中：

- 加载完模型后，先记录左右车门各自的初始 Transform 矩阵，存入 `mMatrixMap`。
- 开门时：基于当前矩阵，先平移到铰链位置，再绕 Y 轴旋转 ±35°，最后平移回去，实现"绕铰链旋转"的开门效果。
- 关门时：直接将矩阵恢复为初始记录值。

车门 entity 序号在代码中以常量 `LEFT_DOOR_ENTITIES = {38, 39, 40}` 与 `RIGHT_DOOR_ENTITIES = {54, 55, 56}` 给出，与 `cartoon_sports_car.glb` 模型一一对应；如更换模型，需要通过 `showModelInfo()` 输出的实体名称重新映射。

### 4. 地面阴影

`groundShadow.mat` 是 unlit、`shadowMultiplier: true` 的透明材质，编译产物 `groundShadow.filamat` 放置于 `assets/` 下，由 `FilamentView2#addGround` 加载，并交由 [GroundFactory#createGroundPlane](file:///d:/workspace/AndroidStudioProjects/FilamentDemo/app/src/main/java/com/imotor/filamentdemo/GroundFactory.java) 构造一个仅接收阴影的大面积平面。
地面 Y 坐标会根据模型包围盒底部自动对齐，确保车轮恰好"贴地"。

> 重新生成 `groundShadow.filamat`：使用 Filament 工具链中的 `matc` 命令：
>
> ```bash
> matc -p mobile -a opengl -o app/src/main/assets/groundShadow.filamat groundShadow.mat
> ```

### 5. 相机防穿地

自定义 [CameraGestureListener](file:///d:/workspace/AndroidStudioProjects/FilamentDemo/app/src/main/java/com/imotor/filamentdemo/FilamentView2.java) 复刻 Filament `GestureDetector` 的 ORBIT/PAN/ZOOM 识别逻辑，并在 ORBIT 更新前判断相机 Y 坐标是否低于 `mGroundY + CAMERA_MIN_HEIGHT_OFFSET`，若是则中断 grab 并重置，避免相机下压穿地。

## 资源来源

- 环境贴图 `lightroom_14b.hdr` / `neutral_*.ktx` 来自 Filament 官方示例。
- 模型 `cartoon_sports_car.glb`、`executive_sedan.glb` 用于演示用途，版权归原作者所有。

## License

本项目遵循仓库根目录的 [LICENSE.md](file:///d:/workspace/AndroidStudioProjects/FilamentDemo/LICENSE.md)。第三方依赖与资源（Filament、glTF 模型、HDR 贴图等）请遵循其各自的许可协议。
