# README
本项目为中山大学2022大学生创新训练项目《基于轻量级深度网络的健身动作评价》代码仓库，使用 Movenet 模型并部署在安卓端。

我们制作了7种姿势（标准深蹲、深蹲前倾、深蹲过浅、标准平板支撑、平板支撑臀部过高、平板支撑头部过高、站立）的数据集 `dataset.zip`，模型相关文件在 `rfa_project`中，权重文件为 `pose_classifier.tflite`, 安卓部署代码在 `android` 中。

更详细的使用手册可参考 https://github.com/linyiLYi/pose-monitor.

## 鸣谢
- https://github.com/linyiLYi/pose-monitor
- https://github.com/tensorflow/examples/tree/master/lite/examples/pose_estimation/android
