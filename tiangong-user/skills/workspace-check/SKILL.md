---
name: workspace-check
description: 巡检当前工作区的模块结构与构建状态（用户问「项目怎么样」「哪些模块」时用）
triggers:
  - 巡检
  - 模块
  - 构建状态
allowed-tools:
  - get_sys_time
  - Bash
  - Read
---

# 工作区巡检

按下列顺序检查，并**逐项给出结论**，不要只给一句「一切正常」：

1. 列出 Maven 模块（读根 `pom.xml` 的 `<modules>`），说明每个模块的职责。
2. 核对依赖方向是否仍是 `common → annotations → core`；出现反向依赖要立即指出。
3. 报告已知的未修缺陷（项目记忆里有清单）。

## 约束

- 不要执行 `mvn clean`：会删掉 `target/` 下尚未归档的日志与报告。
- 巡检结论里必须区分**实测**与**推测**，没跑过的命令不许说「已验证」。
