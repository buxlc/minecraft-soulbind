# 验证记录

日期：2026-10-22（Asia/Shanghai）。

- 编译目标：Spigot API `26.2-R0.1-SNAPSHOT`，Java 25；仅使用 Bukkit/Spigot 公共 API。
- 自动测试：`build.ps1` / Maven `clean verify`，19 项全部通过。
- 隔离服务端：已有的 LeavesX 26.2 / Bukkit API 26.2，Java 25，Vault 1.7.3。
- 服务端验证：`verification/probe/SoulBindProbe.java` 在真实服务端运行，47 条断言全部通过；使用实际 ItemStack、ItemMeta、YAML、菜单库存和插件事件分发，并以模拟玩家和测试 Vault 经济服务驱动业务。
- 覆盖：原 Lore 保留、自定义绑定 Lore、单件身份、可堆叠物品独立绑定和解绑后堆叠恢复、死亡保留、keepInventory 防重复、销毁事件注销、满背包入仓、召回撤销旧版本、重载后身份和设置、个人丢弃/拾取策略、GUI 提交预览、独立价格实际扣费、管理员入口权限、他人物品强制解绑。
- 原有服务器目录未改动；验证服务器监听本机独立端口 25596，检查完成后已正常关闭。

未进行真人客户端点击或与原有服务器全部第三方插件组合的在线兼容性测试。测试日志与结果位于 `verification/server/logs/latest.log`、`verification/server/probe-result.txt`。
