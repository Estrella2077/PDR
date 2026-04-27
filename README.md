# 九轴IMU-PDR示例

该目录下是一个独立的 Android Studio 工程，全部内容都放在 `codex/` 中，没有修改 `PDR-main` 和 `SensorCollectorWatch-main`。

## 已实现功能

- 采集手机九轴 IMU 中的三类核心原始数据：
  - 加速度计
  - 陀螺仪
  - 磁力计
- 使用 `PDR-main` 同类百度地图底图，支持手势缩放
- 支持三种定位模式：
  - `PDR`
  - `卫星导航`
  - `PDR + 卫星导航`
- 长按地图设置 PDR 起点
- 记录原始传感器数据到 `imu_raw.csv`
- 记录步级 PDR 输出到 `pdr_steps.csv`
- 横竖屏切换时不重建 Activity，会话不中断

## 数据保存位置

会话文件默认写入：

`Android/data/com.example.imupdr/files/Documents/imu_pdr_sessions/<时间戳>/`

每次会话包含：

- `imu_raw.csv`
- `pdr_steps.csv`

## 核心文件

- `app/src/main/java/com/example/imupdr/MainActivity.kt`
- `app/src/main/java/com/example/imupdr/PdrProcessor.kt`
- `app/src/main/java/com/example/imupdr/CsvSessionWriter.kt`
- `app/src/main/java/com/example/imupdr/Transer.java`

## 使用方式

1. 用 Android Studio 打开 `codex/`
2. 等待 Gradle 同步完成
3. 安装到带加速度计、陀螺仪、磁力计和 GNSS 的 Android 手机
4. 根据需要选择 `PDR / 卫星导航 / PDR+卫星`
5. 如需纯 PDR，建议先长按地图设置起点
6. 点击“开始采集”

## 当前限制

- 当前融合模式是轻量级位置校正，不是完整卡尔曼融合
- PDR 步长模型和峰值阈值仍需按设备和携带方式继续调参
- 航向仍以加速度计 + 磁力计为主，陀螺仪当前主要用于原始数据采集
