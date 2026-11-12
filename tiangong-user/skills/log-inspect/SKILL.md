---
name: log-inspect
description: 排查 TianGong 运行日志的正确姿势（用户问「日志里为什么没有」「这个机制生效了吗」时用）
triggers:
  - 日志
  - 为什么没有
  - 生效了吗
  - 排查
allowed-tools:
  - Bash
  - Read
---

# 查日志

## 两个会让你误判的陷阱

1. **中文是 GBK 乱码** —— `grep "技能装配完成"` 一定匹配不到，会误判成「机制没触发」。
   核查必须用英文片段：类名、`Tests run`、`BUILD`、`SkillRegistry:73`、行号。
2. **TraceHandler 打印长文本会被截断** —— 曾据此误判「技能目录没进系统提示词」，
   实际拼进去了（141 字）。判断长内容是否生效，**加一行 debug 日志打印长度**，
   别靠截断的打印。

## 断言「某机制未生效」必须配对照组

否则证明力为零——你无法区分「机制失效」与「压根没走到那里」。

## 定位手法

- 找挂死的 JVM：`jcmd -l` → `jcmd <pid> Thread.print` → 看线程停在哪个方法。
- Windows 下 kill：`MSYS_NO_PATHCONV=1 taskkill /PID <pid> /F /T`
  （不加 `MSYS_NO_PATHCONV` 会被路径转换吃掉参数）。
