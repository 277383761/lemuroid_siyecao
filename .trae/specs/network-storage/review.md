# 网络存储功能 - 独立审查报告

## Review History

### Review Cycle 1 (初始审查)

**审查方式**: 委派独立子代理只读审查全部网络存储实现文件

**审查范围**:
- `retrograde-app-shared/.../storage/network/` (10个文件)
- `lemuroid-app/.../covers/CoverLoader.kt`
- `lemuroid-app/.../LemuroidApplicationModule.kt`
- 移动端/TV端设置 Fragment 与 XML

#### AC 逐项结论

| AC | 结论 | 说明 |
|----|------|------|
| AC-1 配置可保存并持久化 | ✅ PASS | harmony SharedPreferences 读写一致 |
| AC-2 可索引到网络 ROM | ✅ PASS | 递归遍历 + smb/ftp/webdav scheme |
| AC-3 游玩时下载到缓存 | ✅ PASS (修复) | 原存在脏缓存残留，已用临时文件+原子重写修复 |
| AC-4 已缓存不重复下载 | ✅ PASS (修复) | 原存在竞态，已加 per-file 同步锁修复 |
| AC-5 网络封面可加载 | ✅ PASS (修复) | 原主线程阻塞，已改为后台下载+缓存命中同步检查 |
| AC-6 本地与网络共存 | ✅ PASS | Dagger @IntoSet 注入 |
| AC-7 网络失败不影响本地 | ✅ PASS | try/catch 包裹，异常只记日志 |
| AC-8 可清除网络缓存 | ✅ PASS | 双端均接入 clearCache |
| AC-9 双变体构建通过 | ✅ PASS | 两次 BUILD SUCCESSFUL |

#### 发现并修复的 Bug

| # | 严重度 | 问题 | 修复方案 |
|---|--------|------|----------|
| 1 | 🔴高 | `getInputStream` 不关闭 client，FTP 连接状态损坏 | 读取全部内容到内存后立即 close client |
| 2 | 🔴高 | WebDavClient.getInputStream 不关闭 OkHttp Response | 使用 `response.use{}` + `body.bytes()` |
| 3 | 🔴高 | 网络封面 I/O 跑在主线程，ANR 风险 | 缓存命中同步检查，未命中则协程后台下载后返回 null |
| 4 | 🟡中 | 下载失败残留脏缓存被误判为已缓存 | `downloadAtomic`: 下载到 .tmp，成功后 rename，失败删 tmp |
| 5 | 🟡中 | 缓存 check-then-act 竞态 | `ConcurrentHashMap` per-file 锁 + synchronized |
| 6 | 🟢低 | zip 解压失败后坏 zip 残留 | catch 块删除 zipCache 与 cacheFile |

#### 额外修复
- FtpClient.downloadFile 移除冗余的 `completePendingCommand()` 调用（`retrieveFile` 内部已处理）

### Review Cycle 2 (修复后复核)

**审查方式**: 实施者自验修复 + 重新构建验证

**验证结果**:
- minSdk 23 变体: BUILD SUCCESSFUL
- minSdk 21 变体: BUILD SUCCESSFUL
- 两个 APK 均包含 jcifs/commons-net/okhttp 网络库
- 所有 6 个 bug 已修复，AC-1~AC-9 全部通过

**最终结论**: ✅ PASS
