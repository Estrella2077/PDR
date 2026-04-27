# 关键文件索引

## 主逻辑

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\MainActivity.kt`

作用：

- 页面切换
- 地图逻辑
- GNSS 监听
- IMU 监听
- 设置页参数应用
- 实时轨迹绘制
- 导入后处理轨迹绘制
- 状态刷新

## PDR 核心

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\PdrProcessor.kt`

作用：

- 加速度 / 陀螺仪 / 磁场输入
- 步态检测
- AHRS 姿态接入
- 真北航向估计
- 步长估计
- 位置增量推算
- 支持按身高生成模型参数

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\AhrsEstimator.kt`

作用：

- 九轴 AHRS 姿态解算
- 根据加速度计 / 磁力计初始化姿态
- 根据陀螺仪积分并结合重力 / 地磁反馈更新四元数
- 输出 `yaw / pitch / roll`

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\GeomagneticHelper.kt`

作用：

- 获取地磁偏角
- 将磁北航向修正为真北航向

## 导入后处理

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\ImportedTrackParser.kt`

作用：

- 解析 `imu_raw.csv`
- 解析 `pdr_steps.csv`
- 对传感器数据执行与实时链路一致的离线 PDR 后处理
- 生成 `PDR / GNSS / PDR+GNSS` 三种导入轨迹结果

## 模型参数定义

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\PdrModelPreset.kt`

作用：

- 定义 PDR 参数对象
- 定义 AHRS 参数、步态阈值、步频步长模型参数
- 根据身高生成当前使用的模型参数

## 传感器曲线控件

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\TripleAxisChartView.kt`

作用：

- 绘制三轴实时曲线

## 卫星云图控件

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\SatelliteSkyView.kt`

作用：

- 按星座颜色绘制卫星云图

## 坐标转换

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\Transer.java`
- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\GPSPoint.java`
- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\OutputXY.java`

作用：

- WGS84 / 百度坐标互转
- 平面坐标转换

## 数据保存

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\java\com\example\imupdr\CsvSessionWriter.kt`

作用：

- 保存原始 IMU / GNSS 数据
- 保存 PDR 步级输出

## 主界面布局

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\res\layout\activity_main.xml`

作用：

- 四页布局：
  - 地图
  - 传感器
  - 参考
  - 设置
- 参考页内包含卫星云图、GNSS 摘要、姿态摘要

## 文案资源

- `D:\Android Studio\AndroidStudioProjects\codex\app\src\main\res\values\strings.xml`

作用：

- 页面标题
- 按钮文案
- 设置页文案
- 参考页标题与说明文案
