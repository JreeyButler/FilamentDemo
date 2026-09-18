# FilamentDemo

基于 Google [Filament](https://github.com/google/filament) 实时渲染引擎实现的 Android 端 3D 汽车展示 Demo。
应用加载 `glb` 格式的车辆模型，提供 IBL 环境光、展厅级画面（ACES/Bloom/SSR）、车灯聚光灯、地面、相机轨道控制、车门开合、
行驶驱动（原地展厅式轮子自转）、速度光束与相机抖动等交互特效，整体观感参考
[alphardex/su7-replica](https://github.com/alphardex/su7-replica)。

## 功能特性

- **glTF 模型加载**：通过 `gltfio-android` 加载 `assets/models/` 下的 `.glb` 模型
  （默认加载 `cartoon_sports_car_taillights.glb`，由 `tools/split_taillights.py` 从源模型拆分尾灯/转向灯材质生成）。
- **IBL 环境光与暗场天空盒**：`KTX1Loader` 加载 `assets/neutral/neutral_ibl.ktx`，初始强度 0、由开场动画 3 秒渐亮至 30k lux。
- **展厅级画面调校**：ACES 色调映射、Bloom 辉光、SSR 屏幕空间反射（地面映出车身）、MSAA 抗锯齿、接触阴影。
- **开场灯光渐亮动画**：主光/轮廓光/环境光/Bloom 在 3 秒内按 `power3.out` 渐亮（见 `ShowroomFx`）。
- **展厅布光与灯条**：暖白主光 + 冷蓝轮廓光；车顶两条自发光灯带（emissive quad + Bloom）营造 StartRoom 氛围。
- **聚光车灯**：左右两颗 `FOCUSED_SPOT` 聚光灯（`CarLightSystem`），可实时微调光轴与灯位，测试标记默认关闭。
- **尾灯 / 转向灯**：模型侧把中央尾灯条带、前后转向灯分别拆成独立材质
  `CARRERA_4096_TAILLIGHTS`（红色）与 `CARRERA_4096_TURNSIGNALS`（琥珀色）；
  运行时尾灯切换 `emissiveStrength` 开关（并叠加两颗红色点光做光溢出），转向灯按 ~0.4s 周期闪烁
  （`CarLightSystem.setupRearLights` / `update`）。
- **地面**：lit 材质深色哑光地板（`lit.filamat` + GroundFactory），接受车灯光斑，反射交给 SSR；地面 Y 自动对齐模型轮底。
- **行驶驱动**：速度条设定目标速度（0~200 km/h），轮子实体按 `ω = v / r` 自转，指数平滑模拟加减速（`DriveSystem`）。
- **速度光束**：60 根随机彩色发光条围绕车身平行滑动，随车速**非线性**后退/回绕（低速变化快、高速趋缓）；
  可见根数随速度分级
  （低速 5 根 → 满速 60 根）（`SpeedLinesFX` + Bloom 辉光）。
- **相机抖动**：行驶中相机眼点按速度叠加多频正弦噪声晃动，随速度渐显渐隐（`RenderPipeline`）。
- **车门开合交互**：`DoorController` 接口，对左右前门 entity 进行铰链平移 + Y 轴 ±35° 旋转。
- **车衣换装**：非破坏性替换外观车漆的 `baseColorMap`（复制材质实例），自动发现 `assets/skins/` 下所有贴图可循环切换，
  内饰/玻璃保持原样（`CarSkinSystem` + `assets/skins/`）。可用 `tools/project_skin.py` 把任意 JPG 经 3D 投影烘焙成车衣。
- **相机轨道控制**：ORBIT/PAN/ZOOM 自定义手势（`CameraGestureListener`），带阻尼与防穿地限制。
- **全屏沉浸**：Edge-to-Edge + 隐藏状态栏/导航栏（`NoActionBar`），下滑临时呼出。
- **省电渲染调度**：忙碌（行驶/动画/手势/光束在场）满帧 → 静止 10fps 冷却 3 秒 → 深睡停止调度，
  触摸/速度/车灯/车门入口唤醒。

## 架构

`FilamentView2`（装配 + 帧调度 + 省电调度 + 手势）将子系统拆分为单一职责模块：

| 类 | 职责 |
| --- | --- |
| [FilamentView2](app/src/main/java/com/imotor/filamentdemo/FilamentView2.java) | 装配、FrameCallback 帧调度、省电三段式、公共 API 转发、门控制 |
| [ShowroomFx](app/src/main/java/com/imotor/filamentdemo/ShowroomFx.java) | 开场"灯光渐亮"动画、主光/轮廓光、发光灯条 |
| [DriveSystem](app/src/main/java/com/imotor/filamentdemo/DriveSystem.java) | 速度状态（指数平滑）、轮子收集与自转、`MAX_SPEED`（200 km/h） |
| [SpeedLinesFX](app/src/main/java/com/imotor/filamentdemo/SpeedLinesFX.java) | 光束隧道全套：生成、随速后移/回绕、数量分级（rank 管理） |
| [CarLightSystem](app/src/main/java/com/imotor/filamentdemo/CarLightSystem.java) | 左右聚光前照灯（开关、光轴/位置实时调整）+ 尾灯开关 + 转向灯闪烁 |
| [RenderPipeline](app/src/main/java/com/imotor/filamentdemo/RenderPipeline.java) | 自管渲染：反射读取 ModelViewer 的 `swapChain`/`resourceLoader`，相机姿态 + 速度抖动 |
| [EmissiveQuadFactory](app/src/main/java/com/imotor/filamentdemo/EmissiveQuadFactory.java) | emissive 材质加载 + 发光四边形工厂（灯条/光束共用） |
| [CameraGestureListener](app/src/main/java/com/imotor/filamentdemo/FilamentView2.java)（内部类） | ORBIT/PAN/ZOOM 手势识别、阻尼、防穿地 |
| [CarSkinSystem](app/src/main/java/com/imotor/filamentdemo/CarSkinSystem.java) | 车衣换装：复制外观材质实例、替换 `baseColorMap`、纹理上传/释放 |
| [GroundFactory](app/src/main/java/com/imotor/filamentdemo/GroundFactory.java) | 地面 Mesh 构造工具 |
| [DebugHelper](app/src/main/java/com/imotor/filamentdemo/DebugHelper.java) | 调试辅助标记（坐标轴/灯位/落点，默认关闭） |

帧循环（`FilamentView2.FrameCallback`）每帧依次调用：
`ShowroomFx.updateIntro` → `DriveSystem.update` → `SpeedLinesFX.update` → `RenderPipeline.update`，
并按场景忙碌状态选择满帧 / 10fps 冷却 / 深睡三种调度。

## 项目结构

```
FilamentDemo/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── models/                       # glTF/GLB 模型（仅打包此目录）
│   │   │   │   ├── cartoon_sports_car_taillights.glb  # 当前使用（尾灯/转向灯已拆分）
│   │   │   │   └── executive_sedan.glb
│   │   │   ├── skins/                        # 车衣贴图（按模型 UV 图集制作）
│   │   │   │   ├── skin_sakura.jpg
│   │   │   │   └── skin_cyber.jpg
│   │   │   ├── neutral/neutral_ibl.ktx       # IBL 环境贴图
│   │   │   ├── emissive.filamat              # 发光四边形材质（灯条/光束）
│   │   │   ├── lit.filamat / lit.mat         # lit 地面材质（编译产物/源）
│   │   │   └── ...
│   │   ├── java/com/imotor/filamentdemo/
│   │   │   ├── MainActivity.java             # 入口 Activity，绑定 UI 控件（含全屏沉浸）
│   │   │   ├── FilamentView2.java            # 渲染入口：装配 + 帧调度 + 手势 + 门
│   │   │   ├── ShowroomFx.java               # 开场动画 + 展厅灯光/灯条
│   │   │   ├── DriveSystem.java              # 行驶系统
│   │   │   ├── SpeedLinesFX.java             # 速度光束特效
│   │   │   ├── CarLightSystem.java           # 车灯系统（前照灯/尾灯/转向灯）
│   │   │   ├── RenderPipeline.java           # 自管渲染管线（含相机抖动）
│   │   │   ├── EmissiveQuadFactory.java      # 发光四边形工厂
│   │   │   ├── CarSkinSystem.java            # 车衣换装（替换 baseColorMap）
│   │   │   ├── DoorController.java           # 车门控制接口
│   │   │   ├── GroundFactory.java            # 地面 Mesh 构造工具
│   │   │   └── DebugHelper.java              # 调试辅助标记
│   │   ├── res/layout/activity_main.xml      # UI（渲染视图 + 车门/车灯/光位调试/速度面板）
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── groundShadow.mat                          # 历史材质源（现地面已改用 lit.filamat）
├── tools/models/cartoon_sports_car.glb       # 源车模（不打包，供工具脚本生成/烘焙用）
├── tools/split_taillights.py                 # 开发脚本：拆分尾灯/转向灯几何材质，生成新模型（不打包）
├── tools/make_skin.py                        # 开发脚本：由 glb UV 图集生成示例车衣（不打包）
├── tools/project_skin.py                     # 开发脚本：JPG 3D 投影烘焙成车衣（推荐，不打包）
├── tools/jpg_to_skin.py                      # 开发脚本：JPG 直接贴 UV 图集（不打包）
├── gradle/libs.versions.toml                 # 依赖版本目录
├── build.gradle / settings.gradle
└── LICENSE.md
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
2. 等待 Gradle 同步完成，确认 `gradle/libs.versions.toml` 中的 Filament 依赖被成功下载。
3. 连接真机或启动模拟器（建议使用支持 OpenGL ES 3.0+ 的真机），运行 `app` 模块即可。

命令行构建：

```bash
./gradlew :app:assembleDebug
```

> 注意：默认仅打包 `armeabi-v7a` ABI，若设备为 64 位且未安装兼容层，请在 `defaultConfig.ndk` 中追加 `'arm64-v8a'` 后重新构建。

## 使用说明

进入应用后（左手边按钮列 + 底部速度面板）：

- **拖拽 / 双指缩放 / 双指平移**：相机轨道控制，防穿地。
- **open/close left/right door**：开/关左/右前门。
- **open/close front light**：开/关车头前照灯。
- **open/close rear light**：开/关中央红色尾灯（需相机转到车尾才看得到）。
- **open/close turn signal**：开/关前后琥珀色转向灯，开启后自动闪烁。
- **Light Dir / Light Pos**：车灯光轴与灯位调试面板（开发用）。
- **Skin**：循环切换车衣（原漆 → 樱花 → 霓虹），只改外观车漆，内饰/玻璃保持原样。
- **Start / Stop + 速度条**：启动行驶（原地展厅式，轮子自转 + 速度光束 + 相机抖动），
  速度条 0~200 km/h，行驶中可实时拖动调速。

## 关键实现说明

### 1. 初始化与渲染入口

`FilamentView2` 静态块初始化 Filament/gltfio/utils；`ModelViewer` + `UiHelper` + `Manipulator` 完成 SurfaceView 绑定。
渲染由 `RenderPipeline` 自管：

```
syncModelLoading() → manipulator.getLookAt(+速度抖动) → camera.lookAt → beginFrame/render/endFrame
```

> 背景：`ModelViewer.render()` 内部把"相机同步 → 绘制"焊死，外部无钩子插抖动，因此复制其流程并插入抖动。
> `swapChain` / `resourceLoader` 为 Kotlin private 成员，经反射读取；反射失败自动退化为 `ModelViewer.render`（无抖动但可渲染）。

### 2. 行驶驱动

- 收集轮子：按节点名包含 `brakes` 匹配 4 个轮子节点（各含 rim + tyre 子树）。
- 转向绕节点局部 X 轴（轮轴），`ω = v / WHEEL_RADIUS(0.33m)`，转向符号 `SPIN_SIGN = -1`（真机校准）。
- 加减速为指数平滑（`SPEED_SMOOTH_K`），复刻 su7-replica 的 `power2.out` 手感。

### 3. 速度光束

- 60 根随机颜色/长度的细长发光条（emissive unlit quad，正交双片十字保证任意视角可见）。
- 以**非线性速度**向 -X 后退（`v_beam = v_max·(1 − e^(−v/v_knee))`，低车速时光束加速明显、高速趋缓），出界回绕；
  可见根数 = `5 + 55 × speed/上限`（rank 随机序号，稳定不闪烁）。
- 全部可见性通过"逐根 add/remove 场景"管理，避免黑色不透明条遮挡背景。

### 4. 相机防穿地

`CameraGestureListener` 在 ORBIT 更新前判断相机 Y 是否低于 `mGroundY + CAMERA_MIN_HEIGHT_OFFSET`，
中断 grab 并重置，避免相机下压穿地。

### 5. 材质编译

资产中的 `.filamat` 均由 Filament 工具链 `matc` 编译。例如：

```bash
matc -p mobile -a opengl -o app/src/main/assets/lit.filamat lit.mat
matc -p mobile -a opengl -o app/src/main/assets/emissive.filamat emissive.mat
```

### 6. 自定义车衣

把任意 JPG/PNG 转成车衣。**推荐用 3D 投影烘焙**（图案在车身各面板间连贯），
备有"直接贴图集"脚本（简单但会呈碎片拼贴）。依赖：`pillow numpy`。

**推荐：3D 投影烘焙**（读取车模顶点/UV，把图片投影到车身曲面再烘回图集）

```bash
# 侧视投影（默认，适合痛车/侧绘）：图在车身两侧呈连贯画面
python3 tools/project_skin.py my_art.jpg

# 缩放/平移控制贴图位置（zoom>1 放大，center 为投影平面上的中心）
python3 tools/project_skin.py anime.png --project side --zoom 0.5 --center 0.5 0.45

# 前脸 / 顶视 / 柱面环绕
python3 tools/project_skin.py art.jpg --project front
python3 tools/project_skin.py art.jpg --project top
python3 tools/project_skin.py art.jpg --project cylindrical

# 投影范围外的车身填底色；--shading 控制保留原 AO/明暗
python3 tools/project_skin.py art.jpg --base-color "#101018" --shading 0.8
```

**简单模式：直接贴 UV 图集**（无 3D 投影，图片会被各 UV 岛切碎，适合无缝图案/纯色）

```bash
python3 tools/jpg_to_skin.py my_art.jpg --mode cover
python3 tools/jpg_to_skin.py logo.png --mode contain --base-color "#101018"
python3 tools/jpg_to_skin.py tile.jpg --mode tile --tile-size 1024
```

两者都会输出到 `app/src/main/assets/skins/<名字>.jpg`；重启 App 后该车衣自动出现在 `Skin` 按钮的循环列表里（无需改代码）。

> 原理：车模外观车漆共用一张 UV 图集，直接替换图集会让图片被 UV 岛切碎；
> `project_skin.py` 通过模型真实的顶点+UV 做“投影 → UV 空间光栅化 → 采样”，因此图案连贯。
> 若要极致对位的痛车，仍可在 Blender 中 "Project from View" 后烘焙再放入 `assets/skins/`。

### 7. 尾灯模型侧拆分（无需 Blender）

后灯组布局（用户确认）：
`|转向灯|倒车灯|-----尾灯(中央红条)-----|倒车灯|转向灯|`
其中两侧转向/倒车灯在 `Object_47`（材质 `CARRERA_4096_lamps`），
而**中央红色尾灯条带**其实是车身网格 `Object_5`（材质 `CARRERA_4096`）里的一段红色贴图三角面。

`tools/split_taillights.py` 用脚本做“模型侧修改”（需 Pillow，用于按贴图颜色识别红色条带）：

```bash
python3 tools/split_taillights.py
# 生成 models/cartoon_sports_car_taillights.glb：
#   Object_5：车身 primitive（CARRERA_4096）
#             + 中央尾灯 primitive（CARRERA_4096_TAILLIGHTS，93 索引，红 emissive、strength=0）
#   Object_47：倒车灯保留 CARRERA_4096_lamps
#             + 转向灯 primitive（CARRERA_4096_TURNSIGNALS，168 索引，琥珀 emissive、strength=0）
```

- 尾灯：世界坐标 `x<-1.2`、`|z|<0.56`、`y∈0.45~0.75` + 贴图红色度筛出 31 个中央红条三角面；
- 转向灯：后转向灯 `x<-1.0 且 |z|>0.75`（24 面）+ 前转向灯 `x>1.0`（车头琥珀灯带，32 面），共 56 面。

脚本仅追加索引 accessor/bufferView（不动顶点），克隆对应基础材质为独立发光材质并修正 glb 的
buffer/chunk 对齐；源模型保持不变，App 加载新模型。运行时 `CarLightSystem` 找到对应材质实例，
尾灯只改 `emissiveStrength` 开关、转向灯按周期闪烁，因此是**真实灯罩几何发光**，而非叠加的假四边形。

## 资源来源

- 环境贴图 `neutral_*.ktx` 来自 Filament 官方示例。
- 展厅特效参考 [alphardex/su7-replica](https://github.com/alphardex/su7-replica)（速度光束/相机抖动/灯条氛围）。
- 模型 `cartoon_sports_car.glb`、`executive_sedan.glb` 用于演示用途，版权归原作者所有。

## License

本项目遵循仓库根目录的 LICENSE.md。第三方依赖与资源（Filament、glTF 模型、HDR 贴图等）请遵循其各自的许可协议。
