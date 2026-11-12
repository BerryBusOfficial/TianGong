---
name: rollback-point
description: 在动手改代码前追加一个可回退的提交（用户说「先备份」「加回滚点」「怕改坏」时用）
triggers:
  - 回滚
  - 回滚点
  - 备份
  - 怕改坏
allowed-tools:
  - Bash
---

# 追加回滚点

`rollback-points` 分支只写快照，不动工作树。追加的是**当前 develop 的内容**，
所以顺序是：先提交改动 → 再追加快照。

```bash
TREE=$(git rev-parse 'develop^{tree}')
NEW=$(GIT_AUTHOR_NAME=梧桐 GIT_AUTHOR_EMAIL=mcht1023@outlook.com \
      GIT_COMMITTER_NAME=梧桐 GIT_COMMITTER_EMAIL=mcht1023@outlook.com \
      git commit-tree "$TREE" -p rollback-points -m "chore: 回滚基线 — <这次做了什么>")
git update-ref refs/heads/rollback-points "$NEW"
git push origin rollback-points
```

## 判断依据

- **带 `-p rollback-points` 就是 fast-forward**，普通 push 即可，**不需要 force**
  （2026-09-18 实测）。不带父提交才需要 `--force`。
- 追加后校验两边 tree 一致：
  `git rev-parse 'develop^{tree}'` 与 `rollback-points^{tree}` 相同即正确。

## 网络

本仓库 `.git/config` 已写死 `http.proxy=http://127.0.0.1:7897`，会压过环境变量里那个
坏的 `12978`，**不必再 export**。别写到全局配置——会影响用户的 Gitee 仓库。
