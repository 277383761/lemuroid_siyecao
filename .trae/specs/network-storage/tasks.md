# 网络存储 ROM 加载 - 实施计划

## Task 1: 添加网络协议依赖
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 在 `buildSrc/src/main/java/deps.kt` 中新增 `jcifs`（SMB）与 `commonsNet`（FTP）依赖坐标
  - 在 `retrograde-app-shared/build.gradle.kts` 中引入这两个依赖
- **Acceptance Criteria Addressed**: AC-9
- **Test Requirements**:
  - `rule` TR-1.1: `deps.kt` 与 `build.gradle.kts` 中存在 jcifs-ng 与 commons-net 依赖声明
- **Completion Evidence**:
  - `deps.kt` 第 136-137 行：`jcifs = "eu.agno3.jcifs:jcifs-ng:2.1.9"`, `commonsNet = "commons-net:commons-net:3.9.0"`
  - `retrograde-app-shared/build.gradle.kts` 第 45-46 行引入两个依赖
  - 构建成功，DEX 中检测到 17 处 jcifs/ftp/webdav 类引用

## Task 2: 网络存储配置模型与偏好存取
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 新建 `NetworkStorageConfig` 数据类
  - 新建 `NetworkStoragePreferences` 工具类（使用主 harmony SharedPreferences）
  - 定义 `NetworkProtocol` 枚举
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-2.1: `NetworkStoragePreferences.load(context)` 返回非空 config 对象，保存后可重新读出
- **Completion Evidence**:
  - `NetworkProtocol.kt`, `NetworkStorageConfig.kt`, `NetworkStoragePreferences.kt` 三文件创建于 `retrograde-app-shared/.../storage/network/`
  - 配置读写通过 harmony SharedPreferences，与偏好设置框架共用同一存储

## Task 3: 网络客户端接口与三协议实现
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 2
- **Description**:
  - `NetworkClient` 接口（继承 Closeable）
  - `SmbClient`（jcifs-ng）、`FtpClient`（commons-net）、`WebDavClient`（OkHttp PROPFIND）
  - `NetworkClientFactory` 工厂
- **Acceptance Criteria Addressed**: AC-2, AC-3
- **Test Requirements**:
  - `rule` TR-3.1: 三个客户端均实现 listFiles/getInputStream/downloadFile
  - `rule` TR-3.2: WebDavClient 使用 OkHttp 发送 PROPFIND 并解析响应
- **Completion Evidence**:
  - 五个文件创建于 `storage/network/`，编译通过
  - WebDavClient 使用 `Xml.newPullParser()` 解析 PROPFIND 响应

## Task 4: NetworkStorageProvider 实现
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 2, Task 3
- **Description**:
  - 实现 `StorageProvider`，uriSchemes = smb/ftp/webdav
  - `listBaseStorageFiles()` 递归遍历网络目录
  - `getGameRomFiles()` 下载 ROM 到缓存（支持 zip 解压与多文件）
  - 缓存命中直接复用
- **Acceptance Criteria Addressed**: AC-2, AC-3, AC-4, AC-7
- **Test Requirements**:
  - `rule` TR-4.1: listBaseStorageFiles 对未启用配置返回 emptyFlow
  - `rule` TR-4.2: getGameRomFiles 缓存命中时不调用 downloadFile
  - `rule` TR-4.3: listBaseStorageFiles 网络异常时 emit 空列表不抛异常
  - `rubric` TR-4.4: 缓存目录组织; scale 1-5; anchors 1=混乱/3=按游戏隔离/5=按游戏+协议隔离且支持清理; threshold >=4
- **Completion Evidence**:
  - `NetworkStorageProvider.kt` 创建，编译通过
  - 缓存目录：`cacheDir/network-games/` 与 `cacheDir/network-covers/`
  - listBaseStorageFiles 用 try/catch 包裹，异常时记录日志不抛出
  - getGameRomFiles 先检查 cacheFile.exists()，存在则直接返回
  - TR-4.4 评分：4/5（按游戏隔离，支持清除缓存）

