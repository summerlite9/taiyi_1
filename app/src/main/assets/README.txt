# 听力增强模型文件说明

此目录用于存放TensorFlow Lite模型文件，用于实时听力增强和降噪功能。

## 模型文件要求

1. 模型文件应命名为 `audio_enhancer.tflite`
2. 模型应支持以下输入和输出格式：
   - 输入：单声道音频帧，大小为512个浮点数
   - 输出：增强后的单声道音频帧，大小为512个浮点数

## 如何获取模型

您可以使用以下方法获取适合的模型：

1. 使用TensorFlow训练自定义的语音增强模型
2. 使用预训练的开源模型，如DTLN (Dual-signal Transformation LSTM Network)
3. 从TensorFlow Hub下载语音增强模型并转换为TensorFlow Lite格式

## 注意事项

当前应用使用简单的信号处理方法进行听力增强和降噪。添加TensorFlow Lite模型后，需要修改AudioEnhancer.java文件中的loadModelFile()方法和processFrame()方法，以使用模型进行处理。