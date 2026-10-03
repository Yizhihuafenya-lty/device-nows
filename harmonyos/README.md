# 设备信息 · HarmonyOS ArkUI 版

这是 Android 版设备信息工具的原生 HarmonyOS ArkUI/ArkTS 实现，采用 Stage 模型。

当前页面包含：

- 设备型号、系统版本、逻辑处理器数量
- 实时内存：总量、已用、可用、占用比例
- 实时电池电压、电流、功率
- 最近 60 秒功率曲线
- CPU 逐核心占用能力状态说明

CPU 逐核心真实占用是否可读取取决于 HarmonyOS 版本和设备开放的系统能力。ArkUI 页面不会伪造数据；系统未开放时显示“系统未开放”。

使用 DevEco Studio 打开本目录，等待同步依赖后运行 `entry` 模块。