## Task 5: Dagger 注册 NetworkStorageProvider
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 4
- **Description**:
  - 在 `LemuroidApplicationModule.kt` 中新增 `@Provides @IntoSet` 方法
- **Acceptance Criteria Addressed**: AC-6
- **Test Requirements**:
  - `rule` TR-5.1: Dagger 编译通过，provider 集合包含 network provider
- **Completion Evidence**:
  - `LemuroidApplicationModule.kt` 第 159-164 行注册 `networkStorageProvider`
  - Dagger 编译成功（kapt 通过）

## Task 6: CoverLoader 支持网络封面
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 4
- **Description**:
  - CoverLoader 增加 smb/ftp/webdav scheme 分支
  - 从网络共享同目录下载同名封面到本地缓存
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-6.1: findLocalCoverData 对 smb scheme 返回 File 或 null
  - `rule` TR-6.2: 已缓存的封面直接返回，不重复下载
- **Completion Evidence**:
  - `CoverLoader.kt` 中 `findLocalCoverFromNetwork` 方法实现
  - 先检查 coverCacheDir 本地缓存，命中则直接返回
  - 复用 `isCoverFile` 匹配逻辑

## Task 7: 移动端设置界面
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 2
- **Description**:
  - strings.xml、keys.xml 新增网络存储资源
  - `network_storage_settings.xml` 偏好页
  - `NetworkStorageSettingsFragment` 处理测试连接与清除缓存
  - 主 SettingsFragment 添加入口与导航
- **Acceptance Criteria Addressed**: AC-1, AC-8
- **Test Requirements**:
  - `rule` TR-7.1: 设置页可输入并保存所有配置字段
  - `rule` TR-7.2: 清除缓存按钮可删除 network-games 与 network-covers 目录
- **Completion Evidence**:
  - `network_storage_settings.xml` 创建，包含全部配置字段
  - `NetworkStorageSettingsFragment.kt` 创建，测试连接在 IO 线程执行
  - 导航图注册 `navigation_settings_network_storage`
  - 资源编译通过

## Task 8: TV 端设置界面
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 7
- **Description**:
  - tv_settings.xml 内嵌网络存储设置屏幕
  - TVSettingsFragment 处理测试连接与清除缓存
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-8.1: TV 设置中可见网络存储入口并可编辑配置
- **Completion Evidence**:
  - `tv_settings.xml` 新增 Network Storage PreferenceScreen
  - `TVSettingsFragment.kt` 新增 `handleNetworkTestConnection` 与 `handleNetworkClearCache`

## Task 9: 构建验证（双变体）
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1-8
- **Description**:
  - 构建 minSdk 23 与 minSdk 21 两个变体
  - 验证 APK 包含网络库依赖
- **Acceptance Criteria Addressed**: AC-9
- **Test Requirements**:
  - `rule` TR-9.1: minSdk 23 变体 BUILD SUCCESSFUL
  - `rule` TR-9.2: minSdk 21 变体 BUILD SUCCESSFUL
  - `rubric` TR-9.3: 依赖完整性; scale 1-5; anchors 1=缺依赖/3=部分/5=APK 含 jcifs+commons-net+okhttp; threshold >=5
- **Completion Evidence**:
  - minSdk 23: `BUILD SUCCESSFUL in 25s`，APK sdkVersion='23'
  - minSdk 21: `BUILD SUCCESSFUL in 1m 4s`，APK sdkVersion='21'
  - 两个 APK 均为 versionName='1.15.2'
  - DEX 中检测到 17 处 jcifs/ftpclient/webdav/propfind 类引用
  - 输出文件：
    - `output/lemuroid-1.15.2-android5-网络存储.apk`（177MB）
    - `output/lemuroid-1.15.2-android6plus-网络存储.apk`（177MB）
  - TR-9.3 评分：5/5
