# `adf-sample-agent-java` 智能体开发框架示例团队（东南大学 4W0T1me 小组）

本项目是基于 RoboCup 救援模拟 (RCRS) 智能体开发框架 (ADF) 的示例团队实现。

## 1. 环境要求

* Git
* OpenJDK Java 17

## 2. 下载项目

```bash
$ git clone https://github.com/roborescue/adf-sample-agent-java.git
```

## 3. 编译项目

```bash
$ cd adf-sample-agent-java
$ ./gradlew clean
$ ./gradlew build
```

## 4. 运行说明

`adf-sample-agent-java` 是基于 ADF 核心 (`adf-core-java`) 的 RCRS (`rcrs-server`) 示例团队实现。

运行 `adf-sample-agent-java` 前，需要先启动 `rcrs-server`（下载、编译和运行 `rcrs-server` 的说明请参考：<链接1>）。

启动 `rcrs-server` 后，打开新的终端窗口并执行：

```bash
$ cd adf-sample-agent-java
$ ./launch.sh -all
```

## 5. 技术支持

如需报告错误、提出改进建议或请求支持，请在 GitHub 上提交问题：<链接2>。

---
**东南大学 4W0T1me 小组**  
*智能体救援模拟研究团队*

---
### 版本信息
- 项目版本：adf-sample-agent-java v4.0
- 维护团队：东南大学 4W0T1me 小组
- 最后更新：2025年1月11日

---
*此文档由东南大学 4W0T1me 小组根据原始英文文档适配整理*