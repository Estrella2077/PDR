# 本次对话上下文摘要

生成日期：2026-04-15

## 用户目标

在 `D:\Android Studio\AndroidStudioProjects` 下，参考但不修改：

- `PDR-main`
- `SensorCollectorWatch-main`

新建一个独立工程，放在 `codex/` 目录中，实现：

- 手机九轴 IMU 采集
- 基于自采数据在手机上进行 PDR 实时定位
- 使用类似 `PDR-main` 的百度地图显示
- 支持 GNSS 与 PDR 融合

## 已完成的阶段性需求

### 第一阶段

- 在 `codex/` 下创建独立 Android Studio 工程
- 实现基础 IMU 采集
- 实现 CSV 落盘
- 实现基础 PDR 轨迹推算

### 第二阶段

- 接入与 `PDR-main` 同类的百度地图 SDK
- 界面改为中文为主
- 修复横屏旋转导致中断的问题
- 加入三种定位模式：
  - `PDR`
  - `GNSS`
  - `PDR+GNSS`

### 第三阶段

- 顶部加入状态栏安全留白
- UI 精简与重排
- 加入底部分页：
  - 地图
  - 传感器
  - 参考
- 参考页加入卫星云图

### 第四阶段

- 传感器页从数值卡片升级为实时曲线页
- 模式文案统一为：
  - `GNSS`
  - `PDR+GNSS`
- 参考页去掉说明文案
- 卫星云图按不同星座使用不同颜色绘制

### 第五阶段

- 删除独立“模型页”概念
- 第四个分页改为“设置”页，布局参考 `PDR-main`
- 设置页从上到下包含：
  - `PDR模型`
    - 仅保留身高一个参数
  - `轨迹绘制`
  - `定位导航模式`
  - `姿态检测`

### 第六阶段

- 增加保存传感器数据功能
- 增加导入数据文件功能
- 导入 `imu_raw.csv` 后可进行离线 PDR 后处理
- 导入 `pdr_steps.csv` 后可直接投影并绘制轨迹
- 导入结果会按当前模式在地图上显示：
  - `PDR`
  - `GNSS`
  - `PDR+GNSS`

### 第七阶段

- 将课程文档中的算法迁移进本工程
- 采用九轴 AHRS 替换旧的加速度计 + 磁力计航向链路
- 加入磁偏角修正，输出真北航向
- 步长估计改为“步频 + 身高补偿”为主
- 实时 PDR 与导入后处理统一为同一套算法链路

### 第八阶段

- UI 先做过一版增强样式，随后按用户要求回退到上一版整体风格
- 将“姿态检测”从设置页移到参考页
- 删除“启用姿态检测”开关
- 仅对参考页做小幅排版优化：
  - `卫星云图`
  - `GNSS 摘要`
  - `姿态摘要`

## 当前工程结构结论

当前主工程是：

- `D:\Android Studio\AndroidStudioProjects\codex`

这是一个独立工程，不应回写到：

- `PDR-main`
- `SensorCollectorWatch-main`

## 当前功能状态

### 地图页

- 百度地图正常接入
- 支持手势缩放
- 长按地图可设置 PDR 起点
- 显示当前模式、身高、步数、距离、GNSS 状态
- 可绘制实时轨迹
- 可显示导入数据后处理得到的轨迹

### 传感器页

- 加速度计三轴实时曲线
- 陀螺仪三轴实时曲线
- 磁力计三轴实时曲线
- PDR 趋势曲线
- 可开始/停止保存当前传感器会话

### 参考页

- 卫星云图
- 北斗 / GPS / GLONASS / GALILEO / QZSS / SBAS / IRNSS 等使用不同颜色
- 未参与定位的卫星通过透明度和点大小区分
- 姿态摘要展示：
  - 持机状态
  - `Pitch / Roll`
  - 航向估计

### 设置页

- `PDR模型`
  - 输入并应用身高参数
- `轨迹绘制`
  - 开关实时轨迹显示
  - 开关导入后处理轨迹显示
- `定位导航模式`
  - `PDR`
  - `GNSS`
  - `PDR+GNSS`

### 数据导入与后处理

- 支持导入保存过的 `imu_raw.csv`
- 支持导入保存过的 `pdr_steps.csv`
- 对 `imu_raw.csv` 进行离线 PDR 后处理并投影到地图
- 根据当前模式切换显示对应导入轨迹
- 离线后处理与实时链路使用同一套九轴算法

## 本次关键结果

- 重写 `MainActivity.kt`，清理旧乱码并统一中文状态文案
- 重写 `ImportedTrackParser.kt`，增加离线后处理能力
- 更新 `strings.xml`，统一“设置页 / 导入后处理 / 保存传感器数据”等文案
- 新增 `AhrsEstimator.kt`，实现九轴 AHRS 姿态解算
- 新增 `GeomagneticHelper.kt`，实现磁偏角修正
- 更新 `PdrProcessor.kt` 与 `PdrModelPreset.kt`，迁移九轴姿态、步频步长模型和节律约束
- 将姿态摘要从设置页移到参考页，并删除姿态检测开关
- 多次修改后 `assembleDebug` 均已成功通过

## 下次继续时建议优先读取的文件

- `codex/review/CURRENT_STATUS.md`
- `codex/review/KEY_FILES.md`
- `codex/review/NEXT_STEPS.md`

## 下次对话建议提示词

可以直接对我说：

“先阅读 `codex/review` 里的上下文摘要，再继续修改工程。”

或者：

“先读取 `codex/review/CURRENT_STATUS.md` 和 `codex/review/KEY_FILES.md`，然后继续上次工作。”
