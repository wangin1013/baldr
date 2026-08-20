# Baldr

> 巴德尔（Baldr），北欧神话中的光明之神。一款面向 Java 应用的 **AI 驱动性能诊断工具**。

Baldr 内嵌 Arthas + async-profiler，对目标 JVM 进行 CPU / 内存 / 锁采样，自动解析热点与调用路径，再交由大模型（DeepSeek / 豆包 等）生成结构化的性能优化建议，一键产出 Markdown 诊断报告。

## ✨ 特性

- **零安装采样**：内嵌 Arthas 与 async-profiler，无需在目标机预装任何工具。
- **AI 智能诊断**：接入多家大模型，自动分析 CPU 热点、调用链，输出根因、优化方案与示例代码。
- **多 Provider 架构**：内置 DeepSeek、豆包（火山方舟）与本地私有模型，可通过参数自由切换。
- **纯净输出**：自动屏蔽 JVM / async-profiler 的底层告警噪音（ByteBuddy 动态 Agent、CDS Sharing、framebuf 等）。
- **优雅降级**：AI 调用失败时仍照常输出性能热点数据，不影响采样结果。
- **单文件分发**：产物为一个 fat jar，`java -jar` 即可运行。

## 📦 下载安装

前往 [Releases](https://github.com/wangin1013/baldr/releases) 页面下载最新的 `baldr.jar`。
http://wangyijin9.cn/baldr.jar

命令行下载
```bash
curl -L -O http://wangyijin9.cn/baldr.jar
```



```bash
# 下载最新版本（将 wangin1013 替换为实际仓库路径）
curl -L -o baldr.jar https://github.com/wangin1013/baldr/releases/latest/download/baldr.jar

# 校验完整性（可选）
curl -L -O https://github.com/wangin1013/baldr/releases/latest/download/SHA256SUMS.txt
sha256sum -c SHA256SUMS.txt
```

**运行环境**：JDK 8 及以上（推荐 JDK 11+）。

## 🚀 快速开始

```bash
# 1. 配置大模型 API Key（以 DeepSeek 为例）
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx

# 2. 找到目标 Java 进程 PID
jps -l

# 3. 采样并生成诊断报告（默认 CPU 采样 30 秒，DeepSeek 分析）
java -jar baldr.jar --pid <PID>

# 4. 采样 60 秒、输出到文件
java -jar baldr.jar --pid <PID> --duration 60 --output report.md
```

## ⚙️ 命令行选项

| 选项 | 缩写 | 说明 | 默认值 |
| --- | --- | --- | --- |
| `--pid` | `-p` | 目标 Java 进程 PID（**必填**） | - |
| `--duration` | `-d` | 采样时长（秒） | `30` |
| `--event` | `-e` | 采样事件：`cpu` / `alloc` / `lock` | `cpu` |
| `--output` | `-o` | 报告输出文件路径，默认打印到控制台 | - |
| `--provider` | | 云端大模型：`deepseek` / `doubao` | `deepseek` |
| `--api-key` | | API Key，默认读取对应 provider 的环境变量 | - |
| `--endpoint` | | 自定义 API endpoint | provider 内置 |
| `--model` | | 模型名 | provider 内置 |
| `--local` | | 使用本地私有模型（Ollama/vLLM 等 OpenAI 兼容服务） | `false` |
| `--help` | `-h` | 查看帮助 | - |
| `--version` | `-V` | 查看版本 | - |

## 🤖 大模型 Provider

Baldr 采用多 Provider 架构，各厂商均为 OpenAI 兼容接口。API Key 优先取 `--api-key` 参数，否则读取对应环境变量。

### DeepSeek（默认）

```bash
export DEEPSEEK_API_KEY=sk-xxxx
java -jar baldr.jar --pid <PID>
# 等价于 --provider deepseek --model deepseek-v4-pro
```

### 豆包 / 火山方舟

```bash
export ARK_API_KEY=xxxx
java -jar baldr.jar --pid <PID> --provider doubao
# 默认模型 doubao-pro-32k，也可用推理接入点 ID：
java -jar baldr.jar --pid <PID> --provider doubao --model ep-xxxxxxxx
```

### 本地私有模型（离线 / 内网）

适用于对数据隐私、合规有要求的场景——模型部署在本地或内网，数据不出网。支持任意 OpenAI 兼容的本地推理服务（Ollama、vLLM、LM Studio、LocalAI 等）。

使用 `--local` 开关，默认指向 Ollama（`http://localhost:11434/v1/chat/completions`）：

```bash
# Ollama 示例（先 ollama pull qwen2.5-coder 并启动服务）
java -jar baldr.jar --pid <PID> --local --model qwen2.5-coder

# vLLM / LM Studio 等：用 --endpoint 指定地址
java -jar baldr.jar --pid <PID> --local \
  --endpoint http://localhost:8000/v1/chat/completions \
  --model your-local-model

# 也可用环境变量配置，命令更简洁
export LOCAL_LLM_ENDPOINT=http://localhost:11434/v1/chat/completions
java -jar baldr.jar --pid <PID> --local --model qwen2.5-coder
```

本地服务通常无需 API Key；若你的服务开启了鉴权，通过 `--api-key` 或环境变量 `LOCAL_LLM_API_KEY` 传入。

| 本地服务 | 默认 endpoint |
| --- | --- |
| Ollama | `http://localhost:11434/v1/chat/completions` |
| vLLM | `http://localhost:8000/v1/chat/completions` |
| LM Studio | `http://localhost:1234/v1/chat/completions` |

> 提示：本地模型需支持 JSON 输出能力（`response_format`）以获得最佳结构化诊断效果；能力较弱的模型可能触发降级为纯文本摘要。

### 使用自定义 OpenAI 兼容服务

任意 OpenAI 兼容网关都可通过 `--endpoint` 直接接入：

```bash
java -jar baldr.jar --pid <PID> --provider deepseek \
  --endpoint https://your-gateway/v1/chat/completions \
  --api-key sk-xxxx --model your-model
```

## 📄 报告示例

```markdown
# Baldr 性能分析报告

## CPU 热点 Top 10
| 方法 | 占比 | 样本数 |
| --- | ---: | ---: |
| com.example.OrderService.calc | 42.3% | 1270 |

## AI 诊断结论
- **瓶颈总结**: 订单金额计算存在重复的 BigDecimal 装箱开销
- **严重程度**: HIGH
- **根因分析**: ...

### 优化建议
...
```

## 🔧 从源码构建

需要 JDK 8+ 与 Maven 3.6+：

```bash
git clone https://github.com/wangin1013/baldr.git
cd baldr
mvn clean package -DskipTests
# 产物：baldr-cli/target/baldr.jar
```

## 🏷️ 发布新版本

本仓库通过 GitHub Actions 自动发布。推送形如 `vX.Y.Z` 的 tag 即触发构建并生成 Release：

```bash
# 更新版本号后
git tag v1.0.0
git push origin v1.0.0
```

工作流（`.github/workflows/release.yml`）会自动：

1. 用 JDK 8 执行 `mvn clean package`；
2. 产出 `baldr-<version>.jar` 与固定名 `baldr.jar`；
3. 生成 `SHA256SUMS.txt` 校验文件；
4. 创建 GitHub Release 并上传上述文件（含自动生成的 Release Notes）。

## 🗂️ 项目结构

```
baldr/
├── baldr-core/                    # 核心模块
│   └── src/main/java/com/wh/baldr/core/
│       ├── arthas/                # 内嵌 Arthas Agent（源码内联）
│       ├── collector/             # 性能采样收集器
│       ├── parser/                # collapsed 格式解析、热点/调用树构建
│       ├── analyzer/              # AI 诊断
│       │   └── provider/          # 多大模型 Provider（DeepSeek/豆包/本地）
│       ├── report/                # Markdown 报告生成
│       └── model/                 # 数据模型
├── baldr-cli/                     # 命令行入口（打包为 fat jar）
├── .github/workflows/             # CI 与 Release 工作流
└── pom.xml
```

## ⚠️ 注意事项

- Baldr 采用嵌入式 Arthas，attach 到**当前 JVM**，因此 async-profiler 实际采集的是运行 Baldr 的进程；`--pid` 主要用于校验与记录。
- 报告默认写入 `/tmp/baldr-ai/` 目录。
- JDK 21+ 首次动态加载 Agent 会有告警，Baldr 已通过进程自举与输出过滤自动屏蔽，无需手动加 JVM 参数。

## 📜 License

本项目内嵌了 [Arthas](https://github.com/alibaba/arthas)（Apache-2.0）相关源码，相应文件保留原作者署名。

---

_Baldr — 让 AI 照亮你的性能瓶颈。_