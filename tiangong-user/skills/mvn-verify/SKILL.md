---
name: mvn-verify
description: 在本仓库跑 Maven 构建与测试，并确认测试真的跑了（用户要求「编译」「跑测试」「验证」时用）
triggers:
  - 编译
  - 构建
  - 跑测试
  - mvn
allowed-tools:
  - Bash
  - Read
---

# 构建与测试

## 必用这三条命令

```bash
export PATH="/c/Users/WuXicheng/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:$PATH"
export JAVA_HOME='C:\Program Files\Java\jdk-21'          # Windows 形式，MSYS 路径会被判「未正确定义」
"/c/Program Files/maven/mvn/bin/mvn.cmd" -q -DskipTests compile > /tmp/build.log 2>&1
```

- **bash 下的 `mvn` 脚本会报找不到 `classworlds.Launcher`**，必须用 `mvn.cmd`。
- **PowerShell 会吞 stdout**，不要用它跑构建。
- 日志**不要写进 `target/`**：`mvn clean` 会把日志文件一起删掉，表现是「退出码 0 但文件不存在」。写 `/tmp`。

## 三个必踩的坑

1. **`-Dtest='A+B'` 不生效** —— Surefire 用**逗号**分隔类名。用了加号的表现是
   `exit=0` + `BUILD SUCCESS`，但**一个测试都没跑**。
2. **判断测试是否真跑了**，唯一依据是
   `target/surefire-reports/TEST-<全限定类名>.xml` 是否存在。退出码不可信。
3. **日志里的中文是 GBK 乱码**，`grep 中文关键词` 必失败。核查要用英文片段
   （类名、枚举名、`Tests run`、`BUILD`、行号）。

## 结束前必须确认

- `grep -E "Tests run" /tmp/build.log` 有输出，且数字符合预期
- 全量跑一遍 `mvn test`（约 70 秒），确认无回归
