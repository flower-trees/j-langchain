# J-LangChain 架构图

1.0.19 总体架构初稿，包含中文、英文版本。`.drawio` 为可编辑源文件，同名 `.png` 为预览。中英文 README 的架构章节已引用对应版本的 PNG，并提供源文件链接。目录统一为 `docs/images`。

配色参考评测项目的总体架构图：蓝色用于流程编排和模型接入，紫色用于 Agent / Skill，绿色用于记忆控制和语音，暖橙色用于工具和检索。颜色用于区分能力类别，不表示发布状态或规划阶段。

采用浅蓝、浅紫、浅绿和暖橙色分组，存储使用圆柱；虚线箭头编号用于阅读定位，不代表固定执行顺序。图按当前代码中的流程编排、Agent 执行、记忆控制、模型接入、RAG 与 TTS 能力组织。

当前环境无法启动 draw.io 桌面程序或 Chrome，PNG 使用源文件中的坐标、文字和连接关系生成预览；字体、粗体和圆柱细节可能与 draw.io 原生导出略有差异。源文件可直接用 draw.io 打开，正式采用前建议原生导出：

```bash
/Applications/draw.io.app/Contents/MacOS/draw.io -x -f png -s 2 -b 16 \
  -o 'v1.0.19-图1_J-LangChain总体架构.png' \
  'v1.0.19-图1_J-LangChain总体架构.drawio'

/Applications/draw.io.app/Contents/MacOS/draw.io -x -f png -s 2 -b 16 \
  -o 'v1.0.19-fig1_J-LangChain-architecture-en.png' \
  'v1.0.19-fig1_J-LangChain-architecture-en.drawio'
```
